package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MultipartReader
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
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

    /** The PC library, newest first: file name -> contents. */
    val library = linkedMapOf<String, String>()

    /** Thumbnails (JPEG contents) of the PC library videos that have one, by video name. */
    val thumbnails = mutableMapOf<String, String>()

    /** Set to drop the connection halfway through sending a video. */
    var dropMediaDownloads = false

    /** The X links sent To PC, in the order they arrived. */
    val jobs = CopyOnWriteArrayList<String>()

    /** How long this PC takes to answer a link sent To PC. */
    var jobsDelayMillis = 0L

    /** The thumbnails the phone sent for videos without one: video name -> the form it posted. */
    val uploadedThumbnails = mutableMapOf<String, String>()

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

    private fun answer(request: RecordedRequest): MockResponse {
        val segments = request.requestUrl!!.pathSegments
        val loggedIn = request.getHeader("Authorization") == "Bearer $session"
        return when {
            segments == listOf("api", "jobs") ->
                if (!loggedIn) json("""{"detail":"Not logged in"}""", 401)
                else {
                    val url = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["url"]!!.jsonPrimitive.content
                    jobs += url
                    json(buildJsonObject { put("id", "job${jobs.size}"); put("url", url); put("status", "queued") }.toString())
                        .setHeadersDelay(jobsDelayMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            segments == listOf("api", "upload") ->
                if (!loggedIn) json("""{"detail":"Not logged in"}""", 401) else receiveUpload(request)
            segments.size == 2 && segments[0] in listOf("media", "thumb") || segments.firstOrNull() == "api" && segments.getOrNull(1) == "videos" ->
                if (loggedIn) answerLibrary(request, segments) else json("""{"detail":"Not logged in"}""", 401)
            else -> answerPairing(request)
        }
    }

    private fun answerLibrary(request: RecordedRequest, segments: List<String>): MockResponse {
        val name = segments.getOrNull(segments.lastIndex)
        return when {
            request.method == "GET" && segments == listOf("api", "videos") ->
                json(JsonArray(library.keys.mapIndexed { i, video -> videoJson(video, addedSecondsAgo = i * 60) }).toString())
            request.method == "POST" && segments.size == 4 && segments[3] == "thumb" && segments[2] in library -> {
                uploadedThumbnails[segments[2]] = request.body.readUtf8()
                thumbnails[segments[2]] = "the phone's thumbnail"
                json(videoJson(segments[2], 0).toString())
            }
            request.method == "DELETE" && segments.size == 3 && name in library -> {
                library.remove(name)
                thumbnails.remove(name)
                json("""{"ok":true}""")
            }
            request.method == "GET" && segments[0] == "media" && name in library -> {
                val body = library.getValue(name!!)
                MockResponse().setBody(body).apply {
                    if (dropMediaDownloads) socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
                }
            }
            request.method == "GET" && segments[0] == "thumb" ->
                thumbnails.entries.firstOrNull { thumbName(it.key) == name }?.let { MockResponse().setBody(it.value) }
                    ?: MockResponse().setResponseCode(404)
            else -> json("""{"detail":"Not found"}""", 404)
        }
    }

    /** An upload: the file goes into the PC library, newest first, under the name the phone gave it. */
    private fun receiveUpload(request: RecordedRequest): MockResponse {
        val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=")
        MultipartReader(request.body, boundary).use { reader ->
            while (true) {
                val part = reader.nextPart() ?: break
                val disposition = part.headers["Content-Disposition"].orEmpty()
                if ("name=\"file\"" !in disposition) continue
                val name = disposition.substringAfter("filename=\"").substringBefore('"')
                val content = part.body.readUtf8()
                val older = library.toMap()
                library.clear()
                library[name] = content
                library.putAll(older - name)
                return json(videoJson(name, 0).toString())
            }
        }
        return json("""{"detail":"No file"}""", 400)
    }

    private fun videoJson(name: String, addedSecondsAgo: Int) = buildJsonObject {
        put("name", name)
        put("title", name.substringBeforeLast('.'))
        put("uploader", "Someone")
        put("duration", 12.5)
        put("size", library.getValue(name).length)
        put("added", ADDED_NEWEST - addedSecondsAgo + 0.25)
        if (name in thumbnails) put("thumb", thumbName(name)) else put("thumb", null as String?)
    }

    private fun answerPairing(request: RecordedRequest): MockResponse = when (request.path) {
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

        /** When the newest PC library video was added, in seconds since the epoch, as the PC lists it. */
        const val ADDED_NEWEST = 1_700_000_000

        fun thumbName(video: String) = video.substringBeforeLast('.') + ".jpg"

        private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
    }
}
