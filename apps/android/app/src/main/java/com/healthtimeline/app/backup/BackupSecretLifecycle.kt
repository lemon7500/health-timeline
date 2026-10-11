package com.healthtimeline.app.backup

import kotlinx.coroutines.Job

/** Also covers cancellation before a launched coroutine ever enters its body/finally. */
internal fun Job.eraseBackupPasswordOnCompletion(password: CharArray): Job = apply {
    invokeOnCompletion { password.fill('\u0000') }
}
