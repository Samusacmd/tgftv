package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import com.telegramfiretv.R
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class ChatGridFragment : VerticalGridSupportFragment() {

    private val thumbs = ThumbLoader()
    private lateinit var chatsAdapter: ArrayObjectAdapter
    private var grid = false
    private var listName = "main"
    private val uiHandler = Handler(Looper.getMainLooper())
    // Firma dell'ultima lista mostrata: se non cambia, non ricostruiamo l'adapter (niente flicker).
    private var lastSig: String = ""
    // Debounce: all'avvio arrivano centinaia di UpdateChatPosition a raffica; le raggruppiamo
    // in un solo refresh dopo una breve pausa invece di ricostruire la griglia ogni volta.
    private val refreshRunnable = Runnable { refresh() }
    private val chatsListener: () -> Unit = { scheduleRefresh() }

    private fun scheduleRefresh() {
        uiHandler.removeCallbacks(refreshRunnable)
        uiHandler.postDelayed(refreshRunnable, 150)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs.start()
        grid = Settings.chatViewMode(requireContext()) == "grid"
        listName = arguments?.getString("list") ?: "main"
        title = null

        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false).apply {
            numberOfColumns = if (grid) Settings.gridColumns(requireContext()) else 1
        }
        val presenter: Presenter = if (grid) CardPresenter(thumbs) else ListChatPresenter(thumbs)
        chatsAdapter = ArrayObjectAdapter(presenter)
        adapter = chatsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            val chat = item as TdApi.Chat
            val intent = if (chat.type.constructor == TdApi.ChatTypePrivate.CONSTRUCTOR)
                Intent(requireContext(), BotChatActivity::class.java)
            else
                Intent(requireContext(), MediaListActivity::class.java)
            startActivity(
                intent
                    .putExtra("chatId", chat.id)
                    .putExtra("title", chat.title)
            )
        }

        TdClient.addChatsListener(chatsListener)
        TdClient.loadChats(200)
        refresh()
    }

    private fun refresh() {
        val list = if (listName == "archive") TdClient.orderedArchiveChats() else TdClient.orderedMainChats()
        // Se la lista (ordine + id + titoli) è identica a quella già mostrata, non tocchiamo
        // l'adapter: evita la ricostruzione completa che causava lo sfarfallio.
        val sig = list.joinToString("|") { "${it.id}:${it.title}" }
        if (sig == lastSig) return
        lastSig = sig
        chatsAdapter.clear()
        chatsAdapter.addAll(0, list)
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(refreshRunnable)
        thumbs.stop()
        TdClient.removeChatsListener(chatsListener)
    }
}

class ListChatPresenter(private val thumbs: ThumbLoader) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_list, parent, false)
        val pct = Settings.listWidthPercent(parent.context)
        val width = parent.context.resources.displayMetrics.widthPixels * pct / 100
        v.layoutParams?.let { it.width = width }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val chat = item as TdApi.Chat
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.title).apply { text = chat.title; isSelected = true }
        v.findViewById<TextView>(R.id.subtitle).text = chatType(chat)
        val thumb = v.findViewById<ImageView>(R.id.thumb)
        if (Settings.showChatImages(v.context)) {
            thumbs.loadImage(thumb, chat.photo?.small, chat.photo?.minithumbnail?.data)
        } else {
            thumb.tag = null
            thumb.setImageBitmap(null)
            thumb.setBackgroundColor(Color.parseColor("#223344"))
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.thumb)?.apply { setImageBitmap(null); tag = null }
    }

    private fun chatType(chat: TdApi.Chat): String =
        when (chat.type.constructor) {
            TdApi.ChatTypeSupergroup.CONSTRUCTOR ->
                if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "Canale" else "Gruppo"
            TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> "Gruppo"
            TdApi.ChatTypePrivate.CONSTRUCTOR -> "Privato"
            else -> ""
        }
}
