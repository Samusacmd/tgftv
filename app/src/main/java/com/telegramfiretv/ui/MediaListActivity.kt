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
    p
