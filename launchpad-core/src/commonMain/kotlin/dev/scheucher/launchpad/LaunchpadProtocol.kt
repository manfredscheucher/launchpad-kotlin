package dev.scheucher.launchpad

/**
 * Pure, side-effect-free encoding/decoding of the Launchpad **Programmer mode** MIDI protocol.
 * Every function returns or parses raw MIDI bytes; nothing here touches hardware. This is the one
 * place that has to match the Novation Programmer's Reference manual, so it is small and testable.
 *
 * References (Launchpad Mini [MK3] / Pro [MK3] / X programmer's reference):
 *  - SysEx header: F0 00 20 29 02 <productId> ...
 *  - Enter Programmer mode: header + 0E 01 F7  (0E 00 = back to Live/standalone)
 *  - Grid pads: notes 11..88, value = row*10 + col (1-indexed), pad(0,0) = bottom-left = note 11
 *  - Static colour by palette: 90 <note> <velocity(0..127)>   (Ch1=static, Ch2=flash, Ch3=pulse)
 *  - LED lighting SysEx: header + 03 <colourspec...> F7, each colourspec =
 *        <type> <ledIndex> <data...>   type 0=palette(1B) 1=flash(2B) 2=pulse(1B) 3=RGB(3B, 0..127)
 */
object LaunchpadProtocol {

    const val SYSEX_START = 0xF0
    const val SYSEX_END = 0xF7
    // Novation manufacturer id + the fixed 02 that precedes the product id.
    private val HEADER_PREFIX = intArrayOf(0x00, 0x20, 0x29, 0x02)

    private const val NOTE_ON_CH1 = 0x90   // static colour
    private const val NOTE_ON_CH2 = 0x91   // flashing colour
    private const val NOTE_ON_CH3 = 0x92   // pulsing colour
    private const val CC_CH1 = 0xB0        // control-change buttons, static colour

    // ---- SysEx header ----------------------------------------------------------------------

    private fun header(model: LaunchpadModel): IntArray =
        intArrayOf(SYSEX_START) + HEADER_PREFIX + intArrayOf(model.productId)

    /** SysEx to switch a device into Programmer mode (full external LED control). */
    fun enterProgrammerMode(model: LaunchpadModel): ByteArray =
        (header(model) + intArrayOf(0x0E, 0x01, SYSEX_END)).toBytes()

    /** SysEx to return the device to normal (Live/standalone) operation. Call this on shutdown. */
    fun enterLiveMode(model: LaunchpadModel): ByteArray =
        (header(model) + intArrayOf(0x0E, 0x00, SYSEX_END)).toBytes()

    /** Universal Device Inquiry — asks any Launchpad to identify itself. Model-independent. */
    fun deviceInquiry(): ByteArray =
        intArrayOf(SYSEX_START, 0x7E, 0x7F, 0x06, 0x01, SYSEX_END).toBytes()

    // ---- Grid pad <-> note mapping ---------------------------------------------------------

    /** Programmer-mode note number for a grid [pad]: (row+1)*10 + (col+1), bottom-left = 11. */
    fun noteFor(pad: Pad): Int = (pad.y + 1) * 10 + (pad.x + 1)

    /** Inverse of [noteFor]: the grid pad for a note in 11..88, or null if it's not a grid pad. */
    fun padForNote(note: Int): Pad? {
        val col = note % 10 - 1
        val row = note / 10 - 1
        return if (col in 0..7 && row in 0..7) Pad(col, row) else null
    }

    // ---- LED control: one batched SysEx frame ----------------------------------------------

    /**
     * Encode a batch of [instructions] into a single "LED lighting" SysEx frame (command 0x03).
     * Preferred over per-pad note-on messages: one USB write repaints the whole board with no
     * visible tearing. Up to 81 colourspecs fit in one frame; callers with more should chunk.
     */
    fun ledSysex(model: LaunchpadModel, instructions: List<LedInstruction>): ByteArray {
        val body = ArrayList<Int>(instructions.size * 6)
        body.addAll(header(model).asList())
        body.add(0x03)
        for (ins in instructions) {
            // colourspec = <type> <ledIndex> <data...>
            when (val l = ins.lighting) {
                // Static uses RGB (type 3) for full-colour control; palette type 0 is 1-byte only.
                is Lighting.Static -> { body.add(3); body.add(ins.ledIndex); body.addRgb(l.color) }
                // Pulsing (type 2) is palette-indexed per the manual (1 data byte).
                is Lighting.Pulsing -> { body.add(2); body.add(ins.ledIndex); body.add(PaletteMap.nearest(l.color)) }
                // Flashing (type 1) is palette-only: 2 data bytes = colour B (alt) then colour A.
                is Lighting.Flashing -> {
                    body.add(1); body.add(ins.ledIndex)
                    body.add(PaletteMap.nearest(l.alt))
                    body.add(PaletteMap.nearest(l.color))
                }
            }
        }
        body.add(SYSEX_END)
        return body.toBytes()
    }

