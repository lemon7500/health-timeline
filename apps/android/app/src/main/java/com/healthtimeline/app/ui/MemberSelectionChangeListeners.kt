package com.healthtimeline.app.ui

/** Main-thread synchronous delivery: StateFlow can conflate an A -> B -> A round trip. */
internal class MemberSelectionChangeListeners {
    // A callable reference can hash its mutable receiver. Keep stable subscription identities,
    // so dispatch cannot change the hash used by a subsequent unsubscribe.
    private class Subscription(val listener: (Long?) -> Unit)
    private val listeners = linkedSetOf<Subscription>()

    fun observe(listener: (Long?) -> Unit): () -> Unit {
        val subscription = Subscription(listener)
        listeners += subscription
        return { listeners -= subscription }
    }

    fun changed(memberId: Long?) {
        listeners.toList().forEach { it.listener(memberId) }
    }

    fun clear() = listeners.clear()
}
