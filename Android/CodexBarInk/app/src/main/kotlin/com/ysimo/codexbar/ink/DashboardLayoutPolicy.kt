package com.ysimo.codexbar.ink

data class DashboardLayoutProfile(
    val compactPhoneChrome: Boolean,
    val readerChrome: Boolean,
    val horizontalProviderContent: Boolean,
    val metricColumns: Int,
)

object DashboardLayoutPolicy {
    fun resolve(
        widthDp: Int,
        heightDp: Int,
        smallestWidthDp: Int = minOf(widthDp, heightDp),
    ): DashboardLayoutProfile {
        val shortEdge = minOf(widthDp, heightDp).coerceAtLeast(1)
        val longEdge = maxOf(widthDp, heightDp)
        val aspectRatio = longEdge.toFloat() / shortEdge
        val phoneShaped = aspectRatio >= PHONE_ASPECT_RATIO
        val readerChrome = smallestWidthDp >= READER_SHORT_EDGE_DP
        return DashboardLayoutProfile(
            compactPhoneChrome = phoneShaped && !readerChrome,
            readerChrome = readerChrome,
            horizontalProviderContent = widthDp >= heightDp,
            metricColumns = if (readerChrome && widthDp < heightDp) {
                WIDE_METRIC_COLUMNS
            } else {
                PORTRAIT_PHONE_METRIC_COLUMNS
            },
        )
    }

    private const val PHONE_ASPECT_RATIO = 1.72f
    private const val READER_SHORT_EDGE_DP = 600
    private const val PORTRAIT_PHONE_METRIC_COLUMNS = 2
    private const val WIDE_METRIC_COLUMNS = 3
}
