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
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** A PC the phone app knows. Its address identifies it (as on the PCs themselves). */
data class Pc(val name: String, val url: String, val home: Boolean)

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
 * The known PCs are learned from any reachable PC's PC list.
 *
 * Every call does network I/O, so none may run on the main thread.
 */
class KnownPcs(private val storage: Storage) {
    private val lock = Any()

    @Volatile
    private var pinned: Pair<String, OkHttpClient>? = null // the stored CA (PEM) and a client trusting only it

    /** The known PCs, the home PC first as its list shows it. */
    fun list(): List<Pc> = synchronized(lock) { storage.get(LIST)?.let(::parsePcs) ?: emptyList() }

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
            ?: listOf(Pc(qr.pc.host, qr.pc.toString().trimEnd('/'), home = false))
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
     * Checks which known PCs are reachable, and learns joined PCs (and new names) from the lists of
     * the reachable ones. PCs that aren't reachable are kept.
     */
    fun refresh(): List<PcStatus> {
        val connection = connection() ?: return emptyList()
        val http = connection.http.newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val states = mutableMapOf<String, PcState>()
        var toCheck = list()
        val pool = Executors.newFixedThreadPool(4)
        try {
            while (toCheck.isNotEmpty()) {
                val checks = toCheck.map { pc -> pc.url to pool.submit<Pair<PcState, List<Pc>?>> { check(http, pc.url, connection.session) } }
                for ((url, future) in checks) {
                    val (state, listed) = future.get()
                    states[url] = state
                    if (listed != null) learn(listed)
                }
                toCheck = list().filter { it.url !in states } // PCs just learned from a list
            }
        } finally {
            pool.shutdown()
        }
        return list().map { PcStatus(it, states[it.url] ?: PcState.NOT_REACHABLE) }
    }

    private fun check(http: OkHttpClient, url: String, session: String): Pair<PcState, List<Pc>?> {
        val base = url.toHttpUrlOrNull() ?: return PcState.NOT_REACHABLE to null
        return try {
            http.newCall(listRequest(base.resolve("/api/pcs")!!.toString(), session)).execute().use {
                when {
                    it.code == 401 -> PcState.PAIR_AGAIN to null
                    !it.isSuccessful -> PcState.NOT_REACHABLE to null // something else lives at that address now
                    else -> PcState.REACHABLE to it.json()?.let(::parsePcs)
                }
            }
        } catch (e: IOException) {
            PcState.NOT_REACHABLE to null
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

        /** [known] updated with [listed]: new names for known addresses, and new PCs at the end. */
        fun merge(known: List<Pc>, listed: List<Pc>): List<Pc> {
            val byUrl = listed.associateBy { it.url }
            val updated = known.map { byUrl[it.url] ?: it }
            return updated + listed.filter { pc -> updated.none { it.url == pc.url } }
        }

        fun Response.json(): JsonElement? = runCatching { Json.parseToJsonElement(body!!.string()) }.getOrNull()

        fun JsonElement.string(key: String): String? = (this as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull

        fun parsePcs(text: String): List<Pc> = runCatching { parsePcs(Json.parseToJsonElement(text)) }.getOrDefault(emptyList())

        fun parsePcs(json: JsonElement): List<Pc> = (json as? JsonArray).orEmpty().mapNotNull { item ->
            val url = item.string("url")?.trimEnd('/')?.takeIf { it.toHttpUrlOrNull()?.isHttps == true } ?: return@mapNotNull null
            val home = ((item as JsonObject)["home"])?.jsonPrimitive?.booleanOrNull ?: false
            Pc(item.string("name") ?: url, url, home)
        }

        fun writePcs(pcs: List<Pc>): String = JsonArray(pcs.map {
            buildJsonObject { put("name", it.name); put("url", it.url); put("home", it.home) }
        }).toString()
    }
}
