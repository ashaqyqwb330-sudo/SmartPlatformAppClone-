package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.MainActivity
import com.example.db.AppDatabase
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * الفقاعة العائمة الذكية (Bubble Overlay Service) - مركز القيادة العائم لجميع الإصدارات
 *
 * يوفر وصولاً سريعاً، فحصاً يدوياً/تلقائياً مستمراً، ومؤشر حالة بصري ملون،
 * مع إمكانية التمدد للوحة تحكم مصغرة تمكن من التحكم بالخدمة وعرض إحصائيات مجلد العمل.
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
            // Silent block for constraints on modern Android systems when window is not focused
        }
    }

    private fun onNewClipboardTextDetected(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
        if (!isAutoProcess) return

        val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
        val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
        val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

        if (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:")) {
            processClipboardContent(text)
        }
    }

    private fun processClipboardContent(text: String) {
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
        if (text == lastProcessed && text.isNotBlank()) return
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

            val frameLayout = FrameLayout(this).apply {
                setViewTreeLifecycleOwner(this@BubbleService)
                setViewTreeViewModelStoreOwner(this@BubbleService)
                setViewTreeSavedStateRegistryOwner(this@BubbleService)
            }

            val composeView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    var isExpanded by remember { mutableStateOf(false) }
                    
                    if (isExpanded) {
                        BubbleExpandedCard(
                            onCollapse = { isExpanded = false },
                            onDrag = { dx, dy ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                try {
                                    windowManager.updateViewLayout(frameLayout, params)
                                } catch (e: Exception) {}
                            }
                        )
                    } else {
                        BubbleCollapsedDot(
                            onExpand = { isExpanded = true },
                            onDrag = { dx, dy ->
                                params.x += dx.toInt()
                                params.y += dy.toInt()
                                try {
                                    windowManager.updateViewLayout(frameLayout, params)
                                } catch (e: Exception) {}
                            }
                        )
                    }
                }
            }

            val recomposer = Recomposer(Dispatchers.Main)
            composeView.setParentCompositionContext(recomposer)
            serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    recomposer.runRecomposeAndApplyChanges()
                } catch (e: Exception) {
                    Log.e("BubbleService", "Recomposer error: ${e.message}")
                }
            }

            frameLayout.addView(composeView)
            floatingView = frameLayout

            windowManager.addView(frameLayout, params)
            
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        } catch (e: Exception) {
            Log.e("BubbleService", "Error displaying bubble overlay: ${e.message}", e)
            try {
                Toast.makeText(applicationContext, "فشل تشغيل الكرة العائمة: ${e.localizedMessage ?: e.message}", Toast.LENGTH_LONG).show()
                removeFloatingView()
                stopSelf()
            } catch (ex: Exception) {
                Log.e("BubbleService", "Error inside catch block: ${ex.message}")
            }
        }

        return START_STICKY
    }

    @Composable
    fun BubbleCollapsedDot(onExpand: () -> Unit, onDrag: (Float, Float) -> Unit) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFFFD700), Color(0xFFB8860B)),
                    )
                )
                .border(1.5.dp, Color.White, CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { /* silent start */ },
                        onDragEnd = { /* silent completed */ },
                        onDragCancel = { /* silent cancel */ },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        }
                    )
                }
                .clickable { onExpand() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "قائمة الأتمتة",
                tint = Color.Black,
                modifier = Modifier.size(26.dp)
            )
        }
    }

    @Composable
    fun BubbleExpandedCard(onCollapse: () -> Unit, onDrag: (Float, Float) -> Unit) {
        val createdFilesFlow = remember { database.dao().getAllCreatedFiles() }
        val logsFlow = remember { database.dao().getAllLogs() }

        val createdFiles by createdFilesFlow.collectAsState(initial = emptyList())
        val logs by logsFlow.collectAsState(initial = emptyList())

        val baseDir = getBaseDir()
        val totalFilesCount = remember(createdFiles) { createdFiles.distinctBy { it.path }.size }
        val lastAction = remember(logs) { logs.firstOrNull()?.message ?: "لا توجد عمليات نشطة" }

        val pIsPaused = remember { mutableStateOf(isPaused) }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .width(260.dp)
                .border(
                    width = 1.2.dp,
                    brush = Brush.horizontalGradient(listOf(Color(0xFFFFD700), Color(0xFFB8860B))),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Header (Drag Area)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(dragAmount.x, dragAmount.y)
                                }
                            )
                        }
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(16.dp))
                        Text(
                            text = "منصة الأتمتة",
                            color = Color(0xFFFFD700),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = { onCollapse() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "تصغير", tint = Color.LightGray, modifier = Modifier.size(16.dp))
                    }
                }

                Divider(color = Color(0xFF334155))

                Spacer(modifier = Modifier.height(8.dp))

                // Monitoring status row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("المراقبة الفورية:", color = Color.Gray, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (pIsPaused.value) Color.Red else Color.Green)
                        )
                        Text(
                            text = if (pIsPaused.value) "متوقف" else "نشط",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // File stats and layout info
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("المجلد: ${baseDir.name}", color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("الملفات: $totalFilesCount", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Last action log
                Text(
                    text = "آخر إجراء: $lastAction",
                    color = Color.LightGray,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(Color(0xFF0F172A), RoundedCornerShape(4.dp)).fillMaxWidth().padding(4.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Actions buttons
                Button(
                    onClick = { manualTriggerClipboardFromBubble() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        Text("⚡ معالجة الحافظة الآن", fontSize = 10.sp, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Toggle Active state button
                    Button(
                        onClick = {
                            toggleClipboardServicePause()
                            pIsPaused.value = isPaused
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).height(28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = if (pIsPaused.value) "استئناف" else "إيقاف", fontSize = 9.sp, color = Color.White)
                    }

                    // Open App button
                    Button(
                        onClick = { launchAppMain() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).height(28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = "الإعدادات", fontSize = 9.sp, color = Color.White)
                    }

                    // Self Hide Bubble button
                    Button(
                        onClick = {
                            removeFloatingView()
                            stopSelf()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).height(28.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = "إغلاق", fontSize = 9.sp, color = Color.White)
                    }
                }
            }
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
