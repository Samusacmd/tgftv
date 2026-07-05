package com.telegramfiretv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Riceve gli stati dell'installazione avviata da UpdateManager.
 *
 * - STATUS_PENDING_USER_ACTION: il sistema chiede la conferma dell'utente -> apriamo la
 *   finestra di conferma di Android.
 * - STATUS_SUCCESS: la nuova versione è installata -> la riapriamo subito, senza passare
 *   dalla schermata finale dell'installer (quella con il solo tasto Esci). Questo codice
 *   viene eseguito già nella versione appena installata.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val launch = context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
                    ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    runCatching { context.startActivity(launch) }
                }
            }
        }
    }
}
