package com.sunloom.app.ui

/**
 * Remembers which display set the library was last reconciled for (Article IV §6). The app hears about display changes on
 * every window resize, focus change and start; when the set is the one already reconciled there is nothing to plan, so
 * the full pass (seeds, re-validation, reconcile, re-apply) can be skipped.
 */
internal class DisplayReconcileGate {
    @Volatile
    private var reconciledKey: String? = null

    fun needsReconcile(key: String): Boolean = key != reconciledKey

    fun markReconciled(key: String) {
        reconciledKey = key
    }

    /** Forget it, so the next change is treated as new (after a reset, or a setting that changes what the pass does). */
    fun invalidate() {
        reconciledKey = null
    }
}
