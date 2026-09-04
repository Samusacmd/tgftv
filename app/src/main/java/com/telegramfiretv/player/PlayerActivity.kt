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
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    private var posKey: String = ""
    private var label: String = ""
    private var isAudio = false
    private var isPhoto = false
    private var started = false
    private var streamingFallback = false
    // Ritentativi dello streaming dopo un errore: al rientro dallo standby la rete della
    // Fire TV impiega qualche secondo a riattivarsi e il primo tentativo può fallire per
    // motivi transitori. Prima di declassare al download classico (lento su file grandi),
    // ritentiamo lo streaming alcune volte a distanza di qualche secondo.
    private var streamingRetries = 0
    private var fileListener: ((TdApi.File) -> Unit)? = null
    private var streamingActive = false

    @Volatile
    private var stopped = false

    private val handler = Handler(Looper.getMainLooper())
    private val pendingDeletes = mutableListOf<Runnable>()

    /** Chiave stabile per la posizione di ripresa: usa l'id univoco remoto del file
     *  (persistente tra sessioni), con ripiego sull'id locale se non disponibile. */
    /**
     * Chiave stabile per posizione di ripresa e flag "già visto": SOLO l'id univoco
     * remoto del file, mai l'id locale (file.id) — quello vale solo per la sessione
     * corrente e può essere riassegnato da TDLib a un file diverso in seguito, il che
     * causava stelline "già visto" sbagliate su file mai aperti. Se l'id univoco non
     * c'è, niente ripiego: per quel file la ripresa/il flag semplicemente non si salvano.
     */
    private fun keyFor(file: TdApi.File): String = file.remote.uniqueId

    /**
     * Ricava un MIME type da passare a ExoPlayer in base all'estensione del nome file.
     * Serve soprattutto per i contenitori come MKV: senza questo hint, in streaming l'URI
     * è "tdfile://..." (nessuna estensione) e lo sniffing del formato può fallire. Dare il
     * MIME giusto fa scegliere subito l'estrattore corretto (es. Matroska) ed evita l'errore
     * "container malformed" su file che invece sono validi.
     */
    private fun guessMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mov" -> MimeTypes.VIDEO_MP4
            "ts" -> MimeTypes.VIDEO_MP2T
            "avi" -> MimeTypes.VIDEO_AVI
            "mp3" -> MimeTypes.AUDIO_MPEG
            "m4a", "aac" -> MimeTypes.AUDIO_AAC
            "ogg", "oga", "opus" -> MimeTypes.AUDIO_OPUS
            "flac" -> MimeTypes.AUDIO_FLAC
            "wav" -> MimeTypes.AUDIO_WAV
            else -> null
        }
    }

    /** Programma la cancellazione differita di un file (cache di streaming già abbandonato). */
    private fun scheduleDelete(fileId: Int) {
        val r = object : Runnable {
            override fun run() { pendingDeletes.remove(this); TdClient.deleteFile(fileId) }
        }
        pendingDeletes.add(r)
        handler.postDelayed(r, 30_000)
    }

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
        posKey = ""
        label = labels.getOrElse(index) { "" }
        val kind = kinds.getOrElse(index) { 0 }
        isAudio = kind == 1
        isPhoto = kind == 2
        streamingFallback = false
        streamingActive = false
    }

    private fun goNext() { if (index < fileIds.size - 1) switchTo(index + 1) }
    private fun goPrev() { if (index > 0) switchTo(index - 1) }

    /** Cambia file restando nella stessa schermata (niente rilancio: evita di azzerare i callback). */
    private fun switchTo(i: Int) {
        player?.let {
            if (started && it.playbackState != Player.STATE_ENDED && posKey.isNotEmpty()) {
                Settings.savePosition(this, posKey, it.currentPosition)
            }
        }
        // Stesso motivo di onStop: in streaming il download del file lasciato va sempre
        // fermato, altrimenti compete con quello del nuovo file appena selezionato.
        if (targetFileId >= 0 && (streamingActive || !started)) TdClient.cancelDownload(targetFileId)
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
        // Al rientro ripartiamo sempre provando lo streaming: se prima dell'uscita eravamo
        // caduti nel download classico per un errore transitorio, non deve restare permanente.
        streamingFallback = false
        streamingRetries = 0
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
            // Buffer iniziale più generoso: sui dispositivi lenti il player partiva subito
            // con pochissimo buffer e si bloccava più volte nei primi secondi. Ora attende
            // 6 secondi di contenuto prima di partire (8 dopo un blocco), con una scorta
            // che cresce fino a 60s durante la riproduzione.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    20_000,   // buffer minimo da mantenere
                    60_000,   // buffer massimo
                    6_000,    // buffer richiesto prima di INIZIARE la riproduzione
                    8_000     // buffer richiesto per RIPARTIRE dopo un blocco
                )
                .build()
            val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
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
                    if (streamingActive && !streamingFallback && !isPhoto) {
                        try {
                            // Salviamo comunque la posizione raggiunta.
                            val resumeAt = exo.currentPosition.coerceAtLeast(0L)
                            if (posKey.isNotEmpty()) Settings.savePosition(this@PlayerActivity, posKey, resumeAt)
                            // L'errore è spesso transitorio (es. rete in riattivazione dopo
                            // lo standby della Fire TV): ritentiamo lo streaming prima di
                            // declassare al download classico, lentissimo sui file grandi.
                            if (streamingRetries < 3) {
                                streamingRetries++
                                streamingActive = false
                                started = false
                                runOnUiThread {
                                    setStatus("Riavvio streaming… (tentativo $streamingRetries)")
                                    handler.postDelayed({ if (!stopped) startItem() }, 2000)
                                }
                                return
                            }
                            // Ritentativi esauriti: ricadiamo sul download classico,
                            // riprendendo da dove si era interrotto.
                            streamingFallback = true
                            streamingActive = false
                            started = false
                            runOnUiThread {
                                setStatus("Streaming non riuscito, scarico normalmente…")
                                startClassicDownload(retries = 5)
                            }
                        } catch (e: Exception) {
                            // Non lasciamo che un problema nel percorso di fallback faccia
                            // crashare l'Activity: mostriamo l'errore invece.
                            runOnUiThread {
                                status.visibility = View.VISIBLE
                                status.text = "Errore nel passaggio al download:\n${e.message ?: ""}"
                            }
                        }
                        return
                    }
                    runOnUiThread {
                        status.visibility = View.VISIBLE
                        status.text = "Errore riproduzione:\n${error.errorCodeName}\n${error.message ?: ""}"
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_BUFFERING) {
                        // Attesa del riempimento del buffer: avvisa invece di sembrare bloccato.
                        setStatus("Caricamento…")
                    }
                    if (playbackState == Player.STATE_READY) {
                        status.visibility = View.GONE
                        // Riproduzione avviata davvero: azzera i ritentativi per gli errori futuri.
                        streamingRetries = 0
                    }
                    if (playbackState == Player.STATE_ENDED) {
                        if (posKey.isNotEmpty()) Settings.clearPosition(this@PlayerActivity, posKey)
                        // Auto-avanzamento: se c'è un file successivo nella lista passa a quello,
                        // altrimenti (ultimo elemento) chiude il player.
                        if (index < fileIds.size - 1) goNext() else finish()
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

        // Usiamo addFileListener (lista condivisa, sicura): ogni schermata registra il
        // proprio ascoltatore in modo indipendente, senza interferire con i download di
        // miniature/sticker o con gli altri ascoltatori attivi nell'app.
        fileListener?.let { TdClient.removeFileListener(it) }
        val listener: (TdApi.File) -> Unit = { file ->
            if (!stopped && file.id == targetFileId) runOnUiThread { onFileProgress(file) }
        }
        fileListener = listener
        TdClient.addFileListener(listener)
        setStatus("Preparo: $label")

        val useStreaming = Settings.streamingEnabled(this) && !isPhoto && !streamingFallback
        if (useStreaming) {
            streamingActive = true
            requestFileSizeForStreaming(attemptsLeft = 5)
        } else {
            startClassicDownload(retries = 5)
        }
    }

    /**
     * Chiede a TDLib la dimensione del file per avviare lo streaming. Appena dopo un cambio
     * rapido di file, TDLib può rispondere con size/expectedSize ancora a 0 (sta ancora
     * elaborando la richiesta precedente di cancellazione/avvio): in quel caso riproviamo
     * dopo una breve attesa invece di arrenderci subito al download classico.
     */
    private fun requestFileSizeForStreaming(attemptsLeft: Int) {
        TdClient.getFile(targetFileId) { obj ->
            if (stopped) return@getFile
            val knownSize = if (obj is TdApi.File) {
                if (obj.size > 0) obj.size else obj.expectedSize
            } else 0L
            if (obj is TdApi.File && knownSize > 0) {
                runOnUiThread { startStreaming(obj, knownSize) }
            } else if (attemptsLeft > 0) {
                runOnUiThread {
                    handler.postDelayed({
                        if (!stopped) requestFileSizeForStreaming(attemptsLeft - 1)
                    }, 200)
                }
            } else {
                // Dopo vari tentativi, ancora nessuna dimensione nota: ricadiamo sul download classico.
                runOnUiThread {
                    streamingActive = false
                    startClassicDownload(retries = 5)
                }
            }
        }
    }

    /** Avvia la riproduzione in streaming usando TdDataSource, con un buffer iniziale minimo. */
    @androidx.annotation.OptIn(UnstableApi::class)
    private fun startStreaming(file: TdApi.File, knownSize: Long) {
        if (started || stopped) return
        val exo = player ?: return
        posKey = keyFor(file)
        // Segna il file come "già visto" (flag mostrato negli elenchi dei canali).
        Settings.markWatched(this, posKey)
        setStatus("Avvio streaming…")

        val factory = TdDataSource.Factory(targetFileId, knownSize, estimatedBufferBytes())
        val mimeType = guessMimeType(label)
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse("tdfile://$targetFileId"))
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)
        val mediaSource = ProgressiveMediaSource.Factory(factory)
            .createMediaSource(mediaItemBuilder.build())

        started = true
        status.visibility = View.GONE

        val prev = lastPlayedFileId
        lastPlayedFileId = targetFileId
        // Solo in streaming: il file lasciato è una cache temporanea, lo cancelliamo dopo un po'.
        if (prev >= 0 && prev != targetFileId) scheduleDelete(prev)

        exo.setMediaSource(mediaSource)
        exo.prepare()
        val pos = Settings.savedPosition(this, posKey)
        if (pos > 0) exo.seekTo(pos)
        exo.playWhenReady = true
        if (isAudio) binding.playerView.showController()
    }

    /**
     * Avvia il download classico gestendo anche gli errori di TDLib: se DownloadFile risponde
     * con un errore transitorio (rete non pronta, file appena cancellato dallo stop precedente),
     * riprova dopo un secondo invece di restare in silenzio bloccato su "Scarico… 0%".
     */
    private fun startClassicDownload(retries: Int) {
        if (stopped) return
        TdClient.downloadFile(targetFileId) { obj ->
            if (stopped) return@downloadFile
            when {
                obj is TdApi.File -> runOnUiThread { onFileProgress(obj) }
                retries > 0 -> handler.postDelayed({ startClassicDownload(retries - 1) }, 1000)
                else -> runOnUiThread {
                    val msg = (obj as? TdApi.Error)?.message ?: "errore sconosciuto"
                    status.visibility = View.VISIBLE
                    status.text = "Download non riuscito:\n$msg"
                }
            }
        }
    }

    private fun onFileProgress(file: TdApi.File) {
        if (stopped) return
        if (streamingActive) return // gestito internamente da TdDataSource
        val local = file.local
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            posKey = keyFor(file)
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
        // Segna il file come "già visto" (flag mostrato negli elenchi dei canali).
        if (posKey.isNotEmpty()) Settings.markWatched(this, posKey)

        // In download classico NON cancelliamo il file precedente: resta in cache, così
        // rivederlo subito non richiede di riscaricarlo. (La pulizia delle cache di streaming
        // avviene invece in startStreaming/onStop.)
        lastPlayedFileId = targetFileId

        val mimeType = guessMimeType(if (label.isNotEmpty()) label else path)
        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.fromFile(File(path)))
        if (mimeType != null) mediaItemBuilder.setMimeType(mimeType)
        exo.setMediaItem(mediaItemBuilder.build())
        exo.prepare()
        val pos = Settings.savedPosition(this, posKey)
        if (pos > 0) exo.seekTo(pos)
        exo.playWhenReady = true
        if (isAudio) binding.playerView.showController()
    }

    /**
     * Stima quanti byte bufferizzare prima di avviare la riproduzione, in base ai secondi
     * impostati dall'utente. Usa una stima di bitrate prudente (audio/video misto) perché
     * il bitrate reale non è noto finché il file non è almeno parzialmente analizzato;
     * sovrastimare leggermente evita scatti, sottostimare causa solo un'attesa più breve.
     */
    private fun estimatedBufferBytes(): Long {
        val seconds = Settings.streamingBufferSec(this)
        val assumedBitrateBytesPerSec = if (isAudio) 32L * 1024L else 1200L * 1024L
        return seconds * assumedBitrateBytesPerSec
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
        // Esegue subito le cancellazioni differite delle cache di streaming già abbandonate,
        // invece di lasciarle in sospeso (evita sia il ritardo sia la fuga di un file).
        val toDelete = pendingDeletes.toList()
        pendingDeletes.clear()
        toDelete.forEach { handler.removeCallbacks(it); it.run() }
        player?.let {
            if (started && it.playbackState != Player.STATE_ENDED && posKey.isNotEmpty()) {
                Settings.savePosition(this, posKey, it.currentPosition)
            }
        }
        fileListener?.let { TdClient.removeFileListener(it) }
        fileListener = null
        // Per lo streaming il download non è mai "completo" finché non si è visto tutto
        // il file: se non cancelliamo qui, resta attivo in background e rallenta/blocca
        // l'avvio dello streaming del prossimo file scelto dalla lista.
        if (targetFileId >= 0 && (streamingActive || !started)) {
            TdClient.cancelDownload(targetFileId)
            if (streamingActive) {
                // Il file scaricato durante lo streaming è solo una cache temporanea per
                // la lettura, non un download intenzionale: senza questa cancellazione
                // resterebbe sul disco indefinitamente ogni video visto, anche se mai
                // riaperto in seguito (il cleanup automatico scattava solo aprendo il
                // file SUCCESSIVO, non quando si chiude semplicemente il player).
                TdClient.deleteFile(targetFileId)
            }
        }
        binding.playerView.player = null
        player?.release()
        player = null
        started = false
    }
}
