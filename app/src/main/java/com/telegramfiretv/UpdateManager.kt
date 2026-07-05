package com.telegramfiretv

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento dell'app dal link pubblico pCloud.
 *
 * Flusso: legge i metadati del link (nome del file remoto), ne estrae la versione
 * (es. "FiregramTV-1.0.123.apk" -> 1.0.123) e la confronta con quella installata.
 * Se quella remota è più nuova, scarica l'APK nella cartella privata dell'app e
 * avvia l'installer di sistema. Il file scaricato viene eliminato al successivo
 * avvio dell'app (cleanupOldDownloads), cioè dopo che l'aggiornamento è avvenuto.
 *
 * NOTA: perché il confronto versioni funzioni, l'APK caricato su pCloud deve avere
 * la versione nel nome (formato x.y.z, es. FiregramTV-1.0.123.apk). Se il nome non
 * contiene una versione riconoscibile, il download parte comunque e ci pensa
 * l'installer di Android a rifiutare versioni uguali o più vecchie.
 */
object UpdateManager {

    private const val PUBLINK_CODE = "XZ4jLi5ZyVguDRPEmyRC6wujIKj5wj3g8DJV"
    private const val FILE_NAME = "firegramtv-update.apk"

    /** Elimina eventuali APK di aggiornamento rimasti da un update precedente. */
    fun cleanupOldDownloads(c: Context) {
        runCatching { File(c.getExternalFilesDir(null), FILE_NAME).delete() }
        runCatching { File(c.cacheDir, FILE_NAME).delete() }
    }

    /**
     * Controlla la versione remota e, se più nuova, scarica e avvia l'installazione.
     * [onStatus] riceve i messaggi di avanzamento (chiamato da un thread di lavoro:
     * chi lo usa deve portarlo sul thread UI, es. con runOnUiThread).
     */
    fun checkAndInstall(c: Context, onStatus: (String) -> Unit) {
        Thread {
            try {
                onStatus("Update — controllo versione…")
                val meta = fetchJson("https://api.pcloud.com/showpublink?code=$PUBLINK_CODE")
                val remoteName = meta.optJSONObject("metadata")?.optString("name") ?: ""
                val remoteVer = extractVersion(remoteName)
                val localVer = extractVersion(BuildConfig.VERSION_NAME)
                if (remoteVer != null && localVer != null && !isNewer(remoteVer, localVer)) {
                    onStatus("Update — già aggiornato (${BuildConfig.VERSION_NAME})")
                    return@Thread
                }

                onStatus("Update — preparo il download…")
                val dl = fetchJson("https://api.pcloud.com/getpublinkdownload?code=$PUBLINK_CODE")
                val hosts = dl.optJSONArray("hosts")
                val path = dl.optString("path")
                if (hosts == null || hosts.length() == 0 || path.isEmpty()) {
                    throw Exception(dl.optString("error", "risposta pCloud non valida"))
                }
                val url = "https://" + hosts.getString(0) + path

                val out = File(c.getExternalFilesDir(null) ?: c.cacheDir, FILE_NAME)
                download(url, out, onStatus)

                onStatus("Update — avvio installazione…")
                install(c, out)
                onStatus("Update — conferma l'installazione")
            } catch (e: Exception) {
                onStatus("Update — errore: ${e.message ?: "sconosciuto"}")
            }
        }.start()
    }

    private fun fetchJson(u: String): JSONObject {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun download(u: String, out: File, onStatus: (String) -> Unit) {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var readTot = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        readTot += n
                        if (total > 0) {
                            val pct = (readTot * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onStatus("Update — scarico… $pct%")
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        c.startActivity(i)
    }

    /** Estrae una versione tipo 1.0.123 da una stringa (nome file o versionName). */
    private fun extractVersion(s: String): List<Int>? =
        Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(s)?.groupValues?.drop(1)?.map { it.toInt() }

    /** true se [remote] è strettamente più nuova di [local] (confronto segmento per segmento). */
    private fun isNewer(remote: List<Int>, local: List<Int>): Boolean {
        for (i in 0 until minOf(remote.size, local.size)) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return remote.size > local.size
    }
}
