package app.ownplay.mobile.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderCategoryDisplayPolicyTest {
    @Test
    fun knownRegionPrefixIsHiddenWithoutChangingCountryIdentity() {
        val presentation = ProviderCategoryDisplayPolicy.present("EUROPE | ALBANIA Sports")

        assertEquals("Sports", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱", presentation.flagEmoji)
        assertEquals("🇦🇱 Sports", presentation.label)
    }

    @Test
    fun uppercaseCountryCodeCanDriveFlagAfterRegionPrefixIsRemoved() {
        val presentation = ProviderCategoryDisplayPolicy.present("EU | AL | Premium")

        assertEquals("Premium", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱", presentation.flagEmoji)
    }

    @Test
    fun unknownPrefixIsNeverBlindlyRemoved() {
        val presentation = ProviderCategoryDisplayPolicy.present("PREMIUM | Albania")

        assertEquals("PREMIUM | Albania", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱", presentation.flagEmoji)
    }

    @Test
    fun displayCanKeepRawRegionPrefixWhenPreferenceIsDisabled() {
        val presentation = ProviderCategoryDisplayPolicy.present(
            rawName = "EUROPE | Albania",
            hideRegionPrefix = false,
        )

        assertEquals("EUROPE | Albania", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
    }

    @Test
    fun uncertainCategoryDoesNotInventCountryOrFlag() {
        val presentation = ProviderCategoryDisplayPolicy.present("Premium Cinema")

        assertEquals("Premium Cinema", presentation.displayName)
        assertNull(presentation.countryCode)
        assertNull(presentation.flagEmoji)
    }

    @Test
    fun existingProviderFlagIsNotDuplicated() {
        val presentation = ProviderCategoryDisplayPolicy.present("EUROPE | 🇦🇱 Albania")

        assertEquals("", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱", presentation.flagEmoji)
        assertEquals("🇦🇱", presentation.label)
    }

    @Test
    fun leadingCountryPrefixIsRemovedButCategoryRemainderIsPreserved() {
        val presentation = ProviderCategoryDisplayPolicy.present("ALBANIA | Movies")

        assertEquals("Movies", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱 Movies", presentation.label)
    }

    @Test
    fun countryNameOutsideTheLeadingPrefixIsPreserved() {
        val presentation = ProviderCategoryDisplayPolicy.present("Premium | Albania")

        assertEquals("Premium | Albania", presentation.displayName)
        assertEquals("AL", presentation.countryCode)
        assertEquals("🇦🇱 Premium | Albania", presentation.label)
    }

    @Test
    fun decoratedCountryCodePrefixIsRemoved() {
        val presentation = ProviderCategoryDisplayPolicy.present("[IT] Movies")

        assertEquals("Movies", presentation.displayName)
        assertEquals("IT", presentation.countryCode)
        assertEquals("🇮🇹 Movies", presentation.label)
    }


    @Test
    fun earliestExplicitCountryCodeWinsOverLaterCategoryTextCode() {
        val presentation = ProviderCategoryDisplayPolicy.present("[IT] FANTASIA / SCI-FI")

        assertEquals("FANTASIA / SCI-FI", presentation.displayName)
        assertEquals("IT", presentation.countryCode)
        assertEquals("🇮🇹 FANTASIA / SCI-FI", presentation.label)
    }

    @Test
    fun decoratedCountryNamePrefixIsRemoved() {
        val presentation = ProviderCategoryDisplayPolicy.present("(ITALIA) Cinema")

        assertEquals("Cinema", presentation.displayName)
        assertEquals("IT", presentation.countryCode)
        assertEquals("🇮🇹 Cinema", presentation.label)
    }

    @Test
    fun leadingSeparatorBeforeRegionAndCountryIsRemoved() {
        val presentation = ProviderCategoryDisplayPolicy.present("| EUROPE | ITALY | Movies")

        assertEquals("Movies", presentation.displayName)
        assertEquals("IT", presentation.countryCode)
        assertEquals("🇮🇹 Movies", presentation.label)
    }

    @Test
    fun leadingFlagAndDecoratedCountryPrefixCollapseToSingleFlag() {
        val presentation = ProviderCategoryDisplayPolicy.present("🇮🇹 | ITALY | Cinema")

        assertEquals("Cinema", presentation.displayName)
        assertEquals("IT", presentation.countryCode)
        assertEquals("🇮🇹 Cinema", presentation.label)
    }

    @Test
    fun lowercaseShortWordsDoNotAccidentallyBecomeCountryCodes() {
        val presentation = ProviderCategoryDisplayPolicy.present("Movies in English")

        assertNull(presentation.countryCode)
        assertNull(presentation.flagEmoji)
    }
    @Test
    fun labelConvertsRecognizedProviderPrefixToCountryFlag() {
        val label = ProviderCategoryDisplayPolicy.label(
            rawName = "EUROPE | ALBANIA Sports",
            hideRegionPrefix = false,
            showFlag = true,
        )

        assertEquals("🇦🇱 Sports", label)
    }

    @Test
    fun labelCanHidePrefixWithoutForcingFlag() {
        val label = ProviderCategoryDisplayPolicy.label(
            rawName = "EUROPE | ALBANIA Sports",
            hideRegionPrefix = true,
            showFlag = false,
        )

        assertEquals("Sports", label)
    }

}
