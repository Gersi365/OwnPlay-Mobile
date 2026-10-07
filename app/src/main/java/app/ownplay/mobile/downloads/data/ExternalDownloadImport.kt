package app.ownplay.mobile.downloads.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ownplay.mobile.data.db.DownloadDao
import app.ownplay.mobile.data.db.DownloadEntity
import app.ownplay.mobile.data.db.LibraryDao
import app.ownplay.mobile.data.db.LibraryDownloadImportEpisodeRow
import app.ownplay.mobile.data.db.MovieEntity
import app.ownplay.mobile.downloads.domain.DownloadFilePolicy
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadMediaKind
import app.ownplay.mobile.downloads.domain.DownloadOrigin
import app.ownplay.mobile.downloads.domain.DownloadPreferencesRepository
import app.ownplay.mobile.downloads.domain.DownloadStatus
import app.ownplay.mobile.sources.domain.SourceId
import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.externalDownloadImportDataStore by preferencesDataStore(name = "ownplay_external_download_import")

internal enum class ExternalDownloadScanStatus {
    COMPLETE,
    TRUNCATED,
    PERMISSION_ERROR,
    ERROR,
}

internal enum class ExternalDownloadImportIssueKind {
    UNMATCHED,
    AMBIGUOUS,
    CONFLICT,
}

internal data class ExternalDownloadImportIssue(
    val file: ExternalDownloadImportFile,
    val candidates: List<ExternalDownloadImportMatch>,
    val kind: ExternalDownloadImportIssueKind,
)

internal data class ExternalDownloadImportResult(
    val discoveredVideoFiles: Int,
    val imported: Int,
    val alreadyTracked: Int,
    val unmatched: Int,
    val conflicts: Int,
    val ambiguous: Int = 0,
    val accessRequired: Boolean = false,
    val scanStatus: ExternalDownloadScanStatus = ExternalDownloadScanStatus.COMPLETE,
    val unresolvedFiles: List<ExternalDownloadImportIssue> = emptyList(),
)

internal data class ExternalDownloadImportFile(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val relativePathSegments: List<String>,
)

internal data class ExternalDownloadImportMatch(
    val mediaKind: DownloadMediaKind,
    val contentId: String,
    val title: String,
)

internal object ExternalDownloadImportMatcher {
    private val episodePattern = Regex("(?i)s(\\d{1,2})e(\\d{1,3})")
    private val ownPlaySuffixPattern = Regex("(?i)\\s+-\\s+[a-f0-9]{12}$")
    private val yearPattern = Regex("^(19|20)\\d{2}$")
    private val resolutionPattern = Regex("^(480|576|720|1080|1440|2160)p$")
    private val allowedMovieSuffixTokens = setOf(
        "4k", "uhd", "hdr", "dv", "dolby", "vision", "bluray", "blu", "ray",
        "webrip", "web", "dl", "webdl", "remux", "hdtv", "x264", "x265",
        "h264", "h265", "hevc", "av1", "aac", "ac3", "eac3", "ddp", "atmos",
        "proper", "repack", "extended", "unrated",
    )

    fun match(
        file: ExternalDownloadImportFile,
        movies: List<MovieEntity>,
        episodes: List<LibraryDownloadImportEpisodeRow>,
    ): ExternalDownloadImportMatch? = candidates(file, movies, episodes).singleOrNull()

