package app.xvid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.xvid.core.Clock
import app.xvid.core.NetworkState
import app.xvid.core.Storage

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

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
