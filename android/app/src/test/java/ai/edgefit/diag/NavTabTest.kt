package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for UX-01A tab navigation behavior. */
class NavTabTest {

    @Test
    fun backFromHomeExits() {
        assertNull(NavTab.backTarget(NavTab.HOME))
    }

    @Test
    fun backFromAnywhereElseGoesHome() {
        for (tab in NavTab.values()) {
            if (tab == NavTab.HOME) continue
            assertEquals(NavTab.HOME, NavTab.backTarget(tab))
        }
    }

    @Test
    fun exactlyFiveDestinations() {
        assertEquals(5, NavTab.values().size)
    }

    @Test
    fun titlesAreNonBlank() {
        for (tab in NavTab.values()) {
            assertEquals(false, NavTab.title(tab).isBlank())
        }
    }

    @Test
    fun fromNameRestoresOrDefaultsHome() {
        assertEquals(NavTab.AGENT, NavTab.fromName("AGENT"))
        assertEquals(NavTab.HOME, NavTab.fromName(null))
        assertEquals(NavTab.HOME, NavTab.fromName("Bogus"))
    }
}
