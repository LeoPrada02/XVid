package app.xvid.core

/**
 * Stands in for yt-dlp's self-update. [onUpdate] runs on each successful update,
 * e.g. to make a [FakeEngine] that X had broken work again.
 */
/** Thrown out of a [FakeEngine] to stand in for Android stopping the app in the middle of a download. */
class Interrupted : Error("The app was stopped")

class FakeUpdater(
    var failWith: String? = null,
    var onUpdate: () -> Unit = {},
) : YtDlpUpdater {
    var calls = 0

    override fun update(): Boolean {
        calls++
        failWith?.let { throw java.io.IOException(it) }
        onUpdate()
        return true
    }
}
