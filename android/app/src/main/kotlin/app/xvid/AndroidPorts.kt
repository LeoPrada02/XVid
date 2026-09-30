package app.xvid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.xvid.core.Clock
import app.xvid.core.NetworkState
import app.xvid.core.Release
import app.xvid.core.ReleaseFeed
import app.xvid.core.Storage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AndroidNetworkState(context: Context) : NetworkState {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

/** Private app storage backed by SharedPreferences. */
class PreferencesStorage(context: Context) : Storage {
    private val prefs = context.getSharedPreferences("xvid", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

object WallClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}

/** The latest release of [repo] ("owner/name") from the GitHub Releases API. */
class GitHubReleaseFeed(private val repo: String) : ReleaseFeed {
    override fun latest(): Release? {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> return null // no release yet
                else -> error("GitHub answered $status")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            return Release(tag = json.getString("tag_name"), url = json.getString("html_url"))
        } finally {
            connection.disconnect()
        }
    }
}
