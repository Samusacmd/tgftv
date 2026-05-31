package com.telegramfiretv.player

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private var targetFileId: Int = -1
    private var started = false

    private val fileListener: (TdApi.File) -> Unit = { file ->
        if (file.id == targetFileId) runOnUiThread { onFileProgress(file) }
    }

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
        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo
        binding.playerView.useController = true
        // Oscuramento disattivato: comandi visibili ma senza velo scuro.
        if (!Settings.playerDim(this)) {
            binding.playerView
                .findViewById<View>(androidx.media3.ui.R.id.exo_controls_background)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        player = exo

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "Errore riproduzione:\n${error.errorCodeName}\n${error.message ?: ""}"
                }
            }
        })

        if (targetFileId < 0) {
            setStatus("Nessun file da riprodurre.")
            return
        }

        TdClient.addFileListener(fileListener)
        setStatus("Preparo: " + (intent.getStringExtra(EXTRA_LABEL) ?: ""))
        TdClient.downloadFile(targetFileId) { obj ->
            if (obj is TdApi.File) runOnUiThread { onFileProgress(obj) }
        }
    }

    private fun onFileProgress(file: TdApi.File) {
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
        if (started) return
        started = true
        status.visibility = View.GONE
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun setStatus(text: String) {
        status.visibility = View.VISIBLE
        status.text = text
    }

    override fun onStop() {
        super.onStop()
        TdClient.removeFileListener(fileListener)
        if (!started && targetFileId >= 0) {
            TdClient.cancelDownload(targetFileId)
        }
        player?.release()
        player = null
    }
}
