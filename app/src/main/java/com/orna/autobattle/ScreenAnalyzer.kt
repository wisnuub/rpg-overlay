package com.orna.autobattle

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.sqrt

/**
 * Analyzes a screen bitmap to determine game state and find action targets.
 *
 * All screen positions are derived from real OrnaRPG screenshots (portrait,
 * ~710×1540 reference resolution — ratios scale to any resolution).
 */
object ScreenAnalyzer {

    enum class GameState {
        WORLD_MAP,
        PRE_BATTLE,             // green BATTLE button popup
        PRE_BATTLE_BERSERK,     // red BATTLE button popup
        BATTLE,                 // 6-button combat grid
        ITEM_SCREEN,            // battle item grid (2-col, opened from ITEM button)
        WORLD_MAP_ITEMS,        // world-map inventory grid (4-col, opened from ITEMS nav)
        SKILL_SCREEN,           // skill submenu open
        FLEE_CONFIRM,           // "Flee from battle?" YES/NO dialog
        VICTORY,                // VICTORY! + CONTINUE button
        SHOP_SCREEN,            // shop open (Buy/Sell/BuyBack tabs visible)
        BUY_DIALOG,             // quantity-selection popup over shop
        PLAYER_SCREEN,          // character menu (opened intentionally to check buffs)
        UNWANTED_SCREEN,        // accidental building tap that's NOT a shop → tap X
        UNKNOWN
    }

    data class ItemCell(val x: Float, val y: Float, val needsLongPress: Boolean)

    data class MonsterTarget(
        val x: Int,
        val y: Int,
        val confidence: Float,
        val templateName: String
    )

    // ── Battle panel ──────────────────────────────────────────────────────────
    private const val BATTLE_PANEL_Y = 0.767f
    private const val BATTLE_DARK_MAX = 55
    private const val BATTLE_DARK_RATIO = 0.58f

    // ── Pre-battle popup BATTLE button ────────────────────────────────────────
    private const val PREBATTLE_BTN_X = 0.499f
    private const val PREBATTLE_BTN_Y = 0.570f
    private const val PREBATTLE_BTN_HALF_W = 0.100f
    private const val PREBATTLE_BTN_HALF_H = 0.023f
    private const val PREBATTLE_GREEN_SAT_MIN = 0.20f
    private const val PREBATTLE_RED_SAT_MIN = 0.25f

    // ── Victory CONTINUE button ───────────────────────────────────────────────
    private const val VICTORY_BTN_X = 0.499f
    private const val VICTORY_BTN_Y = 0.943f
    private const val VICTORY_BTN_HALF_W = 0.140f
    private const val VICTORY_BTN_HALF_H = 0.020f

    // ── Flee confirmation dialog ──────────────────────────────────────────────
    private const val FLEE_CONFIRM_SAMPLE_Y = 0.430f
    private const val FLEE_YES_X = 0.689f
    private const val FLEE_YES_Y = 0.578f
    private const val FLEE_NO_X  = 0.251f
    private const val FLEE_NO_Y  = 0.578f

    // ── Battle item screen (2-col grid) ───────────────────────────────────────
    private const val ITEM_GRID_START_Y = 0.140f
    private const val ITEM_ROW_H = 0.0747f
    private const val ITEM_LEFT_X = 0.251f
    private const val ITEM_RIGHT_X = 0.749f
    private const val ITEM_BORDER_LEFT_COL_X  = 0.038f
    private const val ITEM_BORDER_RIGHT_COL_X = 0.523f
    private const val GOLD_HUE_MIN = 33f
    private const val GOLD_HUE_MAX = 58f
    private const val GOLD_SAT_MIN = 0.45f
    private const val GOLD_VAL_MIN = 0.50f
    private const val ICON_REGION_HALF = 0.025f

    // ── World-map 4-col inventory grid ────────────────────────────────────────
    // Columns at x≈12.5%, 38.2%, 61.2%, 84.4%; first booster row at y≈59.1%
    private val WMAP_ITEM_COLS = floatArrayOf(0.125f, 0.382f, 0.612f, 0.844f)
    private const val WMAP_ITEM_ROW_Y = 0.591f

