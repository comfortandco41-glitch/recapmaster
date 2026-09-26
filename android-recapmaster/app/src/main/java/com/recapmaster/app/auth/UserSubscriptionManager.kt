package com.recapmaster.app.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UserSubscription(
    val uid: String = "",
    val email: String = "",
    val displayName: String = "",
    val createdAtMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val status: String = "active", // "active", "expired", "extended"
    val isUnlimited: Boolean = true,
    val isAdmin: Boolean = false,
    val note: String = ""
) {
    val isExpired: Boolean
        get() {
            if (isAdmin) return false
            if (expiresAtMs <= 0L) return true
            return System.currentTimeMillis() > expiresAtMs
        }

    val remainingDays: Long
        get() {
            if (isAdmin) return 9999L
            val diff = expiresAtMs - System.currentTimeMillis()
            return if (diff > 0) diff / (24 * 60 * 60 * 1000L) else 0L
        }

    val remainingHours: Long
        get() {
            if (isAdmin) return 99999L
            val diff = expiresAtMs - System.currentTimeMillis()
            return if (diff > 0) (diff / (60 * 60 * 1000L)) % 24 else 0L
        }

    val formattedExpiry: String
        get() {
            if (isAdmin) return "Admin (Unlimited)"
            if (expiresAtMs <= 0L) return "Expired"
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(expiresAtMs))
        }
}

object UserSubscriptionManager {

    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private var snapshotListener: ListenerRegistration? = null
    private var prefs: SharedPreferences? = null

