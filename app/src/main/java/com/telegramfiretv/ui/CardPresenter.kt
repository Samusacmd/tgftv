package com.telegramfiretv.ui

import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import org.drinkless.tdlib.TdApi

class CardPresenter(private val thumbs: ThumbLoader) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(313, 176)
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val chat = item as TdApi.Chat
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
}
