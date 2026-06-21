package com.telegramfiretv.tdlib

import android.content.Context
import com.telegramfiretv.BuildConfig
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.concurrent.CopyOnWriteArrayList

object TdClient {

    private var client: Client? = null
    private lateinit var dbDir: String

    @Volatile
    var authState: TdApi.AuthorizationState? = null
        private set

    // Tutti gli eventi usano liste di listener (thread-safe) invece di un singolo campo
    // sovrascrivibile: così schermate diverse possono registrarsi/deregistrarsi in modo
    // indipendente senza azzerarsi a vicenda (problema tipico con i fragment + replace).
    private val authListeners = CopyOnWriteArrayList<(TdApi.AuthorizationState?) -> Unit>()
    fun addAuthListener(l: (TdApi.AuthorizationState?) -> Unit) { authListeners.add(l) }
    fun removeAuthListener(l: (TdApi.AuthorizationState?) -> Unit) { authListeners.remove(l) }

    private val chatsListeners = CopyOnWriteArrayList<() -> Unit>()
    fun addChatsListener(l: () -> Unit) { chatsListeners.add(l) }
    fun removeChatsListener(l: () -> Unit) { chatsListeners.remove(l) }

    private val messagesListeners = CopyOnWriteArrayList<(Long) -> Unit>()
    fun addMessagesListener(l: (Long) -> Unit) { messagesListeners.add(l) }
    fun removeMessagesListener(l: (Long) -> Unit) { messagesListeners.remove(l) }

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
        Client.setLogMessageHandler(0) { _, _ -> }
        client = Client.create({ obj -> onResult(obj) }, null, null)
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

            TdApi.UpdateNewMessage.CONSTRUCTOR ->
                for (l in messagesListeners) l((obj as TdApi.UpdateNewMessage).message.chatId)

            TdApi.UpdateMessageEdited.CONSTRUCTOR ->
                for (l in messagesListeners) l((obj as TdApi.UpdateMessageEdited).chatId)

            TdApi.UpdateMessageContent.CONSTRUCTOR ->
                for (l in messagesListeners) l((obj as TdApi.UpdateMessageContent).chatId)
        }
    }

    private fun handleAuthState(state: TdApi.AuthorizationState) {
        authState = state
        if (state.constructor == TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR) {
            val params = TdApi.SetTdlibParameters()
            params.databaseDirectory = dbDir
            params.useMessageDatabase = true
            params.useSecretChats = false
            params.apiId = BuildConfig.API_ID
            params.apiHash = BuildConfig.API_HASH
            params.systemLanguageCode = "it"
            params.deviceModel = "Fire TV"
            params.applicationVersion = "1.0"
            params.databaseEncryptionKey = ByteArray(0)
            client?.send(params) {}
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

    fun loadChats(limit: Int = 50) {
        client?.send(TdApi.LoadChats(TdApi.ChatListMain(), limit)) {}
        client?.send(TdApi.LoadChats(TdApi.ChatListArchive(), limit)) {}
    }

    /** Chat delle tue liste (principale, poi archivio); esclude quelle scoperte dalla ricerca. */
    fun orderedChats(): List<TdApi.Chat> {
        synchronized(chats) {
            val main = chats.filter { mainOrder.containsKey(it.id) }
                .sortedByDescending { mainOrder[it.id] ?: 0L }
            val arch = chats.filter { !mainOrder.containsKey(it.id) && archiveOrder.containsKey(it.id) }
                .sortedByDescending { archiveOrder[it.id] ?: 0L }
            return main + arch
        }
    }

    /** Solo le chat della lista principale. */
    fun orderedMainChats(): List<TdApi.Chat> {
        synchronized(chats) {
            return chats.filter { mainOrder.containsKey(it.id) }
                .sortedByDescending { mainOrder[it.id] ?: 0L }
        }
    }

    /** Solo le chat archiviate. */
    fun orderedArchiveChats(): List<TdApi.Chat> {
        synchronized(chats) {
            return chats.filter { archiveOrder.containsKey(it.id) && !mainOrder.containsKey(it.id) }
                .sortedByDescending { archiveOrder[it.id] ?: 0L }
        }
    }

    fun openChat(chatId: Long) {
        client?.send(TdApi.OpenChat(chatId)) {}
    }

    /** Cerca una chat già nota in modo thread-safe (la lista è mutata dal thread di TDLib). */
    fun findChat(chatId: Long): TdApi.Chat? = synchronized(chats) { chats.firstOrNull { it.id == chatId } }

    /** Legge un utente dalla cache in modo thread-safe. */
    fun cachedUser(userId: Long): TdApi.User? = synchronized(users) { users[userId] }

    fun getChatHistory(chatId: Long, fromMessageId: Long, limit: Int, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetChatHistory(chatId, fromMessageId, 0, limit, false)) { handler(it) }
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

    /**
     * Scarica un intervallo del file con priorità alta, per lo streaming.
     * offset/limit in byte; TDLib scarica per blocchi a partire da offset.
     */
    fun downloadFileRange(fileId: Int, offset: Long, limit: Long, handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.DownloadFile(fileId, 32, offset, limit, false)) { handler(it) }
    }

    /** Richiede lo stato corrente del file (per leggere quanto è scaricato in sequenza). */
    fun getFile(fileId: Int, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetFile(fileId)) { handler(it) }
    }

    /** Scarica un file e chiama [handler] con il path locale una volta completato. */
    fun downloadFilePath(fileId: Int, handler: (String) -> Unit) {
        client?.send(TdApi.DownloadFile(fileId, 32, 0, 0, false)) { obj ->
            val path = (obj as? TdApi.File)?.local?.path ?: ""
            if (path.isNotEmpty()) { handler(path); return@send }
            // Fallback: ascolta gli aggiornamenti finché il file è scaricato, usando la
            // lista condivisa fileListeners (sicura per usi concorrenti e indipendente
            // dagli altri ascoltatori registrati nell'app).
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
        client?.send(TdApi.SearchPublicChats(query)) { handler(it) }
    }

    fun getChat(chatId: Long, handler: (TdApi.Object) -> Unit) {
        client?.send(TdApi.GetChat(chatId)) { handler(it) }
    }

    /** Libera i file scaricati gestiti da TDLib. */
    fun clearCache(handler: (TdApi.Object) -> Unit = {}) {
        client?.send(TdApi.OptimizeStorage()) { handler(it) }
    }

    fun sendText(chatId: Long, text: String, forumTopicId: Int = 0) {
        val topic: TdApi.MessageTopic? = if (forumTopicId != 0) TdApi.MessageTopicForum(forumTopicId) else null
        val content = TdApi.InputMessageText(TdApi.FormattedText(text, emptyArray()), null, false)
        client?.send(TdApi.SendMessage(chatId, topic, null, null, null, content)) {}
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
