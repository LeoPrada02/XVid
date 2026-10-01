package app.xvid.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoTypesTest {
    @Test
    fun `every video type the engine can produce has its own mime type`() {
        assertEquals("video/mp4", VideoTypes.mimeTypeOf("XVid_1.mp4"))
        assertEquals("video/x-m4v", VideoTypes.mimeTypeOf("XVid_1.M4V"))
        assertEquals("video/x-matroska", VideoTypes.mimeTypeOf("XVid_1.mkv"))
        assertEquals("video/webm", VideoTypes.mimeTypeOf("XVid_1.webm"))
        assertEquals("video/quicktime", VideoTypes.mimeTypeOf("XVid_1.mov"))
    }

    @Test
    fun `only finished video files count as videos`() {
        assertTrue(VideoTypes.isVideo(File("00001.mp4")))
        assertTrue(VideoTypes.isVideo(File("00001.WEBM")))
        assertFalse(VideoTypes.isVideo(File("00001.mp4.part")))
        assertFalse(VideoTypes.isVideo(File("00001.jpg")))
    }
}
