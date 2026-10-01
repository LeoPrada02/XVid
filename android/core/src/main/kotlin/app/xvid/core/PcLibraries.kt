package app.xvid.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A video in a PC library, as that PC lists it. Its [name] (the file name on the PC) identifies it there.
 * [addedAt] is in milliseconds since the epoch; [thumb] is the PC's file name for its thumbnail, if it has one.
 */
data class PcVideo(
    val name: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Double?,
    val sizeBytes: Long,
    val addedAt: Long,
    val thumb: String?,
)

/** What a player needs to stream a PC library video: its address, and a client and headers that get in. */
class PcStream(val url: String, val http: OkHttpClient, val headers: Map<String, String>)

/**
 * The PC libraries of the known PCs: browse a reachable PC's library, stream from it, Save to phone,
 * and delete. Every call goes to the PC, so the listing is never stale; thumbnails are kept on the
 * phone as a cache, per PC, and those of videos no longer listed are removed.
 *
 * Blocking; callers run it off the main thread. Throws [PcException] when the PC can't do it.
 */
class PcLibraries(
    private val pcs: KnownPcs,
    private val phoneLibrary: PhoneLibrary,
    /** For the thumbnails of videos the PC has none for (uploads, files dropped into its folder). */
    private val frames: PcFrameGrabber,
    /** Private cache folder for the thumbnails. */
    private val thumbnailDir: File,
) {
    private val requests = PcRequests(pcs)

    /** [pc]'s PC library, newest first. */
    fun videos(pc: Pc): List<PcVideo> {
        val videos = requests.call(pc, Request.Builder().url(requests.url(pc) { addPathSegments("api/videos") })) { response ->
            val json = runCatching { Json.parseToJsonElement(response.body!!.string()) }.getOrNull()
            (json as? JsonArray ?: PcRequests.fail("The PC's library listing couldn't be read")).mapNotNull(::parseVideo)
        }.sortedByDescending { it.addedAt }
        removeThumbnailsExcept(pc, videos.map(::thumbnailName).toSet())
        return videos
    }

    /**
     * [video]'s thumbnail: the PC's, fetched the first time, or for a video the PC has none for, a
     * frame grabbed from it (which the PC gets too, as when the web app shows the video). Null when
     * there's none to be had.
     */
    @Synchronized
    fun thumbnail(pc: Pc, video: PcVideo): File? {
        val file = File(thumbnailFolder(pc), thumbnailName(video))
        if (file.isFile) return file
        val part = File(file.path + ".part")
        return try {
            file.parentFile.mkdirs()
            val thumb = video.thumb
            if (thumb != null) {
                requests.call(pc, Request.Builder().url(requests.url(pc) { addPathSegment("thumb").addPathSegment(thumb) })) { response ->
                    part.outputStream().use { response.body!!.byteStream().copyTo(it) }
                }
            } else {
                val length = frames.grab(stream(pc, video), part)
                runCatching { sendThumbnail(pc, video, part, length) } // the PC keeping it is only a bonus
            }
            if (part.length() > 0 && part.renameTo(file)) file else null
        } catch (e: Exception) {
            null
        } finally {
            part.delete()
        }
    }

    /** How to stream [video] from [pc]. Seeking works: the PC answers range requests. */
    fun stream(pc: Pc, video: PcVideo): PcStream {
        val connection = requests.connection()
        return PcStream(mediaUrl(pc, video).toString(), connection.http, mapOf(PcRequests.AUTHORIZATION to "Bearer ${connection.session}"))
    }

    /**
     * Save to phone: copies [video] from [pc] straight into the phone library (nothing is added if it
     * breaks off). [onProgress] gets a percentage, or null when the size isn't known.
     */
    fun saveToPhone(pc: Pc, video: PcVideo, onProgress: (Int?) -> Unit): PhoneVideo = try {
        phoneLibrary.write(video.name) { output ->
            val request = Request.Builder().url(mediaUrl(pc, video, download = true))
            requests.call(pc, request, client = { it.newBuilder().readTimeout(60, TimeUnit.SECONDS).build() }, notFound = gone(pc)) { response ->
                val body = response.body!!
                val total = body.contentLength().takeIf { it > 0 }
                var copied = 0L
                var lastPercent: Int? = -1
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val percent = total?.let { (copied * 100 / it).toInt().coerceIn(0, 100) }
                        if (percent != lastPercent) onProgress(percent).also { lastPercent = percent }
                    }
                }
                if (total != null && copied != total) throw IOException("The PC stopped sending the video")
            }
        }
    } catch (e: PcException) {
        throw e
    } catch (e: Exception) {
        throw PcException(PcException.Reason.FAILED, "Couldn't save the video: ${e.message ?: e.javaClass.simpleName}")
    }

    /** Deletes [video] from [pc]'s PC library. */
    fun delete(pc: Pc, video: PcVideo) {
        requests.call(pc, Request.Builder().url(requests.url(pc) { addPathSegments("api/videos").addPathSegment(video.name) }).delete(), notFound = gone(pc)) {}
    }

    private fun gone(pc: Pc) = "That video isn't in ${pc.name}'s PC library anymore"

    private fun mediaUrl(pc: Pc, video: PcVideo, download: Boolean = false): HttpUrl = requests.url(pc) {
        addPathSegment("media").addPathSegment(video.name)
        if (download) addQueryParameter("download", "true")
    }

    /** Sends the frame in [jpeg] to [pc] as [video]'s thumbnail, as the web app does. */
    private fun sendThumbnail(pc: Pc, video: PcVideo, jpeg: File, lengthSeconds: Double?) {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "thumbnail.jpg", jpeg.asRequestBody("image/jpeg".toMediaType()))
            .apply { lengthSeconds?.let { addFormDataPart("duration", it.toString()) } }
            .build()
        val target = requests.url(pc) { addPathSegments("api/videos").addPathSegment(video.name).addPathSegment("thumb") }
        requests.call(pc, Request.Builder().url(target).post(form)) {}
    }

    @Synchronized // not while a thumbnail is on its way in
    private fun removeThumbnailsExcept(pc: Pc, keep: Set<String>) {
        thumbnailFolder(pc).listFiles().orEmpty().filter { it.name !in keep }.forEach { it.delete() }
    }

    private fun thumbnailFolder(pc: Pc) = File(thumbnailDir, hash(pc.id))

    private companion object {
        /** Changes when the PC replaces the video or its thumbnail. */
        fun thumbnailName(video: PcVideo) = hash("${video.name}\n${video.thumb}\n${video.addedAt}\n${video.sizeBytes}") + ".jpg"

        fun hash(text: String) =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

        fun parseVideo(item: JsonElement): PcVideo? {
            val json = item as? JsonObject ?: return null
            fun string(key: String) = json[key]?.jsonPrimitive?.contentOrNull
            val name = string("name")?.takeIf { it.isNotBlank() } ?: return null
            return PcVideo(
                name = name,
                title = string("title") ?: name,
                uploader = string("uploader"),
                durationSeconds = json["duration"]?.jsonPrimitive?.doubleOrNull,
                sizeBytes = json["size"]?.jsonPrimitive?.longOrNull ?: 0,
                addedAt = ((json["added"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                thumb = string("thumb"),
            )
        }
    }
}
