package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** A private certificate authority, like the one setup.cmd makes with mkcert on the home PC. */
class FakeCa(name: String = "XVid test CA") {
    val held: HeldCertificate = HeldCertificate.Builder().commonName(name).certificateAuthority(0).build()
    val pem: String get() = held.certificatePem()
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(held.certificate.encoded).joinToString("") { "%02x".format(it) }
}

/**
 * A PC's HTTP API on a fake HTTPS server: its certificate is signed by [signedBy] and made for
 * [certifiedFor] (its address by default), it hands out [servesCa] from /api/pair/ca, redeems [code]
 * once, and lists itself then [others] as its PC list. A PC that moved to a new address is a new
 * FakePc with the same [id].
 */
class FakePc(
    var name: String,
    signedBy: FakeCa,
    val isHome: Boolean = true,
    var servesCa: FakeCa = signedBy,
    var code: String = "one-time-code",
    val id: String = "id-of-$name",
    certifiedFor: List<String>? = null,
    port: Int = 0,
) {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    var others: List<Pc> = emptyList()
    var session = SESSION // the session this PC accepts (it changes if the PC gets a new token)

    init {
        server.start(port)
        val leaf = HeldCertificate.Builder()
            .apply { (certifiedFor ?: listOf(server.hostName, "localhost")).forEach(::addSubjectAlternativeName) }
            .signedBy(signedBy.held)
            .build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf).build().sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return answer(request)
            }
        }
    }

    val port: Int get() = server.port

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** This PC as a PC list shows it. */
    val listed: Pc get() = Pc(id, name, url, isHome)

    /** The text of the QR code this PC's Add a phone dialog shows. */
    fun qr(fingerprint: String = servesCa.fingerprint, code: String = this.code) =
        "xvid://pair?pc=${enc(url)}&code=${enc(code)}&fp=$fingerprint"

    fun paths() = requests.map { it.path }

    fun stop() = server.shutdown()

    private fun answer(request: RecordedRequest): MockResponse = when (request.path) {
        "/api/pair/ca" -> MockResponse().setBody(servesCa.pem)
        "/api/pair/redeem" -> {
            val given = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["code"]?.jsonPrimitive?.content
            if (given == code) {
                code = "used"
                json(buildJsonObject { put("ok", true); put("session", SESSION); put("media_key", MEDIA_KEY) }.toString())
            } else {
                json("""{"detail":"This pairing code expired or was already used. Scan a new QR code on the PC."}""", 401)
            }
        }
        "/api/pcs" ->
            if (request.getHeader("Authorization") != "Bearer $session") json("""{"detail":"Not logged in"}""", 401)
            else json(JsonArray((listOf(listed) + others).map(::pcJson)).toString())
        else -> MockResponse().setResponseCode(404)
    }

    private fun pcJson(pc: Pc) = buildJsonObject { put("id", pc.id); put("name", pc.name); put("url", pc.url); put("home", pc.home) }

    private fun json(body: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    companion object {
        const val SESSION = "session-for-every-joined-pc"
        const val MEDIA_KEY = "media-key"

        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
    }
}
