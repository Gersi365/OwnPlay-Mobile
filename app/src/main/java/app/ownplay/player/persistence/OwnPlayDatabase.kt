package app.ownplay.player.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.ownplay.player.persistence.download.MediaDownloadDao
import app.ownplay.player.persistence.download.MediaDownloadEntity
import app.ownplay.player.persistence.live.LiveBrowseDao
import app.ownplay.player.persistence.recent.RecentChannelDao
import app.ownplay.player.persistence.recent.RecentChannelEntity
import app.ownplay.player.persistence.series.ProviderSeriesCategoryEntity
import app.ownplay.player.persistence.series.ProviderSeriesEntity
import app.ownplay.player.persistence.series.ProviderSeriesEpisodeEntity
import app.ownplay.player.persistence.series.ProviderSeriesSeasonEntity
import app.ownplay.player.persistence.series.SeriesCatalogDao
import app.ownplay.player.persistence.sync.DeviceSyncChannelEntity
import app.ownplay.player.persistence.sync.DeviceSyncDao
import app.ownplay.player.persistence.sync.DeviceSyncGroupEntity
import app.ownplay.player.persistence.sync.DeviceSyncGroupMembershipEntity
import app.ownplay.player.persistence.sync.DeviceSyncLocalStateEntity
import app.ownplay.player.persistence.sync.DeviceSyncSourceEntity
import app.ownplay.player.persistence.sync.MIGRATION_5_6
import app.ownplay.player.persistence.vod.MediaFavoriteEntity
import app.ownplay.player.persistence.vod.PlaybackProgressEntity
import app.ownplay.player.persistence.vod.ProviderMovieEntity
import app.ownplay.player.persistence.vod.ProviderVodCategoryEntity
import app.ownplay.player.persistence.vod.VodCatalogDao

@Database(
    entities = [
        PlaylistSourceEntity::class,
        ProviderCategoryEntity::class,
        ProviderChannelEntity::class,
        ChannelCustomizationEntity::class,
        HiddenEntryEntity::class,
        FavoriteEntryEntity::class,
        CustomGroupEntity::class,
        CustomGroupMembershipEntity::class,
        PlaylistRefreshStateEntity::class,
        RecentChannelEntity::class,
        ProviderVodCategoryEntity::class,
        ProviderMovieEntity::class,
        MediaFavoriteEntity::class,
        PlaybackProgressEntity::class,
        MediaDownloadEntity::class,
        ProviderSeriesCategoryEntity::class,
        ProviderSeriesEntity::class,
        ProviderSeriesSeasonEntity::class,
        ProviderSeriesEpisodeEntity::class,
        DeviceSyncLocalStateEntity::class,
        DeviceSyncSourceEntity::class,
        DeviceSyncChannelEntity::class,
        DeviceSyncGroupEntity::class,
        DeviceSyncGroupMembershipEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class OwnPlayDatabase : RoomDatabase() {
    abstract fun playlistSourceDao(): PlaylistSourceDao
    abstract fun providerCatalogDao(): ProviderCatalogDao
    abstract fun personalizationDao(): PersonalizationDao
    abstract fun refreshStateDao(): RefreshStateDao
    abstract fun liveBrowseDao(): LiveBrowseDao
    abstract fun recentChannelDao(): RecentChannelDao
    abstract fun vodCatalogDao(): VodCatalogDao
    abstract fun mediaDownloadDao(): MediaDownloadDao
    abstract fun seriesCatalogDao(): SeriesCatalogDao
    abstract fun deviceSyncDao(): DeviceSyncDao

    companion object {
        const val DATABASE_NAME = "ownplay.db"

        fun create(context: Context): OwnPlayDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                OwnPlayDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                )
                .enableMultiInstanceInvalidation()
                .build()
    }
}
