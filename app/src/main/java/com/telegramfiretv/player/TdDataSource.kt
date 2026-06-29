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
    private var readCallsSinceKeepAlive: Int = 0

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
            // limit=0 è la convenzione TDLib per "scarica senza limite fino alla fine del
            // file": passare un numero di byte enorme può comportarsi diversamente da 0
            // a seconda della versione/condizioni di rete, e in alcuni casi TDLib si ferma
            // dopo aver soddisfatto quel limite invece di continuare oltre.
            TdClient.downloadFileRange(fileId, readPosition, 0)

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
            // Rinnova periodicamente la richiesta di download (vedi nota nel companion):
            // garantisce che TDLib continui a scaricare in avanti anche se per qualche
            // motivo si era fermato dopo aver soddisfatto una richiesta precedente, e fa
            // sì che il download prosegua anche quando ExoPlayer è in pausa (il buffer
            // continua a riempirsi mentre l'utente non guarda).
            readCallsSinceKeepAlive++
            if (readCallsSinceKeepAlive >= KEEP_ALIVE_EVERY_N_READS) {
                readCallsSinceKeepAlive = 0
                TdClient.downloadFileRange(fileId, readPosition, 0)
            }

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
        val latch = CountDownLatch(1)
        val satisfied = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener: (TdApi.File) -> Unit = { f ->
            if (f.id == fileId) {
                if (isRangeAvailable(f, from, needed)) {
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
                    if (isRangeAvailable(obj, from, needed)) {
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

    /**
     * Indica se nel file sono disponibili in modo contiguo i byte nell'intervallo
     * [from, from+needed). Per lo streaming MKV è essenziale tenere conto dell'OFFSET da cui
     * TDLib sta scaricando: i metadati di seek (Cues/SeekHead) di un MKV stanno spesso in
     * CODA al file, perciò ExoPlayer chiede subito un offset altissimo. In quel caso il solo
     * downloadedSize (totale scaricato dall'inizio) non raggiunge mai quella posizione e lo
     * streaming andava in timeout. TDLib espone invece local.downloadOffset (da dove sta
     * scaricando) e local.downloadedPrefixSize (quanti byte contigui da quell'offset): la
     * disponibilità a una posizione si calcola da questi due.
     */
    private fun isRangeAvailable(f: TdApi.File, from: Long, needed: Long): Boolean {
        if (f.local.isDownloadingCompleted) return true
        val off = f.local.downloadOffset
        val prefix = f.local.downloadedPrefixSize
        // I byte contigui realmente leggibili vanno da off a off+prefix.
        // Servono tutti i byte in [from, from+needed).
        return off <= from && (off + prefix) >= (from + needed)
    }

    companion object {
        /** Ogni quante chiamate a read() rinnoviamo la richiesta di download verso TDLib. */
        private const val KEEP_ALIVE_EVERY_N_READS = 8
    }
}

/** Codici minimi per DataSourceException, per non dipendere da PlaybackException qui. */
private object PlaybackErrorCodes {
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_READ_POSITION_OUT_OF_RANGE = 2008
    const val IO_UNSPECIFIED = 2000
}
