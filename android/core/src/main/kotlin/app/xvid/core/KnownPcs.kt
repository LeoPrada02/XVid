package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * A PC the phone app knows. Its [id] identifies it; its address ([url]) can change, and is learned
 * again from the network. PCs remembered from before PCs had ids use their address as their id.
 */
data class Pc(val id: String, val name: String, val url: String, val home: Boolean) {
    private val hasId get() = id != url

    /** Whether [other] is this PC: the same id, or the same address for a PC without an id yet. */
    internal fun sameAs(other: Pc) = id == other.id || (url == other.url && !(hasId && other.hasId))

    /** Whether [other] is surely another PC: both have ids, and they differ. */
    internal fun isAnotherPcThan(other: Pc) = hasId && other.hasId && id != other.id
}

enum class PcState {
    REACHABLE,

    /** Off, not running XVid, or the phone is on another network. Normal away from home, not an error. */
    NOT_REACHABLE,

    /** Reachable, but it no longer accepts the phone's login (its token changed): pair again. */
    PAIR_AGAIN,
}

data class PcStatus(val pc: Pc, val state: PcState)

sealed interface PairingResult {
    /** Paired: [pcs] is every PC the phone now knows (the paired PC and the PCs that joined it). */
    data class Paired(val pcs: List<Pc>) : PairingResult

    /** The scanned QR code isn't an XVid pairing code. */
    data object NotAPairingCode : PairingResult

    /** The PC couldn't be reached (another Wi-Fi, or XVid isn't running there). */
    data object NotReachable : PairingResult

    /** The PC's certificate doesn't match the fingerprint in the QR code. Nothing was sent to it. */
    data object FingerprintMismatch : PairingResult

    /** The PC refused the code (expired, already used, too many wrong attempts). */
    data class CodeRefused(val reason: String) : PairingResult
}

/** What talking to any known PC needs: a client that trusts only their CA, and the login. */
class PcConnection(val http: OkHttpClient, val session: String, val mediaKey: String)

/**
 * Pairing and the known PCs. Pairing with one PC trusts its private CA (for XVid's own connections
 * only) and logs in; since joined PCs share the home PC's CA and login, that covers all of them.
 * The known PCs are learned from any reachable PC's PC list, and their current addresses from
 * [discovery], falling back to the last address each was reached at.
 *
 * Every call does network I/O, so none may run on the main thread.
 */
class KnownPcs(private val storage: Storage, private val discovery: PcDiscovery) {
    private val lock = Any()

    @Volatile
    private var pinned: Pair<String, OkHttpClient>? = null // the stored CA (PEM) and a client trusting only it

    /** The known PCs, the home PC first as its list shows it. */
    fun list(): List<Pc> = synchronized(lock) { storage.get(LIST)?.let(::parsePcs) ?: emptyList() }

    /** The known PC with [id], or null when the phone doesn't know it (anymore). */
    fun find(id: String?): Pc? = list().firstOrNull { it.id == id }

    /** For talking to the PCs, or null before pairing. */
    fun connection(): PcConnection? {
        val caPem = storage.get(CA) ?: return null
        val session = storage.get(SESSION) ?: return null
        val http = pinned?.takeIf { it.first == caPem }?.second
            ?: PcTrust.pinnedTo(PcTrust.parsePem(caPem), BASE).also { pinned = caPem to it }
        return PcConnection(http, session, storage.get(MEDIA_KEY).orEmpty())
    }

