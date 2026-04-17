package com.orna.autobattle

/**
 * Produces a sequence of map-navigation actions (pan, zoom) to explore the
 * world map when no monsters or shop buildings are visible on screen.
 *
 * Strategy: spiral out from centre using 8 cardinal + diagonal swipes,
 * then zoom out (pinch) to reveal a wider area.
 */
class MapNavigator {

    data class PanAction(
        val fromX: Float, val fromY: Float,
        val toX: Float,   val toY: Float,
        val durationMs: Long = 400
    )

    data class ZoomAction(
        /** Two finger endpoints for pinch-zoom. Positive delta = zoom in, negative = zoom out. */
        val cx: Float, val cy: Float,
        val deltaX: Float,  // each finger moves ±deltaX from centre
        val deltaY: Float,
        val durationMs: Long = 400
    )

    sealed class NavAction {
        data class Pan(val pan: PanAction) : NavAction()
        data class Zoom(val zoom: ZoomAction) : NavAction()
    }

    private var stepIndex = 0

    // Swipe directions: drag from → to  (drags the map so world moves opposite)
    // Centre of the game map area: approximately x=50%, y=45%
    private val cx = 0.50f
    private val cy = 0.45f
    private val swipeAmt = 0.20f  // how far each swipe moves (fraction of screen)

    private val panSequence: List<PanAction> = buildList {
        // 8 directions × 2 repeats, then zoom out
        val dirs = listOf(
            -swipeAmt to 0f,      // pan left  (drag right)
             swipeAmt to 0f,      // pan right
            0f to -swipeAmt,      // pan up
            0f to  swipeAmt,      // pan down
            -swipeAmt to -swipeAmt,
             swipeAmt to -swipeAmt,
            -swipeAmt to  swipeAmt,
             swipeAmt to  swipeAmt,
        )
        for (repeat in 0..1) {
            for ((dx, dy) in dirs) {
                add(PanAction(cx, cy, (cx + dx).coerceIn(0.05f, 0.95f), (cy + dy).coerceIn(0.05f, 0.85f)))
            }
        }
    }

    /** Returns the next navigation action to try. Cycles through pan then zoom. */
    fun next(): NavAction {
        val idx = stepIndex % (panSequence.size + 2)
        stepIndex++
        return when {
            idx < panSequence.size -> NavAction.Pan(panSequence[idx])
            idx == panSequence.size -> NavAction.Zoom(ZoomAction(cx, cy, 0.15f, 0f))  // zoom out
            else -> NavAction.Zoom(ZoomAction(cx, cy, -0.10f, 0f))                    // zoom in
        }
    }

    fun reset() { stepIndex = 0 }
}
