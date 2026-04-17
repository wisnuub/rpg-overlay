package com.orna.autobattle

/**
 * Which buttons to prefer during a battle turn.
 *
 * MP_AWARE: use free actions (Attack / Volley) by default; switch to the
 * mana-skill slot when the mana bar is above [MpSettings.threshold].
 */
enum class BattleStrategy(val label: String) {
    ATTACK_ONLY("Attack Only"),
    VOLLEY_ONLY("Volley Only (free)"),
    MP_AWARE("Smart (MP-aware)"),
    SPAM_SKILL_1("Spam Skill 1"),
    CYCLE_FREE("Cycle Attack + Volley");

    companion object {
        fun labels() = values().map { it.label }.toTypedArray()
        fun fromIndex(i: Int) = values().getOrElse(i) { ATTACK_ONLY }
    }
}

/** Settings for the MP-aware strategy. */
data class MpSettings(
    /** 0–100. Use mana skills only when mana bar % is above this. */
    val threshold: Int = 30,
    /** If true, flee berserk monsters instead of fighting them. */
    val skipBerserk: Boolean = false
)
