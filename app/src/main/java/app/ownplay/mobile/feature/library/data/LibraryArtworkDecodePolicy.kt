package app.ownplay.mobile.feature.library.data

internal object LibraryArtworkDecodePolicy {
    const val DEFAULT_MAX_DIMENSION_PX: Int = 1024

    fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
    ): Int {
        require(maxDimensionPx > 0) { "Artwork decode bound must be positive" }
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1

        val largest = maxOf(sourceWidth, sourceHeight).toLong()
        var sample = 1L
        while (
            ceilDiv(largest, sample) > maxDimensionPx.toLong() &&
            sample <= Int.MAX_VALUE.toLong() / 2L
        ) {
            sample *= 2L
        }
        return sample.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ceilDiv(value: Long, divisor: Long): Long =
        (value + divisor - 1L) / divisor
}
