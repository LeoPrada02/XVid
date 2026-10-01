package app.xvid.core

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MaximumQualityTest {
    @TempDir
    lateinit var workDir: File

    private val engine = FakeEngine()
    private val storage = FakeStorage()
    private val setting = MaximumQualitySetting(storage)
    private lateinit var downloads: PhoneDownloads

    @BeforeTest
    fun setUp() {
        downloads = PhoneDownloads(engine, FakePhoneLibrary(), FakeNetwork(), workDir, maximumQuality = setting::current)
    }

    private fun formatRequestedWith(quality: MaximumQuality): String {
        setting.choose(quality)
        engine.requests.clear()
        downloads.download("https://x.com/someone/status/1")
        return engine.requests.single().format
    }

    @Test
    fun `Best merges the best video with the best audio`() {
        assertEquals("bv*+ba/b", formatRequestedWith(MaximumQuality.BEST))
    }

    @Test
    fun `720p asks for the best version no taller than 720, falling back to the smallest`() {
        assertEquals("bv*[height<=?720]+ba/b[height<=?720]/wv*+ba/w", formatRequestedWith(MaximumQuality.P720))
    }

    @Test
    fun `480p asks for the best version no taller than 480, falling back to the smallest`() {
        assertEquals("bv*[height<=?480]+ba/b[height<=?480]/wv*+ba/w", formatRequestedWith(MaximumQuality.P480))
    }

    @Test
    fun `phone downloads use Best until a maximum quality is chosen`() {
        assertEquals(MaximumQuality.BEST, setting.current())

        downloads.download("https://x.com/someone/status/1")

        assertEquals("bv*+ba/b", engine.requests.single().format)
    }

    @Test
    fun `the chosen maximum quality is kept across restarts`() {
        setting.choose(MaximumQuality.P480)

        val afterRestart = MaximumQualitySetting(storage)

        assertEquals(MaximumQuality.P480, afterRestart.current())
    }

    @Test
    fun `a stored value the app doesn't know falls back to Best`() {
        storage.put(MaximumQualitySetting.KEY, "4k")

        assertEquals(MaximumQuality.BEST, setting.current())
    }
}
