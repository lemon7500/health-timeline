package com.healthtimeline.app.ui

import org.junit.Assert.*
import org.junit.Test

class MemberSelectionChangeListenersTest {
    @Test fun awayAndBackInTheSameCallStackIsNeverConflated() {
        val listeners = MemberSelectionChangeListeners()
        val changes = mutableListOf<Long?>()
        val unregister = listeners.observe(changes::add)
        listeners.changed(2)
        listeners.changed(1)
        assertEquals(listOf(2L, 1L), changes)
        unregister()
        listeners.changed(3)
        assertEquals(listOf(2L, 1L), changes)
    }

    @Test fun listenerCanUnregisterDuringDispatchWithoutSkippingOtherListeners() {
        val listeners = MemberSelectionChangeListeners()
        val changes = mutableListOf<String>()
        lateinit var unregister: () -> Unit
        unregister = listeners.observe { changes += "first"; unregister() }
        listeners.observe { changes += "second" }
        listeners.changed(2)
        listeners.changed(1)
        assertEquals(listOf("first", "second", "second"), changes)
        listeners.clear()
        listeners.changed(null)
        assertEquals(3, changes.size)
    }

    @Test fun independentSubscriptionsOfTheSameCallbackCanBeRemovedSeparately() {
        val listeners = MemberSelectionChangeListeners()
        val changes = mutableListOf<Long?>()
        val callback: (Long?) -> Unit = changes::add
        val first = listeners.observe(callback)
        val second = listeners.observe(callback)
        listeners.changed(2)
        first()
        listeners.changed(1)
        second()
        listeners.changed(3)
        assertEquals(listOf(2L, 2L, 1L), changes)
    }
}
