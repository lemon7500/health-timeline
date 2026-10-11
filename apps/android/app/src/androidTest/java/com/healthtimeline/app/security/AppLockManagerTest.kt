package com.healthtimeline.app.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockManagerTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val preferenceName = "lock-regression-${UUID.randomUUID()}"
    private var now = 100_000L
    private lateinit var manager: AppLockManager

    @Before fun setup() {
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences(preferenceName, mode)
        }
        manager = AppLockManager(isolated) { now }
        manager.setEnabled(true)
    }

    @After fun cleanup() {
        base.deleteSharedPreferences(preferenceName)
    }

    @Test fun lostPickerCallbackDoesNotDisableLaterBackgroundTimeout() {
        manager.beginExternalActivity()
        manager.onAppBackgrounded()
        now += 60_000
        // Returning after launcher recreation: no endExternalActivity callback arrives.
        manager.onAppForegrounded()
        assertFalse(manager.locked.value)
        manager.onAppBackgrounded()
        now += 30_001
        manager.onAppForegrounded()
        assertTrue(manager.locked.value)
    }

    @Test fun latePickerCallbackCannotEraseAnUnrelatedBackgroundTimeout() {
        manager.beginExternalActivity()
        manager.onAppBackgrounded()
        manager.onAppForegrounded()
        manager.onAppBackgrounded()
        now += 30_001
        manager.endExternalActivity()
        manager.onAppForegrounded()
        assertTrue(manager.locked.value)
    }

    @Test fun screenLockAndUnlockDiscardAnExternalExemption() {
        manager.beginExternalActivity()
        manager.onAppBackgrounded()
        manager.lock()
        assertTrue(manager.locked.value)
        manager.unlock()
        manager.onAppBackgrounded()
        now += 30_001
        manager.onAppForegrounded()
        assertTrue(manager.locked.value)
    }

    @Test fun aNormalPickerTripIsExemptButNextBackgroundTripIsNot() {
        manager.beginExternalActivity()
        manager.onAppBackgrounded()
        now += 60_000
        manager.endExternalActivity()
        manager.onAppForegrounded()
        assertFalse(manager.locked.value)
        manager.onAppBackgrounded()
        now += 29_999
        manager.onAppForegrounded()
        assertFalse(manager.locked.value)
        manager.onAppBackgrounded()
        now += 30_000
        manager.onAppForegrounded()
        assertTrue(manager.locked.value)
    }

    @Test fun failedPreferenceCommitDoesNotDisableOrUnlockTheSession() {
        manager.lock()
        val failing = failingPreferences(throwOnCommit = false)
        val guarded = AppLockManager(failing) { now }
        assertTrue(guarded.locked.value)
        assertFalse(guarded.setEnabled(false))
        assertTrue(guarded.enabled.value)
        assertTrue(guarded.locked.value)
        assertTrue(base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).getBoolean("enabled", false))
    }

    @Test fun exceptionDuringPreferenceWriteIsReportedWithoutChangingLockState() {
        val guarded = AppLockManager(failingPreferences(throwOnCommit = true)) { now }
        assertFalse(guarded.setEnabled(false))
        assertTrue(guarded.enabled.value)
        assertTrue(guarded.locked.value)
    }

    @Test fun failedIntroductionWriteCanBeRetried() {
        val guarded = AppLockManager(failingPreferences(throwOnCommit = false)) { now }
        assertTrue(guarded.shouldShowIntroduction())
        assertFalse(guarded.markIntroductionShown())
        assertTrue(guarded.shouldShowIntroduction())
        assertTrue(guarded.markIntroductionShown())
        assertFalse(guarded.shouldShowIntroduction())
    }

    private fun failingPreferences(throwOnCommit: Boolean): Context {
        val original = base.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        var failNext = true
        val proxy = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, arguments ->
            if (method.name != "edit") method.invoke(original, *(arguments ?: emptyArray()))
            else {
                val editor = original.edit()
                lateinit var guardedEditor: SharedPreferences.Editor
                guardedEditor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, editorMethod, editorArguments ->
                    if (editorMethod.name == "commit" && failNext) {
                        failNext = false
                        if (throwOnCommit) throw IllegalStateException("simulated write failure")
                        editor.commit() // Simulate the in-memory update that may precede a failed disk write.
                        false
                    } else {
                        val result = editorMethod.invoke(editor, *(editorArguments ?: emptyArray()))
                        if (result is SharedPreferences.Editor) guardedEditor else result
                    }
                } as SharedPreferences.Editor
                guardedEditor
            }
        } as SharedPreferences
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = proxy
        }
    }
}