    // ── Screen close / nav X button ──────────────────────────────────────────
    private const val SCREEN_CLOSE_X = 0.499f
    private const val SCREEN_CLOSE_Y = 0.960f

    // ── HP / MP bars ─────────────────────────────────────────────────────────
    private const val BAR_X_LEFT = 0.141f
    private const val BAR_X_RIGHT = 0.331f
    private const val HP_BAR_Y = 0.537f
    private const val MP_BAR_Y = 0.549f

    // ── Shop screen ──────────────────────────────────────────────────────────
    // Category row (filter tabs) at y≈21.5%; potion category (first icon) at x≈9.6%
    private const val SHOP_CATEGORY_ROW_Y = 0.215f
    private const val SHOP_POTION_CATEGORY_X = 0.096f
    // Item rows: first row center y≈40.3%, row height ≈15.7%
    private const val SHOP_ITEM_ROW_START_Y = 0.403f
    private const val SHOP_ITEM_ROW_HEIGHT = 0.157f
    // BUY button on the right side of each item row
    private const val SHOP_BUY_BTN_X = 0.837f
    // Shop detection: look for a green BUY button at the first item row
    private const val SHOP_TAB_Y = 0.214f

    // ── Buy quantity dialog ───────────────────────────────────────────────────
    // +10 button at x≈67.5%, dialog center y≈50.3%
    private const val BUY_DIALOG_PLUS10_X = 0.675f
    private const val BUY_DIALOG_QTY_Y = 0.503f
    // Confirm BUY button at (69.3%, 68.7%); CANCEL at (30.6%, 68.7%)
    private const val BUY_DIALOG_CONFIRM_X = 0.693f
    private const val BUY_DIALOG_CONFIRM_Y = 0.687f
    private const val BUY_DIALOG_CANCEL_X = 0.306f

    // ── Purchase success banner ───────────────────────────────────────────────
    // White "Purchased: …" banner appears at y≈7.5% across the screen
    private const val PURCHASE_BANNER_Y = 0.075f

    // ── Template matching ─────────────────────────────────────────────────────
    private const val MATCH_THRESHOLD = 0.82f
    private const val MATCH_STEP = 4
    private const val SEARCH_Y_TOP = 0.05f
    private const val SEARCH_Y_BOT = 0.88f

    // ── Yellow heuristic ──────────────────────────────────────────────────────
    private const val HUE_MIN = 35f
    private const val HUE_MAX = 65f
    private const val SAT_MIN = 0.55f
    private const val VAL_MIN = 0.80f

    // ─────────────────────────────────────────────────────────────────────────

    fun detectState(bmp: Bitmap): GameState {
        if (hasVictoryButton(bmp))    return GameState.VICTORY
        if (isFleeConfirmScreen(bmp)) return GameState.FLEE_CONFIRM
        if (isBuyDialog(bmp))         return GameState.BUY_DIALOG
        if (isShopScreen(bmp))        return GameState.SHOP_SCREEN
        if (hasBattlePanel(bmp))      return GameState.BATTLE
        val preBattle = detectPreBattle(bmp)
        if (preBattle != null)        return preBattle
        if (hasItemGridPattern(bmp)) {
            return if (isWorldMapItemGrid(bmp)) GameState.WORLD_MAP_ITEMS
                   else GameState.ITEM_SCREEN
        }
        if (hasYellowMonsters(bmp) || TemplateManager.count() > 0) return GameState.WORLD_MAP
        if (isBlackOverlayScreen(bmp)) {
            return if (isPlayerScreen(bmp)) GameState.PLAYER_SCREEN
                   else GameState.UNWANTED_SCREEN
        }
        return GameState.UNKNOWN
    }

    // ── Shop detection ────────────────────────────────────────────────────────

