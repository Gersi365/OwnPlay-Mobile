package app.ownplay.mobile.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialInputPolicyTest {
    @Test
    fun `display name is trimmed`() {
        assertEquals(
            "Home TV",
            CredentialInputPolicy.normalizeDisplayName("  Home TV  "),
        )
    }

    @Test
    fun `blank display name is rejected`() {
        assertNull(CredentialInputPolicy.normalizeDisplayName("   "))
    }

    @Test
    fun `credentials reject blank values`() {
        assertFalse(CredentialInputPolicy.isValidCredential("   "))
    }

    @Test
    fun `credentials reject control characters`() {
        assertFalse(CredentialInputPolicy.isValidCredential("user\nname"))
    }

    @Test
    fun `credentials reject unreasonable length`() {
        assertFalse(CredentialInputPolicy.isValidCredential("x".repeat(513)))
    }

    @Test
    fun `ordinary credential is accepted`() {
        assertTrue(CredentialInputPolicy.isValidCredential("provider-user"))
    }
}
