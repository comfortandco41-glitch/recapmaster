package com.recapmaster.app.engine

import org.json.JSONObject
import java.io.File

object SubtitleGenerator {

    private fun secToAssTime(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong()
        val h = totalMs / 3600000
        val m = (totalMs % 3600000) / 60000
        val s = (totalMs % 60000) / 1000
        val cs = (totalMs % 1000) / 10
        return String.format("%d:%02d:%02d.%02d", h, m, s, cs)
    }

    fun generateAssFile(
        transcriptJson: String,
        outputAssFile: File,
        videoWidth: Int = 1280,
        videoHeight: Int = 720,
        placement: String = "bottom",
        fontScale: Float = 1.0f,
        marginV: Int = 30
    ): File {
        val alignment = when (placement.lowercase()) {
            "top" -> 8
            "center", "middle" -> 5
            "bottom_left" -> 1
            "bottom_right" -> 3
            "top_left" -> 7
            "top_right" -> 9
            else -> 2 // bottom center
        }

        val fontSize = (videoHeight * 0.045 * fontScale).toInt().coerceAtLeast(16)

        val header = """
[Script Info]
Title: Burmese Dubbed Subtitles
ScriptType: v4.00+
WrapStyle: 0
ScaledBorderAndShadow: yes
PlayResX: $videoWidth
PlayResY: $videoHeight

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Padauk,$fontSize,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2.0,1.0,$alignment,16,16,$marginV,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """.trimIndent()

        val lines = mutableListOf(header)

        try {
            val json = JSONObject(transcriptJson)
            val segments = json.optJSONArray("segments")
            if (segments != null) {
                for (i in 0 until segments.length()) {
                    val seg = segments.getJSONObject(i)
                    val start = seg.optDouble("start", 0.0)
                    val end = seg.optDouble("end", 0.0)
                    var text = seg.optString("text", "").trim()

                    if (text.isEmpty() || end <= start) continue

                    // Auto line wrap long Burmese sentences
                    if (text.length > 34 && text.contains("။") && !text.endsWith("။")) {
                        val parts = text.split("။", limit = 2)
                        text = "${parts[0].trim()}။\\N${parts[1].trim()}"
                    } else if (text.length > 38 && text.contains(" ")) {
                        val words = text.split(" ")
                        val mid = words.size / 2
                        text = words.subList(0, mid).joinToString(" ") + "\\N" + words.subList(mid, words.size).joinToString(" ")
                    }

                    val startStr = secToAssTime(start)
                    val endStr = secToAssTime(end)
                    lines.add("Dialogue: 0,$startStr,$endStr,Default,,0,0,0,,$text")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        outputAssFile.parentFile?.mkdirs()
        outputAssFile.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        return outputAssFile
    }
}
