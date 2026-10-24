package com.telegramfiretv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.File
import java.util.concurrent.Executors

/**
 * Visualizzatore PDF in app (PdfRenderer di Android, nessuna libreria esterna).
 *
 * Leggerezza: del documento esiste in memoria solo la pagina corrente e le due adiacenti
 * (precaricate in background, a zoom "adatta allo schermo"). Con lo zoom si renderizza solo
 * la porzione visibile (bitmap grande quanto lo schermo, mai l'intera pagina ingrandita).
 *
 * Telecomando:
 *  - Senza zoom: sinistra/su = pagina precedente, destra/giù = pagina successiva.
 *  - OK/centro: zoom 1x -> 2x -> 3x -> 1x. Con zoom le frecce spostano la vista; giù oltre il
 *    fondo passa alla pagina dopo, su oltre l'inizio torna alla pagina prima.
 *  - Avanti/indietro rapido (media next/prev, page up/down) cambiano sempre pagina.
 */
class PdfViewerActivity : Activity() {

    companion object {
        const val EXTRA_FILE_ID = "fileId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_KEY = "key"
    }

    /** Pagina già disegnata: bitmap grande come lo schermo + geometria della pagina sullo schermo. */
    private class Rendered(val bmp: Bitmap, val cw: Float, val ch: Float, val tx: Float, val ty: Float)

    /** Motore di rendering: pdfium (libreria inclusa) oppure PdfRenderer di sistema come ripiego. */
    private interface PageSource {
        val count: Int
        fun pageSize(i: Int): Pair<Float, Float>
        /** Disegna la pagina [i] nel bitmap: angolo in (x,y), dimensione w x h. */
        fun draw(i: Int, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, sc: Float)
        fun close()
    }

    private class PdfiumSource(core: PdfiumCore, private val pfd: ParcelFileDescriptor) : PageSource {
        private val core = core
        private val doc: PdfDocument = core.newDocument(pfd)
        override val count: Int = core.getPageCount(doc)
        override fun pageSize(i: Int): Pair<Float, Float> {
            core.openPage(doc, i)
            return core.getPageWidthPoint(doc, i).toFloat() to core.getPageHeightPoint(doc, i).toFloat()
        }
        override fun draw(i: Int, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, sc: Float) {
            core.openPage(doc, i)
            core.renderPageBitmap(doc, bmp, i, x, y, w, h, false)
        }
        override fun close() { runCatching { core.closeDocument(doc) }; runCatching { pfd.close() } }
    }

    private class SystemSource(private val r: PdfRenderer) : PageSource {
        override val count: Int = r.pageCount
        override fun pageSize(i: Int): Pair<Float, Float> {
            val p = r.openPage(i)
            try { return p.width.toFloat() to p.height.toFloat() } finally { p.close() }
        }
        override fun draw(i: Int, bmp: Bitmap, x: Int, y: Int, w: Int, h: Int, m: Matrix, sc: Float) {
            val p = r.openPage(i)
            try { p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } finally { p.close() }
        }
        override fun close() { runCatching { r.close() } }
    }

    private val worker = Executors.newSingleThreadExecutor()   // i motori PDF non sono thread-safe
    private var renderer: PageSource? = null
    private var pageCount = 0
    private var page = 0
    private var zoom = 1
    private var panTx = Float.NaN
    private var panTy = Float.NaN
    private var requestId = 0
    private var last: Rendered? = null

    // Cache delle sole pagine vicine, a zoom 1 (accesso solo dal thread UI).
    private val cache = HashMap<Int, Rendered>()

    private var screenW = 0
    private var screenH = 0
    private var fileId = 0
    private var fileListener: ((TdApi.File) -> Unit)? = null
    private var prefKey = ""

