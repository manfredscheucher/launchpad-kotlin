package dev.scheucher.launchpad

import javax.sound.midi.MidiDevice
import javax.sound.midi.MidiMessage
import javax.sound.midi.MidiSystem
import javax.sound.midi.Receiver
import javax.sound.midi.ShortMessage
import javax.sound.midi.SysexMessage
import javax.sound.midi.Transmitter

/**
 * JVM (desktop) MIDI transport backed by `javax.sound.midi`.
 *
 * A Launchpad exposes separate input and output ports that share (most of) their device name. We
 * pair them by name so [MidiDeviceInfo.id] identifies a logical device; [open] then opens both the
 * output ([MidiDevice] with a Receiver) and the input (a Transmitter feeding our callback).
 *
 * macOS note: Apple's default MIDI SPI has known SysEx quirks and thread-safety issues in device
 * enumeration. If you hit trouble, drop in the CoreMidi4J provider (uk.co.xfactory-librarians:
 * coremidi4j) — it registers as an SPI and this code needs no changes; its device names are simply
 * prefixed with "CoreMIDI4J - ", which [LaunchpadModel.detect] still matches on the model substring.
 */
actual class MidiTransport actual constructor() {

    private var outDevice: MidiDevice? = null
    private var inDevice: MidiDevice? = null
    private var receiverOut: Receiver? = null
    private var transmitter: Transmitter? = null
    private var onMessage: ((ByteArray) -> Unit)? = null

    actual fun listDevices(): List<MidiDeviceInfo> {
        // Pair a Launchpad's ports by INTERFACE type. Modern Launchpads expose several interfaces
        // (e.g. "MIDI" and "DAW" on the Mini MK3); only the plain MIDI interface accepts Programmer
        // -mode LED control, so DAW/DIN are skipped — matching the digitalfritz driver behaviour.
        val infos = MidiSystem.getMidiDeviceInfo()
        // Group by (base device name, interface type). Value = the output port (we send to it) and
        // whether an input port of the same interface exists.
        data class Group(var out: MidiDevice.Info? = null, var input: MidiDevice.Info? = null, val rawName: String)
        val groups = LinkedHashMap<String, Group>()

        for (info in infos) {
            val name = info.name ?: continue
            if (!looksLikeLaunchpad(name)) continue
            if (interfaceType(name) != InterfaceType.MIDI) continue   // skip DAW / DIN
            val dev = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            val key = normalizeName(name)
            val g = groups.getOrPut(key) { Group(rawName = name) }
            // "In"/receiver = we send to the device; "Out"/transmitter = the device sends to us.
            if (dev.maxReceivers != 0) g.out = info
            if (dev.maxTransmitters != 0) g.input = info
        }

        // We need at least an output (to light LEDs). Input is optional but normally present.
        return groups.entries
            .filter { it.value.out != null }
            .map { (key, g) -> MidiDeviceInfo(id = key, name = g.rawName) }
    }

    actual fun open(deviceId: String) {
        close()
        val infos = MidiSystem.getMidiDeviceInfo()
        var outInfo: MidiDevice.Info? = null
        var inInfo: MidiDevice.Info? = null
        for (info in infos) {
            val name = info.name ?: continue
            if (!looksLikeLaunchpad(name)) continue
            if (interfaceType(name) != InterfaceType.MIDI) continue
            if (normalizeName(name) != deviceId) continue
            val dev = runCatching { MidiSystem.getMidiDevice(info) }.getOrNull() ?: continue
            if (dev.maxReceivers != 0 && outInfo == null) outInfo = info
            if (dev.maxTransmitters != 0 && inInfo == null) inInfo = info
        }
        requireNotNull(outInfo) { "No MIDI output port found for device '$deviceId'" }

        val out = MidiSystem.getMidiDevice(outInfo)
        out.open()
        receiverOut = out.receiver
        outDevice = out

        // Input is optional (we can still light the board without it), but normally present.
        inInfo?.let { info ->
            val input = MidiSystem.getMidiDevice(info)
            input.open()
            val tx = input.transmitter
            tx.receiver = object : Receiver {
                override fun send(message: MidiMessage, timeStamp: Long) {
                    onMessage?.invoke(message.message.copyOf(message.length))
                }
                override fun close() {}
            }
            transmitter = tx
            inDevice = input
        }
    }

    actual fun isOpen(): Boolean = outDevice?.isOpen == true

    actual fun send(message: ByteArray) {
        val rx = receiverOut ?: error("Transport not open")
        val first = if (message.isNotEmpty()) message[0].toInt() and 0xFF else 0
        val msg: MidiMessage = if (first == 0xF0) {
            SysexMessage().apply { setMessage(message, message.size) }
        } else {
            // Short channel message (note-on / control-change): status + up to 2 data bytes.
            val status = first
            val d1 = if (message.size > 1) message[1].toInt() and 0x7F else 0
            val d2 = if (message.size > 2) message[2].toInt() and 0x7F else 0
            ShortMessage().apply { setMessage(status, d1, d2) }
        }
        rx.send(msg, -1)
        // javax.sound.midi delivers asynchronously; bursting 64 note-ons back-to-back (a full board
        // repaint) can drop or reorder messages on some MIDI stacks, leaving pads the wrong colour.
        // A sub-millisecond pace makes bulk repaints reliable without a visible delay. (RtMidi sends
        // synchronously, so the C++ port needs no such pacing.)
        runCatching { Thread.sleep(0, 400_000) } // 0.4 ms
    }

    actual fun setReceiver(onMessage: ((ByteArray) -> Unit)?) { this.onMessage = onMessage }

    actual fun close() {
        // Let any just-sent messages (e.g. the clear + return-to-Live-mode on disconnect) flush to
        // the device before we tear the port down — otherwise closing races the send and the board
        // can be left lit. javax.sound.midi has no explicit flush, so a brief settle is the pragmatic
        // fix (RtMidi sends synchronously, which is why the C++ port doesn't need this).
        runCatching { Thread.sleep(60) }
        runCatching { transmitter?.close() }
        runCatching { receiverOut?.close() }
        runCatching { inDevice?.close() }
        runCatching { outDevice?.close() }
        transmitter = null; receiverOut = null; inDevice = null; outDevice = null
    }

    private enum class InterfaceType { MIDI, DAW, DIN, OTHER }

    private companion object {
        fun looksLikeLaunchpad(name: String) = name.lowercase().let {
            it.contains("launchpad") || it.contains("lpmini") || it.contains("lppro") || it.contains("lpx")
        }

        /**
         * Classify a port by its interface. Only [InterfaceType.MIDI] accepts Programmer-mode LED
         * control; DAW drives Session mode and DIN is the physical 5-pin breakout — both are skipped.
         * DIN is checked before MIDI because DIN names can also contain "MIDI".
         */
        fun interfaceType(name: String): InterfaceType {
            val n = name.lowercase()
            return when {
                n.contains("din") -> InterfaceType.DIN
                n.contains("daw") -> InterfaceType.DAW
                n.contains("midi") -> InterfaceType.MIDI
                else -> InterfaceType.OTHER
            }
        }

        // Collapse the two ports of one interface to a single key by stripping the CoreMIDI4J prefix,
        // the interface word (MIDI/DAW/DIN) and the direction (In/Out).
        fun normalizeName(name: String): String = name
            .replace("CoreMIDI4J - ", "")
            .replace(Regex("\\s*(DAW|MIDI|DIN)?\\s*(In|Out|Input|Output)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
