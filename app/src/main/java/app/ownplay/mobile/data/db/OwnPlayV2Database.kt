package app.ownplay.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        V2SourceEntity::class,
        V2CategoryEntity::class,
        V2MediaItemEntity::class,
        V2ItemPersonalizationEntity::class,
        V2CustomGroupEntity::class,
        V2CustomGroupMembershipEntity::class,
        V2MediaFavoriteEntity::class,
        V2PlaybackProgressEntity::class,
        V2DownloadMetadataEntity::class,
        V2MigrationStateEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OwnPlayV2Database : RoomDatabase() {
    abstract fun sourceDao(): V2SourceDao
    abstract fun catalogDao(): V2CatalogDao
    abstract fun personalizationDao(): V2PersonalizationDao
    abstract fun migrationDao(): V2MigrationDao

    companion object {
        const val DATABASE_NAME = "ownplay_v2.db"

        fun create(context: Context): OwnPlayV2Database =
            Room.databaseBuilder(
                context.applicationContext,
                OwnPlayV2Database::class.java,
                DATABASE_NAME,
            ).build()
    }
}
