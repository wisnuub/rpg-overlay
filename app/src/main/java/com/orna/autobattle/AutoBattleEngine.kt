package com.orna.autobattle

import android.graphics.Bitmap
import android.util.Log

private const val TAG = "AutoBattleEngine"

/**
 * Core state machine for Orna auto-battle.
 *
 * All delays are intentionally generous to account for network/render latency
 * on the phone — never spam taps back-to-back.
 *
 * Call [tick] with each captured frame. Returns a single [Action] for the
 * overlay service to dispatch via AccessibilityService.
 */
class AutoBattleEngine {

    // ── Configurable settings ─────────────────────────────────────────────────
    var strategy: BattleStrategy = BattleStrategy.ATTACK_ONLY
    var mpSettings: MpSettings = MpSettings()
    var healThreshold: Int = 40
    var battleHealThreshold: Int = 30
    var fleeHpThreshold: Int = 0
    var autoBuy: Boolean = false
    var autoBuyCount: Int = 50   // potions to buy; rounded to nearest 10

    // ── Actions ───────────────────────────────────────────────────────────────
    sealed class Action {
        data class Tap(val x: Float, val y: Float) : Action()
        data class LongPress(val x: Float, val y: Float, val ms: Long = 700) : Action()
        data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float,
                         val durationMs: Long = 400) : Action()
        data class Wait(val ms: Long) : Action()
        object Nothing : Action()
    }

    // ── Engine states ─────────────────────────────────────────────────────────
    enum class State {
        APPLYING_BOOSTERS,
        CHECKING_BUFFS,
        HUNTING,
        HEALING,
        PRE_BATTLE,
        PRE_BATTLE_BERSERK,
        BATTLE,
        BATTLE_USING_ITEM,
        BATTLE_FLEEING,
        VICTORY,
        FINDING_SHOP,       // panning/zooming map to locate a shop building
        IN_SHOP,            // shop screen open, navigating to potions
        IN_BUY_DIALOG,      // quantity dialog open, tapping +10 and confirm
        DISMISSING
    }

    var state: State = State.HUNTING
        private set
    var lastScreenState: ScreenAnalyzer.GameState = ScreenAnalyzer.GameState.UNKNOWN
        private set
    var battlesWon = 0
        private set
    var battlesLost = 0
        private set
    var lastHpPct = 100
        private set
    var lastMpPct = 100
        private set
    var activeBuffCount = 0
        private set

    // ── Booster settings ──────────────────────────────────────────────────────
    var boosterSettings: BoosterSettings = BoosterSettings()

    // ── Internal state ────────────────────────────────────────────────────────
    private var skillIndex = 0
    private var lastActionMs = 0L
    private var healCooldownMs = 0L
    private var lastBoosterApplyMs = 0L
    private var boosterQueue = mutableListOf<BoosterItem>()
    private var boosterQueueIndex = 0
    private var needsPotions = false
    private var buyTapsRemaining = 0      // how many +10 taps left before confirm
    private var shopStep = 0              // 0=tap category, 1=tap buy btn, 2=done
    private val mapNavigator = MapNavigator()

    // ── Screen positions (normalised) ─────────────────────────────────────────
    private val POS_PLAYER_BTN  = 0.497f to 0.945f
    private val POS_ITEMS_BTN   = 0.694f to 0.958f
    private val POS_NAV_X       = 0.882f to 0.958f

    private val POS_BATTLE_BTN  = 0.499f to 0.570f
    private val POS_CONTINUE    = 0.499f to 0.943f

    private val POS_SKILL1      = 0.251f to 0.807f
    private val POS_ATTACK      = 0.749f to 0.807f
    private val POS_VOLLEY      = 0.251f to 0.885f
    private val POS_SKILL_MENU  = 0.749f to 0.885f
    private val POS_FLEE        = 0.251f to 0.961f
    private val POS_ITEM_BTN    = 0.749f to 0.961f

    // ── Public API ────────────────────────────────────────────────────────────

    fun reset() {
        state = State.HUNTING
        battlesWon = 0; battlesLost = 0; activeBuffCount = 0
        skillIndex = 0; lastActionMs = 0L; healCooldownMs = 0L
        lastBoosterApplyMs = 0L; boosterQueue.clear(); boosterQueueIndex = 0
        needsPotions = false; buyTapsRemaining = 0; shopStep = 0
        mapNavigator.reset()
    }

    fun requestBoosterApply() { lastBoosterApplyMs = 0L }

    // ── Main tick ─────────────────────────────────────────────────────────────

    fun tick(bmp: Bitmap, screenW: Int, screenH: Int): Action {
        val now = System.currentTimeMillis()

        // Generic overlay dismiss (level-up, etc.) — check before state machine
        if (ScreenAnalyzer.needsDismiss(bmp)) {
            lastScreenState = ScreenAnalyzer.GameState.UNKNOWN
            state = State.DISMISSING
            if (now - lastActionMs > 1200) {
                lastActionMs = now
                return tap(0.5f, 0.5f, screenW, screenH)
            }
            return Action.Wait(300)
        }

        lastScreenState = ScreenAnalyzer.detectState(bmp)
        return when (lastScreenState) {

            // ── Victory ──────────────────────────────────────────────────────
            ScreenAnalyzer.GameState.VICTORY -> {
                if (state == State.BATTLE || state == State.PRE_BATTLE
                    || state == State.PRE_BATTLE_BERSERK) battlesWon++
                state = State.VICTORY
                if (now - lastActionMs < 1000) return Action.Wait(300)
                lastActionMs = now
                Log.d(TAG, "Victory → CONTINUE")
                tap(POS_CONTINUE, screenW, screenH)
            }

            // ── Pre-battle popup ──────────────────────────────────────────────
            ScreenAnalyzer.GameState.PRE_BATTLE -> {
                state = State.PRE_BATTLE
                if (now - lastActionMs < 800) return Action.Wait(300)
                lastActionMs = now
                tap(POS_BATTLE_BTN, screenW, screenH)
            }

            ScreenAnalyzer.GameState.PRE_BATTLE_BERSERK -> {
                state = State.PRE_BATTLE_BERSERK
                if (now - lastActionMs < 800) return Action.Wait(300)
                lastActionMs = now
                if (mpSettings.skipBerserk) {
                    Log.d(TAG, "Berserk — skipping")
                    tap(POS_NAV_X, screenW, screenH)
                } else {
                    Log.d(TAG, "Berserk — fighting")
                    tap(POS_BATTLE_BTN, screenW, screenH)
                }
            }

            // ── Active battle ─────────────────────────────────────────────────
            ScreenAnalyzer.GameState.BATTLE -> {
                if (state != State.BATTLE && state != State.BATTLE_USING_ITEM
                    && state != State.BATTLE_FLEEING) {
                    state = State.BATTLE
                    Log.d(TAG, "Entered battle")
                }
                lastHpPct = ScreenAnalyzer.detectHpPercent(bmp)
                lastMpPct = ScreenAnalyzer.detectMpPercent(bmp)

                // Critical HP → flee
                if (fleeHpThreshold > 0 && lastHpPct in 1 until fleeHpThreshold) {
                    if (now - lastActionMs > 1000) {
                        state = State.BATTLE_FLEEING
                        lastActionMs = now
                        Log.d(TAG, "HP critical ($lastHpPct%) → FLEE")
                        return tap(POS_FLEE, screenW, screenH)
                    }
                    return Action.Wait(300)
                }

                // Low HP → open item submenu to heal
                if (battleHealThreshold > 0 && lastHpPct in 1 until battleHealThreshold
                    && state != State.BATTLE_USING_ITEM) {
                    if (now - lastActionMs > 1000) {
                        state = State.BATTLE_USING_ITEM
                        lastActionMs = now
                        Log.d(TAG, "HP low in battle ($lastHpPct%) → ITEM")
                        return tap(POS_ITEM_BTN, screenW, screenH)
                    }
                    return Action.Wait(300)
                }

                // Turn cooldown — wait at least 1.5 s between battle taps
                if (now - lastActionMs < 1500) return Action.Wait(300)
                lastActionMs = now
                state = State.BATTLE
                battleAction(screenW, screenH)
            }

            // ── Flee confirmation ─────────────────────────────────────────────
            ScreenAnalyzer.GameState.FLEE_CONFIRM -> {
                if (now - lastActionMs < 700) return Action.Wait(200)
                lastActionMs = now
                val (yx, yy) = ScreenAnalyzer.fleeYesPos()
                Log.d(TAG, "Flee confirm → YES")
                Action.Tap(yx * screenW, yy * screenH)
            }

            // ── Battle item screen (heal during combat) ───────────────────────
            ScreenAnalyzer.GameState.ITEM_SCREEN -> {
                if (now - lastActionMs < 800) return Action.Wait(300)
                lastActionMs = now
                val item = ScreenAnalyzer.findHealthPotion(bmp)
                if (item != null) {
                    Log.d(TAG, "Using health potion at (${item.x},${item.y}) longPress=${item.needsLongPress}")
                    if (item.needsLongPress)
                        Action.LongPress(item.x * screenW, item.y * screenH, 800)
                    else
                        Action.Tap(item.x * screenW, item.y * screenH)
                } else {
                    // No potions — flag for auto-buy and close screen
                    if (autoBuy) {
                        needsPotions = true
                        Log.d(TAG, "No potions found — flagging needsPotions")
                    }
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    Action.Tap(cx * screenW, cy * screenH)
                }
            }

            // ── World-map 4-col inventory (booster application) ───────────────
            ScreenAnalyzer.GameState.WORLD_MAP_ITEMS -> {
                if (state == State.APPLYING_BOOSTERS) {
                    if (now - lastActionMs < 700) return Action.Wait(300)
                    lastActionMs = now
                    if (boosterQueueIndex < boosterQueue.size) {
                        val item = boosterQueue[boosterQueueIndex++]
                        Log.d(TAG, "Applying booster: ${item.label}")
                        return Action.Tap(item.rx * screenW, item.ry * screenH)
                    }
                    // All boosters tapped — close screen
                    lastBoosterApplyMs = now
                    state = State.HUNTING
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                // Unexpected — close
                if (now - lastActionMs > 600) {
                    lastActionMs = now
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                Action.Wait(300)
            }

            // ── Skill screen — close immediately ─────────────────────────────
            ScreenAnalyzer.GameState.SKILL_SCREEN -> {
                if (now - lastActionMs > 600) {
                    lastActionMs = now
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                Action.Wait(300)
            }

            // ── Shop screen ───────────────────────────────────────────────────
            ScreenAnalyzer.GameState.SHOP_SCREEN -> {
                // Purchase banner = buy completed → close shop
                if (ScreenAnalyzer.hasPurchaseBanner(bmp)) {
                    if (now - lastActionMs < 1200) return Action.Wait(400)
                    needsPotions = false
                    state = State.HUNTING
                    lastActionMs = now
                    Log.d(TAG, "Purchase complete — closing shop")
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }

                if (autoBuy && needsPotions) {
                    state = State.IN_SHOP
                    if (now - lastActionMs < 900) return Action.Wait(300)
                    lastActionMs = now
                    return when (shopStep) {
                        0 -> {
                            // Tap potions category filter
                            shopStep = 1
                            Log.d(TAG, "Shop: tapping potion category")
                            val (cx, cy) = ScreenAnalyzer.shopPotionCategoryPos()
                            Action.Tap(cx * screenW, cy * screenH)
                        }
                        else -> {
                            // Find health potion row and tap BUY
                            val rowY = ScreenAnalyzer.findShopHealthPotionRow(bmp)
                            if (rowY != null) {
                                shopStep = 0
                                val (bx, by) = ScreenAnalyzer.shopBuyBtnPos(rowY)
                                Log.d(TAG, "Shop: tapping BUY at row y=$rowY")
                                buyTapsRemaining = (autoBuyCount / 10).coerceAtLeast(1) - 1
                                Action.Tap(bx * screenW, by * screenH)
                            } else {
                                // Couldn't find potions — close shop
                                Log.d(TAG, "Shop: no health potion row found, closing")
                                state = State.HUNTING
                                val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                                Action.Tap(cx * screenW, cy * screenH)
                            }
                        }
                    }
                }

                // Not in auto-buy mode — close accidental shop open
                if (now - lastActionMs > 700) {
                    lastActionMs = now
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                Action.Wait(300)
            }

            // ── Buy quantity dialog ───────────────────────────────────────────
            ScreenAnalyzer.GameState.BUY_DIALOG -> {
                state = State.IN_BUY_DIALOG
                // Generous delay: let dialog fully render and avoid accidental double-tap
                if (now - lastActionMs < 900) return Action.Wait(300)
                lastActionMs = now
                return if (buyTapsRemaining > 0) {
                    buyTapsRemaining--
                    Log.d(TAG, "Buy dialog: +10 tap ($buyTapsRemaining remaining)")
                    val (px, py) = ScreenAnalyzer.buyDialogPlus10Pos()
                    Action.Tap(px * screenW, py * screenH)
                } else {
                    Log.d(TAG, "Buy dialog: confirming purchase")
                    val (cx, cy) = ScreenAnalyzer.buyDialogConfirmPos()
                    Action.Tap(cx * screenW, cy * screenH)
                }
            }

            // ── Player character screen ───────────────────────────────────────
            ScreenAnalyzer.GameState.PLAYER_SCREEN -> {
                if (state == State.CHECKING_BUFFS) {
                    activeBuffCount = ScreenAnalyzer.detectActiveBuffCount(bmp)
                    Log.d(TAG, "Active buffs: $activeBuffCount")
                    state = State.APPLYING_BOOSTERS
                    lastActionMs = now
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                if (now - lastActionMs > 600) {
                    lastActionMs = now
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                Action.Wait(300)
            }

            // ── Unwanted screen (building tap, etc.) ──────────────────────────
            ScreenAnalyzer.GameState.UNWANTED_SCREEN -> {
                if (now - lastActionMs > 600) {
                    lastActionMs = now
                    Log.d(TAG, "Closing unwanted screen")
                    val (cx, cy) = ScreenAnalyzer.closeButtonPos()
                    return Action.Tap(cx * screenW, cy * screenH)
                }
                Action.Wait(300)
            }

            // ── World map ─────────────────────────────────────────────────────
            ScreenAnalyzer.GameState.WORLD_MAP -> {
                // Detect defeat: left BATTLE without going through VICTORY
                if (state == State.BATTLE || state == State.BATTLE_FLEEING) {
                    battlesLost++
                    Log.d(TAG, "Returned to map from battle — defeat. W:$battlesWon L:$battlesLost")
                    state = State.HUNTING
                }

                // Booster check
                val boosterIntervalMs = boosterSettings.reapplyEvery * 60_000L
                val boostersNeeded = boosterSettings.enabled.isNotEmpty()
                    && (lastBoosterApplyMs == 0L
                        || (boosterIntervalMs > 0 && now - lastBoosterApplyMs > boosterIntervalMs))
                if (boostersNeeded && state != State.APPLYING_BOOSTERS && state != State.CHECKING_BUFFS) {
                    state = State.CHECKING_BUFFS
                    lastActionMs = now
                    Log.d(TAG, "Opening player screen for buff check")
                    return tap(POS_PLAYER_BTN, screenW, screenH)
                }

                // Kick off booster tapping after buff check closed player screen
                if (state == State.APPLYING_BOOSTERS && boosterQueue.isEmpty()) {
                    boosterQueue = boosterSettings.enabled.toMutableList()
                    boosterQueueIndex = 0
                    lastActionMs = now
                    Log.d(TAG, "Opening items screen for ${boosterQueue.size} boosters")
                    return tap(POS_ITEMS_BTN, screenW, screenH)
                }

                // World-map heal
                if (healThreshold > 0 && now - healCooldownMs > 10_000) {
                    val hp = ScreenAnalyzer.detectHpPercent(bmp)
                    lastHpPct = if (hp > 0) hp else lastHpPct
                    if (lastHpPct in 1 until healThreshold) {
                        state = State.HEALING
                        healCooldownMs = now
                        lastActionMs = now
                        Log.d(TAG, "HP low ($lastHpPct%) — long-press ITEMS to heal")
                        return longPress(POS_ITEMS_BTN, screenW, screenH, 800)
                    }
                }

                // Auto-buy: find shop if potions are empty
                if (autoBuy && needsPotions) {
                    if (now - lastActionMs < 1200) return Action.Wait(400)
                    val shopPos = ScreenAnalyzer.findShopBuilding(bmp)
                    if (shopPos != null) {
                        lastActionMs = now
                        state = State.FINDING_SHOP
                        shopStep = 0
                        Log.d(TAG, "Found shop building at ${shopPos.first},${shopPos.second}")
                        return Action.Tap(shopPos.first.toFloat(), shopPos.second.toFloat())
                    } else {
                        // Pan/zoom to reveal map and find a shop
                        lastActionMs = now
                        return when (val nav = mapNavigator.next()) {
                            is MapNavigator.NavAction.Pan -> {
                                val p = nav.pan
                                Log.d(TAG, "Navigating map to find shop")
                                Action.Swipe(
                                    p.fromX * screenW, p.fromY * screenH,
                                    p.toX * screenW, p.toY * screenH,
                                    p.durationMs
                                )
                            }
                            is MapNavigator.NavAction.Zoom -> Action.Nothing
                        }
                    }
                }

                state = State.HUNTING
                if (now - lastActionMs < 1000) return Action.Wait(300)
                lastActionMs = now
                huntAction(bmp, screenW, screenH)
            }

            ScreenAnalyzer.GameState.UNKNOWN -> Action.Nothing
        }
    }

    // ── Battle action ─────────────────────────────────────────────────────────

    private fun battleAction(w: Int, h: Int): Action {
        val pos = when (strategy) {
            BattleStrategy.ATTACK_ONLY  -> POS_ATTACK
            BattleStrategy.VOLLEY_ONLY  -> POS_VOLLEY
            BattleStrategy.SPAM_SKILL_1 -> POS_SKILL1
            BattleStrategy.MP_AWARE -> {
                if (lastMpPct >= mpSettings.threshold) {
                    Log.d(TAG, "MP_AWARE: MP ok ($lastMpPct%) → skill")
                    POS_SKILL1
                } else {
                    Log.d(TAG, "MP_AWARE: low MP ($lastMpPct%) → attack")
                    POS_ATTACK
                }
            }
            BattleStrategy.CYCLE_FREE -> {
                val btn = if (skillIndex % 2 == 0) POS_ATTACK else POS_VOLLEY
                skillIndex++
                btn
            }
        }
        Log.d(TAG, "Battle tap → $pos  strategy=$strategy")
        return tap(pos, w, h)
    }

    // ── Hunt action ───────────────────────────────────────────────────────────

    private fun huntAction(bmp: Bitmap, w: Int, h: Int): Action {
        val monsters = ScreenAnalyzer.findMonsters(bmp)
        return if (monsters.isNotEmpty()) {
            val t = monsters.first()
            Log.d(TAG, "Tapping monster '${t.templateName}' at (${t.x},${t.y}) conf=${t.confidence}")
            Action.Tap(t.x.toFloat(), t.y.toFloat())
        } else {
            Log.d(TAG, "No monsters visible")
            Action.Nothing
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun tap(pos: Pair<Float, Float>, w: Int, h: Int) =
        Action.Tap(pos.first * w, pos.second * h)

    private fun tap(rx: Float, ry: Float, w: Int, h: Int) =
        Action.Tap(rx * w, ry * h)

    private fun longPress(pos: Pair<Float, Float>, w: Int, h: Int, ms: Long) =
        Action.LongPress(pos.first * w, pos.second * h, ms)
}
