package app.ownplay.player.source.onboarding

import app.ownplay.player.source.SourceError
import app.ownplay.player.source.SourceSyncFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSyncFailureMappingTest {
    @Test
    fun preservesSourceErrorCause() {
        assertEquals(
            SourceSyncFailure.Source(SourceError.AuthenticationFailed),
            SourceOnboardingFailure.SourceFailure(SourceError.AuthenticationFailed)
                .toSourceSyncFailure(),
        )
    }

    @Test
    fun mapsOperationalFailureCategories() {
        assertEquals(
            SourceSyncFailure.SecureStorage,
            SourceOnboardingFailure.SecureStorageFailure.toSourceSyncFailure(),
        )
        assertEquals(
            SourceSyncFailure.Persistence,
            SourceOnboardingFailure.PersistenceFailure.toSourceSyncFailure(),
        )
        assertEquals(
            SourceSyncFailure.CatalogImport,
            SourceOnboardingFailure.CatalogImportFailure.toSourceSyncFailure(),
        )
    }
}
