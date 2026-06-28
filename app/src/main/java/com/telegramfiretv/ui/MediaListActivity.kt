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
import androidx.core.content.ContextCompat
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

data class TopicEntry(val forumTopicId: Int, val name: String, val mini: ByteArray?, val thumbFile: TdApi.File?)

internal fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    return "%d:%02d".format(sec / 60, sec % 60)
}

internal fun decodeImageBytes(data: ByteArray?): Bitmap? =
    if (data == null) null else BitmapFactory.decodeByteArray(data, 0, data.size)

internal fun decodeImageFile(path: String?): Bitmap? =
    if (path.isNullOrEmpty()) null else BitmapFactory.decodeFile(path)

private val PLACEHOLDER = ColorDrawable(0xFF22303A.toInt())
private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "ts", "flv", "mpg", "mpeg", "wmv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma")

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
    private var forumTopicId = 0
    private var titleText: String? = null
    private var mode = "grid"

    companion object {
        var cache: List<MediaEntry>? = null
        var cacheChatId: Long = -1
        var cacheShowAll: Boolean = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        forumTopicId = intent.getIntExtra("forumTopicId", 0)
        titleText = intent.getStringExtra("title")
        mode = intent.getStringExtra("mode") ?: "grid"

        val containerId = View.generateViewId()
        setContentView(FrameLayout(this).apply { id = containerId })

        if (savedInstanceState == null) {
            val f = MediaGridFragment().apply {
                arguments = Bundle().apply {
                    putLong("chatId", chatId)
                    putInt("forumTopicId", forumTopicId)
                    putString("title", titleText)
                    putString("mode", mode)
                }
            }
            supportFragmentManager.beginTransaction().replace(containerId, f).commit()
            Toast.makeText(this, "Premi MENU per elenco/griglia", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(
                Intent(this, MediaListActivity::class.java)
                    .putExtra("chatId", chatId)
                    .putExtra("forumTopicId", forumTopicId)
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
    private var forumTopicId = 0
    private var grid = true
    private var showAll = true
    private var oldest = 0L
    private var pages = 0
    private var scanned = 0
    private var lastError: String? = null
    private var emptyRetries = 8
    private var writeOrb = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs.start()
        chatId = arguments?.getLong("chatId") ?: 0L
        forumTopicId = arguments?.getInt("forumTopicId") ?: 0
        grid = (arguments?.getString("mode") ?: "grid") == "grid"
        showAll = Settings.mediaFilter(requireContext()) == "all"
        title = arguments?.getString("title") ?: "Contenuti"

        val ctx = requireContext()
        val chat = TdClient.chats.firstOrNull { it.id == chatId }
        val isChannel = (chat?.type as? TdApi.ChatTypeSupergroup)?.isChannel == true
        val canWrite = when {
            forumTopicId != 0 -> Settings.writeFlag(ctx, "forum", true)
            isChannel -> Settings.writeFlag(ctx, "admin_channels", false)
            else -> Settings.writeFlag(ctx, "groups", true) || Settings.writeFlag(ctx, "forum", true)
        }
        if (canWrite) {
            writeOrb = true
            setOnSearchClickedListener {
                startActivity(
                    Intent(ctx, BotChatActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("forumTopicId", forumTopicId)
                        .putExtra("title", arguments?.getString("title"))
                )
            }
        }

        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = if (grid) Settings.gridColumns(requireContext()) else 1
        }

        setOnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is TopicEntry -> startActivity(
                    Intent(requireContext(), MediaListActivity::class.java)
                        .putExtra("chatId", chatId)
                        .putExtra("forumTopicId", item.forumTopicId)
                        .putExtra("title", item.name)
                        .putExtra("mode", if (grid) "grid" else "list")
                )
                is MediaEntry -> {
                    val ids = collected.map { it.fileId }.toIntArray()
                    val labs = collected.map { it.title }.toTypedArray()
                    val kinds = collected.map { when (it.type) { "Audio" -> 1; "Foto" -> 2; else -> 0 } }.toIntArray()
                    val idx = collected.indexOf(item).coerceAtLeast(0)
                    startActivity(
                        Intent(requireContext(), PlayerActivity::class.java)
                            .putExtra(PlayerActivity.EXTRA_FILE_IDS, ids)
                            .putExtra(PlayerActivity.EXTRA_LABELS, labs)
                            .putExtra(PlayerActivity.EXTRA_KINDS, kinds)
                            .putExtra(PlayerActivity.EXTRA_INDEX, idx)
                    )
                }
            }
        }

        TdClient.openChat(chatId)

        if (forumTopicId == 0) {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (writeOrb) {
            val orb = view.findViewById<SearchOrbView>(androidx.leanback.R.id.title_orb)
            val src = ContextCompat.getDrawable(requireContext(), R.drawable.ic_compose) as? BitmapDrawable
            if (orb != null && src != null) {
                val px = (26 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(src.bitmap, px, px, true)
                orb.setOrbIcon(BitmapDrawable(resources, scaled))
                orb.setOrbColors(SearchOrbView.Colors(0, 0, 0))
                orb.elevation = 0f
                orb.enableOrbColorAnimation(false)
            }
        }
    }

    private fun showTopics(topics: List<TdApi.ForumTopic>) {
        val chat = TdClient.orderedChats().firstOrNull { it.id == chatId }
        val mini = chat?.photo?.minithumbnail?.data
        val thumb = chat?.photo?.small
        val a = ArrayObjectAdapter(TopicPresenter(thumbs))
        for (t in topics) {
            val fid = t.info.forumTopicId
            if (fid != 0) a.add(TopicEntry(fid, t.info.name, mini, thumb))
        }
        if (a.size() == 0) {
            startMedia()
            return
        }
        gridPresenter = VerticalGridPresenter().apply {
            numberOfColumns = Settings.gridColumns(requireContext())
        }
        adapter = a
        title = "Argomenti (${a.size()})"
    }

    private fun startMedia() {
        val itemPresenter: Presenter = if (grid) GridMediaPresenter(thumbs) else ListMediaPresenter(thumbs)
        itemsAdapter = ArrayObjectAdapter(itemPresenter)
        adapter = itemsAdapter

        val cached = MediaListActivity.cache
        if (forumTopicId == 0 && cached != null && MediaListActivity.cacheChatId == chatId && MediaListActivity.cacheShowAll == showAll) {
            collected.addAll(cached)
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
        } else {
            loadPage(0L)
        }
    }

    private fun fetch(from: Long, handler: (TdApi.Object) -> Unit) {
        if (forumTopicId != 0) TdClient.getForumTopicHistory(chatId, forumTopicId, from, 80, handler)
        else TdClient.getChatHistory(chatId, from, 80, handler)
    }

    private fun loadPage(from: Long) {
        fetch(from) { result ->
            activity?.runOnUiThread {
                if (result is TdApi.Error) {
                    lastError = "err ${result.code}: ${result.message}"
                    finishLoading()
                    return@runOnUiThread
                }
                val msgs = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
                if (msgs.isEmpty()) {
                    if (collected.isEmpty() && emptyRetries > 0) {
                        emptyRetries--
                        view?.postDelayed({ loadPage(0L) }, 600)
                    } else finishLoading()
                    return@runOnUiThread
                }
                scanned += msgs.size
                for (m in msgs) {
                    extractMedia(m)?.let { if (showAll || it.type != "Foto") collected.add(it) }
                    oldest = m.id
                }
                pages++
                val maxPages = if (forumTopicId != 0) 40 else 15
                if (pages < maxPages && collected.size < 300) loadPage(oldest) else finishLoading()
            }
        }
    }

    private fun finishLoading() {
        itemsAdapter.clear()
        if (collected.isEmpty()) {
            val base = if (forumTopicId != 0) "Argomento: 0 media su $scanned msg" else "Vuoto: 0 media su $scanned msg"
            title = if (lastError != null) "$base — $lastError" else base
        } else {
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
            if (forumTopicId == 0) {
                MediaListActivity.cache = collected.toList()
                MediaListActivity.cacheChatId = chatId
                MediaListActivity.cacheShowAll = showAll
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
                val doc = c.document
                val mime = doc.mimeType
                val ext = doc.fileName.substringAfterLast('.', "").lowercase()
                when {
                    mime.startsWith("video/") || ext in VIDEO_EXT ->
                        MediaEntry(doc.document.id, doc.fileName.ifEmpty { "Video" }, "Video", 0, doc.minithumbnail?.data, doc.thumbnail?.file)
                    mime.startsWith("audio/") || ext in AUDIO_EXT ->
                        MediaEntry(doc.document.id, doc.fileName.ifEmpty { "Audio" }, "Audio", 0, doc.minithumbnail?.data, doc.thumbnail?.file)
                    else -> null
                }
            }
            is TdApi.MessagePhoto -> {
                val sizes = c.photo.sizes
                if (sizes.isEmpty()) null else {
                    val largest = sizes.maxByOrNull { it.width * it.height }!!
                    val smallest = sizes.minByOrNull { it.width * it.height }!!
                    MediaEntry(largest.photo.id, c.caption.text.ifEmpty { "Foto" }, "Foto", 0, c.photo.minithumbnail?.data, smallest.photo)
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
        card.contentText = if (dur.isNotEmpty()) "${e.type} - $dur" else e.type
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
            if (dur.isNotEmpty()) "${e.type} - $dur" else e.type
        thumbs.loadImage(v.findViewById(R.id.thumb), e.thumbFile, e.mini)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.thumb)?.apply { setImageBitmap(null); tag = null }
    }
}

class TopicPresenter(private val thumbs: ThumbLoader) : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(280, 158)
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as TopicEntry
        val card = viewHolder.view as ImageCardView
        card.titleText = e.name
        card.contentText = "Argomento"
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
