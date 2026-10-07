package app.ownplay.mobile.feature.library.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDetailHeroLayoutPolicyTest {
    @Test
    fun constrainedPhoneWidthsStackArtworkAndMetadata() {
        assertTrue(shouldStackLibraryDetailHero(288.dp, 1f))
        assertTrue(shouldStackLibraryDetailHero(328.dp, 1f))
    }

    @Test
    fun widerPhoneCanKeepSideBySideLayoutAtNormalFontScale() {
        assertFalse(shouldStackLibraryDetailHero(372.dp, 1f))
    }

    @Test
    fun increasedFontScaleStacksEvenWhenWidthWouldOtherwiseFit() {
        assertTrue(shouldStackLibraryDetailHero(372.dp, 1.2f))
    }
}
