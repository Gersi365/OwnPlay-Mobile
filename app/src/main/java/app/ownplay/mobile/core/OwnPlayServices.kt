package app.ownplay.mobile.core

import android.content.Context
import app.ownplay.mobile.data.db.OwnPlayDatabase
import app.ownplay.mobile.data.prefs.ActiveSourcePreferences
import app.ownplay.mobile.data.security.CredentialStore
import app.ownplay.mobile.data.security.KeystoreCredentialStore
import app.ownplay.mobile.downloads.data.AndroidDownloadStorage
import app.ownplay.mobile.downloads.data.DownloadAwareSourceRepository
import app.ownplay.mobile.downloads.data.DownloadExecutor
import app.ownplay.mobile.downloads.data.DataStoreDownloadDestinationAssignmentStore
import app.ownplay.mobile.downloads.data.DataStoreDownloadPreferencesRepository
import app.ownplay.mobile.downloads.data.DownloadPreferencesDataStore
import app.ownplay.mobile.downloads.data.DownloadNotificationController
import app.ownplay.mobile.downloads.data.DownloadNotificationPermissionPreferences
import app.ownplay.mobile.downloads.data.DownloadQueueCoordinator
import app.ownplay.mobile.downloads.data.RoomDownloadPresentationMetadataResolver
import app.ownplay.mobile.downloads.data.ManagedSourceRemovalDownloadCoordinator
import app.ownplay.mobile.downloads.data.OkHttpDownloadTransferClient
import app.ownplay.mobile.downloads.data.RoomDownloadRepository
import app.ownplay.mobile.downloads.data.SourceBackedDownloadMediaResolver
import app.ownplay.mobile.downloads.data.WorkManagedDownloadRepository
import app.ownplay.mobile.downloads.data.WorkManagerDownloadScheduler
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.downloads.domain.DownloadPresentationMetadataResolver
import app.ownplay.mobile.downloads.domain.DownloadRepository
import app.ownplay.mobile.feature.library.data.DownloadAwareLibraryPlaybackResolver
import app.ownplay.mobile.feature.library.data.DataStoreLibraryContinueWatchingSuppressionStore
import app.ownplay.mobile.downloads.data.AndroidDownloadedMediaVerifier
import app.ownplay.mobile.feature.library.data.LibraryArtworkLoader
import app.ownplay.mobile.feature.library.data.LibraryPlaybackLocator
import app.ownplay.mobile.feature.library.data.OkHttpLibraryArtworkLoader
import app.ownplay.mobile.feature.library.data.RoomLibraryPlaybackProgressStore
import app.ownplay.mobile.feature.library.data.RoomLibraryRepository
import app.ownplay.mobile.feature.library.data.SourceBackedLibraryMovieDetailLoader
import app.ownplay.mobile.feature.library.data.SourceBackedLibraryPlaybackLocator
import app.ownplay.mobile.feature.library.data.SourceBackedLibrarySeriesDetailRefresher
import app.ownplay.mobile.feature.library.domain.LibraryRepository
import app.ownplay.mobile.feature.live.data.RoomLiveCatchUpPlaybackProgressStore
import app.ownplay.mobile.feature.live.data.RoomLiveOrganizationRefreshStore
import app.ownplay.mobile.feature.live.data.SourceBackedLiveCatchUpRepository
import app.ownplay.mobile.feature.live.data.SourceBackedLiveGuideRepository
import app.ownplay.mobile.feature.live.data.RoomLiveOrganizationRepository
import app.ownplay.mobile.feature.live.domain.LiveCatchUpRepository
import app.ownplay.mobile.feature.live.domain.LiveGuideRepository
import app.ownplay.mobile.feature.live.domain.LiveOrganizationRepository
import app.ownplay.mobile.feature.playback.data.DefaultLivePlaybackMediaPreparer
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngine
import app.ownplay.mobile.feature.playback.data.Media3PlaybackEngineAdapter
import app.ownplay.mobile.feature.playback.data.DataStorePlaybackPreferencesRepository
import app.ownplay.mobile.feature.playback.data.PlaybackPreferencesDataStore
import app.ownplay.mobile.feature.playback.data.SourceBackedCatchUpPlaybackMediaResolver
import app.ownplay.mobile.feature.playback.data.SourceBackedLivePlaybackSourceResolver
import app.ownplay.mobile.feature.playback.domain.PlaybackPreferencesRepository
import app.ownplay.mobile.feature.playback.domain.PlaybackSessionController
import app.ownplay.mobile.feature.settings.data.DataStoreDisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.data.DisplayPreferencesDataStore
import app.ownplay.mobile.feature.settings.data.ManagedSourceRefreshScheduleRepository
import app.ownplay.mobile.feature.settings.data.RefreshScheduleAwareSourceRepository
import app.ownplay.mobile.feature.settings.data.SourceRefreshSchedulePreferences
import app.ownplay.mobile.feature.settings.data.WorkManagerSourceRefreshScheduler
import app.ownplay.mobile.feature.settings.backup.data.BackupPreferenceGateway
import app.ownplay.mobile.feature.settings.backup.data.BackupJsonCodec
import app.ownplay.mobile.feature.settings.backup.data.RoomBackupRestoreRepository
import app.ownplay.mobile.feature.settings.backup.data.RestoredActiveSourceAwareSourceRepository
import app.ownplay.mobile.feature.settings.backup.domain.BackupRestoreRepository
import app.ownplay.mobile.feature.settings.domain.DisplayPreferencesRepository
import app.ownplay.mobile.feature.settings.domain.SourceRefreshScheduleRepository
import app.ownplay.mobile.sources.data.DefaultSourceCatalogLoader
import app.ownplay.mobile.sources.data.OkHttpProviderTransport
import app.ownplay.mobile.sources.data.ProviderTransport
import app.ownplay.mobile.sources.data.RoomCatalogRefreshStore
import app.ownplay.mobile.sources.data.SourceRepositoryImpl
import app.ownplay.mobile.sources.data.m3u.M3uClient
import app.ownplay.mobile.sources.data.m3u.OkHttpM3uClient
import app.ownplay.mobile.sources.data.xtream.OkHttpXtreamClient
import app.ownplay.mobile.sources.data.xtream.XtreamClient
import app.ownplay.mobile.sources.domain.SourceRepository