    /**
     * Shop screen has a list of items with green BUY buttons on the right side.
     * Detect by checking for a green button shape at x≈83.7%, y≈40.3% (first row).
     */
    fun isShopScreen(bmp: Bitmap): Boolean {
        if (hasBattlePanel(bmp)) return false
        val w = bmp.width; val h = bmp.height
        val bx = (SHOP_BUY_BTN_X * w).toInt()
        val by = (SHOP_ITEM_ROW_START_Y * h).toInt()
        var greenCount = 0; var total = 0
        for (dy in -15..15 step 3) for (dx in -35..35 step 5) {
            val p = bmp.getPixel((bx + dx).coerceIn(0, w - 1), (by + dy).coerceIn(0, h - 1))
            val hsv = rgbToHsv(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f)
            if (hsv[0] in 80f..160f && hsv[1] > 0.20f) greenCount++
            total++
        }
        return total > 0 && greenCount.toFloat() / total > 0.22f
    }

    /**
     * Buy quantity dialog has a green BUY confirm button at (69.3%, 68.7%)
     * and a visible +10 button area at (67.5%, 50.3%).
     */
    fun isBuyDialog(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val cx = (BUY_DIALOG_CONFIRM_X * w).toInt()
        val cy = (BUY_DIALOG_CONFIRM_Y * h).toInt()
        var green = 0; var total = 0
        for (dy in -12..12 step 3) for (dx in -30..30 step 5) {
            val p = bmp.getPixel((cx + dx).coerceIn(0, w - 1), (cy + dy).coerceIn(0, h - 1))
            val hsv = rgbToHsv(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f)
            if (hsv[0] in 80f..160f && hsv[1] > 0.20f) green++
            total++
        }
        if (total == 0 || green.toFloat() / total < 0.22f) return false
        // Also verify the +/- quantity area is visible (has some content in dialog center)
        val qx = (BUY_DIALOG_PLUS10_X * w).toInt()
        val qy = (BUY_DIALOG_QTY_Y * h).toInt()
        val center = bmp.getPixel(qx.coerceIn(0, w - 1), qy.coerceIn(0, h - 1))
        return (Color.red(center) + Color.green(center) + Color.blue(center)) / 3 > 30
    }

