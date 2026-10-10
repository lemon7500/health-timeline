package com.healthtimeline.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

/** Unsaved medical text stays in process memory, never in SavedState, a Bundle or the database. */
class UiDraftStateViewModel : ViewModel() {
    private val values = mutableMapOf<Pair<String, String>, MutableState<*>>()
    private var memberId: Long? = null
    // A destination survives Activity recreation until its page explicitly consumes the token.
    val navigationState = mutableStateOf<TodayNavigation?>(null)

    fun bindMember(value: Long?) {
        if (memberId != value) {
            values.clear()
            if (navigationState.value?.memberId != value) navigationState.value = null
            memberId = value
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> state(scope: String, field: String, initial: () -> T): MutableState<T> =
        values.getOrPut(scope to field) { mutableStateOf(initial()) } as MutableState<T>

    fun clearScope(scope: String) {
        values.keys.removeAll { it.first == scope }
    }

    override fun onCleared() {
        values.clear()
        navigationState.value = null
    }
}

@Composable
internal fun <T> rememberUiDraftState(scope: String, field: String, initial: () -> T): MutableState<T> {
    val store: UiDraftStateViewModel = viewModel()
    return remember(store, scope, field) { store.state(scope, field, initial) }
}

internal fun recordDraftScope(memberId: Long, uuid: String?) = "record/$memberId/${uuid ?: "new"}"
internal fun medicationDraftScope(memberId: Long, uuid: String?) = "medication/$memberId/${uuid ?: "new"}"
internal fun followUpDraftScope(memberId: Long, uuid: String?) = "followUp/$memberId/${uuid ?: "new"}"
