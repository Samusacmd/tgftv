package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
    val thumbFile: TdApi.File?,
    // Chiave per il flag "già visto": id univoco remoto del file, la stessa chiave che il
    // player usa per posizioni e visti. Vuota per i tipi non tracciati (foto, post).
    val watchKey: String = "",
    // Id del messaggio TDLib: usato solo per le voci di tipo "Post" (apre PostViewActivity).
    val messageId: Long = 0L
)

/** Etichetta breve per una voce "Post": usa il testo/didascalia se c'è, altrimenti un segnaposto. */
private fun postLabel(m: TdApi.Message): String {
    val t = when (val c = m.content) {
        is TdApi.MessageText -> c.text.text
        is TdApi.MessagePhoto -> c.caption.text
        is TdApi.MessageVideo -> c.caption.text
        is TdApi.MessageDocument -> c.caption.text
        is TdApi.MessageAnimation -> c.caption.text
        else -> ""
    }
    val firstLine = t.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return firstLine.ifBlank { "📋 Post con pulsanti" }
}

/** Mini-miniatura e file miniatura del messaggio, se disponibili (per l'anteprima del Post). */
private fun mediaThumbOf(m: TdApi.Message): Pair<ByteArray?, TdApi.File?>? {
    val pair: Pair<ByteArray?, TdApi.File?> = when (val c = m.content) {
        is TdApi.MessagePhoto -> c.photo.minithumbnail?.data to
            c.photo.sizes.minByOrNull { it.width * it.height }?.photo
        is TdApi.MessageVideo -> c.video.minithumbnail?.data to c.video.thumbnail?.file
        is TdApi.MessageAnimation -> c.animation.minithumbnail?.data to c.animation.thumbnail?.file
        is TdApi.MessageDocument -> c.document.minithumbnail?.data to c.document.thumbnail?.file
        else -> return null
    }
    return if (pair.first == null && pair.second == null) null else pair
}

/** Stessa chiave usata dal player (keyFor): id remoto stabile, con ripiego sull'id locale. */
/**
 * Chiave stabile per il flag "già visto": SOLO l'id univoco remoto del file.
 * Non c'è un ripiego sull'id locale (file.id): quell'id vale solo per la sessione
 * corrente e TDLib può riassegnarlo a un file completamente diverso in seguito (dopo
 * una pulizia cache, un riavvio, ecc.) — un ripiego lì causava stelline sbagliate su
 * file mai riprodotti. Se l'id univoco non c'è, il file semplicemente non partecipa
 * al sistema dei "già visti" (stringa vuota, stesso trattamento già riservato alle foto).
 */