    fun candidates(
        file: ExternalDownloadImportFile,
        movies: List<MovieEntity>,
        episodes: List<LibraryDownloadImportEpisodeRow>,
    ): List<ExternalDownloadImportMatch> {
        val stem = file.displayName.substringBeforeLast('.', file.displayName)
        val episodeToken = episodePattern.find(stem)
        if (episodeToken != null) {
            val season = episodeToken.groupValues[1].toIntOrNull() ?: return emptyList()
            val episodeNumber = episodeToken.groupValues[2].toIntOrNull() ?: return emptyList()
            val seriesHint = seriesHint(file.relativePathSegments)
                ?: canonicalName(stem.substring(0, episodeToken.range.first))
            if (seriesHint.isBlank()) return emptyList()
            return episodes
                .filter { row ->
                    row.seasonNumber == season &&
                        row.episodeNumber == episodeNumber &&
                        canonicalName(row.seriesTitle) == seriesHint
                }
                .distinctBy { it.episodeId }
                .map { row ->
                    ExternalDownloadImportMatch(DownloadMediaKind.EPISODE, row.episodeId, row.title)
                }
        }

        val rawStem = stem.replace(ownPlaySuffixPattern, "")
        val canonicalStem = canonicalName(rawStem)
        if (canonicalStem.isBlank()) return emptyList()
        return movies
            .filter { movie -> movieTitleMatches(canonicalStem, canonicalName(movie.name)) }
            .distinctBy { it.movieId }
            .map { movie ->
                ExternalDownloadImportMatch(DownloadMediaKind.MOVIE, movie.movieId, movie.name)
            }
    }

    private fun seriesHint(relativePathSegments: List<String>): String? {
        val seriesIndex = relativePathSegments.indexOfFirst { canonicalName(it) == "series" }
        return if (seriesIndex >= 0 && seriesIndex + 1 < relativePathSegments.size) {
            canonicalName(relativePathSegments[seriesIndex + 1]).takeIf(String::isNotBlank)
        } else {
            null
        }
    }

    private fun movieTitleMatches(stem: String, title: String): Boolean {
        if (title.isBlank()) return false
        if (stem == title) return true
        if (!stem.startsWith("$title ")) return false
        val suffix = stem.removePrefix(title).trim().split(' ').filter(String::isNotBlank)
        if (suffix.isEmpty()) return false
        return suffix.all { token ->
            token in allowedMovieSuffixTokens || yearPattern.matches(token) || resolutionPattern.matches(token)
        }
    }

