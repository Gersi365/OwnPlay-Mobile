package app.ownplay.player.persistence.recent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import app.ownplay.player.persistence.OwnPlayDatabase
import app.ownplay.player.persistence.ProviderChannelEntity
import kotlinx.coroutines.flow.Flow

const val RECENT_CHANNEL_LIMIT = 20

@Entity(
    tableName = "recent_channels",
    foreignKeys = [
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["channelId"],
            childColumns = ["channelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["watchedAtEpochMillis"])],
)
data class RecentChannelEntity(
    @PrimaryKey val channelId: String,
    val watchedAtEpochMillis: Long,
)

@Dao
interface RecentChannelDao {
    @Upsert
    suspend fun upsert(entry: RecentChannelEntity)

    @Query(
        """
        DELETE FROM recent_channels
        WHERE channelId NOT IN (
            SELECT channelId
            FROM recent_channels
            ORDER BY watchedAtEpochMillis DESC, channelId ASC
            LIMIT :limit
        )
        """,
    )
    suspend fun trimToLimit(limit: Int)

    @Query(
        """
        SELECT * FROM recent_channels
        ORDER BY watchedAtEpochMillis DESC, channelId ASC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<RecentChannelEntity>>

    @Query("DELETE FROM recent_channels WHERE channelId = :channelId")
    suspend fun remove(channelId: String)
}

class RecentChannelHistory(
    private val database: OwnPlayDatabase,
) {
    fun observe(limit: Int = RECENT_CHANNEL_LIMIT): Flow<List<RecentChannelEntity>> {
        require(limit > 0) { "Recent channel limit must be positive" }
        return database.recentChannelDao().observeRecent(limit)
    }

    suspend fun recordWatch(
        channelId: String,
        watchedAtEpochMillis: Long,
    ) {
        require(channelId.isNotBlank()) { "Channel ID must not be blank" }
        require(watchedAtEpochMillis >= 0) { "Watch timestamp must not be negative" }

        database.withTransaction {
            val dao = database.recentChannelDao()
            dao.upsert(
                RecentChannelEntity(
                    channelId = channelId,
                    watchedAtEpochMillis = watchedAtEpochMillis,
                ),
            )
            dao.trimToLimit(RECENT_CHANNEL_LIMIT)
        }
    }
}
