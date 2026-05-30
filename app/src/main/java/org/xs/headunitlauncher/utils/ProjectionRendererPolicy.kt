package org.xs.headunitlauncher.utils

object ProjectionRendererPolicy {
    const val REASON_TEXTURE_FORCED_SPREADTRUM = "texture-forced-spreadtrum"

    data class Decision(
        val viewMode: Settings.ViewMode,
        val reason: String? = null
    )

    fun resolve(
        requested: Settings.ViewMode,
        sdkInt: Int,
        manufacturer: String?,
        hardware: String?,
        board: String?,
        device: String?,
        model: String?
    ): Decision {
        if (requested == Settings.ViewMode.GLES &&
            isSpreadtrumAndroid8(sdkInt, manufacturer, hardware, board, device, model)
        ) {
            return Decision(Settings.ViewMode.TEXTURE, REASON_TEXTURE_FORCED_SPREADTRUM)
        }
        return Decision(requested)
    }

    fun isSpreadtrumAndroid8(
        sdkInt: Int,
        manufacturer: String?,
        hardware: String?,
        board: String?,
        device: String?,
        model: String?
    ): Boolean {
        if (sdkInt !in 26..27) return false
        val joined = listOfNotNull(manufacturer, hardware, board, device, model)
            .joinToString(separator = " ")
            .lowercase()

        return joined.contains("sprd") ||
            joined.contains("spreadtrum") ||
            joined.contains("unisoc") ||
            joined.contains("sp7731") ||
            joined.contains("sc7731")
    }
}