    // RGB data bytes (0..127 per channel). The type byte (3) is written by the caller.
    private fun MutableList<Int>.addRgb(c: LpColor) { add(c.r); add(c.g); add(c.b) }

    /**
     * Encode a batch of [instructions] into a single "LED lighting" SysEx frame (command 0x03) using
     * the PALETTE colour types (0=static, 1=flash, 2=pulse) — one data byte per pad. This is the
     * reliable batched path: it repaints the whole board in ONE USB write (no per-pad Note-On burst
     * that some MIDI stacks drop/reorder) AND it uses palette entries (unlike [ledSysex], whose RGB
     * type 3 did not light LEDs on all hosts). Static pads carry the palette index directly.
     *
     * A frame holds at most ~81 colourspecs (each 1 type + 1 index + 1 data = 3 bytes); a full 64-pad
     * board fits comfortably. Callers with more should chunk.
     */
    fun ledSysexPalette(model: LaunchpadModel, instructions: List<LedInstruction>): ByteArray {
        val body = ArrayList<Int>(instructions.size * 3 + 8)
        body.addAll(header(model).asList())
        body.add(0x03)
        for (ins in instructions) {
            when (val l = ins.lighting) {
                is Lighting.Static -> { body.add(0); body.add(ins.ledIndex); body.add(l.color.paletteIndex) }
                is Lighting.Pulsing -> { body.add(2); body.add(ins.ledIndex); body.add(l.color.paletteIndex) }
                is Lighting.Flashing -> {
                    body.add(1); body.add(ins.ledIndex)
                    body.add(l.alt.paletteIndex)
                    body.add(l.color.paletteIndex)
                }
            }
        }
        body.add(SYSEX_END)
        return body.toBytes()
    }

    // ---- Alternative: simple palette note-on (used for edge buttons via CC) -----------------

    /** Light an edge [button] to a palette [velocity] (0 = off) using a Control-Change message. */
    fun buttonCc(model: LaunchpadModel, button: Button, velocity: Int): ByteArray? {
        val cc = model.ccFor(button) ?: return null
        return intArrayOf(CC_CH1, cc, velocity.coerceIn(0, 127)).toBytes()
    }

    /** Light a single grid pad by palette index via note-on (Ch1 static / Ch2 flash / Ch3 pulse). */
    fun padNoteOn(pad: Pad, velocity: Int, mode: NoteMode = NoteMode.STATIC): ByteArray {
        val status = when (mode) {
            NoteMode.STATIC -> NOTE_ON_CH1; NoteMode.FLASH -> NOTE_ON_CH2; NoteMode.PULSE -> NOTE_ON_CH3
        }
        return intArrayOf(status, noteFor(pad), velocity.coerceIn(0, 127)).toBytes()
    }

    enum class NoteMode { STATIC, FLASH, PULSE }

    // ---- Input decoding --------------------------------------------------------------------

    /**
     * Decode an incoming MIDI message from the device into a [LaunchpadEvent], or null if it isn't
     * a pad/button event we care about. Note-on with velocity 0 is treated as a release.
     */
    fun decode(model: LaunchpadModel, data: ByteArray): LaunchpadEvent? {
        if (data.size < 3) return null
        val status = data[0].toInt() and 0xFF
        val d1 = data[1].toInt() and 0x7F
        val d2 = data[2].toInt() and 0x7F
        return when (status and 0xF0) {
            0x90 -> { // note on/off -> grid pad
                val pad = padForNote(d1) ?: return null
                LaunchpadEvent.PadEvent(pad, pressed = d2 > 0)
            }
            0x80 -> padForNote(d1)?.let { LaunchpadEvent.PadEvent(it, pressed = false) }
            0xB0 -> { // control change -> edge button
                val btn = model.buttonForCc(d1) ?: return null
                LaunchpadEvent.ButtonEvent(btn, pressed = d2 > 0)
            }
            else -> null
        }
    }
}

private fun IntArray.toBytes(): ByteArray = ByteArray(size) { this[it].toByte() }
private fun List<Int>.toBytes(): ByteArray = ByteArray(size) { this[it].toByte() }
