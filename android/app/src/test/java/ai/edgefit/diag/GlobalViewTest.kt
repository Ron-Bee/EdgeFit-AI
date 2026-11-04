package ai.edgefit.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the contribution Approve-gate helper (Android-free). */
class GlobalViewTest {
    @Test
    fun canOfferUploadRequiresAllGates() {
        assertTrue(GlobalView.canOfferUpload("https://host.example", "…42", true, true))
        assertFalse(GlobalView.canOfferUpload(null, "…42", true, true))
        assertFalse(GlobalView.canOfferUpload("https://host.example", null, true, true))
        assertFalse(GlobalView.canOfferUpload("https://host.example", "…42", false, true))
        assertFalse(GlobalView.canOfferUpload("https://host.example", "…42", true, false))
    }
}
