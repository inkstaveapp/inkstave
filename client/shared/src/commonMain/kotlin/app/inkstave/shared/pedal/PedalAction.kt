package app.inkstave.shared.pedal

/**
 * Something a page-turner pedal (or a hardware/Bluetooth keyboard standing
 * in for one) can trigger in the viewer (`ROADMAP.md` M3).
 *
 * Deliberately just these two for now: the pedals this project targets
 * (see `PedalKeyMapping`'s module doc) are one- or two-switch devices in
 * practice, and there's no third action anything in the app currently
 * calls -- performance mode's own on/off toggle (`ROADMAP.md` M3,
 * `PerformanceMode.kt`) is a manual UI control, not pedal-mappable, since
 * a real pedal press mid-performance should always mean "turn the page,"
 * never something a mis-press could disrupt a performance over. Add a new
 * case here only when something real needs it, not speculatively.
 */
enum class PedalAction {
    NEXT_PAGE,
    PREVIOUS_PAGE,
}
