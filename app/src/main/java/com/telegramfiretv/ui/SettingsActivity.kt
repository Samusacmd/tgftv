package com.telegramfiretv.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
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

object Settings {
    private fun p(c: Context) = c.getSharedPreferences("tgftv_settings", Context.MODE_PRIVATE)

    fun gridColumns(c: Context): Int = p(c).getInt("grid_cols", 5)
    fun cycleGridColumns(c: Context): Int {
        val next = when (gridColumns(c)) { 3 -> 4; 4 -> 5; 5 -> 6; 6 -> 7; else -> 3 }
        p(c).edit().putInt("grid_cols", next).apply(); return next
    }

    fun listWidthPercent(c: Context): Int = p(c).getInt("list_width", 50)
    fun adjustListWidth(c: Context, delta: Int): Int {
        val v = (listWidthPercent(c) + delta).coerceIn(20, 100)
        p(c).edit().putInt("list_width", v).apply(); return v
    }

    fun playerDim(c: Context): Boolean = p(c).getBoolean("player_dim", true)
    fun togglePlayerDim(c: Context): Boolean {
        val n = !playerDim(c); p(c).edit().putBoolean("player_dim", n).apply(); return n
    }

    fun chatViewMode(c: Context): String = p(c).getString("chat_view", "grid") ?: "grid"
    fun cycleChatView(c: Context): String {
        val n = if (chatViewMode(c) == "list") "grid" else "list"
        p(c).edit().putString("chat_view", n).apply(); return n
    }

    fun showChatImages(c: Context): Boolean = p(c).getBoolean("chat_images", true)
    fun toggleChatImages(c: Context): Boolean {
        val n = !showChatImages(c); p(c).edit().putBoolean("chat_images", n).apply(); return n
    }

    fun mediaFilter(c: Context): String = p(c).getString("media_filter", "all") ?: "all"
    fun cycleMediaFilter(c: Context): String {
        val n = if (mediaFilter(c) == "all") "av" else "all"
        p(c).edit().putString("media_filter", n).apply(); return n
    }

    fun writeFlag(c: Context, key: String, def: Boolean): Boolean = p(c).getBoolean("write_$key", def)
    fun toggleWriteFlag(c: Context, key: String, def: Boolean): Boolean {
        val n = !writeFlag(c, key, def)
        p(c).edit().putBoolean("write_$key", n).apply(); return n
    }

    fun savedPosition(c: Context, fileId: Int): Long = p(c).getLong("pos_$fileId", 0L)
    fun savePosition(c: Context, fileId: Int, ms: Long) { p(c).edit().putLong("pos_$fileId", ms).apply() }
    fun clearPosition(c: Context, fileId: Int) { p(c).edit().remove("pos_$fileId").apply() }

    /** Riproduzione in streaming (senza attendere il download completo). Sperimentale. */
    fun streamingEnabled(c: Context): Boolean = p(c).getBoolean("streaming_enabled", true)
    fun toggleStreaming(c: Context): Boolean {
        val n = !streamingEnabled(c); p(c).edit().putBoolean("streaming_enabled", n).apply(); return n
    }

