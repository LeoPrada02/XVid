package app.xvid.core

/**
 * Tells the phone user when a newer version of the app is on GitHub Releases.
 * It only links to it: there's no background download or install.
 */
class UpdateCheck(
    private val feed: ReleaseFeed,
    currentVersion: String,
    private val storage: Storage,
    private val clock: Clock,
) {
    private val current = Version.parse(currentVersion)

    /** The latest release if it's newer than this app; null if not, or if it can't be checked. */
    fun newerRelease(): Update? {
        if (current == null) return null
        val release = try {
            feed.latest()
        } catch (e: Exception) {
            null
        } ?: return null
        val version = Version.parse(release.tag) ?: return null
        return if (version > current) Update(version.toString(), release.url) else null
    }

    /**
     * For a notification: checks at most once a day, and returns each newer
     * version only the first time it's seen.
     */
    fun releaseToNotify(): Update? {
        val now = clock.now()
        val lastCheck = storage.get(LAST_CHECK)?.toLongOrNull()
        if (lastCheck != null && now - lastCheck < DAY_MS) return null
        storage.put(LAST_CHECK, now.toString())

        val update = newerRelease() ?: return null
        if (storage.get(NOTIFIED) == update.version) return null
        storage.put(NOTIFIED, update.version)
        return update
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val LAST_CHECK = "update.lastCheck"
        const val NOTIFIED = "update.notifiedVersion"
    }
}

/** A newer version of the app and the page to get it from. */
data class Update(val version: String, val url: String)

/** A release version: major.minor.patch, from a tag like v1.2.0. */
internal data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version) = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

        fun parse(text: String): Version? {
            val (major, minor, patch) = pattern.matchEntire(text.trim())?.destructured ?: return null
            return Version(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}
