package com.telegramfiretv.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.R

class WriteSettingsActivity : FragmentActivity() {

    // chiave -> (etichetta, valore di default)
    private val items = listOf(
        Triple("private", "Chat private", true),
        Triple("bots", "Bot", true),
        Triple("groups", "Gruppi", true),
        Triple("forum", "Gruppi con sottotopic", true),
        Triple("admin_channels", "Canali dove sei admin", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "Abilita scrittura"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 28f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Scegli in quali tipi di chat poter scrivere."
            setTextColor(0xFFAAB4BE.toInt())
            textSize = 16f
            setPadding(0, 0, 0, 28)
        })

        for ((key, label, def) in items) {
            val btn = makeButton()
            fun render() {
                val on = Settings.writeFlag(this, key, def)
                btn.text = (if (on) "\u2611  " else "\u2610  ") + label
            }
            render()
            btn.setOnClickListener {
                Settings.toggleWriteFlag(this, key, def)
                render()
            }
            root.addView(btn)
        }

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        scroll.requestFocus()
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
