package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * لوحة مفاتيح الأتمتة الذكية (Builder IME) لـ Android 10+
 *
 * تصميم مبسط برمجياً بالكامل بنسبة 100% تفادياً لأي مشاكل تتعلق بدورات حياة Compose
 * داخل نظام الإدخال بـ Android، ولضمان ثبات تام ونشاط مستمر دون أي خطر للانهيار.
 */
class BuilderIME : InputMethodService() {

    companion object {
        private const val TAG = "BuilderIME"
    }

    private var clipboardManager: ClipboardManager? = null
    private var database: AppDatabase? = null
    private val imeScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastKnownClipText = ""
    private var isPollingActive = false
    private val handler = Handler(Looper.getMainLooper())
    private var statusTextView: TextView? = null
    private var rootLayout: LinearLayout? = null
    private var isExplorerMode = false

    private val isPaused: Boolean
        get() {
            return try {
                getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                    .getBoolean("clipboard_is_paused", false)
            } catch (e: Exception) {
                false
            }
        }

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkClipboardInIME()
            handler.postDelayed(this, 1500L)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "clipboard_is_paused") {
            updatePollingState()
            updateUIStatus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            database = AppDatabase.getDatabase(this)
            getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener)
            Log.d(TAG, "BuilderIME onCreate initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
        }
    }

    private fun updatePollingState() {
        try {
            if (isPaused) {
                if (isPollingActive) {
                    handler.removeCallbacks(checkRunnable)
                    isPollingActive = false
                    Log.d(TAG, "IME Polling paused.")
                }
            } else {
                if (!isPollingActive) {
                    clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboardManager?.let { cm ->
                        if (cm.hasPrimaryClip()) {
                            val clip = cm.primaryClip
                            if (clip != null && clip.itemCount > 0) {
                                lastKnownClipText = clip.getItemAt(0).text?.toString() ?: ""
                            }
                        }
                    }
                    handler.post(checkRunnable)
                    isPollingActive = true
                    Log.d(TAG, "IME Polling resumed.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating polling state: ${e.message}")
        }
    }

    private fun getStatusString(): String {
        return if (isPaused) {
            "🔴 المراقبة التلقائية بالخلفية: متوقفة مؤقتاً\n(استخدم زر معالجة الحافظة للمسح اليدوي الآن)"
        } else {
            "🟢 المراقبة التلقائية بالخلفية: تعمل بنشاط\n(ستتم معالجة التوجيهات تلقائياً بمجرد نسخها)"
        }
    }

    private fun updateUIStatus() {
        handler.post {
            try {
                statusTextView?.text = getStatusString()
            } catch (e: Exception) {
                Log.e(TAG, "Error updating UI status: ${e.message}")
            }
        }
    }

    override fun onCreateInputView(): View {
        val context = this
        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F19")) // Elegant SuperDark Navy BG
            val pad = dpToPx(12, context)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(280, context) // Lock the keyboard height to standard 280dp
            )
        }

        rebuildIMEUI(context)

        // Make sure clipboard manager is ready
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            updatePollingState()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ClipboardManager in onCreateInputView: ${e.message}")
        }

        return rootLayout!!
    }

    private fun rebuildIMEUI(context: Context) {
        val layout = rootLayout ?: return
        layout.removeAllViews()

        if (isExplorerMode) {
            buildExplorerUI(context, layout)
        } else {
            buildDashboardUI(context, layout)
        }
    }

    private fun buildDashboardUI(context: Context, layout: LinearLayout) {
        // 1. Header Row
        val headerText = TextView(context).apply {
            text = "💻 بيئة التطوير المصغرة (المنصة الذكية)"
            setTextColor(Color.parseColor("#F59E0B")) // BrightGold
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(4, context))
        }
        layout.addView(headerText)

        // Project path description
        val pathText = TextView(context).apply {
            text = "مسار المشروع الافتراضي: SmartPlatform/"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(8, context))
        }
        layout.addView(pathText)

        // 2. Monitoring Status
        statusTextView = TextView(context).apply {
            text = getStatusString()
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12, context))
        }
        layout.addView(statusTextView)

        // 3. Status Stats Badge Row (Files count + active services)
        val statsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12, context))
        }
        
        val filesCount = getPhysicalFilesCount()
        val statsTxt = TextView(context).apply {
            text = "📊 عدد الملفات النشطة بالقرص: $filesCount ملف"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(10, context), dpToPx(6, context), dpToPx(10, context), dpToPx(6, context))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dpToPx(6, context).toFloat()
            }
        }
        statsLayout.addView(statsTxt)
        layout.addView(statsLayout)

        // 4. Action Buttons Container
        val actionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutParams = lp
        }

        // Left button: Open project explorer file list
        val expBtn = Button(context).apply {
            text = "📂 مستكشف شجرة المشروع"
            setTextColor(Color.WHITE)
            background = createButtonDrawable("#D97706") // Accent gold / orange
            textSize = 11f
            setOnClickListener {
                isExplorerMode = true
                rebuildIMEUI(context)
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dpToPx(6, context)
            }
            layoutParams = params
        }
        actionContainer.addView(expBtn)

        // Right button: Process clipboard manually
        val scanBtn = Button(context).apply {
            text = "⚡ معالجة الحافظة الآن"
            setTextColor(Color.WHITE)
            background = createButtonDrawable("#3B82F6") // Blue accent
            textSize = 11f
            setOnClickListener {
                forceScanClipboardManual()
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dpToPx(6, context)
            }
            layoutParams = params
        }
        actionContainer.addView(scanBtn)
        layout.addView(actionContainer)

        // 5. Back Navigation Button / Switch Keyboard Row
        val navigationRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(38, context)).apply {
                topMargin = dpToPx(10, context)
            }
            layoutParams = lp
        }

        val refreshBtn = Button(context).apply {
            text = "🔄 تحديث الإحصائيات"
            setTextColor(Color.parseColor("#94A3B8"))
            background = createButtonDrawable("#1E293B")
            textSize = 10f
            setOnClickListener {
                Toast.makeText(applicationContext, "🔄 تم تحديث إحصائيات المشروع والعداد بنجاح!", Toast.LENGTH_SHORT).show()
                rebuildIMEUI(context)
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                rightMargin = dpToPx(6, context)
            }
            layoutParams = params
        }
        navigationRow.addView(refreshBtn)

        val switchBtn = Button(context).apply {
            text = "🔄 التبديل للكيبورد السابق"
            setTextColor(Color.parseColor("#F59E0B"))
            background = createButtonDrawable("#1E293B")
            textSize = 10f
            setOnClickListener {
                switchBackToPreviousIME()
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                leftMargin = dpToPx(6, context)
            }
            layoutParams = params
        }
        navigationRow.addView(switchBtn)
        layout.addView(navigationRow)
    }

    private fun buildExplorerUI(context: Context, layout: LinearLayout) {
        // 1. Header Toolbar
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(6, context))
        }

        // Back button to Dashboard
        val backBtn = TextView(context).apply {
            text = "⬅️ العودة"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8, context), dpToPx(4, context), dpToPx(8, context), dpToPx(4, context))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dpToPx(4, context).toFloat()
            }
            setOnClickListener {
                isExplorerMode = false
                rebuildIMEUI(context)
            }
        }
        toolbar.addView(backBtn)

        val spacer = TextView(context).apply {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
        }
        toolbar.addView(spacer)

        val titleText = TextView(context).apply {
            text = "📂 مستكشف شجرة الملفات"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        }
        toolbar.addView(titleText)

        val spacer2 = TextView(context).apply {
            val lp = LinearLayout.LayoutParams(dpToPx(8, context), ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        }
        toolbar.addView(spacer2)

        val rfrBtn = TextView(context).apply {
            text = "🔄 تحديث"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8, context), dpToPx(4, context), dpToPx(8, context), dpToPx(4, context))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dpToPx(4, context).toFloat()
            }
            setOnClickListener {
                Toast.makeText(applicationContext, "🔄 جاري إعادة مسح مجلد المشروع...", Toast.LENGTH_SHORT).show()
                rebuildIMEUI(context)
            }
        }
        toolbar.addView(rfrBtn)
        layout.addView(toolbar)

        // Project path breadcrumb
        val breadcrumbText = TextView(context).apply {
            text = "المسار الحالي: SmartPlatform/"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 9f
            setPadding(0, 0, 0, dpToPx(4, context))
        }
        layout.addView(breadcrumbText)

        // 2. Scrollable File Tree Container
        val scrollView = android.widget.ScrollView(context).apply {
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            layoutParams = lp
            isVerticalScrollBarEnabled = true
        }

        val scrollContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Add file rows to scrollContainer
        addExplorerFilesToLayout(context, scrollContainer)

        scrollView.addView(scrollContainer)
        layout.addView(scrollView)
    }

    private fun addExplorerFilesToLayout(context: Context, container: LinearLayout) {
        val base = getBaseDir()
        if (!base.exists() || !base.isDirectory) {
            val noFileTxt = TextView(context).apply {
                text = "المجلد الرئيسي للمشروع فارغ أو غير موجود."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(24, context), 0, dpToPx(24, context))
            }
            container.addView(noFileTxt)
            return
        }

        val filesList = mutableListOf<File>()
        base.walkTopDown().forEach { file ->
            if (file.absolutePath == base.absolutePath) return@forEach
            val relativePath = file.relativeTo(base).path
            val parts = relativePath.split(File.separator)
            if (parts.none { it in BuilderEngine.IGNORE_DIRS }) {
                filesList.add(file)
            }
        }
        
        // Sort directories first, then files alphabetically
        filesList.sortWith(compareBy({ !it.isDirectory }, { it.name.lowercase(java.util.Locale.ROOT) }))

        if (filesList.isEmpty()) {
            val noFileTxt = TextView(context).apply {
                text = "لا توجد ملفات نشطة في المستكشف حالياً."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(24, context), 0, dpToPx(24, context))
            }
            container.addView(noFileTxt)
            return
        }

        for (file in filesList) {
            val itemPath = file.relativeTo(base).path
            val isDirectory = file.isDirectory
            
            // Count nesting level
            val nestingLevel = itemPath.count { it == File.separatorChar }

            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8, context) + (nestingLevel * dpToPx(8, context)), dpToPx(6, context), dpToPx(8, context), dpToPx(6, context))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#111827")) // Gray 900
                    cornerRadius = dpToPx(4, context).toFloat()
                }
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(4, context)
                }
                layoutParams = lp
            }

            // Icon + Name
            val nameTxt = TextView(context).apply {
                val icon = if (isDirectory) "📁 " else "📄 "
                text = "$icon${file.name}"
                setTextColor(if (isDirectory) Color.parseColor("#F59E0B") else Color.parseColor("#F1F5F9"))
                textSize = 11f
                typeface = if (isDirectory) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            itemLayout.addView(nameTxt)

            if (!isDirectory) {
                // File size display
                val sizeKb = file.length() / 1024.0
                val sizeStr = String.format(java.util.Locale.US, "%.1f KB", sizeKb)
                val sizeTxt = TextView(context).apply {
                    text = sizeStr
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 9f
                    setPadding(0, 0, dpToPx(6, context), 0)
                }
                itemLayout.addView(sizeTxt)

                // Open button
                val openBtn = TextView(context).apply {
                    text = "👁️ فتح"
                    setTextColor(Color.parseColor("#10B981")) // EmeraldGreen
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(6, context), dpToPx(4, context), dpToPx(6, context), dpToPx(4, context))
                    setOnClickListener {
                        openFileWithProvider(file)
                    }
                }
                itemLayout.addView(openBtn)

                // Copy button
                val copyBtn = TextView(context).apply {
                    text = "📋 نسخ"
                    setTextColor(Color.parseColor("#3B82F6")) // Blue
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(6, context), dpToPx(4, context), dpToPx(6, context), dpToPx(4, context))
                    setOnClickListener {
                        try {
                            val text = file.readText(Charsets.UTF_8)
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(file.name, text))
                            Toast.makeText(applicationContext, "تم نسخ محتوى ${file.name} إلى الحافظة!", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(applicationContext, "فشل نسخ الملف: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                itemLayout.addView(copyBtn)
            }

            // Delete button
            val delBtn = TextView(context).apply {
                text = "🗑️"
                setTextColor(Color.parseColor("#EF4444")) // Red
                textSize = 11f
                setPadding(dpToPx(6, context), dpToPx(4, context), dpToPx(6, context), dpToPx(4, context))
                setOnClickListener {
                    val typeStr = if (isDirectory) "المجلد" else "الملف"
                    try {
                        val success = file.deleteRecursively()
                        if (success) {
                            Toast.makeText(applicationContext, "تم حذف $typeStr ${file.name} بنجاح!", Toast.LENGTH_SHORT).show()
                            rebuildIMEUI(context)
                        } else {
                            Toast.makeText(applicationContext, "فشل حذف $typeStr.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(applicationContext, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            itemLayout.addView(delBtn)

            container.addView(itemLayout)
        }
    }

    private fun openFileWithProvider(file: File) {
        if (!file.exists()) {
            Toast.makeText(applicationContext, "الملف غير موجود.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val extension = file.extension.lowercase(java.util.Locale.ROOT)
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(applicationContext, "جاري فتح الملف: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "فشل تشغيل المعالج الافتراضي: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getPhysicalFilesCount(): Int {
        val base = getBaseDir()
        if (!base.exists()) return 0
        var count = 0
        try {
            base.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val relativePath = file.relativeTo(base).path
                    val parts = relativePath.split(File.separator)
                    if (parts.none { it in BuilderEngine.IGNORE_DIRS }) {
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error counting physical files: ${e.message}")
        }
        return count
    }

    private fun dpToPx(dp: Int, context: Context): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    private fun createButtonDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(colorHex))
            cornerRadius = dpToPx(8, this@BuilderIME).toFloat()
        }
    }

    private fun switchBackToPreviousIME() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            val token = window?.window?.attributes?.token ?: window?.window?.decorView?.windowToken
            if (token != null) {
                imm.switchToLastInputMethod(token)
            } else {
                showIMESelectionMenu(imm)
            }
        } catch (e: Exception) {
            showIMESelectionMenu(imm)
        }
    }

    private fun showIMESelectionMenu(imm: InputMethodManager) {
        try {
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "يرجى اختيار التبديل يدوياً من شريط تنقل الهاتف.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkClipboardInIME() {
        try {
            val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            if (manager == null || !manager.hasPrimaryClip()) return
            val clipData = manager.primaryClip ?: return
            if (clipData.itemCount == 0) return
            val text = clipData.getItemAt(0).text?.toString() ?: ""

            if (text.isNotBlank() && text != lastKnownClipText) {
                lastKnownClipText = text
                Log.d(TAG, "IME background check detected clipboard change.")
                onNewClipboardTextDetected(text)
            }
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    private fun forceScanClipboardManual() {
        try {
            val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            if (manager != null && manager.hasPrimaryClip()) {
                val clipData = manager.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotBlank()) {
                        Toast.makeText(applicationContext, "🔄 جاري المعالجة الفورية المباشرة...", Toast.LENGTH_SHORT).show()
                        processCopiedText(text)
                    } else {
                        Toast.makeText(applicationContext, "الحافظة فارغة حالياً.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(applicationContext, "الحافظة فارغة حالياً.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(applicationContext, "لم نتمكن من الوصول للحافظة حالياً.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(applicationContext, "فشل فحص الحافظة: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onNewClipboardTextDetected(text: String) {
        try {
            val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
            if (isPaused) return
            val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
            if (!isAutoProcess) return

            if (text.isBlank()) return

            val textHash = text.trim().hashCode().toString()
            val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
            if (textHash == lastProcessedHash) return

            val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
            val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
            val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

            val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
            if (text == lastProcessed) return

            if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:")) {
                processCopiedText(text)
            } else {
                if (text.trim().length >= 8) {
                    sharedPrefs.edit().putString("last_processed_text_hash", textHash).apply()
                    sharedPrefs.edit().putString("last_auto_processed_text", text).apply()
                    imeScope.launch(Dispatchers.Main) {
                        try {
                            Toast.makeText(applicationContext, "⚠️ نص منسوخ لا يحتوي على توجيهات حفظ؛ لم يتم الحفظ تلقائياً.", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {}
                    }
                    logSystemEvent("تجاوز النسخ (لا توجد توجيهات)", "تم تجاوز الحفظ لعدم العثور على بادئات نشطة في النص المنسوخ.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onNewClipboardTextDetected: ${e.message}")
        }
    }

    private fun processCopiedText(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val textHash = text.trim().hashCode().toString()
        val lastProcessedHash = sharedPrefs.getString("last_processed_text_hash", "")
        if (textHash == lastProcessedHash && text.isNotBlank()) {
            return
        }

        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
        if (text == lastProcessed && text.isNotBlank()) {
            return
        }
        sharedPrefs.edit().putString("last_processed_text_hash", textHash).apply()
        sharedPrefs.edit().putString("last_auto_processed_text", text).apply()

        imeScope.launch {
            logSystemEvent("توجيه مكتشف (IME)", "تم التقاط محتويات الحافظة عبر الكيبورد وتمريرها للمحرك...")

            try {
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
                val engine = BuilderEngine(this@BuilderIME, settings)

                val results = engine.processText(text)
                if (results.isEmpty()) {
                    logSystemEvent("معالجة IME فارغة", "لم يعثر المحرك على توجيهات صالحة.")
                    return@launch
                }

                var buildersCount = 0
                var executorsCount = 0
                var treedocCount = 0

                val db = database ?: AppDatabase.getDatabase(this@BuilderIME)
                for (res in results) {
                    when (res.type) {
                        "builder" -> {
                            buildersCount++
                            val path = res.data?.get("path") ?: "unknown"
                            val size = res.data?.get("size")?.toLongOrNull() ?: 0L
                            val mode = res.data?.get("mode") ?: "w"
                            val fullPath = res.data?.get("full_path") ?: ""
                            
                            db.dao().insertFile(
                                FileEntity(
                                    path = path,
                                    fullPath = fullPath,
                                    size = size,
                                    mode = mode
                                )
                            )
                            db.dao().insertLog(
                                LogEntity(
                                    type = "builder",
                                    message = "IME: تم إنشاء الملف: $path",
                                    details = res.message
                                )
                            )
                        }
                        "executor" -> {
                            executorsCount++
                            db.dao().insertLog(
                                LogEntity(
                                    type = "executor",
                                    message = "IME: تنفيذ أمر المنفذ",
                                    details = res.message
                                )
                            )
                        }
                        "treedoc" -> {
                            treedocCount++
                            db.dao().insertLog(
                                LogEntity(
                                    type = "treedoc",
                                    message = "IME: توليد تقرير TreeDoc الشجري",
                                    details = res.message
                                )
                            )
                        }
                    }
                }

                val summary = "IME: تم معالجة $buildersCount ملفات، $executorsCount أوامر، $treedocCount تقارير شجرية."
                logSystemEvent("نجاح معالجة IME", summary)

                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "✅ تم الحفظ والمعالجة التلقائية بنجاح من لوحة المفاتيح!", Toast.LENGTH_SHORT).show()
                }

                val clearClip = sharedPrefs.getBoolean("clear_clip_after_save", false)
                if (clearClip) {
                    withContext(Dispatchers.Main) {
                        try {
                            val manager = clipboardManager ?: (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                            if (manager != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    manager.clearPrimaryClip()
                                } else {
                                    manager.setPrimaryClip(ClipData.newPlainText("", ""))
                                }
                                Toast.makeText(applicationContext, "🧹 تم مسح وتفريغ الحافظة تلقائياً بنجاح.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing clipboard: ${e.message}")
                        }
                    }
                }

            } catch (e: Exception) {
                logSystemEvent("خطأ في معالجة IME", "فشل معالجة النص: ${e.message}")
            }
        }
    }

    private fun logSystemEvent(title: String, message: String) {
        try {
            imeScope.launch(Dispatchers.IO) {
                val db = database ?: AppDatabase.getDatabase(this@BuilderIME)
                db.dao().insertLog(
                    LogEntity(
                        type = "clipboard_ime",
                        message = title,
                        details = message
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error logging system event: ${e.message}")
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        updatePollingState()
        updateUIStatus()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {}
        handler.removeCallbacks(checkRunnable)
        isPollingActive = false
        imeScope.cancel()
        Log.d(TAG, "BuilderIME destroyed.")
    }
}
