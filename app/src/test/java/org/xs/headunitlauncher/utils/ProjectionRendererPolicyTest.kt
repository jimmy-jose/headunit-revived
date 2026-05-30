package org.xs.headunitlauncher.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionRendererPolicyTest {
    @Test
    fun spreadtrumAndroid8GlesFallsBackToTexture() {
        val decision = ProjectionRendererPolicy.resolve(
            requested = Settings.ViewMode.GLES,
            sdkInt = 27,
            manufacturer = "sprd",
            hardware = "sp7731e",
            board = "sp7731e_1h10",
            device = "sp7731e_1h10_native",
            model = "sp7731e_1h10_native"
        )

        assertEquals(Settings.ViewMode.TEXTURE, decision.viewMode)
        assertEquals(ProjectionRendererPolicy.REASON_TEXTURE_FORCED_SPREADTRUM, decision.reason)
    }

    @Test
    fun nonRiskDeviceKeepsGles() {
        val decision = ProjectionRendererPolicy.resolve(
            requested = Settings.ViewMode.GLES,
            sdkInt = 27,
            manufacturer = "google",
            hardware = "qcom",
            board = "walleye",
            device = "walleye",
            model = "Pixel 2"
        )

        assertEquals(Settings.ViewMode.GLES, decision.viewMode)
        assertNull(decision.reason)
    }

    @Test
    fun spreadtrumTextureRequestStaysTextureWithoutOverrideReason() {
        val decision = ProjectionRendererPolicy.resolve(
            requested = Settings.ViewMode.TEXTURE,
            sdkInt = 27,
            manufacturer = "sprd",
            hardware = "sp7731e",
            board = "sp7731e_1h10",
            device = "sp7731e_1h10_native",
            model = "sp7731e_1h10_native"
        )

        assertEquals(Settings.ViewMode.TEXTURE, decision.viewMode)
        assertNull(decision.reason)
    }
}
