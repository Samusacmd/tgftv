package com.telegramfiretv.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.telegramfiretv.R
import com.telegramfiretv.player.PlayerActivity
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.zip.GZIPInputStream

private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "avi", "webm", "m4v", "3gp", "ts", "flv", "mpg", "mpeg", "wmv")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma")

// Colori delle "nuvolette" stile Telegram Desktop: azzurro per i messaggi inviati da me,
// grigio-blu scuro per quelli ricevuti. Allineamento: miei a destra, altrui a sinistra.
private const val COLOR_BUBBLE_MINE = 0xFF2B5278.toInt()
private const val COLOR_BUBBLE_OTHER = 0xFF1C2B33.toInt()

class BotChatActivity : FragmentActivity() {

    private var chatId = 0L
    private var titleText: String? = null
    private var isBot = false
    private var botCommands: List<TdApi.BotCommand> = emptyList()
    private var lastMessages: List<TdApi.Message> = emptyList()
    private var forumTopicId = 0
    private var isGroupChat = false

    private lateinit var messagesBox: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var buttonsBox: LinearLayout
    private lateinit var input: EditText
    private lateinit var inputRow: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { loadAndRender() }

    // Ricontrollo periodico finché la chat è aperta: rilegge la cronologia (la stessa lettura
    // che avviene rientrando nella chat, che trova sempre le risposte) ogni 2 secondi.
    // La guardia lastSig evita qualsiasi ridisegno se non c'è nulla di nuovo: zero sfarfallio.
    // Serve perché su alcuni dispositivi gli update in tempo reale non vengono consegnati.
    private val pollRunnable = object : Runnable {
        override fun run() {
            loadAndRender()
            handler.postDelayed(this, 1000)
        }
    }
    private var emptyRetries = 5
    private var lastSig: String = ""

    // --- Lettura a ritroso automatica ---
    // loadingOlder evita richieste sovrapposte; noMoreOlder segna che la conversazione è
    // finita (la riga in alto diventa un'etichetta); pendingAnchorId è il messaggio a cui
    // riancorare la vista dopo il caricamento, così la lettura riprende da dove si era;
    // autoLoadArmed evita che l'apertura della chat (che parte da scroll 0 prima di
    // scendere in fondo) inneschi un caricamento involontario.
    private var loadingOlder = false
    private var noMoreOlder = false
    private var pendingAnchorId = 0L
    private var autoLoadArmed = false

    // --- Avatar accanto ai messaggi altrui ---
    // Cache delle immagini profilo già scaricate e ritagliate a cerchio (chiave: mittente).
    private val avatarCache = HashMap<String, Bitmap>()
    // Messaggio a cui associare l'avatar sulla sua prima riga; le righe successive dello
    // stesso messaggio ricevono uno spazio equivalente per mantenere l'allineamento.
    private var rowAvatarMsg: TdApi.Message? = null
    private var rowAvatarUsed = true
    private val AVATAR_SIZE = 56

    // --- Risposta a un messaggio ---
    // Premendo OK su una bolla di testo altrui (o tenendo premuto su un media) si entra in
    // modalità risposta: il prossimo invio parte come risposta Telegram a quel messaggio.
    private var replyToMessageId = 0L
    private lateinit var replyBar: TextView

