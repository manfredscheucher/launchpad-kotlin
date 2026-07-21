package dev.scheucher.launchpad

/**
 * High-level, transport-agnostic entry point for driving a single Launchpad.
 *
 * Design mirrors the well-known LP4J split: a [Launchpad] owns the connection; you send commands
 * through it (set pad/button lights) and receive input by registering a [LaunchpadListener]. All
 * the MIDI/SysEx detail is hidden behind [MidiProtocol]-encoded frames sent over a [MidiTransport].
 *
 * Typical use:
 * ```
 * val lp = Launchpad()
 * val devices = lp.available()
 * lp.connect(devices.first())                 // enters Programmer mode
 * lp.setListener(object : LaunchpadListener {
 *     override fun onPad(pad: Pad, pressed: Boolean) { ... }
 * })
 * lp.render(listOf(LedInstruction(LaunchpadProtocol.noteFor(Pad(0,0)), Lighting.Static(LpColor.RED))))
 * ...
 * lp.disconnect()                             // restores Live mode + clears LEDs
 * ```
 */
class Launchpad(private val transport: MidiTransport = MidiTransport()) {

    var model: LaunchpadModel = LaunchpadModel.MINI_MK3
        private set

    private var listener: LaunchpadListener? = null

    /** All connected devices that look like a Launchpad. */
    fun available(): List<MidiDeviceInfo> = transport.listDevices()

    val isConnected: Boolean get() = transport.isOpen()

    /**
     * Open [device] and switch it into Programmer mode. If the device's model couldn't be detected
     * from its name, [fallbackModel] is assumed.
     */
    fun connect(device: MidiDeviceInfo, fallbackModel: LaunchpadModel = LaunchpadModel.MINI_MK3) {
        model = device.model ?: fallbackModel
        transport.open(device.id)
        transport.setReceiver { raw -> handleIncoming(raw) }
        transport.send(LaunchpadProtocol.enterProgrammerMode(model))
        clear()
    }

    /** Convenience: connect to the first available Launchpad, or return false if none is present. */
    fun connectFirst(): Boolean {
        val d = available().firstOrNull() ?: return false
        connect(d)
        return true
    }

    /**
     * Connect to the device whose [MidiDeviceInfo.id] equals [deviceId] (e.g. a serial persisted
     * from a previous session). Returns false if no currently-connected device matches.
     */
    fun connectTo(deviceId: String): Boolean {
        val d = available().firstOrNull { it.id == deviceId } ?: return false
        connect(d)
        return true
    }

    fun setListener(listener: LaunchpadListener?) { this.listener = listener }

    /**
     * Repaint a set of pads. Each pad is lit with a per-pad Note-On carrying a palette velocity —
     * the exact mechanism the (proven) digitalfritz driver uses, which is reliable across MIDI
     * stacks. Static/Flashing/Pulsing map to Note-On channels 1/2/3 respectively.
     *
     * (The batched RGB-SysEx path still exists in [LaunchpadProtocol.ledSysex] for callers that have
     * confirmed it works on their setup, but Note-On is the default because it just works.)
     */
    fun render(instructions: List<LedInstruction>) {
        for (ins in instructions) {
            val pad = LaunchpadProtocol.padForNote(ins.ledIndex) ?: continue
            transport.send(paletteNoteOn(pad, ins.lighting))
        }
    }

    /**
     * Repaint a set of pads in ONE batched palette-SysEx frame (see [LaunchpadProtocol.ledSysexPalette]).
     * Preferred for full-board repaints: a single USB write can't drop/reorder pads the way a burst of
     * 64 individual Note-Ons can on some MIDI stacks, and it paints every pad (including the ones set to
     * "off") so no stale/boot pattern survives underneath. Falls back to nothing if not connected.
     */
    fun renderBatched(instructions: List<LedInstruction>) {
        if (instructions.isEmpty()) return
        transport.send(LaunchpadProtocol.ledSysexPalette(model, instructions))
    }

    /** Light a single grid pad. */
    fun setPad(pad: Pad, lighting: Lighting) {
        transport.send(paletteNoteOn(pad, lighting))
    }

    fun setPad(pad: Pad, color: LpColor) = setPad(pad, Lighting.Static(color))

    /** Light an edge button by palette velocity (edge buttons are Control-Change, not Note-On). */
    fun setButton(button: Button, velocity: Int) {
        LaunchpadProtocol.buttonCc(model, button, velocity)?.let { transport.send(it) }
    }

    // Build a Note-On for [pad] from a [Lighting], choosing the channel (static/flash/pulse) and the
    // palette velocity nearest to the requested colour.
    private fun paletteNoteOn(pad: Pad, lighting: Lighting): ByteArray = when (lighting) {
        is Lighting.Static -> LaunchpadProtocol.padNoteOn(pad, lighting.color.paletteIndex, LaunchpadProtocol.NoteMode.STATIC)
        is Lighting.Pulsing -> LaunchpadProtocol.padNoteOn(pad, lighting.color.paletteIndex, LaunchpadProtocol.NoteMode.PULSE)
        is Lighting.Flashing -> LaunchpadProtocol.padNoteOn(pad, lighting.color.paletteIndex, LaunchpadProtocol.NoteMode.FLASH)
    }

    /** Turn every grid pad off (Note-On velocity 0), exactly like the digitalfritz clearAllLEDs. */
    fun clear() {
        for (y in 0..7) for (x in 0..7)
            transport.send(LaunchpadProtocol.padNoteOn(Pad(x, y), 0))
    }

    /**
     * Light the whole grid with a single palette [color] — a visual confirmation that this is the
     * connected device (as digitalfritz's "test device" does). The caller decides how long to leave
     * it lit and what to draw afterwards; this method only paints, so it stays free of any timing/
     * threading assumptions that don't belong in commonMain.
     */
    fun flashAll(color: LpColor = LpColor.WHITE) {
        for (y in 0..7) for (x in 0..7)
            transport.send(LaunchpadProtocol.padNoteOn(Pad(x, y), color.paletteIndex))
    }

    /** Restore normal device operation, clear LEDs, and close the connection. */
    fun disconnect() {
        if (transport.isOpen()) {
            runCatching { clear() }
            runCatching { transport.send(LaunchpadProtocol.enterLiveMode(model)) }
        }
        transport.setReceiver(null)
        transport.close()
    }

    private fun handleIncoming(raw: ByteArray) {
        when (val ev = LaunchpadProtocol.decode(model, raw)) {
            is LaunchpadEvent.PadEvent -> listener?.onPad(ev.pad, ev.pressed)
            is LaunchpadEvent.ButtonEvent -> listener?.onButton(ev.button, ev.pressed)
            null -> {}
        }
    }
}

/** Callback for device input. Override only what you need; defaults are no-ops. */
interface LaunchpadListener {
    fun onPad(pad: Pad, pressed: Boolean) {}
    fun onButton(button: Button, pressed: Boolean) {}
}
