package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import com.example.service.ClipboardMonitorService
import com.example.service.GeminiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val geminiService = GeminiService(context)

    // Prefix Settings
    val prefixBuilder = MutableStateFlow("@builder")
    val prefixExecutor = MutableStateFlow("@executor")
    val prefixTreedoc = MutableStateFlow("@treedoc")
    val baseDirSetting = MutableStateFlow("")

    // Running States
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isServicePaused = MutableStateFlow(false)
    val isServicePaused: StateFlow<Boolean> = _isServicePaused.asStateFlow()

    private val _autoProcessClipboard = MutableStateFlow(true)
    val autoProcessClipboard: StateFlow<Boolean> = _autoProcessClipboard.asStateFlow()

    private val _clearClipAfterSave = MutableStateFlow(false)
    val clearClipAfterSave: StateFlow<Boolean> = _clearClipAfterSave.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "clipboard_is_paused") {
            val isPaused = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)
            _isServicePaused.value = isPaused
        } else if (key == "auto_process_clipboard") {
            val isAutoVal = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("auto_process_clipboard", true)
            _autoProcessClipboard.value = isAutoVal
        } else if (key == "clear_clip_after_save") {
            val isClearVal = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clear_clip_after_save", false)
            _clearClipAfterSave.value = isClearVal
        }
    }

    private val _geminiLoading = MutableStateFlow(false)
    val geminiLoading: StateFlow<Boolean> = _geminiLoading.asStateFlow()

    private val _geminiResponse = MutableStateFlow("")
    val geminiResponse: StateFlow<String> = _geminiResponse.asStateFlow()

    private val _treedocReport = MutableStateFlow("")
    val treedocReport: StateFlow<String> = _treedocReport.asStateFlow()

    // Logs & Files streams
    val eventLogs = database.dao().getAllLogs()
    val createdFiles = database.dao().getAllCreatedFiles()

    // Internal File Browser State
    private val _currentBrowserPath = MutableStateFlow<File?>(null)
    val currentBrowserPath: StateFlow<File?> = _currentBrowserPath.asStateFlow()

    private val _browserFilesList = MutableStateFlow<List<File>>(emptyList())
    val browserFilesList: StateFlow<List<File>> = _browserFilesList.asStateFlow()

    init {
        loadSettings()
        checkServiceStatus()
        navigateToBaseDir()
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        _isServicePaused.value = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)
        _autoProcessClipboard.value = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("auto_process_clipboard", true)
        _clearClipAfterSave.value = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clear_clip_after_save", false)
    }

    private fun loadSettings() {
        val sharedPrefs = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        prefixBuilder.value = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
        prefixExecutor.value = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
        prefixTreedoc.value = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"
        baseDirSetting.value = sharedPrefs.getString("base_dir_path", getBaseDir().absolutePath) ?: getBaseDir().absolutePath
        _autoProcessClipboard.value = sharedPrefs.getBoolean("auto_process_clipboard", true)
        _clearClipAfterSave.value = sharedPrefs.getBoolean("clear_clip_after_save", false)
    }

    fun savePrefixes(builder: String, executor: String, treedoc: String) {
        prefixBuilder.value = builder.trim()
        prefixExecutor.value = executor.trim()
        prefixTreedoc.value = treedoc.trim()

        val sharedPrefs = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("prefix_builder", builder.trim())
            putString("prefix_executor", executor.trim())
            putString("prefix_treedoc", treedoc.trim())
            apply()
        }
        insertSystemLog("تحديث البادئات", "تم تغيير بادئات التوجيهات يدوياً بنجاح.")
    }

    fun saveBaseDir(path: String) {
        val file = File(path)
        if (!file.exists()) {
            file.mkdirs()
        }
        baseDirSetting.value = path
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit().putString("base_dir_path", path).apply()
        insertSystemLog("تغيير المجلد الافتراضي", "تم تغيير مسار مجلد الحفظ الافتراضي إلى: $path")
        navigateToBaseDir()
    }

    fun setAutoProcessClipboard(enabled: Boolean) {
        _autoProcessClipboard.value = enabled
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit().putBoolean("auto_process_clipboard", enabled).apply()
        insertSystemLog("تحديث معالجة الحافظة", "تم ضبط ميزة المعالجة التلقائية للحافظة إلى: ${if (enabled) "مفعّل" else "ملغى"}")
    }

    fun setClearClipAfterSave(enabled: Boolean) {
        _clearClipAfterSave.value = enabled
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit().putBoolean("clear_clip_after_save", enabled).apply()
        insertSystemLog("تحديث مسح الحافظة", "تم ضبط خيار مسح الحافظة بعد الحفظ التلقائي إلى: ${if (enabled) "مفعّل" else "ملغى"}")
    }

    fun checkServiceStatus() {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        val isRunning = am.getRunningServices(Integer.MAX_VALUE).any {
            it.service.className == ClipboardMonitorService::class.java.name
        }
        _isServiceRunning.value = isRunning
        _isServicePaused.value = context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).getBoolean("clipboard_is_paused", false)
    }

    fun toggleServicePause() {
        val nextParam = !_isServicePaused.value
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit().putBoolean("clipboard_is_paused", nextParam).apply()
        _isServicePaused.value = nextParam
        
        // Notify the running service if active
        val intent = Intent(context, ClipboardMonitorService::class.java).apply {
            action = if (nextParam) ClipboardMonitorService.ACTION_PAUSE else ClipboardMonitorService.ACTION_RESUME
        }
        context.startService(intent)
        
        if (nextParam) {
            insertSystemLog("إيقاف مؤقت للخدمة", "تم تعليق معالجة مدخلات الحافظة.")
        } else {
            insertSystemLog("استئناف الخدمة", "تم تنشيط معالجة السجلات الحافظة من جديد.")
        }
    }

    fun startMonitorService() {
        val intent = Intent(context, ClipboardMonitorService::class.java).apply {
            action = ClipboardMonitorService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        _isServiceRunning.value = true
        insertSystemLog("تشغيل الخدمة", "تم تفعيل خدمة مراقبة الحافظة من واجهة المستخدم.")
    }

    fun stopMonitorService() {
        val intent = Intent(context, ClipboardMonitorService::class.java).apply {
            action = ClipboardMonitorService.ACTION_STOP
        }
        context.startService(intent)
        _isServiceRunning.value = false
        insertSystemLog("إيقاف الخدمة", "تم تعطيل خدمة مراقبة الحافظة بنجاح.")
    }

    fun runManualProcess(text: String, onFinished: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            insertSystemLog("معالجة يدوية", "بدء تشغيل معالجة التوجيهات المدخلة يدوياً.")
            
            val settings = mapOf<String, Any>(
                "absolute_path_handling" to "relative",
                "base_dir" to baseDirSetting.value,
                "directive_prefixes" to listOf(prefixBuilder.value),
                "executor_prefixes" to listOf(prefixExecutor.value),
                "treedoc_prefixes" to listOf(prefixTreedoc.value)
            )
            val builderEngine = BuilderEngine(context, settings)

            try {
                val results = builderEngine.processText(text)
                if (results.isEmpty()) {
                    insertSystemLog("معالجة منتهية", "اكتملت المعالجة: لم تتوفر توجيهات صالحة.")
                    viewModelScope.launch(Dispatchers.Main) { onFinished("⚠️ لم يتم العثور على توجيهات صالحة.") }
                    return@launch
                }

                var buildersCount = 0
                var executorsCount = 0
                var treedocCount = 0

                for (res in results) {
                    when (res.type) {
                        "builder" -> {
                            buildersCount++
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
                                    message = "ملف يدوي تم إنشاؤه: $path",
                                    details = res.message
                                )
                            )
                        }
                        "executor" -> {
                            executorsCount++
                            database.dao().insertLog(
                                LogEntity(
                                    type = "executor",
                                    message = "تنفيذ أمر يدوي",
                                    details = res.message
                                )
                            )
                        }
                        "treedoc" -> {
                            treedocCount++
                            database.dao().insertLog(
                                LogEntity(
                                    type = "treedoc",
                                    message = "توليد تقرير شجري يدوي",
                                    details = res.message
                                )
                            )
                            _treedocReport.value = res.data?.get("report") ?: ""
                        }
                    }
                }

                val summary = "معالجة يدوية ناجحة: تم إنشاء $buildersCount ملفات، تنفيذ $executorsCount كتل أوامر، وتوليد $treedocCount كشوفات."
                insertSystemLog("نجاح المعالجة اليدوية", summary)
                viewModelScope.launch(Dispatchers.Main) {
                    onFinished("✅ نجحت معالجة التوجيهات بنجاح!\n\n$summary")
                }
                navigateToBaseDir() // Refresh local browser

            } catch (e: Exception) {
                insertSystemLog("فشل المعالجة", "خطأ أثناء المعالجة اليدوية: ${e.message}")
                viewModelScope.launch(Dispatchers.Main) {
                    onFinished("❌ فشلت المعالجة!\nالسبب: ${e.message}")
                }
            }
        }
    }

    fun executeSingleCommand(cmd: String, onFinished: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            insertSystemLog("أمر مباشر", "تنفيذ توجيه أمر فوري من المنفذ: $cmd")
            val settings = mapOf<String, Any>(
                "absolute_path_handling" to "relative",
                "base_dir" to baseDirSetting.value
            )
            val builderEngine = BuilderEngine(context, settings)
            val out = builderEngine.executeDirective(cmd)
            
            database.dao().insertLog(
                LogEntity(
                    type = "executor",
                    message = "نتيجة التنفيذ: $cmd",
                    details = out
                )
            )
            viewModelScope.launch(Dispatchers.Main) {
                onFinished(out)
            }
        }
    }

    fun generateTreeReport(folderName: String, format: String, copyToClip: Boolean, onFinished: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            insertSystemLog("توليد كشوفات شجرية", "بدء مسح المجلد $folderName بصيغة $format.")
            val settings = mapOf<String, Any>(
                "absolute_path_handling" to "relative",
                "base_dir" to baseDirSetting.value,
                "treedoc_prefixes" to listOf(prefixTreedoc.value)
            )
            val builderEngine = BuilderEngine(context, settings)
            val (msg, data) = builderEngine.runTreedoc(folderName, format, copyToClip)
            
            val report = data?.get("report") ?: ""
            _treedocReport.value = report

            database.dao().insertLog(
                LogEntity(
                    type = "treedoc",
                    message = "تقرير TreeDoc: $msg",
                    details = report
                )
            )
            viewModelScope.launch(Dispatchers.Main) {
                onFinished(msg)
            }
        }
    }

    fun sendGeminiRequest(prompt: String, onResponse: (String) -> Unit) {
        _geminiLoading.value = true
        insertSystemLog("طلب ذكاء اصطناعي", "إرسال طلب محادثة إلى Gemini: $prompt")
        viewModelScope.launch {
            val result = geminiService.generateContent(prompt)
            _geminiLoading.value = false
            result.onSuccess { responseText ->
                _geminiResponse.value = responseText
                database.dao().insertLog(
                    LogEntity(
                        type = "gemini",
                        message = "استجابة Gemini بنجاح",
                        details = responseText
                    )
                )
                onResponse(responseText)
            }.onFailure { err ->
                _geminiResponse.value = "❌ فشل الطلب: ${err.message}"
                database.dao().insertLog(
                    LogEntity(
                        type = "gemini",
                        message = "فشل طلب جمناي",
                        details = err.message
                    )
                )
                onResponse("❌ فشل الاتصال بالنموذج ذكياً!")
            }
        }
    }

    fun clearDatabaseLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().clearLogs()
            insertSystemLog("تطهير السجل", "تم حذف سجلات الأحداث التاريخية بنجاح.")
        }
    }

    fun clearCreatedFilesList() {
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().clearCreatedFiles()
            insertSystemLog("حذف الملفات من التطبيق", "تم تنظيف قائمة الملفات المرجعية المسجلة بنشاط.")
        }
    }

    // --- Directory Browser Internal Mechanics ---
    fun navigateToBaseDir() {
        val root = File(baseDirSetting.value)
        if (!root.exists()) {
            root.mkdirs()
        }
        navigateToDir(root)
    }

    fun navigateToDir(directory: File) {
        var target = directory
        if (target.absolutePath == "/") {
            target = File("/storage/emulated/0")
        }
        if (!target.exists()) {
            try {
                target.mkdirs()
            } catch (e: Exception) {}
        }
        if (!target.exists() || !target.isDirectory) {
            target = File("/storage/emulated/0")
        }
        _currentBrowserPath.value = target
        val files = target.listFiles()?.filter {
            !it.name.startsWith(".") && it.name != "tree_report.txt" && it.name != "tree_report.json"
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        _browserFilesList.value = files
    }

    fun navigateUp() {
        val current = _currentBrowserPath.value ?: return
        val parent = current.parentFile
        if (parent != null) {
            val resolvedParent = if (parent.absolutePath == "/") File("/storage/emulated/0") else parent
            navigateToDir(resolvedParent)
        }
    }

    fun createDirectoryInBrowser(name: String) {
        val current = _currentBrowserPath.value ?: return
        val dir = File(current, name)
        if (!dir.exists()) {
            dir.mkdirs()
            insertSystemLog("تم إنشاء مجلد", "إنشاء مجلد فرعي جديد بنجاح: ${dir.name}")
            navigateToDir(current) // Refresh
        }
    }

    fun deleteFileFromBrowser(file: File) {
        if (file.exists()) {
            val isDeleted = file.deleteRecursively()
            if (isDeleted) {
                insertSystemLog("حذف ملف/مجلد", "تم حذف المرجع بنجاح: ${file.name}")
                _currentBrowserPath.value?.let { navigateToDir(it) }
            }
        }
    }

    fun getGeminiKeyAvailable(): Boolean {
        return geminiService.getApiKey().isNotBlank()
    }

    fun setCustomGeminiKey(key: String) {
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).edit()
            .putString("custom_gemini_api_key", key.trim())
            .apply()
        insertSystemLog("مفتاح API مخصص", "تم إرفاق مفتاح للذكاء الاصطناعي بنجاح.")
    }

    override fun onCleared() {
        super.onCleared()
        context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun insertSystemLog(title: String, message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            database.dao().insertLog(
                LogEntity(
                    type = "system",
                    message = title,
                    details = message
                )
            )
        }
    }

    private fun getBaseDir(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(context.getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(context.filesDir, "SmartPlatform")
        }.also { it.mkdirs() }
    }
}
