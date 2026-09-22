package com.recapmaster.app.data.downloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class DownloadResult(
    val title: String,
    val durationSeconds: Double,
    val localFile: File
)

class UrlDownloader(private val context: Context? = null) {

    suspend fun downloadUrl(url: String, targetFile: File): DownloadResult = withContext(Dispatchers.IO) {
        if (!Python.isStarted()) {
            if (context != null) {
                Python.start(AndroidPlatform(context))
            } else {
                throw IllegalStateException("Python runtime is not initialized. Please restart the app.")
            }
        }
        val py = Python.getInstance()
        val module = py.getModule("yt_downloader")
        val jsonStr = module.callAttr("download_video", url, targetFile.absolutePath).toString()

        val obj = JSONObject(jsonStr)
        if (!obj.optBoolean("success", false)) {
            val err = obj.optString("error", "Failed to download video from URL")
            throw RuntimeException(err)
        }

        val actualPath = obj.getString("filePath")
        DownloadResult(
            title = obj.optString("title", "Recap Source"),
            durationSeconds = obj.optDouble("duration", 0.0),
            localFile = File(actualPath)
        )
    }
}
