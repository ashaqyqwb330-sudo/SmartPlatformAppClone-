package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.db.FileEntity
import com.example.db.LogEntity
import com.example.service.ClipboardMonitorService
import com.example.service.ClipboardAccessibilityService
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.os.PowerManager
import android.os.Environment

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = "${context.packageName}/${ClipboardAccessibilityService::class.java.canonicalName}"
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
    return enabled.contains(service) || enabled.contains("ClipboardAccessibilityService")
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Dynamic orientation support and status bar adjustment
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = SlateBg
                ) {
                    val sharedPrefs = LocalContext.current.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
                    var simulatedGoldenFrame by remember {
                        mutableStateOf(sharedPrefs.getBoolean("simulate_golden_frame", true))
                    }

                    MainAppContent(
                        viewModel = viewModel,
                        simulatedGoldenFrame = simulatedGoldenFrame,
                        onToggleSimulatedFrame = { newState ->
                            simulatedGoldenFrame = newState
                            sharedPrefs.edit().putBoolean("simulate_golden_frame", newState).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkServiceStatus()
        viewModel.navigateToBaseDir() // refresh browser

        // Check clipboard for auto-processing on application focus
        val sharedPrefs = getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE)
        val isAutoProcess = sharedPrefs.getBoolean("auto_process_clipboard", true)
        if (isAutoProcess) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text?.toString() ?: ""
                    val pBuilder = sharedPrefs.getString("prefix_builder", "@builder") ?: "@builder"
                    val pExecutor = sharedPrefs.getString("prefix_executor", "@executor") ?: "@executor"
                    val pTreedoc = sharedPrefs.getString("prefix_treedoc", "@treedoc") ?: "@treedoc"

                    if (text.isNotBlank() && (text.contains("$pBuilder:") || text.contains("$pExecutor:") || text.contains("$pTreedoc:"))) {
                        val lastProcessed = sharedPrefs.getString("last_auto_processed_text", "") ?: ""
                        if (text != lastProcessed) {
                            sharedPrefs.edit().putString("last_auto_processed_text", text).apply()
                            Toast.makeText(this, "📋 تم اكتشاف توجيهات جديدة في الحافظة! جاري المعالجة التلقائية وحفظها...", Toast.LENGTH_LONG).show()
                            viewModel.runManualProcess(text) { resultMsg ->
                                Toast.makeText(this, resultMsg, Toast.LENGTH_SHORT).show()
                                viewModel.navigateToBaseDir() // Refresh file list
                            }
                        }
                    }
                }
            }
        }
    }
}

// Tab selections state representation
enum class MainTab(val ltrTitle: String, val rtlTitle: String) {
    MONITOR("Monitor", "المراقب"),
    TREEDOC("TreeDoc", "الشجرية"),
    EXECUTOR("Executor", "المنفذ"),
    GEMINI("Gemini AI", "جمناي الذكي"),
    SETTINGS("Settings", "الإعدادات")
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainAppContent(
    viewModel: MainViewModel,
    simulatedGoldenFrame: Boolean,
    onToggleSimulatedFrame: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var currentTab by remember { mutableStateOf(MainTab.MONITOR) }
    val scope = rememberCoroutineScope()

    // Permissions State variables
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showPermissionsDashboard by remember { mutableStateOf(false) }
    var hasPostNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Permission launcher
    val requestNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPostNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "تم منح صلاحية الإشعارات بنجاح!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val isAccessibility = isAccessibilityServiceEnabled(context)
        val isAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isBattery = pm.isIgnoringBatteryOptimizations(context.packageName)
        val isOverlay = Settings.canDrawOverlays(context)

        if (!isAccessibility || !isAllFiles || !isBattery || !isOverlay) {
            showPermissionsDashboard = true
        } else if (!hasPostNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            showPermissionDialog = true
        }
    }

    // Layout frame wrapper
    val frameModifier = if (simulatedGoldenFrame) {
        Modifier
            .fillMaxSize()
            .padding(8.dp)
            .border(3.2.dp, Brush.linearGradient(listOf(MetallicGold, BrightGold, DarkGold)), RoundedCornerShape(42.dp))
            .shadow(12.dp, RoundedCornerShape(42.dp))
            .clip(RoundedCornerShape(42.dp))
            .background(SlateBg)
    } else {
        Modifier.fillMaxSize()
    }

    Box(modifier = Modifier.fillMaxSize().background(SlateBg)) {
        Column(modifier = frameModifier) {
            // Android Status Bar Simulation if golden frame is active
            if (simulatedGoldenFrame) {
                SimulatedStatusBar(isServiceActive = viewModel.isServiceRunning.collectAsState().value)
            }

            // Interactive Dynamic App Header
            AppHeader(isServiceRunning = viewModel.isServiceRunning.collectAsState().value)

            // Dynamic Main Screen Switcher
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                when (currentTab) {
                    MainTab.MONITOR -> MonitorScreen(viewModel)
                    MainTab.TREEDOC -> TreeDocScreen(viewModel)
                    MainTab.EXECUTOR -> ExecutorScreen(viewModel)
                    MainTab.GEMINI -> GeminiScreen(viewModel)
                    MainTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        goldenFrameEnabled = simulatedGoldenFrame,
                        onToggleGoldenFrame = onToggleSimulatedFrame
                    )
                }
            }

