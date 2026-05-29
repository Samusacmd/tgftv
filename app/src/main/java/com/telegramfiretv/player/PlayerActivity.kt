package com.telegramfiretv.player

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.telegramfiretv.databinding.ActivityPlayerBinding

/**
 * Player a schermo intero (ExoPlayer / Media3).
 *
 * NOTA: in questa prima versione il player è cablato ma non ancora collegato
 * al media reale della chat. Il prossimo passo (iterazione successiva) sarà:
 *   1) chiedere a TDLib gli ultimi messaggi della chat (GetChatHistory)
 *   2) trovare l'ultimo video/audio
 *   3) scaricarlo con DownloadFile e ottenere il path locale
 *   4) passarlo qui come MediaItem
 */
class PlayerActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_TITLE = "chat_title"
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun onStart() {
        super.onStart()
        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo
        player = exo

        // Placeholder: qui andrà l'URI/percorso del media scaricato da TDLib.
        val mediaUri = intent.getStringExtra("media_uri")
        if (mediaUri != null) {
            exo.setMediaItem(MediaItem.fromUri(mediaUri))
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
