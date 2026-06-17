package com.example.service

import android.content.Context
import android.os.Build
import android.os.Environment
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.db.AppDatabase
import com.example.db.LogEntity
import com.example.engine.BuilderEngine
import kotlinx.coroutines.*
import java.io.File

/**
 * خدمة البلاطة السريعة (Quick Settings Tile)
 *
 * تولد تقريراً شجرياً سريعاً للمنصة الذكية ومجلداتها وتنسخه للحافظة فوراً بنقرة واحدة.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickReportTileService : TileService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.label = "تقرير سريع"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        
        tile.state = Tile.STATE_ACTIVE
        tile.label = "جاري التوليد..."
        tile.updateTile()

        scope.launch {
            val baseDir = getBaseDir()
            val database = AppDatabase.getDatabase(applicationContext)
            
            // Settings matching main configuration
            val settings = mapOf<String, Any>(
                "absolute_path_handling" to "relative",
                "base_dir" to baseDir.absolutePath,
                "directive_prefixes" to listOf("@builder"),
                "executor_prefixes" to listOf("@executor"),
                "treedoc_prefixes" to listOf("@treedoc")
            )
            val builderEngine = BuilderEngine(applicationContext, settings)
            
            database.dao().insertLog(
                LogEntity(
                    type = "system",
                    message = "البلاطة السريعة",
                    details = "تم الضغط على البلاطة السريعة لتوليد تقرير شجري فوري."
                )
            )

            try {
                // Run Treedoc report for primary directory with clipboard copyEnabled = true
                val (msg, data) = builderEngine.runTreedoc(".", "html", true)
                
                database.dao().insertLog(
                    LogEntity(
                        type = "treedoc",
                        message = "البلاطة السريعة: $msg",
                        details = data?.get("report") ?: ""
                    )
                )

                Toast.makeText(applicationContext, "تم نسخ تقرير TreeDoc للحافظة بنجاح!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(applicationContext, "فشل توليد التقرير السريع: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "تقرير سريع"
                tile.updateTile()
            }
        }
    }

    private fun getBaseDir(): File {
        return if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            File(getExternalFilesDir(null), "SmartPlatform")
        } else {
            File(filesDir, "SmartPlatform")
        }.also { it.mkdirs() }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
