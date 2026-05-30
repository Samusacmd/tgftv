package com.telegramfiretv.ui

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R

object Settings {
    private fun p(c: Context) = c.getSharedPreferences("tgftv_settings", Context.MODE_PRIVATE)

    fun gridColumns(c: Context): Int = p(c).getInt("grid_cols", 4)
    fun cycleGridColumns(c: Context): Int {
        val next = when (gridColumns(c)) { 3 -> 4; 4 -> 5; 5 -> 6; else -> 3 }
        p(c).edit().putInt("grid_cols", next).apply()
        return next
    }

    fun listWidthPercent(c: Context): Int = p(c).getInt("list_width", 100)
    fun cycleListWidth(c: Context): Int {
        val next = when (listWidthPercent(c)) { 60 -> 80; 80 -> 100; else -> 60 }
        p(c).edit().putInt("list_width", next).apply()
        return next
    }

    fun playerDim(c: Context): Boolean = p(c).getBoolean("player_dim", true)
    fun togglePlayerDim(c: Context): Boolean {
        val next = !playerDim(c)
        p(c).edit().putBoolean("player_dim", next).apply()
        return next
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

        val gridBtn = makeButton()
        gridBtn.text = "Colonne griglia: ${Settings.gridColumns(this)}"
        gridBtn.setOnClickListener {
            gridBtn.text = "Colonne griglia: ${Settings.cycleGridColumns(this)}"
        }
        root.addView(gridBtn)

        val listBtn = makeButton()
        listBtn.text = "Larghezza elenco: ${Settings.listWidthPercent(this)}%"
        listBtn.setOnClickListener {
            listBtn.text = "Larghezza elenco: ${Settings.cycleListWidth(this)}%"
        }
        root.addView(listBtn)

        val dimBtn = makeButton()
        dimBtn.text = dimLabel()
        dimBtn.setOnClickListener {
            Settings.togglePlayerDim(this)
            dimBtn.text = dimLabel()
        }
        root.addView(dimBtn)

        setContentView(ScrollView(this).apply { addView(root) })
        gridBtn.requestFocus()
    }

    private fun dimLabel() =
        "Oscuramento player in pausa: ${if (Settings.playerDim(this)) "Sì" else "No"}"

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
