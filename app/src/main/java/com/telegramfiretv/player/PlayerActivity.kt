package com.telegramfiretv.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.ForwardingPlayer
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
        const val EXTRA_IS_AUDIO = "is_audio"
        const val EXTRA_IS_PHOTO = "is_photo"
        const val EXTRA_FILE_IDS = "file_ids"
        const val EXTRA_LABELS = "labels"
        const val EXTRA_KINDS = "kinds"
        const val EXTRA_INDEX = "index"
        private var lastPlayedFileId = -1
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var status: TextView
    private lateinit var titleOverlay: TextView
    private lateinit var photoView: ImageView

    private var fileIds: IntArray = IntArray(0)
    private var labels: Array<String> = emptyArray()
    private var kinds: IntArray = IntArray(0)
    private var index: Int = 0

    private var targetFileId: Int = -1
    private var label: String = ""
    private var isAudio = false
    private var isPhoto = false
    private var started = false

    @Volatile
    private var stopped = false

    private val handler = Handler(Looper.getMainLooper())
    private var pendingDelete: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        addContentView(
            photoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

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

        titleOverlay = TextView(this).apply {
            setBackgroundColor(0x66000000)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setPadding(48, 28, 48, 28)
            visibility = View.GONE
        }
        addContentView(
            titleOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP }
        )

        fileIds = intent.getIntArrayExtra(EXTRA_FILE_IDS) ?: intArrayOf(intent.getIntExtra(EXTRA_FILE_ID, -1))
        labels = intent.getStringArrayExtra(EXTRA_LABELS) ?: arrayOf(intent.getStringExtra(EXTRA_LABEL) ?: "")
        kinds = intent.getIntArrayExtra(EXTRA_KINDS) ?: intArrayOf(
            if (intent.getBooleanExtra(EXTRA_IS_AUDIO, false)) 1
            else if (intent.getBooleanExtra(EXTRA_IS_PHOTO, false)) 2 else 0
        )
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (fileIds.size - 1).coerceAtLeast(0))
        applyCurrent()
    }

    private fun applyCurrent() {
        targetFileId = fileIds.getOrElse(index) { -1 }
        label = labels.getOrElse(index) { "" }
        val kind = kinds.getOrElse(index) { 0 }
        isAudio = kind == 1
        isPhoto = kind == 2
    }

    private fun goNext() { if (index < fileIds.size - 1) switchTo(index + 1) }
    private fun goPrev() { if (index > 0) switchTo(index - 1) }

    /** Cambia file restando nella stessa schermata (niente rilancio: evita di azzerare i callback). */
    private fun switchTo(i: Int) {
        player?.let {
            if (started && it.playbackState != Player.STATE_ENDED) {
                Settings.savePosition(this, targetFileId, it.currentPosition)
            }
        }
        if (targetFileId >= 0 && !started) TdClient.cancelDownload(targetFileId)
        index = i
        applyCurrent()
        startItem()
    }

    /** Player "ponte": dichiara a ExoPlayer che esiste precedente/successivo e li dirotta sulla nostra navigazione. */
    private inner class NavPlayer(p: Player) : ForwardingPlayer(p) {
        override fun getAvailableCommands(): Player.Commands {
            val b = super.getAvailableCommands().buildUpon()
            if (index < fileIds.size - 1) b.add(Player.COMMAND_SEEK_TO_NEXT)
            if (index > 0) b.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            return b.build()
        }

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT -> index < fileIds.size - 1
            Player.COMMAND_SEEK_TO_PREVIOUS -> index > 0
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = index < fileIds.size - 1
        override fun hasPreviousMediaItem(): Boolean = index > 0
        override fun seekToNext() { goNext() }
        override fun seekToNextMediaItem() { goNext() }
        override fun seekToPrevious() { goPrev() }
        override fun seekToPreviousMediaItem() { goPrev() }
    }

    override fun onStart() {
        super.onStart()
        stopped = false
        startItem()
    }

    private fun startItem() {
        started = false
        photoView.visibility = View.GONE
        photoView.setImageBitmap(null)
        titleOverlay.visibility = View.GONE

        player?.let {
            binding.playerView.player = null
            it.release()
        }
        player = null

        if (!isPhoto) {
            binding.playerView.visibility = View.VISIBLE
            val exo = ExoPlayer.Builder(this).build()
            binding.playerView.player = NavPlayer(exo)
            binding.playerView.useController = true
            if (isAudio) {
                binding.playerView.controllerShowTimeoutMs = 0
                binding.playerView.controllerHideOnTouch = false
                binding.playerView.controllerAutoShow = true
                titleOverlay.text = label
                titleOverlay.visibility = View.VISIBLE
            } else {
                binding.playerView.controllerAutoShow = Settings.playerDim(this)
                binding.playerView.controllerShowTimeoutMs = 2000
            }
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
        } else {
            binding.playerView.player = null
            binding.playerView.visibility = View.GONE
        }

        if (targetFileId < 0) {
            setStatus("Nessun file da riprodurre.")
            return
        }

        TdClient.onFileUpdated = { file ->
            if (!stopped && file.id == targetFileId) runOnUiThread { onFileProgress(file) }
        }
        setStatus("Preparo: $label")
        TdClient.downloadFile(targetFileId) { obj ->
            if (!stopped && obj is TdApi.File) runOnUiThread { onFileProgress(obj) }
        }
    }

    private fun onFileProgress(file: TdApi.File) {
        if (stopped) return
        val local = file.local
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            if (isPhoto) showPhoto(local.path) else play(local.path)
        } else if (file.size > 0) {
            val pct = (100.0 * local.downloadedSize / file.size).toInt()
            setStatus("Scarico… $pct%")
        } else {
            setStatus("Scarico…")
        }
    }

    private fun showPhoto(path: String) {
        if (started || stopped) return
        started = true
        status.visibility = View.GONE
        val bmp = decodeScaled(path, 1920, 1080) ?: run {
            setStatus("Impossibile aprire l'immagine.")
            return
        }
        photoView.setImageBitmap(bmp)
        photoView.visibility = View.VISIBLE
    }

    private fun decodeScaled(path: String, maxW: Int, maxH: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxW || bounds.outHeight / sample > maxH) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Throwable) {
            null
        }
    }

    private fun play(path: String) {
        if (started || stopped) return
        started = true
        status.visibility = View.GONE
        val exo = player ?: return

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
        if (isAudio) binding.playerView.showController()
    }

    private fun setStatus(text: String) {
        status.visibility = View.VISIBLE
        status.text = text
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> { goNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { goPrev(); return true }
        }
        if (isPhoto) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { goNext(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { goPrev(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
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
        binding.playerView.player = null
        player?.release()
        player = null
        started = false
    }
}
