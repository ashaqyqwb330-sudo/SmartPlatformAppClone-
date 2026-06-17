package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * خدمة إمكانية الوصول لمراقبة الحافظة في الخلفية (Android 10+)
 *
 * تقوم بقراءة البادئات البرمجية (@builder, @executor, @treedoc) فور نسخها بأي مكان
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardAccess"
        private const val DEBOUNCE_DELAY_MS = 600L
    }

    private var lastCheckTime = 0L
    private lateinit var clipboardManager: ClipboardManager

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Log.d(TAG, "ClipboardAccessibilityService onCreate")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastCheckTime >= DEBOUNCE_DELAY_MS) {
                lastCheckTime = now
                checkClipboardInService()
            }
        }
    }

    private fun checkClipboardInService() {
        try {
            if (!clipboardManager.hasPrimaryClip()) return
            val clipData = clipboardManager.primaryClip ?: return
            if (clipData.itemCount == 0) return
            val text = clipData.getItemAt(0).text?.toString() ?: ""

            if (text.isNotBlank()) {
                val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
                val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
                val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

                if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:")) {
                    val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
                    if (text != lastProcessed) {
                        Log.d(TAG, "Accessibility detected smart directives. Broadcasting to main service...")
                        val intent = Intent("com.example.ACTION_CLIPBOARD_UPDATED").apply {
                            putExtra("clip_text", text)
                            setPackage(packageName)
                        }
                        sendBroadcast(intent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard in AccessibilityService: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "ClipboardAccessibilityService interrupted")
    }
}
