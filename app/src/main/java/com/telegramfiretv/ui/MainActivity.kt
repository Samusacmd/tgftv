package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.BuildConfig
import com.telegramfiretv.R
import com.telegramfiretv.UpdateManager

class MainActivity : FragmentActivity() {

    private var containerId = 0
    private lateinit var jumpTopBtn: Button
    private lateinit var jumpBottomBtn: Button
    private var currentSig: String? = null
    private var currentList = "main"
    private lateinit var chatTab: Button
    private lateinit var archiveTab: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Elimina l'APK di un eventuale aggiornamento precedente (ormai installato).
        UpdateManager.cleanupOldDownloads(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 24, 48, 12)
        }
        chatTab = tabButton("Chat")
        archiveTab = tabButton("Archiviate")
        val searchBtn = tabButton("Cerca")
        chatTab.setOnClickListener { switchTo("main") }
        archiveTab.setOnClickListener { switchTo("archive") }
        searchBtn.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        // Tasto hamburger (☰): apre il menu impostazioni anche sui telecomandi Android TV
        // senza tasto MENU fisico. Compatto, circa metà degli altri tasti.
        val menuBtn = Button(this).apply {
            text = "\u2261"
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 20f
            minWidth = 0
            minimumWidth = 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 16
            layoutParams = lp
            setPadding(20, 16, 20, 16)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        bar.addView(menuBtn)
        bar.addView(chatTab)
        bar.addView(archiveTab)
        bar.addView(searchBtn)
        // Spinge l'intestazione sul bordo destro della barra.
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextView(this@MainActivity).apply {
                text = "FiregramTV"
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 22f
            })
            addView(TextView(this@MainActivity).apply {
                text = "build ${BuildConfig.VERSION_NAME}"
                setTextColor(0xFFAAB4BE.toInt())
                textSize = 12f
            })
        })
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        containerId = View.generateViewId()
        root.addView(
            FrameLayout(this).apply { id = containerId },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Tasti "vai all'inizio" / "vai alla fine" dell'elenco chat, fissi sulla sinistra
        // dello schermo (in alto e in basso): utili con molte chat, senza dover scorrere
        // manualmente tutta la lista.
        fun makeJumpButton(symbol: String, onClick: () -> Unit) = Button(this).apply {
            text = symbol
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 18f
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 18, 24, 18)
            setOnClickListener { onClick() }
        }
        jumpTopBtn = makeJumpButton("▲") { jumpChatList(toTop = true) }
        jumpBottomBtn = makeJumpButton("▼") { jumpChatList(toTop = false) }
        val overlay = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(jumpTopBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            // Più in basso del tasto ☰: prima si sovrapponeva al menu.
            ).also { it.topMargin = 220; it.leftMargin = 16 })
            addView(jumpBottomBtn, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).also { it.bottomMargin = 48; it.leftMargin = 16 })
        }

        setContentView(overlay)
        updateTabs()
        showFragment()
    }

    /**
     * Con liste lunghe, premere SINISTRA sull'ultima chat selezionata non sempre arriva ai
     * tasti ▲/▼ (la ricerca automatica del focus può fallire su salti grandi sullo schermo).
     * Intercettiamo qui SINISTRA e spostiamo il focus direttamente sul tasto più vicino
     * (▲ se l'elemento selezionato è nella metà alta dello schermo, ▼ altrimenti).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused != null && focused !== jumpTopBtn && focused !== jumpBottomBtn) {
                val loc = IntArray(2)
                focused.getLocationOnScreen(loc)
                val screenHeight = resources.displayMetrics.heightPixels
                if (loc[1] < screenHeight / 2) jumpTopBtn.requestFocus() else jumpBottomBtn.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchTo(list: String) {
        if (list == currentList) return
        currentList = list
        updateTabs()
        showFragment()
    }

    /** Sposta la selezione all'inizio o alla fine dell'elenco chat attualmente mostrato. */
    private fun jumpChatList(toTop: Boolean) {
        val grid = findViewById<View>(containerId)
            ?.findViewById<androidx.leanback.widget.VerticalGridView>(androidx.leanback.R.id.browse_grid)
            ?: return
        val count = grid.adapter?.itemCount ?: return
        if (count == 0) return
        grid.selectedPosition = if (toTop) 0 else count - 1
    }

    private fun updateTabs() {
        chatTab.text = if (currentList == "main") "\u25CF Chat" else "Chat"
        archiveTab.text = if (currentList == "archive") "\u25CF Archiviate" else "Archiviate"
    }

    override fun onResume() {
        super.onResume()
        if (currentSig != null && currentSig != signature()) showFragment()
    }

    private fun signature() =
        "${Settings.chatViewMode(this)}|${Settings.showChatImages(this)}|${Settings.gridColumns(this)}|${Settings.listWidthPercent(this)}"

    private fun showFragment() {
        currentSig = signature()
        val f = ChatGridFragment().apply {
            arguments = Bundle().apply { putString("list", currentList) }
        }
        supportFragmentManager.beginTransaction()
            .replace(containerId, f)
            .commitAllowingStateLoss()
    }

    private fun tabButton(label: String): Button {
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
            layoutParams = lp
            setPadding(36, 16, 36, 16)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
