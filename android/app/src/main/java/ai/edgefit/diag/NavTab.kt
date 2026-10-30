package ai.edgefit.diag

/**
 * UX-01A bottom-navigation destinations
 * (ef-ux-01-design.md §B). Pure for unit tests; the
 * activity owns tab state and view swapping.
 */
enum class NavTab {
    HOME,
    BENCHMARK,
    SESSIONS,
    AGENT,
    SETTINGS;

    companion object {
        fun title(tab: NavTab): String = when (tab) {
            HOME -> "Home"
            BENCHMARK -> "Benchmark"
            SESSIONS -> "Sessions"
            AGENT -> "Agent"
            SETTINGS -> "Settings"
        }

        /**
         * UX-01BC-R2 icon destination. Original EdgeFit
         * vector glyphs, tinted selected/unselected by the
         * activity. Pure mapping; drawables must exist.
         */
        fun icon(tab: NavTab): Int = when (tab) {
            HOME -> R.drawable.ic_nav_home
            BENCHMARK -> R.drawable.ic_nav_bench
            SESSIONS -> R.drawable.ic_nav_sessions
            AGENT -> R.drawable.ic_nav_agent
            SETTINGS -> R.drawable.ic_nav_settings
        }

        /**
         * Back-navigation target. Null means the back press
         * exits the activity (default behavior).
         */
        fun backTarget(tab: NavTab): NavTab? =
            if (tab == HOME) null else HOME

        /** Restores a persisted tab name, defaulting to HOME. */
        fun fromName(name: String?): NavTab = try {
            valueOf(name ?: "HOME")
        } catch (_: Exception) {
            HOME
        }
    }
}
