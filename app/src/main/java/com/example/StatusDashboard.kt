package com.example

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.ProjectContextManager
import com.example.engine.ProjectManager
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusDashboardDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onNavigateToTab: (MainTab) -> Unit
) {
    val context = LocalContext.current
    val sharedPrefs = remember(context) { context.getSharedPreferences("SmartPrefs", Context.MODE_PRIVATE) }
    val capturePrefs = remember(context) { context.getSharedPreferences("SmartCapturePrefs", Context.MODE_PRIVATE) }
    
    // Service state
    val isMonitorServiceRunning by viewModel.isServiceRunning.collectAsState()
    
    // Accessibility check
    var isKeyboardAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    
    // Bubble check
    var isGoldenBubbleRunning by remember {
        mutableStateOf(
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getRunningServices(Integer.MAX_VALUE)
                ?.any { it.service.className == "com.example.service.GoldenBubbleService" } ?: false
        )
    }

    // Refresh trigger
    var refreshCounter by remember { mutableStateOf(0) }

    LaunchedEffect(refreshCounter) {
        isKeyboardAccessibilityEnabled = isAccessibilityServiceEnabled(context)
        isGoldenBubbleRunning = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.getRunningServices(Integer.MAX_VALUE)
            ?.any { it.service.className == "com.example.service.GoldenBubbleService" } ?: false
    }

    val isSmartCaptureEnabled = capturePrefs.getBoolean("enable_context_manager", true)
    val saveAllTexts = capturePrefs.getBoolean("save_all_texts", false)
    val activeProject = ProjectContextManager.getCurrentProjectPath(context)
    val documentTheme = capturePrefs.getString("document_theme", "dark") ?: "dark"
    val importedProjectsCount = ProjectManager.getAllProjects(context).size
    
    val latestLog by viewModel.eventLogs.collectAsState(initial = emptyList())
    val lastEvent = latestLog.firstOrNull()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .padding(12.dp)
                .background(SlateBg)
                .border(1.2.dp, GlassBorder, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp)),
            color = SlateBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Title view
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(GoldGlassBg, CircleShape)
                                .border(1.dp, MetallicGold, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📊", fontSize = 16.sp)
                        }
                        Text(
                            "حالة محركات النظام",
                            color = BrightGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = TextSilver)
                    }
                }

                Divider(color = GlassBorder)

                // List contents
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Monitor Service Status
                    item {
                        DashboardItemCard(
                            title = "خدمة المراقبة التلقائية",
                            statusText = if (isMonitorServiceRunning) "نشطة ومتحفزة 🟢" else "معطّلة 🔴",
                            statusColor = if (isMonitorServiceRunning) EmeraldGlow else DangerRed,
                            subDetails = "تراقب هذا الحساب وجميع النسخ من التطبيقات الأخرى تلقائياً.",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.MONITOR)
                            }
                        )
                    }

                    // Keyboard service (accessibility option)
                    item {
                        DashboardItemCard(
                            title = "خدمة لوحة المفاتيح والوصول",
                            statusText = if (isKeyboardAccessibilityEnabled) "مفعّلة 🟢" else "معطلة ⚪",
                            statusColor = if (isKeyboardAccessibilityEnabled) EmeraldGlow else TextGray,
                            subDetails = "مطلوبة للأتمتة والكتابة الفورية دون تدخل كيبورد تقليدي.",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.SETTINGS)
                            }
                        )
                    }

                    // Golden overlay bubble
                    item {
                        DashboardItemCard(
                            title = "الفقاعة الذهبية للمراقبة الخارجية",
                            statusText = if (isGoldenBubbleRunning) "ظاهرة ونشطة 🟢" else "غير مفعّلة 🔴",
                            statusColor = if (isGoldenBubbleRunning) EmeraldGlow else DangerRed,
                            subDetails = "تتيح لك إدارة الحفظ والسياق فوق أي تطبيق بنقرة سريعة.",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.SETTINGS)
                            }
                        )
                    }

                    // Smart capture
                    item {
                        DashboardItemCard(
                            title = "محرك الالتقاط الذكي والأرشفة",
                            statusText = if (isSmartCaptureEnabled) "مفعّل 🟢" else "معطّل ⚪",
                            statusColor = if (isSmartCaptureEnabled) EmeraldGlow else TextGray,
                            subDetails = "الوضع: " + (if (saveAllTexts) "حفظ كل محتويات الحافظة" else "حفظ انتقائي وتصنيف ذكي"),
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.SETTINGS)
                            }
                        )
                    }

                    // Context manager
                    item {
                        DashboardItemCard(
                            title = "مدير السياق والفلترة الكودية",
                            statusText = "نشط 🟢",
                            statusColor = EmeraldGlow,
                            subDetails = "المشروع الفعال حالياً: ${activeProject}",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.PROJECTS)
                            }
                        )
                    }

                    // Theme and styling
                    item {
                        DashboardItemCard(
                            title = "تنسيق مظهر المخرجات الحالي",
                            statusText = "نمط: ${documentTheme.uppercase(Locale.ROOT)} 🎨",
                            statusColor = BrightGold,
                            subDetails = "تصدر المستندات بالخطوط والتصميم الذهبي الفاخر لهذا النمط.",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.SETTINGS)
                            }
                        )
                    }

                    // Projects statistics
                    item {
                        DashboardItemCard(
                            title = "مكتبة المشاريع الذكية",
                            statusText = "$importedProjectsCount مجلد نشط 📁",
                            statusColor = MetallicGold,
                            subDetails = "مستنداتك مصنفة ومؤرشفة في مجلدات منفصلة لكل مشروع.",
                            onClick = {
                                onDismiss()
                                onNavigateToTab(MainTab.PROJECTS)
                            }
                        )
                    }

                    // Last logged incident
                    item {
                        val simpleTime = if (lastEvent != null) {
                            try {
                                val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                sdf.format(Date(lastEvent!!.timestamp))
                            } catch (e: Exception) {
                                "مؤخراً"
                            }
                        } else null

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardSlateBg),
                            shape = RoundedCornerShape(12.dp),
                            border = ColumnDefaults.borderStroke()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "🕐 آخر حدث مسجل بالنظام:",
                                        color = MetallicGold,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    simpleTime?.let {
                                        Text(
                                            it,
                                            color = TextGray,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                if (lastEvent != null) {
                                    Text(
                                        lastEvent!!.message,
                                        color = TextSilver,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp
                                    )
                                } else {
                                    Text(
                                        "لا يوجد أحداث مسجلة بعد، النظام في وضع الاستعداد التام.",
                                        color = TextSilver,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { refreshCounter++ },
                        colors = ButtonDefaults.buttonColors(containerColor = CardSlateBg, contentColor = MetallicGold),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(44.dp)
                            .border(1.dp, GlassBorder, RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("تحديث الحالة غداً", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("موافق ورجوع", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardItemCard(
    title: String,
    statusText: String,
    statusColor: Color,
    subDetails: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardSlateBg),
        shape = RoundedCornerShape(12.dp),
        border = ColumnDefaults.borderStroke()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = TextSilver,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                
                Surface(
                    color = statusColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        statusText,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            
            Text(
                subDetails,
                color = TextGray,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
    }
}

object ColumnDefaults {
    @Composable
    fun borderStroke() = androidx.compose.foundation.BorderStroke(0.8.dp, GlassBorder)
}
