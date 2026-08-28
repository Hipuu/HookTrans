package io.hooktrans.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.hooktrans.core.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.load(context).enabled) return
        TranslatorService.start(context)
    }
}
