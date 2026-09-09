package app.ownplay.mobile.sources

import kotlinx.coroutines.flow.Flow

interface SourceRepository {
    fun observeSources(): Flow<List<SourceDescriptor>>
    fun observeCatalog(sourceId: String, catalogType: CatalogType): Flow<List<ProviderItem>>
    fun observeActiveSourceId(): Flow<String?>

    suspend fun addSource(draft: SourceDraft, credentials: XtreamCredentials? = null): SourceDescriptor
    suspend fun updateSource(source: SourceDescriptor, credentials: XtreamCredentials? = null)
    suspend fun removeSource(sourceId: String)
    suspend fun selectSource(sourceId: String?)
    suspend fun refreshSource(sourceId: String): RefreshResult
}
