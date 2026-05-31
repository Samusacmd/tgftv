package com.telegramfiretv.player

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.telegramfiretv.databinding.ActivityPlayerBinding
import com.telegramfiretv.tdlib.TdClient
import com.telegramfiretv.ui.Settings
import org.drinkless.tdlib.TdApi
import java.io.File

class PlayerActivity : FragmentActivity() {

    companion object {
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_LABEL = "label"
        private var lastPlayedFileId = -1
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private var targetFileId: Int = -1
    private var started = false

    @Volatile
    private var stopped = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingDelete: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        status = TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        addContentView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )

        targetFileId = intent.getIntExtra(EXTRA_FILE_ID, -1)
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo
        binding.playerView.useController = true
        binding.playerView.controllerAutoShow = Settings.playerDim(this)
        binding.playerView.controllerShowTimeoutMs = 2000
        player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "Errore riproduzione:\n${error.errorCodeName}\n${error.message ?: ""}"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    Settings.clearPosition(this@PlayerActivity, targetFileId)
                    finish()
                }
            }
        })

        if (targetFileId < 0) {
            setStatus("Nessun file da riprodurre.")
            return
        }

        TdClient.onFileUpdated = { file ->
            if (!stopped && file.id == targetFileId) runOnUiThread { onFileProgress(file) }
        }
        setStatus("Preparo: " + (intent.getStringExtra(EXTRA_LABEL) ?: ""))
        TdClient.downloadFile(targetFileId) { obj ->
            if (!stopped && obj is TdApi.File) runOnUiThread { onFileProgress(obj) }
        }
    }

    private fun onFileProgress(file: TdApi.File) {
        if (stopped) return
        val local = file.local
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            play(local.path)
        } else if (file.size > 0) {
            val pct = (100.0 * local.downloadedSize / file.size).toInt()
            setStatus("Scarico… $pct%")
        } else {
            setStatus("Scarico…")
        }
    }

    private fun play(path: String) {
        if (started || stopped) return
        started = true
        status.visibility = View.GONE
        val exo = player ?: return

        // Cancella la cache del media precedente solo dopo 30s di questo
        // (così tornando indietro subito non si perde nulla).
        val prev = lastPlayedFileId
        lastPlayedFileId = targetFileId
        if (prev >= 0 && prev != targetFileId) {
            val r = Runnable { TdClient.deleteFile(prev) }
            pendingDelete = r
            handler.postDelayed(r, 30_000)
        }

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        exo.prepare()
        val pos = Settings.savedPosition(this, targetFileId)
        if (pos > 0) exo.seekTo(pos)
        exo.playWhenReady = true
    }

    private fun setStatus(text: String) {
        status.visibility = View.VISIBLE
        status.text = text
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        pendingDelete?.let { handler.removeCallbacks(it) }
        pendingDelete = null
        player?.let {
            if (started && it.playbackState != Player.STATE_ENDED) {
                Settings.savePosition(this, targetFileId, it.currentPosition)
            }
        }
        TdClient.onFileUpdated = null
        if (targetFileId >= 0 && !started) TdClient.cancelDownload(targetFileId)
        player?.release()
        player = null
    }
}
