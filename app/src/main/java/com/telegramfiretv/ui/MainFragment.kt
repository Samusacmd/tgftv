package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class MainFragment : BrowseSupportFragment() {

    private val thumbs = ThumbLoader()
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val chatsAdapter = ArrayObjectAdapter(CardPresenter(thumbs))
    private val chatsListener: () -> Unit = { activity?.runOnUiThread { refreshChats() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs.start()
        title = "Telegram Fire TV"
        headersState = HEADERS_DISABLED

        rowsAdapter.add(ListRow(HeaderItem(0, "Le tue chat"), chatsAdapter))
        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            val chat = item as TdApi.Chat
            startActivity(
                Intent(requireContext(), MediaListActivity::class.java)
                    .putExtra("chatId", chat.id)
                    .putExtra("title", chat.title)
            )
        }

        TdClient.addChatsListener(chatsListener)
        TdClient.loadChats(200)
    }

    private fun refreshChats() {
        val sorted = synchronized(TdClient.chats) { TdClient.chats.toList() }
            .sortedByDescending { mainOrder(it) }
        chatsAdapter.clear()
        chatsAdapter.addAll(0, sorted)
    }

    private fun mainOrder(chat: TdApi.Chat): Long {
        val pos = chat.positions?.firstOrNull {
            it.list.constructor == TdApi.ChatListMain.CONSTRUCTOR
        }
        if (pos != null && pos.order != 0L) return pos.order
        return (chat.lastMessage?.date ?: 0).toLong()
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbs.stop()
        TdClient.removeChatsListener(chatsListener)
    }
}
