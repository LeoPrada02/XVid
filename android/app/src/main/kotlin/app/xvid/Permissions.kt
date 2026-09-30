package app.xvid

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The permissions phone downloads need: notifications, and storage on Android 8 and 9. */
object Permissions {
    fun missing(context: Context): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
}
