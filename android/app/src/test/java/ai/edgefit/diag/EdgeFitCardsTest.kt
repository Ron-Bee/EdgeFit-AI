package ai.edgefit.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeFitCardsTest {

    @Test
    fun chipKindForMapsEveryHeroState() {
        assertEquals(EdgeFitCards.ChipKind.NEUTRAL,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.NO_MODEL))
        assertEquals(EdgeFitCards.ChipKind.NEUTRAL,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.STAGED_UNMEASURED))
        assertEquals(EdgeFitCards.ChipKind.MEASURED,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.MEASURED))
        assertEquals(EdgeFitCards.ChipKind.ESTIMATED,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.ESTIMATED_FIT))
        assertEquals(EdgeFitCards.ChipKind.ERROR,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.NOT_SUPPORTED))
        assertEquals(EdgeFitCards.ChipKind.WARNING,
            EdgeFitCards.chipKindFor(
                AeroDecision.HeroState.INSUFFICIENT_EVIDENCE))
    }
}
