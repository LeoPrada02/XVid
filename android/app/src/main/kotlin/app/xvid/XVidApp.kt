package app.xvid

import android.app.Application
import app.xvid.core.MaximumQualitySetting
import app.xvid.core.PhoneDownloads
import app.xvid.core.UpdateCheck
import java.io.File

/** Wires the core module to its Android implementations. */
class XVidApp : Application() {
    val phoneDownloads: PhoneDownloads by lazy {
        PhoneDownloads(
            engine = YoutubeDlEngine(this),
            library = MediaStorePhoneLibrary(this),
            network = AndroidNetworkState(this),
            workDir = File(cacheDir, "downloads"),
            maximumQuality = maximumQuality::current,
        )
    }

    val maximumQuality: MaximumQualitySetting by lazy { MaximumQualitySetting(PreferencesStorage(this)) }

    /** Null in local builds, which don't know which GitHub repo they come from. */
    val updateCheck: UpdateCheck? by lazy {
        BuildConfig.RELEASES_REPO.takeIf { it.isNotEmpty() }?.let { repo ->
            UpdateCheck(GitHubReleaseFeed(repo), BuildConfig.VERSION_NAME, PreferencesStorage(this), WallClock)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