    /** Pairs with the PC in the scanned QR code [qrText]. */
    fun pair(qrText: String): PairingResult {
        val qr = PairingQr.parse(qrText) ?: return PairingResult.NotAPairingCode

        // 1. Get the PC's CA certificate, and check it against the fingerprint before trusting anything.
        val caPem = try {
            PcTrust.unverified(PAIRING).newCall(Request.Builder().url(qr.pc.resolve("/api/pair/ca")!!).build())
                .execute().use { if (it.isSuccessful) it.peekBody(MAX_CA_BYTES).string() else null }
        } catch (e: IOException) {
            null
        } ?: return PairingResult.NotReachable
        val ca = try {
            PcTrust.parsePem(caPem)
        } catch (e: CertificateException) {
            return PairingResult.FingerprintMismatch
        }
        if (PcTrust.fingerprint(ca) != qr.fingerprint) return PairingResult.FingerprintMismatch

        // 2. Redeem the code over a connection that trusts only that CA: this fails unless the PC's own
        //    certificate is signed by it, so the code only ever goes to the real PC.
        val http = PcTrust.pinnedTo(ca, PAIRING)
        val body = buildJsonObject { put("code", qr.code) }.toString().toRequestBody(JSON)
        val login = try {
            http.newCall(Request.Builder().url(qr.pc.resolve("/api/pair/redeem")!!).post(body).build()).execute().use {
                val json = it.json()
                if (!it.isSuccessful) return PairingResult.CodeRefused(json?.string("detail") ?: "The PC answered ${it.code}")
                (json?.string("session") ?: return PairingResult.NotReachable) to json.string("media_key").orEmpty()
            }
        } catch (e: SSLException) {
            return PairingResult.FingerprintMismatch
        } catch (e: IOException) {
            return PairingResult.NotReachable
        }

        // 3. Remember it all, and learn the PCs that joined it.
        val listed = fetchList(http, qr.pc.toString().trimEnd('/'), login.first)
            ?: qr.pc.toString().trimEnd('/').let { listOf(Pc(it, qr.pc.host, it, home = false)) }
        synchronized(lock) {
            val sameHome = storage.get(CA)?.let { runCatching { PcTrust.fingerprint(PcTrust.parsePem(it)) }.getOrNull() } == qr.fingerprint
            storage.put(CA, caPem)
            storage.put(SESSION, login.first)
            storage.put(MEDIA_KEY, login.second)
            storage.put(LIST, writePcs(if (sameHome) merge(list(), listed) else listed))
        }
        return PairingResult.Paired(list())
    }

    /**
     * Checks which known PCs are reachable, at the address [discovery] found each at or else its last
     * known address, and learns joined PCs (and new names) from the lists of the reachable ones.
     * PCs that aren't reachable are kept.
     */
    fun refresh(): List<PcStatus> {
        val connection = connection() ?: return emptyList()
        val http = connection.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val found = runCatching { discovery.find() }.getOrDefault(emptyList()).associate { it.id to it.url }
        val states = mutableMapOf<String, PcState>()
        val reachedAt = mutableMapOf<String, String>()
        var toCheck = list()
        val pool = Executors.newFixedThreadPool(4)
        try {
            while (toCheck.isNotEmpty()) {
                val checks = toCheck.map { pc ->
                    val addresses = listOfNotNull(found[pc.id], pc.url).distinct()
                    pc.id to pool.submit<Check> { checkAt(http, pc, addresses, connection.session) }
                }
                for ((id, future) in checks) {
                    val check = future.get()
                    // A PC remembered from before ids lists itself with its id: keep its result under that too.
                    for (key in setOfNotNull(id, check.itself?.id)) {
                        states[key] = check.state
                        check.url?.let { reachedAt[key] = it }
                    }
                    check.listed?.let(::learn)
                }
                toCheck = list().filter { it.id !in states } // PCs just learned from a list
            }
        } finally {
            pool.shutdown()
        }
        // Where a PC was just reached is where it is now, whatever older lists from other PCs say.
        synchronized(lock) { storage.put(LIST, writePcs(list().map { pc -> reachedAt[pc.id]?.let { pc.copy(url = it) } ?: pc })) }
        return list().map { PcStatus(it, states[it.id] ?: PcState.NOT_REACHABLE) }
    }