            // Bottom Navigation bar
            AppBottomNavigation(
                currentTab = currentTab,
                onTabSelected = { currentTab = it }
            )
        }

        // Floating Interactive Golden Bubble (in-app version for consistent reliable Web preview streaming)
        var showBubbleDialog by remember { mutableStateOf(false) }
        var isReportingBubble by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 90.dp, end = 20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .background(
                        Brush.radialGradient(listOf(BrightGold, MetallicGold, DarkGold)),
                        CircleShape
                    )
                    .clickable {
                        isReportingBubble = true
                        viewModel.generateTreeReport(".", "txt", true) { msg ->
                            isReportingBubble = false
                            Toast
                                .makeText(context, "الفقاعة الذكية: تم نسخ تقرير الشجرة للحافظة!", Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                    .testTag("floating_bubble"),
                contentAlignment = Alignment.Center
            ) {
                if (isReportingBubble) {
                    CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Quick Report Bubble",
                        tint = SlateBg,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
        }

        // Explanation dialogue for permissions
        PermissionsDashboardDialog(
            isOpen = showPermissionsDashboard,
            onDismiss = { showPermissionsDashboard = false },
            viewModel = viewModel
        )

        if (showPermissionDialog) {
            Dialog(
                onDismissRequest = { showPermissionDialog = false },
                properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(CardSlateBg, RoundedCornerShape(28.dp))
                        .border(1.dp, GlassBorder, RoundedCornerShape(28.dp))
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(GoldGlassBg, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = MetallicGold, modifier = Modifier.size(28.dp))
                        }
                        
                        Spacer(modifier = Modifier.height(18.dp))
                        
                        Text(
                            text = "طلب صلاحية الإشعارات",
                            color = TextSilver,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(10.dp))
                        
                        Text(
                            text = "تطلب المنصة الذكية تفعيل الإشعارات لتتمكن من إظهار حالة المحاكي قيد التشغيل في الخلفية ومراقبة وتحديث الملفات المكتوبة فوراً.",
                            color = TextGray,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            textAlign = TextAlign.Center
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = { showPermissionDialog = false }) {
                                Text("تخطي", color = TextGray)
                            }
                            
                            Button(
                                onClick = {
                                    showPermissionDialog = false
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        requestNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text("السماح بالوصول", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Extension to avoid styling shadow helper error compiler references
fun Modifier.shadowGradient(color: Color = Color.Black, size: Dp = 10.dp): Modifier = this

// =====================================================================
// 1. Simulated Android Phone Elements (Frosted Glass and Mock Status Bar)
// =====================================================================

@Composable
fun SimulatedStatusBar(isServiceActive: Boolean) {
    val formatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var timeString by remember { mutableStateOf(formatter.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            timeString = formatter.format(Date())
            kotlinx.coroutines.delay(10000)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, end = 28.dp, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = timeString,
            color = TextSilver,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )

        // Rounded Speaker Mock
        Box(
            modifier = Modifier
                .width(55.dp)
                .height(6.dp)
                .background(Color(0xFF2E3033), RoundedCornerShape(3.dp))
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // Service Status Dot Glow
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(if (isServiceActive) EmeraldGlow else TextMuted, CircleShape)
            )

            // Simulated Battery
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .border(1.dp, TextSilver.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                    .padding(1.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(13.dp)
                        .height(6.dp)
                        .background(if (isServiceActive) EmeraldGlow else TextSilver, RoundedCornerShape(1.dp))
                )
            }
        }
    }
}

@Composable
fun AppHeader(isServiceRunning: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "المنصة الذكية",
                color = TextSilver,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Smart Platform Engine",
                color = MetallicGold,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        Box(
            modifier = Modifier
                .size(38.dp)
                .background(GlassWhite, CircleShape)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isServiceRunning) EmeraldGlow else DangerRed, CircleShape)
            )
        }
    }
}

@Composable
fun AppBottomNavigation(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardSlateBg)
            .border(BorderStroke(0.6.dp, GlassBorder))
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainTab.values().forEach { tab ->
            val isSelected = currentTab == tab
            val tintColor = if (isSelected) MetallicGold else TextGray.copy(alpha = 0.6f)
            val backgroundAccent = if (isSelected) GoldGlassBg else Color.Transparent

            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(backgroundAccent)
                    .clickable { onTabSelected(tab) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("tab_${tab.name.lowercase()}"),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = getTabIcon(tab),
                    contentDescription = tab.rtlTitle,
                    tint = tintColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = tab.rtlTitle,
                    color = tintColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun getTabIcon(tab: MainTab): ImageVector {
    return when (tab) {
        MainTab.MONITOR -> Icons.Default.Home
        MainTab.TREEDOC -> Icons.Default.List
        MainTab.EXECUTOR -> Icons.Default.PlayArrow
        MainTab.GEMINI -> Icons.Default.Star
        MainTab.SETTINGS -> Icons.Default.Settings
    }
}

// =====================================================================
// 2. MONITOR TAB - Clipboard Monitoring list, Text processing, Files & Logs Tables
// =====================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(viewModel: MainViewModel) {
    var manualText by remember { mutableStateOf("") }
    var operationResultMsg by remember { mutableStateOf("") }
    var showResultDialog by remember { mutableStateOf(false) }
    var isProcessingManual by remember { mutableStateOf(false) }

    // File Editor State
    var editingFileEntity by remember { mutableStateOf<FileEntity?>(null) }
    var editingFileContent by remember { mutableStateOf("") }
    var showFileEditorDialog by remember { mutableStateOf(false) }

    val isServiceRunning = viewModel.isServiceRunning.collectAsState().value
    val isServicePaused = viewModel.isServicePaused.collectAsState().value
    val createdFiles = viewModel.createdFiles.collectAsState(initial = emptyList()).value
    val eventLogs = viewModel.eventLogs.collectAsState(initial = emptyList()).value

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Status Widget
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(if (isServiceRunning) GoldGlassBg else GlassWhite, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isServiceRunning) MetallicGold else TextGray,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "مراقبة الحافظة",
                                color = TextSilver,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isServiceRunning) {
                                    if (isServicePaused) "المراقبة متوقفة مؤقتاً ⏸️" else "نشط ومتحفز للتوجيهات @"
                                } else "المراقبة متوقفة في الوقت الحالي",
                                color = if (isServiceRunning) {
                                    if (isServicePaused) Color(0xFFFF9800) else EmeraldGlow
                                } else TextGray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isServiceRunning) {
                            TextButton(
                                onClick = {
                                    viewModel.toggleServicePause()
                                    if (isServicePaused) {
                                        Toast.makeText(context, "تم استئناف المراقبة بنجاح", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "تم إيقاف المراقبة مؤقتاً", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isServicePaused) EmeraldGlow else Color(0xFFFF9800)
                                ),
                                modifier = Modifier.testTag("pause_resume_button")
                            ) {
                                Text(
                                    text = if (isServicePaused) "استئناف" else "إيقاف مؤقت",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Switch(
                            checked = isServiceRunning,
                            onCheckedChange = { isChecked ->
                                if (isChecked) {
                                    viewModel.startMonitorService()
                                    Toast.makeText(context, "تم تفعيل مراقبة الحافظة في الخلفية", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.stopMonitorService()
                                    Toast.makeText(context, "تم إيقاف خدمة مراقبة الحافظة", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SlateBg,
                                checkedTrackColor = MetallicGold,
                                uncheckedThumbColor = TextGray,
                                uncheckedTrackColor = GlassWhite
                            ),
                            modifier = Modifier.testTag("service_switch")
                        )
                    }
                }
            }
        }

        // Direct Manual Directives Paste Box
        item {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "منفذ التوجيهات اليدوي",
                            color = MetallicGold,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = clipboard.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    manualText = clip.getItemAt(0).text?.toString() ?: ""
                                    Toast.makeText(context, "تم لصق محتويات الحافظة!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "الحافظة فارغة حالياً.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Paste Clipboard", tint = TextGray, modifier = Modifier.size(16.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = manualText,
                        onValueChange = { manualText = it },
                        placeholder = {
                            Text(
                                text = "الصق التوجيه هنا...\nمثال:\n// @builder:file code.py\nprint('Hello World')\n// @builder:end",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .testTag("manual_input_field"),
                        textStyle = TextStyle(color = TextSilver, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold.copy(alpha = 0.5f),
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = GlassBlack,
                            unfocusedContainerColor = GlassBlack
                        ),
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (manualText.isBlank()) {
                                Toast.makeText(context, "الرجاء إدخال نص التوجيه أولاً", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isProcessingManual = true
                            viewModel.runManualProcess(manualText) { res ->
                                isProcessingManual = false
                                operationResultMsg = res
                                showResultDialog = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("process_directives_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isProcessingManual) {
                            CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("معالجة النص والتعليمات", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Created Files Grid List
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "آخر الملفات المكتوبة",
                        color = TextSilver,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (createdFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearCreatedFilesList() }) {
                            Text("تصفير القائمة", color = DangerRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (createdFiles.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(GlassWhite, RoundedCornerShape(20.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("لا توجد ملفات منشأة في السجلات حالياً.", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 2
                    ) {
                        val limitValues = createdFiles.take(8)
                        limitValues.forEach { fileEntity ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .background(CardSlateBg, RoundedCornerShape(18.dp))
                                    .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                                    .clickable {
                                        // Open inline File Editor Dialogue!
                                        val realFile = File(fileEntity.fullPath)
                                        if (realFile.exists()) {
                                            try {
                                                editingFileContent = realFile.readText()
                                                editingFileEntity = fileEntity
                                                showFileEditorDialog = true
                                            } catch (e: Exception) {
                                                Toast
                                                    .makeText(context, "فشل قراءة الملف: ${e.message}", Toast.LENGTH_SHORT)
                                                    .show()
                                            }
                                        } else {
                                            Toast
                                                .makeText(context, "الملف لم يعد متواجداً في المسار الحقيقي.", Toast.LENGTH_SHORT)
                                                .show()
                                        }
                                    }
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(GoldGlassBg, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Build, contentDescription = null, tint = MetallicGold, modifier = Modifier.size(15.dp))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = fileEntity.path,
                                        color = TextSilver,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${fileEntity.size} Bytes • ${if(fileEntity.mode == "w") "كتابة" else "إلحاق"}",
                                        color = TextGray,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Live Event Logs List (سجل الأحداث)
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "سجل الأحداث الحية",
                        color = TextSilver,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (eventLogs.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearDatabaseLogs() }) {
                            Text("مسح السجل", color = DangerRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (eventLogs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(GlassWhite, RoundedCornerShape(20.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("سجل الأحداث نظيف وفارغ.", color = TextMuted, fontSize = 12.sp)
                    }
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        eventLogs.take(15).forEach { log ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(GlassWhite, RoundedCornerShape(14.dp))
                                    .border(1.dp, GlassBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(
                                                when (log.type) {
                                                    "builder" -> Color(0x203B82F6)
                                                    "executor" -> Color(0x20F59E0B)
                                                    "treedoc" -> Color(0x2084CC16)
                                                    "gemini" -> Color(0x208B5CF6)
                                                    else -> GoldGlassBg
                                                },
                                                CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when (log.type) {
                                                "builder" -> Icons.Default.Create
                                                "executor" -> Icons.Default.PlayArrow
                                                "treedoc" -> Icons.Default.List
                                                "gemini" -> Icons.Default.Star
                                                else -> Icons.Default.Info
                                            },
                                            contentDescription = null,
                                            tint = when (log.type) {
                                                "builder" -> Color(0xFF60A5FA)
                                                "executor" -> Color(0xFFFBBF24)
                                                "treedoc" -> Color(0xFFA3E635)
                                                "gemini" -> Color(0xFFA78BFA)
                                                else -> MetallicGold
                                            },
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(log.message, color = TextSilver, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        if (!log.details.isNullOrBlank()) {
                                            Text(log.details, color = TextGray, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                    }

                                    Text(
                                        text = SimpleDateFormat("mm:ss", Locale.getDefault()).format(Date(log.timestamp)),
                                        color = TextMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal outcomes
    if (showResultDialog) {
        Dialog(onDismissRequest = { showResultDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSlateBg, RoundedCornerShape(24.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "تفاصيل العملية والمخرجات",
                        color = MetallicGold,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = operationResultMsg,
                        color = TextSilver,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 250.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showResultDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("حسنًا", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Interactive Inline File Editor Dialog
    if (showFileEditorDialog && editingFileEntity != null) {
        Dialog(onDismissRequest = { showFileEditorDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSlateBg, RoundedCornerShape(24.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "محرر الملفات: ${editingFileEntity?.path}",
                        color = MetallicGold,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "المسار الكامل: ${editingFileEntity?.fullPath}",
                        color = TextMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = editingFileContent,
                        onValueChange = { editingFileContent = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        textStyle = TextStyle(color = TextSilver, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = GlassBlack,
                            unfocusedContainerColor = GlassBlack
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                editingFileEntity?.let {
                                    val f = File(it.fullPath)
                                    if (f.exists()) {
                                        f.delete()
                                    }
                                    scope.launch {
                                        viewModel.clearCreatedFilesList() // refresh
                                    }
                                    showFileEditorDialog = false
                                    Toast.makeText(context, "تم حذف الملف الحقيقي بنجاح", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Text("حذف الملف", color = DangerRed, fontWeight = FontWeight.Bold)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { showFileEditorDialog = false }) {
                                Text("إلغاء", color = TextGray)
                            }

                            Button(
                                onClick = {
                                    editingFileEntity?.let { entity ->
                                        try {
                                            File(entity.fullPath).writeText(editingFileContent)
                                            Toast.makeText(context, "تم حفظ التعديلات بنجاح!", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "فشل الحفظ: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                        showFileEditorDialog = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("حفظ", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 3. TREEDOC TAB - Report generator with nested Interactive Directory Chooser
// =====================================================================

@Composable
fun TreeDocScreen(viewModel: MainViewModel) {
    var chosenFolder by remember { mutableStateOf(".") }
    var chosenFormat by remember { mutableStateOf("html") }
    var includeSizes by remember { mutableStateOf(true) }
    var includeMtime by remember { mutableStateOf(true) }
    var isGenerating by remember { mutableStateOf(false) }

    val treedocReportText = viewModel.treedocReport.collectAsState().value
    val browserCurrentPath = viewModel.currentBrowserPath.collectAsState().value
    val browserFiles = viewModel.browserFilesList.collectAsState().value

    var showDirectoryChooser by remember { mutableStateOf(false) }
    var newDirName by remember { mutableStateOf("") }
    var showNewDirDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Form Configuration Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("إعداد تقارير الشجرة (TreeDoc)", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Choose Folder Button Input
                    Text("المجلد المستهدف للمسح", color = TextGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = chosenFolder,
                            onValueChange = { chosenFolder = it },
                            modifier = Modifier.weight(1f).testTag("folder_input_field"),
                            textStyle = TextStyle(color = TextSilver, fontSize = 12.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MetallicGold.copy(alpha = 0.5f),
                                unfocusedBorderColor = GlassBorder,
                                focusedContainerColor = GlassBlack,
                                unfocusedContainerColor = GlassBlack
                            )
                        )

                        Button(
                            onClick = {
                                viewModel.navigateToBaseDir()
                                showDirectoryChooser = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("browse_button")
                        ) {
                            Text("تصفح", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Format Picker
                    Text("صيغة التقرير الإخراجية", color = TextGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        listOf("html" to "موقع HTML", "txt" to "نص شجري خطي", "json" to "قاعدة بيانات JSON").forEach { (fmt, label) ->
                            val isSelected = chosenFormat == fmt
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .background(if (isSelected) GoldGlassBg else GlassWhite, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (isSelected) MetallicGold else GlassBorder, RoundedCornerShape(12.dp))
                                    .clickable { chosenFormat = fmt }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (isSelected) MetallicGold else TextSilver, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Advanced Parameters
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = includeSizes,
                                onCheckedChange = { includeSizes = it },
                                colors = CheckboxDefaults.colors(checkedColor = MetallicGold, uncheckedColor = TextGray)
                            )
                            Text("أحجام الملفات", color = TextSilver, fontSize = 11.sp)
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = includeMtime,
                                onCheckedChange = { includeMtime = it },
                                colors = CheckboxDefaults.colors(checkedColor = MetallicGold, uncheckedColor = TextGray)
                            )
                            Text("تواريخ التعديل", color = TextSilver, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isGenerating = true
                                viewModel.generateTreeReport(chosenFolder, chosenFormat, true) { message ->
                                    isGenerating = false
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f).height(46.dp).testTag("generate_report_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("توليد ونسخ التقرير", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Output Monospace Terminal View
        if (treedocReportText.isNotBlank()) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("التقرير المولّد الحاضر", color = TextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(treedocReportText))
                                Toast.makeText(context, "تم النسخ بنجاح للحافظة", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Copy Report text", tint = MetallicGold, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .background(GlassBlack, RoundedCornerShape(18.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = treedocReportText,
                            color = TextSilver,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    // Interactive Internal File Explorer Dialog Drawer
    if (showDirectoryChooser && browserCurrentPath != null) {
        Dialog(
            onDismissRequest = { showDirectoryChooser = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .background(CardSlateBg, RoundedCornerShape(26.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(26.dp))
                    .padding(18.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("المستكشف الداخلي للمنصة", color = MetallicGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        IconButton(
                            onClick = { showNewDirDialog = true }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "New Directory", tint = TextSilver, modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "المسار الحالي: ${browserCurrentPath.name.ifBlank { "SmartPlatform" }}",
                        color = TextGray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Root navigation path lists
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .background(GlassBlack, RoundedCornerShape(16.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    ) {
                        if (browserFiles.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("هذا المجلد فارغ حالياً.", color = TextMuted, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                // Up level option
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.navigateUp() }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = null, tint = MetallicGold, modifier = Modifier.size(16.dp))
                                        Text(".. (الرجوع للمجلد الأعلى)", color = MetallicGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.5f))
                                }

                                items(browserFiles) { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (file.isDirectory) {
                                                    viewModel.navigateToDir(file)
                                                }
                                            }
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (file.isDirectory) Icons.Default.Menu else Icons.Default.Info,
                                                contentDescription = null,
                                                tint = if (file.isDirectory) MetallicGold else TextSilver,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(file.name, color = TextSilver, fontSize = 12.sp)
                                        }

                                        IconButton(
                                            onClick = { viewModel.deleteFileFromBrowser(file) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete File", tint = DangerRed.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                                        }
                                    }
                                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showDirectoryChooser = false }) {
                            Text("إلغاء", color = TextGray)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                chosenFolder = browserCurrentPath.name.ifBlank { "." }
                                showDirectoryChooser = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("تحديد المجلد الحالي", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Modal Directory Creation dialogue
    if (showNewDirDialog) {
        Dialog(onDismissRequest = { showNewDirDialog = false }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardSlateBg, RoundedCornerShape(20.dp))
                    .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Text("إنشاء مجلد فرعي جديد", color = MetallicGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDirName,
                        onValueChange = { newDirName = it },
                        placeholder = { Text("مثلاً: designs", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold,
                            unfocusedBorderColor = GlassBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showNewDirDialog = false }) {
                            Text("إلغاء", color = TextGray)
                        }
                        Button(
                            onClick = {
                                if (newDirName.isNotBlank()) {
                                    viewModel.createDirectoryInBrowser(newDirName)
                                    newDirName = ""
                                }
                                showNewDirDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg)
                        ) {
                            Text("إنشاء", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 4. EXECUTOR TAB - Preloaded simulation actions & live terminal logs
// =====================================================================

@Composable
fun ExecutorScreen(viewModel: MainViewModel) {
    var rawCommandStr by remember { mutableStateOf("") }
    var terminalOutput by remember { mutableStateOf("Ready to receive sandbox commands.") }
    var executorLoading by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("منفذ التعليمات البرمجية والأوامر (Executor)", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Live preloaded command cards
                    Text("أنماط تنفيذ وتوليد سريعة", color = TextGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            CommandBadge("build") {
                                rawCommandStr = "build"
                            }
                        }
                        item {
                            CommandBadge("run sample.py") {
                                rawCommandStr = "run sample.py"
                            }
                        }
                        item {
                            CommandBadge("open model.json") {
                                rawCommandStr = "open model.json"
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // CLI input box
                    OutlinedTextField(
                        value = rawCommandStr,
                        onValueChange = { rawCommandStr = it },
                        modifier = Modifier.fillMaxWidth().testTag("command_input_field"),
                        textStyle = TextStyle(color = TextSilver, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        placeholder = { Text("أدخل اسم الأمر المطلق هنا...", color = TextMuted, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = GlassBlack,
                            unfocusedContainerColor = GlassBlack
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            if (rawCommandStr.isBlank()) {
                                Toast.makeText(context, "الرجاء تحديد نوع الأمر أولاً", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            executorLoading = true
                            viewModel.executeSingleCommand(rawCommandStr) { out ->
                                executorLoading = false
                                terminalOutput = out
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("execute_command_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (executorLoading) {
                            CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("تنفيذ الأمر الصادر الرديف", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // CLI Output display console
        item {
            Column {
                Text("شاشة كاشف مخرجات الطرفية", color = TextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(GlassBlack, RoundedCornerShape(18.dp))
                        .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = terminalOutput,
                        color = EmeraldGlow,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    }
}

@Composable
fun CommandBadge(cmd: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(GoldGlassBg, RoundedCornerShape(8.dp))
            .border(1.dp, MetallicGold.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(cmd, color = MetallicGold, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

// =====================================================================
// 5. GEMINI CHAT TAB - Custom chatbot with direct deployment buttons
// =====================================================================

@Composable
fun GeminiScreen(viewModel: MainViewModel) {
    var chatPromptStr by remember { mutableStateOf("") }
    val geminiReply = viewModel.geminiResponse.collectAsState().value
    val isLoading = viewModel.geminiLoading.collectAsState().value
    val isKeyAvailable = viewModel.getGeminiKeyAvailable()

    // Interactive Deploy State
    var isDeployingCode by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Warning API Key alert if empty
        if (!isKeyAvailable) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x20EF4444), RoundedCornerShape(18.dp))
                        .border(1.dp, DangerRed.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Column {
                        Text("مفتاح جمناي غير محدد", color = DangerRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "لاستخدام ميزات الذكاء الاصطناعي الكاملة، يرجى تهيئة مفتاح GEMINI_API_KEY في لوحة Secrets للمنصة، أو إضافته يدوياً بتبويب الإعدادات بالأسفل.",
                            color = TextSilver.copy(alpha = 0.9f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // Preset prompts selection
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("توجيه محدد مسبقاً للرفيق", color = TextGray, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            "انشئ تطبيق Python تجريبي" to "انشئ تطبيق Python تجريبي يطبع معلومات النظام والمنصة الذكية.",
                            "لد سكريبت Kotlin بسيط" to "اكتب سكريبت Kotlin يحتوي على توجيه @builder:file مع كود يقرأ الحافظة للأندرويد."
                        ).forEach { (caption, fullPrompt) ->
                            item {
                                Box(
                                    modifier = Modifier
                                        .background(GlassWhite, RoundedCornerShape(10.dp))
                                        .border(1.dp, GlassBorder, RoundedCornerShape(10.dp))
                                        .clickable { chatPromptStr = fullPrompt }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(caption, color = TextSilver, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Chat Input form
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("محادثة وكيل جمناي المخصص", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = chatPromptStr,
                        onValueChange = { chatPromptStr = it },
                        placeholder = { Text("اكتب طلبك ذكياً، ليرد جمناي ويبني لك التوجيهات الحية المباشرة فوراً...", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("gemini_prompt_field"),
                        maxLines = 4,
                        textStyle = TextStyle(color = TextSilver, fontSize = 12.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold,
                            unfocusedBorderColor = GlassBorder,
                            focusedContainerColor = GlassBlack,
                            unfocusedContainerColor = GlassBlack
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            if (chatPromptStr.isBlank()) return@Button
                            viewModel.sendGeminiRequest(chatPromptStr) { Toast.makeText(context, "وصلت الاستجابة الذكية!", Toast.LENGTH_SHORT).show() }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp).testTag("gemini_send_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("إرسال للنموذج ذكياً", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // AI Response display bubble layout with embedded DIRECT DEPLOY Action Button
        if (geminiReply.isNotBlank()) {
            item {
                Column {
                    Text("محادثة جمناي الرديف ومقترحات الكود", color = TextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassBlack, RoundedCornerShape(18.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = geminiReply,
                                color = TextSilver,
                                fontSize = 12.sp,
                                modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 280.dp)
                            )

                            // Detect builder directives inside the response and show GOLD DEPLOY BUTTON
                            if (geminiReply.contains("@builder:file")) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        isDeployingCode = true
                                        viewModel.runManualProcess(geminiReply) { out ->
                                            isDeployingCode = false
                                            Toast.makeText(context, out, Toast.LENGTH_LONG).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGlow, contentColor = SlateBg),
                                    modifier = Modifier.fillMaxWidth().height(44.dp).testTag("deploy_gemini_directives_button"),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    if (isDeployingCode) {
                                        CircularProgressIndicator(color = SlateBg, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Build, contentDescription = null, tint = SlateBg, modifier = Modifier.size(16.dp))
                                            Text("تطبيق توجيهات الملفات المكتشفة فورا", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 6. SETTINGS TAB - custom configuration settings
// =====================================================================

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    goldenFrameEnabled: Boolean,
    onToggleGoldenFrame: (Boolean) -> Unit
) {
    var bPrefix by remember { mutableStateOf(viewModel.prefixBuilder.value) }
    var ePrefix by remember { mutableStateOf(viewModel.prefixExecutor.value) }
    var tPrefix by remember { mutableStateOf(viewModel.prefixTreedoc.value) }
    var bDir by remember { mutableStateOf(viewModel.baseDirSetting.value) }
    var apiKeyManual by remember { mutableStateOf("") }
    
    var showDirBrowser by remember { mutableStateOf(false) }
    var showManualPermissionsDashboard by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentSavedBaseDir = viewModel.baseDirSetting.collectAsState().value

    LaunchedEffect(currentSavedBaseDir) {
        bDir = currentSavedBaseDir
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Automation Shield Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("مركز صلاحيات الأتمتة الخلفية", color = MetallicGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("مراجعة وتفعيل خدمات إمكانية الوصول، استثناء البطارية وصلاحية الملفات للأتمتة الكاملة", color = TextGray, fontSize = 10.sp)
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = { showManualPermissionsDashboard = true },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldGlassBg, contentColor = BrightGold),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إدارة الصلاحيات", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("تهيئة البادئات المخصصة", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = bPrefix,
                        onValueChange = { bPrefix = it },
                        label = { Text("بادئة المنشئ (Builder Prefix)", color = TextMuted, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().testTag("prefix_builder_field"),
                        textStyle = TextStyle(color = TextSilver, fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = ePrefix,
                        onValueChange = { ePrefix = it },
                        label = { Text("بادئة المنفذ (Executor Prefix)", color = TextMuted, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = TextSilver, fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = tPrefix,
                        onValueChange = { tPrefix = it },
                        label = { Text("بادئة TreeDoc (TreeDoc Prefix)", color = TextMuted, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = TextSilver, fontSize = 12.sp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            viewModel.savePrefixes(bPrefix, ePrefix, tPrefix)
                            Toast.makeText(context, "تم حفظ تغييرات البادئات بنجاح!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.fillMaxWidth().height(44.dp).testTag("save_prefixes_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("حفظ البادئات والرموز", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Base directory preferences card with Folder Browser select
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("المجلد الافتراضي لعمليات التصدير والأتمتة", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = bDir,
                            onValueChange = { bDir = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(color = TextSilver, fontSize = 11.sp),
                            singleLine = true
                        )
                        
                        Button(
                            onClick = { showDirBrowser = true },
                            colors = ButtonDefaults.buttonColors(containerColor = GoldGlassBg, contentColor = BrightGold),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.height(54.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "تصفح المجلدات", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تصفح", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Button(
                        onClick = {
                            viewModel.saveBaseDir(bDir)
                            Toast.makeText(context, "تم تحديث جذر الحفظ الافتراضي!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تثبيت مسار المجلد يدويًا", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Dialogue modals inside Settings
        item {
            PermissionsDashboardDialog(
                isOpen = showManualPermissionsDashboard,
                onDismiss = { showManualPermissionsDashboard = false },
                viewModel = viewModel
            )

            DirectoryBrowserDialog(
                isOpen = showDirBrowser,
                onDismiss = { showDirBrowser = false },
                initialPath = bDir,
                onConfirm = { chosenPath ->
                    bDir = chosenPath
                    viewModel.saveBaseDir(chosenPath)
                    Toast.makeText(context, "تم حفظ وتثبيت مجلد العمل الجديد!", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Golden Frame Toggle Card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("الوضع المُحاكى (إطار ذهبي)", color = TextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("تفعيل إطار الهاتف الفاخر المزود بشريط الحالة", color = TextGray, fontSize = 10.sp)
                    }

                    Switch(
                        checked = goldenFrameEnabled,
                        onCheckedChange = { onToggleGoldenFrame(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SlateBg,
                            checkedTrackColor = MetallicGold,
                            uncheckedThumbColor = TextGray,
                            uncheckedTrackColor = GlassWhite
                        ),
                        modifier = Modifier.testTag("frame_simulation_switch")
                    )
                }
            }
        }

        // Clipboard Auto-Processing Toggle Card
        item {
            val autoProcessEnabled = viewModel.autoProcessClipboard.collectAsState().value
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("المعالجة التلقائية للحافظة", color = TextSilver, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("توليد الملفات والتعليمات تلقائياً بمجرد نسخ التوجيهات وحفظها في المجلد الافتراضي دون تدخل يدوي", color = TextGray, fontSize = 10.sp)
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = autoProcessEnabled,
                        onCheckedChange = { viewModel.setAutoProcessClipboard(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SlateBg,
                            checkedTrackColor = MetallicGold,
                            uncheckedThumbColor = TextGray,
                            uncheckedTrackColor = GlassWhite
                        ),
                        modifier = Modifier.testTag("auto_process_clipboard_switch")
                    )
                }
            }
        }

        // Custom Gemini API key card
        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("مفتاح جمناي المخصص (Manual Key)", color = MetallicGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKeyManual,
                        onValueChange = { apiKeyManual = it },
                        placeholder = { Text("أدخل مفتاح جمناي يدويًا هنا...", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = TextSilver, fontSize = 12.sp),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (apiKeyManual.isNotBlank()) {
                                viewModel.setCustomGeminiKey(apiKeyManual)
                                apiKeyManual = ""
                                Toast.makeText(context, "تم حفظ مفتاح API جمناي بنجاح!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تهيئة المفتاح والدمج الذكي", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// =====================================================================
// Custom Reusable Layout Containers (GlassCard & general design styles)
// =====================================================================

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(GlassWhite, RoundedCornerShape(24.dp))
            .border(BorderStroke(0.8.dp, GlassBorder), RoundedCornerShape(24.dp))
    ) {
        content()
    }
}

// =====================================================================
// Advanced Directory Browser Dialog & Setup Companion (Arabic layout)
// =====================================================================

@Composable
fun DirectoryBrowserDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    initialPath: String,
    onConfirm: (String) -> Unit
) {
    if (!isOpen) return
    val context = LocalContext.current
    
    var currentPath by remember {
        mutableStateOf(
            if (initialPath.isNotBlank()) File(initialPath).also { it.mkdirs() }
            else File("/storage/emulated/0")
        )
    }
    
    var pathInputText by remember { mutableStateOf(currentPath.absolutePath) }
    var subDirs by remember { mutableStateOf(emptyList<File>()) }
    
    fun loadDirs() {
        val path = currentPath
        if (path.exists() && path.isDirectory) {
            val list = path.listFiles { file -> file.isDirectory }?.toList() ?: emptyList()
            subDirs = list.sortedBy { it.name.lowercase() }
        } else {
            subDirs = emptyList()
        }
        pathInputText = currentPath.absolutePath
    }
    
    LaunchedEffect(currentPath) {
        loadDirs()
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .background(SlateBg, RoundedCornerShape(24.dp))
                .border(1.2.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Menu, contentDescription = null, tint = MetallicGold)
                        Text("مستعرض ومعالج أدلة الحفظ", color = TextSilver, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TextGray)
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Manual path row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = pathInputText,
                        onValueChange = { pathInputText = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = TextSilver, fontSize = 11.sp),
                        label = { Text("مسار الدليل النشط", color = TextMuted, fontSize = 10.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MetallicGold,
                            unfocusedBorderColor = GlassBorder,
                            focusedLabelColor = BrightGold,
                            unfocusedLabelColor = TextMuted
                        )
                    )
                    
                    IconButton(
                        onClick = {
                            val f = File(pathInputText)
                            if (f.exists()) {
                                currentPath = f
                            } else {
                                Toast.makeText(context, "المسار المكتوب غير موجود!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.background(GoldGlassBg, RoundedCornerShape(8.dp)).size(38.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "الانتقال للمسار", tint = BrightGold, modifier = Modifier.size(18.dp))
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                // Quick shortcuts
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val shortcuts = listOf(
                        Triple("الرئيسية", "/storage/emulated/0", Icons.Default.Home),
                        Triple("المستندات", "/storage/emulated/0/Documents", Icons.Default.Menu),
                        Triple("التنزيلات", "/storage/emulated/0/Download", Icons.Default.Menu),
                        Triple("المشاريع", "/storage/emulated/0/BuilderProjects", Icons.Default.Favorite)
                    )
                    
                    shortcuts.forEach { (label, pathStr, icon) ->
                        val selected = currentPath.absolutePath == pathStr
                        Button(
                            onClick = {
                                val folder = File(pathStr)
                                folder.mkdirs()
                                currentPath = folder
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) MetallicGold else GoldGlassBg,
                                contentColor = if (selected) SlateBg else TextSilver
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(label, fontSize = 9.sp)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                // Directory control header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "محتويات: ${currentPath.name.ifBlank { "الجذر الرئيسي" }}",
                        color = TextSilver,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (currentPath.parentFile != null) {
                        TextButton(
                            onClick = { currentPath = currentPath.parentFile!! },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("⬆️ صعود للأعلى", fontSize = 11.sp, color = BrightGold)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(6.dp))
                
                // Browse Directories container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                        .background(CardSlateBg)
                ) {
                    if (subDirs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = TextGray, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("لا توجد مجلدات فرعية في هذا المسار", color = TextGray, fontSize = 11.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                            items(subDirs) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { currentPath = folder }
                                        .padding(vertical = 10.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Menu, contentDescription = null, tint = MetallicGold, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = folder.name,
                                        color = TextSilver,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Divider(color = GlassBorder, thickness = 0.5.dp)
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                // Action Buttons at bottom
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            if (clipboard != null && clipboard.hasPrimaryClip()) {
                                val clipStr = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                if (clipStr.isNotBlank() && clipStr.startsWith("/")) {
                                    val f = File(clipStr)
                                    f.mkdirs()
                                    if (f.exists() && f.isDirectory) {
                                        currentPath = f
                                        Toast.makeText(context, "تم جلب وحزم المسار من الحافظة!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GoldGlassBg, contentColor = BrightGold),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("لصق مسار من الحافظة", fontSize = 10.sp)
                    }
                    
                    Button(
                        onClick = {
                            onConfirm(currentPath.absolutePath)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("تأكيد المجلد الافتراضي", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


// =====================================================================
// Modern Automation & Permissions Unified Dashboard (Arabic representation)
// =====================================================================

@Composable
fun PermissionsDashboardDialog(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    viewModel: MainViewModel
) {
    if (!isOpen) return
    val context = LocalContext.current
    
    // States
    var hasNotify by remember { mutableStateOf(true) }
    var hasAccessibility by remember { mutableStateOf(false) }
    var hasAllFiles by remember { mutableStateOf(false) }
    var hasBatteryIgnore by remember { mutableStateOf(false) }
    var hasOverlay by remember { mutableStateOf(false) }
    
    fun refreshStates() {
        hasNotify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        
        hasAccessibility = isAccessibilityServiceEnabled(context)
        
        hasAllFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
        
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        hasBatteryIgnore = pm.isIgnoringBatteryOptimizations(context.packageName)
        
        hasOverlay = Settings.canDrawOverlays(context)
    }
    
    LaunchedEffect(Unit) {
        refreshStates()
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .background(SlateBg, RoundedCornerShape(26.dp))
                .border(1.2.dp, Brush.linearGradient(listOf(MetallicGold, BrightGold)), RoundedCornerShape(26.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Build, contentDescription = null, tint = BrightGold, modifier = Modifier.size(24.dp))
                        Text(
                            text = "مركز صلاحيات الأتمتة الكاملة",
                            color = TextSilver,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = TextGray)
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))
                
                Text(
                    text = "لجعل التطبيق يعمل في الخلفية بصمت تام ويستعرض وينسخ الملفات فور تكرار التوجيهات، قم بتمكين الصلاحيات التالية لتشغيل الأتمتة الكاملة بأمان:",
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Permissions lists
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Accessibility Service
                    item {
                        PermissionItemCard(
                            title = "خدمة إمكانية الوصول لمراقبة الحافظة",
                            description = "تمنع قيود أندرويد قراءة الحافظة في الخلفية. تفعيل هذه الخدمة يمكن التطبيق من التقاط وتطبيق البادئات (@builder, @executor) فوراً بمجرد نسخها في أي تطبيق وبكل خصوصية وأمان.",
                            isGranted = hasAccessibility,
                            onGrant = {
                                Toast.makeText(context, "ابحث عن 'مساعد أتمتة الحافظة (المنصة الذكية)' وقم بتفعيله", Toast.LENGTH_LONG).show()
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            }
                        )
                    }
                    
                    // 2. Full Storage Access (MANAGE_EXTERNAL_STORAGE)
                    item {
                        PermissionItemCard(
                            title = "الوصول الكامل إلى جميع الملفات والأشجار",
                            description = "مهم وحيوي لوضع وإنشاء المشاريع ومجلد العمل في أي مكان مخصص على جهازك (مثل /storage/emulated/0/BuilderProjects) دون حظر.",
                            isGranted = hasAllFiles,
                            onGrant = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                        context.startActivity(intent)
                                    }
                                } else {
                                    Toast.makeText(context, "ممنوح تلقائياً على هذا النظام", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    
                    // 3. Ignore Battery Optimization
                    item {
                        PermissionItemCard(
                            title = "استثناء التطبيق من تحسين استهلاك البطارية",
                            description = "تجنب إغلاق خدمات المراقبة الذكية من قبل النظام عند سكون أو قفل شاشة الهاتف.",
                            isGranted = hasBatteryIgnore,
                            onGrant = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (x: Exception) {}
                                }
                            }
                        )
                    }
                    
                    // 4. Draw Over Other Apps (SYSTEM_ALERT_WINDOW)
                    item {
                        PermissionItemCard(
                            title = "الظهور فوق التطبيقات (الكرة العائمة الذكية)",
                            description = "ضروري لإظهار الكرة العائمة التفاعلية فوق كل التطبيقات لإنشاء وتوليد تقارير شجرية سريعة بنقرة.",
                            isGranted = hasOverlay,
                            onGrant = {
                                try {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                            }
                        )
                    }
                    
                    // 5. Notifications
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        item {
                            PermissionItemCard(
                                title = "صلاحية الإشعارات اليدوية والدائمة",
                                description = "الحفاظ على بقاء محاكي الخلفية نشطاً ومستقراً ومراقباً عبر النظام باستمرار.",
                                isGranted = hasNotify,
                                onGrant = {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(14.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { refreshStates() },
                        colors = ButtonDefaults.buttonColors(containerColor = GlassWhite, contentColor = TextSilver),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("طالع التغييرات", fontSize = 11.sp)
                    }
                    
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تم الإعداد تماماً", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrant: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = TextSilver,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isGranted) EmeraldGlow.copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (isGranted) EmeraldGlow else Color.Red)
                ) {
                    Text(
                        text = if (isGranted) "مفعّل" else "مطلوب تفعيل",
                        color = if (isGranted) EmeraldGlow else Color.Red,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = description,
                color = TextGray,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
            
            if (!isGranted) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = GoldGlassBg, contentColor = BrightGold),
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("انتقل للإعداد والتفعيل ↗", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

