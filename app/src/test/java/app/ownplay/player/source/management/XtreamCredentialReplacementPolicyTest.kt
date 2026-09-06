package app.ownplay.player.source.management

import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamCredentialReplacementPolicyTest {
    @Test
    fun blankReplacementFieldsKeepExistingCredentials() {
        assertEquals(
            XtreamCredentialEditMode.KEEP_EXISTING,
            XtreamCredentialReplacementPolicy.classify("", ""),
        )
        assertEquals(
            XtreamCredentialEditMode.KEEP_EXISTING,
            XtreamCredentialReplacementPolicy.classify("   ", "   "),
        )
    }

    @Test
    fun completeReplacementUsesNewCredentials() {
        assertEquals(
            XtreamCredentialEditMode.REPLACE,
            XtreamCredentialReplacementPolicy.classify("new-user", "new-password"),
        )
    }

    @Test
    fun partialReplacementIsRejected() {
        assertEquals(
            XtreamCredentialEditMode.INCOMPLETE,
            XtreamCredentialReplacementPolicy.classify("new-user", ""),
        )
        assertEquals(
            XtreamCredentialEditMode.INCOMPLETE,
            XtreamCredentialReplacementPolicy.classify("", "new-password"),
        )
    }
}
