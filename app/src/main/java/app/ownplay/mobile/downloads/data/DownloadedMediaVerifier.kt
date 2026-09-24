package app.ownplay.mobile.downloads.data

import app.ownplay.mobile.downloads.domain.DownloadIntegrityPolicy
import app.ownplay.mobile.downloads.domain.DownloadItem
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun interface DownloadedMediaVerifier {
    suspend fun verify(item: DownloadItem): Boolean
}

internal fun interface DownloadedMediaAvailabilityProbe {
    suspend fun isAvailable(item: DownloadItem): Boolean
}

/** Streams a bounded buffer; never loads the downloaded media into memory. */
internal suspend fun verifyDownloadedBytes(
    input: InputStream,
    expectedBytes: Long,
    expectedSha256: String?,
): Boolean {
    if (expectedBytes <= 0L) return false
    val normalizedHash = expectedSha256?.let(DownloadIntegrityPolicy::normalizeSha256)
    if (expectedSha256 != null && normalizedHash == null) return false
    val digest = normalizedHash?.let { MessageDigest.getInstance("SHA-256") }
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) break
        if (read.toLong() > expectedBytes - count) return false
        count += read
        digest?.update(buffer, 0, read)
    }
    if (count != expectedBytes) return false
    return digest == null || digest.digest().joinToString("") { "%02x".format(it) } == normalizedHash
}
