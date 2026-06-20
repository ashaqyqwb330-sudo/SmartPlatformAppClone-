package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlinx.coroutines.flow.first

import android.widget.ScrollView
import android.view.inputmethod.InputMethodManager

/**
 * الخدمة العائمة الذهبية V2 - Golden Bubble Service
 * واجهة تقليدية View-based بحتة لضمان أقصى مستويات الأداء والاستقرار وسرعة الاستجابة.
 * لا تحتوي على أي أكواد Compose لتفادي أي انهيار غير متوقع على مختلف الأجهزة.
 */
class GoldenBubbleService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var rootLayout: FrameLayout? = null
    private lateinit var database: AppDatabase

    private val handler = Handler(Looper.getMainLooper())
    private var lastKnownClipText = ""
    private var isPollingActive = false
    private var lastSavedFilePath: String? = null

    private lateinit var statusCircle: View
    private lateinit var statusTxt: TextView
    private lateinit var lastActionTxt: TextView
    private lateinit var toggleStatusBtn: Button
    
    private lateinit var bubbleStatusTxt: TextView
    private var clipboardReceiver: BroadcastReceiver? = null

    // Project Context Dialog Views
    private var contextDialogLayout: LinearLayout? = null
    private var contextDialogText: TextView? = null
    private var saveHereBtn: Button? = null
    private var newFolderBtn: Button? = null
    private var ignoreBtn: Button? = null
    private var activeProjectTxtView: TextView? = null

    // Manual Folder Name Input Views
    private var manualNameInputLayout: LinearLayout? = null
    private var manualFolderNameEditText: android.widget.EditText? = null
    private var confirmManualFolderBtn: Button? = null
    private var cancelManualFolderBtn: Button? = null

    // Template Dialog Views
    private var templateDialogLayout: LinearLayout? = null
    private var importFastBtn: Button? = null
    private var importDetailBtn: Button? = null
    private var templateCancelBtn: Button? = null
    private var pendingTemplateText: String? = null

    private val isPaused: Boolean
        get() = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)

    private val clipboardRunnable = object : Runnable {
        override fun run() {
            checkClipboardInGoldenBubble()
            handler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        database = AppDatabase.getDatabase(this)
        
        // Register broadcast receiver for clipboard updates & smart capture completions
        clipboardReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "com.example.ACTION_CLIPBOARD_UPDATED") {
                    val text = intent.getStringExtra("extra_text") ?: ""
                    if (text.isNotBlank()) {
                        Log.d("GoldenBubbleService", "Received clipboard broadcast: size=${text.length}")
                        onClipboardTextDetected(text)
                    }
                } else if (intent?.action == "com.example.ACTION_SMART_CAPTURE_COMPLETED") {
                    val lastSavedName = intent.getStringExtra("last_saved_name") ?: ""
                    val lastSavedPath = intent.getStringExtra("last_saved_path") ?: ""
                    if (lastSavedPath.isNotEmpty()) {
                        lastSavedFilePath = lastSavedPath
                        updateLastActionText("💾 تم حفظ: $lastSavedName (انقر للمستند)")
                    }
                } else if (intent?.action == "com.example.ACTION_PROJECT_CONTEXT_QUESTION") {
                    val text = intent.getStringExtra("extra_text") ?: ""
                    if (text.isNotBlank()) {
                        showContextDecisionDialog(text)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("com.example.ACTION_CLIPBOARD_UPDATED")
            addAction("com.example.ACTION_SMART_CAPTURE_COMPLETED")
            addAction("com.example.ACTION_PROJECT_CONTEXT_QUESTION")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(clipboardReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(clipboardReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e("GoldenBubbleService", "Error registering receiver: ${e.message}")
        }
        
        startGoldenPolling()
        Log.d("GoldenBubbleService", "GoldenBubbleService created and polling handler started.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            try {
                removeGoldenView()
                stopSelf()
            } catch (e: Exception) {
                Log.e("GoldenBubbleService", "Error stopping service: ${e.message}")
            }
            return START_NOT_STICKY
        }

        if (rootLayout != null) return START_STICKY

        setupGoldenView()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        return START_STICKY
    }

    private fun startGoldenPolling() {
        if (!isPollingActive) {
            handler.post(clipboardRunnable)
            isPollingActive = true
        }
    }

    private fun checkClipboardInGoldenBubble() {
        if (isPaused) return
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip ?: return
                if (clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank() && text != lastKnownClipText) {
                        lastKnownClipText = text
                        onClipboardTextDetected(text)
                    }
                }
            }
        } catch (e: Exception) {
            // safe silent catch
        }
    }

    private fun onClipboardTextDetected(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
        if (!isAutoProcess) return

        val textHash = text.trim().hashCode().toString()
        val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
        if (textHash == lastProcessedHash) return

        val smartPrefs = getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE)
        val autoImportTemplates = smartPrefs.getBoolean("auto_import_templates", true)
        val trimmedText = text.trim()
        if (autoImportTemplates && trimmedText.startsWith("{") && (trimmedText.contains("template_version") || trimmedText.contains("templateVersion"))) {
            pendingTemplateText = text
            showTemplateDialogOverlay()
            return
        }

        val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
        val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
        val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

        val smartEnabled = smartPrefs.getBoolean("smart_capture_enabled", false)

        if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:") || smartEnabled) {
            processClipboardContent(text, force = false)
        }
    }

    private fun processClipboardContent(text: String, force: Boolean = false) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val textHash = text.trim().hashCode().toString()
        if (!force) {
            val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
            if (textHash == lastProcessedHash && text.isNotBlank()) return

            val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
            if (text == lastProcessed && text.isNotBlank()) return
        }

        sharedPrefs.edit().apply {
            putString("last_processed_text_hash", textHash)
            putString("last_auto_processed_text", text)
            apply()
        }

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
                    var lastCreatedPath = ""
                    for (res in results) {
                        if (res.type == "builder") {
                            buildersCount++
                            val path = res.data?.get("path") ?: "unknown"
                            val size = res.data?.get("size")?.toLongOrNull() ?: 0L
                            val mode = res.data?.get("mode") ?: "w"
                            val fullPath = res.data?.get("full_path") ?: ""
                            lastCreatedPath = path

                            database.dao().insertFile(
                                FileEntity(path = path, fullPath = fullPath, size = size, mode = mode)
                            )
                            database.dao().insertLog(
                                LogEntity(type = "builder", message = "الفقاعة الذهبية: تم إنشاء $path", details = res.message)
                            )
                        } else {
                            database.dao().insertLog(
                                LogEntity(type = res.type, message = "الفقاعة الذهبية: إجراء ${res.type}", details = res.message)
                            )
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (buildersCount > 0 && lastCreatedPath.isNotEmpty()) {
                            Toast.makeText(applicationContext, "✅ تم إنشاء $lastCreatedPath في المجلد بنجاح!", Toast.LENGTH_LONG).show()
                            updateLastActionText("تم إنشاء $lastCreatedPath")
                        } else {
                            Toast.makeText(applicationContext, "✅ معالجة ناجحة! تم تنفيذ الإجراءات.", Toast.LENGTH_SHORT).show()
                            updateLastActionText("معالجة ناجحة: تم تنفيذ الإجراءات")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GoldenBubbleService", "Error processing content: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateLastActionText("فشل المعالجة: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun updateLastActionText(msg: String) {
        try {
            lastActionTxt.text = "آخر إجراء: $msg"
            if (::bubbleStatusTxt.isInitialized) {
                bubbleStatusTxt.text = msg
            }
        } catch (e: Exception) {}
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun getRelativeTimeString(time: Long): String {
        val diff = System.currentTimeMillis() - time
        if (diff < 0) return "الآن"
        val seconds = diff / 1000
        if (seconds < 60) return "الآن"
        val minutes = seconds / 60
        if (minutes < 60) return "منذ $minutes د"
        val hours = minutes / 60
        if (hours < 24) return "منذ $hours س"
        val days = hours / 24
        return "منذ $days ي"
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
            toggleStatusBtn.text = "إيقاف مؤقت"
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun setupGoldenView() {
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
                x = 150
                y = 250
            }

            // Root FrameLayout
            val root = FrameLayout(this)

            // 1. COLLAPSED CONTAINER VIEW (Contains Circle bubble + tiny Text beneath it - Problem 4)
            val collapsedLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.VISIBLE
            }

            // The Gold Circle Bubble itself
            val circleBubble = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = createCircleDrawable("#1A1A2E").apply {
                    setStroke(dpToPx(2), Color.parseColor("#FFD700"))
                }
                val lbTxt = TextView(this@GoldenBubbleService).apply {
                    text = "✨"
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                }
                addView(lbTxt)
            }
            val bubbleParams = LinearLayout.LayoutParams(dpToPx(54), dpToPx(54))
            collapsedLayout.addView(circleBubble, bubbleParams)

            // Tiny TextView below circleBubble
            bubbleStatusTxt = TextView(this).apply {
                text = "المراقب الذكي - نشط"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 9f
                gravity = Gravity.CENTER
                setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                background = createRoundedDrawable("#2A1A3E", 4f, "#FFD700", 1)
                val statusParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(4), 0, 0)
                }
                layoutParams = statusParams
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnTouchListener { _, event ->
                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                        val path = lastSavedFilePath
                        if (path != null) {
                            com.example.engine.FileUtils.openFile(this@GoldenBubbleService, path)
                        } else {
                            collapsedLayout.performClick()
                        }
                    }
                    true
                }
            }
            collapsedLayout.addView(bubbleStatusTxt)

            val collapsedParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            root.addView(collapsedLayout, collapsedParams)

            // 2. EXPANDED CARD VIEW
            val expandedLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1A1A2E", 16f, "#FFD700", 2)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                visibility = View.GONE
            }

            // Header (Title and Close 'x' icon)
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }

            val titleContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val goldIcon = TextView(this).apply {
                text = "👑"
                textSize = 14f
                setPadding(0, 0, dpToPx(4), 0)
            }
            titleContainer.addView(goldIcon)

            val titleTxt = TextView(this).apply {
                text = "المراقب الذكي الذهبي"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            }
            titleContainer.addView(titleTxt)
            header.addView(titleContainer)

            val closeIconBtn = TextView(this).apply {
                text = "✕"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 16f
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                setOnClickListener {
                    expandedLayout.visibility = View.GONE
                    collapsedLayout.visibility = View.VISIBLE
                }
            }
            header.addView(closeIconBtn)
            expandedLayout.addView(header)

            // Divider line
            val divider = View(this).apply {
                setBackgroundColor(Color.parseColor("#2E2E4E"))
                val dParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(8))
                }
                layoutParams = dParams
            }
            expandedLayout.addView(divider)

            // Status Indicator Row
            val statusRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dpToPx(6))
            }
            val statusLabel = TextView(this).apply {
                text = "مراقبة الحافظة:"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            statusRow.addView(statusLabel)

            val indicatorContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            statusCircle = View(this).apply {
                background = createCircleDrawable("#10B981")
                val cParams = LinearLayout.LayoutParams(dpToPx(8), dpToPx(8)).apply {
                    setMargins(0, 0, dpToPx(6), 0)
                }
                layoutParams = cParams
            }
            indicatorContainer.addView(statusCircle)

            statusTxt = TextView(this).apply {
                text = "نشط"
                setTextColor(Color.WHITE)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
            }
            indicatorContainer.addView(statusTxt)
            statusRow.addView(indicatorContainer)
            expandedLayout.addView(statusRow)

            // 1.5 Active Project Indicator & Switcher Row
            val activeProjectRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(2), 0, dpToPx(6))
            }

            val projectLabelPrefix = TextView(this).apply {
                text = "المشروع النشط:"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            activeProjectRow.addView(projectLabelPrefix)

            val activeProjectTxt = TextView(this).apply {
                val currentPath = com.example.engine.ProjectContextManager.getCurrentProjectPath(this@GoldenBubbleService)
                val allProjs = com.example.engine.ProjectManager.getAllProjects(this@GoldenBubbleService)
                val currentName = allProjs.find { it.first == currentPath || it.first.endsWith(currentPath) }?.second ?: "سياق النشاط"
                text = "📁 $currentName"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, dpToPx(6), 0)
            }
            activeProjectTxtView = activeProjectTxt
            activeProjectRow.addView(activeProjectTxt)

            val switchProjectBtn = Button(this).apply {
                text = "🔄 تبديل"
                setTextColor(Color.BLACK)
                background = createRoundedDrawable("#FFD700", 4f)
                textSize = 8.5f
                setPadding(dpToPx(6), dpToPx(2), dpToPx(6), dpToPx(2))
                val lp = LinearLayout.LayoutParams(dpToPx(56), dpToPx(24))
                layoutParams = lp
            }
            activeProjectRow.addView(switchProjectBtn)

            switchProjectBtn.setOnClickListener {
                val allProjs = com.example.engine.ProjectManager.getAllProjects(this@GoldenBubbleService).take(3)
                if (allProjs.size > 1) {
                    val currentPath = com.example.engine.ProjectContextManager.getCurrentProjectPath(this@GoldenBubbleService)
                    var index = allProjs.indexOfFirst { it.first == currentPath || it.first.endsWith(currentPath) }
                    if (index == -1) index = 0
                    val nextIndex = (index + 1) % allProjs.size
                    val nextProj = allProjs[nextIndex]
                    com.example.engine.ProjectManager.setActiveProject(this@GoldenBubbleService, nextProj.first)
                    activeProjectTxt.text = "📁 ${nextProj.second}"
                    Toast.makeText(applicationContext, "🔄 تم تبديل المشروع النشط إلى: ${nextProj.second}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(applicationContext, "لا توجد مشاريع أخرى للتبديل إليها", Toast.LENGTH_SHORT).show()
                }
            }

            expandedLayout.addView(activeProjectRow)

            // Last Event message bubble
            lastActionTxt = TextView(this).apply {
                text = "آخر إجراء: لا توجد عمليات حالية"
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 9.5f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = createRoundedDrawable("#0F0F1E", 4f)
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
                val laParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(6))
                }
                layoutParams = laParams
            }
            expandedLayout.addView(lastActionTxt)

            // Event Logs Section (Auto updating from database)
            val logsTitle = TextView(this).apply {
                text = "📋 سجل مراقبة الملفات والأحداث:"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dpToPx(2), 0, dpToPx(4))
            }
            expandedLayout.addView(logsTitle)

            val logsScrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(70)
                ).apply {
                    setMargins(0, 0, 0, dpToPx(8))
                }
                background = createRoundedDrawable("#09081A", 8f)
                setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
            }

            val logsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            logsScrollView.addView(logsContainer)
            expandedLayout.addView(logsScrollView)

            // Start listening to Flow of database logs
            serviceScope.launch {
                database.dao().getAllLogs().collect { logsList ->
                    val recentLogs = logsList.take(5)
                    withContext(Dispatchers.Main) {
                        logsContainer.removeAllViews()
                        if (recentLogs.isEmpty()) {
                            val emptyTxt = TextView(this@GoldenBubbleService).apply {
                                text = "لا توجد سجلات بعد."
                                setTextColor(Color.parseColor("#64748B"))
                                textSize = 9f
                                gravity = Gravity.CENTER
                                setPadding(0, dpToPx(16), 0, dpToPx(16))
                            }
                            logsContainer.addView(emptyTxt)
                        } else {
                            for (log in recentLogs) {
                                val logRow = LinearLayout(this@GoldenBubbleService).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    setPadding(0, dpToPx(2), 0, dpToPx(2))
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                }
                                
                                val iconTxt = TextView(this@GoldenBubbleService).apply {
                                    text = when (log.type.lowercase()) {
                                        "builder" -> "📄"
                                        "executor" -> "⚙️"
                                        "treedoc" -> "📁"
                                        "gemini" -> "🧠"
                                        else -> "⚠️"
                                    }
                                    textSize = 11f
                                    setPadding(0, 0, dpToPx(4), 0)
                                }
                                logRow.addView(iconTxt)
                                
                                val contentLayout = LinearLayout(this@GoldenBubbleService).apply {
                                    orientation = LinearLayout.VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                }
                                
                                val msgTxt = TextView(this@GoldenBubbleService).apply {
                                    text = log.message
                                    setTextColor(Color.parseColor("#E2E8F0"))
                                    textSize = 9.5f
                                    maxLines = 1
                                    ellipsize = android.text.TextUtils.TruncateAt.END
                                }
                                contentLayout.addView(msgTxt)
                                
                                val timeTxt = TextView(this@GoldenBubbleService).apply {
                                    text = getRelativeTimeString(log.timestamp)
                                    setTextColor(Color.parseColor("#64748B"))
                                    textSize = 8f
                                    gravity = Gravity.LEFT
                                }
                                contentLayout.addView(timeTxt)
                                
                                logRow.addView(contentLayout)
                                
                                val typeLow = log.type.lowercase()
                                if (typeLow == "smart_capture" || typeLow == "smart_capture" || typeLow == "builder") {
                                    logRow.setOnClickListener {
                                        var path = log.details ?: ""
                                        if (path.startsWith("المسار: ")) {
                                            path = path.removePrefix("المسار: ").trim()
                                        } else if (path.startsWith("الملف: ")) {
                                            path = path.removePrefix("الملف: ").trim()
                                        }
                                        if (path.isNotEmpty()) {
                                            com.example.engine.FileUtils.openFile(this@GoldenBubbleService, path)
                                        }
                                    }
                                }
                                
                                logsContainer.addView(logRow)
                            }
                        }
                    }
                }
            }

            // Central button to process Clipboard immediately manually
            val triggerBtn = Button(this).apply {
                text = "⚡ معالجة الحافظة الآن"
                setTextColor(Color.WHITE)
                textSize = 11f
                background = createRoundedDrawable("#D97706", 8f)
                setOnClickListener {
                    manualCollectClipboard()
                }
                val tbParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(6))
                }
                layoutParams = tbParams
            }
            expandedLayout.addView(triggerBtn)

            // Keyboard Toggle Button
            val keyboardBtn = Button(this).apply {
                text = "⌨️ تبديل لوحة المفاتيح"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 11f
                background = createRoundedDrawable("#1E1D3A", 8f, "#FFD700", 1)
                setOnClickListener {
                    try {
                        // Start TransparentActivity to guarantee IME system dialog display (Problem 2)
                        val intent = Intent(this@GoldenBubbleService, TransparentActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(applicationContext, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                val ksbParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(8))
                }
                layoutParams = ksbParams
            }
            expandedLayout.addView(keyboardBtn)

            // Copy Latest Logs Button (Problem 5)
            val copyLogsBtn = Button(this).apply {
                text = "📋 نسخ آخر الأحداث"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11f
                background = createRoundedDrawable("#1F2937", 8f, "#64748B", 1)
                setOnClickListener {
                    serviceScope.launch {
                        try {
                            val smartPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                            val count = smartPrefs.getInt("log_copy_count", 5)
                            
                            // Get current logs list snapshot
                            val logs = database.dao().getAllLogs().first().take(count)
                            if (logs.isEmpty()) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(applicationContext, "سجل الأحداث فارغ.", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }
                            
                            val formattedLogs = logs.joinToString("\n") { log ->
                                "[${getRelativeTimeString(log.timestamp)}] ${log.message}"
                            }
                            
                            withContext(Dispatchers.Main) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Event Logs", formattedLogs)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(applicationContext, "تم نسخ آخر $count أحداث بنجاح!", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(applicationContext, "خطأ أثناء نسخ الأحداث: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                val clParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(8))
                }
                layoutParams = clParams
            }
            expandedLayout.addView(copyLogsBtn)

            // Actions panel buttons Row (Toggle pause, stop entire service)
            val actionsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            toggleStatusBtn = Button(this).apply {
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#475569", 6f)
                setOnClickListener {
                    toggleMonitoringPause()
                    updateStatusUI()
                }
                val tsParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = tsParams
            }
            actionsRow.addView(toggleStatusBtn)

            val appLauncherBtn = Button(this).apply {
                text = "الرئيسية"
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#475569", 6f)
                setOnClickListener {
                    launchMainApp()
                }
                val alParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = alParams
            }
            actionsRow.addView(appLauncherBtn)

            val killServiceBtn = Button(this).apply {
                text = "إغلاق"
                textSize = 9.5f
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#991B1B", 6f)
                setOnClickListener {
                    removeGoldenView()
                    stopSelf()
                }
                val ksParams = LinearLayout.LayoutParams(0, dpToPx(30), 1f)
                layoutParams = ksParams
            }
            actionsRow.addView(killServiceBtn)
            expandedLayout.addView(actionsRow)

            val cardParams = FrameLayout.LayoutParams(dpToPx(270), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            root.addView(expandedLayout, cardParams)

            // 3. CONTEXT DECISION DIALOG OVERLAY
            val contextDL = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1F1F35", 16f, "#FFD700", 2)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                visibility = View.GONE
            }

            val cdTitle = TextView(this).apply {
                text = "💡 قرار سياق المشروع"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dpToPx(8))
            }
            contextDL.addView(cdTitle)

            val cdBody = TextView(this).apply {
                text = ""
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.START
                setPadding(0, 0, 0, dpToPx(12))
            }
            contextDL.addView(cdBody)
            contextDialogText = cdBody

            val cdActions = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }

            val btnSaveHere = Button(this).apply {
                text = "حفظ في المشروع الحالي"
                setTextColor(Color.BLACK)
                background = createRoundedDrawable("#10B981", 8f)
                textSize = 11f
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(6))
                }
                layoutParams = lp
            }
            cdActions.addView(btnSaveHere)
            saveHereBtn = btnSaveHere

            val btnNewFolder = Button(this).apply {
                text = "مجلد جديد واقتراح اسم"
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#3B82F6", 8f)
                textSize = 11f
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34)).apply {
                    setMargins(0, 0, 0, dpToPx(6))
                }
                layoutParams = lp
            }
            cdActions.addView(btnNewFolder)
            newFolderBtn = btnNewFolder

            val btnIgnore = Button(this).apply {
                text = "تجاهل"
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#EF4444", 8f)
                textSize = 11f
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(34))
                layoutParams = lp
            }
            cdActions.addView(btnIgnore)
            ignoreBtn = btnIgnore

            contextDL.addView(cdActions)
            contextDialogLayout = contextDL

            root.addView(contextDL, cardParams)

            // 4. MANUAL FOLDER NAME INPUT OVERLAY
            val manualFL = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1F1F35", 16f, "#FFD700", 2)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                visibility = View.GONE
            }

            val mfTitle = TextView(this).apply {
                text = "📁 مجلد مشروع جديد"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dpToPx(8))
            }
            manualFL.addView(mfTitle)

            val mfEditText = android.widget.EditText(this).apply {
                hint = "اكتب اسم المجلد الجديد..."
                setHintTextColor(Color.GRAY)
                setTextColor(Color.WHITE)
                textSize = 13f
                background = createRoundedDrawable("#2A2A44", 8f, "#444466", 1)
                setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dpToPx(12))
                }
                layoutParams = lp
            }
            manualFL.addView(mfEditText)
            manualFolderNameEditText = mfEditText

            val mfBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val btnCancelManual = Button(this).apply {
                text = "إلغاءونص"
                text = "إلغاء"
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#EF4444", 8f)
                textSize = 12f
                val lp = LinearLayout.LayoutParams(0, dpToPx(34), 1f).apply {
                    setMargins(0, 0, dpToPx(6), 0)
                }
                layoutParams = lp
            }
            mfBtnRow.addView(btnCancelManual)
            cancelManualFolderBtn = btnCancelManual

            val btnConfirmManual = Button(this).apply {
                text = "تأكيد"
                setTextColor(Color.BLACK)
                background = createRoundedDrawable("#10B981", 8f)
                textSize = 12f
                val lp = LinearLayout.LayoutParams(0, dpToPx(34), 1f)
                layoutParams = lp
            }
            mfBtnRow.addView(btnConfirmManual)
            confirmManualFolderBtn = btnConfirmManual

            manualFL.addView(mfBtnRow)
            manualNameInputLayout = manualFL

            root.addView(manualFL, cardParams)

            // 5. TEMPLATE DETECTION OVERLAY
            val templateFL = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = createRoundedDrawable("#1F1F35", 16f, "#FFD700", 2)
                setPadding(dpToPx(14), dpToPx(14), dpToPx(14), dpToPx(14))
                visibility = View.GONE
            }

            val tfTitle = TextView(this).apply {
                text = "📁 تم كشف قالب مشروع ذكي! هل تريد استيراده؟"
                setTextColor(Color.parseColor("#FFD700"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, dpToPx(12))
            }
            templateFL.addView(tfTitle)

            val tfBtnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            val btnFast = Button(this).apply {
                text = "استيراد سريع"
                setTextColor(Color.BLACK)
                background = createRoundedDrawable("#10B981", 8f)
                textSize = 10f
                val lp = LinearLayout.LayoutParams(0, dpToPx(34), 1.2f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = lp
            }
            tfBtnRow.addView(btnFast)
            importFastBtn = btnFast

            val btnDetail = Button(this).apply {
                text = "معاينة وتحرير"
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#3B82F6", 8f)
                textSize = 10f
                val lp = LinearLayout.LayoutParams(0, dpToPx(34), 1.2f).apply {
                    setMargins(0, 0, dpToPx(4), 0)
                }
                layoutParams = lp
            }
            tfBtnRow.addView(btnDetail)
            importDetailBtn = btnDetail

            val btnCancelTmpl = Button(this).apply {
                text = "تجاهل"
                setTextColor(Color.WHITE)
                background = createRoundedDrawable("#EF4444", 8f)
                textSize = 10f
                val lp = LinearLayout.LayoutParams(0, dpToPx(34), 1f)
                layoutParams = lp
            }
            tfBtnRow.addView(btnCancelTmpl)
            templateCancelBtn = btnCancelTmpl

            templateFL.addView(tfBtnRow)
            templateDialogLayout = templateFL

            root.addView(templateFL, cardParams)

            // Setup template click actions
            btnFast.setOnClickListener {
                val tText = pendingTemplateText
                if (!tText.isNullOrBlank()) {
                    performFastImport(tText)
                } else {
                    Toast.makeText(applicationContext, "عذراً، محتوى القالب غير متوفر", Toast.LENGTH_SHORT).show()
                }
            }

            btnDetail.setOnClickListener {
                val tText = pendingTemplateText
                if (!tText.isNullOrBlank()) {
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                        putExtra("import_template_json", tText)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                    hideTemplateDialogOverlay()
                } else {
                    Toast.makeText(applicationContext, "عذراً، محتوى القالب غير متوفر", Toast.LENGTH_SHORT).show()
                }
            }

            btnCancelTmpl.setOnClickListener {
                hideTemplateDialogOverlay()
            }

            // Touch Dragging Logic
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isMoving = false

            val touchDragListener = View.OnTouchListener { view, event ->
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
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true
                        }
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(root, params)
                        } catch (e: Exception) {}
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

            collapsedLayout.setOnTouchListener(touchDragListener)
            collapsedLayout.setOnClickListener {
                collapsedLayout.visibility = View.GONE
                expandedLayout.visibility = View.VISIBLE
            }

            header.setOnTouchListener(touchDragListener)

            // Initialize UI elements states
            updateStatusUI()

            rootLayout = root
            windowManager.addView(root, params)

        } catch (e: Exception) {
            Log.e("GoldenBubbleService", "Error building golden bubble overlay: ${e.message}", e)
            Toast.makeText(applicationContext, "فشل تشغيل الكرة الذهبية: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
            removeGoldenView()
            stopSelf()
        }
    }

    private fun showContextDecisionDialog(text: String) {
        val currentProj = com.example.engine.ProjectContextManager.getCurrentProjectPath(this)
        val keywords = com.example.engine.ProjectContextManager.extractKeywords(text)
        val topic = if (keywords.isNotEmpty()) keywords.take(2).joinToString(" و ") else "موضوعات عامة"
        
        val bodyMsg = "هذا النص يبدو أنه عن '$topic'.\nأنت حالياً في مشروع '$currentProj'.\nأين تريد حفظه؟"
        
        contextDialogText?.text = bodyMsg
        
        saveHereBtn?.setOnClickListener {
            com.example.engine.ProjectContextManager.isBypassed = true
            serviceScope.launch {
                val baseDir = getBaseDir()
                val cmdContext = com.example.engine.CommandContext(
                    context = applicationContext,
                    baseDir = baseDir,
                    args = emptyMap(),
                    flags = emptyList()
                )
                val results = com.example.engine.SmartCaptureEngine.processCapturedText(text, cmdContext)
                
                // Write Log
                val db = AppDatabase.getDatabase(applicationContext)
                db.dao().insertLog(
                    LogEntity(
                        type = "context_manager",
                        message = "تم الحفظ في مسار المشروع ذو الصلة ($currentProj)",
                        details = "تم تأكيد الحفظ بواسطة المستخدم بنجاح.\nالملفات: ${results.savedFiles.joinToString { it.fileName }}"
                    )
                )
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "✅ تم الحفظ في $currentProj", Toast.LENGTH_SHORT).show()
                    hideContextDecisionDialog()
                }
            }
            com.example.engine.ProjectContextManager.isBypassed = false
        }
        
        newFolderBtn?.setOnClickListener {
            val suggested = com.example.engine.ProjectContextManager.suggestFolderName(text, applicationContext)
            if (suggested == "MANUAL") {
                showManualFolderNameInputDialog(text)
            } else {
                createNewFolderAndSave(suggested, text)
            }
        }
        
        ignoreBtn?.setOnClickListener {
            hideContextDecisionDialog()
            Toast.makeText(applicationContext, "❌ تم تجاهل النص", Toast.LENGTH_SHORT).show()
        }
        
        rootLayout?.let { root ->
            for (i in 0 until root.childCount) {
                root.getChildAt(i).visibility = View.GONE
            }
            contextDialogLayout?.visibility = View.VISIBLE
        }
    }

    private fun showManualFolderNameInputDialog(text: String) {
        contextDialogLayout?.visibility = View.GONE
        manualNameInputLayout?.visibility = View.VISIBLE
        manualFolderNameEditText?.setText("")
        manualFolderNameEditText?.requestFocus()

        confirmManualFolderBtn?.setOnClickListener {
            val entered = manualFolderNameEditText?.text?.toString()?.trim() ?: ""
            if (entered.isBlank()) {
                Toast.makeText(applicationContext, "يرجى كتابة اسم صحيح للمجلد", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createNewFolderAndSave(entered, text)
        }

        cancelManualFolderBtn?.setOnClickListener {
            manualNameInputLayout?.visibility = View.GONE
            contextDialogLayout?.visibility = View.VISIBLE
        }
    }

    private fun createNewFolderAndSave(folderName: String, text: String) {
        com.example.engine.ProjectContextManager.isBypassed = true
        serviceScope.launch {
            val baseDir = getBaseDir()
            val sanitizedFolder = folderName.replace(Regex("[\\\\/:*?\"<>|]"), " ").replace(Regex("\\s+"), "_").trim()
            val finalFolder = if (sanitizedFolder.isEmpty()) "مجلد_جديد" else sanitizedFolder
            
            val newFolder = File(baseDir, finalFolder)
            newFolder.mkdirs()
            
            com.example.engine.ProjectContextManager.setCurrentProjectPath(applicationContext, finalFolder)
            
            val cmdContext = com.example.engine.CommandContext(
                context = applicationContext,
                baseDir = baseDir,
                args = emptyMap(),
                flags = emptyList()
            )
            val results = com.example.engine.SmartCaptureEngine.processCapturedText(text, cmdContext)
            
            // Write Log
            try {
                val db = AppDatabase.getDatabase(applicationContext)
                db.dao().insertLog(
                    LogEntity(
                        type = "context_manager",
                        message = "إنشاء مجلد: تم إنشاء مجلد باسم '$finalFolder' وحفظ المستند فيه.",
                        details = "تم الحفظ بنجاح.\nالملفات: ${results.savedFiles.joinToString { it.fileName }}"
                    )
                )
            } catch (e: Exception) {
                // silently ignored
            }
            
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "✅ تم الحفظ وإنشاء مجلد: $finalFolder", Toast.LENGTH_SHORT).show()
                manualNameInputLayout?.visibility = View.GONE
                hideContextDecisionDialog()
            }
        }
        com.example.engine.ProjectContextManager.isBypassed = false
    }
    
    private fun showTemplateDialogOverlay() {
        contextDialogLayout?.visibility = View.GONE
        manualNameInputLayout?.visibility = View.GONE
        templateDialogLayout?.visibility = View.VISIBLE
    }

    private fun hideTemplateDialogOverlay() {
        templateDialogLayout?.visibility = View.GONE
        hideContextDecisionDialog()
    }

    private fun performFastImport(templateJson: String) {
        val parsedResult = com.example.engine.TemplateParser.parse(templateJson)
        if (parsedResult.isSuccess) {
            val template = parsedResult.getOrThrow()
            val basePath = com.example.engine.ProjectContextManager.getBaseDir(applicationContext).absolutePath
            val buildResult = com.example.engine.ProjectBuilder.build(template, basePath)
            if (buildResult.isSuccess) {
                val projectPath = buildResult.getOrThrow()
                com.example.engine.ProjectManager.addProject(applicationContext, projectPath, template.projectName)
                com.example.engine.ProjectManager.setActiveProject(applicationContext, projectPath)
                activeProjectTxtView?.text = "📁 ${template.projectName}"
                Toast.makeText(applicationContext, "✅ تم استيراد وتفعيل قالب: ${template.projectName}", Toast.LENGTH_LONG).show()
                hideTemplateDialogOverlay()
                
                val refreshIntent = Intent("com.example.ACTION_REFRESH_PROJECTS")
                refreshIntent.setPackage(packageName)
                sendBroadcast(refreshIntent)
            } else {
                Toast.makeText(applicationContext, "❌ فشل بناء مجلدات المشروع: ${buildResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(applicationContext, "❌ فشل تحليل القالب: ${parsedResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hideContextDecisionDialog() {
        contextDialogLayout?.visibility = View.GONE
        manualNameInputLayout?.visibility = View.GONE
        templateDialogLayout?.visibility = View.GONE
        rootLayout?.let { root ->
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child == contextDialogLayout || child == manualNameInputLayout || child == templateDialogLayout) {
                    child.visibility = View.GONE
                } else if (child is LinearLayout && child != contextDialogLayout && child != manualNameInputLayout && child != templateDialogLayout) {
                    child.visibility = View.VISIBLE
                    break
                }
            }
        }
    }

    private fun manualCollectClipboard() {
        try {
            val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
            val liveText = sharedPrefs.getString("live_clipboard_text", "") ?: ""
            
            var textToProcess = ""
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    textToProcess = clipData.getItemAt(0).text?.toString() ?: ""
                }
            }
            
            if (textToProcess.isBlank()) {
                textToProcess = liveText
            }
            
            if (textToProcess.isNotBlank()) {
                Toast.makeText(applicationContext, "🔄 جاري معالجة النص يدوياً...", Toast.LENGTH_SHORT).show()
                processClipboardContent(textToProcess, force = true)
            } else {
                Toast.makeText(applicationContext, "الحافظة فارغة بالكامل ولم يتم استقبال أي نص.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleMonitoringPause() {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val current = sharedPrefs.getBoolean("clipboard_is_paused", false)
        sharedPrefs.edit().putBoolean("clipboard_is_paused", !current).apply()

        val toastMsg = if (!current) "تم الإيقاف المؤقت للمراقبة." else "تم استئناف المراقبة بنشاط."
        Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
    }

    private fun launchMainApp() {
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "تعذر تشغيل التطبيق: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeGoldenView() {
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            rootLayout = null
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
        removeGoldenView()
        handler.removeCallbacks(clipboardRunnable)
        isPollingActive = false
        clipboardReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e("GoldenBubbleService", "Error unregistering receiver: ${e.message}")
            }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }
}