    /** Attiva la modalità risposta verso [m] (solo se la scrittura è abilitata nella chat). */
    private fun setReplyTarget(m: TdApi.Message) {
        if (inputRow.visibility != View.VISIBLE) {
            Toast.makeText(this, "Scrittura non abilitata in questa chat", Toast.LENGTH_SHORT).show()
            return
        }
        replyToMessageId = m.id
        val who = senderLabel(m)
        val excerpt = (messageText(m) ?: mediaOf(m)?.third ?: "")
            .replace('\n', ' ').take(48)
        replyBar.text = "↩  Risposta a $who: $excerpt   —  premi qui per annullare"
        replyBar.visibility = View.VISIBLE
        // Porta subito il cursore sulla casella di scrittura e prova ad aprire la
        // tastiera virtuale (dove il sistema lo consente).
        input.requestFocus()
        input.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearReplyTarget() {
        replyToMessageId = 0L
        replyBar.visibility = View.GONE
    }

    /**
     * Firma della lista messaggi usata per decidere se ridisegnare. Include, oltre a id e
     * numero, anche editDate e l'hash del testo di ogni messaggio: alcuni bot rispondono ai
     * pulsanti MODIFICANDO il messaggio del menu invece di inviarne uno nuovo, e con la sola
     * coppia numero+idUltimo la modifica risultava invisibile (bisognava uscire e rientrare).
     */
    private fun sigOf(msgs: List<TdApi.Message>): String =
        msgs.joinToString("|") { "${it.id}:${it.editDate}:${messageText(it)?.hashCode() ?: 0}" }
    private var lastNewestId: Long = 0L
    private val messagesListener: (Long) -> Unit =
        { cid -> if (cid == chatId) runOnUiThread { scheduleRefresh() } }

    // Riceve direttamente il nuovo messaggio: lo inseriamo subito nella lista e ri-renderizziamo,
    // senza dipendere dalla rilettura della history (che può arrivare in ritardo). È questo che
    // fa comparire le risposte del bot in tempo reale come sul telefono.
    private val newMessageListener: (TdApi.Message) -> Unit = { msg ->
        if (msg.chatId == chatId) runOnUiThread {
            if (lastMessages.none { it.id == msg.id }) {
                lastMessages = (listOf(msg) + lastMessages).sortedByDescending { it.id }
                lastNewestId = lastMessages.firstOrNull()?.id ?: lastNewestId
                lastSig = sigOf(lastMessages)
                renderWithSenders(lastMessages, scrollBottom = true)
            }
            // Rete di sicurezza: riallinea con la history poco dopo. La guardia lastSig in
            // loadAndRender evita re-render inutili (niente flicker) se non c'è nulla di nuovo.
            scheduleRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatId = intent.getLongExtra("chatId", 0L)
        forumTopicId = intent.getIntExtra("forumTopicId", 0)
        titleText = intent.getStringExtra("title")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 32, 64, 32)
        }
        root.addView(TextView(this).apply {
            text = titleText ?: "Chat"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setPadding(0, 0, 0, 12)
        })

        messagesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Con pochi messaggi, ancorali al fondo (sopra la casella) come Telegram,
            // invece di lasciarli in alto con il vuoto sotto.
            gravity = Gravity.BOTTOM
        }
        messagesScroll = ScrollView(this).apply {
            isFocusable = false
            isFillViewport = true
            addView(messagesBox)
        }
        // Arrivati in cima scorrendo (anche senza elementi selezionabili), carica da solo
        // i messaggi precedenti: la chat si legge a ritroso senza premere nulla.
        messagesScroll.viewTreeObserver.addOnScrollChangedListener {
            if (autoLoadArmed && messagesScroll.scrollY <= 20 &&
                messagesBox.height > messagesScroll.height && lastMessages.isNotEmpty()
            ) loadOlder()
        }
        root.addView(messagesScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
        ).apply { bottomMargin = 12 })

        buttonsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val buttonsScroll = ScrollView(this).apply { isFocusable = false; addView(buttonsBox) }
        root.addView(buttonsScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { bottomMargin = 12 })

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        this.inputRow = inputRow
        input = EditText(this).apply {
            hint = "Scrivi un messaggio…"
            setHintTextColor(0xFF7A8893.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setBackgroundColor(0xFF1B262C.toInt())
            setPadding(24, 20, 24, 20)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(makeButton("Invia") { sendInput() })
        // Barra "risposta a…": compare sopra la casella quando si seleziona un messaggio.
        replyBar = TextView(this).apply {
            setTextColor(0xFF9FC6E8.toInt())
            textSize = 14f
            setPadding(24, 10, 24, 10)
            setBackgroundColor(0xFF16222B.toInt())
            visibility = View.GONE
            isFocusable = true
            setOnFocusChangeListener { v, has ->
                v.setBackgroundColor(if (has) 0xFF24405A.toInt() else 0xFF16222B.toInt())
            }
            setOnClickListener { clearReplyTarget() }
        }
        root.addView(replyBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)

        // La scrittura rispetta le impostazioni "Abilita scrittura".
        val chat0 = TdClient.findChat(chatId)
        val isPrivate = chat0?.type is TdApi.ChatTypePrivate
        isGroupChat = chat0?.type is TdApi.ChatTypeSupergroup || chat0?.type is TdApi.ChatTypeBasicGroup
        // Per i gruppi questa schermata si apre solo dall'icona già autorizzata -> input visibile.
        // Per le chat private decidiamo dopo aver capito se è un bot o una persona.
        inputRow.visibility = if (isPrivate) View.GONE else View.VISIBLE

        TdClient.openChat(chatId)
        TdClient.addMessagesListener(messagesListener)
        TdClient.addNewMessageListener(newMessageListener)
        loadBotInfo()
        loadAndRender()
    }

    private fun sendInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        TdClient.sendText(chatId, t, forumTopicId, replyToMessageId)
        clearReplyTarget()
        input.setText("")
        scheduleRefresh()
    }

    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 250)
    }

    private fun loadBotInfo() {
        val chat = TdClient.findChat(chatId) ?: return
        val type = chat.type
        if (type is TdApi.ChatTypePrivate) {
            TdClient.getUserFullInfo(type.userId) { obj ->
                runOnUiThread {
                    if (obj is TdApi.UserFullInfo) {
                        val info = obj.botInfo
                        isBot = info != null
                        botCommands = info?.commands?.toList() ?: emptyList()
                        val canWrite = if (isBot) Settings.writeFlag(this, "bots", true)
                                       else Settings.writeFlag(this, "private", true)
                        inputRow.visibility = if (canWrite) View.VISIBLE else View.GONE
                        renderWithSenders(lastMessages, true)
                    } else {
                        // Info non disponibili (es. errore di rete): trattiamo come chat privata
                        // normale e rispettiamo comunque l'impostazione di scrittura.
                        inputRow.visibility =
                            if (Settings.writeFlag(this, "private", true)) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun loadAndRender() {
        val cb: (TdApi.Object) -> Unit = { result ->
            val incoming = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            runOnUiThread {
                if (incoming.isEmpty() && lastMessages.isEmpty() && emptyRetries > 0) {
                    emptyRetries--
                    handler.postDelayed({ loadAndRender() }, 500)
                    return@runOnUiThread
                }
                // Unisce gli ultimi messaggi con quelli già caricati (anche più vecchi via
                // "Messaggi precedenti"), senza buttarli via, e togliendo quelli cancellati di recente.
                lastMessages = if (lastMessages.isEmpty()) incoming else mergeMessages(lastMessages, incoming)
                val newestId = lastMessages.firstOrNull()?.id ?: 0L
                val sig = sigOf(lastMessages)
                if (sig == lastSig) return@runOnUiThread     // nulla di nuovo: niente re-render (no flicker)
                val grew = newestId > lastNewestId
                lastSig = sig
                lastNewestId = newestId
                renderWithSenders(lastMessages, scrollBottom = grew)
            }
        }
        if (forumTopicId != 0) TdClient.getForumTopicHistory(chatId, forumTopicId, 0L, 25, cb)
        else TdClient.getChatHistory(chatId, 0L, 25, cb)
    }

    /** Fonde i nuovi messaggi (più recenti) con quelli già caricati e rimuove quelli cancellati
     *  che ricadono nella finestra appena riletta. Risultato ordinato dal più recente. */
    private fun mergeMessages(old: List<TdApi.Message>, incoming: List<TdApi.Message>): List<TdApi.Message> {
        if (incoming.isEmpty()) return old
        val incomingIds = incoming.mapTo(HashSet()) { it.id }
        val minIncoming = incoming.minOf { it.id }
        val byId = LinkedHashMap<Long, TdApi.Message>()
        for (m in incoming) byId[m.id] = m
        for (m in old) {
            if (byId.containsKey(m.id)) continue
            // Nel range della pagina riletta ma non più presente => cancellato: lo lasciamo fuori.
            if (m.id >= minIncoming && m.id !in incomingIds) continue
            byId[m.id] = m
        }
        return byId.values.sortedByDescending { it.id }
    }

    private fun loadOlder() {
        if (loadingOlder || noMoreOlder) return
        val oldestId = lastMessages.lastOrNull()?.id ?: return
        loadingOlder = true
        val cb: (TdApi.Object) -> Unit = { result ->
            val more = (result as? TdApi.Messages)?.messages?.filterNotNull().orEmpty()
            runOnUiThread {
                loadingOlder = false
                if (more.isNotEmpty()) {
                    lastMessages = (lastMessages + more).distinctBy { it.id }.sortedByDescending { it.id }
                    lastSig = sigOf(lastMessages)
                } else {
                    // Conversazione finita: la riga in alto diventa un'etichetta fissa.
                    noMoreOlder = true
                }
                // Riancora la vista al messaggio che era il più vecchio visibile: la lettura
                // riprende esattamente da lì, con i messaggi più vecchi appena caricati sopra.
                pendingAnchorId = oldestId
                renderWithSenders(lastMessages, scrollBottom = false)
            }
        }
        if (forumTopicId != 0) TdClient.getForumTopicHistory(chatId, forumTopicId, oldestId, 25, cb)
        else TdClient.getChatHistory(chatId, oldestId, 25, cb)
    }

    /** Porta il focus (o lo scroll) sulle righe del messaggio indicato. */
    private fun focusAnchor(msgId: Long) {
        for (i in 0 until messagesBox.childCount) {
            val row = messagesBox.getChildAt(i)
            if (row.tag == msgId) {
                val target = findFocusableIn(row)
                if (target != null) target.requestFocus()
                else messagesScroll.scrollTo(0, row.top)
                return
            }
        }
    }

    private fun findFocusableIn(v: View): View? {
        if (v.isFocusable) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findFocusableIn(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun render(msgs: List<TdApi.Message>, scrollBottom: Boolean = true) {
        // Subito dopo l'installazione alcuni dati (utenti, chat, sticker) possono non essere
        // ancora in cache: un accesso incoerente qui faceva chiudere l'app al primo tap su
        // certi dispositivi. Proteggiamo la costruzione delle view: se qualcosa va storto,
        // l'app resta in piedi e il refresh successivo (a dati pronti) mostra tutto.
        try {
            renderInner(msgs, scrollBottom)
        } catch (e: Throwable) {
            handler.postDelayed({ scheduleRefresh() }, 400)
        }
    }

    private fun renderInner(msgs: List<TdApi.Message>, scrollBottom: Boolean = true) {
        messagesBox.removeAllViews()
        if (noMoreOlder) {
            messagesBox.addView(TextView(this).apply {
                text = "—  Inizio della conversazione  —"
                setTextColor(0xFF8899A6.toInt())
                textSize = 14f
                setPadding(16, 16, 16, 16)
            })
        } else {
            // Basta arrivarci col focus: il caricamento parte da solo, senza premere OK.
            addLoaderRow("↑  Messaggi precedenti") { loadOlder() }
        }
        // Raccolgo i media della conversazione per la riproduzione con precedente/successivo.
        val mediaRefs = ArrayList<Triple<Int, Int, String>>()
        var prevSender: String? = null
        for (m in msgs.reversed()) {
            val rowsBefore = messagesBox.childCount
            val mine = m.isOutgoing
            // Avatar solo per i messaggi altrui, sulla prima riga del messaggio.
            if (!mine) { rowAvatarMsg = m; rowAvatarUsed = false } else { rowAvatarMsg = null; rowAvatarUsed = true }
            val media = mediaOf(m)
            val text = if (media == null) messageText(m) else null
            val isSticker = m.content is TdApi.MessageSticker
            if (media == null && !isSticker && (text == null || text.isBlank())) continue
            if (isGroupChat) {
                val sender = senderLabel(m)
                if (sender != prevSender) { addSenderHeader(sender, mine); prevSender = sender }
            }
            // Citazione: se questo messaggio è una risposta, mostra la nuvoletta col
            // messaggio originario sopra il contenuto (come su Telegram).
            (m.replyTo as? TdApi.MessageReplyToMessage)?.let { rt ->
                if (rt.chatId == chatId || rt.chatId == 0L) addReplyQuote(rt, mine)
            }
            if (media != null) {
                val idx = mediaRefs.size
                mediaRefs.add(media)
                val photoContent = m.content as? TdApi.MessagePhoto
                if (media.second == 2 && photoContent != null && Settings.showChatImages(this)) {
                    // Foto: piccola anteprima nella chat; OK apre, pressione prolungata risponde.
                    addPhotoPreview(photoContent, mine,
                        onClick = { playMediaAt(mediaRefs.toList(), idx) },
                        onLongClick = { setReplyTarget(m) })
                } else {
                    val th = if (Settings.showChatImages(this)) mediaThumb(m) else null
                    if (th != null) {
                        // Media con miniatura (video, GIF, audio con copertina, documenti):
                        // anteprima con ▶; OK riproduce a schermo intero, tenuto premuto risponde.
                        addMediaPreview(media.third, th.first, th.second, mine,
                            onClick = { playMediaAt(mediaRefs.toList(), idx) },
                            onLongClick = { setReplyTarget(m) })
                    } else {
                        val icon = if (media.second == 2) "🖼" else "▶"
                        addMessageBubble("$icon  ${media.third}", mine, true,
                            onClick = { playMediaAt(mediaRefs.toList(), idx) },
                            onLongClick = { setReplyTarget(m) })
                    }
                }
            } else if (isSticker) {
                val sticker = (m.content as TdApi.MessageSticker).sticker
                addStickerView(sticker.sticker.id, sticker.sticker.local.path, sticker.format, sticker.emoji, mine)
            } else {
                val t = text!!
                val cmd = findCommand(t)
                val link = findLink(t)
                when {
                    cmd != null -> addMessageBubble(t, mine, true,
                        onClick = { TdClient.sendText(chatId, cmd); scheduleRefresh() },
                        onLongClick = { setReplyTarget(m) })
                    link != null -> addMessageBubble(t, mine, true,
                        onClick = { openLink(link) },
                        onLongClick = { setReplyTarget(m) })
                    // Testo semplice altrui: OK = rispondi. I propri messaggi restano non selezionabili.
                    !mine -> addMessageBubble(t, mine, true, onClick = { setReplyTarget(m) })
                    else -> addMessageBubble(t, mine, false, null)
                }
            }
            // Anteprima link: indipendente dal tipo di contenuto del messaggio (testo, foto con caption, ecc.)
            val lp = (m.content as? TdApi.MessageText)?.linkPreview
            if (lp != null) addLinkPreview(lp, mine)
            // Marca tutte le righe di questo messaggio con il suo id: serve per riancorare
            // la vista dopo il caricamento dei messaggi precedenti.
            for (i in rowsBefore until messagesBox.childCount) messagesBox.getChildAt(i).tag = m.id
        }
        val anchor = pendingAnchorId
        pendingAnchorId = 0L
        if (anchor != 0L) {
            messagesScroll.post { focusAnchor(anchor) }
        } else if (scrollBottom) {
            messagesScroll.post {
                messagesScroll.fullScroll(View.FOCUS_DOWN)
                autoLoadArmed = true
            }
        }

        buttonsBox.removeAllViews()

        val inlineMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupInlineKeyboard }
        if (inlineMsg != null) {
            val markup = inlineMsg.replyMarkup as TdApi.ReplyMarkupInlineKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) row.addView(inlineButton(inlineMsg.id, b))
                buttonsBox.addView(row)
            }
        }

        val kbMsg = msgs.firstOrNull { it.replyMarkup is TdApi.ReplyMarkupShowKeyboard }
        if (kbMsg != null) {
            val markup = kbMsg.replyMarkup as TdApi.ReplyMarkupShowKeyboard
            for (rowArr in markup.rows) {
                val row = newRow()
                for (b in rowArr) row.addView(makeButton(b.text) { TdClient.sendText(chatId, b.text); scheduleRefresh() })
                buttonsBox.addView(row)
            }
        }

        // /start e comandi solo se è davvero un bot.
        if (isBot) {
            buttonsBox.addView(makeButton("/start") { TdClient.sendText(chatId, "/start"); scheduleRefresh() })
            for (c in botCommands) {
                val label = "/${c.command}" + if (c.description.isNotBlank()) "  —  ${c.description}" else ""
                buttonsBox.addView(makeButton(label) { TdClient.sendText(chatId, "/${c.command}"); scheduleRefresh() })
            }
        }

        // L'area dei pulsanti occupa spazio solo se ha davvero dei pulsanti: nelle chat
        // senza tastiera (es. tra persone) va nascosta, altrimenti riserva un terzo dello
        // schermo lasciando un vuoto tra la conversazione e la casella di invio.
        (buttonsBox.parent as? View)?.visibility =
            if (buttonsBox.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun renderWithSenders(msgs: List<TdApi.Message>, scrollBottom: Boolean) {
        if (!isGroupChat) { render(msgs, scrollBottom); return }
        val missing = msgs.mapNotNull { (it.senderId as? TdApi.MessageSenderUser)?.userId }
            .filter { TdClient.cachedUser(it) == null }.distinct()
        if (missing.isEmpty()) { render(msgs, scrollBottom); return }
        var remaining = missing.size
        var done = false
        for (id in missing) {
            TdClient.getUser(id) {
                runOnUiThread {
                    remaining--
                    if (remaining <= 0 && !done) { done = true; render(msgs, scrollBottom) }
                }
            }
        }
    }

    private fun senderLabel(m: TdApi.Message): String {
        return when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> {
                val u = TdClient.cachedUser(s.userId)
                if (u != null) (u.firstName + " " + u.lastName).trim().ifEmpty { "Utente" } else "Utente"
            }
            is TdApi.MessageSenderChat -> TdClient.findChat(s.chatId)?.title ?: "Canale"
            else -> "Sconosciuto"
        }
    }

    private fun addSenderHeader(name: String, mine: Boolean) {
        val tv = TextView(this).apply {
            text = name
            setTextColor(0xFF4FC3F7.toInt())
            textSize = 13f
            setPadding(20, 18, 20, 0)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        if (mine) { row.addView(spacer); row.addView(tv) } else { row.addView(tv); row.addView(spacer) }
        messagesBox.addView(row)
    }

    private fun playMediaAt(refs: List<Triple<Int, Int, String>>, idx: Int) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_FILE_IDS, refs.map { it.first }.toIntArray())
                .putExtra(PlayerActivity.EXTRA_KINDS, refs.map { it.second }.toIntArray())
                .putExtra(PlayerActivity.EXTRA_LABELS, refs.map { it.third }.toTypedArray())
                .putExtra(PlayerActivity.EXTRA_INDEX, idx)
        )
    }

    private fun inlineButton(messageId: Long, b: TdApi.InlineKeyboardButton): Button {
        return makeButton(b.text) {
            when (val ty = b.type) {
                is TdApi.InlineKeyboardButtonTypeCallback -> {
                    TdClient.sendCallback(chatId, messageId, ty.data) { ans ->
                        if (ans is TdApi.CallbackQueryAnswer && ans.text.isNotBlank()) {
                            runOnUiThread { Toast.makeText(this, ans.text, Toast.LENGTH_SHORT).show() }
                        }
                    }
                    scheduleRefresh()
                }
                is TdApi.InlineKeyboardButtonTypeUrl -> openLink(ty.url)
                else -> Toast.makeText(this, "Tipo di pulsante non supportato", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openLink(raw: String) {
        val link = normalizeLink(raw)
        if (link.contains("t.me/c/")) {
            // Risoluzione ufficiale via TDLib: niente calcoli manuali sugli id, niente
            // rischio di finire sul messaggio sbagliato (importante per i pulsanti dei
            // post-menu, es. "St.01", che puntano a un messaggio preciso del canale).
            Toast.makeText(this, "Apro…", Toast.LENGTH_SHORT).show()
            TdClient.getMessageLinkInfo(link) { obj ->
                runOnUiThread {
                    val info = obj as? TdApi.MessageLinkInfo
                    val msg = info?.message
                    when {
                        msg != null -> startActivity(
                            Intent(this, PostViewActivity::class.java)
                                .putExtra("chatId", msg.chatId)
                                .putExtra("messageId", msg.id)
                        )
                        info != null && info.chatId != 0L -> TdClient.getChat(info.chatId) { c ->
                            runOnUiThread {
                                if (c is TdApi.Chat) openChat(c)
                                else Toast.makeText(this, "Chat non accessibile", Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> Toast.makeText(this, "Link non risolvibile: $raw", Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
        if (link.contains("t.me/+") || link.contains("t.me/joinchat/")) {
            openInvite(link)
            return
        }
        if (!link.contains("t.me/") && !raw.trim().startsWith("@")) {
            // Link esterno (non Telegram): apri nel browser di sistema
            val url = if (link.startsWith("http://") || link.startsWith("https://")) link else "https://$link"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Impossibile aprire il link", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val u = extractUsername(raw)
        if (u == null) {
            Toast.makeText(this, "Link non apribile: $raw", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Apro @$u…", Toast.LENGTH_SHORT).show()
        TdClient.searchPublicChat(u) { obj ->
            runOnUiThread {
                if (obj is TdApi.Chat) openChat(obj)
                else Toast.makeText(this, "Non trovato: @$u", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun normalizeLink(raw: String): String {
        var s = raw.trim()
        val i = s.indexOf("t.me/")
        if (i > 0) s = s.substring(i)
        if (s.startsWith("t.me/")) s = "https://$s"
        return s
    }

    private fun openInvite(link: String) {
        Toast.makeText(this, "Apro invito…", Toast.LENGTH_SHORT).show()
        TdClient.checkInviteLink(link) { obj ->
            runOnUiThread {
                if (obj is TdApi.ChatInviteLinkInfo && obj.chatId != 0L) {
                    TdClient.getChat(obj.chatId) { c -> runOnUiThread { if (c is TdApi.Chat) openChat(c) } }
                } else {
                    TdClient.joinByInviteLink(link) { j ->
                        runOnUiThread {
                            if (j is TdApi.Chat) openChat(j)
                            else Toast.makeText(this, "Impossibile aprire l'invito", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun openChat(chat: TdApi.Chat) {
        val intent = if (chat.type.constructor == TdApi.ChatTypePrivate.CONSTRUCTOR)
            Intent(this, BotChatActivity::class.java)
        else
            Intent(this, MediaListActivity::class.java)
        startActivity(intent.putExtra("chatId", chat.id).putExtra("title", chat.title))
    }

    private fun extractUsername(raw: String): String? {
        val s = raw.trim()
        if (s.startsWith("@")) return s.drop(1).takeWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        val i = s.indexOf("t.me/")
        if (i >= 0) {
            val rest = s.substring(i + 5)
            if (rest.startsWith("+") || rest.startsWith("joinchat") || rest.startsWith("c/")) return null
            return rest.takeWhile { it.isLetterOrDigit() || it == '_' }.ifEmpty { null }
        }
        return null
    }

    private fun findLink(text: String): String? {
        Regex("(https?://)?t\\.me/\\S+").find(text)?.let { return it.value }
        Regex("https?://\\S+").find(text)?.let { return it.value }
        Regex("@[A-Za-z0-9_]{4,}").find(text)?.let { return it.value }
        return null
    }

    private fun findCommand(text: String): String? {
        val m = Regex("(?:^|\\s)(/[A-Za-z0-9_]{1,64})").find(text) ?: return null
        return m.groupValues[1]
    }

    private fun mediaOf(m: TdApi.Message): Triple<Int, Int, String>? {
        return when (val c = m.content) {
            is TdApi.MessageVideo -> Triple(c.video.video.id, 0, c.video.fileName.ifEmpty { c.caption.text.ifEmpty { "Video" } })
            is TdApi.MessageAnimation -> Triple(c.animation.animation.id, 0, c.caption.text.ifEmpty { "Video" })
            is TdApi.MessageAudio -> Triple(c.audio.audio.id, 1, c.audio.fileName.ifEmpty { c.caption.text.ifEmpty { "Audio" } })
            is TdApi.MessageVoiceNote -> Triple(c.voiceNote.voice.id, 1, "Messaggio vocale")
            is TdApi.MessageVideoNote -> Triple(c.videoNote.video.id, 0, "Video")
            is TdApi.MessagePhoto -> {
                val s = c.photo.sizes
                if (s.isEmpty()) null
                else Triple(s.maxByOrNull { it.width * it.height }!!.photo.id, 2, c.caption.text.ifEmpty { "Foto" })
            }
            is TdApi.MessageDocument -> {
                val ext = c.document.fileName.substringAfterLast('.', "").lowercase()
                val mime = c.document.mimeType
                when {
                    mime.startsWith("video/") || ext in VIDEO_EXT -> Triple(c.document.document.id, 0, c.document.fileName.ifEmpty { "Video" })
                    mime.startsWith("audio/") || ext in AUDIO_EXT -> Triple(c.document.document.id, 1, c.document.fileName.ifEmpty { "Audio" })
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun messageText(m: TdApi.Message): String? {
        return when (val c = m.content) {
            is TdApi.MessageText -> c.text.text
            is TdApi.MessageAnimatedEmoji -> c.emoji
            is TdApi.MessageSticker -> null
            is TdApi.MessageDocument -> {
                // Documento non riproducibile: mostra il nome completo del file (con
                // estensione) invece del generico "[document]"; la didascalia, se c'è, sotto.
                val name = c.document.fileName.ifEmpty { "Documento" }
                val cap = c.caption.text
                if (cap.isBlank()) "📄 $name" else "📄 $name\n$cap"
            }
            else -> "[" + c.javaClass.simpleName.removePrefix("Message").lowercase() + "]"
        }
    }

    private fun addLinkPreview(lp: TdApi.LinkPreview, mine: Boolean) {
        val capPx = chatContentMaxWidthPx()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1A2A35.toInt())
                cornerRadius = 12f
                setStroke(3, 0xFF2E6E9E.toInt())
            }
            setPadding(20, 16, 20, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val site = lp.siteName.ifEmpty { lp.url }
        if (site.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = site
                setTextColor(0xFF4FC3F7.toInt())
                textSize = 12f
                setPadding(0, 0, 0, 4)
                maxWidth = capPx
            })
        }
        if (lp.title.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = lp.title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 4)
                maxWidth = capPx
            })
        }
        if (lp.description.text.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = lp.description.text
                setTextColor(0xFFB0BEC5.toInt())
                textSize = 13f
                maxLines = 3
                maxWidth = capPx
            })
        }
        // Immagine anteprima da LinkPreviewType
        val previewPhoto: TdApi.Photo? = when (val t = lp.type) {
            is TdApi.LinkPreviewTypePhoto -> t.photo
            is TdApi.LinkPreviewTypeArticle -> t.photo
            else -> null
        }
        val thumb = previewPhoto?.sizes?.maxByOrNull { it.width * it.height }
        if (thumb != null) {
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = LinearLayout.LayoutParams(capPx, 200).also { it.topMargin = 10 }
            }
            card.addView(iv)
            val path0 = thumb.photo.local.path
            if (path0.isNotEmpty()) {
                BitmapFactory.decodeFile(path0)?.let { iv.setImageBitmap(it) }
            } else {
                TdClient.downloadFilePath(thumb.photo.id) { path ->
                    BitmapFactory.decodeFile(path)?.let { bmp ->
                        runOnUiThread { iv.setImageBitmap(bmp) }
                    }
                }
            }
        }
        card.isFocusable = true
        card.setOnFocusChangeListener { v, has ->
            (v.background as? GradientDrawable)
                ?.setColor(if (has) 0xFF2E4A6E.toInt() else 0xFF1A2A35.toInt())
        }
        card.setOnClickListener { openLink(lp.url) }
        wrapInRow(card, mine)
    }

    private fun addStickerView(fileId: Int, localPath: String, format: TdApi.StickerFormat, emoji: String, mine: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
            layoutParams = LinearLayout.LayoutParams(200, 200)
        }

        fun fallbackEmoji() {
            box.removeAllViews()
            box.addView(TextView(this).apply { text = emoji.ifEmpty { "🎭" }; textSize = 36f })
        }

        when (format) {
            is TdApi.StickerFormatTgs -> {
                val lav = LottieAnimationView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(200, 200)
                    repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
                }
                box.addView(lav)

                fun loadLottie(path: String) {
                    // Lettura del file + decompressione GZIP su thread di background: farlo sul main
                    // thread (quando il file era già locale) causava scatti/ANR su sticker grossi.
                    // Il parse vero e proprio lo affidiamo poi al factory asincrono di Lottie.
                    Thread {
                        val json: String? = try {
                            GZIPInputStream(File(path).inputStream()).bufferedReader().use { it.readText() }
                        } catch (e: Exception) { null }
                        if (json == null) {
                            runOnUiThread { if (!isFinishing && !isDestroyed) fallbackEmoji() }
                            return@Thread
                        }
                        LottieCompositionFactory.fromJsonString(json, path)
                            .addListener { comp ->
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed) { lav.setComposition(comp); lav.playAnimation() }
                                }
                            }
                            .addFailureListener {
                                runOnUiThread { if (!isFinishing && !isDestroyed) fallbackEmoji() }
                            }
                    }.start()
                }

                if (localPath.isNotEmpty() && File(localPath).exists()) {
                    loadLottie(localPath)
                } else {
                    TdClient.downloadFilePath(fileId) { path ->
                        if (path.isNotEmpty()) loadLottie(path)
                    }
                }
            }
            is TdApi.StickerFormatWebp -> {
                // Sticker statico: Android decodifica il WebP nativamente con BitmapFactory.
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(200, 200)
                }
                box.addView(iv)

                fun showStatic(path: String) {
                    val bmp = try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
                    if (bmp != null) iv.setImageBitmap(bmp) else fallbackEmoji()
                }

                if (localPath.isNotEmpty() && File(localPath).exists()) {
                    showStatic(localPath)
                } else {
                    TdClient.downloadFilePath(fileId) { path ->
                        if (path.isNotEmpty()) runOnUiThread { if (!isFinishing && !isDestroyed) showStatic(path) }
                    }
                }
            }
            else -> {
                // Sticker video (Webm) non ancora supportato: mostriamo l'emoji come segnaposto.
                fallbackEmoji()
            }
        }

        wrapInRow(box, mine)
    }

    /** Larghezza massima del contenuto di una nuvoletta/anteprima, per non occupare tutto lo schermo. */
    private fun chatContentMaxWidthPx(): Int = (resources.displayMetrics.widthPixels * 0.6).toInt()

    /** Riga di utilità a piena larghezza, senza nuvoletta (es. "Messaggi precedenti"). */
    private fun addLoaderRow(text: String, onClick: () -> Unit) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 16f
            setPadding(16, 16, 16, 16)
            isFocusable = true
            setOnFocusChangeListener { v, has ->
                v.setBackgroundColor(if (has) 0xFF2E6E9E.toInt() else 0x00000000)
                if (has) onClick()   // il focus basta: nessun tasto da premere
            }
            setOnClickListener { onClick() }
        }
        messagesBox.addView(tv)
    }

    /**
     * Messaggio di chat in stile "nuvoletta": allineato a destra (azzurro) se inviato da me,
     * a sinistra (grigio-blu) se ricevuto — come su Telegram Desktop.
     */
    private fun addMessageBubble(text: String, mine: Boolean, focusable: Boolean, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 16f
            setPadding(8, 4, 8, 4)
            maxWidth = chatContentMaxWidthPx()
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 26f
                setColor(if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            setPadding(24, 16, 24, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(tv)
        }
        if (focusable && (onClick != null || onLongClick != null)) {
            bubble.isFocusable = true
            bubble.setOnFocusChangeListener { v, has ->
                (v.background as? GradientDrawable)
                    ?.setColor(if (has) 0xFF3E6FA8.toInt() else if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            if (onClick != null) bubble.setOnClickListener { onClick() }
            if (onLongClick != null) bubble.setOnLongClickListener { onLongClick(); true }
        }
        wrapInRow(bubble, mine)
    }

    /**
     * Piccola anteprima della foto nella chat: mostra subito la mini-miniatura inclusa nel
     * messaggio (istantanea, nessun download), poi scarica la versione piccola e la
     * sostituisce. Cliccabile: apre la foto a schermo intero come prima.
     */
    private fun addPhotoPreview(c: TdApi.MessagePhoto, mine: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(320, 200)
            c.photo.minithumbnail?.data?.let { d ->
                runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                    ?.let { setImageBitmap(it) }
            }
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 26f
                setColor(if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(img)
            val cap = c.caption.text
            if (cap.isNotBlank()) addView(TextView(this@BotChatActivity).apply {
                text = cap
                setTextColor(0xFFE6EDF2.toInt())
                textSize = 14f
                maxWidth = 320
                setPadding(4, 8, 4, 0)
            })
            isFocusable = true
            setOnFocusChangeListener { v, has ->
                (v.background as? GradientDrawable)
                    ?.setColor(if (has) 0xFF3E6FA8.toInt() else if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        wrapInRow(bubble, mine)

        // Scarica la versione piccola e rimpiazza la mini sfocata appena pronta.
        val small = c.photo.sizes.minByOrNull { it.width * it.height }?.photo ?: return
        if (small.local.isDownloadingCompleted && small.local.path.isNotEmpty()) {
            runCatching { BitmapFactory.decodeFile(small.local.path) }.getOrNull()
                ?.let { img.setImageBitmap(it) }
        } else {
            TdClient.downloadFilePath(small.id) { path ->
                runOnUiThread {
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                        ?.let { img.setImageBitmap(it) }
                }
            }
        }
    }

    /** Incolonna [content] a destra se [mine], a sinistra altrimenti, aggiungendolo a messagesBox. */
    private fun wrapInRow(content: View, mine: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4; it.bottomMargin = 4 }
        }
        fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }
        if (mine) {
            row.addView(spacer()); row.addView(content)
        } else {
            // Slot fisso a sinistra: contiene l'avatar sulla prima riga del messaggio,
            // resta vuoto sulle successive così le nuvolette restano allineate.
            val slot = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE)
                    .also { it.rightMargin = 10 }
            }
            if (!rowAvatarUsed) {
                rowAvatarUsed = true
                slot.addView(makeAvatarView(rowAvatarMsg))
            }
            row.addView(slot)
            row.addView(content)
            row.addView(spacer())
        }
        messagesBox.addView(row)
    }

    /** Miniatura circolare dell'immagine profilo del mittente del messaggio. */
    private fun makeAvatarView(m: TdApi.Message?): ImageView {
        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(AVATAR_SIZE, AVATAR_SIZE)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Segnaposto: cerchio grigio finché non arriva la foto (o se non c'è).
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3A4A55.toInt())
            }
        }
        if (m == null) return img

        val key: String
        var mini: ByteArray? = null
        var file: TdApi.File? = null
        when (val s = m.senderId) {
            is TdApi.MessageSenderUser -> {
                key = "u${s.userId}"
                val u = TdClient.cachedUser(s.userId)
                mini = u?.profilePhoto?.minithumbnail?.data
                file = u?.profilePhoto?.small
            }
            is TdApi.MessageSenderChat -> {
                key = "c${s.chatId}"
                val ch = TdClient.findChat(s.chatId)
                mini = ch?.photo?.minithumbnail?.data
                file = ch?.photo?.small
            }
            else -> return img
        }

        avatarCache[key]?.let { img.setImageBitmap(it); return img }

        // Mini-miniatura subito (sfocata ma istantanea), poi la versione vera.
        mini?.let { d ->
            runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                ?.let { img.setImageBitmap(circled(it)) }
        }
        val f = file ?: return img
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
            runCatching { BitmapFactory.decodeFile(f.local.path) }.getOrNull()?.let {
                val c = circled(it)
                avatarCache[key] = c
                img.setImageBitmap(c)
            }
        } else {
            TdClient.downloadFilePath(f.id) { path ->
                runOnUiThread {
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let {
                        val c = circled(it)
                        avatarCache[key] = c
                        img.setImageBitmap(c)
                    }
                }
            }
        }
        return img
    }

    /** Ritaglia la bitmap a cerchio. */
    private fun circled(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r, paint)
        return out
    }

    /**
     * Miniature disponibili per il contenuto del messaggio: mini-miniatura istantanea
     * (bytes) e file della miniatura vera da scaricare. Null se il contenuto non ne ha.
     */
    private fun mediaThumb(m: TdApi.Message): Pair<ByteArray?, TdApi.File?>? {
        val pair: Pair<ByteArray?, TdApi.File?> = when (val c = m.content) {
            is TdApi.MessageVideo -> c.video.minithumbnail?.data to c.video.thumbnail?.file
            is TdApi.MessageAnimation -> c.animation.minithumbnail?.data to c.animation.thumbnail?.file
            is TdApi.MessageVideoNote -> c.videoNote.minithumbnail?.data to c.videoNote.thumbnail?.file
            is TdApi.MessageAudio -> c.audio.albumCoverMinithumbnail?.data to c.audio.albumCoverThumbnail?.file
            is TdApi.MessageDocument -> c.document.minithumbnail?.data to c.document.thumbnail?.file
            is TdApi.MessagePhoto -> c.photo.minithumbnail?.data to
                c.photo.sizes.minByOrNull { it.width * it.height }?.photo
            else -> return null
        }
        return if (pair.first == null && pair.second == null) null else pair
    }

    /**
     * Nuvoletta-citazione del messaggio a cui [m] risponde: barra colorata, eventuale
     * miniatura del media originale, mittente ed estratto. Se il messaggio originale è un
     * media riproducibile, la citazione è selezionabile e OK lo riproduce a schermo intero.
     */
    private fun addReplyQuote(rt: TdApi.MessageReplyToMessage, mine: Boolean) {
        val senderTv = TextView(this).apply {
            setTextColor(0xFF6FB1E8.toInt())
            textSize = 12f
            text = "…"
        }
        val excerptTv = TextView(this).apply {
            setTextColor(0xFFB8C4CC.toInt())
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = (chatContentMaxWidthPx() * 0.8).toInt()
        }
        val thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(44, 44).also { it.rightMargin = 10 }
            visibility = View.GONE
        }
        val quote = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = 14f
                setColor(0xFF17242E.toInt())
            }
            setPadding(10, 8, 14, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            // Barra verticale azzurra in stile Telegram.
            addView(View(this@BotChatActivity).apply {
                setBackgroundColor(0xFF6FB1E8.toInt())
                layoutParams = LinearLayout.LayoutParams(5, LinearLayout.LayoutParams.MATCH_PARENT)
                    .also { it.rightMargin = 10 }
            })
            addView(thumbView)
            addView(LinearLayout(this@BotChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(senderTv)
                addView(excerptTv)
            })
        }
        wrapInRow(quote, mine)

        fun fill(orig: TdApi.Message) {
            senderTv.text = senderLabel(orig)
            val media = mediaOf(orig)
            excerptTv.text = (messageText(orig) ?: media?.third ?: "").replace('\n', ' ')
            mediaThumb(orig)?.first?.let { d ->
                runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()?.let {
                    thumbView.setImageBitmap(it)
                    thumbView.visibility = View.VISIBLE
                }
            }
            // Se l'originale è un media riproducibile: OK sulla citazione lo apre a schermo intero.
            if (media != null) {
                quote.isFocusable = true
                quote.setOnFocusChangeListener { v, has ->
                    (v.background as? GradientDrawable)
                        ?.setColor(if (has) 0xFF2C4356.toInt() else 0xFF17242E.toInt())
                }
                quote.setOnClickListener { playMediaAt(listOf(media), 0) }
            }
        }

        val orig = lastMessages.firstOrNull { it.id == rt.messageId }
        if (orig != null) {
            fill(orig)
        } else {
            // Non tra i messaggi caricati: lo recuperiamo da TDLib e completiamo la citazione.
            TdClient.getMessage(chatId, rt.messageId) { obj ->
                if (obj is TdApi.Message) runOnUiThread { fill(obj) }
                else runOnUiThread { senderTv.text = "Messaggio"; excerptTv.text = "non disponibile" }
            }
        }
    }

    /**
     * Anteprima con miniatura per i media (video, GIF, audio con copertina, documenti):
     * immagine con simbolo ▶ sovrapposto e nome sotto. OK riproduce a schermo intero,
     * pressione prolungata attiva la risposta.
     */
    private fun addMediaPreview(
        label: String,
        mini: ByteArray?,
        thumbFile: TdApi.File?,
        mine: Boolean,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
    ) {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(320, 190)
            mini?.let { d ->
                runCatching { BitmapFactory.decodeByteArray(d, 0, d.size) }.getOrNull()
                    ?.let { setImageBitmap(it) }
            }
        }
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(320, 190)
            addView(img)
            // Simbolo play sovrapposto al centro.
            addView(TextView(this@BotChatActivity).apply {
                text = "▶"
                textSize = 30f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(8f, 0f, 0f, 0xFF000000.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            })
        }
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 26f
                setColor(if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            setPadding(12, 12, 12, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(frame)
            if (label.isNotBlank()) addView(TextView(this@BotChatActivity).apply {
                text = label
                setTextColor(0xFFE6EDF2.toInt())
                textSize = 14f
                maxWidth = 320
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(4, 8, 4, 0)
            })
            isFocusable = true
            setOnFocusChangeListener { v, has ->
                (v.background as? GradientDrawable)
                    ?.setColor(if (has) 0xFF3E6FA8.toInt() else if (mine) COLOR_BUBBLE_MINE else COLOR_BUBBLE_OTHER)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        wrapInRow(bubble, mine)

        // Scarica la miniatura vera e sostituisce la mini sfocata appena pronta.
        val f = thumbFile ?: return
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
            runCatching { BitmapFactory.decodeFile(f.local.path) }.getOrNull()?.let { img.setImageBitmap(it) }
        } else {
            TdClient.downloadFilePath(f.id) { path ->
                runOnUiThread {
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.let { img.setImageBitmap(it) }
                }
            }
        }
    }

    private fun newRow(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setBackgroundResource(R.drawable.bg_button)
            setTextColor(0xFFFFFFFF.toInt())
            isAllCaps = false
            textSize = 16f
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 16
            lp.bottomMargin = 12
            layoutParams = lp
            setPadding(28, 18, 28, 18)
            setOnClickListener { onClick() }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(refreshRunnable)
        TdClient.removeMessagesListener(messagesListener)
        TdClient.removeNewMessageListener(newMessageListener)
    }
}
