package dev.scheucher.launchpad

/**
 * Core, transport-agnostic domain model for a Novation Launchpad in **Programmer mode**.
 *
 * Nothing in this file knows about MIDI bytes — that lives in [LaunchpadProtocol] and the
 * per-platform [MidiTransport] implementations. This layer is what applications program against.
 */

/**
 * A position on the 8x8 grid. [x] is the column 0..7 (left to right), [y] the row 0..7 counted
 * **from the bottom** (0 = bottom row, 7 = top row).
 *
 * The bottom-up convention matches the Launchpad's own Programmer-mode note numbering (note 11 is
 * the bottom-left pad) and lets a chess board map directly: file -> [x], rank -> [y].
 */
data class Pad(val x: Int, val y: Int) {
    init {
        require(x in 0..7) { "Pad x out of range: $x" }
        require(y in 0..7) { "Pad y out of range: $y" }
    }
}

/**
 * The non-grid buttons around the edge. Programmer mode reports these as Control Change messages;
 * the concrete CC number depends on the device model (see [LaunchpadModel]).
 */
enum class Button {
    UP, DOWN, LEFT, RIGHT,
    SESSION, DRUMS, KEYS, USER, // top-row mode buttons (naming per Mini MK3)
    LOGO,                       // the round Novation logo LED (top-right on some models)
    // Right-hand "scene launch" column, top (SCENE_0) to bottom (SCENE_7).
    SCENE_0, SCENE_1, SCENE_2, SCENE_3, SCENE_4, SCENE_5, SCENE_6, SCENE_7,
}

/**
 * An RGB colour with each channel in the Launchpad's native 0..127 range (NOT 0..255).
 * The device drives its LEDs with 7-bit per-channel values.
 */
/**
 * A Launchpad colour. Two ways to specify it:
 *
 *  - **[palette]** set (0..127): use that exact entry from the device's built-in colour palette.
 *    This is the reliable path (plain Note-On velocity) and what the chess colour scheme uses so the
 *    12 piece tones are precise and predictable.
 *  - **[palette] null**: an RGB triple (0..127 each). Used with the RGB-SysEx path, or mapped to the
 *    nearest palette entry when sent as Note-On.
 *
 * [paletteIndex] always returns a usable 0..127 velocity: the explicit index if set, else the
 * nearest palette match for the RGB values.
 */
data class LpColor(val r: Int, val g: Int, val b: Int, val palette: Int? = null) {
    init {
        require(r in 0..127) { "r out of range: $r" }
        require(g in 0..127) { "g out of range: $g" }
        require(b in 0..127) { "b out of range: $b" }
        require(palette == null || palette in 0..127) { "palette out of range: $palette" }
    }

    /** The palette velocity to use for a Note-On: explicit index, or nearest-RGB fallback. */
    val paletteIndex: Int get() = palette ?: PaletteMap.nearest(this)

    companion object {
        val OFF = LpColor(0, 0, 0, palette = 0)
        val WHITE = LpColor(127, 127, 127, palette = 3)
        val RED = LpColor(127, 0, 0, palette = 5)
        val GREEN = LpColor(0, 127, 0, palette = 21)
        val BLUE = LpColor(0, 0, 127, palette = 45)

        /** A colour that is exactly the given palette entry (0..127). RGB is approximate metadata. */
        fun ofPalette(index: Int): LpColor = LpColor(0, 0, 0, palette = index)

        /** Convenience: build from ordinary 0..255 RGB (e.g. a UI colour), scaling down to 0..127. */
        fun fromRgb888(r: Int, g: Int, b: Int): LpColor =
            LpColor(r * 127 / 255, g * 127 / 255, b * 127 / 255)
    }
}

/**
 * How a target should be lit. STATIC is a plain colour; FLASHING alternates with a second colour
 * on the device's beat clock; PULSING breathes between dim and full. All three are supported in
 * Programmer mode (channels 1/2/3 for palette; the LED-SysEx encodes the type explicitly).
 */
sealed interface Lighting {
    data class Static(val color: LpColor) : Lighting
    /** Alternates between [color] and [alt] on the device beat clock. */
    data class Flashing(val color: LpColor, val alt: LpColor = LpColor.OFF) : Lighting
    data class Pulsing(val color: LpColor) : Lighting
}

/** A single "light this target to this state" instruction, batched into one SysEx frame. */
data class LedInstruction(val ledIndex: Int, val lighting: Lighting)

/** An input event coming back from the device (a pad or button press/release). */
sealed interface LaunchpadEvent {
    val pressed: Boolean
    data class PadEvent(val pad: Pad, override val pressed: Boolean) : LaunchpadEvent
    data class ButtonEvent(val button: Button, override val pressed: Boolean) : LaunchpadEvent
}
