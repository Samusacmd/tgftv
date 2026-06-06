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

    var onAuthStateChanged: ((TdApi.AuthorizationState?) -> Unit)? = null
    var onChatsChanged: (() -> Unit)? = null
    var onFileUpdated: ((TdApi.File) -> Unit)? = null

    private val fileListeners = CopyOnWriteArrayList<(TdApi.File) -> Unit>()
    fun addFileListener(l: (TdApi.File) -> Unit) { fileListeners.add(l) }
    fun removeFileListener(l: (TdApi.File) -> Unit) { fileListeners.remove(l) }

    val chats: MutableList<TdApi.Chat> = mutableListOf()
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
                onChatsChanged?.invoke()
            }

            TdApi.UpdateChatPosition.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatPosition
                val lc = u.position.list.constructor
                if (lc == TdApi.ChatListMain.CONSTRUCTOR || lc == TdApi.ChatListArchive.CONSTRUCTOR) {
                    synchronized(chats) {
                        val map = if (lc == TdApi.ChatListMain.CONSTRUCTOR) mainOrder else archiveOrder
                        if (u.position.order == 0L) map.remove(u.chatId) else map[u.chatId] = u.position.order
                    }
                    onChatsChanged?.invoke()
                }
            }

            TdApi.UpdateFile.CONSTRUCTOR -> {
                val f = (obj as TdApi.UpdateFile).file
                onFileUpdated?.invoke(f)
                for (l in fileListeners) l(f)
            }
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
        onAuthStateChanged?.invoke(state)
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
}
