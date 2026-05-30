package com.telegramfiretv.ui

import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

class CardPresenter : Presenter() {

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

        val photo = chat.photo
        val small = photo?.small
        val crisp = if (small != null && small.local.isDownloadingCompleted && small.local.path.isNotEmpty())
            decodeImageFile(small.local.path) else null
        val bmp = crisp ?: decodeImageBytes(photo?.minithumbnail?.data)
        card.mainImage = if (bmp != null) BitmapDrawable(card.resources, bmp) else null

        if (crisp == null && small != null && small.local.canBeDownloaded && !small.local.isDownloadingCompleted) {
            TdClient.downloadFile(small.id)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.titleText = null
        card.contentText = null
        card.mainImage = null
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
