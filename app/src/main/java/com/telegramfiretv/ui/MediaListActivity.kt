package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import com.telegramfiretv.R
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

data class MediaEntry(
    val fileId: Int,
    val title: String,
    val type: String,
    val durationSec: Int,
    val mini: ByteArray?,
    val thumbFile: TdApi.File?
)

data class TopicEntry(val threadId: Long, val name: String)

internal fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    return "%d:%02d".format(sec / 60, sec % 60)
}

internal fun decodeImageBytes(data: ByteArray?): Bitmap? =
    if (data == null) null else BitmapFactory.decodeByteArray(data, 0, data.size)

internal fun decodeImageFile(path: String?): Bitmap? =
    if (path.isNullOrEmpty()) null else BitmapFactory.decodeFile(path)

private val PLACEHOLDER = ColorDrawable(0xFF22303A.toInt())

class ThumbLoader {
    private val main = Handler(Looper.getMainLooper())
    private class Target(val ref: WeakReference<View>, val apply: (Bitmap?) -> Unit)
    private val waiting = ConcurrentHashMap<Int, MutableList<Target>>()
    private val listener: (TdApi.File) -> Unit = { onFile(it) }

    fun start() = TdClient.addFileListener(listener)
    fun stop() { TdClient.removeFileListener(listener); waiting.clear() }

    fun load(card: ImageCardView, thumbFile: TdApi.File?, mini: ByteArray?) =
        bind(card, { bmp ->
            card.mainImage = if (bmp != null) BitmapDrawable(card.resources, bmp) else PLACEHOLDER
        }, thumbFile, mini)

    fun loadImage(image: ImageView, thumbFile: TdApi.File?, mini: ByteArray?) =
        bind(image, { bmp -> image.setImageBitmap(bmp) }, thumbFile, mini)

    private fun bind(view: View, apply: (Bitmap?) -> Unit, thumbFile: TdApi.File?, miniData: ByteArray?) {
        val mini = decodeImageBytes(miniData)
        if (thumbFile == null) { view.tag = null; apply(mini); return }
        val local = thumbFile.local
        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            view.tag = null; apply(decodeImageFile(local.path) ?: mini); return
        }
        view.tag = thumbFile.id
        apply(mini)
        waiting.getOrPut(thumbFile.id) { mutableListOf() }.add(Target(WeakReference(view), apply))
        if (local.canBeDownloaded) TdClient.downloadFile(thumbFile.id) { obj -> if (obj is TdApi.File) onFile(obj) }
    }

    private fun onFile(file: TdApi.File) {
        if (!file.local.isDownloadingCompleted || file.local.path.isEmpty()) return
        val id = file.id
        val path = file.local.path
        main.post {
            val list = waiting.remove(id) ?: return@post
            val bmp = decodeImageFile(path) ?: return@post
            for (t in list) {
                val v = t.ref.get() ?: continue
                if (v.tag == id) t.apply(bmp)
            }
        }
    }
}

class MediaListActivity : FragmentActivity() {

    private var chatId = 0L
    private var threadId = 0L
    private var titleText: String? = null
    private var mode = "grid"

