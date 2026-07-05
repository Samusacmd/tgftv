package com.telegramfiretv

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento dell'app dalla cartella condivisa pCloud.
 *
 * Flusso: elenca i file della cartella pubblica, individua i "FiregramTV-x.y.z.apk",
 * sceglie quello con la versione più alta e la confronta con quella installata.
 * Se è più nuova, avvisa (tramite onFound: chi chiama mostra la finestra di conferma)
 * e, alla conferma, scarica l'APK e lo installa con PackageInstaller. A installazione
 * completata, la UpdateInstallActivity riapre automaticamente l'app nella
 * nuova versione; al riavvio, cleanupOldDownloads elimina l'APK scaricato.
 */
object UpdateManager {

    private const val FOLDER_CODE = "kZKh4i5Zg4ce2VRIClFTo6prs5BlPfr4E8Ry"
    private const val FILE_NAME = "firegramtv-update.apk"
    private val APK_REGEX = Regex("FiregramTV-(\\d+)\\.(\\d+)\\.(\\d+)\\.apk", RegexOption.IGNORE_CASE)

    /** Elimina l'APK di un eventuale aggiornamento precedente (ormai installato). */
    fun cleanupOldDownloads(c: Context) {
        runCatching { File(c.getExternalFilesDir(null), FILE_NAME).delete() }
        runCatching { File(c.cacheDir, FILE_NAME).delete() }
    }

    /**
     * Controlla la cartella remota. Se trova una versione più nuova chiama [onFound] con
     * la versione (es. "1.0.194") e una funzione da invocare per avviare il download e
     * l'installazione. Tutti i callback arrivano da un thread di lavoro: chi li usa deve
     * portarli sul thread UI (runOnUiThread).
     */
    fun checkForUpdate(c: Context, onStatus: (String) -> Unit, onFound: (String, () -> Unit) -> Unit) {
        Thread {
            try {
                onStatus("Update — controllo versione…")
                val meta = fetchJson("https://api.pcloud.com/showpublink?code=$FOLDER_CODE")
                val contents = meta.optJSONObject("metadata")?.optJSONArray("contents")
                    ?: throw Exception(meta.optString("error", "cartella pCloud non leggibile"))

                var bestVer: List<Int>? = null
                var bestFileId = 0L
                for (i in 0 until contents.length()) {
                    val f = contents.getJSONObject(i)
                    if (f.optBoolean("isfolder", false)) continue
                    val m = APK_REGEX.find(f.optString("name")) ?: continue
                    val ver = m.groupValues.drop(1).map { it.toInt() }
                    if (bestVer == null || isNewer(ver, bestVer!!)) {
                        bestVer = ver
                        bestFileId = f.optLong("fileid")
                    }
                }

                if (bestVer == null) {
                    onStatus("Update — nessun APK nella cartella")
                    return@Thread
                }
                val localVer = extractVersion(BuildConfig.VERSION_NAME)
                if (localVer != null && !isNewer(bestVer!!, localVer)) {
                    onStatus("Update — già aggiornato (${BuildConfig.VERSION_NAME})")
                    return@Thread
                }

                val verName = bestVer!!.joinToString(".")
                val fid = bestFileId
                onFound(verName) {
                    Thread {
                        try {
                            downloadAndInstall(c, fid, onStatus)
                        } catch (e: Exception) {
                            onStatus("Update — errore: ${e.message ?: "sconosciuto"}")
                        }
                    }.start()
                }
            } catch (e: Exception) {
                onStatus("Update — errore: ${e.message ?: "sconosciuto"}")
            }
        }.start()
    }

    private fun downloadAndInstall(c: Context, fileId: Long, onStatus: (String) -> Unit) {
        onStatus("Update — preparo il download…")
        val dl = fetchJson("https://api.pcloud.com/getpublinkdownload?code=$FOLDER_CODE&fileid=$fileId")
        val hosts = dl.optJSONArray("hosts")
        val path = dl.optString("path")
        if (hosts == null || hosts.length() == 0 || path.isEmpty()) {
            throw Exception(dl.optString("error", "risposta pCloud non valida"))
        }
        val url = "https://" + hosts.getString(0) + path

        val out = File(c.getExternalFilesDir(null) ?: c.cacheDir, FILE_NAME)
        download(url, out, onStatus)

        onStatus("Update — avvio installazione…")
        installViaPackageInstaller(c, out)
        onStatus("Update — conferma l'installazione")
    }

    /**
     * Installa tramite PackageInstaller: gli stati dell'installazione arrivano alla
     * UpdateInstallActivity (invisibile), che mostra la conferma di sistema e, a
     * installazione completata, riapre subito l'app aggiornata.
     */
    private fun installViaPackageInstaller(c: Context, apk: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(FILE_NAME, 0, apk.length()).use { outStream ->
                apk.inputStream().use { it.copyTo(outStream) }
                session.fsync(outStream)
            }
            val intent = Intent(c, UpdateInstallActivity::class.java)
                .setAction("com.telegramfiretv.UPDATE_STATUS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = if (Build.VERSION.SDK_INT >= 31)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else
                PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getActivity(c, 0, intent, flags)
            session.commit(pending.intentSender)
        }
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

    /** Estrae una versione tipo 1.0.123 da una stringa. */
    private fun extractVersion(s: String): List<Int>? =
        Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(s)?.groupValues?.drop(1)?.map { it.toInt() }

    /** true se [remote] è strettamente più nuova di [local]. */
    private fun isNewer(remote: List<Int>, local: List<Int>): Boolean {
        for (i in 0 until minOf(remote.size, local.size)) {
            if (remote[i] > local[i]) return true
            if (remote[i] < local[i]) return false
        }
        return remote.size > local.size
    }
}
