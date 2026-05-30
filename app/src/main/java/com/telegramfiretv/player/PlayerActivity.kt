package com.telegramfiretv.player

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.telegramfiretv.databinding.ActivityPlayerBinding
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import java.io.File

class PlayerActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TITLE = "chat_title"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var status: TextView

    private var chatId: Long = 0L
    private var targetFileId: Int = -1
    private var started = false

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

        chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0L)
    }

    override fun onStart() {
        super.onStart()
        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo
        player = exo

        TdClient.onFileUpdated = { file ->
            if (file.id == targetFileId) runOnUiThread { onFileProgress(file) }
        }

        setStatus("Cerco un contenuto da riprodurre…")
        TdClient.openChat(chatId)
        resolveMedia(retry = 1)
    }

    private fun resolveMedia(retry: Int) {
        TdClient.getChatHistory(chatId, 0, 50) { result ->
            if (result is TdApi.Messages) {
                val media = result.messages
                    ?.filterNotNull()
                    ?.firstNotNullOfOrNull { extractMedia(it) }
                runOnUiThread {
                    when {
                        media != null -> startDownload(media.first, media.second)
                        retry > 0 -> resolveMedia(retry - 1)
                        else -> setStatus("Nessun video o audio trovato in questa chat.")
                    }
                }
            } else {
                runOnUiThread { setStatus("Impossibile leggere i messaggi della chat.") }
            }
        }
    }

    private fun startDownload(fileId: Int, label: String) {
        targetFileId = fileId
        setStatus("Preparo: $label")
        TdClient.downloadFile(fileId) { obj ->
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

    private fun extractMedia(msg: TdApi.Message): Pair<Int, String>? {
        return when (val c = msg.content) {
            is TdApi.MessageVideo -> c.video.video.id to c.video.fileName.ifEmpty { "Video" }
            is TdApi.MessageAudio -> c.audio.audio.id to
                listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }
                    .joinToString(" - ").ifEmpty { "Audio" }
            is TdApi.MessageVoiceNote -> c.voiceNote.voice.id to "Messaggio vocale"
            is TdApi.MessageVideoNote -> c.videoNote.video.id to "Video messaggio"
            is TdApi.MessageAnimation -> c.animation.animation.id to c.animation.fileName.ifEmpty { "GIF" }
            is TdApi.MessageDocument -> {
                val mime = c.document.mimeType
                if (mime.startsWith("video/") || mime.startsWith("audio/"))
                    c.document.document.id to c.document.fileName.ifEmpty { "File" }
                else null
            }
            else -> null
        }
    }

    override fun onStop() {
        super.onStop()
        TdClient.onFileUpdated = null
        player?.release()
        player = null
    }
}