    /**
     * White "Purchased: …" banner appears at the top of the screen after a buy.
     */
    fun hasPurchaseBanner(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val y = (PURCHASE_BANNER_Y * h).toInt().coerceIn(0, h - 1)
        var bright = 0; var total = 0
        for (x in (w * 0.10f).toInt() until (w * 0.90f).toInt() step 5) {
            val p = bmp.getPixel(x, y)
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 > 180) bright++
            total++
        }
        return total > 0 && bright.toFloat() / total > 0.40f
    }

    /**
     * Scans shop item rows for a red-icon health potion.
     * Returns the Y ratio of the matching row's center, or null if not found.
     */
    fun findShopHealthPotionRow(bmp: Bitmap): Float? {
        val w = bmp.width; val h = bmp.height
        val iconX = (0.15f * w).toInt()
        for (row in 0..4) {
            val rowY = SHOP_ITEM_ROW_START_Y + row * SHOP_ITEM_ROW_HEIGHT
            if (rowY > 0.92f) break
            val iconY = (rowY * h).toInt().coerceIn(0, h - 1)
            if (isCellHealthPotion(bmp, iconX, iconY)) return rowY
        }
        return null
    }

    /**
     * Scans the world map for red-roof building clusters (r>180, g<100, b<60).
     * Returns pixel coordinates of the nearest building centroid, or null.
     */
    fun findShopBuilding(bmp: Bitmap): Pair<Int, Int>? {
        val w = bmp.width; val h = bmp.height
        val reds = mutableListOf<Pair<Int, Int>>()
        for (y in (h * 0.10f).toInt() until (h * 0.85f).toInt() step 4)
            for (x in 0 until w step 4) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 180 && g < 100 && b < 60) reds.add(x to y)
            }
        if (reds.size < 15) return null
        return clusterCentroids(reds, 30).firstOrNull()
    }

    // ── Position accessors ────────────────────────────────────────────────────

    fun closeButtonPos() = SCREEN_CLOSE_X to SCREEN_CLOSE_Y
    fun fleeYesPos() = FLEE_YES_X to FLEE_YES_Y
    fun fleeNoPos() = FLEE_NO_X to FLEE_NO_Y
    fun shopPotionCategoryPos() = SHOP_POTION_CATEGORY_X to SHOP_CATEGORY_ROW_Y
    fun shopBuyBtnPos(rowY: Float) = SHOP_BUY_BTN_X to rowY
    fun buyDialogPlus10Pos() = BUY_DIALOG_PLUS10_X to BUY_DIALOG_QTY_Y
    fun buyDialogConfirmPos() = BUY_DIALOG_CONFIRM_X to BUY_DIALOG_CONFIRM_Y

    // ── Flee / item screen ────────────────────────────────────────────────────

    fun isFleeConfirmScreen(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        // YES button (red) at (68.9%, 57.8%)
        val yesX = (FLEE_YES_X * w).toInt(); val yesY = (FLEE_YES_Y * h).toInt()
        var yesRed = 0; var yesTotal = 0
        for (dy in -8..8 step 2) for (dx in -20..20 step 4) {
            val p = bmp.getPixel((yesX + dx).coerceIn(0, w - 1), (yesY + dy).coerceIn(0, h - 1))
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r > 150 && r > g + 40 && r > b + 40) yesRed++
            yesTotal++
        }
        if (yesTotal == 0 || yesRed.toFloat() / yesTotal <= 0.40f) return false
        // NO button (gray/blue) at (25.1%, 57.8%) must NOT also be red —
        // rules out red battle effects, enemy health bars, etc. at the YES position.
        val noX = (FLEE_NO_X * w).toInt()
        var noRed = 0; var noTotal = 0
        for (dy in -8..8 step 2) for (dx in -20..20 step 4) {
            val p = bmp.getPixel((noX + dx).coerceIn(0, w - 1), (yesY + dy).coerceIn(0, h - 1))
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r > 150 && r > g + 40 && r > b + 40) noRed++
            noTotal++
        }
        return noTotal == 0 || noRed.toFloat() / noTotal < 0.25f
    }

    fun isItemScreen(bmp: Bitmap): Boolean {
        if (hasBattlePanel(bmp)) return false
        return hasItemGridPattern(bmp)
    }

    fun isSkillScreen(bmp: Bitmap): Boolean = isItemScreen(bmp)

    fun findHealthPotion(bmp: Bitmap): ItemCell? {
        val w = bmp.width; val h = bmp.height
        val maxRows = 14
        for (row in maxRows downTo 0) {
            for (col in 1 downTo 0) {
                val cx = if (col == 0) ITEM_LEFT_X else ITEM_RIGHT_X
                val cy = ITEM_GRID_START_Y + row * ITEM_ROW_H + ITEM_ROW_H * 0.5f
                if (cy > 0.95f) continue
                val iconX = (cx * w).toInt()
                val iconY = ((cy - ITEM_ROW_H * 0.15f) * h).toInt()
                if (isCellHealthPotion(bmp, iconX, iconY)) {
                    val goldBorderX = if (col == 0) ITEM_BORDER_LEFT_COL_X else ITEM_BORDER_RIGHT_COL_X
                    val needsLongPress = hasCellGoldBorder(bmp, goldBorderX, cy)
                    return ItemCell(cx, cy, needsLongPress)
                }
            }
        }
        return null
    }

    // ── Pre-battle detection ──────────────────────────────────────────────────

    fun detectPreBattle(bmp: Bitmap): GameState? {
        val w = bmp.width; val h = bmp.height
        val cx = (PREBATTLE_BTN_X * w).toInt()
        val cy = (PREBATTLE_BTN_Y * h).toInt()
        val rx = (PREBATTLE_BTN_HALF_W * w).toInt()
        val ry = (PREBATTLE_BTN_HALF_H * h).toInt()

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (y in (cy - ry)..(cy + ry) step 3) {
            for (x in (cx - rx)..(cx + rx) step 3) {
                val px = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                rSum += Color.red(px); gSum += Color.green(px); bSum += Color.blue(px)
                count++
            }
        }
        if (count == 0) return null
        val r = rSum.toFloat() / count / 255f
        val g = gSum.toFloat() / count / 255f
        val b = bSum.toFloat() / count / 255f
        val hsv = rgbToHsv(r, g, b)
        val hue = hsv[0]; val sat = hsv[1]

        return when {
            sat >= PREBATTLE_GREEN_SAT_MIN && hue in 80f..160f -> GameState.PRE_BATTLE
            sat >= PREBATTLE_RED_SAT_MIN && (hue >= 340f || hue <= 20f) -> GameState.PRE_BATTLE_BERSERK
            sat >= PREBATTLE_RED_SAT_MIN && hue in 10f..45f -> GameState.PRE_BATTLE_BERSERK
            else -> null
        }
    }

    // ── Resource bars ─────────────────────────────────────────────────────────

    fun detectHpPercent(bmp: Bitmap): Int = barFillPercent(bmp, HP_BAR_Y, ::isHpRed)

    fun detectMpPercent(bmp: Bitmap): Int = barFillPercent(bmp, MP_BAR_Y, ::isMpBlue)

    // ── Monster finding ───────────────────────────────────────────────────────

    fun findMonsters(bmp: Bitmap, maxTargets: Int = 5): List<MonsterTarget> {
        val templates = TemplateManager.all()
        if (templates.isNotEmpty()) {
            val hits = findByTemplates(bmp, templates, maxTargets)
            if (hits.isNotEmpty()) return hits
        }
        return findByYellowHeuristic(bmp, maxTargets)
    }

    // ── Generic overlay dismiss ───────────────────────────────────────────────

    fun needsDismiss(bmp: Bitmap): Boolean {
        if (hasVictoryButton(bmp)) return false
        val w = bmp.width; val h = bmp.height
        val cx = w / 2
        val cyTop = (h * 0.35f).toInt(); val cyBot = (h * 0.55f).toInt()
        var bright = 0; var total = 0
        for (y in cyTop until cyBot step 4)
            for (x in cx - 80 until cx + 80 step 4) {
                val p = bmp.getPixel(x.coerceIn(0, w - 1), y)
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 > 200) bright++
                total++
            }
        return total > 0 && bright.toFloat() / total > 0.30f
    }

    // ── Player / character screen ─────────────────────────────────────────────

    fun isPlayerScreen(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val xL = (w * 0.36f).toInt(); val xR = (w * 0.64f).toInt()
        val yT = (h * 0.38f).toInt(); val yB = (h * 0.50f).toInt()
        var orange = 0; var total = 0
        for (y in yT..yB step 4) for (x in xL..xR step 4) {
            val p = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r > 160 && g in 80..200 && b < 80 && r > g + 40) orange++
            total++
        }
        return total > 0 && orange.toFloat() / total > 0.06f
    }

    fun detectActiveBuffCount(bmp: Bitmap): Int {
        val w = bmp.width; val h = bmp.height
        val y = (h * 0.628f).toInt().coerceIn(0, h - 1)
        val xStart = (w * 0.05f).toInt(); val xEnd = (w * 0.95f).toInt()
        var inPill = false; var pillCount = 0; var runLen = 0
        for (x in xStart until xEnd) {
            val p = bmp.getPixel(x, y)
            val bright = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3 > 60
            if (bright) {
                runLen++
                if (!inPill && runLen > 8) { inPill = true; pillCount++ }
            } else {
                if (inPill && runLen < 6) pillCount--
                inPill = false; runLen = 0
            }
        }
        return pillCount.coerceAtLeast(0)
    }

    // ── Private: black overlay detection ─────────────────────────────────────

    private fun isBlackOverlayScreen(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        var sum = 0L; var count = 0
        for (y in (h * 0.05f).toInt() until (h * 0.60f).toInt() step 20)
            for (x in (w * 0.05f).toInt() until (w * 0.95f).toInt() step 20) {
                val p = bmp.getPixel(x, y)
                sum += (Color.red(p) + Color.green(p) + Color.blue(p)) / 3
                count++
            }
        if (count == 0) return false
        // True overlay screens (player/building menus) are near-black (avg < 28).
        // Dark cave/mountain terrain is dark grey (avg 30-45) — must not trigger this.
        return sum.toFloat() / count < 28f
    }

    // ── Private: grid detection ───────────────────────────────────────────────

    private fun hasItemGridPattern(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        var darkCells = 0
        for (row in 1..2) {
            val cy = ((ITEM_GRID_START_Y + row * ITEM_ROW_H) * h).toInt()
            for (cx in listOf(ITEM_LEFT_X, ITEM_RIGHT_X)) {
                val p = bmp.getPixel((cx * w).toInt().coerceIn(0, w - 1), cy.coerceIn(0, h - 1))
                if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 80) darkCells++
            }
        }
        return darkCells >= 3
    }

    /**
     * Distinguishes the 4-column world-map inventory from the 2-column battle
     * item screen. Checks for dark cells at the 4-column x positions.
     */
    private fun isWorldMapItemGrid(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val ry = (WMAP_ITEM_ROW_Y * h).toInt().coerceIn(0, h - 1)
        var darkCols = 0
        for (cx in WMAP_ITEM_COLS) {
            val p = bmp.getPixel((cx * w).toInt().coerceIn(0, w - 1), ry)
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < 80) darkCols++
        }
        return darkCols >= 3
    }

    // ── Private: cell analysis ────────────────────────────────────────────────

    private fun isCellHealthPotion(bmp: Bitmap, cx: Int, cy: Int): Boolean {
        val w = bmp.width; val h = bmp.height
        val r = (ICON_REGION_HALF * w).toInt().coerceAtLeast(8)
        var redDom = 0; var total = 0
        for (dy in -r..r step 3) for (dx in -r..r step 3) {
            val p = bmp.getPixel((cx + dx).coerceIn(0, w - 1), (cy + dy).coerceIn(0, h - 1))
            val rv = Color.red(p); val gv = Color.green(p); val bv = Color.blue(p)
            if (rv > 130 && rv > gv + 30 && rv > bv + 30) redDom++
            total++
        }
        return total > 0 && redDom.toFloat() / total > 0.18f
    }

    private fun hasCellGoldBorder(bmp: Bitmap, borderX: Float, cellCY: Float): Boolean {
        val w = bmp.width; val h = bmp.height
        val px = (borderX * w).toInt().coerceIn(0, w - 1)
        val pyTop = ((cellCY - ITEM_ROW_H * 0.3f) * h).toInt().coerceIn(0, h - 1)
        val pyBot = ((cellCY + ITEM_ROW_H * 0.3f) * h).toInt().coerceIn(0, h - 1)
        var goldCount = 0; var total = 0
        for (py in pyTop..pyBot step 3) {
            val p = bmp.getPixel(px, py)
            val hsv = rgbToHsv(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f)
            if (hsv[0] in GOLD_HUE_MIN..GOLD_HUE_MAX && hsv[1] >= GOLD_SAT_MIN && hsv[2] >= GOLD_VAL_MIN)
                goldCount++
            total++
        }
        return total > 0 && goldCount.toFloat() / total > 0.35f
    }

    // ── Private: battle panel / victory ──────────────────────────────────────

    private fun hasBattlePanel(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val panelY = (h * BATTLE_PANEL_Y).toInt()
        var dark = 0; var total = 0
        for (x in (w * 0.1f).toInt() until (w * 0.9f).toInt() step 8) {
            val p = bmp.getPixel(x, panelY.coerceIn(0, h - 1))
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < BATTLE_DARK_MAX) dark++
            total++
        }
        if (total == 0 || dark.toFloat() / total <= BATTLE_DARK_RATIO) return false
        // The battle panel is a dark bar at the bottom; the area above (battlefield / world map)
        // should be meaningfully brighter. Reject if the whole screen is uniformly dark
        // (dark cave/grey terrain on world map would fail this check).
        val aboveY = (h * 0.62f).toInt().coerceIn(0, h - 1)
        var aboveDark = 0; var aboveTotal = 0
        for (x in (w * 0.1f).toInt() until (w * 0.9f).toInt() step 8) {
            val p = bmp.getPixel(x, aboveY)
            if ((Color.red(p) + Color.green(p) + Color.blue(p)) / 3 < BATTLE_DARK_MAX) aboveDark++
            aboveTotal++
        }
        return aboveTotal == 0 || aboveDark.toFloat() / aboveTotal < 0.30f
    }

    private fun hasVictoryButton(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val cx = (VICTORY_BTN_X * w).toInt()
        val cy = (VICTORY_BTN_Y * h).toInt()
        val rx = (VICTORY_BTN_HALF_W * w).toInt()
        val ry = (VICTORY_BTN_HALF_H * h).toInt()
        var greenCount = 0; var total = 0
        for (y in (cy - ry)..(cy + ry) step 3)
            for (x in (cx - rx)..(cx + rx) step 3) {
                val p = bmp.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
                val hsv = rgbToHsv(Color.red(p) / 255f, Color.green(p) / 255f, Color.blue(p) / 255f)
                if (hsv[0] in 80f..160f && hsv[1] > 0.15f) greenCount++
                total++
            }
        return total > 0 && greenCount.toFloat() / total > 0.35f
    }

    // ── Private: bar fill ─────────────────────────────────────────────────────

    private fun barFillPercent(bmp: Bitmap, yRatio: Float, isBarColor: (Int) -> Boolean): Int {
        val w = bmp.width; val h = bmp.height
        val barY = (yRatio * h).toInt().coerceIn(0, h - 1)
        val xStart = (BAR_X_LEFT * w).toInt()
        val xEnd = (BAR_X_RIGHT * w).toInt()
        val total = xEnd - xStart
        if (total <= 0) return 100
        var lastFilled = xStart
        for (x in xStart until xEnd) {
            if (isBarColor(bmp.getPixel(x, barY))) lastFilled = x
        }
        return ((lastFilled - xStart).toFloat() / total * 100).toInt().coerceIn(0, 100)
    }

    private fun isHpRed(pixel: Int): Boolean {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        return r > 140 && r > g * 2 && r > b * 2
    }

    private fun isMpBlue(pixel: Int): Boolean {
        val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
        return b > 120 && b > r * 1.5f && b > g * 1.2f
    }

    // ── Private: template matching ────────────────────────────────────────────

    private fun findByTemplates(
        frame: Bitmap, templates: List<TemplateManager.Template>, maxTargets: Int
    ): List<MonsterTarget> {
        val fw = frame.width; val fh = frame.height
        val yStart = (fh * SEARCH_Y_TOP).toInt()
        val yEnd = (fh * SEARCH_Y_BOT).toInt()
        val framePixels = IntArray(fw * fh)
        frame.getPixels(framePixels, 0, fw, 0, 0, fw, fh)
        // Coarser step for large template sets to stay within frame budget
        val step = (templates.size / 8 + MATCH_STEP).coerceAtMost(28)
        val hits = mutableListOf<MonsterTarget>()
        for (tmpl in templates) {
            val bmp = tmpl.matchBitmap  // pre-scaled to ~56px at init time
            val tw = bmp.width; val th = bmp.height
            val tmplPixels = IntArray(tw * th)
            bmp.getPixels(tmplPixels, 0, tw, 0, 0, tw, th)
            var bestConf = 0f; var bestX = 0; var bestY = 0
            val ySearchEnd = minOf(yEnd, fh - th)
            for (fy in yStart until ySearchEnd step step)
                for (fx in 0 until fw - tw step step) {
                    val conf = sadConfidence(framePixels, fw, fx, fy, tmplPixels, tw, th)
                    if (conf > bestConf) { bestConf = conf; bestX = fx + tw / 2; bestY = fy + th / 2 }
                }
            if (bestConf >= MATCH_THRESHOLD)
                hits.add(MonsterTarget(bestX, bestY, bestConf, tmpl.name))
        }
        return deduplicateTargets(hits, 40).sortedByDescending { it.confidence }.take(maxTargets)
    }

    private fun sadConfidence(
        frame: IntArray, frameW: Int, ox: Int, oy: Int,
        tmpl: IntArray, tw: Int, th: Int
    ): Float {
        var sad = 0L; var count = 0
        for (ty in 0 until th) {
            val fOff = (oy + ty) * frameW + ox; val tOff = ty * tw
            for (tx in 0 until tw) {
                val tp = tmpl[tOff + tx]
                if ((tp ushr 24) < 128) continue
                val fp = frame[fOff + tx]
                sad += kotlin.math.abs(Color.red(fp) - Color.red(tp))
                sad += kotlin.math.abs(Color.green(fp) - Color.green(tp))
                sad += kotlin.math.abs(Color.blue(fp) - Color.blue(tp))
                count++
            }
        }
        if (count == 0) return 0f
        return 1f - sad.toFloat() / (count * 255L * 3L)
    }

    // ── Private: yellow heuristic ─────────────────────────────────────────────

    private fun findByYellowHeuristic(bmp: Bitmap, maxTargets: Int): List<MonsterTarget> {
        val w = bmp.width; val h = bmp.height
        val yellow = mutableListOf<Pair<Int, Int>>()
        for (y in (h * SEARCH_Y_TOP).toInt() until (h * SEARCH_Y_BOT).toInt() step 3)
            for (x in 0 until w step 3)
                if (isOrnaYellow(bmp.getPixel(x, y))) yellow.add(x to y)
        return clusterCentroids(yellow, 40).take(maxTargets)
            .map { (cx, cy) -> MonsterTarget(cx, cy + 20, 0.5f, "yellow_heuristic") }
    }

    private fun hasYellowMonsters(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height; var count = 0
        for (y in (h * 0.1f).toInt() until (h * 0.85f).toInt() step 6)
            for (x in 0 until w step 6)
                if (isOrnaYellow(bmp.getPixel(x, y)) && ++count >= 8) return true
        return false
    }

    private fun isOrnaYellow(pixel: Int): Boolean {
        val hsv = rgbToHsv(Color.red(pixel) / 255f, Color.green(pixel) / 255f, Color.blue(pixel) / 255f)
        return hsv[0] in HUE_MIN..HUE_MAX && hsv[1] >= SAT_MIN && hsv[2] >= VAL_MIN
    }

    // ── Private: utilities ────────────────────────────────────────────────────

    private fun rgbToHsv(r: Float, g: Float, b: Float): FloatArray {
        val max = maxOf(r, g, b); val delta = max - minOf(r, g, b)
        val s = if (max == 0f) 0f else delta / max
        val h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0) it + 360f else it }
        return floatArrayOf(h, s, max)
    }

    private fun deduplicateTargets(targets: List<MonsterTarget>, minDist: Int): List<MonsterTarget> {
        val kept = mutableListOf<MonsterTarget>()
        for (t in targets.sortedByDescending { it.confidence }) {
            val tooClose = kept.any { k ->
                val dx = (k.x - t.x).toLong(); val dy = (k.y - t.y).toLong()
                sqrt((dx * dx + dy * dy).toDouble()) < minDist
            }
            if (!tooClose) kept.add(t)
        }
        return kept
    }

    private fun clusterCentroids(points: List<Pair<Int, Int>>, r: Int): List<Pair<Int, Int>> {
        val assigned = BooleanArray(points.size)
        val out = mutableListOf<Pair<Int, Int>>()
        for (i in points.indices) {
            if (assigned[i]) continue
            var sx = points[i].first; var sy = points[i].second; var n = 1
            for (j in i + 1 until points.size) {
                if (assigned[j]) continue
                val dx = points[j].first - points[i].first
                val dy = points[j].second - points[i].second
                if (dx * dx + dy * dy <= r * r) {
                    sx += points[j].first; sy += points[j].second; n++; assigned[j] = true
                }
            }
            assigned[i] = true; out.add(sx / n to sy / n)
        }
        return out
    }
}
