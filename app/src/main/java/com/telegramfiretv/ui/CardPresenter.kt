package com.telegramfiretv.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class CardPresenter(private val thumbs: ThumbLoader) : Presenter() {

    /** ViewHolder con riferimento alla chat attualmente mostrata (serve per "tieni premuto per uscire"). */
    private class VH(view: android.view.View) : Presenter.ViewHolder(view) {
        var chat: TdApi.Chat? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(313, 176)
        }
        val vh = VH(cardView)

        // "Tieni premuto OK per 5 secondi" per uscire dal canale/gruppo, con conferma.
        // Usiamo il tasto (non un semplice OnLongClickListener, che scatta troppo presto)
        // per poter misurare esattamente i 5 secondi di pressione continua.
        val handler = Handler(Looper.getMainLooper())
        var holding = false
        var triggered = false
        val holdRunnable = Runnable {
            triggered = true
            holding = false
            vh.chat?.let { confirmLeaveChat(cardView.context, it) }
        }
        cardView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0 && !holding) {
                            holding = true
                            triggered = false
                            handler.postDelayed(holdRunnable, 5000)
                        }
                        false
                    }
                    KeyEvent.ACTION_UP -> {
                        if (holding) {
                            handler.removeCallbacks(holdRunnable)
                            holding = false
                        }
                        // Se è scattata l'uscita durante la pressione, blocchiamo il rilascio
                        // per non far scattare ANCHE l'apertura normale della chat.
                        val wasTriggered = triggered
                        triggered = false
                        wasTriggered
                    }
                    else -> false
                }
            } else false
        }
        return vh
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val chat = item as TdApi.Chat
        val vh = viewHolder as VH
        vh.chat = chat
        val card = viewHolder.view as ImageCardView
        card.titleText = chat.title
        card.contentText = chatTypeLabel(chat)

        if (Settings.showChatImages(card.context)) {
            thumbs.load(card, chat.photo?.small, chat.photo?.minithumbnail?.data)
        } else {
            card.tag = null
            card.mainImage = ColorDrawable(0xFF22303A.toInt())
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder as VH).chat = null
        val card = viewHolder.view as ImageCardView
        card.titleText = null
        card.contentText = null
        card.mainImage = null
        card.tag = null
    }

    private fun chatTypeLabel(chat: TdApi.Chat): String =
        when (chat.type.constructor) {
            TdApi.ChatTypeSupergroup.CONSTRUCTOR ->
                if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "Canale" else "Gruppo"
            TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> "Gruppo"
            TdApi.ChatTypePrivate.CONSTRUCTOR -> "Privato"
            else -> ""
        }

    /** Avviso di conferma prima di uscire; alla conferma esce, aggiorna la lista e pulisce la cache. */
    private fun confirmLeaveChat(context: Context, chat: TdApi.Chat) {
        android.app.AlertDialog.Builder(context)
            .setTitle("Uscire da \"${chat.title}\"?")
            .setMessage("Verrai rimosso da questo canale/gruppo. Per rientrare potrebbe servire un nuovo invito.")
            .setPositiveButton("Esci") { _, _ ->
                TdClient.leaveChat(chat.id) {
                    // All'uscita: aggiorna la lista chat e pulisce automaticamente la cache
                    // dei media, che altrimenti resterebbe legata a un canale non più presente.
                    TdClient.loadChats(200)
                    MediaListActivity.clearCache()
                    Toast.makeText(context, "Uscito da \"${chat.title}\"", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .setCancelable(true)
            .show()
    }
}
