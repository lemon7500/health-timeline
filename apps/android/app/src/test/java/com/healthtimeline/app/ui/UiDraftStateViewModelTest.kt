package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class UiDraftStateViewModelTest {
    @Test fun rebuildingPageReusesTheInMemoryDraft() {
        val store = UiDraftStateViewModel()
        store.bindMember(1)
        val original = store.state("record/1/new", "text") { "" }
        original.value = "未提交病程原文"
        val rebuilt = store.state("record/1/new", "text") { "" }
        assertSame(original, rebuilt)
        assertEquals("未提交病程原文", rebuilt.value)
        store.bindMember(1)
        assertSame(original, store.state("record/1/new", "text") { "" })
    }

    @Test fun cancellingOrSavingRemovesTheDraft() {
        val store = UiDraftStateViewModel()
        val original = store.state("record/1/new", "text") { "原文" }
        store.clearScope("record/1/new")
        val next = store.state("record/1/new", "text") { "" }
        assertNotSame(original, next)
        assertEquals("", next.value)
    }

    @Test fun switchingMembersClearsPrivateDrafts() {
        val store = UiDraftStateViewModel()
        store.bindMember(1)
        store.state("record/1/new", "text") { "成员一的原文" }
        store.bindMember(2)
        store.bindMember(1)
        assertEquals("", store.state("record/1/new", "text") { "" }.value)
    }

    @Test fun pendingDestinationSurvivesRebindingTheSameMemberUntilConsumed() {
        val store = UiDraftStateViewModel()
        store.bindMember(1)
        val destination = TodayNavigation.MedicationDay(1, LocalDate.of(2026, 10, 8))
        store.navigationState.value = destination
        store.bindMember(1)
        assertSame(destination, store.navigationState.value)
        store.navigationState.value = consumeTodayNavigation(store.navigationState.value, destination.token)
        assertNull(store.navigationState.value)
    }

    @Test fun switchingToNotificationsMemberKeepsItsMatchingDestination() {
        val store = UiDraftStateViewModel()
        store.bindMember(1)
        val destination = TodayNavigation.MedicationDay(2, LocalDate.of(2026, 10, 8))
        store.navigationState.value = destination
        store.bindMember(2)
        assertSame(destination, store.navigationState.value)
        store.bindMember(1)
        assertNull(store.navigationState.value)
    }

    @Test fun rebuildingMedicationPageKeepsItsSelectedHistoryDate() {
        val store = UiDraftStateViewModel()
        store.bindMember(1)
        store.state("medicationPage/1", "selectedDate") { "2026-10-09" }.value = "2026-10-08"
        assertEquals("2026-10-08", store.state("medicationPage/1", "selectedDate") { "2026-10-09" }.value)
        store.bindMember(2)
        assertEquals("2026-10-09", store.state("medicationPage/2", "selectedDate") { "2026-10-09" }.value)
    }
}
