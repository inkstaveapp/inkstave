package app.inkstave.android

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureUiStateTest {
    @Test
    fun `no camera hardware wins even if permission happens to be granted`() {
        assertEquals(CaptureUiState.NoCameraHardware, captureUiState(hasCameraHardware = false, cameraPermissionGranted = true))
        assertEquals(CaptureUiState.NoCameraHardware, captureUiState(hasCameraHardware = false, cameraPermissionGranted = false))
    }

    @Test
    fun `permission needed when hardware exists but permission is not granted`() {
        assertEquals(CaptureUiState.PermissionNeeded, captureUiState(hasCameraHardware = true, cameraPermissionGranted = false))
    }

    @Test
    fun `ready when hardware exists and permission is granted`() {
        assertEquals(CaptureUiState.Ready, captureUiState(hasCameraHardware = true, cameraPermissionGranted = true))
    }
}
