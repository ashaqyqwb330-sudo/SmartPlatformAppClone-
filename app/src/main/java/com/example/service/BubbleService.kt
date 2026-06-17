package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import com.example.engine.ProcessResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * الفقاعة العائمة الذكية (Bubble Overlay Service) - نسخة الـ View التقليدية بالكامل
 * لضمان أقصى درجات الاستقرار والثبات وتجنب أي انهيارات.
 */
class BubbleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var floatingView: FrameLayout? = null
    private lateinit var database: AppDatabase

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownClipText = ""
    private var isPollingActive = false

    private lateinit var filesCountTxt: TextView
    private lateinit var lastActionTxt: TextView
    private lateinit var statusCircle: View
    private lateinit var statusTxt: TextView
    private lateinit var toggleStatusBtn: Button

    private val isPaused: Boolean
        get() = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkClipboardInBubble()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        savedStateRegistryController.performRestore(null)
        database = AppDatabase.getDatabase(this)
        
        startClipboardPolling()
        Log.d("BubbleService", "BubbleService created and clipboard polling initiated.")
    }

    private fun startClipboardPolling() {
        if (!isPollingActive) {
            handler.post(checkRunnable)
            isPollingActive = true
        }
    }

    private fun checkClipboardInBubble() {
        if (isPaused) return
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip ?: return
                if (clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank() && text != lastKnownClipText) {
                        lastKnownClipText = text
                        Log.d("BubbleService", "Detected clipboard update via Bubble Service Overlay.")
                        onNewClipboardTextDetected(text)
                    }
                }
            }
        } catch (e: Exception) {
            // Safe silent catch
        }
    }

    private fun onNewClipboardTextDetected(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
        if (!isAutoProcess) return

        val textHash = text.trim().hashCode().toString()
        val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
        if (textHash == lastProcessedHash) return

        val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
        val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
        val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

        if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:")) {
            processClipboardContent(text)
        }
    }

    private fun processClipboardContent(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val textHash = text.trim().hashCode().toString()
        val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
        if (textHash == lastProcessedHash && text.isNotBlank()) return

        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
        if (text == lastProcessed && text.isNotBlank()) return

        sharedPrefs.edit().putString("last_processed_text_hash", textHash).apply()
        sharedPrefs.edit().putString("last_auto_processed_text", text).apply()

        serviceScope.launch {
            try {
                val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
                val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
                val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

                val settings = mapOf(
                    "absolute_path_handling" to "relative",
                    "base_dir" to getBaseDir().absolutePath,
                    "directive_prefixes" to listOf(pBuilder),
                    "executor_prefixes" to listOf(pExecutor),
                    "treedoc_prefixes" to listOf(pTreedoc)
                )
                val engine = BuilderEngine(applicationContext, settings)
                val results = engine.processText(text)

                if (results.isNotEmpty()) {
                    var buildersCount = 0
                    for (res in results) {
                        if (res.type == "builder") {
                            buildersCount++
                            val path = res.data?.get("path") ?: "unknown"
                            val size = res.data?.get("size")?.toLongOrNull() ?: 0L
                            val mode = res.data?.get("mode") ?: "w"
                            val fullPath = res.data?.get("full_path") ?: ""

                            database.dao().insertFile(
                                FileEntity(path = path, fullPath = fullPath, size = size, mode = mode)
                            )
                            database.dao().insertLog(
                                LogEntity(type = "builder", message = "فقاعة: تم إنشاء $path", details = res.message)
                            )
                        } else {
                            database.dao().insertLog(
                                LogEntity(type = res.type, message = "فقاعة: إجراء ${res.type}", details = res.message)
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(applicationContext, "✅ فقاعة الذكاء: تم حفظ ومعالجة $buildersCount ملفات!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("BubbleService", "Error processing text: ${e.message}")
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            try {
                removeFloatingView()
                stopSelf()
            } catch (e: Exception) {
                Log.e("BubbleService", "Error basic stop: ${e.message}")
            }
            return START_NOT_STICKY
        }

        if (floatingView != null) return START_STICKY

        setupFloatingView()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        return START_STICKY
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createCircleDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun createRoundedDrawable(colorHex: String, radiusDp: Float, strokeColorHex: String? = null, strokeWidthDp: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(colorHex))
            cornerRadius = dpToPx(radiusDp.toInt()).toFloat()
            if (strokeColorHex != null && strokeWidthDp > 0) {
                setStroke(dpToPx(strokeWidthDp), Color.parseColor(strokeColorHex))
            }
        }
    }

    private fun updateStatusUI() {
        val paused = isPaused
        if (paused) {
            statusCircle.background = createCircleDrawable("#ef4444")
            statusTxt.text = "متوقف"
            toggleStatusBtn.text = "استئناف"
        } else {
            statusCircle.background = createCircleDrawable("#10b981")
            statusTxt.text = "نشط"
            toggleStatusBtn.text = "إيقاف"
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupFloatingView() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 120
                y = 200
            }

            // Root view container
            val rootLayout = FrameLayout(this)

            // 1. COLLAPSED DOT VIEW
            val collapsedLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = createCircleDrawable("#1e293b").apply {
                    setStroke(dpToPx(2), Color.parseColor("#ffd700"))
                }
            }
            val logoTxt = TextView(this).apply {
                text = "🤖"
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
            }
            collapsedLayout.addView(logoTxt)

            val collapsedDotParams = FrameLayout.LayoutParams(dpToPx(56), dpToPx(56)).apply {
                gravity = Gravity.CENTER
            }
            rootLayout.addView(collapsedLayout, collapsedDotParams)

            // 2. EXPANDED CARD VIEW
            val expandedCardLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1e293b", 16f, "#ffd700", 2)
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
                visibility = View.GONE
            }

            // Header Row (Title and Close button)
            val headerLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }

            val headerTitleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val iconTxt = TextView(this).apply {
                text = "⭐"
                textSize = 14f
                setPadding(0, 0, dpToPx(4), 0)
            }
            headerTitleLayout.addView(iconTxt)

            val titleTxt = TextView(this).apply {
                text = "منصة الأتمتة"
                setTextColor(Color.parseColor("#ffd700"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            headerTitleLayout.addView(titleTxt)
            headerLayout.addView(headerTitleLayout)

            val closeTxtBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#cbd5e1"))
                textSize = 16f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                setOnClickListener {
                    expandedCardLayout.visibility = View.GONE
                    collapsedLayout.visibility = View.VISIBLE
                }
            }
            headerLayout.addView(closeTxtBtn)
            expandedCardLayout.addView(headerLayout)

            // Divider Line
            val divider = View(this).apply {
                setBackgroundColor(Color.parseColor("#334155"))
                val dParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(8))
                }
                layoutParams = dParams
            }
            expandedCardLayout.addView(divider)

            // Status Row
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }
            val statusLabel = TextView(this).apply {
                text = "المراقبة الفورية:"
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusRow.addView(statusLabel)

            val statusIndicatorContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusCircle = View(this).apply {
                background = createCircleDrawable("#10b981")
                val cParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                    setMargins(0, 0, dpToPx(6), 0)
                }
                layoutParams = cParams
            }
            statusIndicatorContainer.addView(statusCircle)

            statusTxt = TextView(this).apply {
                text = "نشط"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            statusIndicatorContainer.addView(statusTxt)
            statusRow.addView(statusIndicatorContainer)
            expandedCardLayout.addView(statusRow)

            // Stats Row (Base folder & files count)
            val statsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }
            val folderTxt = TextView(this).apply {
                val currentBase = getBaseDir()
                text = "المجلد: ${currentBase.name}"
                setTextColor(Color.parseColor("#cbd5e1"))
                textSize = 10f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            statsRow.addView(folderTxt)

            filesCountTxt = TextView(this).apply {
                text = "الملفات: 0"
                setTextColor(Color.parseColor("#ffd700"))
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            statsRow.addView(filesCountTxt)
            expandedCardLayout.addView(statsRow)

            // Last Event text view block
            lastActionTxt = TextView(this).apply {
                text = "آخر إجراء: لا توجد عمليات نشطة"
                setTextColor(Color.parseColor("#cbd5e1"))
                textSize = 9.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = createRoundedDrawable("#0f172a", 4f)
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                val lParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(10))
                }
                layoutParams = lParams
            }
            expandedCardLayout.addView(lastActionTxt)

            // Primary Manual Copy processing trigger button
            val manualTriggerBtn = Button(this).apply {
                text = "⚡ معالجة الحافظة الآن"
                setTextColor(Color.WHITE)
                textSize = 11f
                background = createRoundedDrawable("#d97706", 8f)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    manualTriggerClipboardFromBubble()
                }
                val bParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(8))
                }
                layoutParams = bParams
            }
            expandedCardLayout.addView(manualTriggerBtn)

            // Action Row buttons (Toggle status, Settings, Exit/Close)
            val actionBtnsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            toggleStatusBtn = Button(this).apply {
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#475569", 6f)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    toggleClipboardServicePause()
                    updateStatusUI()
                }
                val tParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = tParams
            }
            actionBtnsRow.addView(toggleStatusBtn)

            val settingsBtn = Button(this).apply {
                text = "الإعدادات"
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#475569", 6f)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    launchAppMain()
                }
                val sParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = sParams
            }
            actionBtnsRow.addView(settingsBtn)

            val stopBtn = Button(this).apply {
                text = "إغلاق"
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#991b1b", 6f)
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    removeFloatingView()
                    stopSelf()
                }
                val clParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f)
                layoutParams = clParams
            }
            actionBtnsRow.addView(stopBtn)

            expandedCardLayout.addView(actionBtnsRow)

            val expandedCardParams = FrameLayout.LayoutParams(dpToPx(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            rootLayout.addView(expandedCardLayout, expandedCardParams)

            // Draggability Logic
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false

            val dragTouchListener = View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isMoving = true
                        }
                        params.x = initialX + deltaX.toInt()
                        params.y = initialY + deltaY.toInt()
                        try {
                            windowManager.updateViewLayout(rootLayout, params)
                        } catch (e: Exception) {
                            Log.e("BubbleService", "Error updateViewLayout: ${e.message}")
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoving) {
                            view.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            collapsedLayout.setOnTouchListener(dragTouchListener)
            collapsedLayout.setOnClickListener {
                collapsedLayout.visibility = View.GONE
                expandedCardLayout.visibility = View.VISIBLE
            }

            headerLayout.setOnTouchListener(dragTouchListener)

            // Reactively fetch DB stats using Coroutine Collection
            serviceScope.launch {
                database.dao().getAllCreatedFiles().collect { files ->
                    val totalFilesCount = files.distinctBy { it.path }.size
                    withContext(Dispatchers.Main) {
                        try {
                            filesCountTxt.text = "الملفات: $totalFilesCount"
                        } catch (e: Exception) {}
                    }
                }
            }

            serviceScope.launch {
                database.dao().getAllLogs().collect { logs ->
                    val lastAction = logs.firstOrNull()?.message ?: "لا توجد عمليات نشطة"
                    withContext(Dispatchers.Main) {
                        try {
                            lastActionTxt.text = "آخر إجراء: $lastAction"
                        } catch (e: Exception) {}
                    }
                }
            }

            // Sync states with SharedPreferences initially
            updateStatusUI()

            floatingView = rootLayout
            windowManager.addView(rootLayout, params)

        } catch (e: Exception) {
            Log.e("BubbleService", "Error displaying bubble overlay: ${e.message}", e)
            Toast.makeText(applicationContext, "فشل تشغيل الكرة العائمة: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
            removeFloatingView()
            stopSelf()
        }
    }

    private fun manualTriggerClipboardFromBubble() {
        try {
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                val clipData = clipboardManager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        Toast.makeText(applicationContext, "🔄 جاري المعالجة الفورية المباشرة...", Toast.LENGTH_SHORT).show()
                        processClipboardContent(text)
                    } else {
                        Toast.makeText(applicationContext, "الحافظة فارغة حالياً.", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(applicationContext, "لم يعثر على بادئات أو نصوص بالحافظة.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "خطأ بالطلب: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleClipboardServicePause() {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val current = sharedPrefs.getBoolean("clipboard_is_paused", false)
        sharedPrefs.edit().putBoolean("clipboard_is_paused", !current).apply()
        
        val message = if (!current) "تم إيقاف المراقبة مؤقتاً." else "تم استئناف المراقبة بنشاط."
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun launchAppMain() {
        try {
            val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(launchIntent)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "فشل فتح التطبيق الرئيسي: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFloatingView() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore
            }
            floatingView = null
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

    override fun onDestroy() {
        removeFloatingView()
        handler.removeCallbacks(checkRunnable)
        isPollingActive = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null
}