private fun watchKeyOf(f: TdApi.File): String = f.remote.uniqueId

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
    private lateinit var headerBox: LinearLayout
    private var startAfterMessageId = 0L

    companion object {
        var cache: List<MediaEntry>? = null
        var cacheChatId: Long = -1
        var cacheShowAll: Boolean = true

        fun clearCache() {
            cache = null
            cacheChatId = -1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        forumTopicId = intent.getIntExtra("forumTopicId", 0)
        titleText = intent.getStringExtra("title")
        mode = intent.getStringExtra("mode") ?: "grid"
        // Se presente, salta l'inizio del canale e mostra i file a partire da questo
        // messaggio in avanti (es. dopo un "segnalibro" che segna l'inizio di una stagione).
        startAfterMessageId = intent.getLongExtra("startAfterMessageId", 0L)

        val containerId = View.generateViewId()
        headerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(32, 24, 32, 16)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerBox)
            addView(FrameLayout(this@MediaListActivity).apply {
                id = containerId
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            })
        }
        setContentView(root)

        if (savedInstanceState == null) {
            val f = MediaGridFragment().apply {
                arguments = Bundle().apply {
                    putLong("chatId", chatId)
                    putInt("forumTopicId", forumTopicId)
                    putString("title", titleText)
                    putString("mode", mode)
                    putLong("startAfterMessageId", startAfterMessageId)
                }
            }
            supportFragmentManager.beginTransaction().replace(containerId, f).commit()
            Toast.makeText(this, "Premi MENU per elenco/griglia", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Mostra in cima allo schermo, sopra la griglia, il post iniziale del canale (locandina
     * + trama) con subito sotto i pulsanti per selezionare la stagione/serie — sempre
     * visibile all'apertura, invece di dover scorrere fino in fondo per trovarlo.
     */
    fun showIntroHeader(m: TdApi.Message, posterMsg: TdApi.Message?) {
        headerBox.removeAllViews()
        headerBox.visibility = View.VISIBLE

        val c = m.content
        val linkPreview = (c as? TdApi.MessageText)?.linkPreview
        var bodyText = when {
            linkPreview != null -> listOfNotNull(
                linkPreview.title.ifBlank { null },
                linkPreview.description.text.ifBlank { null }
            ).joinToString("\n\n").ifBlank { (c as TdApi.MessageText).text.text }
            c is TdApi.MessageText -> c.text.text
            c is TdApi.MessagePhoto -> c.caption.text
            c is TdApi.MessageVideo -> c.caption.text
            c is TdApi.MessageDocument -> c.caption.text
            else -> ""
        }
        if (bodyText.isBlank()) bodyText = (posterMsg?.content as? TdApi.MessagePhoto)?.caption?.text ?: ""

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 360)
                .also { it.bottomMargin = 16 }
        }
        // Foto: propria (se il post ha una vera foto), altrimenti quella dell'anteprima link,
        // altrimenti quella del messaggio-locandina immediatamente precedente.
        val photoObj: TdApi.Photo? = when {
            c is TdApi.MessagePhoto -> c.photo
            linkPreview != null -> when (val t = linkPreview.type) {
                is TdApi.LinkPreviewTypePhoto -> t.photo
                is TdApi.LinkPreviewTypeArticle -> t.photo
                else -> null
            }
            else -> (posterMsg?.content as? TdApi.MessagePhoto)?.photo
        }
        val big = photoObj?.sizes?.maxByOrNull { it.width * it.height }?.photo
        if (big != null) {
            headerBox.addView(img)
            photoObj.minithumbnail?.data?.let { d ->
                runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                    ?.let { img.setImageBitmap(it) }
            }
            if (big.local.isDownloadingCompleted && big.local.path.isNotEmpty()) {
                runCatching { BitmapFactory.decodeFile(big.local.path) }.getOrNull()
                    ?.let { img.setImageBitmap(it) }
            } else {
                TdClient.downloadFilePath(big.id) { path ->
                    runOnUiThread {
                        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                            ?.let { img.setImageBitmap(it) }
                    }
                }
            }
        }

        if (bodyText.isNotBlank()) {
            headerBox.addView(TextView(this).apply {
                text = bodyText
                setTextColor(0xFFE6EDF2.toInt())
                textSize = 15f
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, 16)
            })
        }

        val markup = m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
        markup?.rows?.forEach { rowArr ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8 }
            }
            rowArr.forEach { b ->
                row.addView(Button(this).apply {
                    text = b.text
                    setBackgroundResource(R.drawable.bg_button)
                    setTextColor(0xFFFFFFFF.toInt())
                    isAllCaps = false
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .also { it.rightMargin = 8 }
                    setOnClickListener {
                        when (val ty = b.type) {
                            is TdApi.InlineKeyboardButtonTypeCallback ->
                                TdClient.sendCallback(chatId, m.id, ty.data) {}
                            is TdApi.InlineKeyboardButtonTypeUrl -> resolveAndOpen(ty.url)
                            else -> {}
                        }
                    }
                })
            }
            headerBox.addView(row)
        }
    }

    /** Risoluzione ufficiale del link (stessa tecnica di PostViewActivity/BotChatActivity). */
    private fun resolveAndOpen(raw: String) {
        var link = raw.trim()
        val i = link.indexOf("t.me/")
        if (i > 0) link = link.substring(i)
        if (link.startsWith("t.me/")) link = "https://$link"

        if (link.contains("t.me/c/")) {
            Toast.makeText(this, "Apro…", Toast.LENGTH_SHORT).show()
            TdClient.getMessageLinkInfo(link) { obj ->
                runOnUiThread {
                    val info = obj as? TdApi.MessageLinkInfo
                    val msg = info?.message
                    when {
                        msg != null && msg.content is TdApi.MessageSticker -> startActivity(
                            Intent(this, MediaListActivity::class.java)
                                .putExtra("chatId", msg.chatId)
                                .putExtra("startAfterMessageId", msg.id)
                                .putExtra("title", "Contenuti")
                        )
                        msg != null -> startActivity(
                            Intent(this, PostViewActivity::class.java)
                                .putExtra("chatId", msg.chatId)
                                .putExtra("messageId", msg.id)
                        )
                        else -> Toast.makeText(this, "Link non risolvibile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            return
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)))
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile aprire il link", Toast.LENGTH_SHORT).show()
            }
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

    // Modalità "da un segnalibro in poi": invece di partire dai messaggi più recenti e
    // scorrere all'indietro, si parte da un messaggio preciso (es. lo sticker che segna
    // l'inizio di una stagione) e si carica in avanti, verso i messaggi più nuovi.
    private var startAfterMessageId = 0L
    private var forwardCursor = 0L
    // Id del "post iniziale" del canale (locandina + pulsanti navigazione stagioni), se
    // trovato: viene agganciato in cima all'apertura invece di comparire in fondo dopo
    // tutti gli episodi. Escluso poi dal normale ciclo per non farlo comparire due volte.
    private var introPostId = 0L
    // Id del messaggio-locandina (solo foto, senza pulsanti) quando è distinto dal post
    // dei pulsanti: va escluso anche lui dal normale ciclo, altrimenti comparirebbe due
    // volte (una nell'intestazione, una come foto qualunque in fondo alla griglia).
    private var introPosterId = 0L

    // Scroll infinito: carichiamo a blocchi invece che tutto insieme.
    private var loading = false          // un blocco è in corso: evita richieste sovrapposte
    private var reachedEnd = false       // il canale/topic è finito: niente altro da caricare
    private var initialShown = false     // il primo blocco è già stato mostrato
    private val hardCap = 1300           // limite massimo di media raccolti
    private val pagesPerBatch = 6        // pagine caricate per ogni blocco (6 × 80 = fino a 480 msg)
    private val prefetchThreshold = 12   // quando mancano così pochi item alla fine, precarica

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs.start()
        chatId = arguments?.getLong("chatId") ?: 0L
        forumTopicId = arguments?.getInt("forumTopicId") ?: 0
        grid = (arguments?.getString("mode") ?: "grid") == "grid"
        showAll = Settings.mediaFilter(requireContext()) == "all"
        title = arguments?.getString("title") ?: "Contenuti"
        startAfterMessageId = arguments?.getLong("startAfterMessageId") ?: 0L
        forwardCursor = startAfterMessageId

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
                    if (item.type == "Post") {
                        startActivity(
                            Intent(requireContext(), PostViewActivity::class.java)
                                .putExtra("chatId", chatId)
                                .putExtra("messageId", item.messageId)
                        )
                        return@setOnItemViewClickedListener
                    }
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

        setOnItemViewSelectedListener { _, item, _, _ ->
            // Quando la selezione si avvicina alla fine della lista già caricata, precarica
            // il blocco successivo (scroll infinito): l'utente non aspetta mai il caricamento.
            if (item is MediaEntry) {
                val pos = collected.indexOf(item)
                if (pos >= 0 && pos >= collected.size - prefetchThreshold) loadMoreIfNeeded()
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

        if (startAfterMessageId != 0L) {
            // Modalità "da un segnalibro in poi": la posizione di partenza cambia ogni volta,
            // quindi non ha senso riusare/scrivere la cache pensata per la sfoglia normale.
            title = "Carico…"
            loadForwardBatch()
            return
        }

        val cached = MediaListActivity.cache
        if (forumTopicId == 0 && cached != null && MediaListActivity.cacheChatId == chatId && MediaListActivity.cacheShowAll == showAll) {
            collected.addAll(cached)
            itemsAdapter.addAll(0, collected)
            title = "${collected.size} contenuti"
            initialShown = true
            // La cache serve alla riapertura rapida della stessa schermata. Non conserva l'id
            // dell'ultimo messaggio, quindi non possiamo riprendere lo scroll senza rischiare
            // duplicati: la trattiamo come lista completa. Riaprendo da zero (cambio filtro o
            // cache scaduta) il caricamento progressivo riparte e arriva fino a $hardCap.
            reachedEnd = true
        } else {
            title = "Carico…"
            // Il post iniziale del canale (locandina + pulsanti stagioni) è spesso il primo
            // messaggio mai pubblicato: normalmente comparirebbe solo dopo aver scorso tutto
            // l'elenco (si carica dai più recenti all'indietro). Lo agganciamo qui in cima,
            // e solo DOPO avviamo il caricamento normale della griglia: così l'esclusione
            // (introPostId/introPosterId) è già pronta quando la griglia inizia a leggere i
            // messaggi, ed evitiamo che il post compaia anche come voce normale.
            if (forumTopicId == 0) {
                // Locandina e post-pulsanti possono essere sia i primissimi che gli ultimi
                // messaggi del canale (varia da canale a canale): controlliamo alcuni
                // messaggi da ENTRAMBI gli estremi e li abbiniamo, indipendentemente da
                // quale dei due lati contenga cosa.
                TdClient.getChatHistory(chatId, 0L, 3) { newestResult ->
                    activity?.runOnUiThread {
                        val newestMsgs = (newestResult as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
                        TdClient.getChatHistory(chatId, 1L, 3) { oldestResult ->
                            activity?.runOnUiThread {
                                val oldestMsgs = (oldestResult as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
                                val pool = (newestMsgs + oldestMsgs).distinctBy { it.id }

                                val buttonsMsg = pool.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupInlineKeyboard }
                                if (buttonsMsg != null) {
                                    introPostId = buttonsMsg.id
                                    val posterMsg = pool.firstOrNull { cand ->
                                        cand.id != buttonsMsg.id && (
                                            cand.content is TdApi.MessagePhoto ||
                                            ((cand.content as? TdApi.MessageText)?.linkPreview != null)
                                        )
                                    }
                                    if (posterMsg != null) introPosterId = posterMsg.id
                                    (activity as? MediaListActivity)?.showIntroHeader(buttonsMsg, posterMsg)
                                }
                                loadBatch()
                            }
                        }
                    }
                }
            } else {
                loadBatch()
            }
        }
    }

    /** Carica un blocco di pagine e le AGGIUNGE alla lista, senza svuotare l'adapter. */
    private fun loadBatch() {
        if (loading || reachedEnd) return
        if (collected.size >= hardCap) { reachedEnd = true; return }
        loading = true
        loadPage(if (initialShown) oldest else 0L, pagesInBatch = 0)
    }

    /** Se serve altro (non stiamo già caricando, non abbiamo finito, non al tetto), carica un blocco. */
    private fun loadMoreIfNeeded() {
        if (loading || reachedEnd || collected.size >= hardCap) return
        if (startAfterMessageId != 0L) loadForwardBatch() else loadBatch()
    }

    /** Carica un blocco procedendo IN AVANTI da [forwardCursor] (dal segnalibro verso i più recenti). */
    private fun loadForwardBatch() {
        if (loading || reachedEnd) return
        if (collected.size >= hardCap) { reachedEnd = true; return }
        loading = true
        loadForwardPage(pagesInBatch = 0)
    }

    private fun loadForwardPage(pagesInBatch: Int) {
        val limit = 80
        // Offset negativo = tecnica standard TDLib per ottenere i messaggi PIÙ NUOVI di
        // fromMessageId invece che quelli più vecchi (che è il comportamento di default).
        TdClient.getChatHistory(chatId, forwardCursor, limit, offset = -limit) { result ->
            activity?.runOnUiThread {
                if (result is TdApi.Error) {
                    lastError = "err ${result.code}: ${result.message}"
                    finishBatch(endReached = true)
                    return@runOnUiThread
                }
                // TDLib restituisce sempre dal più recente al più vecchio: li rimettiamo in
                // ordine cronologico e teniamo solo quelli davvero successivi al segnalibro.
                val newer = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
                    .filter { it.id > forwardCursor }.sortedBy { it.id }
                if (newer.isEmpty()) {
                    if (collected.isEmpty() && emptyRetries > 0) {
                        emptyRetries--
                        view?.postDelayed({ loadForwardPage(pagesInBatch) }, 600)
                    } else {
                        finishBatch(endReached = true)
                    }
                    return@runOnUiThread
                }
                scanned += newer.size
                for (m in newer) {
                    val kb = m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
                    if (kb != null) {
                        val label = postLabel(m)
                        val th = mediaThumbOf(m)
                        collected.add(MediaEntry(0, label, "Post", 0, th?.first, th?.second, "", m.id))
                    } else {
                        extractMedia(m)?.let { if (showAll || it.type != "Foto") collected.add(it) }
                    }
                    forwardCursor = m.id
                }
                pages++
                val batchDone = pagesInBatch + 1 >= pagesPerBatch
                val capReached = collected.size >= hardCap
                when {
                    capReached -> finishBatch(endReached = true)
                    batchDone -> finishBatch(endReached = false)
                    else -> loadForwardPage(pagesInBatch + 1)
                }
            }
        }
    }

    private fun fetch(from: Long, handler: (TdApi.Object) -> Unit) {
        if (forumTopicId != 0) TdClient.getForumTopicHistory(chatId, forumTopicId, from, 80, handler)
        else TdClient.getChatHistory(chatId, from, 80, handler = handler)
    }

    private fun loadPage(from: Long, pagesInBatch: Int) {
        fetch(from) { result ->
            activity?.runOnUiThread {
                if (result is TdApi.Error) {
                    lastError = "err ${result.code}: ${result.message}"
                    finishBatch(endReached = true)
                    return@runOnUiThread
                }
                val msgs = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
                if (msgs.isEmpty()) {
                    // All'inizio la history locale può essere vuota: riprova qualche volta.
                    if (collected.isEmpty() && emptyRetries > 0) {
                        emptyRetries--
                        view?.postDelayed({ loadPage(0L, pagesInBatch) }, 600)
                    } else {
                        // Nessun altro messaggio: siamo davvero alla fine del canale/topic.
                        finishBatch(endReached = true)
                    }
                    return@runOnUiThread
                }
                scanned += msgs.size
                for (m in msgs) {
                    if (m.id == introPostId || m.id == introPosterId) { oldest = m.id; continue }
                    val kb = m.replyMarkup as? TdApi.ReplyMarkupInlineKeyboard
                    if (kb != null) {
                        // Post con tastiera a pulsanti (es. menu con elenco stagioni/episodi):
                        // lo trattiamo come voce a sé, apribile per usare i pulsanti, invece
                        // di estrarne il solo media (che da solo perderebbe i pulsanti).
                        val label = postLabel(m)
                        val th = mediaThumbOf(m)
                        collected.add(MediaEntry(0, label, "Post", 0, th?.first, th?.second, "", m.id))
                    } else {
                        extractMedia(m)?.let { if (showAll || it.type != "Foto") collected.add(it) }
                    }
                    oldest = m.id
                }
                pages++
                val batchDone = pagesInBatch + 1 >= pagesPerBatch
                val capReached = collected.size >= hardCap
                when {
                    capReached -> finishBatch(endReached = true)
                    batchDone -> finishBatch(endReached = false)
                    else -> loadPage(oldest, pagesInBatch + 1)
                }
            }
        }
    }

    /** Mostra i media raccolti finora (aggiungendo solo i nuovi) e libera lo stato di caricamento. */
    private fun finishBatch(endReached: Boolean) {
        loading = false
        if (endReached) reachedEnd = true

        if (collected.isEmpty()) {
            val base = if (forumTopicId != 0) "Argomento: 0 media su $scanned msg" else "Vuoto: 0 media su $scanned msg"
            title = if (lastError != null) "$base — $lastError" else base
            return
        }

        // Aggiunge all'adapter SOLO gli elementi non ancora mostrati (niente clear/addAll totale).
        val shown = itemsAdapter.size()
        if (collected.size > shown) {
            itemsAdapter.addAll(shown, collected.subList(shown, collected.size))
        }
        initialShown = true
        val suffix = if (reachedEnd) "" else "…"
        title = "${collected.size} contenuti$suffix"

        if (forumTopicId == 0 && startAfterMessageId == 0L) {
            MediaListActivity.cache = collected.toList()
            MediaListActivity.cacheChatId = chatId
            MediaListActivity.cacheShowAll = showAll
        }
    }

    private fun extractMedia(msg: TdApi.Message): MediaEntry? {
        return when (val c = msg.content) {
            is TdApi.MessageVideo ->
                MediaEntry(c.video.video.id, c.video.fileName.ifEmpty { c.caption.text.ifEmpty { "Video" } }, "Video", c.video.duration, c.video.minithumbnail?.data, c.video.thumbnail?.file, watchKeyOf(c.video.video))
            is TdApi.MessageAudio -> {
                val name = listOf(c.audio.performer, c.audio.title).filter { it.isNotBlank() }
                    .joinToString(" - ").ifEmpty { c.audio.fileName.ifEmpty { "Audio" } }
                MediaEntry(c.audio.audio.id, name, "Audio", c.audio.duration, c.audio.albumCoverMinithumbnail?.data, c.audio.albumCoverThumbnail?.file, watchKeyOf(c.audio.audio))
            }
            is TdApi.MessageVoiceNote -> MediaEntry(c.voiceNote.voice.id, "Messaggio vocale", "Audio", c.voiceNote.duration, null, null, watchKeyOf(c.voiceNote.voice))
            is TdApi.MessageVideoNote -> MediaEntry(c.videoNote.video.id, "Video messaggio", "Video", c.videoNote.duration, c.videoNote.minithumbnail?.data, c.videoNote.thumbnail?.file, watchKeyOf(c.videoNote.video))
            is TdApi.MessageAnimation -> MediaEntry(c.animation.animation.id, c.animation.fileName.ifEmpty { "GIF" }, "Video", c.animation.duration, c.animation.minithumbnail?.data, c.animation.thumbnail?.file, watchKeyOf(c.animation.animation))
            is TdApi.MessageDocument -> {
                val doc = c.document
                val mime = doc.mimeType
                val ext = doc.fileName.substringAfterLast('.', "").lowercase()
                when {
                    mime.startsWith("video/") || ext in VIDEO_EXT ->
                        MediaEntry(doc.document.id, doc.fileName.ifEmpty { "Video" }, "Video", 0, doc.minithumbnail?.data, doc.thumbnail?.file, watchKeyOf(doc.document))
                    mime.startsWith("audio/") || ext in AUDIO_EXT ->
                        MediaEntry(doc.document.id, doc.fileName.ifEmpty { "Audio" }, "Audio", 0, doc.minithumbnail?.data, doc.thumbnail?.file, watchKeyOf(doc.document))
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

/** Bitmap della stellina, disegnata una volta sola e condivisa tra le card. */
private var starBitmap: Bitmap? = null

/**
 * Stellina come drawable da usare come "foreground" della card: un livello sovrapposto
 * ancorato all'angolo in alto a destra dell'anteprima, che non entra nel layout interno
 * della card Leanback (aggiungerla come vista figlia la faceva finire sotto l'immagine).
 */
private fun starDrawable(res: android.content.res.Resources): Drawable {
    val bmp = starBitmap ?: run {
        val size = 40
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f
            setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt())
        }
        cv.drawText("⭐", 3f, 32f, p)
        starBitmap = b
        b
    }
    // InsetDrawable = margini della stellina dal bordo (alto e destra).
    return InsetDrawable(BitmapDrawable(res, bmp), 0, 6, 8, 0)
}

class GridMediaPresenter(private val thumbs: ThumbLoader) : Presenter() {
    /** ViewHolder con il drawable della stellina e l'elemento associato (per il toggle). */
    private class VH(view: View, val star: Drawable) : Presenter.ViewHolder(view) {
        var entry: MediaEntry? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(280, 158)
            foregroundGravity = Gravity.TOP or Gravity.END
        }
        val vh = VH(card, starDrawable(parent.context.resources))
        // Pressione prolungata (OK tenuto premuto): inverte lo stato "già visto".
        card.setOnLongClickListener { v ->
            val e = vh.entry ?: return@setOnLongClickListener false
            if (e.watchKey.isEmpty()) return@setOnLongClickListener false
            val now = Settings.toggleWatched(v.context, e.watchKey)
            card.foreground = if (now) vh.star else null
            Toast.makeText(v.context, if (now) "Segnato come già riprodotto" else "Segnato come non riprodotto", Toast.LENGTH_SHORT).show()
            true
        }
        return vh
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as MediaEntry
        val vh = viewHolder as VH
        vh.entry = e
        val card = viewHolder.view as ImageCardView
        card.titleText = e.title
        val seen = e.watchKey.isNotEmpty() && Settings.isWatched(card.context, e.watchKey)
        card.foreground = if (seen) vh.star else null
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
        (viewHolder as VH).entry = null
        card.foreground = null
        card.titleText = null
        card.contentText = null
        card.mainImage = null
        card.tag = null
    }
}

class ListMediaPresenter(private val thumbs: ThumbLoader) : Presenter() {
    private class VH(view: View, val star: TextView) : Presenter.ViewHolder(view) {
        var entry: MediaEntry? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_list, parent, false)
        val pct = Settings.listWidthPercent(parent.context)
        val width = parent.context.resources.displayMetrics.widthPixels * pct / 100
        v.layoutParams?.let { it.width = width }
        val vh = VH(v, v.findViewById(R.id.watchedStar))
        // Pressione prolungata (OK tenuto premuto): inverte lo stato "già visto".
        v.setOnLongClickListener { view ->
            val e = vh.entry ?: return@setOnLongClickListener false
            if (e.watchKey.isEmpty()) return@setOnLongClickListener false
            val now = Settings.toggleWatched(view.context, e.watchKey)
            vh.star.visibility = if (now) View.VISIBLE else View.GONE
            Toast.makeText(view.context, if (now) "Segnato come già riprodotto" else "Segnato come non riprodotto", Toast.LENGTH_SHORT).show()
            true
        }
        return vh
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val e = item as MediaEntry
        val vh = viewHolder as VH
        vh.entry = e
        val v = viewHolder.view
        v.findViewById<TextView>(R.id.title).apply { text = e.title; isSelected = true }
        vh.star.visibility =
            if (e.watchKey.isNotEmpty() && Settings.isWatched(v.context, e.watchKey)) View.VISIBLE else View.GONE
        val dur = formatDuration(e.durationSec)
        v.findViewById<TextView>(R.id.subtitle).text =
            if (dur.isNotEmpty()) "${e.type} - $dur" else e.type
        thumbs.loadImage(v.findViewById(R.id.thumb), e.thumbFile, e.mini)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        (viewHolder as VH).entry = null
        viewHolder.star.visibility = View.GONE
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
