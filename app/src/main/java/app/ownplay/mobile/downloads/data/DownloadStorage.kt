package app.ownplay.mobile.downloads.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.ownplay.mobile.downloads.domain.DownloadId
import app.ownplay.mobile.downloads.domain.DownloadDestinationPolicy
import app.ownplay.mobile.downloads.domain.DownloadPendingNamePolicy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

internal data class PendingDownloadOutput(
    val outputStream: OutputStream,
    internal val token: PendingDownloadToken,
)

internal sealed interface PendingDownloadToken {
    data class MediaStoreEntry(
        val uri: Uri,
        val finalDisplayName: String,
        val pendingMissing: Boolean = false,
    ) : PendingDownloadToken

    data class PrivateFile(
        val temporaryFile: File,
        val finalFile: File,
        val backupFile: File? = null,
        var published: Boolean = false,
        val replaceExisting: Boolean = true,
        val pendingMissing: Boolean = false,
    ) : PendingDownloadToken
}

internal data class RecoveredPublishedDownloadOutput(
    val localReference: String,
    val inputStream: InputStream,
    val verifiedSize: Long,
)

internal interface DownloadStorage {
    suspend fun openPending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
    ): PendingDownloadOutput?

    suspend fun openPendingAtDestination(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
        destinationRelativePath: String,
    ): PendingDownloadOutput? = openPending(downloadId, media)

    suspend fun verifiedSize(pending: PendingDownloadOutput): Long?

    suspend fun publish(pending: PendingDownloadOutput): String?

    suspend fun discard(pending: PendingDownloadOutput)

    suspend fun discardPending(downloadId: DownloadId): Boolean

    suspend fun removePublished(localReference: String): Boolean

    fun describePending(pending: PendingDownloadOutput): String =
        throw UnsupportedOperationException("Storage does not support durable output descriptors")

    fun recoverOwnedPending(downloadId: DownloadId, descriptor: String): PendingDownloadOutput? = null

    fun openPendingInput(pending: PendingDownloadOutput): InputStream? = null

    fun pendingOutputMissing(pending: PendingDownloadOutput): Boolean = false

    fun recoverOwnedPublished(
        downloadId: DownloadId,
        descriptor: String,
    ): RecoveredPublishedDownloadOutput? = null

    suspend fun finalizePublished(pending: PendingDownloadOutput, localReference: String): Boolean = true

    suspend fun finalizeRecoveredPublished(
        downloadId: DownloadId,
        descriptor: String,
        localReference: String,
    ): Boolean = true

    suspend fun rollbackRecoveredPublished(
        downloadId: DownloadId,
        descriptor: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean = false
}

