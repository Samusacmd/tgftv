package com.telegramfiretv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.telegramfiretv.tdlib.TdClient
import org.drinkless.tdlib.TdApi
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DataSource Media3 che legge un file TDLib mentre viene scaricato, senza attendere
 * il completamento. Chiede a TDLib di scaricare a partire dall'offset richiesto da
 * ExoPlayer (priorità "vicino alla testa di lettura") e legge dal file locale parziale
 * non appena i byte richiesti sono presenti.
 *
 * Pensato per essere robusto: se qualcosa non torna (file non trovato, timeout, errore
 * TDLib), lancia IOException così ExoPlayer può fallire in modo pulito e PlayerActivity
 * può ricadere sul download classico.
 */
@UnstableApi
class TdDataSource(
    private val fileId: Int,
    private val totalSize: Long,
    private val initialBufferBytes: Long = 256L * 1024L
) : BaseDataSource(true) {

    private var uri: Uri = Uri.EMPTY
    private var raf: RandomAccessFile? = null
    private var localPath: String = ""
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L

    class Factory(
        private val fileId: Int,
        private val totalSize: Long,
        private val initialBufferBytes: Long = 256L * 1024L
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(fileId, totalSize, initialBufferBytes)
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != androidx.media3.common.C.LENGTH_UNSET.toLong())
            dataSpec.length else totalSize - dataSpec.position

        try {
            // Avvia subito il download con priorità sull'offset richiesto: questo è anche ciò
            // che fa TDLib creare il file fisico locale (GetFile da solo non lo crea).
            TdClient.downloadFileRange(fileId, readPosition, bytesRemaining.coerceAtLeast(0))

            // Aspetta che TDLib confermi un path locale (il file viene creato all'avvio del download).
            val path = waitForLocalPath(timeoutMs = 10_000)
                ?: throw DataSourceException(PlaybackErrorCodes.IO_FILE_NOT_FOUND)
            localPath = path
            raf = RandomAccessFile(localPath, "r")

            // Attende che almeno i primi byte richiesti siano disponibili prima di restituire.
            waitForBytesAvailable(readPosition, minOf(bytesRemaining, initialBufferBytes), timeoutMs = 15_000)
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            // Qualsiasi altro errore imprevisto: lo trasformiamo in DataSourceException così
            // ExoPlayer lo gestisce tramite onPlayerError invece di farlo propagare grezzo
            // (che altrimenti potrebbe far crashare l'intera Activity).
            throw DataSourceException(PlaybackErrorCodes.IO_UNSPECIFIED)
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return -1 // C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        try {
            // Attende che i byte richiesti siano scaricati. Timeout ampio: TDLib può avere
            // rallentamenti temporanei di rete; un timeout breve causava fallback ingiustificati
            // al download classico, con perdita della posizione di riproduzione.
            if (!waitForBytesAvailable(readPosition, toRead.toLong(), timeoutMs = 60_000)) {
                throw DataSourceException(PlaybackErrorCodes.IO_READ_POSITION_OUT_OF_RANGE)
            }

            val file = raf ?: throw DataSourceException(PlaybackErrorCodes.IO_UNSPECIFIED)
            file.seek(readPosition)
            val read = file.read(buffer, offset, toRead)
            if (read == -1) return -1

            readPosition += read
            bytesRemaining -= read
            bytesTransferred(read)
            return read
        } catch (e: DataSourceException) {
            throw e
        } catch (e: Exception) {
            // Stesso principio di open(): non lasciar mai scappare un'eccezione grezza,
            // altrimenti rischia di far crashare l'intera Activity invece che il solo player.
            throw DataSourceException(PlaybackErrorCodes.IO_UNSPECIFIED)
        }
    }

    override fun getUri(): Uri = uri

    override fun close() {
        try {
            raf?.close()
        } catch (e: Exception) {
            // ignorato in chiusura
        } finally {
            raf = null
            transferEnded()
        }
    }

    /** Aspetta che TDLib confermi il path locale del file, una volta che il download è stato avviato. */
    private fun waitForLocalPath(timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val resultPath = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val listener: (TdApi.File) -> Unit = { f ->
            if (f.id == fileId && f.local.path.isNotEmpty()) {
                resultPath.set(f.local.path)
                latch.countDown()
            }
        }
        TdClient.addFileListener(listener)
        try {
            // Controllo immediato, nel caso il path sia già noto.
            TdClient.getFile(fileId) { obj ->
                if (obj is TdApi.File && obj.local.path.isNotEmpty()) {
                    resultPath.set(obj.local.path)
                    latch.countDown()
                }
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } finally {
            TdClient.removeFileListener(listener)
        }
        return resultPath.get()
    }

    /**
     * Aspetta (con polling leggero su UpdateFile) che almeno [needed] byte siano scaricati
     * in sequenza a partire da [from]. Ritorna false se va in timeout.
     */
    private fun waitForBytesAvailable(from: Long, needed: Long, timeoutMs: Long): Boolean {
        if (needed <= 0) return true
        val target = from + needed
        val latch = CountDownLatch(1)
        val satisfied = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener: (TdApi.File) -> Unit = { f ->
            if (f.id == fileId) {
                val downloadedPrefix = downloadedPrefixEnd(f)
                if (downloadedPrefix >= target || f.local.isDownloadingCompleted) {
                    satisfied.set(true)
                    latch.countDown()
                }
            }
        }
        TdClient.addFileListener(listener)
        try {
            // Controllo immediato: magari è già disponibile.
            TdClient.getFile(fileId) { obj ->
                if (obj is TdApi.File) {
                    val downloadedPrefix = downloadedPrefixEnd(obj)
                    if (downloadedPrefix >= target || obj.local.isDownloadingCompleted) {
                        satisfied.set(true)
                        latch.countDown()
                    }
                }
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } finally {
            TdClient.removeFileListener(listener)
        }
        return satisfied.get()
    }

    /** Stima la fine del prefisso scaricato in sequenza, leggibile a partire da readPosition. */
    private fun downloadedPrefixEnd(f: TdApi.File): Long {
        val local = f.local
        if (local.isDownloadingCompleted) return totalSize.coerceAtLeast(local.downloadedSize)
        // Va usato l'intervallo CONTIGUO realmente disponibile, che parte da downloadOffset
        // (l'offset richiesto a TDLib), non downloadedSize: quest'ultimo è il totale dei byte
        // scaricati, non per forza contigui. Dopo un seek poteva risultare >= target grazie a
        // byte scaricati prima, mentre quelli alla posizione di lettura non c'erano ancora:
        // si finiva per leggere zeri/garbage dal file pre-allocato.
        return local.downloadOffset + local.downloadedPrefixSize
    }

}

/** Codici minimi per DataSourceException, per non dipendere da PlaybackException qui. */
private object PlaybackErrorCodes {
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_READ_POSITION_OUT_OF_RANGE = 2008
    const val IO_UNSPECIFIED = 2000
}
