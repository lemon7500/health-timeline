package com.healthtimeline.app.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppLockManager(private val context: Context) {
    private val preferences = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)
    private val enabledMutable = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    private val lockedMutable = MutableStateFlow(enabledMutable.value)
    private var backgroundAt: Long? = null
    private var externalActivityDepth = 0

    val enabled = enabledMutable.asStateFlow()
    val locked = lockedMutable.asStateFlow()

    fun setEnabled(value: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, value).commit()) { "无法保存应用保护设置" }
        enabledMutable.value = value
        lockedMutable.value = false
        backgroundAt = null
    }

    fun unlock() {
        lockedMutable.value = false
        backgroundAt = null
    }

    fun lock() {
        if (enabledMutable.value) lockedMutable.value = true
    }

    fun onAppBackgrounded() {
        if (externalActivityDepth == 0 && enabledMutable.value) backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded() {
        val leftAt = backgroundAt ?: return
        if (enabledMutable.value && SystemClock.elapsedRealtime() - leftAt >= RELOCK_DELAY_MS) lock()
        backgroundAt = null
    }

    fun beginExternalActivity() {
        externalActivityDepth++
        backgroundAt = null
    }

    fun endExternalActivity() {
        if (externalActivityDepth > 0) externalActivityDepth--
        backgroundAt = null
    }

    fun shouldShowIntroduction(): Boolean = !preferences.getBoolean(KEY_INTRO_SHOWN, false)

    fun markIntroductionShown() {
        check(preferences.edit().putBoolean(KEY_INTRO_SHOWN, true).commit()) { "无法保存应用保护提示状态" }
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
