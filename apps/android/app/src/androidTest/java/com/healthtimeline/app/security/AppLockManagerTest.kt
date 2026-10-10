package com.healthtimeline.app.security

import android.content.Context
import android.content.ContextWrapper
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
}
