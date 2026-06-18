package com.example

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.db.LogEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppReportHelper {

    fun generateInteractiveHtmlReport(logs: List<LogEntity>, editedMap: Map<Int, String>): String {
        val sb = java.lang.StringBuilder()
        sb.append("""
            <!DOCTYPE html>
            <html lang="ar" dir="rtl">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>تقرير سجل الأحداث التفاعلي - المراقب الذكي</title>
                <style>
                    :root {
                        --bg-slate: #0b0f19;
                        --card-bg: #111827;
                        --border-gold: #d97706;
                        --text-silver: #e2e8f0;
                        --text-gray: #94a3b8;
                        --gold-glow: #f59e0b;
                        --success-tint: rgba(16, 185, 129, 0.15);
                        --success-text: #10b981;
                        --error-tint: rgba(239, 68, 68, 0.15);
                        --error-text: #ef4444;
                    }
                    body {
                        background-color: var(--bg-slate);
                        color: var(--text-silver);
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        margin: 0;
                        padding: 24px;
                        line-height: 1.6;
                    }
                    .container {
                        max-width: 1000px;
                        margin: 0 auto;
                    }
                    .header-card {
                        background: linear-gradient(135deg, #111827 0%, #1f2937 100%);
                        border: 1px solid var(--border-gold);
                        border-radius: 16px;
                        padding: 24px;
                        margin-bottom: 24px;
                        box-shadow: 0 10px 30px rgba(0,0,0,0.5);
                        text-align: center;
                    }
                    .header-card h1 {
                        color: var(--gold-glow);
                        margin: 0 0 10px 0;
                        font-size: 26px;
                        text-shadow: 0 0 10px rgba(245, 158, 11, 0.3);
                    }
                    .header-card p {
                        color: var(--text-gray);
                        margin: 0;
                        font-size: 14px;
                    }
                    .controls {
                        display: flex;
                        gap: 16px;
                        margin-bottom: 20px;
                        flex-wrap: wrap;
                        align-items: center;
                    }
                    .search-box {
                        flex: 1;
                        min-width: 280px;
                        position: relative;
                    }
                    .search-input {
                        width: 100%;
                        padding: 12px 20px;
                        border-radius: 12px;
                        background-color: var(--card-bg);
                        border: 1px solid #374151;
                        color: var(--text-silver);
                        font-size: 14px;
                        outline: none;
                        box-sizing: border-box;
                        transition: border-color 0.2s;
                    }
                    .search-input:focus {
                        border-color: var(--border-gold);
                        box-shadow: 0 0 0 2px rgba(217, 119, 6, 0.2);
                    }
                    .stats-bar {
                        display: flex;
                        gap: 15px;
                        font-size: 13px;
                        align-items: center;
                        color: var(--text-gray);
                    }
                    .stat-tag {
                        background-color: #1e293b;
                        padding: 6px 12px;
                        border-radius: 20px;
                        color: var(--text-silver);
                        border: 1px solid #334155;
                    }
                    .stat-tag.success {
                        background-color: rgba(16,185,129,0.1);
                        color: var(--success-text);
                        border-color: rgba(16,185,129,0.2);
                    }
                    .stat-tag.error {
                        background-color: rgba(239,68,68,0.1);
                        color: var(--error-text);
                        border-color: rgba(239,68,68,0.2);
                    }
                    .table-container {
                        background-color: var(--card-bg);
                        border: 1px solid #1f2937;
                        border-radius: 16px;
                        overflow: hidden;
                        box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
                    }
                    table {
                        width: 100%;
                        border-collapse: collapse;
                        text-align: right;
                    }
                    th {
                        background-color: #1f2937;
                        color: var(--gold-glow);
                        padding: 14px 16px;
                        font-weight: 600;
                        font-size: 14px;
                        cursor: pointer;
                        user-select: none;
                        border-bottom: 2px solid var(--border-gold);
                    }
                    th:hover {
                        background-color: #374151;
                    }
                    td {
                        padding: 14px 16px;
                        border-bottom: 1px solid #1f2937;
                        font-size: 13px;
                        vertical-align: middle;
                    }
                    tr.row-success {
                        background-color: var(--success-tint);
                    }
                    tr.row-error {
                        background-color: var(--error-tint);
                    }
                    tr:hover {
                        filter: brightness(1.1);
                    }
                    .badge {
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 16px;
                    }
                    .timestamp {
                        color: var(--text-gray);
                        font-family: monospace;
                        font-size: 12px;
                        white-space: nowrap;
                    }
                    .msg-text {
                        font-weight: bold;
                        margin-bottom: 4px;
                        color: var(--text-silver);
                    }
                    .details-text {
                        color: var(--text-gray);
                        font-size: 11px;
                    }
                    tr.row-error .msg-text {
                        color: var(--error-text);
                    }
                    tr.row-error .details-text {
                        color: rgba(239, 68, 68, 0.82);
                    }
                    @media (max-width: 600px) {
                        body {
                            padding: 12px;
                        }
                        th, td {
                            padding: 10px;
                            font-size: 12px;
                        }
                        .timestamp {
                            font-size: 10px;
                        }
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header-card">
                        <h1>📊 تقرير سجل الأحداث التفاعلي</h1>
                        <p>تطبيق المراقب الذكي • تم التوليد في: <span id="generation-time"></span></p>
                    </div>
                    
                    <div class="controls">
                        <div class="search-box">
                            <input type="text" id="search-input" class="search-input" placeholder="🔍 تصفية الأحداث بالبحث عن كلمة مفتاحية...">
                        </div>
                        <div class="stats-bar">
                            <span class="stat-tag">الإجمالي: <strong id="count-total">0</strong></span>
                            <span class="stat-tag success">ناجح: <strong id="count-success">0</strong></span>
                            <span class="stat-tag error">فشل: <strong id="count-error">0</strong></span>
                        </div>
                    </div>
                    
                    <div class="table-container">
                        <table id="logs-table">
                            <thead>
                                <tr>
                                    <th onclick="sortTable(0)">الحالة ↕</th>
                                    <th onclick="sortTable(1)">نوع العملية ↕</th>
                                    <th onclick="sortTable(2)">الحدث والتفاصيل ↕</th>
                                    <th onclick="sortTable(3)">الوقت ↕</th>
                                </tr>
                            </thead>
                            <tbody id="table-body">
        """.trimIndent())

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val nowStr = sdf.format(Date())

        for (log in logs) {
            val detail = editedMap[log.id] ?: log.details ?: ""
            val isFail = log.message.contains("❌") || log.message.contains("فشل") || detail.contains("❌") || detail.contains("فشل")
            val rowClass = if (isFail) "row-error" else "row-success"
            
            val typeLabel = when (log.type) {
                "builder" -> "منظم المجلد"
                "executor" -> "محرك الأوامر"
                "treedoc" -> "مستكشف الملفات"
                "gemini" -> "ذكاء اصطناعي"
                else -> "النظام والخدمات"
            }

            val icon = when (log.type) {
                "builder" -> "📄"
                "executor" -> "⚙️"
                "treedoc" -> "📁"
                "gemini" -> "🧠"
                else -> "ℹ"
            }
            val finalIcon = if (isFail) "❌" else icon

            sb.append("""
                <tr class="$rowClass" data-fail="$isFail">
                    <td style="text-align:center;"><span class="badge">$finalIcon</span></td>
                    <td><strong>$typeLabel</strong></td>
                    <td>
                        <div class="msg-text">${htmlEscape(log.message)}</div>
                        <div class="details-text">${htmlEscape(detail)}</div>
                    </td>
                    <td><span class="timestamp">${sdf.format(Date(log.timestamp))}</span></td>
                </tr>
            """.trimIndent())
        }

        sb.append("""
                            </tbody>
                        </table>
                    </div>
                </div>
                
                <script>
                    document.getElementById('generation-time').innerText = "$nowStr";
                    
                    const searchInput = document.getElementById('search-input');
                    const tableBody = document.getElementById('table-body');
                    const rows = tableBody.getElementsByTagName('tr');
                    
                    function updateStats() {
                        let total = 0, success = 0, error = 0;
                        for (let row of rows) {
                            if (row.style.display !== 'none') {
                                total++;
                                if (row.getAttribute('data-fail') === 'true') {
                                    error++;
                                } else {
                                    success++;
                                }
                            }
                        }
                        document.getElementById('count-total').innerText = total;
                        document.getElementById('count-success').innerText = success;
                        document.getElementById('count-error').innerText = error;
                    }
                    
                    searchInput.addEventListener('input', function() {
                        const filter = searchInput.value.toLowerCase();
                        for (let row of rows) {
                            const text = row.textContent.toLowerCase();
                            if (text.includes(filter)) {
                                row.style.display = '';
                            } else {
                                row.style.display = 'none';
                            }
                        }
                        updateStats();
                    });
                    
                    let sortDirections = [true, true, true, false];
                    function sortTable(colIndex) {
                        const table = document.getElementById("logs-table");
                        let switching = true;
                        let shouldSwitch = false;
                        let i;
                        const dir = sortDirections[colIndex] ? "asc" : "desc";
                        sortDirections[colIndex] = !sortDirections[colIndex];
                        
                        while (switching) {
                            switching = false;
                            const tableRows = table.rows;
                            for (i = 1; i < (tableRows.length - 1); i++) {
                                shouldSwitch = false;
                                const x = tableRows[i].getElementsByTagName("TD")[colIndex];
                                const y = tableRows[i + 1].getElementsByTagName("TD")[colIndex];
                                
                                let xVal = x.textContent.toLowerCase().trim();
                                let yVal = y.textContent.toLowerCase().trim();
                                
                                if (dir === "asc") {
                                    if (xVal > yVal) {
                                        shouldSwitch = true;
                                        break;
                                    }
                                } else if (dir === "desc") {
                                    if (xVal < yVal) {
                                        shouldSwitch = true;
                                        break;
                                    }
                                }
                            }
                            if (shouldSwitch) {
                                tableRows[i].parentNode.insertBefore(tableRows[i + 1], tableRows[i]);
                                switching = true;
                            }
                        }
                    }
                    
                    updateStats();
                </script>
            </body>
            </html>
        """.trimIndent())
        return sb.toString()
    }

    private fun htmlEscape(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
    }

    fun generateTxtReport(logs: List<LogEntity>, editedMap: Map<Int, String>): String {
        val sb = java.lang.StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("=== تقرير سجل الأحداث - المراقب الذكي ===")
        sb.appendLine("تاريخ التصدير: ${sdf.format(Date())}")
        sb.appendLine("========================================")
        for (log in logs) {
            val details = editedMap[log.id] ?: log.details ?: ""
            val timeStr = sdf.format(Date(log.timestamp))
            sb.appendLine("[$timeStr] [${log.type.uppercase(Locale.ROOT)}] ${log.message}")
            if (details.isNotBlank()) {
                sb.appendLine("   التفاصيل: $details")
            }
        }
        return sb.toString()
    }

    fun generateCsvReport(logs: List<LogEntity>, editedMap: Map<Int, String>): String {
        val sb = java.lang.StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        sb.appendLine("ID,Timestamp,Type,Message,Details,Status")
        for (log in logs) {
            val details = editedMap[log.id] ?: log.details ?: ""
            val isFail = log.message.contains("❌") || log.message.contains("فشل") || details.contains("❌") || details.contains("فشل")
            val status = if (isFail) "FAILED" else "SUCCESS"
            val timeStr = sdf.format(Date(log.timestamp))
            
            val csvMsg = log.message.replace("\"", "\"\"")
            val csvDet = details.replace("\"", "\"\"")
            sb.appendLine("${log.id},\"$timeStr\",\"${log.type}\",\"$csvMsg\",\"$csvDet\",\"$status\"")
        }
        return sb.toString()
    }

    fun generateJsonReport(logs: List<LogEntity>, editedMap: Map<Int, String>): String {
        val rootArray = JSONArray()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        for (log in logs) {
            val details = editedMap[log.id] ?: log.details ?: ""
            val isFail = log.message.contains("❌") || log.message.contains("فشل") || details.contains("❌") || details.contains("فشل")
            val obj = JSONObject().apply {
                put("id", log.id)
                put("timestamp", sdf.format(Date(log.timestamp)))
                put("type", log.type)
                put("message", log.message)
                put("details", details)
                put("status", if (isFail) "FAILED" else "SUCCESS")
            }
            rootArray.put(obj)
        }
        return rootArray.toString(4)
    }

    fun saveAndOpenHtmlReport(context: Context, html: String) {
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val reportFile = File(root, "interactive_log_report.html")
            reportFile.writeText(html, Charsets.UTF_8)
            
            // Copy html code to clipboard
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("HTML Report", html))
            
            // FileProvider trigger
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, reportFile)
            
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val chooserIntent = Intent.createChooser(viewIntent, "عرض التقرير التفاعلي عبر المتصفح")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
            
            android.widget.Toast.makeText(context, "✅ تم توليد وتصدير التقرير بنجاح وحفظه في: ${reportFile.name}", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "⚠️ فشل في فتح المتصفح التلقائي: ${e.message}\nلكن تم نسخ ملف HTML للحافظة بنجاح!", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun saveAndShareFile(context: Context, content: String, filename: String, mimeType: String) {
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(root, filename)
            file.writeText(content, Charsets.UTF_8)
            
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooserIntent = Intent.createChooser(shareIntent, "مشاركة الملف المصدر")
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooserIntent)
            
            android.widget.Toast.makeText(context, "✅ تم حفظ الملف باسم ($filename) بنجاح وجاهز للمشاركة!", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "❌ فشل حفظ ومشاركة الملف: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
