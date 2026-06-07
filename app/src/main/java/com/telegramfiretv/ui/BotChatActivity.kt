package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "ts", "flv", "mpg", "mpeg", "wmv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma")

class BotChatActivity : FragmentActivity() {

    private var chatId = 0L
    private var titleText: String? = null
    private var isBot = false
    private var botCommands: List<TdApi.BotCommand> = emptyList()
    private var lastMessages: List<TdApi.Message> = emptyList()

    private lateinit var messagesBox: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var buttonsBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var inputRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadAndRender() }
    private var emptyRetries = 5

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        titleText = intent.getStringExtra("title")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 32, 64, 32)
        }
        root.addView(TextView(this).apply {
            text = titleText ?: "Chat"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setPadding(0, 0, 0, 12)
        })

        messagesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messagesScroll = ScrollView(this).apply { isFocusable = false; addView(messagesBox) }
        root.addView(messagesScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
        ).apply { bottomMargin = 12 })

        buttonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttonsScroll = ScrollView(this).apply { isFocusable = false; addView(buttonsBox) }
        root.addView(buttonsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = 12 })

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        this.inputRow = inputRow
        input = EditText(this).apply {
            hint = "Scrivi un messaggio…"
            setHintTextColor(0xFF7A8893.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFF1B262C.toInt())
            setPadding(24, 20, 24, 20)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(makeButton("Invia") { sendInput() })
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        // La scrittura rispetta le impostazioni "Abilita scrittura".
        val chat0 = TdClient.chats.firstOrNull { it.id == chatId }
        val isPrivate = chat0?.type is TdApi.ChatTypePrivate
        // Per i gruppi questa schermata si apre solo dall'icona già autorizzata -> input visibile.
        // Per le chat private decidiamo dopo aver capito se è un bot o una persona.
        inputRow.visibility = if (isPrivate) View.GONE else View.VISIBLE

        TdClient.openChat(chatId)
        TdClient.onMessagesChanged = { cid -> if (cid == chatId) runOnUiThread { scheduleRefresh() } }
        loadBotInfo()
        loadAndRender()
    }

    private fun sendInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        TdClient.sendText(chatId, t)
        input.setText("")
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 250)
    }

    private fun loadBotInfo() {
        val chat = TdClient.chats.firstOrNull { it.id == chatId } ?: return
        val type = chat.type
        if (type is TdApi.ChatTypePrivate) {
            TdClient.getUserFullInfo(type.userId) { obj ->
                if (obj is TdApi.UserFullInfo) {
                    val info = obj.botInfo
                    runOnUiThread {
                        isBot = info != null
                        botCommands = info?.commands?.toList() ?: emptyList()
                        val canWrite = if (isBot) Settings.writeFlag(this, "bots", true)
                                       else Settings.writeFlag(this, "private", true)
                        inputRow.visibility = if (canWrite) View.VISIBLE else View.GONE
                        render(lastMessages)
                    }
                }
            }
        }
    }

    private fun loadAndRender() {
        TdClient.getChatHistory(chatId, 0L, 25) { result ->
            val msgs = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            runOnUiThread {
                if (msgs.isEmpty() && emptyRetries > 0) {
                    emptyRetries--
                    handler.postDelayed({ loadAndRender() }, 500)
                    return@runOnUiThread
                }
                lastMessages = msgs
                render(msgs)
            }
        }
    }

    private fun loadOlder() {
        val oldestId = lastMessages.lastOrNull()?.id ?: return
        TdClient.getChatHistory(chatId, oldestId, 25) { result ->
            val more = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            runOnUiThread {
                if (more.isNotEmpty()) {
                    lastMessages = lastMessages + more
                    render(lastMessages, scrollBottom = false)
                } else {
                    Toast.makeText(this, "Nessun messaggio precedente", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun render(msgs: List<TdApi.Message>, scrollBottom: Boolean = true) {
        messagesBox.removeAllViews()
        addRow("↑  Messaggi precedenti", true) { loadOlder() }
        // Raccolgo i media della conversazione per la riproduzione con precedente/successivo.
        val mediaRefs = ArrayList<Triple<Int, Int, String>>()
        for (m in msgs.reversed()) {
            val media = mediaOf(m)
            if (media != null) {
                val idx = mediaRefs.size
                mediaRefs.add(media)
                val icon = if (media.second == 2) "🖼" else "▶"
                addRow("$icon  ${media.third}", true) { playMediaAt(mediaRefs.toList(), idx) }
            } else {
                val text = messageText(m)
                if (text.isBlank()) continue
                val cmd = findCommand(text)
                val link = findLink(text)
                when {
                    cmd != null -> addRow(text, true) { TdClient.sendText(chatId, cmd); scheduleRefresh() }
                    link != null -> addRow(text, true) { openLink(link) }
                    else -> addRow(text, false, null)
                }
            }
        }
        if (scrollBottom) messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }

        buttonsBox.removeAllViews()

        val inlineMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupInlineKeyboard }
        if (inlineMsg != null) {
            val markup = inlineMsg.replyMarkup as TdApi.ReplyMarkupInlineKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) row.addView(inlineButton(inlineMsg.id, b))
                buttonsBox.addView(row)
            }
        }

        val kbMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupShowKeyboard }
        if (kbMsg != null) {
            val markup = kbMsg.replyMarkup as TdApi.ReplyMarkupShowKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) row.addView(makeButton(b.text) { TdClient.sendText(chatId, b.text); scheduleRefresh() })
                buttonsBox.addView(row)
            }
        }

        // /start e comandi solo se è davvero un bot.
        if (isBot) {
            buttonsBox.addView(makeButton("/start") { TdClient.sendText(chatId, "/start"); scheduleRefresh() })
            for (c in botCommands) {
                val label = "/${c.command}" + if (c.description.isNotBlank()) "  —  ${c.description}" else ""
                buttonsBox.addView(makeButton(label) { TdClient.sendText(chatId, "/${c.command}"); scheduleRefresh() })
            }
        }
    }

    private fun playMediaAt(refs: List<Triple<Int, Int, String>>, idx: Int) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_FILE_IDS, refs.map { it.first }.toIntArray())
                .putExtra(PlayerActivity.EXTRA_KINDS, refs.map { it.second }.toIntArray())
                .putExtra(PlayerActivity.EXTRA_LABELS, refs.map { it.third }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_INDEX, idx)
        )
    }

    private fun inlineButton(messageId: Long, b: TdApi.InlineKeyboardButton): Button {
        return makeButton(b.text) {
            when (val ty = b.type) {
                is TdApi.InlineKeyboardButtonTypeCallback -> {
                    TdClient.sendCallback(chatId, messageId, ty.data) { ans ->
                        if (ans is TdApi.CallbackQueryAnswer && ans.text.isNotBlank()) {
                            runOnUiThread { Toast.makeText(this, ans.text, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    scheduleRefresh()
                }
                is TdApi.InlineKeyboardButtonTypeUrl -> openLink(ty.url)
                else -> Toast.makeText(this, "Tipo di pulsante non supportato", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLink(raw: String) {
        val link = normalizeLink(raw)
        if (link.contains("t.me/+") || link.contains("t.me/joinchat/")) {
            openInvite(link)
            return
        }
        val u = extractUsername(raw)
        if (u == null) {
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

    private fun normalizeLink(raw: String): String {
        var s = raw.trim()
        val i = s.indexOf("t.me/")
        if (i > 0) s = s.substring(i)
        if (s.startsWith("t.me/")) s = "https://$s"
        return s
    }

    private fun openInvite(link: String) {
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
    }

    private fun openChat(chat: TdApi.Chat) {
        val intent = if (chat.type.constructor == TdApi.ChatTypePrivate.CONSTRUCTOR)
            Intent(this, BotChatActivity::class.java)
        else
            Intent(this, MediaListActivity::class.java)
        startActivity(intent.putExtra("chatId", chat.id).putExtra("title", chat.title))
    }

    private fun extractUsername(raw: String): String? {
        val s = raw.trim()
        if (s.startsWith("@")) return s.drop(1).takeWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        val i = s.indexOf("t.me/")
        if (i >= 0) {
            val rest = s.substring(i + 5)
            if (rest.startsWith("+") || rest.startsWith("joinchat") || rest.startsWith("c/")) return null
            return rest.takeWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        }
        return null
    }

    private fun findLink(text: String): String? {
        Regex("(https?://)?t\\.me/\\S+").find(text)?.let { return it.value }
        Regex("@[A-Za-z0-9_]{4,}").find(text)?.let { return it.value }
        return null
    }

    private fun findCommand(text: String): String? {
        val m = Regex("(?:^|\\s)(/[A-Za-z0-9_]{1,64})").find(text) ?: return null
        return m.groupValues[1]
    }

    private fun mediaOf(m: TdApi.Message): Triple<Int, Int, String>? {
        return when (val c = m.content) {
            is TdApi.MessageVideo -> Triple(c.video.video.id, 0, c.video.fileName.ifEmpty { c.caption.text.ifEmpty { "Video" } })
            is TdApi.MessageAnimation -> Triple(c.animation.animation.id, 0, c.caption.text.ifEmpty { "Video" })
            is TdApi.MessageAudio -> Triple(c.audio.audio.id, 1, c.audio.fileName.ifEmpty { c.caption.text.ifEmpty { "Audio" } })
            is TdApi.MessageVoiceNote -> Triple(c.voiceNote.voice.id, 1, "Messaggio vocale")
            is TdApi.MessageVideoNote -> Triple(c.videoNote.video.id, 0, "Video")
            is TdApi.MessagePhoto -> {
                val s = c.photo.sizes
                if (s.isEmpty()) null
                else Triple(s.maxByOrNull { it.width * it.height }!!.photo.id, 2, c.caption.text.ifEmpty { "Foto" })
            }
            is TdApi.MessageDocument -> {
                val ext = c.document.fileName.substringAfterLast('.', "").lowercase()
                val mime = c.document.mimeType
                when {
                    mime.startsWith("video/") || ext in VIDEO_EXT -> Triple(c.document.document.id, 0, c.document.fileName.ifEmpty { "Video" })
                    mime.startsWith("audio/") || ext in AUDIO_EXT -> Triple(c.document.document.id, 1, c.document.fileName.ifEmpty { "Audio" })
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun messageText(m: TdApi.Message): String {
        return when (val c = m.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessageSticker -> "[sticker]"
            else -> "[" + c.javaClass.simpleName.removePrefix("Message").lowercase() + "]"
        }
    }

    private fun addRow(text: String, focusable: Boolean, onClick: (() -> Unit)? = null) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 16f
            setPadding(16, 16, 16, 16)
        }
        if (focusable && onClick != null) {
            tv.isFocusable = true
            tv.setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) 0xFF2E6E9E.toInt() else 0x00000000) }
            tv.setOnClickListener { onClick() }
        }
        messagesBox.addView(tv)
    }

    private fun newRow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 16f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 16
            lp.bottomMargin = 12
            layoutParams = lp
            setPadding(28, 18, 28, 18)
            setOnClickListener { onClick() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
        TdClient.onMessagesChanged = null
    }
}
