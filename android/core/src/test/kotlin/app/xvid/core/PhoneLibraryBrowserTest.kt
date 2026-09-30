package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneLibraryBrowserTest {
    @TempDir
    lateinit var folderDir: File

    @TempDir
    lateinit var thumbnailDir: File

    private lateinit var folder: FakePhoneLibraryFolder
    private val thumbnails = FakeThumbnailMaker()
    private lateinit var browser: PhoneLibraryBrowser

    @BeforeTest
    fun setUp() {
        folder = FakePhoneLibraryFolder(folderDir)
        browser = PhoneLibraryBrowser(folder, thumbnails, thumbnailDir)
    }

    @Test
    fun `an empty folder is an empty phone library`() {
        assertEquals(emptyList(), browser.videos())
    }

    @Test
    fun `the phone library lists the folder's videos newest first`() {
        folder.put("XVid_1.mp4", addedAt = 1_000)
        folder.put("XVid_3.mp4", addedAt = 3_000)
        folder.put("XVid_2.mp4", addedAt = 2_000)

        assertEquals(listOf("XVid_3.mp4", "XVid_2.mp4", "XVid_1.mp4"), browser.videos().map { it.name })
    }

    @Test
    fun `videos added at the same time keep a stable order by name`() {
        folder.put("XVid_9_2.mp4", addedAt = 5_000)
        folder.put("XVid_9_1.mp4", addedAt = 5_000)

        assertEquals(listOf("XVid_9_1.mp4", "XVid_9_2.mp4"), browser.videos().map { it.name })
    }

    @Test
    fun `files that aren't videos are left out`() {
        folder.put("XVid_1.mp4", addedAt = 1_000)
        folder.put("notes.txt", addedAt = 2_000)

        assertEquals(listOf("XVid_1.mp4"), browser.videos().map { it.name })
    }

    @Test
    fun `a video deleted from the gallery disappears from the phone library`() {
        folder.put("XVid_1.mp4", addedAt = 1_000)
        folder.put("XVid_2.mp4", addedAt = 2_000)
        assertEquals(2, browser.videos().size)

        folder.deleteFromGallery("XVid_2.mp4")

        assertEquals(listOf("XVid_1.mp4"), browser.videos().map { it.name })
    }

    @Test
    fun `a file deleted outside the app disappears even if the folder's index still has it`() {
        folder.put("XVid_1.mp4", addedAt = 1_000)
        folder.put("XVid_2.mp4", addedAt = 2_000)
        assertEquals(2, browser.videos().size)

        folder.deleteOutside("XVid_1.mp4")

        assertEquals(listOf("XVid_2.mp4"), browser.videos().map { it.name })
    }

    @Test
    fun `every listing reads the folder itself, so a new video shows up without any record to update`() {
        assertEquals(emptyList(), browser.videos())

        folder.put("XVid_1.mp4", addedAt = 1_000)

        assertEquals(listOf("XVid_1.mp4"), browser.videos().map { it.name })
        assertEquals(2, folder.listings)
    }

    @Test
    fun `a thumbnail is made by the app once and then reused`() {
        val video = folder.put("XVid_1.mp4", addedAt = 1_000)

        val first = assertNotNull(browser.thumbnail(video))
        val second = assertNotNull(browser.thumbnail(video))

        assertEquals(first, second)
        assertEquals("thumbnail of XVid_1.mp4", second.readText())
        assertEquals(listOf("XVid_1.mp4"), thumbnails.made)
    }

    @Test
    fun `each video gets its own thumbnail`() {
        val one = folder.put("XVid_1.mp4", addedAt = 1_000)
        val two = folder.put("XVid_2.mp4", addedAt = 2_000)

        assertNotEquals(browser.thumbnail(one), browser.thumbnail(two))
        assertEquals(listOf("XVid_1.mp4", "XVid_2.mp4"), thumbnails.made)
    }

    @Test
    fun `a video whose thumbnail can't be made has none, and nothing half-made is kept`() {
        thumbnails.fails = true
        val video = folder.put("XVid_1.mp4", addedAt = 1_000)

        assertNull(browser.thumbnail(video))
        assertTrue(thumbnailDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a replaced video gets a new thumbnail`() {
        val old = folder.put("XVid_1.mp4", addedAt = 1_000, content = "old")
        browser.thumbnail(old)

        folder.deleteFromGallery("XVid_1.mp4")
        val replacement = folder.put("XVid_1.mp4", addedAt = 9_000, content = "a longer new video")
        browser.thumbnail(replacement)

        assertEquals(listOf("XVid_1.mp4", "XVid_1.mp4"), thumbnails.made)
    }

    @Test
    fun `thumbnails of videos no longer in the phone library are removed`() {
        val kept = folder.put("XVid_1.mp4", addedAt = 1_000)
        val deleted = folder.put("XVid_2.mp4", addedAt = 2_000)
        browser.thumbnail(kept)
        browser.thumbnail(deleted)

        folder.deleteFromGallery("XVid_2.mp4")
        browser.videos()

        assertEquals(1, thumbnailDir.listFiles().orEmpty().size)
        assertEquals("thumbnail of XVid_1.mp4", assertNotNull(browser.thumbnail(kept)).readText())
        assertEquals(listOf("XVid_1.mp4", "XVid_2.mp4"), thumbnails.made)
    }

    @Test
    fun `a folder that can't be read is reported, not shown as empty`() {
        folder.failWith = "no permission"

        val error = runCatching { browser.videos() }.exceptionOrNull()

        assertEquals("no permission", assertNotNull(error).message)
    }
}
