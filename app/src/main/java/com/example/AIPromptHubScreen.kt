package com.example

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

data class PromptTemplateItem(
    val id: String,
    val title: String,
    val iconEmoji: String,
    val description: String,
    val promptText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIPromptHubScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val prefs = remember(context) { context.getSharedPreferences("SmartPrefs", android.content.Context.MODE_PRIVATE) }
    
    // Loaded system instruction
    var activeSystemInstruction by remember {
        mutableStateOf(prefs.getString("ai_system_instruction", "") ?: "")
    }

    val promptItems = remember {
        listOf(
            PromptTemplateItem(
                id = "creative_designer",
                title = "أمر توليد المحتوى (Creative Designer)",
                iconEmoji = "🎨",
                description = "يقوم بتوجيه محرك الذكاء لاشتقاق أفكار مبتكرة وكتابة مقالات ترويجية أو إبداعية غنية بالتنسيق التلقائي المتناسق.",
                promptText = "أنت مصمم ومبدع محتوى احترافي للمنصة الذكية. قم بصياغة مقالات ونصوص تسويقية أو قصصية بأسلوب جذاب ولغة بصرية راقية مع تنسيق العناوين والفقرات للغة العرض المطلوبة."
            ),
            PromptTemplateItem(
                id = "senior_developer",
                title = "أمر المبرمج المحترف (Senior Developer)",
                iconEmoji = "💻",
                description = "يقوم بتحويل الأفكار الفنية والمخططات إلى أكواد برمجية معيارية بجميع لغات التطوير مع التخطيط التلقائي لهيكل كتل التعليمات.",
                promptText = "أنت مبرمج محترف وخبير في هندسة البرمجيات بالمنصة الذكية. صمم كودًا نظيفًا ومعياريًا مع التعليقات الوافية وهيكل الملفات المناسب للغة المطلوبة مستخدماً كتل @builder المناسبة لبناء الملفات المصدرية تلقائياً."
            ),
            PromptTemplateItem(
                id = "security_analyst",
                title = "أمر محلل الكود الكفء (Security Analyst)",
                iconEmoji = "🛡️",
                description = "يقوم بفحص الأكواد البرمجية والتوجيهات التقنية للكشف عن الثغرات الأمنية واقتراح التدابير الحمائية لإصلاح الأخطاء تلقائياً.",
                promptText = "أنت محلل ومستشار أمني كفء بالمنصة الذكية. قم بمراجعة الأكواد البرمجية والتطبيقات المقترحة بدقة للتأكد من خلوها من العقبات الأمنية، وتطبيق قواعد الجدار الناري والتصحيحات المرجعية بترميز آمن."
            ),
            PromptTemplateItem(
                id = "academic_assistant",
                title = "أمر المساعد الأكاديمي (Academic Assistant)",
                iconEmoji = "🎓",
                description = "لتلخيص المستندات الكبيرة، تبسيط النظريات الأكاديمية الصعبة وتصميم مقررات تدريبية وشروحات تخصصية متدرجة وسهلة الحفظ.",
                promptText = "أنت مساعد أكاديمي مرشد ومدرس خبير بالمنصة الذكية. ساعد المستخدم في تلخيص المناهج، تبسيط النظريات التعليمية والعلوم الصعبة لغوياً، وتوفير شروحات علمية مع الرسوم والجداول المرجعية."
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "مركز تعليمات المساعدين الذكي",
                        color = BrightGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "للخلف",
                            tint = MetallicGold
                        )
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Introductory Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardSlateBg),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, GlassBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚡", fontSize = 16.sp)
                            Text(
                                "تخصيص سلوك الذكاء الاصطناعي",
                                color = BrightGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            "اختر أحد التوجيهات المهيأة مسبقاً لحقنها في محرك المساعد الذكي. هذا سيعدل التعليمات التوجيهية الأساسية (System Instruction) الخاصة بالمساعد ليتناسب مع مجال تخصصك المفضل.",
                            color = TextSilver,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        
                        if (activeSystemInstruction.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = GoldGlassBg,
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(0.8.dp, MetallicGold.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = BrightGold, modifier = Modifier.size(14.dp))
                                    Text(
                                        "التعليمات النشطة مخصصة ومحفونة بنجاح في الإعدادات المعيارية.",
                                        color = BrightGold,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Cards items list
            items(promptItems) { item ->
                val isActive = activeSystemInstruction == item.promptText

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isActive) MetallicGold else GlassBorder,
                            RoundedCornerShape(14.dp)
                        ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive) GoldGlassBg else CardSlateBg
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Title row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(item.iconEmoji, fontSize = 18.sp)
                                Text(
                                    item.title,
                                    color = if (isActive) BrightGold else TextSilver,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                            
                            if (isActive) {
                                Badge(
                                    containerColor = MetallicGold,
                                    contentColor = SlateBg
                                ) {
                                    Text("محقونة ونشطة", fontSize = 8.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        }

                        // Description
                        Text(
                            item.description,
                            color = TextGray,
                            fontSize = 10.5.sp,
                            lineHeight = 14.5.sp
                        )

                        // Code quote card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(0.8.dp, GlassBorder, RoundedCornerShape(8.dp)),
                            colors = CardDefaults.cardColors(containerColor = SlateBg),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = item.promptText,
                                color = TextSilver,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(10.dp),
                                lineHeight = 14.sp
                            )
                        }

                        // Action Buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Copy button
                            Button(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(item.promptText))
                                    Toast.makeText(context, "📋 تم نسخ كود التلقين إلى الحافظة!", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GlassWhite, contentColor = TextSilver),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(8.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Text("نسخ التلقين", fontSize = 10.sp)
                                }
                            }

                            // Share button
                            Button(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, item.promptText)
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, "مشاركة التوجيه")
                                    context.startActivity(shareIntent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GlassWhite, contentColor = TextSilver),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(34.dp)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(8.dp)),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(12.dp))
                                    Text("مشاركة", fontSize = 10.sp)
                                }
                            }

                            // Inject button
                            Button(
                                onClick = {
                                    prefs.edit().putString("ai_system_instruction", item.promptText).apply()
                                    activeSystemInstruction = item.promptText
                                    Toast.makeText(context, "🎉 تم حقن وتثبيت التوجيه التلقائي للمساعد في النظام بنجاح!", Toast.LENGTH_LONG).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MetallicGold, contentColor = SlateBg),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .weight(1.5f)
                                    .height(34.dp)
                                    .testTag("inject_prompt_${item.id}"),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Text("حقن في المساعد", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Reset custom prompt card
            if (activeSystemInstruction.isNotBlank()) {
                item {
                    Button(
                        onClick = {
                            prefs.edit().remove("ai_system_instruction").apply()
                            activeSystemInstruction = ""
                            Toast.makeText(context, "🔁 تم إعادة ضبط التوجيه للمرجعية الافتراضية بنجاح.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed.copy(alpha = 0.15f), contentColor = DangerRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .border(1.dp, DangerRed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Text("إعادة ضبط تعليمات المساعد الافتراضية", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