    private lateinit var image: ImageView
    private lateinit var status: TextView
    private lateinit var pageLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            text = "Apro il PDF…"
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(16, 6, 16, 6)
            visibility = View.GONE
        }
        root.addView(pageLabel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, 24, 24)
        })
        setContentView(root)

        fileId = intent.getIntExtra(EXTRA_FILE_ID, -1)
        prefKey = intent.getStringExtra(EXTRA_KEY).orEmpty().ifEmpty { "fid_$fileId" }
        if (fileId < 0) { finish(); return }
        runCatching { intent.getStringExtra(EXTRA_KEY)?.takeIf { it.isNotEmpty() }?.let { Settings.markWatched(this, it) } }
        startDownload()
    }

    // ---------------------------------------------------------------- download

    private fun startDownload() {
        val listener: (TdApi.File) -> Unit = { f ->
            if (f.id == fileId) runOnUiThread { onFileUpdate(f) }
        }
        fileListener = listener
        TdClient.addFileListener(listener)   // prima della richiesta, per non perdere il "completato"
        TdClient.downloadFile(fileId) { obj ->
            (obj as? TdApi.File)?.let { f -> runOnUiThread { onFileUpdate(f) } }
        }
    }

    private var opened = false

    private fun onFileUpdate(f: TdApi.File) {
        if (opened || isFinishing) return
        if (f.local.isDownloadingCompleted && f.local.path.isNotEmpty()) {
            opened = true
            fileListener?.let { TdClient.removeFileListener(it) }
            fileListener = null
            openPdf(f.local.path)
        } else {
            val total = if (f.size > 0) f.size else f.expectedSize
            val pct = if (total > 0) (f.local.downloadedSize * 100 / total).toInt() else -1
            status.text = if (pct >= 0) "Scarico il PDF… $pct%" else "Scarico il PDF…"
        }
    }

    // ---------------------------------------------------------------- apertura

    private fun openPdf(path: String) {
        status.text = "Apro il PDF…"
        worker.execute {
            val src = File(path)
            val size = runCatching { src.length() }.getOrDefault(-1L)
            val head = runCatching {
                src.inputStream().use { i -> val b = ByteArray(8); val n = i.read(b); if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else "" }
            }.getOrDefault("")
            if (size <= 0L) {
                runOnUiThread { fail("File scaricato vuoto (0 byte). Riprova.\n$path") }
                return@execute
            }
            if (!head.startsWith("%PDF")) {
                val shown = head.filter { it.code in 32..126 }
                runOnUiThread { fail("Il file non è un PDF valido (inizia con \"$shown\", $size byte)") }
                return@execute
            }
            var lastError = ""
            // 1) pdfium (incluso nell'app); 2) PdfRenderer di sistema, sul file e su una copia.
            for (attempt in 0..2) {
                try {
                    val source: PageSource = when (attempt) {
                        0 -> PdfiumSource(PdfiumCore(applicationContext),
                            ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY))
                        else -> {
                            val f = if (attempt == 1) src else {
                                val copy = File(cacheDir, "view_$fileId.pdf")
                                src.copyTo(copy, overwrite = true)
                                copy
                            }
                            SystemSource(PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)))
                        }
                    }
                    if (source.count <= 0) {
                        lastError = "nessuna pagina trovata ($size byte)"
                        source.close()
                        continue
                    }
                    renderer = source
                    pageCount = source.count
                    val saved = getSharedPreferences("pdf_pos", Context.MODE_PRIVATE).getInt(prefKey, 0)
                    runOnUiThread {
                        page = saved.coerceIn(0, pageCount - 1)
                        status.visibility = View.GONE
                        showPage()
                    }
                    return@execute
                } catch (e: SecurityException) {
                    runOnUiThread { fail("PDF protetto da password: non supportato") }
                    return@execute
                } catch (e: Throwable) {
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "errore"} ($size byte)"
                }
            }
            runOnUiThread { fail("Impossibile leggere il PDF: $lastError") }
        }
    }

    private fun fail(msg: String) {
        status.visibility = View.VISIBLE
        status.text = msg
    }

    // ---------------------------------------------------------------- rendering

    /** Disegna la porzione visibile della pagina [idx]. Va chiamata SOLO dal worker. */
    private fun renderNow(idx: Int, z: Int, wantTx: Float, wantTy: Float): Rendered? {
        val r = renderer ?: return null
        val (pw, ph) = r.pageSize(idx)
        val base = minOf(screenW / pw, screenH / ph)
        val sc = base * z
        val cw = pw * sc
        val ch = ph * sc
        val tx = when {
            cw <= screenW -> (screenW - cw) / 2f
            wantTx.isNaN() -> (screenW - cw) / 2f
            else -> wantTx.coerceIn(screenW - cw, 0f)
        }
        val ty = when {
            ch <= screenH -> (screenH - ch) / 2f
            wantTy.isNaN() -> 0f
            else -> wantTy.coerceIn(screenH - ch, 0f)
        }
        val bmp = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.BLACK)
        canvas.drawRect(tx, ty, tx + cw, ty + ch, Paint().apply { color = Color.WHITE })
        val m = Matrix().apply { postScale(sc, sc); postTranslate(tx, ty) }
        r.draw(idx, bmp, tx.toInt(), ty.toInt(), cw.toInt(), ch.toInt(), m, sc)
        return Rendered(bmp, cw, ch, tx, ty)
    }

    private fun showPage() {
        val idx = page
        val z = zoom
        val myId = ++requestId
        pageLabel.text = "${idx + 1} / $pageCount" + if (z > 1) "  ·  ${z}x" else ""
        pageLabel.visibility = View.VISIBLE
        getSharedPreferences("pdf_pos", Context.MODE_PRIVATE).edit().putInt(prefKey, idx).apply()

        if (z == 1) {
            cache[idx]?.let { show(it); trimAndPrefetch(); return }
        }
        val wantTx = panTx
        val wantTy = panTy
        worker.execute {
            val res = runCatching { renderNow(idx, z, wantTx, wantTy) }.getOrNull()
            runOnUiThread {
                if (res == null) return@runOnUiThread
                if (z == 1) cache[idx] = res
                if (myId == requestId) {
                    show(res)
                    trimAndPrefetch()
                }
            }
        }
    }

    private fun show(r: Rendered) {
        last = r
        panTx = r.tx
        panTy = r.ty
        image.setImageBitmap(r.bmp)
    }

    /** Tiene in memoria solo pagina corrente ± 1 e precarica le vicine mancanti. */
    private fun trimAndPrefetch() {
        cache.keys.filter { it < page - 1 || it > page + 1 }.forEach { cache.remove(it) }
        if (zoom != 1) return
        for (n in intArrayOf(page + 1, page - 1)) {
            if (n < 0 || n >= pageCount || cache.containsKey(n)) continue
            worker.execute {
                val res = runCatching { renderNow(n, 1, Float.NaN, Float.NaN) }.getOrNull()
                if (res != null) runOnUiThread {
                    if (n >= page - 1 && n <= page + 1) cache[n] = res
                }
            }
        }
    }

    // ---------------------------------------------------------------- telecomando

    private fun goPage(delta: Int, atBottom: Boolean = false) {
        val n = page + delta
        if (n < 0 || n >= pageCount) return
        page = n
        panTx = Float.NaN
        panTy = if (atBottom) -Float.MAX_VALUE else Float.NaN
        showPage()
    }

    private fun cycleZoom() {
        zoom = if (zoom >= 3) 1 else zoom + 1
        panTx = Float.NaN
        panTy = Float.NaN
        showPage()
    }

    private fun pan(dx: Float, dy: Float) {
        val r = last ?: return
        panTx = r.tx + dx
        panTy = r.ty + dy
        showPage()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (renderer == null || pageCount == 0) return super.onKeyDown(keyCode, event)
        val r = last
        val step = screenW / 4f
        val zoomed = zoom > 1 && r != null
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { cycleZoom(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { goPage(+1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { goPage(-1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (zoomed) pan(-step, 0f) else goPage(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (zoomed) pan(step, 0f) else goPage(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (zoomed) {
                    val atBottom = r!!.ty <= screenH - r.ch + 1f
                    if (atBottom) goPage(+1) else pan(0f, -screenH / 3f)
                } else goPage(+1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (zoomed) {
                    val atTop = r!!.ty >= -1f
                    if (atTop) goPage(-1, atBottom = true) else pan(0f, screenH / 3f)
                } else goPage(-1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        fileListener?.let { TdClient.removeFileListener(it) }
        fileListener = null
        // Chiude il renderer sul worker (dopo gli eventuali rendering in coda).
        worker.execute { runCatching { renderer?.close() }; renderer = null; runCatching { File(cacheDir, "view_$fileId.pdf").delete() } }
        worker.shutdown()
        cache.clear()
        image.setImageDrawable(null)
    }
}
