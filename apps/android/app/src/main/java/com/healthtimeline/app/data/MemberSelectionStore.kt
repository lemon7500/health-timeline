package com.healthtimeline.app.data

import android.content.Context

class MemberSelectionStore(context: Context) {
    private val preferences = context.getSharedPreferences("member_selection", Context.MODE_PRIVATE)

    fun read(): Long? = preferences.getLong(KEY_SELECTED_MEMBER, -1L).takeIf { it > 0L }

    fun write(memberId: Long) {
        check(preferences.edit().putLong(KEY_SELECTED_MEMBER, memberId).commit()) {
            "无法保存当前家庭成员"
        }
    }

    companion object {
        private const val KEY_SELECTED_MEMBER = "selected_member_id"
    }
}
