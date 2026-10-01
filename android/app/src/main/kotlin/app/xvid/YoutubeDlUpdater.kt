package app.xvid

import android.content.Context
import app.xvid.core.YtDlpUpdater
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Updates youtubedl-android's yt-dlp from yt-dlp's stable GitHub releases. The
 * library deletes the old yt-dlp and puts the new one in its place, so the app
 * doesn't grow with each update.
 */
class YoutubeDlUpdater(private val context: Context) : YtDlpUpdater {
    override fun update(): Boolean {
        val youtubeDl = YoutubeDL.getInstance()
        youtubeDl.init(context) // does nothing when the engine already started it
        return youtubeDl.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) == YoutubeDL.UpdateStatus.DONE
    }
}
