package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.example.db.AppDatabase
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File
import kotlin.math.abs

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

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        savedStateRegistryController.performRestore(null)
        Log.d("BubbleService", "BubbleService created.")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            removeFloatingView()
            stopSelf()
            return START_NOT_STICKY
        }

        if (floatingView != null) return START_STICKY

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
            x = 100
            y = 100
        }

        val frameLayout = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@BubbleService)
            setViewTreeViewModelStoreOwner(this@BubbleService)
            setViewTreeSavedStateRegistryOwner(this@BubbleService)
        }

        val composeView = ComposeView(this).apply {
            setContent {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFFFFD700), Color(0xFFB8860B)),
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "التقرير الذهبي",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        frameLayout.addView(composeView)
        floatingView = frameLayout

        frameLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0.0f
            private var initialTouchY = 0.0f
            private var touchTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        touchTime = System.currentTimeMillis()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(frameLayout, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = event.rawX - initialTouchX
                        val diffY = event.rawY - initialTouchY
                        val duration = System.currentTimeMillis() - touchTime
                        
                        if (abs(diffX) < 10 && abs(diffY) < 10 && duration < 300) {
                            onBubbleClicked()
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(frameLayout, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        } catch (e: Exception) {
            Log.e("BubbleService", "Error displaying bubble overlay: ${e.message}")
            stopSelf()
        }

        return START_STICKY
    }

    private fun onBubbleClicked() {
        Toast.makeText(applicationContext, "⚡ جاري المسح السريع عبر الكرة الذهبية...", Toast.LENGTH_SHORT).show()
        
        serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            
            val baseDir = getExternalFilesDir(null) ?: filesDir
            val smartPlatformDir = File(baseDir, "SmartPlatform")
            if (!smartPlatformDir.exists()) {
                smartPlatformDir.mkdirs()
            }

            val settings = mapOf(
                "base_dir" to smartPlatformDir.absolutePath,
                "directive_prefixes" to listOf("@builder"),
                "executor_prefixes" to listOf("@executor"),
                "treedoc_prefixes" to listOf("@treedoc"),
                "absolute_path_handling" to "relative"
            )

            val engine = BuilderEngine(applicationContext, settings)

            withContext(Dispatchers.IO) {
                try {
                    val resultPair = engine.runTreedoc("SmartPlatform", "txt", true)
                    db.dao().insertLog(
                        LogEntity(
                            type = "treedoc",
                            message = "الكرة العائمة: تم التوليد التلقائي للتقرير الشجري ونسخه للمذكرة."
                        )
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            applicationContext,
                            "تم نسخ التقرير الشجري لـ 'SmartPlatform' عبر الفقاعة المعلقة!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    db.dao().insertLog(
                        LogEntity(
                            type = "system",
                            message = "فشل توليد التقرير من الكرة المعلقة: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    private fun removeFloatingView() {
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
            floatingView = null
        }
    }

    override fun onDestroy() {
        removeFloatingView()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }
}
