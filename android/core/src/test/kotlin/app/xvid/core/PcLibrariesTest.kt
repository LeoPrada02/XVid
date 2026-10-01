package app.xvid.core

import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcLibrariesTest {
    @TempDir
    lateinit var temp: File

    private val ca = FakeCa()
    private val home = FakePc("home", ca)
    private val knownPcs = KnownPcs(FakeStorage(), FakeDiscovery())
    private val phoneLibrary = FakePhoneLibrary()
    private val frames = FakeFrameGrabber()
    private val libraries by lazy { PcLibraries(knownPcs, phoneLibrary, frames, File(temp, "thumbnails")) }

    init {
        knownPcs.pair(home.qr())
    }

    @AfterEach
    fun stop() = home.stop()

    private fun pc(): Pc = knownPcs.list().single()

    private fun video(name: String) = libraries.videos(pc()).single { it.name == name }

    // Listing

    @Test
    fun `lists a PC library newest first, with what the PC knows of each video`() {
        home.library["newest.mp4"] = "newest video"
        home.library["older one.mp4"] = "older video"

        val videos = libraries.videos(pc())

        assertEquals(listOf("newest.mp4", "older one.mp4"), videos.map { it.name })
        val newest = videos.first()
        assertEquals("newest", newest.title)
        assertEquals("Someone", newest.uploader)
        assertEquals(12.5, newest.durationSeconds)
        assertEquals("newest video".length.toLong(), newest.sizeBytes)
        assertEquals(FakePc.ADDED_NEWEST * 1000L + 250, newest.addedAt)
    }

    @Test
    fun `an empty PC library lists nothing`() {
        assertEquals(emptyList(), libraries.videos(pc()))
    }

    @Test
    fun `thumbnails come from the PC and are kept on the phone`() {
        home.library["with thumb.mp4"] = "video"
        home.library["without.mp4"] = "video"
        home.thumbnails["with thumb.mp4"] = "jpeg bytes"

        val withThumb = video("with thumb.mp4")
        val thumbnail = assertNotNull(libraries.thumbnail(pc(), withThumb))
        assertEquals("jpeg bytes", thumbnail.readText())
        assertEquals(emptyList(), frames.grabbed) // the PC's own is used

        home.stop() // asked again, it's not fetched again
        assertEquals("jpeg bytes", libraries.thumbnail(pc(), withThumb)?.readText())
    }

    @Test
    fun `a video without a thumbnail gets one from a frame, and the PC gets it too`() {
        home.library["uploaded clip.mp4"] = "video"

        val thumbnail = assertNotNull(libraries.thumbnail(pc(), video("uploaded clip.mp4")))

        assertEquals(listOf("${home.url}/media/uploaded%20clip.mp4"), frames.grabbed)
        assertEquals("frame of ${home.url}/media/uploaded%20clip.mp4", thumbnail.readText())
        val form = assertNotNull(home.uploadedThumbnails["uploaded clip.mp4"])
        assertTrue("frame of ${home.url}/media/uploaded%20clip.mp4" in form)
        assertTrue("name=\"duration\"" in form && "42.0" in form)
        assertEquals("the phone's thumbnail", libraries.thumbnail(pc(), video("uploaded clip.mp4"))?.readText())
    }

    @Test
    fun `a video no frame can be grabbed from has no thumbnail`() {
        home.library["broken.mp4"] = "not really a video"
        frames.failWith = "no frame"

        assertNull(libraries.thumbnail(pc(), video("broken.mp4")))
        assertEquals(emptyMap(), home.uploadedThumbnails)
    }

    @Test
    fun `thumbnails of videos no longer in the PC library are removed`() {
        home.library["gone soon.mp4"] = "video"
        home.thumbnails["gone soon.mp4"] = "jpeg bytes"
        val thumbnail = assertNotNull(libraries.thumbnail(pc(), video("gone soon.mp4")))
        home.library.clear()

        libraries.videos(pc())

        assertTrue(!thumbnail.exists())
    }

    @Test
    fun `a PC that isn't reachable can't be browsed`() {
        val pc = pc()
        home.stop()

        val error = assertThrows<PcException> { libraries.videos(pc) }
        assertEquals(PcException.Reason.NOT_REACHABLE, error.reason)
    }

    @Test
    fun `a PC that no longer accepts the phone's login asks to pair again`() {
        home.session = "a new token was made on the PC"

        val error = assertThrows<PcException> { libraries.videos(pc()) }
        assertEquals(PcException.Reason.PAIR_AGAIN, error.reason)
    }

    // Streaming

    @Test
    fun `a video streams from the PC over the app's own connection`() {
        home.library["clip one.mp4"] = "streamed bytes"

        val stream = libraries.stream(pc(), video("clip one.mp4"))

        val request = Request.Builder().url(stream.url).apply { stream.headers.forEach { (k, v) -> header(k, v) } }.build()
        val body = stream.http.newCall(request).execute().use { it.body!!.string() }
        assertEquals("streamed bytes", body)
    }

    // Save to phone

    @Test
    fun `save to phone copies the video into the phone library`() {
        home.library["a clip.mp4"] = "the video's bytes"
        val progress = mutableListOf<Int?>()

        val saved = libraries.saveToPhone(pc(), video("a clip.mp4")) { progress += it }

        assertEquals("a clip.mp4", saved.name)
        assertEquals(mapOf("a clip.mp4" to "the video's bytes"), phoneLibrary.videos)
        assertEquals(100, progress.last())
    }

    @Test
    fun `a save to phone that breaks off adds nothing to the phone library`() {
        home.library["a clip.mp4"] = "x".repeat(200_000)
        home.dropMediaDownloads = true

        val error = assertThrows<PcException> { libraries.saveToPhone(pc(), video("a clip.mp4")) {} }

        assertEquals(PcException.Reason.NOT_REACHABLE, error.reason)
        assertEquals(emptyMap(), phoneLibrary.videos)
    }

    @Test
    fun `a save to phone the phone library refuses says why`() {
        home.library["a clip.mp4"] = "video"
        phoneLibrary.failWith = "storage is full"

        val error = assertThrows<PcException> { libraries.saveToPhone(pc(), video("a clip.mp4")) {} }
        assertEquals(PcException.Reason.FAILED, error.reason)
        assertTrue("storage is full" in error.message!!)
    }

    @Test
    fun `a video deleted on the PC meanwhile can't be saved`() {
        home.library["a clip.mp4"] = "video"
        val clip = video("a clip.mp4")
        home.library.clear()

        val error = assertThrows<PcException> { libraries.saveToPhone(pc(), clip) {} }
        assertEquals(PcException.Reason.NOT_FOUND, error.reason)
        assertEquals(emptyMap(), phoneLibrary.videos)
    }

    // Delete

    @Test
    fun `delete removes the video from the PC library`() {
        home.library["keep.mp4"] = "video"
        home.library["delete me #1.mp4"] = "video"

        libraries.delete(pc(), video("delete me #1.mp4"))

        assertEquals(listOf("keep.mp4"), home.library.keys.toList())
        assertEquals(listOf("keep.mp4"), libraries.videos(pc()).map { it.name })
    }

    @Test
    fun `deleting a video that's already gone says so`() {
        home.library["a clip.mp4"] = "video"
        val clip = video("a clip.mp4")
        home.library.clear()

        val error = assertThrows<PcException> { libraries.delete(pc(), clip) }
        assertEquals(PcException.Reason.NOT_FOUND, error.reason)
    }

    // Upload

    @Test
    fun `upload sends a video from the phone into the PC library`() {
        home.library["older.mp4"] = "older video"
        val progress = mutableListOf<Int?>()

        val uploaded = libraries.upload(pc(), FakePhoneVideoFile("from the gallery.mp4", "x".repeat(300_000))) { progress += it }

        assertEquals("from the gallery.mp4", uploaded.name)
        assertEquals("x".repeat(300_000), home.library["from the gallery.mp4"])
        assertEquals(listOf("from the gallery.mp4", "older.mp4"), libraries.videos(pc()).map { it.name })
        assertEquals(100, progress.last())
        assertTrue(progress.size > 2) // it moves along the way
    }

    @Test
    fun `an uploaded video gets a thumbnail made on the phone`() {
        libraries.upload(pc(), FakePhoneVideoFile("clip.mp4", "video")) {}

        val form = assertNotNull(home.uploadedThumbnails["clip.mp4"])
        assertTrue("frame of clip.mp4" in form && "12.0" in form)
        assertEquals("the phone's thumbnail", libraries.thumbnail(pc(), video("clip.mp4"))?.readText())
    }

    @Test
    fun `a video no thumbnail can be made of is still uploaded`() {
        libraries.upload(pc(), FakePhoneVideoFile("clip.mp4", "video", noFrame = true)) {}

        assertEquals("video", home.library["clip.mp4"])
        assertEquals(emptyMap(), home.uploadedThumbnails)
    }

    @Test
    fun `upload is refused when the PC isn't reachable`() {
        val pc = pc()
        home.stop()

        val error = assertThrows<PcException> { libraries.upload(pc, FakePhoneVideoFile("clip.mp4", "video")) {} }
        assertEquals(PcException.Reason.NOT_REACHABLE, error.reason)
    }

    @Test
    fun `a video the phone can't read isn't blamed on the PC`() {
        val error = assertThrows<PcException> { libraries.upload(pc(), FakePhoneVideoFile("clip.mp4", "video", unreadable = true)) {} }

        assertEquals(PcException.Reason.FAILED, error.reason)
        assertEquals("Couldn't read the video on the phone: permission revoked", error.message)
        assertEquals(emptyMap(), home.library)
    }

    @Test
    fun `a kind of video PCs don't keep isn't uploaded`() {
        val error = assertThrows<PcException> { libraries.upload(pc(), FakePhoneVideoFile("old phone clip.3gp", "video")) {} }

        assertEquals(PcException.Reason.FAILED, error.reason)
        assertTrue(error.message!!.startsWith("PC libraries only keep"))
        assertEquals(emptyMap(), home.library)
    }
}
