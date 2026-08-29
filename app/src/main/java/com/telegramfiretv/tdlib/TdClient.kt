package com.telegramfiretv.tdlib

import android.content.Context
import com.telegramfiretv.BuildConfig
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdClient {

    private var client: Client? = null
    private lateinit var dbDir: String
    private var dbKey: ByteArray = ByteArray(0)

    @Volatile
    var authState: TdApi.AuthorizationState? = null
        private set

    private val authListeners = CopyOnWriteArrayList<(TdApi.AuthorizationState?) -> Unit>()
    fun addAuthListener(l: (TdApi.AuthorizationState?) -> Unit) { authListeners.add(l) }
    fun removeAuthListener(l: (TdApi.AuthorizationState?) -> Unit) { authListeners.remove(l) }

    private val chatsListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addChatsListener(l: () -> Unit) { chatsListeners.add(l) }
    fun removeChatsListener(l: () -> Unit) { chatsListeners.remove(l) }

    private val messagesListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    fun addMessagesListener(l: (Long) -> Unit) { messagesListeners.add(l) }
    fun removeMessagesListener(l: (Long) -> Unit) { messagesListeners.remove(l) }

    // Listener che ricevono direttamente il nuovo messaggio (non solo il chatId): permette
    // di mostrarlo subito senza rileggere la history, che può essere ancora indietro.
    private val newMessageListeners = CopyOnWriteArrayList<(TdApi.Message) -> Unit>()
    fun addNewMessageListener(l: (TdApi.Message) -> Unit) { newMessageListeners.add(l) }
    fun removeNewMessageListener(l: (TdApi.Message) -> Unit) { newMessageListeners.remove(l) }

    private val fileListeners = CopyOnWriteArrayList<(TdApi.File) -> Unit>()
    fun addFileListener(l: (TdApi.File) -> Unit) { fileListeners.add(l) }
    fun removeFileListener(l: (TdApi.File) -> Unit) { fileListeners.remove(l) }

    val chats: MutableList<TdApi.Chat> = mutableListOf()
    val users = HashMap<Long, TdApi.User>()
    private val mainOrder = HashMap<Long, Long>()
    private val archiveOrder = HashMap<Long, Long>()

    fun init(context: Context) {
        if (client != null) return
        dbDir = context.filesDir.absolutePath + "/tdlib"
        dbKey = loadOrCreateDbKey(context)
        Client.setLogMessageHandler(0) { _, _ -> }
        client = Client.create({ obj -> onResult(obj) }, null, null)
    }

    private fun loadOrCreateDbKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences("tgftv_secure", Context.MODE_PRIVATE)
        prefs.getString("db_key", null)?.let {
            return android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
        }
        val dbExists = java.io.File(dbDir).exists()
        val key = if (dbExists) ByteArray(0)
                  else ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("db_key", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP))
            .apply()
        return key
    }

    private fun applyPositions(chatId: Long, positions: Array<TdApi.ChatPosition>) {
        mainOrder.remove(chatId)
        archiveOrder.remove(chatId)
        for (p in positions) {
            when (p.list.constructor) {
                TdApi.ChatListMain.CONSTRUCTOR -> if (p.order != 0L) mainOrder[chatId] = p.order
                TdApi.ChatListArchive.CONSTRUCTOR -> if (p.order != 0L) archiveOrder[chatId] = p.order
            }
        }
    }

    private fun onResult(obj: TdApi.Object) {
        when (obj.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR ->
                handleAuthState((obj as TdApi.UpdateAuthorizationState).authorizationState)

            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val chat = (obj as TdApi.UpdateNewChat).chat
                synchronized(chats) {
                    if (chats.none { it.id == chat.id }) chats.add(chat)
                    applyPositions(chat.id, chat.positions)
                }
                for (l in chatsListeners) l()
            }

            TdApi.UpdateChatPosition.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatPosition
                val lc = u.position.list.constructor
                if (lc == TdApi.ChatListMain.CONSTRUCTOR || lc == TdApi.ChatListArchive.CONSTRUCTOR) {
                    synchronized(chats) {
                        val map = if (lc == TdApi.ChatListMain.CONSTRUCTOR) mainOrder else archiveOrder
                        if (u.position.order == 0L) map.remove(u.chatId) else map[u.chatId] = u.position.order
                    }
                    for (l in chatsListeners) l()
                }
            }

            TdApi.UpdateFile.CONSTRUCTOR -> {
                val f = (obj as TdApi.UpdateFile).file
                for (l in fileListeners) l(f)
            }

            TdApi.UpdateUser.CONSTRUCTOR -> {
                val u = (obj as TdApi.UpdateUser).user
                synchronized(users) { users[u.id] = u }
            }

            TdApi.UpdateNewMessage.CONSTRUCTOR -> {
                val msg = (obj as TdApi.UpdateNewMessage).message
                for (l in messagesListeners) l(msg.chatId)
                for (l in newMessageListeners) l(msg)
            }

            TdApi.UpdateMessageEdited.CONSTRUCTOR ->
                for (l in messagesListeners) l((obj as TdApi.UpdateMessageEdited).chatId)

            TdApi.UpdateMessageContent.CONSTRUCTOR ->
                for (l in messagesListeners) l((obj as TdApi.UpdateMessageContent).chatId)

            TdApi.UpdateChatTitle.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatTitle
                synchronized(chats) { chats.firstOrNull { it.id == u.chatId }?.title = u.title }
                for (l in chatsListeners) l()
            }

            TdApi.UpdateChatPhoto.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatPhoto
                synchronized(chats) { chats.firstOrNull { it.id == u.chatId }?.photo = u.photo }
                for (l in chatsListeners) l()
            }

            TdApi.UpdateChatLastMessage.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatLastMessage
                synchronized(chats) {
                    chats.firstOrNull { it.id == u.chatId }?.lastMessage = u.lastMessage
                    applyPositions(u.chatId, u.positions)
                }
                for (l in chatsListeners) l()
                // Alcune risposte (soprattutto dei bot) arrivano alla chat aperta come
                // aggiornamento dell'ultimo messaggio invece che come UpdateNewMessage:
                // inoltriamo anche questo ai listener del messaggio, così la chat aperta le
                // mostra subito senza dover uscire e rientrare.
                u.lastMessage?.let { m -> for (l in newMessageListeners) l(m) }
            }

            TdApi.UpdateChatReadInbox.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatReadInbox
                synchronized(chats) {
                    chats.firstOrNull { it.id == u.chatId }?.let {
                        it.unreadCount = u.unreadCount
                        it.lastReadInboxMessageId = u.lastReadInboxMessageId
                    }
                }
                for (l in chatsListeners) l()
            }

            TdApi.UpdateDeleteMessages.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateDeleteMessages
                if (u.isPermanent) for (l in messagesListeners) l(u.chatId)
            }
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        authState = state
        when (state.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                val params = TdApi.SetTdlibParameters()
                params.databaseDirectory = dbDir
                params.useMessageDatabase = true
                params.useSecretChats = false
                params.apiId = BuildConfig.API_ID
                params.apiHash = BuildConfig.API_HASH
                params.systemLanguageCode = "it"
                params.deviceModel = "Fire TV"
                params.applicationVersion = "1.0"
                params.databaseEncryptionKey = dbKey
                client?.send(params) {}
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                synchronized(chats) { chats.clear(); mainOrder.clear(); archiveOrder.clear() }
                synchronized(users) { users.clear() }
                client = Client.create({ obj -> onResult(obj) }, null, null)
            }
        }
        for (l in authListeners) l(state)
    }

    fun sendPhone(phone: String) {
        client?.send(TdApi.SetAuthenticationPhoneNumber(phone, null)) {}
    }

    fun sendCode(code: String) {
        client?.send(TdApi.CheckAuthenticationCode(code)) {}
    }

    fun sendPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) {}
    }

    fun sendEmailAddress(email: String) {
        client?.send(TdApi.SetAuthenticationEmailAddress(email)) {}
    }

    fun sendEmailCode(code: String) {
        client?.send(TdApi.CheckAuthenticationEmailCode(TdApi.EmailAddressAuthenticationCode(code))) {}
    }

    fun logout() {
        client?.send(TdApi.LogOut()) {}
    }

    fun loadChats(limit: Int = 50) {
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) {}
        client?.send(TdApi.LoadChats(TdApi.ChatListArchive(), limit)) {}
    }

    /** Esce da un canale/gruppo (o abbandona una chat privata dai contatti recenti). */
    fun leaveChat(chatId: Long, handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.LeaveChat(chatId)) { handler(it) }
    }

    /**
     * Rimuove una chat PRIVATA (persona o bot) dall'elenco, cancellandone la cronologia.
     * NOTA: TDLib non permette di "uscire" (LeaveChat) dalle chat private o segrete — per
     * quelle l'unica azione equivalente è eliminarne la cronologia e toglierle dalla lista.
     */
    fun deleteChatHistory(chatId: Long, removeFromChatList: Boolean = true, revoke: Boolean = false, handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.DeleteChatHistory(chatId, removeFromChatList, revoke)) { handler(it) }
    }

    fun orderedChats(): List<TdApi.Chat> {
        synchronized(chats) {
            val main = chats.filter { mainOrder.containsKey(it.id) }
                .sortedByDescending { mainOrder[it.id] ?: 0L }
            val arch = chats.filter { !mainOrder.containsKey(it.id) && archiveOrder.containsKey(it.id) }
                .sortedByDescending { archiveOrder[it.id] ?: 0L }
            return main + arch
        }
    }

    fun orderedMainChats(): List<TdApi.Chat> {
        synchronized(chats) {
            return chats.filter { mainOrder.containsKey(it.id) }
                .sortedByDescending { mainOrder[it.id] ?: 0L }
        }
    }

    fun orderedArchiveChats(): List<TdApi.Chat> {
        synchronized(chats) {
            return chats.filter { archiveOrder.containsKey(it.id) && !mainOrder.containsKey(it.id) }
                .sortedByDescending { archiveOrder[it.id] ?: 0L }
        }
    }

    fun openChat(chatId: Long) {
        client?.send(TdApi.OpenChat(chatId)) {}
    }

    fun findChat(chatId: Long): TdApi.Chat? = synchronized(chats) { chats.firstOrNull { it.id == chatId } }

    fun cachedUser(userId: Long): TdApi.User? = synchronized(users) { users[userId] }

    fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int, offset: Int = 0, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false)) { handler(it) }
    }

    fun getForumTopics(chatId: Long, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetForumTopics(chatId, "", 0, 0, 0, 100)) { handler(it) }
    }

    fun getThreadHistory(chatId: Long, messageThreadId: Long, fromMessageId: Long, limit: Int, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetMessageThreadHistory(chatId, messageThreadId, fromMessageId, 0, limit)) { handler(it) }
    }

    fun getForumTopicHistory(chatId: Long, forumTopicId: Int, fromMessageId: Long, limit: Int, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetForumTopicHistory(chatId, forumTopicId, fromMessageId, 0, limit)) { handler(it) }
    }

    fun downloadFile(fileId: Int, handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { handler(it) }
    }

    fun downloadFileRange(fileId: Int, offset: Long, limit: Long, handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.DownloadFile(fileId, 32, offset, limit, false)) { handler(it) }
    }

    fun getFile(fileId: Int, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetFile(fileId)) { handler(it) }
    }

    fun downloadFilePath(fileId: Int, handler: (String) -> Unit) {
        client?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { obj ->
            val path = (obj as? TdApi.File)?.local?.path ?: ""
            if (path.isNotEmpty()) { handler(path); return@send }
            lateinit var listener: (TdApi.File) -> Unit
            listener = { file ->
                if (file.id == fileId && file.local.isDownloadingCompleted) {
                    removeFileListener(listener)
                    handler(file.local.path)
                }
            }
            addFileListener(listener)
        }
    }

    fun cancelDownload(fileId: Int) {
        client?.send(TdApi.CancelDownloadFile(fileId, false)) {}
    }

    fun deleteFile(fileId: Int) {
        client?.send(TdApi.DeleteFile(fileId)) {}
    }

    fun searchPublicChats(query: String, handler: (TdApi.Object) -> Unit) {
        // Versioni recenti di TDLib richiedono anche il filtro per tipo di chat:
        // null = nessun filtro, stesso comportamento di prima (cerca in tutti i tipi).
        client?.send(TdApi.SearchPublicChats(query, null)) { handler(it) }
    }

    fun getChat(chatId: Long, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetChat(chatId)) { handler(it) }
    }

    fun clearCache(handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.OptimizeStorage()) { handler(it) }
    }

    /** Recupera un singolo messaggio per id (usato per mostrare le citazioni delle risposte). */
    fun getMessage(chatId: Long, messageId: Long, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetMessage(chatId, messageId)) { handler(it) }
    }

    /**
     * Risolve UFFICIALMENTE un link t.me (es. t.me/c/<id>/<messageId>) in chat e messaggio
     * esatti, usando la logica di TDLib stessa invece di calcoli manuali sugli id — più
     * affidabile per i link presi dai pulsanti dei post (menu con episodi/stagioni ecc.).
     */
    fun getMessageLinkInfo(url: String, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetMessageLinkInfo(url)) { handler(it) }
    }

    fun sendText(chatId: Long, text: String, forumTopicId: Int = 0, replyToMessageId: Long = 0L) {
        val topic: TdApi.MessageTopic? = if (forumTopicId != 0) TdApi.MessageTopicForum(forumTopicId) else null
        // Risposta a un messaggio specifico della stessa chat (citazione visibile a tutti).
        // Firma a 4 argomenti della versione TDLib in uso: (messageId, quote, checklistTaskId, pollOptionId).
        val replyTo: TdApi.InputMessageReplyTo? =
            if (replyToMessageId != 0L) TdApi.InputMessageReplyToMessage(replyToMessageId, null, 0, null) else null
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        client?.send(TdApi.SendMessage(chatId, topic, replyTo, null, null, content)) {}
    }

    fun sendCallback(chatId: Long, messageId: Long, data: ByteArray, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetCallbackQueryAnswer(chatId, messageId, TdApi.CallbackQueryPayloadData(data))) { handler(it) }
    }

    fun getUserFullInfo(userId: Long, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetUserFullInfo(userId)) { handler(it) }
    }

    fun getUser(userId: Long, handler: (TdApi.Object) -> Unit) {
        val cached = synchronized(users) { users[userId] }
        if (cached != null) { handler(cached); return }
        client?.send(TdApi.GetUser(userId)) { obj ->
            if (obj is TdApi.User) synchronized(users) { users[obj.id] = obj }
            handler(obj)
        }
    }

    fun searchPublicChat(username: String, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.SearchPublicChat(username)) { handler(it) }
    }

    fun checkInviteLink(link: String, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.CheckChatInviteLink(link)) { handler(it) }
    }

    fun joinByInviteLink(link: String, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.JoinChatByInviteLink(link)) { handler(it) }
    }
}