    /** How checking a PC went: where it answered, if it did, and its PC list (which lists the PC itself first). */
    private class Check(val state: PcState, val url: String? = null, val listed: List<Pc>? = null) {
        val itself: Pc? get() = listed?.firstOrNull()?.takeIf { url != null }
    }

    /** Checks [pc] at each of [addresses] in turn, until it answers at one. */
    private fun checkAt(http: OkHttpClient, pc: Pc, addresses: List<String>, session: String): Check {
        var check = Check(PcState.NOT_REACHABLE)
        for (url in addresses) {
            check = check(http, pc, url, session)
            if (check.state != PcState.NOT_REACHABLE) break
        }
        return check
    }

    private fun check(http: OkHttpClient, pc: Pc, url: String, session: String): Check {
        val base = url.toHttpUrlOrNull() ?: return Check(PcState.NOT_REACHABLE)
        return try {
            http.newCall(listRequest(base.resolve("/api/pcs")!!.toString(), session)).execute().use {
                val listed = if (it.isSuccessful) it.json()?.let(::parsePcs) else null
                when {
                    it.code == 401 -> Check(PcState.PAIR_AGAIN, url)
                    listed == null -> Check(PcState.NOT_REACHABLE) // something else lives at that address now
                    listed.firstOrNull()?.isAnotherPcThan(pc) == true -> Check(PcState.NOT_REACHABLE, listed = listed) // another PC took it
                    else -> Check(PcState.REACHABLE, url, listed)
                }
            }
        } catch (e: IOException) {
            Check(PcState.NOT_REACHABLE)
        }
    }

    private fun fetchList(http: OkHttpClient, url: String, session: String): List<Pc>? = try {
        http.newCall(listRequest("$url/api/pcs", session)).execute().use { if (it.isSuccessful) it.json()?.let(::parsePcs) else null }
    } catch (e: IOException) {
        null
    }

    private fun listRequest(url: String, session: String) =
        Request.Builder().url(url).header("Authorization", "Bearer $session").build()

    private fun learn(listed: List<Pc>) = synchronized(lock) { storage.put(LIST, writePcs(merge(list(), listed))) }

    private companion object {
        const val CA = "pcs.ca"
        const val SESSION = "pcs.session"
        const val MEDIA_KEY = "pcs.mediaKey"
        const val LIST = "pcs.list"
        const val MAX_CA_BYTES = 64L * 1024
        val JSON = "application/json".toMediaType()

        val BASE = OkHttpClient()
        val PAIRING: OkHttpClient = BASE.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()

        /** [known] updated with [listed]: new names and addresses for known PCs, and new PCs at the end. */
        fun merge(known: List<Pc>, listed: List<Pc>): List<Pc> {
            val updated = known.map { pc -> listed.firstOrNull { it.sameAs(pc) } ?: pc }
            return updated + listed.filter { pc -> updated.none { it.sameAs(pc) } }
        }

        fun Response.json(): JsonElement? = runCatching { Json.parseToJsonElement(body!!.string()) }.getOrNull()

        fun JsonElement.string(key: String): String? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

        fun parsePcs(text: String): List<Pc> = runCatching { parsePcs(Json.parseToJsonElement(text)) }.getOrDefault(emptyList())

        fun parsePcs(json: JsonElement): List<Pc> = (json as? JsonArray).orEmpty().mapNotNull { item ->
            val url = item.string("url")?.trimEnd('/')?.takeIf { it.toHttpUrlOrNull()?.isHttps == true } ?: return@mapNotNull null
            val home = ((item as JsonObject)["home"])?.jsonPrimitive?.booleanOrNull ?: false
            Pc(item.string("id")?.takeIf { it.isNotBlank() } ?: url, item.string("name") ?: url, url, home)
        }

        fun writePcs(pcs: List<Pc>): String = JsonArray(pcs.map {
            buildJsonObject { put("id", it.id); put("name", it.name); put("url", it.url); put("home", it.home) }
        }).toString()
    }
}
