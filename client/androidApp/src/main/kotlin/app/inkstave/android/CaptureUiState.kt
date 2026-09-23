package app.inkstave.android

/**
 * Which screen [CaptureActivity] should show, derived from two real-world facts about the
 * device/permission state. A plain decision table (no `Context`/`Activity` dependency) so it's
 * unit-testable without Robolectric or a real device -- see `CaptureUiStateTest.kt`. The Activity
 * itself owns turning [hasCameraHardware]/[cameraPermissionGranted] into this state (real
 * `PackageManager`/`ContextCompat` calls, not test-covered here) and reacting to the result here
 * (real `CameraX`/Compose UI, also not test-covered here) -- this file is only the decision in
 * between, factored out specifically so it can be.
 */
sealed interface CaptureUiState {
    /** No usable camera on this device at all (`PackageManager.FEATURE_CAMERA_ANY` absent) --
     * nothing a permission grant could fix, so distinct from [PermissionNeeded]. */
    data object NoCameraHardware : CaptureUiState

    /** A camera exists but the `CAMERA` runtime permission hasn't been granted yet (or was
     * denied) -- [CaptureActivity] shows an explanation and a way to (re-)request it. */
    data object PermissionNeeded : CaptureUiState

    /** Camera present, permission granted -- the live preview/shutter/review UI can run. */
    data object Ready : CaptureUiState
}

/**
 * The decision itself: [CaptureUiState.NoCameraHardware] takes priority over
 * [CaptureUiState.PermissionNeeded] -- there's no point asking for a permission that couldn't
 * possibly lead anywhere usable on a camera-less device.
 */
fun captureUiState(
    hasCameraHardware: Boolean,
    cameraPermissionGranted: Boolean,
): CaptureUiState =
    when {
        !hasCameraHardware -> CaptureUiState.NoCameraHardware
        !cameraPermissionGranted -> CaptureUiState.PermissionNeeded
        else -> CaptureUiState.Ready
    }
