package app.xvid

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import app.xvid.core.PcFrameGrabber
import app.xvid.core.PcStream
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Thumbnails of PC library videos the PC has none for, without downloading the videos: Android's
 * frame reader reads only the parts it needs, through range requests over the app's own connection.
 */
class RangeFrameGrabber : PcFrameGrabber {
    override fun grab(stream: PcStream, target: File): Double? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(RangeDataSource(stream))
            Frames.write(retriever, target, stream.url)
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 }
        } finally {
            retriever.release()
        }
    }
}

/** A video on a PC, read a block at a time with range requests; the last few blocks are kept. */
private class RangeDataSource(private val stream: PcStream) : MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > KEEP_BLOCKS
    }
    private var total = -1L

    override fun getSize(): Long {
        if (total < 0) block(0)
        return total
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        var done = 0
        while (done < size && position + done < getSize()) {
            val at = position + done
            val data = block(at / BLOCK)
            val from = (at % BLOCK).toInt()
            if (from >= data.size) break
            val count = minOf(size - done, data.size - from)
            System.arraycopy(data, from, buffer, offset + done, count)
            done += count
        }
        return if (done == 0) -1 else done
    }

    private fun block(index: Long): ByteArray = blocks.getOrPut(index) {
        val start = index * BLOCK
        val request = Request.Builder().url(stream.url)
            .header("Range", "bytes=$start-${start + BLOCK - 1}")
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        stream.http.newCall(request).execute().use { response ->
            if (response.code != 206) throw IOException("The PC didn't send part of the video (${response.code})")
            total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw IOException("The PC didn't say how big the video is")
            response.body!!.bytes()
        }
    }

    override fun close() = Unit

    private companion object {
        const val BLOCK = 256 * 1024L
        const val KEEP_BLOCKS = 16
    }
}
