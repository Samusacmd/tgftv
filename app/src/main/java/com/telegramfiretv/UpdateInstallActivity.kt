package com.telegramfiretv

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.widget.Toast

/**
 * Activity invisibile che riceve gli stati dell'installazione avviata da UpdateManager.
 * Si usa un'activity (e non un BroadcastReceiver) perché su diverse versioni di
 * Android/Fire OS l'apertura della finestra di conferma da un receiver viene bloccata,
 * mentre da un'activity funziona sempre.
 *
 * - STATUS_PENDING_USER_ACTION: apre la finestra di conferma di sistema.
 * - STATUS_SUCCESS: la nuova versione è installata -> la riapre subito (questo codice
 *   gira già nella versione appena installata).
 * - Errori: mostra il motivo con un Toast.
 */
class UpdateInstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent) {
        when (val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) runCatching { startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val launch = packageManager.getLeanbackLaunchIntentForPackage(packageName)
                    ?: packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { startActivity(launch) }
                }
            }
            -999 -> { /* avvio senza stato: niente da fare */ }
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "codice $status"
                Toast.makeText(this, "Installazione non riuscita: $msg", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
