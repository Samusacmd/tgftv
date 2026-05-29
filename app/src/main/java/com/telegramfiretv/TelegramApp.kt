package com.telegramfiretv

import android.app.Application
import com.telegramfiretv.tdlib.TdClient

class TelegramApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Carica la libreria nativa di TDLib e inizializza il client.
        TdClient.init(this)
    }
}
