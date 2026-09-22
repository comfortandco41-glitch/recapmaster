package com.recapmaster.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.recapmaster.app.pipeline.RecapPipelineManager

class RecapApplication : Application() {

    companion object {
        lateinit var instance: RecapApplication
            private set
    }

    val pipelineManager: RecapPipelineManager by lazy {
        RecapPipelineManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Ensure Chaquopy Python runtime is initialized safely
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
