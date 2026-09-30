package app.xvid

import android.app.Application
import app.xvid.core.PhoneDownloads
import java.io.File

/** Wires the core module to its Android implementations. */
class XVidApp : Application() {
    val phoneDownloads: PhoneDownloads by lazy {
        PhoneDownloads(
            engine = YoutubeDlEngine(this),
            library = MediaStorePhoneLibrary(this),
            network = AndroidNetworkState(this),
            workDir = File(cacheDir, "downloads"),
        )
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
