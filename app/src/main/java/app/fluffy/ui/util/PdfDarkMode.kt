package app.fluffy.ui.util

import androidx.compose.ui.graphics.ColorMatrix

object PdfDarkMode {
    private const val WARM_R = 1.00f
    private const val WARM_G = 0.92f
    private const val WARM_B = 0.72f

    val colorMatrix: ColorMatrix = ColorMatrix(
        floatArrayOf(
            -0.299f * WARM_R, -0.587f * WARM_R, -0.114f * WARM_R, 0f, 255f * WARM_R,
            -0.299f * WARM_G, -0.587f * WARM_G, -0.114f * WARM_G, 0f, 255f * WARM_G,
            -0.299f * WARM_B, -0.587f * WARM_B, -0.114f * WARM_B, 0f, 255f * WARM_B,
            0f, 0f, 0f, 1f, 0f
        )
    )
}
