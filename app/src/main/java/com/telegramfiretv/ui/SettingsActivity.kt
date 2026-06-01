package com.telegramfiretv.ui

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R

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

    fun savedPosition(c: Context, fileId: Int): Long = p(c).getLong("pos_$fileId", 0L)
    fun savePosition(c: Context, fileId: Int, ms: Long) { p(c).edit().putLong("pos_$fileId", ms).apply() }
    fun clearPosition(c: Context, fileId: Int) { p(c).edit().remove("pos_$fileId").apply() }
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