    private fun canonicalName(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}

internal class ExternalDownloadImportManager(
    context: Context,
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
    @Suppress("UNUSED_PARAMETER") private val preferencesRepository: DownloadPreferencesRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val store = applicationContext.externalDownloadImportDataStore

    val folderUri: Flow<String?> = store.data.map { it[FOLDER_TREE_URI] }.catch { emit(null) }

    suspend fun grantFolder(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!isSupportedTreeUri(uri)) return@withContext false
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            store.edit { values -> values[FOLDER_TREE_URI] = uri.toString() }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun scan(sourceId: SourceId): ExternalDownloadImportResult = withContext(Dispatchers.IO) {
        val uri = currentUsableTreeUri() ?: return@withContext ExternalDownloadImportResult(
            discoveredVideoFiles = 0,
            imported = 0,
            alreadyTracked = 0,
            unmatched = 0,
            conflicts = 0,
            accessRequired = true,
            scanStatus = ExternalDownloadScanStatus.PERMISSION_ERROR,
        )
        val inventory = listVideoFiles(uri)
        val movies = libraryDao.getAvailableMoviesForDownloadImport(sourceId.value)
        val episodes = libraryDao.getAvailableEpisodesForDownloadImport(sourceId.value)
        var imported = 0
        var tracked = 0
        var unmatched = 0
        var ambiguous = 0
        var conflicts = 0
        val unresolved = mutableListOf<ExternalDownloadImportIssue>()

        for (file in inventory.files) {
            val candidates = ExternalDownloadImportMatcher.candidates(file, movies, episodes)
            if (candidates.isEmpty()) {
                unmatched += 1
                unresolved += ExternalDownloadImportIssue(
                    file = file,
                    candidates = emptyList(),
                    kind = ExternalDownloadImportIssueKind.UNMATCHED,
                )
                continue
            }
            if (candidates.size > 1) {
                ambiguous += 1
                unresolved += ExternalDownloadImportIssue(
                    file = file,
                    candidates = candidates,
                    kind = ExternalDownloadImportIssueKind.AMBIGUOUS,
                )
                continue
            }
            when (importMatchedFile(sourceId, file, candidates.single())) {
                ImportMatchedResult.IMPORTED -> imported += 1
                ImportMatchedResult.TRACKED -> tracked += 1
                ImportMatchedResult.CONFLICT -> {
                    conflicts += 1
                    unresolved += ExternalDownloadImportIssue(
                        file = file,
                        candidates = candidates,
                        kind = ExternalDownloadImportIssueKind.CONFLICT,
                    )
                }
            }
        }

        ExternalDownloadImportResult(
            discoveredVideoFiles = inventory.files.size,
            imported = imported,
            alreadyTracked = tracked,
            unmatched = unmatched,
            conflicts = conflicts,
            ambiguous = ambiguous,
            scanStatus = inventory.status,
            unresolvedFiles = unresolved,
        )
    }

    suspend fun importSelection(
        sourceId: SourceId,
        file: ExternalDownloadImportFile,
        selected: ExternalDownloadImportMatch,
    ): Boolean = withContext(Dispatchers.IO) {
        val movies = libraryDao.getAvailableMoviesForDownloadImport(sourceId.value)
        val episodes = libraryDao.getAvailableEpisodesForDownloadImport(sourceId.value)
        val currentCandidates = ExternalDownloadImportMatcher.candidates(file, movies, episodes)
        if (selected !in currentCandidates) return@withContext false
        importMatchedFile(sourceId, file, selected) != ImportMatchedResult.CONFLICT
    }

    private suspend fun importMatchedFile(
        sourceId: SourceId,
        file: ExternalDownloadImportFile,
        match: ExternalDownloadImportMatch,
    ): ImportMatchedResult {
        val stableDownloadId = DownloadIdentity.stableId(sourceId, match.mediaKind, match.contentId)
        val existing = downloadDao.getForContent(sourceId.value, match.mediaKind.name, match.contentId)
        if (DownloadFinalizationCodec.hasFinalization(existing?.integrityMetadata)) {
            return ImportMatchedResult.CONFLICT
        }
        val managedIdentity = existing?.downloadId
            ?.takeIf(String::isNotBlank)
            ?.let(::DownloadId)
            ?: stableDownloadId
        val appManagedFile = DownloadFilePolicy.hasManagedIdentitySuffix(file.displayName, managedIdentity)
        val existingStatus = existing?.state?.let { value ->
            runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.UNKNOWN)
        }

        if (existingStatus == DownloadStatus.COMPLETED && appManagedFile) {
            val integrity = DownloadIntegrityMetadata.decode(existing.integrityMetadata)
            if (integrity?.origin == DownloadOrigin.IMPORTED_EXTERNAL) {
                downloadDao.upsert(
                    existing.copy(
                        title = match.title,
                        bytesDownloaded = file.sizeBytes,
                        totalBytes = file.sizeBytes,
                        localReference = file.uri,
                        integrityMetadata = DownloadIntegrityMetadata.encode(
                            verifiedBytes = file.sizeBytes,
                            sha256 = null,
                            origin = DownloadOrigin.APP_MANAGED,
                        ),
                        failureReason = null,
                        updatedAt = clock(),
                    ),
                )
            }
        }
        if (existingStatus in setOf(
                DownloadStatus.WAITING_FOR_WIFI,
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED,
                DownloadStatus.COMPLETED,
            )
        ) {
            return ImportMatchedResult.TRACKED
        }
        if (existingStatus == DownloadStatus.UNKNOWN) return ImportMatchedResult.CONFLICT

        val now = clock()
        val downloadId = existing?.downloadId ?: stableDownloadId.value
        downloadDao.upsert(
            DownloadEntity(
                downloadId = downloadId,
                sourceId = sourceId.value,
                mediaKind = match.mediaKind.name,
                contentId = match.contentId,
                title = match.title,
                streamIdentity = match.contentId,
                state = DownloadStatus.COMPLETED.name,
                bytesDownloaded = file.sizeBytes,
                totalBytes = file.sizeBytes,
                localReference = file.uri,
                integrityMetadata = DownloadIntegrityMetadata.encode(
                    verifiedBytes = file.sizeBytes,
                    sha256 = null,
                    origin = if (appManagedFile) DownloadOrigin.APP_MANAGED else DownloadOrigin.IMPORTED_EXTERNAL,
                ),
                failureReason = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        return ImportMatchedResult.IMPORTED
    }

    private suspend fun currentUsableTreeUri(): Uri? {
        val value = runCatching { store.data.first()[FOLDER_TREE_URI] }.getOrNull() ?: return null
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
        if (!isSupportedTreeUri(uri)) return null
        val persisted = resolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
        return uri.takeIf { persisted }
    }

    private fun isSupportedTreeUri(uri: Uri): Boolean {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
        return runCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull()
            ?.isNotBlank() == true
    }

    private fun listVideoFiles(treeUri: Uri): ExternalDownloadScanResult {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return ExternalDownloadScanResult(emptyList(), ExternalDownloadScanStatus.ERROR)
        val results = mutableListOf<ExternalDownloadImportFile>()
        val queue = ArrayDeque<DirectoryNode>()
        queue.add(DirectoryNode(rootId, emptyList(), 0))
        var visited = 0
        var status = ExternalDownloadScanStatus.COMPLETE

        while (queue.isNotEmpty()) {
            if (visited >= MAX_DOCUMENTS) {
                if (status == ExternalDownloadScanStatus.COMPLETE) status = ExternalDownloadScanStatus.TRUNCATED
                break
            }
            val node = queue.removeFirst()
            if (node.depth > MAX_DEPTH) {
                if (status == ExternalDownloadScanStatus.COMPLETE) status = ExternalDownloadScanStatus.TRUNCATED
                continue
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, node.documentId)
            val children = try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    buildList {
                        while (cursor.moveToNext()) {
                            if (visited >= MAX_DOCUMENTS) {
                                if (status == ExternalDownloadScanStatus.COMPLETE) {
                                    status = ExternalDownloadScanStatus.TRUNCATED
                                }
                                break
                            }
                            visited += 1
                            add(
                                DocumentRow(
                                    cursor.getString(idIndex),
                                    cursor.getString(nameIndex).orEmpty(),
                                    cursor.getString(mimeIndex).orEmpty(),
                                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                                ),
                            )
                        }
                    }
                } ?: emptyList()
            } catch (_: SecurityException) {
                status = ExternalDownloadScanStatus.PERMISSION_ERROR
                emptyList()
            } catch (_: Exception) {
                if (status != ExternalDownloadScanStatus.PERMISSION_ERROR) {
                    status = ExternalDownloadScanStatus.ERROR
                }
                emptyList()
            }

            children.forEach { child ->
                if (child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (child.displayName.isNotBlank()) {
                        if (node.depth >= MAX_DEPTH) {
                            if (status == ExternalDownloadScanStatus.COMPLETE) {
                                status = ExternalDownloadScanStatus.TRUNCATED
                            }
                        } else {
                            queue.add(
                                DirectoryNode(
                                    child.documentId,
                                    node.relativeSegments + child.displayName,
                                    node.depth + 1,
                                ),
                            )
                        }
                    }
                } else if (isSupportedVideo(child.displayName, child.mimeType, child.sizeBytes)) {
                    results += ExternalDownloadImportFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId).toString(),
                        displayName = child.displayName,
                        sizeBytes = requireNotNull(child.sizeBytes),
                        relativePathSegments = node.relativeSegments,
                    )
                }
            }
        }
        return ExternalDownloadScanResult(results, status)
    }

    private fun isSupportedVideo(displayName: String, mimeType: String, sizeBytes: Long?): Boolean {
        if (sizeBytes == null || sizeBytes <= 0L) return false
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return mimeType.startsWith("video/") || extension in SUPPORTED_VIDEO_EXTENSIONS
    }

    private enum class ImportMatchedResult {
        IMPORTED,
        TRACKED,
        CONFLICT,
    }

    private data class ExternalDownloadScanResult(
        val files: List<ExternalDownloadImportFile>,
        val status: ExternalDownloadScanStatus,
    )

    private data class DirectoryNode(
        val documentId: String,
        val relativeSegments: List<String>,
        val depth: Int,
    )

    private data class DocumentRow(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
    )

    private companion object {
        val FOLDER_TREE_URI = stringPreferencesKey("folder_tree_uri")
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val MAX_DEPTH = 8
        const val MAX_DOCUMENTS = 1500
        val SUPPORTED_VIDEO_EXTENSIONS =
            setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mpg", "mpeg")
    }
}