    companion object {
        var cache: List<MediaEntry>? = null
        var cacheChatId: Long = -1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        threadId = intent.getLongExtra("threadId", 0L)
        titleText = intent.getStringExtra("title")
        mode = intent.getStringExtra("mode") ?: "grid"

        val containerId = View.generateViewId()
        setContentView(FrameLayout(this).apply { id = containerId })

        if (savedInstanceState == null) {
            val f = MediaGridFragment().apply {
                arguments = Bundle().apply {
                    putLong("chatId", chatId)
                    putLong("threadId", threadId)
                    putString("title", titleText)
                    putString("mode", mode)
                }
            }
            supportFragmentManager.beginTransaction().replace(containerId, f).commit()
            Toast.makeText(this, "Premi MENU (☰) per elenco/griglia", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(
                Intent(this, MediaListActivity::class.java)
                    .putExtra("chatId", chatId)
                    .putExtra("threadId", threadId)
                    .putExtra("title", titleText)
                    .putExtra("mode", if (mode == "grid") "list" else "grid")
            )
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

class MediaGridFragment : VerticalGridSupportFragment() {

    private val thumbs = ThumbLoader()
    private lateinit var itemsAdapter: ArrayObjectAdapter
    private val collected = mutableListOf<MediaEntry>()

    private var chatId = 0L
    private var threadId = 0L
    private var grid = true
    private var oldest = 0L
    private var pages = 0
    private var emptyRetries = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs.start()
        chatId = arguments?.getLong("chatId") ?: 0L
        threadId = arguments?.getLong("threadId") ?: 0L
        grid = (arguments?.getString("mode") ?: "grid") == "grid"
        title = arguments?.getString("title") ?: "Contenuti"

        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = if (grid) Settings.gridColumns(requireContext()) else 1
        }

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is TopicEntry -> startActivity(
                    Intent(requireContext(), MediaListActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("threadId", item.threadId)
                        .putExtra("title", item.name)
                        .putExtra("mode", if (grid) "grid" else "list")
                )
                is MediaEntry -> startActivity(
                    Intent(requireContext(), PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_FILE_ID, item.fileId)
                        .putExtra(PlayerActivity.EXTRA_LABEL, item.title)
                )
            }
        }

        TdClient.openChat(chatId)

        if (threadId == 0L) {
            // Se la chat è un forum, mostra prima gli argomenti.
            TdClient.getForumTopics(chatId) { result ->
                val topics = (result as? TdApi.ForumTopics)?.topics?.filterNotNull().orEmpty()
                activity?.runOnUiThread {
                    if (topics.isNotEmpty()) showTopics(topics) else startMedia()
                }
            }
        } else {
            startMedia()
        }
    }

    private fun showTopics(topics: List<TdApi.ForumTopic>) {
        gridPresenter = VerticalGridPresenter().apply { numberOfColumns = 1 }
        val a = ArrayObjectAdapter(TopicPresenter())
        for (t in topics) a.add(TopicEntry(t.info.messageThreadId, t.info.name))
        adapter = a
        title = "Argomenti (${topics.size})"
    }

    private fun startMedia() {
        val itemPresenter: Presenter = if (grid) GridMediaPresenter(thumbs) else ListMediaPresenter(thumbs)
        itemsAdapter = ArrayObjectAdapter(itemPresenter)
        adapter = itemsAdapter

        val cached = MediaListActivity.cache
        if (threadId == 0L && cached != null && MediaListActivity.cacheChatId == chatId) {
            collected.addAll(cached)
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
        } else {
            loadPage(0L)
        }
    }

    private fun fetch(from: Long, handler: (TdApi.Object) -> Unit) {
        if (threadId != 0L) TdClient.getThreadHistory(chatId, threadId, from, 80, handler)
        else TdClient.getChatHistory(chatId, from, 80, handler)
    }

    private fun loadPage(from: Long) {
        fetch(from) { result ->
            val msgs = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            activity?.runOnUiThread {
                if (msgs.isEmpty()) {
                    if (collected.isEmpty() && emptyRetries > 0) {
                        emptyRetries--
                        view?.postDelayed({ loadPage(0L) }, 400)
                    } else finishLoading()
                    return@runOnUiThread
                }
                for (m in msgs) {
                    extractMedia(m)?.let { collected.add(it) }
                    oldest = m.id
                }
                pages++
                if (pages < 15 && collected.size < 200) loadPage(oldest) else finishLoading()
            }
        }
    }

    private fun finishLoading() {
        itemsAdapter.clear()
        if (collected.isEmpty()) {
            title = if (threadId != 0L) "Nessun contenuto in questo argomento" else "Nessun video o audio trovato"
        } else {
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
            if (threadId == 0L) {
                MediaListActivity.cache = collected.toList()
                MediaListActivity.cacheChatId = chatId
            }
        }
    }

    private fun extractMedia(msg: TdApi.Message): MediaEntry? {
        return when (val c = msg.content) {
            is TdApi.MessageVideo ->
                MediaEntry(c.video.video.id, c.video.fileName.ifEmpty { c.caption.text.ifEmpty { "Video" } }, "Video", c.video.duration, c.video.minithumbnail?.data, c.video.thumbnail?.file)
            is TdApi.MessageAudio -> {
                val name = listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }
                    .joinToString(" - ").ifEmpty { c.audio.fileName.ifEmpty { "Audio" } }
                MediaEntry(c.audio.audio.id, name, "Audio", c.audio.duration, c.audio.albumCoverMinithumbnail?.data, c.audio.albumCoverThumbnail?.file)
            }
            is TdApi.MessageVoiceNote -> MediaEntry(c.voiceNote.voice.id, "Messaggio vocale", "Audio", c.voiceNote.duration, null, null)
            is TdApi.MessageVideoNote -> MediaEntry(c.videoNote.video.id, "Video messaggio", "Video", c.videoNote.duration, c.videoNote.minithumbnail?.data, c.videoNote.thumbnail?.file)
            is TdApi.MessageAnimation -> MediaEntry(c.animation.animation.id, c.animation.fileName.ifEmpty { "GIF" }, "Video", c.animation.duration, c.animation.minithumbnail?.data, c.animation.thumbnail?.file)
            is TdApi.MessageDocument -> {
                val mime = c.document.mimeType
                when {
                    mime.startsWith("video/") -> MediaEntry(c.document.document.id, c.document.fileName.ifEmpty { "Video" }, "Video", 0, c.document.minithumbnail?.data, c.document.thumbnail?.file)
                    mime.startsWith("audio/") -> MediaEntry(c.document.document.id, c.document.fileName.ifEmpty { "Audio" }, "Audio", 0, c.document.minithumbnail?.data, c.document.thumbnail?.file)
                    else -> null
                }
            }
            else -> null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        thumbs.stop()
    }
}

class GridMediaPresenter(private val thumbs: ThumbLoader) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(280, 158)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as MediaEntry
        val card = viewHolder.view as ImageCardView
        card.titleText = e.title
        val dur = formatDuration(e.durationSec)
        card.contentText = if (dur.isNotEmpty()) "${e.type} · $dur" else e.type
        card.findViewById<TextView>(androidx.leanback.R.id.title_text)?.apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        thumbs.load(card, e.thumbFile, e.mini)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val card = viewHolder.view as ImageCardView
        card.titleText = null
        card.contentText = null
        card.mainImage = null
        card.tag = null
    }
}

