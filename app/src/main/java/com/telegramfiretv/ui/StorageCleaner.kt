package com.telegramfiretv.ui

import android.content.Context
import com.telegramfiretv.tdlib.TdClient
import java.io.File

/**
 * Pulizia dello spazio occupato sul dispositivo.
 *
 * 1) PDF: alla chiusura del visualizzatore il file NON si cancella subito (se si esce per
 *    sbaglio e si torna nella chat, il PDF si riapre senza riscaricarlo). Il file viene solo
 *    "segnato"; la cancellazione avviene quando si arriva alla home dell'app ([flushPdfs]).
 * 2) "Svuota cache": [wipeLocal] elimina file temporanei, APK di aggiornamento e ciò che
 *    resta dei media scaricati (anche parziali) nelle cartelle di TDLib.
 */
object StorageCleaner {

    private val pendingPdfs = LinkedHashSet<Int>()

    /** Il PDF è stato chiuso: sarà cancellato al ritorno alla home. */
    @Synchronized fun markPdfForDelete(fileId: Int) { pendingPdfs.add(fileId) }

    /** Il PDF è stato riaperto prima di tornare alla home: non va più cancellato. */
    @Synchronized fun keepPdf(fileId: Int) { pendingPdfs.remove(fileId) }

    /** Da chiamare quando si arriva alla home: cancella i PDF segnati (anche se scaricati a metà). */
    fun flushPdfs() {
        val ids = synchronized(this) { val c = pendingPdfs.toList(); pendingPdfs.clear(); c }
        for (id in ids) {
            TdClient.cancelDownload(id)
            TdClient.deleteFile(id)
        }
    }

    @Synchronized fun forgetPendingPdfs() { pendingPdfs.clear() }

    /**
     * Elimina dal disco, senza toccare database e login:
     *  - cache e cartella file esterna dell'app (APK di aggiornamento, temporanei);
     *  - nelle cartelle di TDLib: temp (download parziali), documents, videos, music, voice,
     *    video_notes, animations. Foto, avatar e anteprime restano (sono piccoli).
     * Ritorna i byte liberati. Da chiamare su un thread di lavoro.
     */
    fun wipeLocal(c: Context): Long {
        forgetPendingPdfs()
        var freed = 0L
        freed += deleteContents(c.cacheDir)
        c.externalCacheDir?.let { freed += deleteContents(it) }
        c.getExternalFilesDir(null)?.let { freed += deleteContents(it) }
        val td = File(c.filesDir, "tdlib")
        for (name in listOf("temp", "documents", "videos", "music", "voice", "video_notes", "animations")) {
            freed += deleteContents(File(td, name))
        }
        return freed
    }

    /** Cancella tutto ciò che sta dentro [dir] (la cartella resta). Ritorna i byte eliminati. */
    private fun deleteContents(dir: File): Long {
        var freed = 0L
        val items = dir.listFiles() ?: return 0L
        for (f in items) {
            if (f.isDirectory) {
                freed += deleteContents(f)
                runCatching { f.delete() }
            } else {
                val len = runCatching { f.length() }.getOrDefault(0L)
                if (runCatching { f.delete() }.getOrDefault(false)) freed += len
            }
        }
        return freed
    }
}