internal class AndroidDownloadStorage(
    context: Context,
    private val destinationAssignments: DownloadDestinationAssignmentStore,
    private val privateFileMover: (File, File, Boolean) -> Boolean = ::movePrivateFileAtomically,
) : DownloadStorage {
    internal constructor(context: Context) : this(
        context,
        DataStoreDownloadDestinationAssignmentStore(context.applicationContext),
    )

    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val privateRoot = File(applicationContext.filesDir, "ownplay-downloads")

    override suspend fun openPending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
    ): PendingDownloadOutput? {
        val destination = destinationAssignments.get(downloadId) ?: return null
        return openPendingAtDestination(downloadId, media, destination)
    }

    override suspend fun openPendingAtDestination(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
        destinationRelativePath: String,
    ): PendingDownloadOutput? {
        val destination = DownloadDestinationPolicy.normalize(destinationRelativePath) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openMediaStorePending(downloadId, media, destination)
        } else {
            openPrivatePending(downloadId, media, destination)
        }
    }

    /** Contains app-local storage identity only, never a source URL or provider credentials. */
    override fun describePending(pending: PendingDownloadOutput): String = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> JSONObject()
            .put("kind", "media-store").put("reference", token.uri.toString())
            .put("finalName", token.finalDisplayName).toString()
        is PendingDownloadToken.PrivateFile -> JSONObject().apply {
            put("kind", "private-file")
            put("reference", token.temporaryFile.absolutePath)
            put("finalPath", token.finalFile.absolutePath)
            token.backupFile?.let { put("backupPath", it.absolutePath) }
            put("replaceExisting", token.replaceExisting)
        }.toString()
    }

    /** Reopens read-only, and only after proving exact pending ownership and destination bounds. */
    override fun recoverOwnedPending(downloadId: DownloadId, descriptor: String): PendingDownloadOutput? = runCatching {
        val data = JSONObject(descriptor)
        val sink = object : OutputStream() { override fun write(value: Int) = error("Recovery is read-only") }
        when (data.getString("kind")) {
            "media-store" -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
                val uri = Uri.parse(data.getString("reference"))
                if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY ||
                    uri.query != null || uri.fragment != null ||
                    uri.pathSegments.size != 3 || uri.pathSegments[0] != "external" ||
                    uri.pathSegments[1] != "downloads" || uri.lastPathSegment?.toLongOrNull() == null
                ) return@runCatching null
                val finalName = data.getString("finalName")
                if (finalName.isBlank() || finalName.contains('/') || finalName.contains('\\')) return@runCatching null
                val cursor = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    null, null, null,
                ) ?: return@runCatching null
                val pendingEntry = cursor.use { result ->
                    if (!result.moveToFirst()) return@use PendingDownloadToken.MediaStoreEntry(uri, finalName, pendingMissing = true)
                    val owned =
                        result.getString(0) == DownloadPendingNamePolicy.stagingDisplayName(downloadId) &&
                        result.getInt(1) == 1 && result.getString(2) == applicationContext.packageName
                    if (!owned) return@use null
                    PendingDownloadToken.MediaStoreEntry(uri, finalName)
                } ?: return@runCatching null
                PendingDownloadOutput(sink, pendingEntry)
            }
            "private-file" -> {
                val pendingRoot = File(privateRoot, "pending").canonicalFile
                val token = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
                val expected = File(pendingRoot, "$token.part").canonicalFile
                val temporary = File(data.getString("reference")).canonicalFile
                val completedRoot = File(privateRoot, "completed").canonicalFile
                val finalFile = File(data.getString("finalPath")).canonicalFile
                val replaceExisting = data.optBoolean("replaceExisting", false)
                val expectedBackup = if (replaceExisting) {
                    File(File(privateRoot, "rollback"), "$token.backup").canonicalFile
                } else null
                val backupFile = data.optString("backupPath").takeIf(String::isNotBlank)
                    ?.takeIf { it != "null" }
                    ?.let { File(it).canonicalFile }
                if (temporary != expected || temporary.parentFile != pendingRoot ||
                    (temporary.exists() && !temporary.isFile) ||
                    !finalFile.path.startsWith(completedRoot.path + File.separator) ||
                    (replaceExisting && (backupFile != expectedBackup || backupFile?.parentFile != File(privateRoot, "rollback").canonicalFile)) ||
                    (!replaceExisting && (backupFile != null || finalFile.exists())) ||
                    (replaceExisting && finalFile.exists() && backupFile?.exists() == true && !temporary.isFile)
                ) return@runCatching null
                PendingDownloadOutput(
                    sink,
                    PendingDownloadToken.PrivateFile(
                        temporary,
                        finalFile,
                        backupFile,
                        replaceExisting = replaceExisting,
                        pendingMissing = !temporary.exists(),
                    ),
                )
            }
            else -> null
        }
    }.getOrNull()

    override fun openPendingInput(pending: PendingDownloadOutput): InputStream? = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> runCatching { resolver.openInputStream(token.uri) }.getOrNull()
        is PendingDownloadToken.PrivateFile -> runCatching { token.temporaryFile.inputStream() }.getOrNull()
    }

    override fun pendingOutputMissing(pending: PendingDownloadOutput): Boolean = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> token.pendingMissing
        is PendingDownloadToken.PrivateFile -> token.pendingMissing
    }

    override fun recoverOwnedPublished(
        downloadId: DownloadId,
        descriptor: String,
    ): RecoveredPublishedDownloadOutput? = runCatching {
        val data = JSONObject(descriptor)
        when (data.getString("kind")) {
            "media-store" -> {
                val uri = ownedMediaStoreUri(data.getString("reference")) ?: return@runCatching null
                val finalName = data.getString("finalName")
                if (!isOwnedMediaStore(uri, finalName, pending = false)) return@runCatching null
                val size = mediaStoreSize(uri) ?: return@runCatching null
                val input = resolver.openInputStream(uri) ?: return@runCatching null
                RecoveredPublishedDownloadOutput(uri.toString(), input, size)
            }
            "private-file" -> {
                val identity = ownedPrivateIdentity(downloadId, descriptor) ?: return@runCatching null
                if (!identity.finalFile.isFile) return@runCatching null
                val size = identity.finalFile.length().takeIf { it >= 0L } ?: return@runCatching null
                RecoveredPublishedDownloadOutput(
                    Uri.fromFile(identity.finalFile).toString(),
                    identity.finalFile.inputStream(),
                    size,
                )
            }
            else -> null
        }
    }.getOrNull()

    override suspend fun verifiedSize(pending: PendingDownloadOutput): Long? =
        when (val token = pending.token) {
            is PendingDownloadToken.MediaStoreEntry -> mediaStoreSize(token.uri)
            is PendingDownloadToken.PrivateFile ->
                token.temporaryFile.takeIf(File::isFile)?.length()?.takeIf { it >= 0L }
        }

    override suspend fun publish(pending: PendingDownloadOutput): String? =
        when (val token = pending.token) {
            is PendingDownloadToken.MediaStoreEntry -> publishMediaStore(token)
            is PendingDownloadToken.PrivateFile -> publishPrivate(token)
        }

    override suspend fun discard(pending: PendingDownloadOutput) {
        when (val token = pending.token) {
            is PendingDownloadToken.MediaStoreEntry -> {
                if (!token.pendingMissing && !runCatching { resolver.delete(token.uri, null, null) >= 0 }.getOrDefault(false)) {
                    throw IOException("Owned MediaStore output could not be discarded")
                }
            }
            is PendingDownloadToken.PrivateFile -> {
                if (token.temporaryFile.exists() && !token.temporaryFile.delete()) {
                    throw IOException("Owned private staging output could not be discarded")
                }
                if (!rollbackPrivate(token)) throw IOException("Private output rollback could not be completed")
            }
        }
    }

    override suspend fun finalizePublished(
        pending: PendingDownloadOutput,
        localReference: String,
    ): Boolean = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> localReference == token.uri.toString()
        is PendingDownloadToken.PrivateFile ->
            localReference == Uri.fromFile(token.finalFile).toString() && token.finalFile.isFile &&
                finalizePrivateBackup(token.backupFile)
    }

    override suspend fun finalizeRecoveredPublished(
        downloadId: DownloadId,
        descriptor: String,
        localReference: String,
    ): Boolean = runCatching {
        val data = JSONObject(descriptor)
        when (data.getString("kind")) {
            "media-store" -> {
                val uri = ownedMediaStoreUri(data.getString("reference")) ?: return@runCatching false
                localReference == uri.toString() && isOwnedMediaStore(uri, data.getString("finalName"), pending = false)
            }
            "private-file" -> {
                val identity = ownedPrivateIdentity(downloadId, descriptor) ?: return@runCatching false
                localReference == Uri.fromFile(identity.finalFile).toString() &&
                    identity.finalFile.isFile && finalizePrivateBackup(identity.backupFile)
            }
            else -> false
        }
    }.getOrDefault(false)

    override suspend fun rollbackRecoveredPublished(
        downloadId: DownloadId,
        descriptor: String,
        expectedBytes: Long,
        expectedSha256: String,
    ): Boolean = runCatching {
        val expectedDigest = expectedSha256.lowercase()
        val data = JSONObject(descriptor)
        when (data.getString("kind")) {
            "media-store" -> {
                val uri = ownedMediaStoreUri(data.getString("reference")) ?: return@runCatching false
                if (!isOwnedMediaStore(uri, data.getString("finalName"), pending = false)) return@runCatching false
                // This exact row was created from the persisted app-owned descriptor; on cancellation
                // or failed verification it is safe to remove even when its bytes do not match.
                resolver.delete(uri, null, null) >= 0
            }
            "private-file" -> {
                val identity = ownedPrivateIdentity(downloadId, descriptor) ?: return@runCatching false
                if (!identity.finalFile.isFile) {
                    return@runCatching identity.backupFile?.let { restorePrivateBackup(identity.finalFile, it) } ?: true
                }
                if (identity.backupFile?.isFile == true) {
                    if (!identity.finalFile.delete()) return@runCatching false
                    restorePrivateBackup(identity.finalFile, identity.backupFile)
                } else {
                    val actual = identity.finalFile.inputStream().use(::sha256AndSize)
                    if (actual.first != expectedBytes || actual.second != expectedDigest) return@runCatching false
                    identity.finalFile.delete()
                }
            }
            else -> false
        }
    }.getOrDefault(false)

    override suspend fun discardPending(downloadId: DownloadId): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            discardPendingMediaStore(DownloadPendingNamePolicy.stagingDisplayName(downloadId))
        } else {
            discardPrivatePending(downloadId)
        }

    override suspend fun removePublished(localReference: String): Boolean {
        val uri = runCatching { Uri.parse(localReference) }.getOrNull() ?: return false
        return when (uri.scheme) {
            "content" -> when (uri.authority) {
                MediaStore.AUTHORITY -> removeOwnedMediaStoreReference(uri)
                EXTERNAL_STORAGE_AUTHORITY -> removeExternalDocumentReference(uri)
                else -> false
            }
            "file" -> removePrivateReference(uri)
            else -> false
        }
    }

    private fun removeExternalDocumentReference(uri: Uri): Boolean {
        if (!DocumentsContract.isDocumentUri(applicationContext, uri)) return false
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return false
        val hasWriteAccess = resolver.persistedUriPermissions.any { permission ->
            if (!permission.isWritePermission || permission.uri.authority != uri.authority) return@any false
            val treeDocumentId = runCatching {
                DocumentsContract.getTreeDocumentId(permission.uri)
            }.getOrNull() ?: return@any false
            documentId == treeDocumentId || documentId.startsWith("$treeDocumentId/")
        }
        if (!hasWriteAccess) return false
        return runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
    }

    private fun openMediaStorePending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
        destinationRelativePath: String,
    ): PendingDownloadOutput? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relativePath = (
            destinationRelativePath.trimEnd('/') + "/" +
                media.relativeDirectories.joinToString("/")
        ).trimEnd('/') + "/"
        val stagingDisplayName = DownloadPendingNamePolicy.stagingDisplayName(downloadId)
        if (!discardExistingPendingMediaStore(relativePath, stagingDisplayName)) return null

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, stagingDisplayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(media.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val output = resolver.openOutputStream(uri, "w")
        if (output == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return PendingDownloadOutput(
            outputStream = output,
            token = PendingDownloadToken.MediaStoreEntry(
                uri = uri,
                finalDisplayName = media.displayName,
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun discardExistingPendingMediaStore(
        relativePath: String,
        stagingDisplayName: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val ids = runCatching {
            resolver.query(
                MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 1",
                arrayOf(stagingDisplayName, relativePath, applicationContext.packageName),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(idIndex))
                }
            } ?: emptyList()
        }.getOrElse { return false }

        return ids.all { id ->
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            runCatching { resolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun discardPendingMediaStore(stagingDisplayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val ids = runCatching {
            resolver.query(
                MediaStore.setIncludePending(MediaStore.Downloads.EXTERNAL_CONTENT_URI),
                arrayOf(BaseColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "${MediaStore.MediaColumns.IS_PENDING} = 1",
                arrayOf(stagingDisplayName, applicationContext.packageName),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(BaseColumns._ID)
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(idIndex))
                }
            } ?: emptyList()
        }.getOrElse { return false }

        return ids.all { id ->
            val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            runCatching { resolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
        }
    }

    private fun discardPrivatePending(downloadId: DownloadId): Boolean {
        val token = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
        val candidate = File(File(privateRoot, "pending"), "$token.part")
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        val pendingRoot = runCatching { File(privateRoot, "pending").canonicalFile }.getOrNull() ?: return false
        if (canonicalCandidate.parentFile != pendingRoot) return false
        return !canonicalCandidate.exists() || canonicalCandidate.delete()
    }

    private fun openPrivatePending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
        destinationRelativePath: String,
    ): PendingDownloadOutput? {
        val pendingDirectory = File(privateRoot, "pending")
        val destinationSegments = destinationRelativePath
            .trim('/')
            .split('/')
            .drop(1)
            .filter(String::isNotBlank)
        val destinationRoot = destinationSegments.fold(File(privateRoot, "completed")) { parent, segment ->
            File(parent, segment)
        }
        val finalDirectory = media.relativeDirectories.fold(destinationRoot) { parent, segment ->
            File(parent, segment)
        }
        if (!pendingDirectory.mkdirs() && !pendingDirectory.isDirectory) return null
        if (!finalDirectory.mkdirs() && !finalDirectory.isDirectory) return null
        val rollbackDirectory = File(privateRoot, "rollback")
        if (!rollbackDirectory.mkdirs() && !rollbackDirectory.isDirectory) return null
        val token = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
        val temporary = File(pendingDirectory, "$token.part")
        runCatching { temporary.delete() }
        val finalFile = File(finalDirectory, media.displayName)
        val replaceExisting = !downloadId.value.startsWith("recording-")
        val backupFile = File(rollbackDirectory, "$token.backup")
        if (replaceExisting && backupFile.exists()) return null
        val output = runCatching { FileOutputStream(temporary, false) }.getOrNull() ?: return null
        return PendingDownloadOutput(
            outputStream = output,
            token = PendingDownloadToken.PrivateFile(
                temporaryFile = temporary,
                finalFile = finalFile,
                backupFile = backupFile.takeIf { replaceExisting },
                replaceExisting = replaceExisting,
            ),
        )
    }

    private fun mediaStoreSize(uri: Uri): Long? {
        val statSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize }
        }.getOrNull()?.takeIf { it >= 0L }
        if (statSize != null) return statSize
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0 || cursor.isNull(index)) null else cursor.getLong(index)
            }
        }.getOrNull()
    }

    private fun publishMediaStore(token: PendingDownloadToken.MediaStoreEntry): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val updated = resolver.update(
            token.uri,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, token.finalDisplayName)
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            },
            null,
            null,
        )
        return token.uri.toString().takeIf { updated == 1 }
    }

    private fun publishPrivate(token: PendingDownloadToken.PrivateFile): String? {
        if (!token.temporaryFile.isFile) return null
        val backup = token.backupFile
        if (backup != null && token.finalFile.exists() && !backup.exists()) {
            if (!movePrivateFile(token.finalFile, backup, replace = false)) return null
        } else if (!token.replaceExisting && token.finalFile.exists()) {
            return null
        } else if (backup != null && backup.exists() && token.finalFile.exists()) {
            return null
        }
        if (!movePrivateFile(token.temporaryFile, token.finalFile, replace = false)) {
            if (backup?.exists() == true && !token.finalFile.exists()) restorePrivateBackup(token.finalFile, backup)
            return null
        }
        token.published = true
        return Uri.fromFile(token.finalFile).toString()
    }

    private fun rollbackPrivate(token: PendingDownloadToken.PrivateFile): Boolean {
        val backup = token.backupFile
        if (backup?.isFile == true) {
            if (token.finalFile.exists() && !token.finalFile.delete() && token.finalFile.exists()) return false
            return restorePrivateBackup(token.finalFile, backup)
        } else if (token.published) {
            return !token.finalFile.exists() || token.finalFile.delete()
        }
        return true
    }

    private fun finalizePrivateBackup(backup: File?): Boolean {
        if (backup == null || !backup.exists()) return true
        val rollbackRoot = runCatching { File(privateRoot, "rollback").canonicalFile }.getOrNull() ?: return false
        val canonical = runCatching { backup.canonicalFile }.getOrNull() ?: return false
        if (canonical.parentFile != rollbackRoot || !canonical.name.endsWith(".backup")) return false
        return runCatching { canonical.delete() }.getOrDefault(false) || !canonical.exists()
    }

    private fun restorePrivateBackup(finalFile: File, backupFile: File): Boolean {
        if (!backupFile.isFile) return !backupFile.exists()
        if (finalFile.exists()) return false
        return movePrivateFile(backupFile, finalFile, replace = false)
    }

    private fun movePrivateFile(source: File, target: File, replace: Boolean): Boolean =
        runCatching { privateFileMover(source, target, replace) }.getOrDefault(false)

    private fun ownedMediaStoreUri(raw: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY || uri.query != null || uri.fragment != null ||
            uri.pathSegments.size != 3 || uri.pathSegments[0] != "external" ||
            uri.pathSegments[1] != "downloads" || uri.lastPathSegment?.toLongOrNull() == null
        ) return null
        return uri
    }

    private fun isOwnedMediaStore(uri: Uri, displayName: String, pending: Boolean): Boolean {
        if (displayName.isBlank() || displayName.contains('/') || displayName.contains('\\')) return false
        return resolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == displayName &&
                cursor.getInt(1) == (if (pending) 1 else 0) && cursor.getString(2) == applicationContext.packageName
        } == true
    }

    private fun removeOwnedMediaStoreReference(uri: Uri): Boolean {
        val exactUri = ownedMediaStoreUri(uri.toString()) ?: return false
        val cursor = try {
            resolver.query(
                exactUri,
                arrayOf(
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.IS_PENDING,
                    MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
                ),
                null,
                null,
                null,
            )
        } catch (_: Exception) {
            null
        } ?: return false
        val row = cursor.use { result ->
            if (!result.moveToFirst()) return@use null
            Triple(result.getString(0), result.getInt(1), result.getString(2))
        } ?: return true
        if (row.first.isNullOrBlank() || row.second != 0 || row.third != applicationContext.packageName) return false
        return runCatching { resolver.delete(exactUri, null, null) >= 0 }.getOrDefault(false)
    }

    private data class OwnedPrivateOutput(
        val temporaryFile: File,
        val finalFile: File,
        val backupFile: File?,
    )

    private fun ownedPrivateIdentity(downloadId: DownloadId, descriptor: String): OwnedPrivateOutput? = runCatching {
        val data = JSONObject(descriptor)
        if (data.getString("kind") != "private-file") return@runCatching null
        val idToken = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
        val expectedPendingRoot = File(privateRoot, "pending").canonicalFile
        val expectedTemporary = File(expectedPendingRoot, "$idToken.part").canonicalFile
        val temporary = File(data.getString("reference")).canonicalFile
        val completedRoot = File(privateRoot, "completed").canonicalFile
        val finalFile = File(data.getString("finalPath")).canonicalFile
        val replaceExisting = data.optBoolean("replaceExisting", false)
        val backupPath = data.optString("backupPath").takeIf { it.isNotBlank() && it != "null" }
        val rollbackRoot = File(privateRoot, "rollback").canonicalFile
        val expectedBackup = if (replaceExisting) File(rollbackRoot, "$idToken.backup").canonicalFile else null
        val backup = backupPath?.let { File(it).canonicalFile }
        if (temporary != expectedTemporary || temporary.parentFile != expectedPendingRoot ||
            !finalFile.path.startsWith(completedRoot.path + File.separator) ||
            finalFile.name.isBlank() || finalFile.name == "." || finalFile.name == ".." ||
            (replaceExisting && (backup != expectedBackup || backup?.parentFile != rollbackRoot)) ||
            (!replaceExisting && backup != null)
        ) return@runCatching null
        OwnedPrivateOutput(temporary, finalFile, backup)
    }.getOrNull()

    private fun sha256AndSize(input: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            size += count
            digest.update(buffer, 0, count)
        }
        return size to digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun removePrivateReference(uri: Uri): Boolean {
        val path = uri.path ?: return false
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val completedRoot = runCatching { File(privateRoot, "completed").canonicalFile }.getOrNull() ?: return false
        val allowedPrefix = completedRoot.path + File.separator
        if (!candidate.path.startsWith(allowedPrefix)) return false
        return !candidate.exists() || candidate.delete()
    }

    private fun mimeType(extension: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}

private fun movePrivateFileAtomically(source: File, target: File, replace: Boolean): Boolean =
    runCatching {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(source.toPath(), target.toPath(), *options)
        true
    }.getOrDefault(false)
