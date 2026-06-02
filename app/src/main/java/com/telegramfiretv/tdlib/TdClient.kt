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
    private val chatOrder = HashMap<Long, Long>()

    fun init(context: Context) {
        if (client != null) return
        dbDir = context.filesDir.absolutePath + "/tdlib"
        Client.setLogMessageHandler(0) { _, _ -> }
        client = Client.create({ obj -> onResult(obj) }, null, null)
    }

    private fun mainOrder(positions: Array<TdApi.ChatPosition>): Long {
        for (p in positions) if (p.list.constructor == TdApi.ChatListMain.CONSTRUCTOR) return p.order
        return 0L
    }

    private fun onResult(obj: TdApi.Object) {
        when (obj.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR ->
                handleAuthState((obj as TdApi.UpdateAuthorizationState).authorizationState)

            TdApi.UpdateNewChat.CONSTRUCTOR -> {
                val chat = (obj as TdApi.UpdateNewChat).chat
                synchronized(chats) {
                    if (chats.none { it.id == chat.id }) chats.add(chat)
                    chatOrder[chat.id] = mainOrder(chat.positions)
                }
                onChatsChanged?.invoke()
            }

            TdApi.UpdateChatPosition.CONSTRUCTOR -> {
                val u = obj as TdApi.UpdateChatPosition
                if (u.position.list.constructor == TdApi.ChatListMain.CONSTRUCTOR) {
                    synchronized(chats) { chatOrder[u.chatId] = u.position.order }
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
    }

    /** Chat ordinate come nella lista principale di Telegram (più in alto = ordine maggiore). */
    fun orderedChats(): List<TdApi.Chat> {
        synchronized(chats) {
            return chats.sortedByDescending { chatOrder[it.id] ?: 0L }
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
}
