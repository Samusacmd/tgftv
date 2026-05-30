package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi

data class MediaEntry(
    val fileId: Int,
    val title: String,
    val type: String,
    val durationSec: Int,
    val mini: ByteArray?
)

internal fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    return "%d:%02d".format(sec / 60, sec % 60)
}

class MediaListActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val containerId = View.generateViewId()
        setContentView(FrameLayout(this).apply { id = containerId })
        if (savedInstanceState == null) {
            val f = MediaListFragment().apply {
                arguments = Bundle().apply {
                    putLong("chatId", intent.getLongExtra("chatId", 0L))
                    putString("title", intent.getStringExtra("title"))
                }
            }
            supportFragmentManager.beginTransaction().replace(containerId, f).commit()
        }
    }
}

class MediaListFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val itemsAdapter = ArrayObjectAdapter(MediaPresenter())
    private val collected = mutableListOf<MediaEntry>()

    private var chatId = 0L
    private var oldest = 0L
    private var pages = 0
    private var emptyRetries = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = arguments?.getLong("chatId") ?: 0L
        title = arguments?.getString("title") ?: "Contenuti"
        headersState = HEADERS_DISABLED

        rowsAdapter.add(ListRow(HeaderItem(0, "Video e audio"), itemsAdapter))
        adapter = rowsAdapter

        setOnItemViewClickedListener { _, item, _, _ ->
            val e = item as MediaEntry
            startActivity(
                Intent(requireContext(), PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_FILE_ID, e.fileId)
                    .putExtra(PlayerActivity.EXTRA_LABEL, e.title)
            )
        }

        TdClient.openChat(chatId)
        loadPage(0L)
    }

    private fun loadPage(from: Long) {
        TdClient.getChatHistory(chatId, from, 50) { result ->
            val msgs = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            activity?.runOnUiThread {
                if (msgs.isEmpty()) {
                    if (collected.isEmpty() && emptyRetries > 0) {
                        emptyRetries--
                        view?.postDelayed({ loadPage(0L) }, 400)
                    } else {
                        finishLoading()
                    }
                    return@runOnUiThread
                }
                for (m in msgs) {
                    extractMedia(m)?.let { collected.add(it) }
                    oldest = m.id
                }
                pages++
                if (pages < 6 && collected.size < 50) loadPage(oldest) else finishLoading()
            }
        }
    }

    private fun finishLoading() {
        itemsAdapter.clear()
        if (collected.isEmpty()) {
            title = "Nessun video o audio trovato"
        } else {
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
        }
    }

    private fun extractMedia(msg: TdApi.Message): MediaEntry? {
        return when (val c = msg.content) {
            is TdApi.MessageVideo ->
                MediaEntry(c.video.video.id, c.video.fileName.ifEmpty { c.caption.text.ifEmpty { "Video" } }, "Video", c.video.duration, c.video.minithumbnail?.data)
            is TdApi.MessageAudio -> {
                val name = listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }
                    .joinToString(" - ").ifEmpty { c.audio.fileName.ifEmpty { "Audio" } }
                MediaEntry(c.audio.audio.id, name, "Audio", c.audio.duration, c.audio.albumCoverMinithumbnail?.data)
            }
            is TdApi.MessageVoiceNote -> MediaEntry(c.voiceNote.voice.id, "Messaggio vocale", "Audio", c.voiceNote.duration, null)
            is TdApi.MessageVideoNote -> MediaEntry(c.videoNote.video.id, "Video messaggio", "Video", c.videoNote.duration, c.videoNote.minithumbnail?.data)
            is TdApi.MessageAnimation -> MediaEntry(c.animation.animation.id, c.animation.fileName.ifEmpty { "GIF" }, "Video", c.animation.duration, c.animation.minithumbnail?.data)
            is TdApi.MessageDocument -> {
                val mime = c.document.mimeType
                when {
                    mime.startsWith("video/") -> MediaEntry(c.document.document.id, c.document.fileName.ifEmpty { "Video" }, "Video", 0, c.document.minithumbnail?.data)
                    mime.startsWith("audio/") -> MediaEntry(c.document.document.id, c.document.fileName.ifEmpty { "Audio" }, "Audio", 0, c.document.minithumbnail?.data)
                    else -> null
                }
            }
            else -> null
        }
    }
}

class MediaPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(313, 176)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as MediaEntry
        val card = viewHolder.view as ImageCardView
        card.titleText = e.title
        val dur = formatDuration(e.durationSec)
        card.contentText = if (dur.isNotEmpty()) "${e.type} · $dur" else e.type

        val data = e.mini
        card.mainImage = if (data != null) {
            val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
            if (bmp != null) BitmapDrawable(card.resources, bmp) else null
        } else null
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.titleText = null
        card.contentText = null
        card.mainImage = null
    }
}
