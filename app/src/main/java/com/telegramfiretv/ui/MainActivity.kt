package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private var containerId = 0
    private var currentSig: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        containerId = View.generateViewId()
        setContentView(FrameLayout(this).apply { id = containerId })
        showFragment()
    }

    override fun onResume() {
        super.onResume()
        if (currentSig != null && currentSig != signature()) showFragment()
    }

    private fun signature() =
        "${Settings.chatViewMode(this)}|${Settings.showChatImages(this)}|${Settings.gridColumns(this)}|${Settings.listWidthPercent(this)}"

    private fun showFragment() {
        currentSig = signature()
        supportFragmentManager.beginTransaction()
            .replace(containerId, ChatGridFragment())
            .commitAllowingStateLoss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
