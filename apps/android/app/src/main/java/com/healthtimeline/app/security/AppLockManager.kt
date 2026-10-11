package com.healthtimeline.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppLockManager(
    private val context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime
) {
    private val preferences = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val enabledMutable = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    private val lockedMutable = MutableStateFlow(enabledMutable.value)
    private var backgroundAt: Long? = null
    private var externalActivityDepth = 0

    val enabled = enabledMutable.asStateFlow()
    val locked = lockedMutable.asStateFlow()

    fun setEnabled(value: Boolean): Boolean {
        if (!writeBoolean(KEY_ENABLED, value)) return false
        enabledMutable.value = value
        lockedMutable.value = false
        backgroundAt = null
        externalActivityDepth = 0
        return true
    }

    fun unlock() {
        lockedMutable.value = false
        backgroundAt = null
        externalActivityDepth = 0
    }

    fun lock() {
        if (enabledMutable.value) lockedMutable.value = true
        externalActivityDepth = 0
    }

    fun onAppBackgrounded() {
        if (externalActivityDepth == 0 && enabledMutable.value) backgroundAt = elapsedRealtime()
    }

    fun onAppForegrounded() {
        // A picker callback may be lost when its Compose launcher is removed/recreated.
        // Its exemption is for one external round-trip, never all future background trips.
        externalActivityDepth = 0
        val leftAt = backgroundAt ?: return
        if (enabledMutable.value && elapsedRealtime() - leftAt >= RELOCK_DELAY_MS) lock()
        backgroundAt = null
    }

    fun beginExternalActivity() {
        externalActivityDepth = 1
        backgroundAt = null
    }

    fun endExternalActivity() {
        if (externalActivityDepth > 0) {
            externalActivityDepth = 0
            backgroundAt = null
        }
    }

    fun shouldShowIntroduction(): Boolean = !preferences.getBoolean(KEY_INTRO_SHOWN, false)

    fun markIntroductionShown(): Boolean = writeBoolean(KEY_INTRO_SHOWN, true)

    private fun writeBoolean(key: String, value: Boolean): Boolean {
        val previous = preferences.getBoolean(key, false)
        val saved = runCatching { preferences.edit().putBoolean(key, value).commit() }.getOrDefault(false)
        if (!saved) {
            // SharedPreferences can update its in-memory value even when commit fails.
            // Keep the session's lock state unchanged and best-effort restore that value.
            runCatching { preferences.edit().putBoolean(key, previous).commit() }
        }
        return saved
    }

    fun registerScreenOffReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) lock()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTRO_SHOWN = "introduction_shown_1_2"
        private const val RELOCK_DELAY_MS = 30_000L
    }
}
