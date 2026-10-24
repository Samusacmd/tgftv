package android.support.v4.util

/**
 * Sostituto minimo della classe ArrayMap delle vecchie librerie "support".
 *
 * La libreria PDF (pdfium-android 1.9.0) la cerca con questo nome esatto, ma noi abbiamo
 * escluso le vecchie librerie support (duplicate di androidx e causa dell'errore di build).
 * Una semplice mappa è equivalente per l'uso che ne fa pdfium.
 */
open class ArrayMap<K, V> : java.util.HashMap<K, V>()