class ListMediaPresenter(private val thumbs: ThumbLoader) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_list, parent, false)
        val pct = Settings.listWidthPercent(parent.context)
        val width = parent.context.resources.displayMetrics.widthPixels * pct / 100
        v.layoutParams?.let { it.width = width }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as MediaEntry
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.title).apply { text = e.title; isSelected = true }
        val dur = formatDuration(e.durationSec)
        v.findViewById<TextView>(R.id.subtitle).text =
            if (dur.isNotEmpty()) "${e.type} · $dur" else e.type
        thumbs.loadImage(v.findViewById(R.id.thumb), e.thumbFile, e.mini)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.thumb)?.apply { setImageBitmap(null); tag = null }
    }
}

class TopicPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_list, parent, false)
        val pct = Settings.listWidthPercent(parent.context)
        val width = parent.context.resources.displayMetrics.widthPixels * pct / 100
        v.layoutParams?.let { it.width = width }
        return ViewHolder(v)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as TopicEntry
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.title).apply { text = e.name; isSelected = true }
        v.findViewById<TextView>(R.id.subtitle).text = "Argomento"
        v.findViewById<ImageView>(R.id.thumb).apply {
            setImageBitmap(null)
            setBackgroundColor(0xFF223344.toInt())
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.thumb)?.setImageBitmap(null)
    }
}
