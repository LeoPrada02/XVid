package app.xvid

import android.Manifest
import android.annotation.TargetApi
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.xvid.core.PhoneLibrary
import app.xvid.core.PhoneVideo
import java.io.File

/**
 * The phone library folder, Movies/XVid, written through the media store so
 * the gallery and Google Photos show the videos. [PhoneVideo.id] is the
 * video's content:// URI.
 */
class MediaStorePhoneLibrary(private val context: Context) : PhoneLibrary {
    override fun add(file: File, name: String): PhoneVideo {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) addScoped(file, name) else addLegacy(file, name)
        return PhoneVideo(id = uri.toString(), name = name)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun addScoped(file: File, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(name))
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: error("The media store refused the video")
        try {
            resolver.openOutputStream(uri).use { out ->
                checkNotNull(out) { "Couldn't write to the phone library" }
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Android 8 and 9: write the file ourselves, then register it with the media store. */
    @Suppress("DEPRECATION")
    private fun addLegacy(file: File, name: String): Uri {
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "XVid needs storage access. Open XVid to allow it"
        }
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        folder.mkdirs()
        val target = uniqueFile(folder, name)
        file.copyTo(target)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, target.absolutePath)
            put(MediaStore.Video.Media.DISPLAY_NAME, target.name)
            put(MediaStore.Video.Media.TITLE, target.nameWithoutExtension)
            put(MediaStore.Video.Media.MIME_TYPE, mimeTypeOf(target.name))
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: Uri.fromFile(target)
    }

    private fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(folder, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
            n++
        }
        return candidate
    }

    private fun mimeTypeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        else -> "video/mp4"
    }

    private companion object {
        const val FOLDER = "XVid"
    }
}