    private val _subscription = MutableStateFlow<UserSubscription?>(null)
    val subscription: StateFlow<UserSubscription?> = _subscription.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("user_sub_cache", Context.MODE_PRIVATE)
    }

    /**
     * Called whenever Firebase Auth state changes.
     * Attaches a real-time Firestore listener to users/{uid} to automatically
     * grant 7 days to new users, track expiration, and react immediately when an
     * admin extends the date in Firebase Console.
     */
    fun onUserChanged(user: FirebaseUser?) {
        snapshotListener?.remove()
        snapshotListener = null

        if (user == null) {
            _subscription.value = null
            _isLoading.value = false
            return
        }

        _isLoading.value = true

        // Load cached subscription first for immediate responsiveness
        loadFromCache(user.uid)

        val docRef = firestore.collection("users").document(user.uid)
        snapshotListener = docRef.addSnapshotListener { snapshot, error ->
            _isLoading.value = false
            if (error != null) {
                error.printStackTrace()
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                // New user! Grant 7 days unlimited access
                val now = System.currentTimeMillis()
                val sevenDaysMs = 7L * 24 * 60 * 60 * 1000L
                val expiresAt = now + sevenDaysMs
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expiresAt))

                val initialData = hashMapOf(
                    "uid" to user.uid,
                    "email" to (user.email ?: ""),
                    "displayName" to (user.displayName ?: ""),
                    "createdAtMs" to now,
                    "expiresAtMs" to expiresAt,
                    "expiresAt" to Timestamp(Date(expiresAt)), // Firebase Timestamp gives a Calendar Date Picker in Console!
                    "expiryDate" to dateStr, // Human readable: "2026-10-02"
                    "extendDays" to 0, // Admin can easily type 30 to add 30 days
                    "status" to "active",
                    "isUnlimited" to true,
                    "isAdmin" to false,
                    "note" to "New user 7-day trial"
                )

                docRef.set(initialData).addOnSuccessListener {
                    val sub = UserSubscription(
                        uid = user.uid,
                        email = user.email ?: "",
                        displayName = user.displayName ?: "",
                        createdAtMs = now,
                        expiresAtMs = expiresAt,
                        status = "active",
                        isUnlimited = true,
                        isAdmin = false,
                        note = "New user 7-day trial"
                    )
                    _subscription.value = sub
                    saveToCache(sub)
                }
            } else {
                // Existing user: parse attributes (handles Long, Timestamp, and flexible admin inputs)
                val uid = snapshot.getString("uid") ?: user.uid
                val email = snapshot.getString("email") ?: (user.email ?: "")
                val displayName = snapshot.getString("displayName") ?: (user.displayName ?: "")
                val status = snapshot.getString("status") ?: "active"
                val isUnlimited = snapshot.getBoolean("isUnlimited") ?: true
                val isAdmin = snapshot.getBoolean("isAdmin") ?: false
                val note = snapshot.getString("note") ?: ""

                val createdAtMs = parseTimeToMs(snapshot.get("createdAtMs") ?: snapshot.get("createdAt"))
                val extendDays = snapshot.getLong("extendDays") ?: 0L

                // Check in order: expiresAt (Timestamp/Calendar) -> expiryDate (Text) -> expiresAtMs (Long)
                var expiresAtMs = parseTimeToMs(
                    snapshot.get("expiresAt")
                        ?: snapshot.get("expiryDate")
                        ?: snapshot.get("expiresAtMs")
                        ?: snapshot.get("extendedUntil")
                )

                // Fallback if missing: 7 days from createdAt
                if (expiresAtMs <= 0L && createdAtMs > 0L) {
                    expiresAtMs = createdAtMs + (7L * 24 * 60 * 60 * 1000L)
                }

                // If admin entered extendDays (e.g. 30), extend subscription by that many days
                if (extendDays > 0) {
                    val base = if (expiresAtMs > System.currentTimeMillis()) expiresAtMs else System.currentTimeMillis()
                    expiresAtMs = base + (extendDays * 24 * 60 * 60 * 1000L)
                }

                // If document doesn't have the nice Timestamp yet, backfill it so console displays the calendar picker
                if (snapshot.get("expiresAt") !is Timestamp && expiresAtMs > 0L) {
                    try {
                        val dStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expiresAtMs))
                        docRef.update(
                            mapOf(
                                "expiresAt" to Timestamp(Date(expiresAtMs)),
                                "expiryDate" to dStr
                            )
                        )
                    } catch (_: Exception) {}
                }

                val sub = UserSubscription(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    createdAtMs = createdAtMs,
                    expiresAtMs = expiresAtMs,
                    status = status,
                    isUnlimited = isUnlimited,
                    isAdmin = isAdmin,
                    note = note
                )
                _subscription.value = sub
                saveToCache(sub)
            }
        }
    }

    private fun parseTimeToMs(raw: Any?): Long {
        return when (raw) {
            is Long -> raw
            is Number -> raw.toLong()
            is Timestamp -> raw.toDate().time
            is Date -> raw.time
            is String -> {
                val clean = raw.trim()
                try {
                    clean.toLong()
                } catch (_: Exception) {
                    val formats = listOf("yyyy-MM-dd", "yyyy-MM-dd HH:mm", "yyyy/MM/dd", "dd-MM-yyyy")
                    var parsed = 0L
                    for (fmt in formats) {
                        try {
                            val sdf = SimpleDateFormat(fmt, Locale.US)
                            val d = sdf.parse(clean)
                            if (d != null) {
                                parsed = d.time
                                break
                            }
                        } catch (_: Exception) {}
                    }
                    parsed
                }
            }
            else -> 0L
        }
    }

    private fun saveToCache(sub: UserSubscription) {
        prefs?.edit()?.apply {
            putString("uid_${sub.uid}", sub.uid)
            putLong("expiresAt_${sub.uid}", sub.expiresAtMs)
            putBoolean("isAdmin_${sub.uid}", sub.isAdmin)
            putString("status_${sub.uid}", sub.status)
            apply()
        }
    }

    private fun loadFromCache(uid: String) {
        val p = prefs ?: return
        val cachedExp = p.getLong("expiresAt_$uid", 0L)
        if (cachedExp > 0L) {
            _subscription.value = UserSubscription(
                uid = uid,
                expiresAtMs = cachedExp,
                isAdmin = p.getBoolean("isAdmin_$uid", false),
                status = p.getString("status_$uid", "active") ?: "active"
            )
        }
    }

    fun refresh(user: FirebaseUser?) {
        if (user != null) {
            onUserChanged(user)
        }
    }

    /**
     * Called when user finishes watching a Rewarded Ad.
     * Extends expiration time by [hours] (default 24h) from now (or from current expiration if still active).
     */
    fun grantAdRewardHours(user: FirebaseUser?, hours: Long = 24, onComplete: ((Boolean) -> Unit)? = null) {
        if (user == null) {
            onComplete?.invoke(false)
            return
        }
        val currentSub = _subscription.value
        val baseTime = if (currentSub != null && !currentSub.isExpired) {
            currentSub.expiresAtMs
        } else {
            System.currentTimeMillis()
        }
        val newExpiry = baseTime + (hours * 60 * 60 * 1000L)
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(newExpiry))

        val updates = hashMapOf<String, Any>(
            "expiresAtMs" to newExpiry,
            "expiresAt" to Timestamp(Date(newExpiry)),
            "expiryDate" to dateStr,
            "status" to "active"
        )

        firestore.collection("users").document(user.uid)
            .update(updates)
            .addOnSuccessListener {
                val updatedSub = (currentSub ?: UserSubscription(uid = user.uid, email = user.email ?: "")).copy(
                    expiresAtMs = newExpiry,
                    status = "active"
                )
                _subscription.value = updatedSub
                saveToCache(updatedSub)
                onComplete?.invoke(true)
            }
            .addOnFailureListener {
                onComplete?.invoke(false)
            }
    }
}
