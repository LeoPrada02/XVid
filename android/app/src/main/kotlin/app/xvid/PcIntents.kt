package app.xvid

import android.content.Intent
import app.xvid.core.Pc
import app.xvid.core.PcVideo

// A PC and a PC library video in an Intent, for the screens and the service that act on them.

private const val PC_ID = "pc"
private const val VIDEO_NAME = "video.name"
private const val VIDEO_TITLE = "video.title"
private const val VIDEO_UPLOADER = "video.uploader"
private const val VIDEO_DURATION = "video.duration"
private const val VIDEO_SIZE = "video.size"
private const val VIDEO_ADDED = "video.added"
private const val VIDEO_THUMB = "video.thumb"

internal fun Intent.putPc(pc: Pc): Intent = putExtra(PC_ID, pc.id)

/** The PC put in with [putPc], or null when the phone doesn't know it anymore. */
internal fun Intent.pc(app: XVidApp): Pc? = app.knownPcs.find(getStringExtra(PC_ID))

internal fun Intent.putPcVideo(video: PcVideo): Intent = putExtra(VIDEO_NAME, video.name)
    .putExtra(VIDEO_TITLE, video.title)
    .putExtra(VIDEO_UPLOADER, video.uploader)
    .putExtra(VIDEO_DURATION, video.durationSeconds ?: -1.0)
    .putExtra(VIDEO_SIZE, video.sizeBytes)
    .putExtra(VIDEO_ADDED, video.addedAt)
    .putExtra(VIDEO_THUMB, video.thumb)

internal fun Intent.pcVideo(): PcVideo? {
    val name = getStringExtra(VIDEO_NAME) ?: return null
    return PcVideo(
        name = name,
        title = getStringExtra(VIDEO_TITLE) ?: name,
        uploader = getStringExtra(VIDEO_UPLOADER),
        durationSeconds = getDoubleExtra(VIDEO_DURATION, -1.0).takeIf { it >= 0 },
        sizeBytes = getLongExtra(VIDEO_SIZE, 0),
        addedAt = getLongExtra(VIDEO_ADDED, 0),
        thumb = getStringExtra(VIDEO_THUMB),
    )
}
