package app.xvid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The permissions phone downloads need: notifications, and storage on Android 8 and 9. */
object Permissions {
    private const val ASKED = "permissions.asked"

    /**
     * Whether XVid has asked for them once already. After that, saying no is the user's choice
     * (downloads still show on the main screen), so sharing no longer opens XVid to ask again.
     */
    fun asked(context: Context): Boolean = PreferencesStorage(context).get(ASKED) != null

    fun markAsked(context: Context) = PreferencesStorage(context).put(ASKED, "1")

    fun missing(context: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