    /** Buffer minimo prima di avviare la riproduzione, in secondi di download stimato.
     *  Regolabile con sinistra/destra a passi di 10 secondi (intervallo 10-120). */
    fun streamingBufferSec(c: Context): Int = p(c).getInt("streaming_buffer_sec", 10)
    fun adjustStreamingBuffer(c: Context, deltaSec: Int): Int {
        val base = (streamingBufferSec(c) / 10) * 10            // normalizza a multiplo di 10
        val v = (base + deltaSec).coerceIn(10, 120)
        p(c).edit().putInt("streaming_buffer_sec", v).apply(); return v
    }
}

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Impostazioni"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setPadding(0, 0, 0, 32)
        })

        val chatViewBtn = makeButton()
        chatViewBtn.text = "Vista chat: ${if (Settings.chatViewMode(this) == "grid") "Griglia" else "Elenco"}"
        chatViewBtn.setOnClickListener {
            val v = Settings.cycleChatView(this)
            chatViewBtn.text = "Vista chat: ${if (v == "grid") "Griglia" else "Elenco"}"
        }
        root.addView(chatViewBtn)

        val chatImgBtn = makeButton()
        fun imgLabel() = "Immagini chat: ${if (Settings.showChatImages(this)) "Sì" else "No"}"
        chatImgBtn.text = imgLabel()
        chatImgBtn.setOnClickListener { Settings.toggleChatImages(this); chatImgBtn.text = imgLabel() }
        root.addView(chatImgBtn)

        val filterBtn = makeButton()
        fun filterLabel() = "Mostra: ${if (Settings.mediaFilter(this) == "all") "Tutto" else "Solo video e audio"}"
        filterBtn.text = filterLabel()
        filterBtn.setOnClickListener { Settings.cycleMediaFilter(this); filterBtn.text = filterLabel() }
        root.addView(filterBtn)

        val gridBtn = makeButton()
        gridBtn.text = "Colonne griglia: ${Settings.gridColumns(this)}"
        gridBtn.setOnClickListener { gridBtn.text = "Colonne griglia: ${Settings.cycleGridColumns(this)}" }
        root.addView(gridBtn)

        val listBtn = makeButton()
        fun widthLabel() = "Larghezza elenco: ${Settings.listWidthPercent(this)}%  ◀ ▶"
        listBtn.text = widthLabel()
        listBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { Settings.adjustListWidth(this, -10); listBtn.text = widthLabel(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { Settings.adjustListWidth(this, +10); listBtn.text = widthLabel(); true }
                    else -> false
                }
            } else false
        }
        root.addView(listBtn)

        val dimBtn = makeButton()
        fun dimLabel() = "Oscuramento player in pausa: ${if (Settings.playerDim(this)) "Sì" else "No"}"
        dimBtn.text = dimLabel()
        dimBtn.setOnClickListener { Settings.togglePlayerDim(this); dimBtn.text = dimLabel() }
        root.addView(dimBtn)

        val streamBtn = makeButton()
        fun streamLabel() = "Riproduzione in streaming: ${if (Settings.streamingEnabled(this)) "Attiva" else "Disattiva"}"
        streamBtn.text = streamLabel()
        streamBtn.setOnClickListener { Settings.toggleStreaming(this); streamBtn.text = streamLabel() }
        root.addView(streamBtn)

        val bufferBtn = makeButton()
        fun bufferLabel() = "Buffer iniziale streaming: ${Settings.streamingBufferSec(this)}s  ◀ ▶"
        bufferBtn.text = bufferLabel()
        bufferBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { Settings.adjustStreamingBuffer(this, -10); bufferBtn.text = bufferLabel(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { Settings.adjustStreamingBuffer(this, +10); bufferBtn.text = bufferLabel(); true }
                    else -> false
                }
            } else false
        }
        root.addView(bufferBtn)

        val refreshBtn = makeButton()
        refreshBtn.text = "Aggiorna chat"
        refreshBtn.setOnClickListener {
            MediaListActivity.cache = null
            MediaListActivity.cacheChatId = -1
            TdClient.loadChats(200)
            Toast.makeText(this, "Chat aggiornate", Toast.LENGTH_SHORT).show()
            refreshBtn.text = "Aggiorna chat  ✓"
        }
        root.addView(refreshBtn)

        val cacheBtn = makeButton()
        cacheBtn.text = "Svuota cache"
        cacheBtn.setOnClickListener {
            MediaListActivity.cache = null
            MediaListActivity.cacheChatId = -1
            cacheBtn.text = "Svuoto…"
            TdClient.clearCache { obj ->
                val mb = if (obj is TdApi.StorageStatistics) obj.size / (1024 * 1024) else -1L
                runOnUiThread {
                    cacheBtn.text = if (mb >= 0) "Svuota cache (occupati ${mb} MB)" else "Svuota cache  ✓"
                    Toast.makeText(this, "Cache liberata", Toast.LENGTH_SHORT).show()
                }
            }
        }
        root.addView(cacheBtn)

        val writeBtn = makeButton()
        writeBtn.text = "Abilita scrittura  ▸"
        writeBtn.setOnClickListener {
            startActivity(android.content.Intent(this, WriteSettingsActivity::class.java))
        }
        root.addView(writeBtn)

        // Scorrimento rapido: dal primo elemento premendo SU si salta all'ultimo (e
        // dall'ultimo premendo GIÙ si torna al primo). Così le impostazioni in fondo si
        // raggiungono subito; la ScrollView segue automaticamente il focus.
        chatViewBtn.id = View.generateViewId()
        writeBtn.id = View.generateViewId()
        chatViewBtn.nextFocusUpId = writeBtn.id
        writeBtn.nextFocusDownId = chatViewBtn.id

        setContentView(ScrollView(this).apply { addView(root) })
        chatViewBtn.requestFocus()
    }

    private fun makeButton(): Button {
        return Button(this).apply {
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 18f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 24
            layoutParams = lp
            setPadding(32, 28, 32, 28)
        }
    }
}
