package app.xvid.core

/**
 * The phone app's own X login, separate from the PCs' cookies.txt: the session
 * cookies the in-app login page ended with, kept in private app [storage] and
 * passed to yt-dlp for every phone download.
 *
 * There's no check that the login is still valid: a download failing because it
 * needs a login is what asks for a new one.
 */
class XLogin(private val storage: Storage) {
    fun isLoggedIn(): Boolean = storage.get(KEY) != null

    /**
     * Keeps the X cookies the login page has, as a Cookie header ("name=value; ...").
     * Returns false, keeping nothing new, while they aren't a login yet: yt-dlp needs
     * both the session (auth_token) and the token X checks requests against (ct0).
     */
    fun logIn(cookieHeader: String): Boolean {
        val cookies = cookieHeader.split(';').mapNotNull { pair ->
            val (name, value) = pair.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            (name.trim() to value.trim()).takeIf { it.first.isNotEmpty() && it.second.isNotEmpty() }
        }
        if (REQUIRED.any { name -> cookies.none { it.first == name } }) return false
        storage.put(KEY, cookies.joinToString("; ") { "${it.first}=${it.second}" })
        return true
    }

    fun logOut() {
        storage.put(KEY, null)
    }

    /** The login as a Netscape cookies file for yt-dlp's --cookies, or null when logged out. */
    fun cookies(): String? {
        val header = storage.get(KEY) ?: return null
        return buildString {
            append("# Netscape HTTP Cookie File\n")
            for (pair in header.split("; ")) {
                val (name, value) = pair.split('=', limit = 2)
                // Session cookies (expiry 0) for x.com and its subdomains, HTTPS only.
                append(".x.com\tTRUE\t/\tTRUE\t0\t$name\t$value\n")
            }
        }
    }

    private companion object {
        const val KEY = "x.login"
        val REQUIRED = listOf("auth_token", "ct0")
    }
}
