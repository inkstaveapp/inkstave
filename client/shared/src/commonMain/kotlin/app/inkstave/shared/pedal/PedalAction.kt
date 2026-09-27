package app.inkstave.shared.pedal

/**
 * What a page-turner pedal (or a keyboard standing in for one) can trigger in the viewer.
 * Deliberately only page turns: pedals have one or two switches, and a mid-performance press should
 * never do anything but turn the page.
 */
enum class PedalAction {
    NEXT_PAGE,
    PREVIOUS_PAGE,
}
