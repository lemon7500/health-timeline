package com.healthtimeline.app.reminders

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicationReviewIntentTest {
    @Test fun actionOpensAuthenticationGatedActivityInsteadOfWritingBroadcast() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = medicationReviewIntent(context, 1, 2, "2026-10-09T08:05")
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertNotEquals(ReminderActionReceiver.ACTION_TAKEN, intent.action)
        assertEquals(1L, intent.getLongExtra(ReminderReceiver.EXTRA_MEDICATION_ID, -1))
        assertEquals(2L, intent.getLongExtra(ReminderReceiver.EXTRA_ID, -1))
        assertEquals("2026-10-09T08:05", intent.getStringExtra(ReminderReceiver.EXTRA_SCHEDULED_AT))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test fun distinctDoseTimesHaveDistinctPendingIntentIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(medicationReviewIntent(context, 1, 2, "2026-10-09T08:05")
            .filterEquals(medicationReviewIntent(context, 1, 2, "2026-10-10T08:05")))
    }
}
