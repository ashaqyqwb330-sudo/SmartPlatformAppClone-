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
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A")) // DeepNavyBG
            val pad = dpToPx(16, context)
            setPadding(pad, pad, pad, pad)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Title text header
        val headerText = TextView(context).apply {
            text = "لوحة تحكم المنصة الذكية"
            setTextColor(Color.parseColor("#F59E0B")) // BrightGold
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(6, context))
        }
        layout.addView(headerText)

        // Status text showing active monitoring
        statusTextView = TextView(context).apply {
            text = getStatusString()
            setTextColor(Color.parseColor("#E2E8F0")) // SoftSilver
            textSize = 13f
            setLineSpacing(1.2f, 1.2f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(16, context))
        }
        layout.addView(statusTextView)

        // Force scan clipboard button
        val scanBtn = Button(context).apply {
            text = "⚡ مسح ومعالجة الحافظة الحالية الآن"
            setTextColor(Color.WHITE)
            background = createButtonDrawable("#D97706") // MetallicGold
            textSize = 13f
            setPadding(dpToPx(12, context), dpToPx(10, context), dpToPx(12, context), dpToPx(10, context))
            setOnClickListener {
                forceScanClipboardManual()
            }
        }
        val scanParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(46, context)
        ).apply {
            bottomMargin = dpToPx(12, context)
        }
        layout.addView(scanBtn, scanParams)

        // Switch to last typing method button
        val switchBtn = Button(context).apply {
            text = "🔄 التبديل للوحة المفاتيح السابقة"
            setTextColor(Color.parseColor("#F59E0B")) // BrightGold
            background = createButtonDrawable("#1E293B") // SlateSurface
            textSize = 13f
            setPadding(dpToPx(12, context), dpToPx(10, context), dpToPx(12, context), dpToPx(10, context))
            setOnClickListener {
                switchBackToPreviousIME()
            }
        }
        val switchParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(46, context)
        )
        layout.addView(switchBtn, switchParams)

        // Make sure clipboard manager is ready
        try {
            clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            updatePollingState()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ClipboardManager in onCreateInputView: ${e.message}")
        }

        return layout
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
