package app.ownplay.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SourceEntity::class,
        ProviderCategoryEntity::class,
        CategoryPersonalizationEntity::class,
        LiveChannelEntity::class,
        ChannelPersonalizationEntity::class,
        CustomGroupEntity::class,
        CustomGroupMembershipEntity::class,
        MovieEntity::class,
        SeriesEntity::class,
        EpisodeEntity::class,
        MediaFavoriteEntity::class,
        PlaybackProgressEntity::class,
        DownloadEntity::class,
        RefreshStateEntity::class,
        LiveOrganizationPreferenceEntity::class,
        OwnPlayLiveCategoryEntity::class,
        LiveCategoryScopePersonalizationEntity::class,
        OwnPlayLiveChannelMembershipEntity::class,
        LiveChannelMembershipPersonalizationEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OwnPlayDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao

    abstract fun refreshStateDao(): RefreshStateDao

    abstract fun liveOrganizationDao(): LiveOrganizationDao

    abstract fun libraryDao(): LibraryDao

    abstract fun downloadDao(): DownloadDao

    abstract fun backupDao(): BackupDao

    companion object {
        private const val PROVIDER_MODE = "PROVIDER"
        private const val PROVIDER_UNCATEGORIZED_CATEGORY_ID = "__provider_uncategorized__"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `category_personalization` (
                        `sourceId` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `categoryKey` TEXT NOT NULL,
                        `hidden` INTEGER NOT NULL DEFAULT 0,
                        `manualOrder` INTEGER,
                        PRIMARY KEY(`sourceId`, `kind`, `categoryKey`),
                        FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_category_personalization_sourceId` ON `category_personalization` (`sourceId`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createLiveOrganizationTables(db)
                seedProviderOrganizationBridge(db)
                createProviderOrganizationBridgeTriggers(db)
            }
        }

        private val LIVE_ORGANIZATION_BRIDGE_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createProviderOrganizationBridgeTriggers(db)
            }
        }

        fun create(context: Context): OwnPlayDatabase = Room.databaseBuilder(
            context.applicationContext,
            OwnPlayDatabase::class.java,
            "ownplay-v1.db",
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .addCallback(LIVE_ORGANIZATION_BRIDGE_CALLBACK)
            .build()

        private fun createLiveOrganizationTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `live_organization_preferences` (
                    `sourceId` TEXT NOT NULL,
                    `activeMode` TEXT NOT NULL,
                    PRIMARY KEY(`sourceId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ownplay_live_categories` (
                    `sourceId` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `parentCategoryId` TEXT,
                    `displayName` TEXT NOT NULL,
                    `semanticKey` TEXT,
                    `origin` TEXT NOT NULL,
                    `available` INTEGER NOT NULL,
                    `lastSeenGeneration` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `categoryId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ownplay_live_categories_sourceId` ON `ownplay_live_categories` (`sourceId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ownplay_live_categories_sourceId_parentCategoryId` ON `ownplay_live_categories` (`sourceId`, `parentCategoryId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `live_category_scope_personalization` (
                    `sourceId` TEXT NOT NULL,
                    `organizationMode` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `hidden` INTEGER NOT NULL,
                    `manualOrder` INTEGER,
                    PRIMARY KEY(`sourceId`, `organizationMode`, `categoryId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_live_category_scope_personalization_sourceId` ON `live_category_scope_personalization` (`sourceId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_live_category_scope_personalization_sourceId_organizationMode` ON `live_category_scope_personalization` (`sourceId`, `organizationMode`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `ownplay_live_channel_memberships` (
                    `sourceId` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `channelId` TEXT NOT NULL,
                    `included` INTEGER NOT NULL,
                    `origin` TEXT NOT NULL,
                    `confidence` TEXT,
                    `evidenceJson` TEXT,
                    `available` INTEGER NOT NULL,
                    `lastSeenGeneration` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `categoryId`, `channelId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceId`, `categoryId`) REFERENCES `ownplay_live_categories`(`sourceId`, `categoryId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`channelId`) REFERENCES `live_channels`(`channelId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ownplay_live_channel_memberships_sourceId` ON `ownplay_live_channel_memberships` (`sourceId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ownplay_live_channel_memberships_channelId` ON `ownplay_live_channel_memberships` (`channelId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ownplay_live_channel_memberships_sourceId_categoryId` ON `ownplay_live_channel_memberships` (`sourceId`, `categoryId`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `live_channel_membership_personalization` (
                    `sourceId` TEXT NOT NULL,
                    `organizationMode` TEXT NOT NULL,
                    `categoryId` TEXT NOT NULL,
                    `channelId` TEXT NOT NULL,
                    `hidden` INTEGER NOT NULL,
                    `manualOrder` INTEGER,
                    PRIMARY KEY(`sourceId`, `organizationMode`, `categoryId`, `channelId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`sourceId`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`channelId`) REFERENCES `live_channels`(`channelId`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_live_channel_membership_personalization_sourceId` ON `live_channel_membership_personalization` (`sourceId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_live_channel_membership_personalization_channelId` ON `live_channel_membership_personalization` (`channelId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_live_channel_membership_personalization_sourceId_organizationMode_categoryId` ON `live_channel_membership_personalization` (`sourceId`, `organizationMode`, `categoryId`)",
            )
        }

        private fun seedProviderOrganizationBridge(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                INSERT OR IGNORE INTO `live_organization_preferences` (`sourceId`, `activeMode`)
                SELECT `sourceId`, '$PROVIDER_MODE' FROM `sources`
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `live_category_scope_personalization`
                    (`sourceId`, `organizationMode`, `categoryId`, `hidden`, `manualOrder`)
                SELECT `sourceId`, '$PROVIDER_MODE', `categoryKey`, `hidden`, `manualOrder`
                FROM `category_personalization`
                WHERE `kind` = 'LIVE'
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR REPLACE INTO `live_channel_membership_personalization`
                    (`sourceId`, `organizationMode`, `categoryId`, `channelId`, `hidden`, `manualOrder`)
                SELECT
                    c.`sourceId`,
                    '$PROVIDER_MODE',
                    COALESCE(c.`categoryKey`, '$PROVIDER_UNCATEGORIZED_CATEGORY_ID'),
                    c.`channelId`,
                    p.`hidden`,
                    p.`manualOrder`
                FROM `channel_personalization` AS p
                INNER JOIN `live_channels` AS c ON c.`channelId` = p.`channelId`
                """.trimIndent(),
            )
        }

        private fun createProviderOrganizationBridgeTriggers(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_source_insert`
                AFTER INSERT ON `sources`
                BEGIN
                    INSERT OR IGNORE INTO `live_organization_preferences` (`sourceId`, `activeMode`)
                    VALUES (NEW.`sourceId`, '$PROVIDER_MODE');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_category_personalization_insert`
                AFTER INSERT ON `category_personalization`
                WHEN NEW.`kind` = 'LIVE'
                BEGIN
                    INSERT OR REPLACE INTO `live_category_scope_personalization`
                        (`sourceId`, `organizationMode`, `categoryId`, `hidden`, `manualOrder`)
                    VALUES (NEW.`sourceId`, '$PROVIDER_MODE', NEW.`categoryKey`, NEW.`hidden`, NEW.`manualOrder`);
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_category_personalization_update`
                AFTER UPDATE ON `category_personalization`
                WHEN OLD.`kind` = 'LIVE' OR NEW.`kind` = 'LIVE'
                BEGIN
                    DELETE FROM `live_category_scope_personalization`
                    WHERE `sourceId` = OLD.`sourceId`
                      AND `organizationMode` = '$PROVIDER_MODE'
                      AND `categoryId` = OLD.`categoryKey`;
                    INSERT OR REPLACE INTO `live_category_scope_personalization`
                        (`sourceId`, `organizationMode`, `categoryId`, `hidden`, `manualOrder`)
                    SELECT NEW.`sourceId`, '$PROVIDER_MODE', NEW.`categoryKey`, NEW.`hidden`, NEW.`manualOrder`
                    WHERE NEW.`kind` = 'LIVE';
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_category_personalization_delete`
                AFTER DELETE ON `category_personalization`
                WHEN OLD.`kind` = 'LIVE'
                BEGIN
                    DELETE FROM `live_category_scope_personalization`
                    WHERE `sourceId` = OLD.`sourceId`
                      AND `organizationMode` = '$PROVIDER_MODE'
                      AND `categoryId` = OLD.`categoryKey`;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_channel_personalization_insert`
                AFTER INSERT ON `channel_personalization`
                BEGIN
                    DELETE FROM `live_channel_membership_personalization`
                    WHERE `channelId` = NEW.`channelId`
                      AND `organizationMode` = '$PROVIDER_MODE';
                    INSERT OR REPLACE INTO `live_channel_membership_personalization`
                        (`sourceId`, `organizationMode`, `categoryId`, `channelId`, `hidden`, `manualOrder`)
                    SELECT
                        c.`sourceId`,
                        '$PROVIDER_MODE',
                        COALESCE(c.`categoryKey`, '$PROVIDER_UNCATEGORIZED_CATEGORY_ID'),
                        c.`channelId`,
                        NEW.`hidden`,
                        NEW.`manualOrder`
                    FROM `live_channels` AS c
                    WHERE c.`channelId` = NEW.`channelId`;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_channel_personalization_update`
                AFTER UPDATE ON `channel_personalization`
                BEGIN
                    DELETE FROM `live_channel_membership_personalization`
                    WHERE `channelId` = NEW.`channelId`
                      AND `organizationMode` = '$PROVIDER_MODE';
                    INSERT OR REPLACE INTO `live_channel_membership_personalization`
                        (`sourceId`, `organizationMode`, `categoryId`, `channelId`, `hidden`, `manualOrder`)
                    SELECT
                        c.`sourceId`,
                        '$PROVIDER_MODE',
                        COALESCE(c.`categoryKey`, '$PROVIDER_UNCATEGORIZED_CATEGORY_ID'),
                        c.`channelId`,
                        NEW.`hidden`,
                        NEW.`manualOrder`
                    FROM `live_channels` AS c
                    WHERE c.`channelId` = NEW.`channelId`;
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_channel_personalization_delete`
                AFTER DELETE ON `channel_personalization`
                BEGIN
                    DELETE FROM `live_channel_membership_personalization`
                    WHERE `channelId` = OLD.`channelId`
                      AND `organizationMode` = '$PROVIDER_MODE';
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS `trg_live_org_channel_category_update`
                AFTER UPDATE OF `categoryKey` ON `live_channels`
                WHEN OLD.`categoryKey` IS NOT NEW.`categoryKey`
                BEGIN
                    DELETE FROM `live_channel_membership_personalization`
                    WHERE `channelId` = NEW.`channelId`
                      AND `organizationMode` = '$PROVIDER_MODE';
                    INSERT OR REPLACE INTO `live_channel_membership_personalization`
                        (`sourceId`, `organizationMode`, `categoryId`, `channelId`, `hidden`, `manualOrder`)
                    SELECT
                        NEW.`sourceId`,
                        '$PROVIDER_MODE',
                        COALESCE(NEW.`categoryKey`, '$PROVIDER_UNCATEGORIZED_CATEGORY_ID'),
                        NEW.`channelId`,
                        p.`hidden`,
                        p.`manualOrder`
                    FROM `channel_personalization` AS p
                    WHERE p.`channelId` = NEW.`channelId`;
                END
                """.trimIndent(),
            )
        }
    }
}
