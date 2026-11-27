package com.rejoin.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isBootOrUpdate = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!isBootOrUpdate) return

        val hasConfig = File(context.filesDir, "agent/firebase_config.json").exists()
        val shouldStart = RejoinForegroundService.isServiceDesired(context) ||
            (hasConfig && !RejoinForegroundService.wasExplicitlyStopped(context))
        if (shouldStart) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, RejoinForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
