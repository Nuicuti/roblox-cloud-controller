package com.rejoin.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (RejoinForegroundService.isServiceDesired(context)) {
                ContextCompat.startForegroundService(context, Intent(context, RejoinForegroundService::class.java))
            }
        }
    }
}
