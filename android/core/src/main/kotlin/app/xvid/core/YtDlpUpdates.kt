package app.xvid.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Updates the phone app's yt-dlp to the latest release, replacing the old one in place. */
interface YtDlpUpdater {
    /** True when a newer yt-dlp replaced the old one, false when it was already the latest. Throws when it can't update. */
    fun update(): Boolean
}

/**
 * When yt-dlp gets updated: after a failed phone download (at most once an hour,
 * so a run of failures doesn't hammer GitHub), and in a background check at most
 * once a week. Any update counts as that week's check.
 *
 * Downloads run inside [using], so yt-dlp is never replaced under a running download.
 */
class YtDlpUpdates(
    private val updater: YtDlpUpdater,
    private val storage: Storage,
    private val clock: Clock,
) {
    private val lock = ReentrantReadWriteLock()

    /** Runs [block], which uses yt-dlp, while no update can replace it. */
    fun <T> using(block: () -> T): T = lock.read(block)

    /** Called after a failed download that a newer yt-dlp might fix. Never throws. */
    fun updateAfterFailure() {
        updateIfDue(HOUR_MS)
    }

    /** The weekly background check. Returns whether it checked (false: already checked this week). */
    fun weeklyCheck(): Boolean = updateIfDue(WEEK_MS)

    private fun updateIfDue(interval: Long): Boolean = lock.write {
        val now = clock.now()
        val last = storage.get(LAST_CHECK)?.toLongOrNull()
        if (last != null && now < last) {
            // The clock went back: start counting again from now rather than check more often.
            storage.put(LAST_CHECK, now.toString())
            return false
        }
        if (last != null && now - last < interval) return false
        // Recorded before trying, so a check that fails still counts.
        storage.put(LAST_CHECK, now.toString())
        try {
            updater.update()
        } catch (e: Exception) {
            // Not fatal: the current yt-dlp stays, and the next check tries again.
        }
        true
    }

    private companion object {
        const val HOUR_MS = 60 * 60 * 1000L
        const val WEEK_MS = 7 * 24 * HOUR_MS
        const val LAST_CHECK = "ytdlp.lastCheck"
    }
}
