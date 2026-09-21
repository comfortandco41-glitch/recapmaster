package com.recapmaster.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperEngine(private val context: Context) {

    companion object {
        init {
            try {
                System.loadLibrary("whisper-jni")
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
        }
    }

    private external fun initModel(modelPath: String): Long
    private external fun transcribePcm(ctxPtr: Long, pcmData: FloatArray, language: String): String
    private external fun freeModel(ctxPtr: Long)

    private var contextPtr: Long = 0

    fun loadModel(modelFile: File): Boolean {
        if (!modelFile.exists()) return false
        contextPtr = initModel(modelFile.absolutePath)
        return contextPtr != 0L
    }

    fun release() {
        if (contextPtr != 0L) {
            freeModel(contextPtr)
            contextPtr = 0L
        }
    }

    suspend fun transcribeWav(wavFile: File, language: String = "auto"): String = withContext(Dispatchers.Default) {
        if (contextPtr == 0L) {
            throw IllegalStateException("Whisper model is not initialized. Please load model first.")
        }

        val pcmFloats = readWavToFloatArray(wavFile)
        transcribePcm(contextPtr, pcmFloats, language)
    }

    /**
     * Parses standard 16kHz, 16-bit Mono PCM WAV file into normalized FloatArray [-1.0f, 1.0f].
     */
    private fun readWavToFloatArray(wavFile: File): FloatArray {
        val bytes = wavFile.readBytes()
        if (bytes.size <= 44) return FloatArray(0)

        // Skip 44-byte standard RIFF header
        val pcmByteCount = bytes.size - 44
        val sampleCount = pcmByteCount / 2
        val floatArray = FloatArray(sampleCount)

        val buffer = ByteBuffer.wrap(bytes, 44, pcmByteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val shortVal = buffer.short
            floatArray[i] = shortVal / 32768.0f
        }

        return floatArray
    }
}
