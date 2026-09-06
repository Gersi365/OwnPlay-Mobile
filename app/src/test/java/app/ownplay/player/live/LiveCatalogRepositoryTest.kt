package app.ownplay.player.live

import app.ownplay.player.persistence.ProviderCategoryEntity
import app.ownplay.player.persistence.live.LiveBrowseDao
import app.ownplay.player.persistence.live.LiveChannelRecord
import app.ownplay.player.persistence.live.LiveCustomGroupRecord
import app.ownplay.player.persistence.live.LiveGroupMembershipRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCatalogRepositoryTest {
    @Test
    fun observeIncludesEmptyCustomGroupsSoFirstMembershipCanBeAdded() = runBlocking {
        val repository = LiveCatalogRepository(
            dao = FakeLiveBrowseDao(
                groups = listOf(
                    LiveCustomGroupRecord(
                        groupId = "empty-group",
                        name = "My Main Channels",
                        groupOrder = 0,
                    ),
                ),
                memberships = emptyList(),
            ),
        )

        val snapshot = repository.observe("source-a").first()

        assertEquals(listOf("empty-group"), snapshot.customGroups.map { it.groupId })
        assertEquals(emptyMap<String, Set<String>>(), snapshot.customGroupIdsByChannelId)
    }

    private class FakeLiveBrowseDao(
        private val groups: List<LiveCustomGroupRecord>,
        private val memberships: List<LiveGroupMembershipRecord>,
    ) : LiveBrowseDao {
        override fun observeChannels(sourceId: String): Flow<List<LiveChannelRecord>> = flowOf(emptyList())

        override fun observeCategories(sourceId: String): Flow<List<ProviderCategoryEntity>> = flowOf(emptyList())

        override fun observeCustomGroups(): Flow<List<LiveCustomGroupRecord>> = flowOf(groups)

        override fun observeGroupMemberships(sourceId: String): Flow<List<LiveGroupMembershipRecord>> =
            flowOf(memberships)
    }
}
