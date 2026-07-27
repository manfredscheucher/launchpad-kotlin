package dev.scheucher.launchpad

/**
 * A hardware-free [MidiTransport] for tests, demos and simulation. Drive the WHOLE real Launchpad
 * stack — [Launchpad], [LaunchpadProtocol] encode/decode, the [LaunchpadListener] path — without any
 * physical device or MIDI subsystem. Pass one to [Launchpad]:
 *
 * ```
 * val fake = FakeMidiTransport()
 * val lp = Launchpad(fake)
 * lp.connect(fake.listDevices().first())
 * lp.setPad(Pad(0, 0), LpColor.ofPalette(5))     // recorded; fake.colorAt(Pad(0,0)) == 5
 * fake.emitPad(Pad(0, 0), pressed = true)         // arrives via the real decoder → listener.onPad
 * ```
 *
 * OUTPUT: every raw frame the library sends is captured in [sent]; [ledWrites]/[colorAt] decode the
 * frames back to (pad → palette colour) so a test can assert what the board WOULD show — covering
 * BOTH the per-pad note-on path ([Launchpad.setPad]) and the batched palette-SysEx path
 * ([Launchpad.renderBatched]).
 * INPUT: [emitPad]/[emitButton] encode a real MIDI message and feed it through the registered
 * receiver, so it is decoded by the same [LaunchpadProtocol.decode] the hardware path uses.
 *
 * The simulated device defaults to a Launchpad Mini MK3; override via the constructor for other models.
 */
class FakeMidiTransport(
    private val device: MidiDeviceInfo = MidiDeviceInfo(
        id = "fake-launchpad",
        name = "Fake Launchpad Mini MK3",
        model = LaunchpadModel.MINI_MK3,
    ),
) : MidiTransport {

    private val model: LaunchpadModel = device.model ?: LaunchpadModel.MINI_MK3
    private var open = false
    private var receiver: ((ByteArray) -> Unit)? = null

    /** Every raw frame the library sent, in order (short note-on messages and palette SysEx frames). */
    val sent = mutableListOf<ByteArray>()

    override fun listDevices(): List<MidiDeviceInfo> = listOf(device)

    override fun open(deviceId: String) {
        require(deviceId == device.id) { "Fake transport has no device '$deviceId'" }
        open = true
    }

    override fun isOpen(): Boolean = open

    override fun send(message: ByteArray) {
        check(open) { "Fake transport not open" }
        sent += message.copyOf()
    }

    override fun setReceiver(onMessage: ((ByteArray) -> Unit)?) { receiver = onMessage }

    override fun close() {
        open = false
        receiver = null
    }

    // ---- simulation input -----------------------------------------------------------------------

    /** Simulate the physical device sending a pad press/release, decoded via the real protocol. A
     *  press is a note-on (velocity 127), a release a note-on with velocity 0 — exactly what hardware
     *  sends, so [LaunchpadProtocol.decode] turns it back into a [LaunchpadEvent.PadEvent]. */
    fun emitPad(pad: Pad, pressed: Boolean) {
        val frame = LaunchpadProtocol.padNoteOn(pad, if (pressed) 127 else 0)
        receiver?.invoke(frame)
    }

    /** Simulate a top/side control-button press/release, decoded via the real protocol. */
    fun emitButton(button: Button, pressed: Boolean) {
        val frame = LaunchpadProtocol.buttonCc(model, button, if (pressed) 127 else 0) ?: return
        receiver?.invoke(frame)
    }

    // ---- simulation output ----------------------------------------------------------------------

    /** Clear the recorded output (e.g. between assertion phases). */
    fun clearSent() = sent.clear()

    /** Decode every sent frame into the (pad, palette colour) LED writes it represents, in order. */
    fun ledWrites(): List<Pair<Pad, Int>> = sent.flatMap { decodeLeds(it) }

    /** The last palette colour written to [pad] across all sent frames, or null if never lit. */
    fun colorAt(pad: Pad): Int? = ledWrites().lastOrNull { it.first == pad }?.second

    // Decode a sent frame into pad→colour writes: either a single Ch1..Ch3 note-on (0x9n note vel),
    // or a batched palette SysEx (F0 .. 03 [type index colour ...] F7 — see ledSysexPalette).
    private fun decodeLeds(frame: ByteArray): List<Pair<Pad, Int>> {
        if (frame.isEmpty()) return emptyList()
        val status = frame[0].toInt() and 0xFF
        // Note-on on channels 1..3 (static/flash/pulse) → one pad.
        if (status in 0x90..0x92 && frame.size >= 3) {
            val note = frame[1].toInt() and 0x7F
            val vel = frame[2].toInt() and 0x7F
            val pad = LaunchpadProtocol.padForNote(note) ?: return emptyList()
            return listOf(pad to vel)
        }
        // Batched palette SysEx: find the 0x03 "spec" marker, then walk (type, index, colour[, alt]).
        if (status == LaunchpadProtocol.SYSEX_START) {
            val bytes = frame.map { it.toInt() and 0xFF }
            val specAt = bytes.indexOf(0x03)
            if (specAt < 0) return emptyList()
            val out = mutableListOf<Pair<Pad, Int>>()
            var i = specAt + 1
            while (i + 2 < bytes.size && bytes[i] != LaunchpadProtocol.SYSEX_END) {
                val type = bytes[i]
                val index = bytes[i + 1]
                val pad = LaunchpadProtocol.padForNote(index)
                when (type) {
                    0, 2 -> { // static / pulsing: colour is next byte
                        val color = bytes[i + 2]
                        if (pad != null) out += pad to color
                        i += 3
                    }
                    1 -> { // flashing: alt then colour (two bytes)
                        val color = if (i + 3 < bytes.size) bytes[i + 3] else bytes[i + 2]
                        if (pad != null) out += pad to color
                        i += 4
                    }
                    else -> i += 3
                }
            }
            return out
        }
        return emptyList()
    }
}
