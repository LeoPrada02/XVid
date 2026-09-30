package app.xvid

import android.app.Application
import app.xvid.core.KnownPcs
import app.xvid.core.MaximumQualitySetting
import app.xvid.core.PhoneDownloads
import app.xvid.core.PhoneLibraryBrowser
import app.xvid.core.RetryingPhoneDownloads
import app.xvid.core.UpdateCheck
import app.xvid.core.YtDlpUpdates
import java.io.File

/** Wires the core module to its Android implementations. */
class XVidApp : Application() {
    private val storage by lazy { PreferencesStorage(this) }
    private val network by lazy { AndroidNetworkState(this) }

    /** The Maximum quality setting (see SettingsActivity). */
    val maximumQuality: MaximumQualitySetting by lazy { MaximumQualitySetting(storage) }

    val ytDlpUpdates: YtDlpUpdates by lazy { YtDlpUpdates(YoutubeDlUpdater(this), storage, WallClock) }

    private val phoneDownloads: PhoneDownloads by lazy {
        PhoneDownloads(
            engine = YoutubeDlEngine(this),
            library = MediaStorePhoneLibrary(this),
            network = network,
            workDir = File(cacheDir, "downloads"),
            maximumQuality = maximumQuality::current,
        )
    }

    /** Phone downloads with retries and yt-dlp updates: what the share sheet and background work use. */
    val retryingDownloads: RetryingPhoneDownloads by lazy {
        RetryingPhoneDownloads(phoneDownloads, network, ytDlpUpdates, storage)
    }

    val phoneLibrary: PhoneLibraryBrowser by lazy {
        PhoneLibraryBrowser(
            folder = MediaStorePhoneLibraryFolder(this),
            thumbnails = FrameThumbnailMaker(this),
            thumbnailDir = File(cacheDir, "thumbnails"),
        )
    }

    /** Pairing and the PCs the phone knows (see PcSectionsView). */
    val knownPcs: KnownPcs by lazy { KnownPcs(storage) }

    /** Null in local builds, which don't know which GitHub repo they come from. */
    val updateCheck: UpdateCheck? by lazy {
        BuildConfig.RELEASES_REPO.takeIf { it.isNotEmpty() }?.let { repo ->
            UpdateCheck(GitHubReleaseFeed(repo), BuildConfig.VERSION_NAME, storage, WallClock)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        BackgroundWork.scheduleWeeklyYtDlpCheck(this)
        // In case the app was stopped before a waiting download's retry was scheduled.
        if (retryingDownloads.hasWaiting()) BackgroundWork.retryWhenOnline(this)
    }
}
