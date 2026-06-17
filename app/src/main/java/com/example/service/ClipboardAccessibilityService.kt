package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * خدمة إمكانية الوصول لمراقبة الحافظة في الخلفية (Android 10+)
 *
 * تقوم بقراءة الحافظة دورياً كل ثانية للتعرف اللحظي والفوري على نصوص الحفظ في الخلفية.
 * تم دمج منطق المعالجة والأرشفة والخطوط المتوازية بالكامل لضمان عملها كرفيق حقيقي دون انقطاع.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ClipboardAccess"
        private const val CHANNEL_ID = "SmartAccessibilityChannel"
        private const val NOTIFICATION_ID = 89
    }

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var database: AppDatabase
    private var wakeLock: PowerManager.WakeLock? = null
    
    private var lastKnownClipText: String = ""
    private var isPollingActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val isPaused: Boolean
        get() = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkClipboardInService()
            handler.postDelayed(this, 1000L)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "clipboard_is_paused") {
            updatePollingState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        database = AppDatabase.getDatabase(this)
        
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartPlatform::AccessibilityWakeLock")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing wake lock: ${e.message}")
        }
        
        Log.d(TAG, "ClipboardAccessibilityService onCreate")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "ClipboardAccessibilityService onServiceConnected")
        
        // Setup Foreground Service to prevent OS termination
        try {
            createNotificationChannel()
            val paused = isPaused
            val title = if (paused) "مساعد الحافظة: متوقف مؤقتاً" else "مساعد الحافظة: يعمل بنشاط"
            val text = if (paused) "المراقبة متوقفة مؤقتاً." else "مساعد الحافظة يقوم بالفحص المباشر والفوري بالخلفية."
            startForeground(NOTIFICATION_ID, buildNotification(title, text))
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground in AccessibilityService: ${e.message}")
        }

        getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        updatePollingState()
    }

    private fun updatePollingState() {
        val paused = isPaused
        if (paused) {
            if (isPollingActive) {
                handler.removeCallbacks(checkRunnable)
                isPollingActive = false
                Log.d(TAG, "Polling stopped because monitor is paused.")
            }
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    Log.d(TAG, "WakeLock released.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release wake lock: ${e.message}")
            }
            
            updateNotification("مساعد الحافظة: متوقف مؤقتاً", "المراقبة متوقفة مؤقتاً.")
        } else {
            if (!isPollingActive) {
                try {
                    if (clipboardManager.hasPrimaryClip()) {
                        val clip = clipboardManager.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            lastKnownClipText = clip.getItemAt(0).text?.toString() ?: ""
                        }
                    }
                } catch (e: Exception) {}
                
                handler.post(checkRunnable)
                isPollingActive = true
                Log.d(TAG, "Polling started because monitor is active.")
            }
            try {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire()
                    Log.d(TAG, "WakeLock acquired.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
            }
            
            updateNotification("مساعد الحافظة: يعمل بنشاط", "مساعد الحافظة يقوم بالفحص المباشر والفوري بالخلفية.")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // نعتمد الآن بالكامل على الفحص الدوري البسيط للتجاوب الفوري دون انتظار أحداث النظام
    }

    private fun checkClipboardInService() {
        try {
            if (!clipboardManager.hasPrimaryClip()) return
            val clipData = clipboardManager.primaryClip ?: return
            if (clipData.itemCount == 0) return
            val text = clipData.getItemAt(0).text?.toString() ?: ""

            if (text.isNotBlank() && text != lastKnownClipText) {
                lastKnownClipText = text
                Log.d(TAG, "Accessibility detected clipboard change. Processing...")
                onNewClipboardTextDetected(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking clipboard in AccessibilityService: ${e.message}")
        }
    }

    private fun onNewClipboardTextDetected(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        if (isPaused) return
        val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
        if (!isAutoProcess) return

        if (text.isBlank()) return

        val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
        val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
        val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
        if (text == lastProcessed) return

        if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:")) {
            processCopiedText(text)
        } else {
            // No saved directives found. Only show Toast if text is long enough (e.g. >= 8 chars) to avoid spamming tiny copy-paste
            if (text.trim().length >= 8) {
                sharedPrefs.edit().putString("last_auto_processed_text", text).apply()
                serviceScope.launch(Dispatchers.Main) {
                    try {
                        Toast.makeText(applicationContext, "⚠️ نص منسوخ لا يحتوي على توجيهات حفظ؛ لم يتم الحفظ تلقائياً.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {}
                }
                logSystemEvent("تجاوز النسخ (لا توجد توجيهات)", "تم تجاوز الحفظ لعدم العثور على بادئات نشطة في النص المنسوخ.")
                updateNotification("مراقب الحافظة يعمل بنجاح", "جاهز - تم تجاوز حفظ آخر نص منسوخ لعدم وجود توجيهات صالحة.")
            }
        }
    }

    private fun processCopiedText(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
        if (text == lastProcessed && text.isNotBlank()) {
            return
        }
        // Save to preferences immediately on first ENTRY to prevent any concurrency loops
        sharedPrefs.edit().putString("last_auto_processed_text", text).apply()

        serviceScope.launch {
            logSystemEvent("توجيه مكتشف", "تم التقاط محتويات الحافظة. بدء المعالجة الذكية...")
            updateNotification("معالجة التوجيهات...", "يرجى الانتظار، يجري معالجة الملفات والتعليمات.")

            try {
                // Dynamic engine configuration loaded on each run to ensure updated prefixes are immediately parsed!
                val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
                val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
                val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

                val settings = mapOf<String, Any>(
                    "absolute_path_handling" to "relative",
                    "base_dir" to getBaseDir().absolutePath,
                    "directive_prefixes" to listOf(pBuilder),
                    "executor_prefixes" to listOf(pExecutor),
                    "treedoc_prefixes" to listOf(pTreedoc)
                )
                val engine = BuilderEngine(this@ClipboardAccessibilityService, settings)

                val results = engine.processText(text)
                if (results.isEmpty()) {
                    logSystemEvent("معالجة فارغة", "لم يتم العثور على توجيهات صالحة أو مطابقة في النص الملتقط.")
                    updateNotification("مراقب الحافظة يعمل بنجاح", "جاهز لالتقاط التوجيهات الذكية...")
                    return@launch
                }

                var buildersCount = 0
                var executorsCount = 0
                var treedocCount = 0

                for (res in results) {
                    when (res.type) {
                        "builder" -> {
                            buildersCount++
                            // Write database entry
                            val path = res.data?.get("path") ?: "unknown"
                            val size = res.data?.get("size")?.toLongOrNull() ?: 0L
                            val mode = res.data?.get("mode") ?: "w"
                            val fullPath = res.data?.get("full_path") ?: ""
                            
                            database.dao().insertFile(
                                FileEntity(
                                    path = path,
                                    fullPath = fullPath,
                                    size = size,
                                    mode = mode
                                )
                            )
                            database.dao().insertLog(
                                LogEntity(
                                    type = "builder",
                                    message = "تم إنشاء الملف: $path",
                                    details = res.message
                                )
                            )
                        }
                        "executor" -> {
                            executorsCount++
                            database.dao().insertLog(
                                LogEntity(
                                    type = "executor",
                                    message = "تنفيذ أمر المنفذ",
                                    details = res.message
                                )
                            )
                        }
                        "treedoc" -> {
                            treedocCount++
                            database.dao().insertLog(
                                LogEntity(
                                    type = "treedoc",
                                    message = "توليد تقرير TreeDoc الشجري",
                                    details = res.message
                                )
                            )
                        }
                    }
                }

                val summary = "تم بنجاح: $buildersCount ملفات، $executorsCount أوامر، $treedocCount تقارير شجرية."
                logSystemEvent("نجاح المعالجة الكاملة", summary)
                updateNotification("نجحت المعالجة الذكية", summary)

                // Clear clipboard securely if selected
                val clearClip = sharedPrefs.getBoolean("clear_clip_after_save", false)
                if (clearClip) {
                    withContext(Dispatchers.Main) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                clipboardManager.clearPrimaryClip()
                            } else {
                                clipboardManager.setPrimaryClip(ClipData.newPlainText("", ""))
                            }
                            Toast.makeText(applicationContext, "🧹 تم مسح وتفريغ الحافظة تلقائياً بنجاح لمنع التكرار وحماية الخصوصية.", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing clipboard: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                logSystemEvent("خطأ في المعالجة", "فشل المحرك في معالجة النص: ${e.message}")
                updateNotification("فشلت المعالجة الذكية", "تفاصيل: ${e.message}")
            }
        }
    }

    private fun logSystemEvent(title: String, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            database.dao().insertLog(
                LogEntity(
                    type = "clipboard_service",
                    message = title,
                    details = message
                )
            )
        }
    }

    private fun getBaseDir(): File {
        val path = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getString("base_dir_path", null)
        if (!path.isNullOrBlank()) {
            return File(path).also { it.mkdirs() }
        }
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(filesDir, "SmartPlatform")
        }.also { it.mkdirs() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "مساعد أتمتة الحافظة"
            val descriptionText = "قناة إشعارات مساعد الحافظة لضمان عمل الخدمة بالخلفية"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 10, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val colorGold = 0xFFD4AF37.toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(colorGold)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "ClipboardAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
        handler.removeCallbacks(checkRunnable)
        isPollingActive = false
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {}
        serviceScope.cancel()
        Log.d(TAG, "ClipboardAccessibilityService onDestroy")
    }
}
