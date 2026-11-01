package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF-UX-AERO-01 Step 5 settings model: six groups, only
 * real controls, no fake toggles, no secrets, BYOK kept
 * separate from the Global upload token.
 */
class AeroSettingsTest {

    private fun ctx(): AeroSettings.Context =
        AeroSettings.Context(
            metaKeyStatus = "Meta API key: none configured.",
            globalOrigin = null,
            globalTokenSuffix = null,
            globalTokenPersisted = false,
            versionName = "1.0",
            packageName = "ai.edgefit.diag",
            workloadId = "edgefit-prompt-v1"
        )

    @Test
    fun sixGroupsInFixedOrder() {
        assertEquals(
            listOf(
                AeroSettings.Group.APPEARANCE,
                AeroSettings.Group.BENCHMARK,
                AeroSettings.Group.META,
                AeroSettings.Group.GLOBAL,
                AeroSettings.Group.PRIVACY,
                AeroSettings.Group.ABOUT),
            AeroSettings.groups())
    }

    @Test
    fun groupTitlesAndHelpersAreNonBlank() {
        for (group in AeroSettings.groups()) {
            assertTrue(AeroSettings.title(group).isNotBlank())
            assertTrue(AeroSettings.helper(group).isNotBlank())
        }
        assertEquals("Appearance",
            AeroSettings.title(AeroSettings.Group.APPEARANCE))
        assertEquals("Meta Muse",
            AeroSettings.title(AeroSettings.Group.META))
        assertEquals("EdgeFit Global",
            AeroSettings.title(AeroSettings.Group.GLOBAL))
    }

    @Test
    fun appearanceHasNoActions() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.APPEARANCE, ctx())
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.action == null })
        assertTrue(rows.joinToString(" ") { it.detail ?: "" }
            .contains("no appearance options"))
    }

    @Test
    fun everyGroupHasBeginnerAndHonestRows() {
        for (group in AeroSettings.groups()) {
            val rows = AeroSettings.rows(group, ctx())
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.all { it.label.isNotBlank() })
        }
    }

    @Test
    fun metaGroupKeepsByokSeparate() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.META, ctx())
        val joined = rows.joinToString(" ") {
            it.label + " " + (it.detail ?: "")
        }
        assertTrue(joined.contains("Meta API key"))
        // The Global upload token never appears here.
        assertFalse(joined.contains("upload token"))
        assertFalse(joined.contains("Global Connection"))
        assertTrue(rows.any {
            it.action == AeroSettings.Action.META_KEY
        })
    }

    @Test
    fun globalGroupReportsUnconfiguredHonestly() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.GLOBAL, ctx())
        val joined = rows.joinToString(" ") {
            it.detail ?: ""
        }
        assertTrue(joined.contains("not configured"))
        assertTrue(rows.any {
            it.action == AeroSettings.Action.GLOBAL_CONNECTION
        })
        assertTrue(rows.any {
            it.action == AeroSettings.Action.CONTRIBUTION_HISTORY
        })
    }

    @Test
    fun globalGroupShowsPersistedTokenSuffix() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.GLOBAL,
            ctx().copy(
                globalOrigin = "https://staging-host.example",
                globalTokenSuffix = "…abcd",
                globalTokenPersisted = true))
        val joined = rows.joinToString(" ") {
            it.detail ?: ""
        }
        assertTrue(joined.contains("…abcd"))
        assertTrue(joined.contains("persisted"))
    }

    @Test
    fun privacyStatesNoAutoUpload() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.PRIVACY, ctx())
        val joined = rows.joinToString(" ") {
            it.label + " " + (it.detail ?: "")
        }
        assertTrue(joined.contains("never uploads"))
        assertTrue(joined.contains("withdraw"))
        assertTrue(rows.any {
            it.action == AeroSettings.Action.EXPORT_PROFILE
        })
    }

    @Test
    fun aboutCarriesVersionAndGuide() {
        val rows = AeroSettings.rows(
            AeroSettings.Group.ABOUT, ctx())
        val joined = rows.joinToString(" ") {
            it.detail ?: ""
        }
        assertTrue(joined.contains("1.0"))
        assertTrue(joined.contains("ai.edgefit.diag"))
        assertTrue(joined.contains("edgefit-prompt-v1"))
        assertTrue(rows.any {
            it.action == AeroSettings.Action.HELP_GUIDE
        })
    }

    @Test
    fun benchmarkGroupHoldsOnlyRealDestinations() {
        val actions = AeroSettings.rows(
            AeroSettings.Group.BENCHMARK, ctx())
            .mapNotNull { it.action }.toSet()
        assertEquals(
            setOf(
                AeroSettings.Action.WORKLOAD,
                AeroSettings.Action.MODEL_CATALOG,
                AeroSettings.Action.MEM_DIAG,
                AeroSettings.Action.DEVICE_DETAILS),
            actions)
    }

    @Test
    fun noRowLeaksSecrets() {
        // Only suffixes render; full keys/tokens never do.
        // The fixture carries no secret material, so any
        // secret-shaped value here would be invented.
        val joined = AeroSettings.groups().flatMap {
            AeroSettings.rows(it, ctx())
        }.joinToString(" ") { it.label + " " + (it.detail ?: "") }
        assertFalse(joined.contains("sk-"))
        assertFalse(joined.contains("Bearer"))
    }
}
