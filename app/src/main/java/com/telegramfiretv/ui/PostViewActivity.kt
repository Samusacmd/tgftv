package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.telegramfiretv.R
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Visualizza un singolo messaggio (tipicamente un post di canale con una tastiera a
 * pulsanti, come i "menu" con elenco stagioni/episodi) e i suoi eventuali pulsanti
 * inline, funzionanti come nelle chat: pulsanti URL (inclusi i link interni
 * t.me/c/<id>/<messageId>, che aprono direttamente il messaggio di destinazione,
 * permettendo di navigare tra post-menu concatenati) e pulsanti di callback.
 *
 * NOTA sugli id: nei link t.me/c/<id>/<messageId> il numero nel link è l'id "server"
 * del messaggio, mentre TDLib usa internamente message_id = server_id * 2^20
 * (1 048 576). La conversione è fatta da chi avvia questa activity.
 */
class PostViewActivity : FragmentActivity() {

    private var chatId = 0L
    private var messageId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        messageId = intent.getLongExtra("messageId", 0L)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(48, 40, 48, 40)
        }

        val titleTv = TextView(this).apply {
            text = "Caricamento…"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleTv)

        // Riga diagnostica temporanea: mostra l'id del messaggio ottenuto e il tipo di
        // contenuto, per verificare se il link porta davvero al messaggio giusto.
        val debugTv = TextView(this).apply {
            setTextColor(0xFF8899A6.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 16)
        }
        root.addView(debugTv)

        val photo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 480
            ).also { it.bottomMargin = 24 }
        }
        root.addView(photo)

        val textTv = TextView(this).apply {
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }
        root.addView(textTv)

        val buttonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(buttonsBox)

        val backBtn = Button(this).apply {
            text = "← Indietro"
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(backBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = 24 })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E1418.toInt())
            addView(root)
        })

        if (chatId == 0L || messageId == 0L) {
            titleTv.text = "Messaggio non disponibile"
            return
        }
        val requestedDebug = "richiesto: chatId=$chatId  messageId=$messageId"
        debugTv.text = requestedDebug

        TdClient.getChat(chatId) { c ->
            runOnUiThread { if (c is TdApi.Chat) titleTv.text = c.title }
        }

        TdClient.getMessage(chatId, messageId) { obj ->
            runOnUiThread {
                if (obj !is TdApi.Message) {
                    val why = (obj as? TdApi.Error)?.let { "${it.code}: ${it.message}" } ?: "nessuna risposta"
                    titleTv.text = "Messaggio non trovato ($why)"
                    return@runOnUiThread
                }
                render(obj, photo, textTv, buttonsBox, debugTv, requestedDebug)
            }
        }
    }

    private fun render(
        m: TdApi.Message, photo: ImageView, textTv: TextView, buttonsBox: LinearLayout,
        debugTv: TextView, requestedDebug: String
    ) {
        val c = m.content
        debugTv.text = "$requestedDebug\nottenuto: id=${m.id}  contenuto=${c.javaClass.simpleName}  hasReplyMarkup=${m.replyMarkup != null}"
        val bodyText = when (c) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> c.caption.text
            is TdApi.MessageVideo -> c.caption.text
            is TdApi.MessageDocument -> c.caption.text
            is TdApi.MessageAnimation -> c.caption.text
            else -> ""
        }
        textTv.text = bodyText
        textTv.visibility = if (bodyText.isBlank()) View.GONE else View.VISIBLE

        if (c is TdApi.MessagePhoto) {
            photo.visibility = View.VISIBLE
            c.photo.minithumbnail?.data?.let { d ->
                runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                    ?.let { photo.setImageBitmap(it) }
            }
            val big = c.photo.sizes.maxByOrNull { it.width * it.height }?.photo
            if (big != null) {
                if (big.local.isDownloadingCompleted && big.local.path.isNotEmpty()) {
                    runCatching { BitmapFactory.decodeFile(big.local.path) }.getOrNull()
                        ?.let { photo.setImageBitmap(it) }
                } else {
                    TdClient.downloadFilePath(big.id) { path ->
                        runOnUiThread {
                            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                                ?.let { photo.setImageBitmap(it) }
                        }
                    }
                }
            }
        } else {
            // Miniatura per video/GIF/videomessaggi: stessa logica della foto.
            val thumbPair: Pair<ByteArray?, TdApi.File?>? = when (c) {
                is TdApi.MessageVideo -> c.video.minithumbnail?.data to c.video.thumbnail?.file
                is TdApi.MessageAnimation -> c.animation.minithumbnail?.data to c.animation.thumbnail?.file
                is TdApi.MessageVideoNote -> c.videoNote.minithumbnail?.data to c.videoNote.thumbnail?.file
                is TdApi.MessageDocument -> c.document.minithumbnail?.data to c.document.thumbnail?.file
                else -> null
            }
            if (thumbPair != null) {
                photo.visibility = View.VISIBLE
                thumbPair.first?.let { d ->
                    runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                        ?.let { photo.setImageBitmap(it) }
                }
                val f = thumbPair.second
                if (f != null) {
                    if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
                        runCatching { BitmapFactory.decodeFile(f.local.path) }.getOrNull()
                            ?.let { photo.setImageBitmap(it) }
                    } else {
                        TdClient.downloadFilePath(f.id) { path ->
                            runOnUiThread {
                                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                                    ?.let { photo.setImageBitmap(it) }
                            }
                        }
                    }
                }
            }
        }

        if (c is TdApi.MessageSticker) {
            showSticker(c.sticker, buttonsBox)
        }

        // Il "segnalibro" a cui puntano molti pulsanti (es. inizio di una stagione) è spesso
        // un video/audio/documento, non un altro post con pulsanti: riconosciamolo e offriamo
        // subito la riproduzione a schermo intero.
        val playable: Triple<Int, Int, String>? = when (c) {
            is TdApi.MessageVideo -> Triple(c.video.video.id, 0, c.video.fileName.ifEmpty { "Video" })
            is TdApi.MessageAnimation -> Triple(c.animation.animation.id, 0, c.animation.fileName.ifEmpty { "GIF" })
            is TdApi.MessageVideoNote -> Triple(c.videoNote.video.id, 0, "Video messaggio")
            is TdApi.MessageAudio -> Triple(c.audio.audio.id, 1, c.audio.fileName.ifEmpty { "Audio" })
            is TdApi.MessageVoiceNote -> Triple(c.voiceNote.voice.id, 1, "Messaggio vocale")
            is TdApi.MessageDocument -> {
                val mime = c.document.mimeType
                if (mime.startsWith("video/") || mime.startsWith("audio/"))
                    Triple(c.document.document.id, if (mime.startsWith("audio/")) 1 else 0, c.document.fileName)
                else null
            }
            else -> null
        }
        if (playable != null) {
            buttonsBox.addView(Button(this).apply {
                text = "▶  Riproduci: ${playable.third}"
                setBackgroundResource(R.drawable.bg_button)
                setTextColor(0xFFFFFFFF.toInt())
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 16 }
                setOnClickListener {
                    startActivity(
                        Intent(this@PostViewActivity, PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_FILE_IDS, intArrayOf(playable.first))
                            .putExtra(PlayerActivity.EXTRA_LABELS, arrayOf(playable.third))
                            .putExtra(PlayerActivity.EXTRA_KINDS, intArrayOf(playable.second))
                            .putExtra(PlayerActivity.EXTRA_INDEX, 0)
                    )
                }
            })
        }

        val markup = m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
        if (markup == null) {
            if (playable == null && c !is TdApi.MessageSticker) {
                buttonsBox.addView(TextView(this).apply {
                    text = "Questo messaggio non ha pulsanti né contenuti riproducibili."
                    setTextColor(0xFF8899A6.toInt())
                    textSize = 14f
                })
            }
            return
        }
        for (rowArr in markup.rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10 }
            }
            for (b in rowArr) row.addView(makeInlineButton(m.chatId, m.id, b))
            buttonsBox.addView(row)
        }
    }

    /**
     * Mostra lo sticker del messaggio (statico o animato): serve quando lo sticker STESSO
     * è il "segnalibro" (es. un canale che segna l'inizio di una stagione con uno sticker
     * dedicato) — prima la pagina non mostrava nulla in questo caso.
     */
    private fun showSticker(sticker: TdApi.Sticker, container: LinearLayout) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
            layoutParams = LinearLayout.LayoutParams(240, 240)
        }
        container.addView(box, 0)

        fun fallbackEmoji() {
            box.removeAllViews()
            box.addView(TextView(this).apply { text = sticker.emoji.ifEmpty { "🎭" }; textSize = 48f })
        }

        val fileId = sticker.sticker.id
        val localPath = sticker.sticker.local.path
        when (sticker.format) {
            is TdApi.StickerFormatTgs -> {
                val lav = LottieAnimationView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(240, 240)
                    repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
                }
                box.addView(lav)

                fun loadLottie(path: String) {
                    Thread {
                        val json: String? = try {
                            GZIPInputStream(File(path).inputStream()).bufferedReader().use { it.readText() }
                        } catch (e: Exception) { null }
                        if (json == null) {
                            runOnUiThread { if (!isFinishing && !isDestroyed) fallbackEmoji() }
                            return@Thread
                        }
                        LottieCompositionFactory.fromJsonString(json, path)
                            .addListener { comp ->
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) { lav.setComposition(comp); lav.playAnimation() }
                                }
                            }
                            .addFailureListener {
                                runOnUiThread { if (!isFinishing && !isDestroyed) fallbackEmoji() }
                            }
                    }.start()
                }

                if (localPath.isNotEmpty() && File(localPath).exists()) {
                    loadLottie(localPath)
                } else {
                    TdClient.downloadFilePath(fileId) { path ->
                        if (path.isNotEmpty()) loadLottie(path)
                    }
                }
            }
            is TdApi.StickerFormatWebp -> {
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(240, 240)
                }
                box.addView(iv)

                fun showStatic(path: String) {
                    val bmp = try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
                    if (bmp != null) iv.setImageBitmap(bmp) else fallbackEmoji()
                }

                if (localPath.isNotEmpty() && File(localPath).exists()) {
                    showStatic(localPath)
                } else {
                    TdClient.downloadFilePath(fileId) { path ->
                        if (path.isNotEmpty()) runOnUiThread { if (!isFinishing && !isDestroyed) showStatic(path) }
                    }
                }
            }
            else -> fallbackEmoji() // Sticker video (Webm): emoji come segnaposto.
        }
    }

    private fun makeInlineButton(chatId: Long, messageId: Long, b: TdApi.InlineKeyboardButton): Button {
        return Button(this).apply {
            text = b.text
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).also { it.rightMargin = 8 }
            setOnClickListener {
                when (val ty = b.type) {
                    is TdApi.InlineKeyboardButtonTypeCallback -> {
                        TdClient.sendCallback(chatId, messageId, ty.data) { ans ->
                            if (ans is TdApi.CallbackQueryAnswer && ans.text.isNotBlank()) {
                                runOnUiThread { Toast.makeText(this@PostViewActivity, ans.text, Toast.LENGTH_SHORT).show() }
                            }
                        }
                    }
                    is TdApi.InlineKeyboardButtonTypeUrl -> openTelegramLink(ty.url)
                    else -> Toast.makeText(this@PostViewActivity, "Tipo di pulsante non supportato", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Versione ridotta della risoluzione link usata in BotChatActivity, per i pulsanti URL. */
    private fun openTelegramLink(raw: String) {
        var link = raw.trim()
        val i = link.indexOf("t.me/")
        if (i > 0) link = link.substring(i)
        if (link.startsWith("t.me/")) link = "https://$link"

        if (link.contains("t.me/c/")) {
            // Risoluzione ufficiale via TDLib: niente calcoli manuali sugli id (usati per
            // navigare tra post-menu concatenati, es. dai pulsanti "St.01" ecc.).
            Toast.makeText(this, "Apro…", Toast.LENGTH_SHORT).show()
            TdClient.getMessageLinkInfo(link) { obj ->
                runOnUiThread {
                    val info = obj as? TdApi.MessageLinkInfo
                    val msg = info?.message
                    when {
                        msg != null -> startActivity(
                            Intent(this, PostViewActivity::class.java)
                                .putExtra("chatId", msg.chatId)
                                .putExtra("messageId", msg.id)
                        )
                        info != null && info.chatId != 0L -> TdClient.getChat(info.chatId) { c ->
                            runOnUiThread {
                                if (c is TdApi.Chat) openChat(c)
                                else Toast.makeText(this, "Chat non accessibile", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> {
                            val why = (obj as? TdApi.Error)?.let { "${it.code}: ${it.message}" } ?: obj?.javaClass?.simpleName ?: "nessuna risposta"
                            Toast.makeText(this, "Link non risolvibile ($why): $raw", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            return
        }
        if (link.contains("t.me/+") || link.contains("t.me/joinchat/")) {
            Toast.makeText(this, "Apro invito…", Toast.LENGTH_SHORT).show()
            TdClient.checkInviteLink(link) { obj ->
                runOnUiThread {
                    if (obj is TdApi.ChatInviteLinkInfo && obj.chatId != 0L) {
                        TdClient.getChat(obj.chatId) { c -> runOnUiThread { if (c is TdApi.Chat) openChat(c) } }
                    } else {
                        TdClient.joinByInviteLink(link) { j ->
                            runOnUiThread {
                                if (j is TdApi.Chat) openChat(j)
                                else Toast.makeText(this, "Impossibile aprire l'invito", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            return
        }
        if (!link.contains("t.me/") && !raw.trim().startsWith("@")) {
            val url = if (link.startsWith("http://") || link.startsWith("https://")) link else "https://$link"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile aprire il link", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val u = if (raw.trim().startsWith("@")) raw.trim().drop(1) else {
            val ii = link.indexOf("t.me/")
            if (ii >= 0) link.substring(ii + 5).takeWhile { it.isLetterOrDigit() || it == '_' } else null
        }
        if (u.isNullOrEmpty()) {
            Toast.makeText(this, "Link non apribile: $raw", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Apro @$u…", Toast.LENGTH_SHORT).show()
        TdClient.searchPublicChat(u) { obj ->
            runOnUiThread {
                if (obj is TdApi.Chat) openChat(obj)
                else Toast.makeText(this, "Non trovato: @$u", Toast.LEN
