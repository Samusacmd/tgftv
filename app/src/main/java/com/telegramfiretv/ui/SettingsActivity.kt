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
import com.telegramfiretv.UpdateManager
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

    fun savedPosition(c: Context, key: String): Long = p(c).getLong("pos_$key", 0L)
    fun savePosition(c: Context, key: String, ms: Long) {
        p(c).edit().putLong("pos_$key", ms).apply()
        pruneOldPositions(c)
    }
    fun clearPosition(c: Context, key: String) { p(c).edit().remove("pos_$key").apply() }

    /** Evita che le posizioni salvate si accumulino all'infinito: tiene al massimo 300 voci. */
    private fun pruneOldPositions(c: Context) {
        val prefs = p(c)
        val keys = prefs.all.keys.filter { it.startsWith("pos_") }
        if (keys.size <= 300) return
        val edit = prefs.edit()
        keys.take(keys.size - 300).forEach { edit.remove(it) }
        edit.apply()
    }

    /** Riproduzione in streaming (senza attendere il download completo). Sperimentale. */
    fun streamingEnabled(c: Context): Boolean = p(c).getBoolean("streaming_enabled", true)
    fun toggleStreaming(c: Context): Boolean {
        val n = !streamingEnabled(c); p(c).edit().putBoolean("streaming_enabled", n).apply(); return n
    }

    /** Buffer minimo prima di avviare la riproduzione, in secondi di download stimato (5-120, step 5). */
    fun streamingBufferSec(c: Context): Int = p(c).getInt("streaming_buffer_sec", 5)
    fun adjustStreamingBuffer(c: Context, deltaSec: Int): Int {
        val v = (streamingBufferSec(c) + deltaSec).coerceIn(5, 120)
        p(c).edit().putInt("streaming_buffer_sec", v).apply(); return v
    }

    // ---- File video/audio già visti ----
    // Chiave = id univoco remoto del file (stessa chiave delle posizioni di ripresa):
    // stabile tra sessioni e tra chat diverse che contengono lo stesso file.
    fun isWatched(c: Context, key: String): Boolean =
        key.isNotEmpty() && (p(c).getStringSet("watched", emptySet()) ?: emptySet()).contains(key)

    fun markWatched(c: Context, key: String) {
        if (key.isEmpty()) return
        val cur = HashSet(p(c).getStringSet("watched", emptySet()) ?: emptySet())
        if (cur.add(key)) p(c).edit().putStringSet("watched", cur).apply()
    }

    fun watchedCount(c: Context): Int = (p(c).getStringSet("watched", emptySet()) ?: emptySet()).size

    /** Inverte lo stato "già visto" del file. Ritorna il nuovo stato (true = visto). */
    fun toggleWatched(c: Context, key: String): Boolean {
        if (key.isEmpty()) return false
        val cur = HashSet(p(c).getStringSet("watched", emptySet()) ?: emptySet())
        val now = if (cur.contains(key)) { cur.remove(key); false } else { cur.add(key); true }
        p(c).edit().putStringSet("watched", cur).apply()
        return now
    }

    fun clearWatched(c: Context) { p(c).edit().remove("watched").apply() }
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

        // I pulsanti del menu vengono raccolti qui e poi disposti in griglia a due colonne.
        val menuButtons = mutableListOf<Button>()

        val chatViewBtn = makeButton()
        chatViewBtn.text = "Vista chat: ${if (Settings.chatViewMode(this) == "grid") "Griglia" else "Elenco"}"
        chatViewBtn.setOnClickListener {
            val v = Settings.cycleChatView(this)
            chatViewBtn.text = "Vista chat: ${if (v == "grid") "Griglia" else "Elenco"}"
        }
        menuButtons.add(chatViewBtn)

        val chatImgBtn = makeButton()
        fun imgLabel() = "Immagini chat: ${if (Settings.showChatImages(this)) "Sì" else "No"}"
        chatImgBtn.text = imgLabel()
        chatImgBtn.setOnClickListener { Settings.toggleChatImages(this); chatImgBtn.text = imgLabel() }
        menuButtons.add(chatImgBtn)

        val filterBtn = makeButton()
        fun filterLabel() = "Mostra: ${if (Settings.mediaFilter(this) == "all") "Tutto" else "Solo video e audio"}"
        filterBtn.text = filterLabel()
        filterBtn.setOnClickListener { Settings.cycleMediaFilter(this); filterBtn.text = filterLabel() }
        menuButtons.add(filterBtn)

        val gridBtn = makeButton()
        gridBtn.text = "Colonne griglia: ${Settings.gridColumns(this)}"
        gridBtn.setOnClickListener { gridBtn.text = "Colonne griglia: ${Settings.cycleGridColumns(this)}" }
        menuButtons.add(gridBtn)

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
        menuButtons.add(listBtn)

        val dimBtn = makeButton()
        fun dimLabel() = "Oscuramento player in pausa: ${if (Settings.playerDim(this)) "Sì" else "No"}"
        dimBtn.text = dimLabel()
        dimBtn.setOnClickListener { Settings.togglePlayerDim(this); dimBtn.text = dimLabel() }
        menuButtons.add(dimBtn)

        val streamBtn = makeButton()
        fun streamLabel() = "Riproduzione in streaming: ${if (Settings.streamingEnabled(this)) "Attiva" else "Disattiva"}"
        streamBtn.text = streamLabel()
        streamBtn.setOnClickListener { Settings.toggleStreaming(this); streamBtn.text = streamLabel() }
        menuButtons.add(streamBtn)

        val bufferBtn = makeButton()
        fun bufferLabel() = "Buffer iniziale streaming: ${Settings.streamingBufferSec(this)}s  ◀ ▶"
        bufferBtn.text = bufferLabel()
        bufferBtn.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { Settings.adjustStreamingBuffer(this, -5); bufferBtn.text = bufferLabel(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { Settings.adjustStreamingBuffer(this, +5); bufferBtn.text = bufferLabel(); true }
                    else -> false
                }
            } else false
        }
        menuButtons.add(bufferBtn)

        val refreshBtn = makeButton()
        refreshBtn.text = "Aggiorna chat"
        refreshBtn.setOnClickListener {
            MediaListActivity.clearCache()
            TdClient.loadChats(200)
            Toast.makeText(this, "Chat aggiornate", Toast.LENGTH_SHORT).show()
            refreshBtn.text = "Aggiorna chat  ✓"
        }
        menuButtons.add(refreshBtn)

        val cacheBtn = makeButton()
        cacheBtn.text = "Svuota cache"
        cacheBtn.setOnClickListener {
            MediaListActivity.clearCache()
            cacheBtn.text = "Svuoto…"
            TdClient.clearCache { obj ->
                val mb = if (obj is TdApi.StorageStatistics) obj.size / (1024 * 1024) else -1L
                runOnUiThread {
                    cacheBtn.text = if (mb >= 0) "Svuota cache (occupati ${mb} MB)" else "Svuota cache  ✓"
                    Toast.makeText(this, "Cache liberata", Toast.LENGTH_SHORT).show()
                }
            }
        }
        menuButtons.add(cacheBtn)

        val writeBtn = makeButton()
        writeBtn.text = "Abilita scrittura  ▸"
        writeBtn.setOnClickListener {
            startActivity(android.content.Intent(this, WriteSettingsActivity::class.java))
        }
        menuButtons.add(writeBtn)

        val watchedBtn = makeButton()
        watchedBtn.text = "Azzera file già visti (${Settings.watchedCount(this)})"
        watchedBtn.setOnClickListener {
            Settings.clearWatched(this)
            MediaListActivity.clearCache()
            watchedBtn.text = "Azzera file già visti  ✓"
            Toast.makeText(this, "Elenco dei file visti azzerato", Toast.LENGTH_SHORT).show()
        }
        menuButtons.add(watchedBtn)

        val aboutBtn = makeButton()
        aboutBtn.text = "Informazioni"
        aboutBtn.setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        menuButtons.add(aboutBtn)

        val updateBtn = makeButton()
        updateBtn.text = "Update"
        updateBtn.setOnClickListener {
            updateBtn.text = "Update — controllo…"
            UpdateManager.checkForUpdate(
                this,
                onStatus = { s -> runOnUiThread { updateBtn.text = s } },
                onFound = { ver, start ->
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("Aggiornamento disponibile")
                            .setMessage("È disponibile FiregramTV $ver (installata: ${com.telegramfiretv.BuildConfig.VERSION_NAME}).\nScaricare e installare?")
                            .setPositiveButton("Sì") { _, _ -> start() }
                            .setNegativeButton("No") { _, _ -> updateBtn.text = "Update" }
                            .setCancelable(true)
                            .show()
                    }
                }
            )
        }
        menuButtons.add(updateBtn)

        val logoutBtn = makeButton()
        logoutBtn.text = "Disconnetti account"
        logoutBtn.setTextColor(0xFFFF6B6B.toInt())
        logoutBtn.setOnClickListener {
            if (logoutBtn.text.startsWith("Conferma")) {
                MediaListActivity.clearCache()
                TdClient.logout()
                val i = android.content.Intent(this, LoginActivity::class.java)
                i.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(i)
            } else {
                logoutBtn.text = "Conferma disconnessione?"
                logoutBtn.setBackgroundColor(0xFFB23A3A.toInt())
                logoutBtn.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        menuButtons.add(logoutBtn)

        // Griglia a due colonne: tasti più compatti e metà scorrimento rispetto all'elenco.
        var i = 0
        while (i < menuButtons.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(menuButtons[i], LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { rightMargin = 16; bottomMargin = 12 })
            if (i + 1 < menuButtons.size) {
                row.addView(menuButtons[i + 1], LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { bottomMargin = 12 })
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            root.addView(row)
            i += 2
        }

        // Scorrimento rapido: dal primo elemento premendo SU si salta all'ultimo (e
        // dall'ultimo premendo GIÙ si torna al primo). Così le impostazioni in fondo si
        // raggiungono subito; la ScrollView segue automaticamente il focus.
        chatViewBtn.id = View.generateViewId()
        logoutBtn.id = View.generateViewId()
        chatViewBtn.nextFocusUpId = logoutBtn.id
        logoutBtn.nextFocusDownId = chatViewBtn.id

        setContentView(ScrollView(this).apply { addView(root) })
        chatViewBtn.requestFocus()
    }

    private fun makeButton(): Button {
        return Button(this).apply {
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 15f
            setPadding(24, 18, 24, 18)
        }
    }
}
