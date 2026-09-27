package app.inkstave.android

/**
 * Which screen [CaptureActivity] shows, as a plain decision table with no Android dependencies so it
 * can be unit-tested.
 */
sealed interface CaptureUiState {
    /** No camera on the device; distinct from [PermissionNeeded] because no grant can fix it. */
    data object NoCameraHardware : CaptureUiState

    /** A camera exists but the `CAMERA` permission isn't granted; [CaptureActivity] explains and asks. */
    data object PermissionNeeded : CaptureUiState

    /** Camera present and permission granted. */
    data object Ready : CaptureUiState
}

/** [CaptureUiState.NoCameraHardware] wins over [CaptureUiState.PermissionNeeded]: asking for a
 * permission is pointless without a camera. */
fun captureUiState(
    hasCameraHardware: Boolean,
    cameraPermissionGranted: Boolean,
): CaptureUiState =
    when {
        !hasCameraHardware -> CaptureUiState.NoCameraHardware
        !cameraPermissionGranted -> CaptureUiState.PermissionNeeded
        else -> CaptureUiState.Ready
    }
