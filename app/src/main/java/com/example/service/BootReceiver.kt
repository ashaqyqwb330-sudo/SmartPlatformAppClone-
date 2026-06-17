package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * مستشعر بدء التشغيل للهاتف (Boot Completed Receiver)
 *
 * يقوم بتشغيل خدمة المراقبة تلقائياً عند إقلاع نظام الأندرويد
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val sharedPrefs = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
            val autoStart = sharedPrefs.getBoolean("auto_start_on_boot", true)
            if (autoStart) {
                val serviceIntent = Intent(context, ClipboardMonitorService::class.java).apply {
                    action = ClipboardMonitorService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
