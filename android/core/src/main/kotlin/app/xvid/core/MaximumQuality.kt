package app.xvid.core

/**
 * The Maximum quality setting: the tallest video a phone download may save.
 * It applies to every phone download on every network; PC libraries always
 * get the best.
 */
enum class MaximumQuality(
    /** The yt-dlp format selector phone downloads ask the engine for. */
    val format: String,
) {
    /** Best video merged with best audio, or the best single file that has both. */
    BEST("bv*+ba/b"),
    P720(capped(720)),
    P480(capped(480)),
}

/**
 * The best version no taller than [height], with audio (merged, or a single
 * file that has both). If the post has nothing that small, the smallest
 * version available. `<=?` also keeps versions whose height X doesn't report.
 */
private fun capped(height: Int) = "bv*[height<=?$height]+ba/b[height<=?$height]/wv*+ba/w"

/** The chosen Maximum quality, kept in [storage] so it survives restarts. Default Best. */
class MaximumQualitySetting(private val storage: Storage) {
    fun current(): MaximumQuality =
        storage.get(KEY)?.let { stored -> MaximumQuality.entries.firstOrNull { it.name == stored } }
            ?: MaximumQuality.BEST

    fun choose(quality: MaximumQuality) {
        storage.put(KEY, quality.name)
    }

    companion object {
        const val KEY = "maximum_quality"
    }
}
