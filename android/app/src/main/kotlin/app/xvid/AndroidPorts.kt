package app.xvid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.xvid.core.NetworkState

class AndroidNetworkState(context: Context) : NetworkState {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
