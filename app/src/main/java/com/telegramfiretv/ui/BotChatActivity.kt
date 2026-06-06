package com.telegramfiretv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class BotChatActivity : FragmentActivity() {

    private var chatId = 0L
    private var titleText: String? = null
    private var botCommands: List<TdApi.BotCommand> = emptyList()

    private lateinit var messagesText: TextView
    private lateinit var messagesScroll: ScrollView
    private lateinit var buttonsBox: LinearLayout

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
            setPadding(64, 40, 64, 40)
        }
        root.addView(TextView(this).apply {
            text = titleText ?: "Bot"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            setPadding(0, 0, 0, 16)
        })

        messagesText = TextView(this).apply {
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 16f
        }
        messagesScroll = ScrollView(this).apply {
            isFocusable = false
            addView(messagesText)
        }
        root.addView(messagesScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = 16 })

        buttonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val btnScroll = ScrollView(this).apply {
            isFocusable = false
            addView(buttonsBox)
        }
        root.addView(btnScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        TdClient.openChat(chatId)
        TdClient.onMessagesChanged = { cid ->
            if (cid == chatId) runOnUiThread { scheduleRefresh() }
        }
        loadBotCommands()
        loadAndRender()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 250)
    }

    private fun loadBotCommands() {
        val chat = TdClient.chats.firstOrNull { it.id == chatId } ?: return
        val type = chat.type
        if (type is TdApi.ChatTypePrivate) {
            TdClient.getUserFullInfo(type.userId) { obj ->
                if (obj is TdApi.UserFullInfo) {
                    val cmds = obj.botInfo?.commands?.toList() ?: emptyList()
                    runOnUiThread { botCommands = cmds; render(lastMessages) }
                }
            }
        }
    }

    private var lastMessages: List<TdApi.Message> = emptyList()

    private fun loadAndRender() {
        TdClient.getChatHistory(chatId, 0L, 20) { result ->
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

    private fun render(msgs: List<TdApi.Message>) {
        // Testo degli ultimi messaggi, in ordine cronologico.
        val sb = StringBuilder()
        for (m in msgs.reversed()) {
            val t = messageText(m)
            if (t.isNotBlank()) sb.append(t).append("\n\n")
        }
        messagesText.text = sb.toString().trimEnd()
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }

        buttonsBox.removeAllViews()

        // Tastiera inline del messaggio più recente che ne ha una.
        val inlineMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupInlineKeyboard }
        if (inlineMsg != null) {
            val markup = inlineMsg.replyMarkup as TdApi.ReplyMarkupInlineKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) row.addView(inlineButton(inlineMsg.id, b))
                buttonsBox.addView(row)
            }
        }

        // Tastiera-risposta (pulsanti che inviano testo).
        val kbMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupShowKeyboard }
        if (kbMsg != null) {
            val markup = kbMsg.replyMarkup as TdApi.ReplyMarkupShowKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) {
                    row.addView(makeButton(b.text) { TdClient.sendText(chatId, b.text); scheduleRefresh() })
                }
                buttonsBox.addView(row)
            }
        }

        // Comandi del bot + /start.
        val cmdRow = newRow()
        cmdRow.addView(makeButton("/start") { TdClient.sendText(chatId, "/start"); scheduleRefresh() })
        buttonsBox.addView(cmdRow)
        for (c in botCommands) {
            val label = "/${c.command}" + if (c.description.isNotBlank()) "  —  ${c.description}" else ""
            buttonsBox.addView(makeButton(label) { TdClient.sendText(chatId, "/${c.command}"); scheduleRefresh() })
        }

        buttonsBox.getChildAt(0)?.requestFocus()
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
                is TdApi.InlineKeyboardButtonTypeUrl ->
                    Toast.makeText(this, ty.url, Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(this, "Tipo di pulsante non supportato", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun messageText(m: TdApi.Message): String {
        return when (val c = m.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessagePhoto -> ("[foto] " + c.caption.text).trim()
            is TdApi.MessageVideo -> ("[video] " + c.caption.text).trim()
            is TdApi.MessageAudio -> ("[audio] " + c.caption.text).trim()
            is TdApi.MessageDocument -> ("[file] " + c.caption.text).trim()
            is TdApi.MessageSticker -> "[sticker]"
            else -> "[" + c.javaClass.simpleName.removePrefix("Message").lowercase() + "]"
        }
    }

    private fun newRow(): LinearLayout {
        return LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    }

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
            lp.bottomMargin = 16
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
