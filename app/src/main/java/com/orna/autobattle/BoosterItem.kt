package com.orna.autobattle

/**
 * Booster items available on the world-map Items screen.
 *
 * Positions are normalised (x, y) ratios derived from the real OrnaRPG
 * world-map items screen (710×1540 reference, 4-column grid).
 *
 * BOOSTERS section starts at y≈51.9%.
 * Column X centres: col1=12.5%, col2=38.2%, col3=61.2%, col4=84.4%
 * Row Y centres (within boosters section):
 *   row1≈59.1%,  row2≈70.8%,  row3≈82.8%
 */
enum class BoosterItem(
    val label: String,
    val rx: Float,   // normalised x tap position
    val ry: Float    // normalised y tap position
) {
    EXP_POTION      ("EXP Potion",         0.612f, 0.591f),
    AFFINITY_CANDLE ("Affinity Candle",    0.125f, 0.591f),
    LUCKY_COIN      ("Lucky Coin",         0.382f, 0.708f),
    LUCKY_SILVER    ("Lucky Silver Coin",  0.612f, 0.708f),
    OCCULT_CANDLE   ("Occult Candle",      0.844f, 0.708f),
    HALLOWED_CANDLE ("Hallowed Candle",    0.125f, 0.708f),
    DOWSING_ROD     ("Dowsing Rod",        0.382f, 0.591f),
    TORCH           ("Torch",              0.382f, 0.828f);

    companion object {
        /** Default selection — common farm boosters. */
        val DEFAULT_ENABLED = setOf(EXP_POTION, AFFINITY_CANDLE, LUCKY_COIN, LUCKY_SILVER)
    }
}

/** Persists which boosters the user wants auto-applied. */
data class BoosterSettings(
    val enabled: Set<BoosterItem> = BoosterItem.DEFAULT_ENABLED,
    /** Reapply boosters every N battles (0 = only once at session start). */
    val reapplyEvery: Int = 0
)