class OwnPlayServices private constructor(
    val database: OwnPlayDatabase,
    val activeSourcePreferences: ActiveSourcePreferences,
    val credentialStore: CredentialStore,
    val sourceRepository: SourceRepository,
    val refreshScheduleRepository: SourceRefreshScheduleRepository,
    val displayPreferencesRepository: DisplayPreferencesRepository,
    val playbackPreferencesRepository: PlaybackPreferencesRepository,
    val downloadPreferencesRepository: DownloadPreferencesRepository,
    internal val downloadNotificationPermissionPreferences: DownloadNotificationPermissionPreferences,
    val backupRestoreRepository: BackupRestoreRepository,
    val liveOrganizationRepository: LiveOrganizationRepository,
    val liveGuideRepository: LiveGuideRepository,
    val liveCatchUpRepository: LiveCatchUpRepository,
    val libraryRepository: LibraryRepository,
    val downloadRepository: DownloadRepository,
    internal val downloadPresentationMetadataResolver: DownloadPresentationMetadataResolver,
    internal val downloadQueueCoordinator: DownloadQueueCoordinator,
    internal val downloadExecutor: DownloadExecutor,
    internal val downloadNotifications: DownloadNotificationController,
    internal val libraryPlaybackLocator: LibraryPlaybackLocator,
    internal val libraryArtworkLoader: LibraryArtworkLoader,
    val playbackSessionController: PlaybackSessionController,
    val playbackEngine: Media3PlaybackEngine,
    val providerTransport: ProviderTransport,
    val m3uClient: M3uClient,
    val xtreamClient: XtreamClient,
) {
    internal fun releasePlayback() {
        playbackSessionController.release()
    }

    companion object {
        fun create(context: Context): OwnPlayServices {
            val applicationContext = context.applicationContext
            val database = OwnPlayDatabase.create(applicationContext)
            val activeSourcePreferences = ActiveSourcePreferences(applicationContext)
            val credentialStore = KeystoreCredentialStore(applicationContext)
            val transport = OkHttpProviderTransport()
            val m3uClient = OkHttpM3uClient(transport)
            val xtreamClient = OkHttpXtreamClient(transport)
            val sourceDao = database.sourceDao()
            val refreshStateDao = database.refreshStateDao()
            val liveOrganizationDao = database.liveOrganizationDao()
            val libraryDao = database.libraryDao()
            val downloadDao = database.downloadDao()
            val catalogLoader = DefaultSourceCatalogLoader(
                xtreamClient = xtreamClient,
                m3uClient = m3uClient,
            )
            val liveOrganizationRefreshStore = RoomLiveOrganizationRefreshStore(liveOrganizationDao)
            val catalogRefreshStore = RoomCatalogRefreshStore(
                database = database,
                refreshStateDao = refreshStateDao,
                liveOrganizationRefreshStore = liveOrganizationRefreshStore,
            )
            val liveOrganizationRepository = RoomLiveOrganizationRepository(
                database = database,
                dao = liveOrganizationDao,
            )
            val liveGuideRepository = SourceBackedLiveGuideRepository(
                sourceDao = sourceDao,
                liveOrganizationDao = liveOrganizationDao,
                credentialStore = credentialStore,
                xtreamClient = xtreamClient,
            )
            val liveCatchUpRepository = SourceBackedLiveCatchUpRepository(
                sourceDao = sourceDao,
                liveOrganizationDao = liveOrganizationDao,
                libraryDao = libraryDao,
                credentialStore = credentialStore,
                xtreamClient = xtreamClient,
            )
            val libraryDetailRefresher = SourceBackedLibrarySeriesDetailRefresher(
                sourceDao = sourceDao,
                libraryDao = libraryDao,
                credentialStore = credentialStore,
                xtreamClient = xtreamClient,
            )
            val libraryMovieDetailLoader = SourceBackedLibraryMovieDetailLoader(
                sourceDao = sourceDao,
                libraryDao = libraryDao,
                credentialStore = credentialStore,
                xtreamClient = xtreamClient,
            )
            val libraryRepository = RoomLibraryRepository(
                dao = libraryDao,
                detailRefresher = libraryDetailRefresher,
                movieDetailLoader = libraryMovieDetailLoader,
                continueWatchingSuppressionStore =
                    DataStoreLibraryContinueWatchingSuppressionStore(applicationContext),
            )
            val libraryPlaybackLocator = SourceBackedLibraryPlaybackLocator(
                sourceDao = sourceDao,
                libraryDao = libraryDao,
                credentialStore = credentialStore,
            )
            val downloadNotifications = DownloadNotificationController(applicationContext)
            val downloadPreferencesStore = DownloadPreferencesDataStore(applicationContext)
            val downloadPreferencesRepository = DataStoreDownloadPreferencesRepository(
                downloadPreferencesStore,
            )
            val downloadDestinationAssignments =
                DataStoreDownloadDestinationAssignmentStore(applicationContext)
            val downloadStorage = AndroidDownloadStorage(
                applicationContext,
                downloadDestinationAssignments,
            )
            val downloadNotificationPermissionPreferences =
                DownloadNotificationPermissionPreferences(applicationContext)
            val downloadScheduler = WorkManagerDownloadScheduler(
                context = applicationContext,
            )
            val downloadedMediaVerifier = AndroidDownloadedMediaVerifier(applicationContext)
            val downloadRepository = WorkManagedDownloadRepository(
                delegate = RoomDownloadRepository(downloadDao),
                scheduler = downloadScheduler,
                storage = downloadStorage,
                notifications = downloadNotifications,
                availabilityProbe = downloadedMediaVerifier,
                preferencesRepository = downloadPreferencesRepository,
                destinationAssignments = downloadDestinationAssignments,
            )
            val downloadPresentationMetadataResolver =
                RoomDownloadPresentationMetadataResolver(libraryDao)
            val downloadQueueCoordinator = DownloadQueueCoordinator(
                dao = downloadDao,
                repository = downloadRepository,
            )
            val sourceRemovalDownloadCoordinator = ManagedSourceRemovalDownloadCoordinator(
                downloadDao = downloadDao,
                scheduler = downloadScheduler,
                storage = downloadStorage,
                notifications = downloadNotifications,
                destinationAssignments = downloadDestinationAssignments,
            )
            val displayPreferencesStore = DisplayPreferencesDataStore(applicationContext)
            val displayPreferencesRepository = DataStoreDisplayPreferencesRepository(displayPreferencesStore)
            val playbackPreferencesStore = PlaybackPreferencesDataStore(applicationContext)
            val playbackPreferencesRepository = DataStorePlaybackPreferencesRepository(playbackPreferencesStore)
            val sourceRefreshScheduler = WorkManagerSourceRefreshScheduler(applicationContext)
            val sourceRefreshStore = SourceRefreshSchedulePreferences(applicationContext)
            val refreshScheduleRepository = ManagedSourceRefreshScheduleRepository(
                store = sourceRefreshStore,
                scheduler = sourceRefreshScheduler,
                sourceExists = { sourceId -> sourceDao.get(sourceId.value)?.enabled == true },
            )
            val baseSourceRepository = SourceRepositoryImpl(
                sourceDao = sourceDao,
                refreshStateDao = refreshStateDao,
                activeSourceStore = activeSourcePreferences,
                credentialStore = credentialStore,
                catalogLoader = catalogLoader,
                catalogRefreshStore = catalogRefreshStore,
            )
            val downloadAwareSourceRepository = DownloadAwareSourceRepository(
                delegate = baseSourceRepository,
                removalCoordinator = sourceRemovalDownloadCoordinator,
            )
            val refreshAwareSourceRepository = RefreshScheduleAwareSourceRepository(
                delegate = downloadAwareSourceRepository,
                scheduleCleanup = refreshScheduleRepository,
                refreshStore = sourceRefreshStore,
                scheduler = sourceRefreshScheduler,
            )
            val backupRestoreRepository = RoomBackupRestoreRepository(
                database = database,
                sourceDao = sourceDao,
                backupDao = database.backupDao(),
                preferences = BackupPreferenceGateway(
                    activeSourceStore = activeSourcePreferences,
                    intendedActiveSourceStore = activeSourcePreferences,
                    displayRepository = displayPreferencesRepository,
                    playbackRepository = playbackPreferencesRepository,
                    downloadRepository = downloadPreferencesRepository,
                    refreshStore = sourceRefreshStore,
                    refreshScheduler = sourceRefreshScheduler,
                ),
                codec = BackupJsonCodec(),
            )
            val sourceRepository = RestoredActiveSourceAwareSourceRepository(
                delegate = refreshAwareSourceRepository,
                intendedActiveSourceStore = activeSourcePreferences,
            )
            val downloadExecutor = DownloadExecutor(
                repository = downloadRepository,
                mediaResolver = SourceBackedDownloadMediaResolver(
                    libraryDao = libraryDao,
                    libraryPlaybackLocator = libraryPlaybackLocator,
                ),
                storage = downloadStorage,
                transferClient = OkHttpDownloadTransferClient(),
            )
            val libraryMediaResolver = DownloadAwareLibraryPlaybackResolver(
                onlineResolver = libraryPlaybackLocator,
                downloadRepository = downloadRepository,
                verifier = downloadedMediaVerifier,
            )
            val libraryArtworkLoader = OkHttpLibraryArtworkLoader()
            val libraryPlaybackProgressStore = RoomLibraryPlaybackProgressStore(libraryDao)
            val playbackSourceResolver = SourceBackedLivePlaybackSourceResolver(
                sourceDao = sourceDao,
                liveOrganizationDao = liveOrganizationDao,
                credentialStore = credentialStore,
            )
            val catchUpPlaybackResolver = SourceBackedCatchUpPlaybackMediaResolver(
                sourceDao = sourceDao,
                liveOrganizationDao = liveOrganizationDao,
                credentialStore = credentialStore,
                xtreamClient = xtreamClient,
            )
            val catchUpProgressStore = RoomLiveCatchUpPlaybackProgressStore(libraryDao)
            val playbackEngine = Media3PlaybackEngine(applicationContext)
            val playbackEngineAdapter = Media3PlaybackEngineAdapter(playbackEngine)
            val playbackSessionController = PlaybackSessionController(
                sourceResolver = playbackSourceResolver,
                mediaPreparer = DefaultLivePlaybackMediaPreparer(),
                playbackEngine = playbackEngineAdapter,
                libraryMediaResolver = libraryMediaResolver,
                catchUpMediaResolver = catchUpPlaybackResolver,
                playbackProgressEngine = playbackEngineAdapter,
                libraryProgressStore = libraryPlaybackProgressStore,
                catchUpProgressStore = catchUpProgressStore,
            )

            return OwnPlayServices(
                database = database,
                activeSourcePreferences = activeSourcePreferences,
                credentialStore = credentialStore,
                sourceRepository = sourceRepository,
                refreshScheduleRepository = refreshScheduleRepository,
                displayPreferencesRepository = displayPreferencesRepository,
                playbackPreferencesRepository = playbackPreferencesRepository,
                downloadPreferencesRepository = downloadPreferencesRepository,
                downloadNotificationPermissionPreferences = downloadNotificationPermissionPreferences,
                backupRestoreRepository = backupRestoreRepository,
                liveOrganizationRepository = liveOrganizationRepository,
                liveGuideRepository = liveGuideRepository,
                liveCatchUpRepository = liveCatchUpRepository,
                libraryRepository = libraryRepository,
                downloadRepository = downloadRepository,
                downloadPresentationMetadataResolver = downloadPresentationMetadataResolver,
                downloadQueueCoordinator = downloadQueueCoordinator,
                downloadExecutor = downloadExecutor,
                downloadNotifications = downloadNotifications,
                libraryPlaybackLocator = libraryPlaybackLocator,
                libraryArtworkLoader = libraryArtworkLoader,
                playbackSessionController = playbackSessionController,
                playbackEngine = playbackEngine,
                providerTransport = transport,
                m3uClient = m3uClient,
                xtreamClient = xtreamClient,
            )
        }
    }
}
