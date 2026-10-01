package app.xvid

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.xvid.core.Pc
import app.xvid.core.PcException
import app.xvid.core.PcVideo

/**
 * One PC library video: streams it from the PC, with **Save to phone** and **Delete** under it.
 * It plays inside the app, over the app's own connection to the PC, since other video players
 * don't trust the PCs' private certificate authority.
 */
class PcVideoActivity : Activity() {
    private val app get() = application as XVidApp
    private var player: ExoPlayer? = null
    private lateinit var pc: Pc
    private lateinit var video: PcVideo
    private lateinit var status: TextView

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pc = intent.pc(app) ?: return finish()
        video = intent.pcVideo() ?: return finish()

        status = text(TextStyle.SMALL)
        val view = PlayerView(this).apply { setBackgroundColor(Color.BLACK) }
        try {
            val stream = app.pcLibraries.stream(pc, video)
            val source = OkHttpDataSource.Factory(stream.http).setDefaultRequestProperties(stream.headers)
            player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build().apply {
                setMediaItem(MediaItem.fromUri(stream.url))
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) = showError(getString(R.string.pc_video_cant_play, pc.name))
                })
                prepare()
                playWhenReady = true
            }
            view.player = player
        } catch (e: PcException) {
            status.text = e.message
        }

        setContentView(column {
            setBackgroundColor(color(R.color.bg))
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(column {
                setPadding(dp(16), dp(12), dp(16), dp(20))
                addView(text(TextStyle.HEADING, video.title))
                addView(text(TextStyle.SMALL, getString(R.string.pc_video_meta, pc.name, VideoTiles.meta(this@PcVideoActivity, video.addedAt, video.sizeBytes))), spaced(4))
                addView(status.apply { visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE }, spaced(4))
                addView(row {
                    addView(button(R.string.pc_video_save) { saveToPhone() }, fill())
                    addView(button(R.string.pc_video_delete, ButtonStyle.DANGER) { confirmDelete() }, fill().apply { marginStart = dp(8) })
                }, spaced(12))
            })
        })
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun saveToPhone() {
        DownloadService.saveFromPc(this, pc, video)
        Toast.makeText(this, R.string.pc_video_saving, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pc_video_delete_title, pc.name))
            .setMessage(getString(R.string.pc_video_delete_message, video.title, pc.name))
            .setPositiveButton(R.string.pc_video_delete) { _, _ -> delete() }
            .setNegativeButton(R.string.pc_video_cancel, null)
            .show()
    }

    private fun delete() {
        player?.stop() // so the PC isn't sending the file while it's deleted
        Thread {
            val error = try {
                app.pcLibraries.delete(pc, video)
                null
            } catch (e: PcException) {
                e.takeIf { it.reason != PcException.Reason.NOT_FOUND } // already gone is what was wanted
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (error == null) {
                    Toast.makeText(this, getString(R.string.pc_video_deleted, pc.name), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    showError(error.message.orEmpty())
                }
            }
        }.start()
    }

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(color(R.color.danger))
        status.visibility = View.VISIBLE
    }

    companion object {
        fun open(context: Context, pc: Pc, video: PcVideo) {
            context.startActivity(Intent(context, PcVideoActivity::class.java).putPc(pc).putPcVideo(video))
        }
    }
}

