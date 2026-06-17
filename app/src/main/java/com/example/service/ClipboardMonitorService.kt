package com.example.service

import android.app.*
import android.content.*
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * خدمة الحافظة الذكية (Foreground Service)
 *
 * تراقب التغييرات في الحافظة وتحلل النصوص باستخدام BuilderEngine
 */
class ClipboardMonitorService : Service() {

    companion object {
        const val TAG = "ClipboardService"
        const val CHANNEL_ID = "SmartPlatformChannel"
        const val NOTIFICATION_ID = 88

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TRIGGER_SCAN = "ACTION_TRIGGER_SCAN"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
    }

    private var isPaused: Boolean
        get() = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)
        set(value) {
            getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit().putBoolean("clipboard_is_paused", value).apply()
        }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var builderEngine: BuilderEngine
    private lateinit var database: AppDatabase

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        checkClipboard()
    }

    override fun onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate")
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        database = AppDatabase.getDatabase(this)
        
        // Settings configuration matching engine expectations
        val settings = mapOf<String, Any>(
            "absolute_path_handling" to "relative",
            "base_dir" to getBaseDir().absolutePath,
            "directive_prefixes" to listOf("@builder"),
            "executor_prefixes" to listOf("@executor"),
            "treedoc_prefixes" to listOf("@treedoc")
        )
        builderEngine = BuilderEngine(this, settings)
        
        // Register listener
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        createNotificationChannel()
        
        logSystemEvent("الحصول على المراقبة", "تبدأ الخدمة الخلفية الآن لمراقبة الحافظة وتأمين التوجيهات.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            logSystemEvent("إيقاف المراقبة", "تم إيقاف خدمة المراقبة يدوياً بطلب المستخدم.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_PAUSE) {
            isPaused = true
            logSystemEvent("إيقاف مؤقت", "تم إيضاح حالة الإيقاف المؤقت لعملية مراقبة الخلفية.")
        } else if (action == ACTION_RESUME) {
            isPaused = false
            logSystemEvent("استئناف المراقبة", "تم استئناف مراقبة الخلفية للحافظة من جديد.")
        }

        val title = if (isPaused) "مراقب الحافظة: متوقف مؤقتاً" else "مراقب الحافظة يعمل بنجاح"
        val text = if (isPaused) "المراقبة متوقفة مؤقتاً. اضغط لاستئناف المعالجة." else "جاهز لالتقاط التوجيهات الذكية @builder..."
        val notification = buildNotification(title, text)
        
        // Support all foreground service type declarations for Android 14+
        startForeground(NOTIFICATION_ID, notification)

        if (action == ACTION_TRIGGER_SCAN) {
            checkClipboard()
        }

        return START_STICKY
    }

    private fun checkClipboard() {
        if (isPaused) return
        if (!clipboardManager.hasPrimaryClip()) return
        val clipData = clipboardManager.primaryClip ?: return
        if (clipData.itemCount == 0) return
        val text = clipData.getItemAt(0).text?.toString() ?: return

        if (text.isNotBlank() && (text.contains("@builder:") || text.contains("@executor:") || text.contains("@treedoc:"))) {
            processCopiedText(text)
        }
    }

    private fun processCopiedText(text: String) {
        serviceScope.launch {
            logSystemEvent("توجيه مكتشف", "تم التقاط محتويات الحافظة. بدء المعالجة الذكية...")
            updateNotification("معالجة التوجيهات...", "يرجى الانتظار، يجري معالجة الملفات والتعليمات.")

            try {
                val results = builderEngine.processText(text)
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
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(filesDir, "SmartPlatform")
        }.also { it.mkdirs() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "مراقب المنصة الذكية"
            val descriptionText = "قناة إشعارات مراقب الحافظة الذكية"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pendingPauseResumeIntent = PendingIntent.getService(
            this, 2, pauseResumeIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeLabel = if (isPaused) "استئناف المراقبة" else "إيقاف مؤقت"
        val pauseResumeIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val colorGold = 0xFFD4AF37.toInt()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setColor(colorGold)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(pauseResumeIcon, pauseResumeLabel, pendingPauseResumeIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف المراقبة", pendingStopIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        serviceScope.cancel()
        Log.d(TAG, "Service onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
