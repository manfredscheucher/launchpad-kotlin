package dev.scheucher.launchpad

/**
 * Per-device-model constants: the Novation SysEx header, the "enter Programmer mode" command, and
 * the Control-Change numbers for the edge buttons. Grid pads share the same note layout across all
 * MK3-era models (Mini MK3, Pro, Pro MK3, X), so that lives in [LaunchpadProtocol].
 *
 * Values are taken from the official Novation Programmer's Reference manuals. The manufacturer ID
 * (00 20 29) and header prefix (F0 00 20 29 02 <productId>) are common; only the product id and a
 * couple of button CCs differ.
 */
enum class LaunchpadModel(
    /** Product ID byte inside the SysEx header (after 00 20 29 02). */
    val productId: Int,
    /** Substrings we look for in the MIDI port name to auto-detect this model. */
    val nameMatchers: List<String>,
) {
    MINI_MK3(0x0D, listOf("Launchpad Mini MK3", "LPMiniMK3", "MiniMK3")) {
        override val up = 91; override val down = 92; override val left = 93; override val right = 94
    },
    // The original Launchpad Pro uses a different header/command (Standalone "Live" mode) and
    // different nav CCs; kept here for completeness. Pro MK3 behaves like the Mini's Programmer mode.
    PRO(0x10, listOf("Launchpad Pro", "LPPro")) {
        override val up = 80; override val down = 70; override val left = 91; override val right = 92
    },
    PRO_MK3(0x0E, listOf("Launchpad Pro MK3", "LPProMK3", "ProMK3")) {
        override val up = 91; override val down = 92; override val left = 93; override val right = 94
    },
    X(0x0C, listOf("Launchpad X", "LPX")) {
        override val up = 91; override val down = 92; override val left = 93; override val right = 94
    };

    // Nav buttons vary by model.
    abstract val up: Int
    abstract val down: Int
    abstract val left: Int
    abstract val right: Int

    // Common top-row / side CCs (identical across MK3 models per the reference manuals).
    open val session = 95
    open val drums = 96
    open val keys = 97
    open val user = 98
    open val logo = 99

    /** The 8 right-hand scene-launch buttons, top (index 0) to bottom (index 7). */
    val sceneCCs: IntArray get() = intArrayOf(89, 79, 69, 59, 49, 39, 29, 19)

    /** CC number for a given [Button], or null if this model doesn't expose it as a CC. */
    fun ccFor(button: Button): Int? = when (button) {
        Button.UP -> up; Button.DOWN -> down; Button.LEFT -> left; Button.RIGHT -> right
        Button.SESSION -> session; Button.DRUMS -> drums; Button.KEYS -> keys; Button.USER -> user
        Button.LOGO -> logo
        Button.SCENE_0 -> sceneCCs[0]; Button.SCENE_1 -> sceneCCs[1]; Button.SCENE_2 -> sceneCCs[2]
        Button.SCENE_3 -> sceneCCs[3]; Button.SCENE_4 -> sceneCCs[4]; Button.SCENE_5 -> sceneCCs[5]
        Button.SCENE_6 -> sceneCCs[6]; Button.SCENE_7 -> sceneCCs[7]
    }

    /** Reverse: which [Button] does this CC number correspond to (for input decoding)? */
    fun buttonForCc(cc: Int): Button? = Button.entries.firstOrNull { ccFor(it) == cc }

    companion object {
        /** Best-effort model detection from a MIDI port display name. */
        fun detect(portName: String): LaunchpadModel? {
            val n = portName.lowercase()
            // Check more specific names first (Pro MK3 before Pro; X is distinct).
            return listOf(PRO_MK3, X, MINI_MK3, PRO).firstOrNull { model ->
                model.nameMatchers.any { n.contains(it.lowercase()) }
            }
        }
    }
}
