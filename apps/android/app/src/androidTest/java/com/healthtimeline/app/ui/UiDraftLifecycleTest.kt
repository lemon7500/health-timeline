package com.healthtimeline.app.ui

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiDraftLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lockingRemovesMedicalSemanticsAndUnlockingRestoresTheMemoryDraft() {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val locked = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                if (locked.value) Text("已锁定") else {
                    val original = rememberUiDraftState("record/1/new", "text") { "" }
                    OutlinedTextField(original.value, { original.value = it }, label = { Text("病程原文") })
                }
            }
        }
        compose.onNodeWithText("病程原文").performTextInput("未保存的批量病程原文")
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText("已锁定").assertExists()
        compose.onNodeWithText("未保存的批量病程原文").assertDoesNotExist()
        compose.onNodeWithText("病程原文").assertDoesNotExist()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithText("未保存的批量病程原文").assertExists()
        compose.runOnIdle { owner.viewModelStore.clear() }
    }

    @Test fun reenteringTheEditorRestoresItsOpeningStateUntilExplicitCancel() {
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val composed = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                if (composed.value) {
                    val title = rememberUiDraftState("record/1/new", "title") { "" }
                    OutlinedTextField(title.value, { title.value = it }, label = { Text("小标题") })
                }
            }
        }
        compose.onNodeWithText("小标题").performTextInput("测试病情及事件")
        compose.runOnIdle { composed.value = false }
        compose.runOnIdle { composed.value = true }
        compose.onNodeWithText("测试病情及事件").assertExists()
        compose.runOnIdle { owner.viewModelStore.clear() }
    }
}
