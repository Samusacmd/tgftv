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
import com.telegramfiretv.R
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

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

        TdClient.getChat(chatId) { c ->
            runOnUiThread { if (c is TdApi.Chat) titleTv.text = c.title }
        }

        TdClient.getMessage(chatId, messageId) { obj ->
            runOnUiThread {
                if (obj !is TdApi.Message) {
                    titleTv.text = "Messaggio non trovato"
                    return@runOnUiThread
                }
                render(obj, photo, textTv, buttonsBox)
            }
        }
    }

    private fun render(m: TdApi.Message, photo: ImageView, textTv: TextView, buttonsBox: LinearLayout) {
        val c = m.content
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
        }

        val markup = m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
        if (markup == null) {
            buttonsBox.addView(TextView(this).apply {
                text = "Questo messaggio non ha pulsanti."
                setTextColor(0xFF8899A6.toInt())
                textSize = 14f
            })
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
                    val chat = info?.chat
                    when {
                        msg != null -> startActivity(
                            Intent(this, PostViewActivity::class.java)
                                .putExtra("chatId", msg.chatId)
                                .putExtra("messageId", msg.id)
                        )
                        chat != null -> openChat(chat)
                        else -> Toast.makeText(this, "Link non risolvibile: $raw", Toast.LENGTH_LONG).show()
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
                else Toast.makeText(this, "Non trovato: @$u", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openChat(chat: TdApi.Chat) {
        val intent = if (chat.type.constructor == TdApi.ChatTypePrivate.CONSTRUCTOR)
            Intent(this, BotChatActivity::class.java)
        else
            Intent(this, MediaListActivity::class.java)
        startActivity(intent.putExtra("chatId", chat.id).putExtra("title", chat.title))
    }
}
