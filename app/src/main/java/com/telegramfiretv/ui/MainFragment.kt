package com.telegramfiretv.ui

import android.content.Intent
import android.os.Bundle
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class MainFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val chatsAdapter = ArrayObjectAdapter(CardPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Telegram Fire TV"
        headersState = HEADERS_DISABLED

        val header = HeaderItem(0, "Le tue chat")
        rowsAdapter.add(ListRow(header, chatsAdapter))
        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            val chat = item as TdApi.Chat
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_CHAT_ID, chat.id)
                    .putExtra(PlayerActivity.EXTRA_CHAT_TITLE, chat.title)
            )
        }

        TdClient.onChatsChanged = { activity?.runOnUiThread { refreshChats() } }
        TdClient.loadChats()
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
