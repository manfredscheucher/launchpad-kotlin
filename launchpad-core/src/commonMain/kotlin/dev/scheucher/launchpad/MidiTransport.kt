package dev.scheucher.launchpad

/**
 * A discovered MIDI device pairing (an input port + an output port that belong to the same physical
 * Launchpad). [id] is a stable, platform-specific handle used to open the device; [name] is for
 * display and model detection.
 */
data class MidiDeviceInfo(
    val id: String,
    val name: String,
    val model: LaunchpadModel? = LaunchpadModel.detect(name),
)

/**
 * The single platform seam. Each target (JVM via javax.sound.midi, Android via MidiManager, later
 * native via RtMidi) provides an `actual` implementation. Everything above this — protocol, colour
 * coding, chess rendering — is common Kotlin.
 *
 * Implementations are expected to be usable from a single thread for sending; [setReceiver] may be
 * invoked from a MIDI callback thread, so consumers should hand off to their own dispatcher.
 */
expect class MidiTransport() {
    /** Enumerate connected Launchpad-capable devices (both an input and output port present). */
    fun listDevices(): List<MidiDeviceInfo>

    /** Open the device with the given [MidiDeviceInfo.id]. Throws on failure. */
    fun open(deviceId: String)

    /** True once [open] succeeded and the ports are ready. */
    fun isOpen(): Boolean

    /** Send a raw MIDI message (short message or full SysEx frame incl. F0..F7). */
    fun send(message: ByteArray)

    /** Register a callback for incoming MIDI messages. Pass null to clear. */
    fun setReceiver(onMessage: ((ByteArray) -> Unit)?)

    /** Close ports and release the device. Safe to call when not open. */
    fun close()
}
