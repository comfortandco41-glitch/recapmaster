package com.recapmaster.app.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUpdateInfo(
    val isAvailable: Boolean = false,
    val latestVersionName: String = "",
    val latestVersionCode: Long = 0L,
    val downloadUrl: String = "",
    val releaseNotes: String = "",
    val isForceUpdate: Boolean = false
)

object AppUpdateManager {
    private const val TAG = "AppUpdateManager"

    private val _updateState = MutableStateFlow<AppUpdateInfo?>(null)
    val updateState: StateFlow<AppUpdateInfo?> = _updateState.asStateFlow()

    /**
     * Checks Firestore (app_config/update) for new version info.
     */
    fun checkForUpdates(context: Context) {
        val currentCode = getCurrentVersionCode(context)
        try {
            FirebaseFirestore.getInstance().collection("app_config").document("update")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Error checking for updates: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot != null && snapshot.exists()) {
                        val latestCode = snapshot.getLong("latest_version_code") ?: 0L
                        val latestName = snapshot.getString("latest_version_name") ?: ""
                        val downloadUrl = snapshot.getString("download_url") ?: ""
                        val releaseNotes = snapshot.getString("release_notes")
                            ?: "A new update is available with bug fixes and new features."
                        val isForce = snapshot.getBoolean("is_force_update") ?: false

                        if (latestCode > currentCode && downloadUrl.isNotBlank()) {
                            val info = AppUpdateInfo(
                                isAvailable = true,
                                latestVersionName = latestName,
                                latestVersionCode = latestCode,
                                downloadUrl = downloadUrl,
                                releaseNotes = releaseNotes,
                                isForceUpdate = isForce
                            )
                            _updateState.value = info
                            Log.d(TAG, "Update available: $latestName (code $latestCode > current $currentCode)")
                        } else {
                            _updateState.value = null
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize update check", e)
        }
    }

    fun dismissUpdate() {
        val current = _updateState.value
        if (current != null && !current.isForceUpdate) {
            _updateState.value = null
        }
    }

    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }
}
