package com.recapmaster.app.data.downloader

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.recapmaster.app.engine.FFmpegEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class DownloadResult(
    val title: String,
    val durationSeconds: Double,
    val localFile: File
)

class UrlDownloader(
    private val context: Context? = null,
    private val ffmpegEngine: FFmpegEngine? = null
) {

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

        val videoPath = obj.getString("filePath")
        val audioPath = obj.optString("audioPath", null)
        val needsMux = obj.optBoolean("needsMux", false)

        val finalFile: File = if (needsMux && !audioPath.isNullOrBlank() && ffmpegEngine != null) {
            val videoFile = File(videoPath)
            val audioFile = File(audioPath)
            if (videoFile.exists() && audioFile.exists()) {
                try {
                    ffmpegEngine.muxVideoAndAudio(videoFile, audioFile, targetFile)
                } catch (_: Throwable) {
                    // Fallback to video file if mux fails
                    videoFile
                }
            } else {
                videoFile
            }
        } else {
            File(videoPath)
        }

        DownloadResult(
            title = obj.optString("title", "Recap Source"),
            durationSeconds = obj.optDouble("duration", 0.0),
            localFile = finalFile
        )
    }
}

