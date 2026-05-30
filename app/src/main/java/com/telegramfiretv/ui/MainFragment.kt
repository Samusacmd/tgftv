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

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val chatsAdapter = ArrayObjectAdapter(CardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        TdClient.onChatsChanged = { activity?.runOnUiThread { refreshChats() } }
        TdClient.loadChats(200)
    }

    private fun refreshChats() {
        chatsAdapter.clear()
        synchronized(TdClient.chats) {
            chatsAdapter.addAll(0, TdClient.chats.toList())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TdClient.onChatsChanged = null
    }
}
