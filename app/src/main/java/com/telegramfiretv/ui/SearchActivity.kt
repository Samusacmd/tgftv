package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class SearchActivity : FragmentActivity() {

    private val thumbs = ThumbLoader()
    private lateinit var queryField: EditText
    private lateinit var resultsBox: LinearLayout
    private lateinit var mineBtn: Button
    private lateinit var publicBtn: Button

    private var scope = "mine" // "mine" oppure "public"
    private var searchToken = 0

    private val debounce = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setup()
        } catch (e: Throwable) {
            showCrash(e)
        }
    }

    private fun setup() {
        thumbs.start()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 48, 64, 48)
        }

        queryField = EditText(this).apply {
            hint = "Cerca…"
            setHintTextColor(0xFF7A8893.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFF1B262C.toInt())
            setPadding(28, 24, 28, 24)
        }
        root.addView(queryField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val scopeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mineBtn = makeButton()
        publicBtn = makeButton()
        mineBtn.setOnClickListener { scope = "mine"; updateScopeButtons(); runSearch() }
        publicBtn.setOnClickListener { scope = "public"; updateScopeButtons(); runSearch() }
        scopeRow.addView(mineBtn)
        scopeRow.addView(publicBtn)
        root.addView(scopeRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(resultsBox) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(root)
        updateScopeButtons()

        queryField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                debounce.removeCallbacks(searchRunnable)
                debounce.postDelayed(searchRunnable, 300)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Mostra subito tutte le tue chat (ambito "mine", query vuota).
        runSearch()
    }

    private fun updateScopeButtons() {
        mineBtn.text = if (scope == "mine") "● Le mie chat" else "Le mie chat"
        publicBtn.text = if (scope == "public") "● Canali pubblici" else "Canali pubblici"
    }

    private fun runSearch() {
        searchToken++
        val token = searchToken
        val q = queryField.text.toString()
        if (scope == "mine") searchMine(q) else searchPublic(q, token)
    }

    private fun searchMine(q: String) {
        val ql = q.trim().lowercase()
        val list = TdClient.orderedChats()
            .filter { ql.isEmpty() || it.title.lowercase().contains(ql) }
            .take(60)
        resultsBox.removeAllViews()
        if (list.isEmpty()) {
            showMessage("Nessuna chat trovata.")
            return
        }
        for (c in list) addChatRow(c)
    }

    private fun searchPublic(q: String, token: Int) {
        resultsBox.removeAllViews()
        val ql = q.trim()
        if (ql.length < 2) {
            showMessage("Scrivi almeno due lettere per cercare canali pubblici.")
            return
        }
        showMessage("Cerco…")
        TdClient.searchPublicChats(ql) { result ->
            if (result !is TdApi.Chats) return@searchPublicChats
            val ids = result.chatIds.take(25)
            runOnUiThread {
                if (token != searchToken) return@runOnUiThread
                resultsBox.removeAllViews()
                if (ids.isEmpty()) {
                    showMessage("Nessun canale pubblico trovato.")
                    return@runOnUiThread
                }
                for (id in ids) {
                    TdClient.getChat(id) { obj ->
                        if (obj is TdApi.Chat) runOnUiThread {
                            if (token == searchToken) addChatRow(obj)
                        }
                    }
                }
            }
        }
    }

    private fun showMessage(text: String) {
        resultsBox.removeAllViews()
        resultsBox.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xFFAAB4BE.toInt())
            textSize = 16f
            setPadding(8, 24, 8, 24)
        })
    }

    private fun addChatRow(chat: TdApi.Chat) {
        val v = LayoutInflater.from(this).inflate(R.layout.item_media_list, resultsBox, false)
        v.findViewById<TextView>(R.id.title).apply { text = chat.title; isSelected = true }
        v.findViewById<TextView>(R.id.subtitle).text = chatType(chat)
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        if (Settings.showChatImages(this)) {
            thumbs.loadImage(thumb, chat.photo?.small, chat.photo?.minithumbnail?.data)
        } else {
            thumb.tag = null
            thumb.setImageBitmap(null)
            thumb.setBackgroundColor(Color.parseColor("#223344"))
        }
        v.isFocusable = true
        v.setOnFocusChangeListener { view, hasFocus ->
            view.setBackgroundColor(if (hasFocus) 0xFF24445A.toInt() else 0x00000000)
        }
        v.setOnClickListener {
            startActivity(
                Intent(this, MediaListActivity::class.java)
                    .putExtra("chatId", chat.id)
                    .putExtra("title", chat.title)
            )
        }
        resultsBox.addView(v)
    }

    private fun chatType(chat: TdApi.Chat): String =
        when (chat.type.constructor) {
            TdApi.ChatTypeSupergroup.CONSTRUCTOR ->
                if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "Canale" else "Gruppo"
            TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> "Gruppo"
            TdApi.ChatTypePrivate.CONSTRUCTOR -> "Privato"
            else -> ""
        }

    private fun showCrash(e: Throwable) {
        val tv = TextView(this).apply {
            text = "Errore nella ricerca:\n" + e.toString() + "\n\n" +
                e.stackTrace.take(6).joinToString("\n")
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
            textSize = 14f
            setPadding(40, 40, 40, 40)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun makeButton(): Button {
        return Button(this).apply {
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 16f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 24
            layoutParams = lp
            setPadding(32, 20, 32, 20)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        debounce.removeCallbacks(searchRunnable)
        thumbs.stop()
    }
}
