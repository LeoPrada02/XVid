package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PhoneDownloadsTest {
    @TempDir
    lateinit var workDir: File

    private val engine = FakeEngine()
    private val library = FakePhoneLibrary()
    private val network = FakeNetwork()
    private lateinit var downloads: PhoneDownloads

    @BeforeTest
    fun setUp() {
        downloads = PhoneDownloads(engine, library, network, workDir)
    }

    @Test
    fun `a shared post downloads at best quality into the phone library`() {
        val outcome = downloads.download("Look at this https://x.com/someone/status/123?s=20")

        val saved = assertIs<PhoneDownloadOutcome.Saved>(outcome)
        assertEquals(listOf("XVid_123.mp4"), saved.videos.map { it.name })
        assertEquals(
            mapOf("XVid_123.mp4" to "video 1 of https://x.com/someone/status/123"),
            library.videos,
        )
        assertEquals("bv*+ba/b", engine.requests.single().format)
    }

    @Test
    fun `every video of a post with several videos is saved`() {
        engine.videosPerPost = 3

        val outcome = downloads.download("https://twitter.com/someone/status/456/video/2")

        val saved = assertIs<PhoneDownloadOutcome.Saved>(outcome)
        assertEquals(listOf("XVid_456_1.mp4", "XVid_456_2.mp4", "XVid_456_3.mp4"), saved.videos.map { it.name })
        assertEquals(saved.videos.map { it.name }, library.videos.keys.toList())
        assertEquals("https://x.com/someone/status/456", engine.requests.single().url)
    }

    @Test
    fun `a failing download reports the reason and saves nothing`() {
        engine.failWith = "WARNING: something minor\nERROR: [twitter] 789: No video could be found in this tweet"

        val outcome = downloads.download("https://x.com/someone/status/789")

        assertEquals(PhoneDownloadOutcome.Failed("No video could be found in this tweet"), outcome)
        assertTrue(library.videos.isEmpty())
    }

    @Test
    fun `a video the phone library can't take fails with the reason`() {
        library.failWith = "Storage is full"

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("Couldn't save the video: Storage is full"), outcome)
    }

    @Test
    fun `a post that yields no files fails`() {
        engine.videosPerPost = 0

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("No video in this post"), outcome)
    }

    @Test
    fun `text without an X post link fails without downloading`() {
        val outcome = downloads.download("https://example.com/not-a-post")

        assertEquals(PhoneDownloadOutcome.Failed("That isn't a link to an X post"), outcome)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `with no connection the download fails with that reason`() {
        network.online = false

        val outcome = downloads.download("https://x.com/someone/status/1")

        assertEquals(PhoneDownloadOutcome.Failed("No internet connection"), outcome)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `progress is reported while downloading`() {
        engine.progressSteps = listOf(-1f, 12.7f, 100f)
        val seen = mutableListOf<Int?>()

        downloads.download("https://x.com/someone/status/1") { seen += it }

        assertEquals(listOf(null, 12, 100), seen)
    }

    @Test
    fun `temporary files are cleaned up after saving`() {
        downloads.download("https://x.com/someone/status/1")

        assertTrue(workDir.walk().none { it.isFile })
    }
}
