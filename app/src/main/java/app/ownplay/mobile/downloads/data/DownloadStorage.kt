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
import java.io.OutputStream
import java.io.InputStream
import org.json.JSONObject

internal data class PendingDownloadOutput(
    val outputStream: OutputStream,
    internal val token: PendingDownloadToken,
)

internal sealed interface PendingDownloadToken {
    data class MediaStoreEntry(
        val uri: Uri,
        val finalDisplayName: String,
    ) : PendingDownloadToken

    data class PrivateFile(
        val temporaryFile: File,
        val finalFile: File,
        var published: Boolean = false,
        val replaceExisting: Boolean = true,
    ) : PendingDownloadToken
}

internal interface DownloadStorage {
    suspend fun openPending(
        downloadId: DownloadId,
        media: ResolvedDownloadMedia,
    ): PendingDownloadOutput?

    suspend fun verifiedSize(pending: PendingDownloadOutput): Long?

    suspend fun publish(pending: PendingDownloadOutput): String?

    suspend fun discard(pending: PendingDownloadOutput)

    suspend fun discardPending(downloadId: DownloadId): Boolean

    suspend fun removePublished(localReference: String): Boolean
}

internal class AndroidDownloadStorage(
    context: Context,
    private val destinationAssignments: DownloadDestinationAssignmentStore,
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

    internal suspend fun openPendingAtDestination(
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

    /** Persisted only with recording metadata; contains local storage identity, never a source URL. */
    internal fun describePending(pending: PendingDownloadOutput): String = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> JSONObject()
            .put("kind", "media-store").put("reference", token.uri.toString())
            .put("finalName", token.finalDisplayName).toString()
        is PendingDownloadToken.PrivateFile -> JSONObject()
            .put("kind", "private-file").put("reference", token.temporaryFile.absolutePath)
            .put("finalPath", token.finalFile.absolutePath).toString()
    }

    /** Reopens read-only, and only after proving exact pending ownership and destination bounds. */
    internal fun recoverOwnedPending(downloadId: DownloadId, descriptor: String): PendingDownloadOutput? = runCatching {
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
                if (finalName.contains('/') || finalName.contains('\\') || !finalName.endsWith(".ts")) return@runCatching null
                val owned = resolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                    null, null, null,
                )?.use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getString(0) == DownloadPendingNamePolicy.stagingDisplayName(downloadId) &&
                        cursor.getInt(1) == 1 && cursor.getString(2) == applicationContext.packageName
                } == true
                if (!owned) return@runCatching null
                PendingDownloadOutput(sink, PendingDownloadToken.MediaStoreEntry(uri, finalName))
            }
            "private-file" -> {
                val pendingRoot = File(privateRoot, "pending").canonicalFile
                val token = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
                val expected = File(pendingRoot, "$token.part").canonicalFile
                val temporary = File(data.getString("reference")).canonicalFile
                val completedRoot = File(privateRoot, "completed").canonicalFile
                val finalFile = File(data.getString("finalPath")).canonicalFile
                if (temporary != expected || temporary.parentFile != pendingRoot || !temporary.isFile ||
                    !finalFile.path.startsWith(completedRoot.path + File.separator) ||
                    !finalFile.name.endsWith(".ts") || finalFile.exists()
                ) return@runCatching null
                PendingDownloadOutput(sink, PendingDownloadToken.PrivateFile(temporary, finalFile, replaceExisting = false))
            }
            else -> null
        }
    }.getOrNull()

    internal fun openPendingInput(pending: PendingDownloadOutput): InputStream? = when (val token = pending.token) {
        is PendingDownloadToken.MediaStoreEntry -> runCatching { resolver.openInputStream(token.uri) }.getOrNull()
        is PendingDownloadToken.PrivateFile -> runCatching { token.temporaryFile.inputStream() }.getOrNull()
    }

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
            is PendingDownloadToken.MediaStoreEntry -> runCatching {
                resolver.delete(token.uri, null, null)
            }
            is PendingDownloadToken.PrivateFile -> {
                runCatching { token.temporaryFile.delete() }
                if (token.published) runCatching { token.finalFile.delete() }
            }
        }
    }

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
                MediaStore.AUTHORITY ->
                    runCatching { resolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
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
        val token = downloadId.value.filter(Char::isLetterOrDigit).takeLast(32).ifBlank { "download" }
        val temporary = File(pendingDirectory, "$token.part")
        runCatching { temporary.delete() }
        val finalFile = File(finalDirectory, media.displayName)
        val output = runCatching { FileOutputStream(temporary, false) }.getOrNull() ?: return null
        return PendingDownloadOutput(
            outputStream = output,
            token = PendingDownloadToken.PrivateFile(temporary, finalFile, replaceExisting = !downloadId.value.startsWith("recording-")),
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
        if (token.finalFile.exists() && (!token.replaceExisting || !token.finalFile.delete())) return null
        if (!token.temporaryFile.renameTo(token.finalFile)) return null
        token.published = true
        return Uri.fromFile(token.finalFile).toString()
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
