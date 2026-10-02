package app.ownplay.mobile.feature.live.data

import app.ownplay.mobile.data.db.LiveChannelEntity
import app.ownplay.mobile.data.db.LiveOrganizationDao
import app.ownplay.mobile.data.db.ProviderCategoryEntity
import app.ownplay.mobile.sources.domain.SourceId

interface LiveOrganizationRefreshStore {
    suspend fun reconcileAutomatic(
        sourceId: SourceId,
        generation: Long,
        providerCategories: List<ProviderCategoryEntity>,
        liveChannels: List<LiveChannelEntity>,
    )
}

/**
 * Compatibility hook kept in the source refresh transaction for the v2 -> v3 schema line.
 *
 * Provider-only Live does not generate or update legacy OwnPlay taxonomy state.
 */
@Suppress("UNUSED_PARAMETER")
class RoomLiveOrganizationRefreshStore(
    dao: LiveOrganizationDao,
) : LiveOrganizationRefreshStore {
    override suspend fun reconcileAutomatic(
        sourceId: SourceId,
        generation: Long,
        providerCategories: List<ProviderCategoryEntity>,
        liveChannels: List<LiveChannelEntity>,
    ) = Unit
}
