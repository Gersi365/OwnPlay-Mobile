package app.ownplay.mobile.feature.playback.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import app.ownplay.mobile.feature.playback.domain.LiveDvrDataSourceProvider
import app.ownplay.mobile.feature.playback.domain.LiveDvrReadHandle
import java.io.IOException

internal class LiveDvrDataSourceFailureException(
    val stage: app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage,
    safeMessage: String,
    cause: Throwable? = null,
) : IOException(safeMessage, cause)

@UnstableApi
internal class LiveDvrDataSourceFactory(
    context: Context,
    private val provider: LiveDvrDataSourceProvider,
) : DataSource.Factory {
    private val fallbackFactory = DefaultDataSource.Factory(
        context.applicationContext,
        DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false),
    )

    override fun createDataSource(): DataSource =
        LiveDvrRoutingDataSource(fallbackFactory.createDataSource(), provider)
}

@UnstableApi
private class LiveDvrRoutingDataSource(
    private val fallback: DataSource,
    private val provider: LiveDvrDataSourceProvider,
) : DataSource {
    private var reader: LiveDvrReadHandle? = null
    private var activeUri: Uri? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        activeUri = dataSpec.uri
        if (dataSpec.uri.scheme != DVR_SCHEME) {
            return fallback.open(dataSpec)
        }
        val sessionId = dataSpec.uri.host
            ?: throw LiveDvrDataSourceFailureException(
                app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_URI,
                "The Live DVR session URI is invalid.",
            )
        val baseOffset = dataSpec.uri.getQueryParameter("offset")?.toLongOrNull()
            ?: if (dataSpec.uri.getQueryParameter("offset") == null) 0L else {
                throw LiveDvrDataSourceFailureException(
                    app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_URI,
                    "The Live DVR retained offset is invalid.",
                )
            }
        val absolutePosition = try {
            Math.addExact(baseOffset, dataSpec.position)
        } catch (_: ArithmeticException) {
            throw LiveDvrDataSourceFailureException(
                app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_URI,
                "The Live DVR retained offset is invalid.",
            )
        }
        val opened = try {
            if (
                baseOffset == 0L && dataSpec.position == 0L &&
                dataSpec.uri.getQueryParameter("start") == "live"
            ) {
                provider.openLiveReadHandle(sessionId)
            } else {
                provider.openReadHandle(sessionId, absolutePosition)
            }
        } catch (failure: IOException) {
            if (failure is LiveDvrDataSourceFailureException) throw failure
            throw LiveDvrDataSourceFailureException(
                app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_READER_OPEN,
                "The Live DVR session could not open a retained reader.",
                failure,
            )
        }
        reader = opened ?: throw LiveDvrDataSourceFailureException(
            app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_SESSION_LOOKUP,
            "The Live DVR session is no longer available.",
        )
        val openedReader = reader ?: throw LiveDvrDataSourceFailureException(
            app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_SESSION_LOOKUP,
            "The Live DVR session is no longer available.",
        )
        return if (openedReader.ended) {
            (openedReader.availableLength - absolutePosition).coerceAtLeast(0L)
        } else {
            C.LENGTH_UNSET.toLong()
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val localReader = reader ?: return fallback.read(buffer, offset, length)
        return try {
            localReader.read(buffer, offset, length)
        } catch (failure: IOException) {
            if (failure is LiveDvrDataSourceFailureException) throw failure
            throw LiveDvrDataSourceFailureException(
                app.ownplay.mobile.feature.playback.domain.PlaybackFailureStage.LIVE_DVR_READ,
                "The retained Live DVR stream could not be read.",
                failure,
            )
        }
    }

    override fun getUri(): Uri? = reader?.let { activeUri } ?: fallback.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (reader != null) emptyMap() else fallback.responseHeaders

    override fun close() {
        reader?.close()
        reader = null
        if (activeUri?.scheme != DVR_SCHEME) fallback.close()
        activeUri = null
    }

    private companion object {
        const val DVR_SCHEME = "ownplaydvr"
    }
}
