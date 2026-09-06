package app.ownplay.player.source.onboarding

import app.ownplay.player.source.SourceSyncFailure

internal fun SourceOnboardingFailure.toSourceSyncFailure(): SourceSyncFailure = when (this) {
    SourceOnboardingFailure.InvalidName -> SourceSyncFailure.InvalidInput
    is SourceOnboardingFailure.SourceFailure -> SourceSyncFailure.Source(error)
    SourceOnboardingFailure.SecureStorageFailure -> SourceSyncFailure.SecureStorage
    SourceOnboardingFailure.PersistenceFailure -> SourceSyncFailure.Persistence
    SourceOnboardingFailure.CatalogImportFailure -> SourceSyncFailure.CatalogImport
}
