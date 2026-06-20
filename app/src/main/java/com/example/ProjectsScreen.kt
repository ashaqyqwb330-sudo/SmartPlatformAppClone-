package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.engine.*
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel
import org.json.JSONObject
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var projectsList by remember { mutableStateOf(ProjectManager.getAllProjects(context)) }
    var activeProjectPath by remember { mutableStateOf(ProjectContextManager.getCurrentProjectPath(context)) }
    
    // Dialog and Screen states
    var showImportEditor by remember { mutableStateOf(false) }
    var clipboardImportCandidate by remember { mutableStateOf<String?>(null) }
    var customTemplateJsonInput by remember { mutableStateOf("") }
    
    // Fetch clipboard on launch to offer auto import
    LaunchedEffect(Unit) {
        val systemClipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = systemClipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString() ?: ""
            val trimText = text.trim()
            if (trimText.startsWith("{") && (trimText.contains("template_version") || trimText.contains("templateVersion"))) {
                val parsed = TemplateParser.parse(trimText)
                if (parsed.isSuccess) {
                    clipboardImportCandidate = trimText
                }
            }
        }
    }

    // Refresh listener for external updates (like from the Golden Bubble)
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.example.ACTION_REFRESH_PROJECTS") {
                    projectsList = ProjectManager.getAllProjects(context)
                    activeProjectPath = ProjectContextManager.getCurrentProjectPath(context)
                }
            }
        }
        val filter = android.content.IntentFilter("com.example.ACTION_REFRESH_PROJECTS")
        context.registerReceiver(receiver, filter)
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "📁 مكتبة المشاريع الذكية",
                        color = BrightGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            val activeProj = ProjectContextManager.getCurrentProjectPath(context)
                            val projectDir = ProjectContextManager.getProjectDir(activeProj, context)
                            val configFile = File(projectDir, "project_config.json")
                            if (configFile.exists()) {
                                try {
                                    val json = configFile.readText()
                                    clipboardManager.setText(AnnotatedString(json))
                                    Toast.makeText(context, "✅ تم تصدير هيكل المشروع الحالي للحافظة!", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "عذراً، فشل التصدير: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Fallback export for default project
                                val fallbackJson = """
                                {
                                  "projectName": "$activeProj",
                                  "template_version": 1,
                                  "folders": [
                                    {
                                      "name": "محفوظات",
                                      "path": "saved",
                                      "fileTypes": ["text"],
                                      "keywords": []
                                    }
                                  ]
                                }
                                """.trimIndent()
                                clipboardManager.setText(AnnotatedString(fallbackJson))
                                Toast.makeText(context, "✅ تم تصدير هيكل المشروع الافتراضي للحافظة!", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.testTag("export_active_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "تصدير المشروع النشط", tint = MetallicGold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateBg)
            )
        },
        containerColor = SlateBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Clipboard template candidate notification card
            clipboardImportCandidate?.let { jsonStr ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.2.dp, Brush.linearGradient(listOf(MetallicGold, BrightGold)), RoundedCornerShape(12.dp)),
                        colors = CardDefaults.cardColors(containerColor = GoldGlassBg),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = BrightGold, modifier = Modifier.size(18.dp))
                                    val tempName = try { JSONObject(jsonStr).getString("projectName") } catch (e: Exception) { "قالب ذكي" }
                                    Text("تم كشف قالب مشروع: $tempName", color = BrightGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                IconButton(onClick = { clipboardImportCandidate = null }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "تجاهل", tint = TextGray, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                "تحتوي الحافظة على كود قالب مشروع ذكي مهيأ، هل ترغب في استيراده وتفعيله فوراً؟",
                                color = TextSilver,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val parsed = TemplateParser.parse(jsonStr)
                                        if (parsed.isSuccess) {
                                            val tObj = parsed.getOrThrow()
                                            val buildRes = ProjectBuilder.build(tObj, ProjectContextManager.getBaseDir(context).absolutePath)
                                            if (buildRes.isSuccess) {
                                                val resPath = buildRes.getOrThrow()
                                                ProjectManager.addProject(context, resPath, tObj.projectName)
                                                ProjectManager.setActiveProject(context, resPath)
                                                projectsList = ProjectManager.getAllProjects(context)
                                                activeProjectPath = resPath
                                                Toast.makeText(context, "✅ تم استيراد وتفعيل قالب: ${tObj.projectName}", Toast.LENGTH_LONG).show()
                                                clipboardImportCandidate = null
                                            } else {
                                                Toast.makeText(context, "فشل إنشاء المشروع: ${buildRes.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "فشل تحليل القالب", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGlow, contentColor = Color.Black),
                                    modifier = Modifier.weight(1f).height(30.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("برق السحاب (استيراد دافئ)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        customTemplateJsonInput = jsonStr
                                        showImportEditor = true
                                        clipboardImportCandidate = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                                    modifier = Modifier.weight(1f).height(30.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("معاينة وتعديل رصين", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Quick Actions Block (Top buttons)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            customTemplateJsonInput = ""
                            showImportEditor = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("import_template_action_btn"),
                        shape = RoundedCornerShape(8.dp),
                        elevation = ButtonDefaults.buttonElevation(4.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = SlateBg)
                            Text("استيراد قالب ذكي", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    
                    Button(
                        onClick = {
                            // Automatically paste design pattern snippet to clipboard
                            val templateSample = """
                            {
                              "projectName": "سياق_التطوير_التكنولوجي",
                              "template_version": 1,
                              "folders": [
                                {
                                  "name": "مستندات الهندسة",
                                  "path": "engineering_docs",
                                  "fileTypes": ["txt", "md"],
                                  "keywords": ["هندسة", "معمارية", "تطوير", "قواعد_البيانات", "كود"]
                                },
                                {
                                  "name": "الواجهات البرمجية",
                                  "path": "api_specifications",
                                  "fileTypes": ["json", "yaml"],
                                  "keywords": ["واجهة", "api", "endpoint", "توثيق", "سيرفر"]
                                }
                              ]
                            }
                            """.trimIndent()
                            clipboardManager.setText(AnnotatedString(templateSample))
                            Toast.makeText(context, "📋 تم وضع قالب نموذجي في حافظتك لاستعراضه!", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CardSlateBg, contentColor = MetallicGold),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MetallicGold)
                            Text("نسخ نموذج تجريبي", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Category title
            item {
                Text(
                    "المشاريع المتاحة والمستوردة حالياً:",
                    color = TextSilver,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Projects List
            items(projectsList) { (path, name) ->
                val isActive = activeProjectPath == path || activeProjectPath.endsWith(path)
                
                // Read subfolders count dynamically if exists
                val projectDir = ProjectContextManager.getProjectDir(path, context)
                val subfoldersCount = if (projectDir.exists()) {
                    projectDir.listFiles { _, filename -> 
                        File(projectDir, filename).isDirectory && !filename.startsWith(".")
                    }?.size ?: 0
                } else {
                    0
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .border(
                            1.dp,
                            if (isActive) MetallicGold else GlassBorder,
                            RoundedCornerShape(12.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) GoldGlassBg else CardSlateBg
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isActive) Icons.Default.CheckCircle else Icons.Default.Menu,
                                    contentDescription = null,
                                    tint = if (isActive) BrightGold else TextSilver,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = name,
                                    color = if (isActive) BrightGold else TextSilver,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp
                                )
                            }
                            
                            if (isActive) {
                                Surface(
                                    color = BrightGold.copy(alpha = 0.15f),
                                    shape = CircleShape,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        "نشط ومفعل",
                                        color = BrightGold,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Project Location Details
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("المسار:", color = TextMuted, fontSize = 10.sp)
                                Text(
                                    path,
                                    color = TextSilver,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("الهيكل الداخلي:", color = TextMuted, fontSize = 10.sp)
                                Text(
                                    "فولدرات مصنفة: $subfoldersCount مجلد محمي",
                                    color = TextSilver,
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Divider line
                        Divider(color = GlassBorder.copy(alpha = 0.5f))

                        // Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Activate Button (if not already active)
                            if (!isActive) {
                                Button(
                                    onClick = {
                                        ProjectManager.setActiveProject(context, path)
                                        activeProjectPath = path
                                        Toast.makeText(context, "🔄 تم تفعيل سياق المشروع: $name", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .height(28.dp)
                                        .testTag("activate_proj_${path.hashCode()}"),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Text("تفعيل السياق", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Spacer(modifier = Modifier.width(1.dp))
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Export template configuration
                                IconButton(
                                    onClick = {
                                        val configFile = File(projectDir, "project_config.json")
                                        if (configFile.exists()) {
                                            try {
                                                val json = configFile.readText()
                                                clipboardManager.setText(AnnotatedString(json))
                                                Toast.makeText(context, "✅ تم نسخ كود القالب للحافظة!", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "فشل نسخ القالب: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            // Fallback minimal json construction
                                            val fallback = """
                                            {
                                              "projectName": "$name",
                                              "template_version": 1,
                                              "folders": []
                                            }
                                            """.trimIndent()
                                            clipboardManager.setText(AnnotatedString(fallback))
                                            Toast.makeText(context, "✅ تم نسخ القالب التقريبي للحافظة!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(GlassWhite, CircleShape)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "تصدير القالب", tint = MetallicGold, modifier = Modifier.size(13.dp))
                                }

                                // Delete (hide from current list, no actual delete of file)
                                if (path != "SmartInbox") {
                                    IconButton(
                                        onClick = {
                                            ProjectManager.removeProject(context, path)
                                            projectsList = ProjectManager.getAllProjects(context)
                                            if (isActive) {
                                                ProjectManager.setActiveProject(context, "SmartInbox")
                                                activeProjectPath = "SmartInbox"
                                            }
                                            Toast.makeText(context, "🗑️ تم إزالة المشروع من قائمة المكتبة", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier
                                            .size(28.dp)
                                            .background(DangerRed.copy(alpha = 0.15f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف المشروع", tint = DangerRed, modifier = Modifier.size(13.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Task 6: Visual template screen / Dialog view for review and edits
    if (showImportEditor) {
        ImportTemplateScreen(
            initialJson = customTemplateJsonInput,
            onDismiss = { showImportEditor = false },
            onImportSuccess = { path, name ->
                projectsList = ProjectManager.getAllProjects(context)
                activeProjectPath = path
                showImportEditor = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTemplateScreen(
    initialJson: String,
    onDismiss: () -> Unit,
    onImportSuccess: (String, String) -> Unit
) {
    val context = LocalContext.current
    var jsonText by remember { mutableStateOf(initialJson) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var previewTemplate by remember { mutableStateOf<ProjectTemplate?>(null) }

    // Editable settings backed by template design values
    var editingProjectName by remember { mutableStateOf("") }
    var templateVersion by remember { mutableStateOf("1") }
    var folderTemplatesList = remember { mutableStateListOf<FolderTemplate>() }

    // When the screen opens, execute initial parse if there's text
    LaunchedEffect(Unit) {
        if (jsonText.isNotBlank()) {
            val parsed = TemplateParser.parse(jsonText)
            if (parsed.isSuccess) {
                val t = parsed.getOrThrow()
                previewTemplate = t
                editingProjectName = t.projectName
                templateVersion = t.templateVersion
                folderTemplatesList.clear()
                folderTemplatesList.addAll(t.folders)
            } else {
                parseError = parsed.exceptionOrNull()?.message
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .background(SlateBg)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = SlateBg
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header of dialog
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "🛠️ محرر ومعاين القوالب الذكي",
                        color = BrightGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "اغلاق", tint = TextSilver)
                    }
                }

                Divider(color = GlassBorder)

                // If previewTemplate is null, show input editor to paste JSON
                if (previewTemplate == null) {
                    Text(
                        "قم بلصق محتوى القالب بتنسيق JSON المعياري بناءً على سياق المرحلة الثالثة:",
                        color = TextSilver,
                        fontSize = 11.sp
                    )

                    com.example.ui.components.CodeEditor(
                        value = jsonText,
                        onValueChange = {
                            jsonText = it
                            parseError = null
                        },
                        language = "json",
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .testTag("json_template_editor_area"),
                        placeholder = "{ \"projectName\": \"...\", \"folders\": [...] }"
                    )

                    parseError?.let { err ->
                        Text(
                            "❌ خطأ في القالب: $err",
                            color = DangerRed,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            if (jsonText.trim().isEmpty()) {
                                parseError = "يرجى لصق نص القالب أولاً!"
                                return@Button
                            }
                            val parsed = TemplateParser.parse(jsonText)
                            if (parsed.isSuccess) {
                                val t = parsed.getOrThrow()
                                previewTemplate = t
                                editingProjectName = t.projectName
                                templateVersion = t.templateVersion
                                folderTemplatesList.clear()
                                folderTemplatesList.addAll(t.folders)
                                parseError = null
                            } else {
                                parseError = parsed.exceptionOrNull()?.message
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("parse_template_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("تحليل ومعاينة القالب كودياً", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    // Preview and editor settings screen
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("تعديل المعايير الأساسية للمشروع:", color = MetallicGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                
                                OutlinedTextField(
                                    value = editingProjectName,
                                    onValueChange = { editingProjectName = it },
                                    label = { Text("اسم المشروع (بالعربية المنسقة)") },
                                    modifier = Modifier.fillMaxWidth().testTag("edit_projectName_field"),
                                    textStyle = TextStyle(color = TextSilver, fontSize = 12.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = MetallicGold,
                                        unfocusedBorderColor = GlassBorder,
                                        focusedLabelColor = MetallicGold
                                    )
                                )
                            }
                        }

                        item {
                            Text("📁 إدارة وتخصيص الفولدرات والمجلدات الفرعية:", color = MetallicGold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        items(folderTemplatesList.toList()) { folder ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, GlassBorder, RoundedCornerShape(8.dp)),
                                colors = CardDefaults.cardColors(containerColor = CardSlateBg)
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "مجلد: ${folder.name}",
                                            color = BrightGold,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        IconButton(
                                            onClick = { folderTemplatesList.remove(folder) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "حذف المجلد", tint = DangerRed, modifier = Modifier.size(14.dp))
                                        }
                                    }

                                    // Folder configuration display
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("مسار المجلد (إنجليزي):", color = TextMuted, fontSize = 10.sp)
                                        Text(folder.path, color = TextSilver, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("الأنواع:", color = TextMuted, fontSize = 10.sp)
                                        Text(folder.fileTypes.joinToString(), color = TextSilver, fontSize = 10.sp)
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("قائمة الكلمات الدالة:", color = TextMuted, fontSize = 10.sp)
                                        Text(
                                            if (folder.keywords.isEmpty()) "توليد تلقائي فارغ" else folder.keywords.joinToString(),
                                            color = TextSilver,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }

                        // Add Folder manually inside parser dialog
                        item {
                            Button(
                                onClick = {
                                    folderTemplatesList.add(
                                        FolderTemplate(
                                            name = "مجلد جديد",
                                            path = "new_folder_path",
                                            fileTypes = listOf("txt"),
                                            keywords = listOf("مثال")
                                        )
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CardSlateBg, contentColor = MetallicGold),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, GlassBorder, RoundedCornerShape(6.dp))
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = MetallicGold, modifier = Modifier.size(16.dp))
                                    Text("أضف مجلد فرعي مخصص للمشروع", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { previewTemplate = null },
                            colors = ButtonDefaults.buttonColors(containerColor = CardSlateBg, contentColor = TextSilver),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(44.dp).border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                        ) {
                            Text("تعديل الكود الأصلي", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                if (editingProjectName.trim().isEmpty()) {
                                    Toast.makeText(context, "الرجاء تحديد اسم للمشروع!", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val finalTemplate = ProjectTemplate(
                                    projectName = editingProjectName,
                                    templateVersion = templateVersion,
                                    folders = folderTemplatesList.toList()
                                )
                                val buildRes = ProjectBuilder.build(finalTemplate, ProjectContextManager.getBaseDir(context).absolutePath)
                                if (buildRes.isSuccess) {
                                    val resPath = buildRes.getOrThrow()
                                    ProjectManager.addProject(context, resPath, finalTemplate.projectName)
                                    ProjectManager.setActiveProject(context, resPath)
                                    onImportSuccess(resPath, finalTemplate.projectName)
                                    Toast.makeText(context, "🎉 تم بناء وحفظ وتفعيل المشروع: ${finalTemplate.projectName} بنجاح!", Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "❌ فشل إنشاء المشروع على القرص: ${buildRes.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1.3f).height(44.dp).testTag("save_and_build_project_btn")
                        ) {
                            Text("بناء وحفظ المشروع", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
