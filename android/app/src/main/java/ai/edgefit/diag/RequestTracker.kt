package ai.edgefit.diag

/**
 * UX-01BC request lifecycle guard: at most one in-flight live
 * Meta operation per key. The activity shows progress while a
 * key is held; MetaSessionClient.guardedFlight remains the
 * backstop against double submission. Synchronized; pure JVM.
 */
class RequestTracker {
    private val inFlight = mutableSetOf<String>()

    @Synchronized
    fun begin(key: String): Boolean = inFlight.add(key)

    @Synchronized
    fun end(key: String) {
        inFlight.remove(key)
    }

    @Synchronized
    fun isActive(key: String): Boolean = key in inFlight

    /** Any live Meta operation in flight (onboarding gate). */
    @Synchronized
    fun hasInflight(): Boolean = inFlight.isNotEmpty()
}
