package app.xvid.core

/**
 * Stands in for yt-dlp's self-update. [onUpdate] runs on each successful update,
 * e.g. to make a [FakeEngine] that X had broken work again.
 */
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
