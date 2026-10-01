package app.xvid.core

/** A link to one X post, found in whatever text was shared to the phone app. */
data class XPostLink(val user: String, val statusId: String) {
    /** The post's canonical address, without tracking parameters or a /video/N suffix. */
    val url: String get() = "https://x.com/$user/status/$statusId"

    companion object {
        private val pattern = Regex(
            """https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/([A-Za-z0-9_]+)/status(?:es)?/(\d+)""",
            RegexOption.IGNORE_CASE,
        )

        /** The first X post link in [text], or null when there is none. */
        fun find(text: String): XPostLink? =
            pattern.find(text)?.let { XPostLink(user = it.groupValues[1], statusId = it.groupValues[2]) }
    }
}
