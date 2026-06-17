package com.example.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.LogEntity
import kotlinx.coroutines.*

/**
 * خدمة الحافظة المراقبة (Foreground Service)
 *
 * تبسيط الخدمة لتصبح واجهة للتحكم بالإيقاف المؤقت/الاستئناف والربط ببلاطات الإعدادات السريعة
 */
class ClipboardMonitorService : Service() {

    companion object {
        const val TAG = "ClipboardMonitorService"
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
    private lateinit var database: AppDatabase

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ClipboardMonitorService onCreate")
        database = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.d(TAG, "onStartCommand Action: $action")
        
        if (action == ACTION_STOP) {
            logSystemEvent("إيقاف مراقب الحالة", "تم إيقاف الواجهة الخلفية والإيقاف المؤقت للمراقب المساعد.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            val title = "مراقب الحافظة: معلق"
            val text = "على أندرويد 10+ يرجى استخدام لوحة مفاتيح الأتمتة الذكية للخصوصية والأمان."
            startForeground(NOTIFICATION_ID, buildNotification(title, text))
            return START_STICKY
        }

        if (action == ACTION_PAUSE) {
            isPaused = true
            logSystemEvent("إيقاف مؤقت", "تم تفعيل حالة الإيقاف المؤقت للمراقبة.")
        } else if (action == ACTION_RESUME) {
            isPaused = false
            logSystemEvent("استئناف المراقبة", "تم استئناف المكونات ومراقبة الحافظة بنشاط.")
        }

        val title = if (isPaused) "مراقب الحافظة: متوقف مؤقتاً" else "مراقب الحافظة يعمل بنجاح"
        val text = if (isPaused) "المراقبة متوقفة مؤقتاً. اضغط لاستئناف المعالجة." else "جاهز لالتقاط التوجيهات من الحافظة بالخلفية..."
        val notification = buildNotification(title, text)
        
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "ClipboardMonitorService onDestroy")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
