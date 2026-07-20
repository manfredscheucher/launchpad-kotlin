package dev.scheucher.launchpad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the MIDI encoding against the concrete byte sequences in the Novation Launchpad Mini
 * [MK3] Programmer's Reference manual. If these pass, the wire format is correct regardless of
 * platform transport.
 */
class LaunchpadProtocolTest {

    private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test fun enterProgrammerMode_matchesManual() {
        // F0 00 20 29 02 0D 0E 01 F7  (Mini MK3, product id 0x0D)
        val bytes = LaunchpadProtocol.enterProgrammerMode(LaunchpadModel.MINI_MK3)
        assertEquals("f0 00 20 29 02 0d 0e 01 f7", bytes.hex())
    }

    @Test fun enterLiveMode_matchesManual() {
        val bytes = LaunchpadProtocol.enterLiveMode(LaunchpadModel.MINI_MK3)
        assertEquals("f0 00 20 29 02 0d 0e 00 f7", bytes.hex())
    }

    @Test fun gridNoteMapping_bottomLeftIs11_topRightIs88() {
        // Manual: pad(0,0) bottom-left = note 11 (0Bh); Programmer layout goes up to 88.
        assertEquals(11, LaunchpadProtocol.noteFor(Pad(0, 0)))
        assertEquals(88, LaunchpadProtocol.noteFor(Pad(7, 7)))
        assertEquals(18, LaunchpadProtocol.noteFor(Pad(7, 0))) // bottom-right
        assertEquals(81, LaunchpadProtocol.noteFor(Pad(0, 7))) // top-left
    }

    @Test fun noteRoundTrips() {
        for (y in 0..7) for (x in 0..7) {
            val note = LaunchpadProtocol.noteFor(Pad(x, y))
            assertEquals(Pad(x, y), LaunchpadProtocol.padForNote(note))
        }
        assertNull(LaunchpadProtocol.padForNote(99)) // logo CC, not a grid pad
        assertNull(LaunchpadProtocol.padForNote(10))
    }

    @Test fun rgbLedSysex_encodesTypeAndChannels() {
        // Static red on bottom-left (note 11) via RGB (type 3), full red = 127,0,0.
        val frame = LaunchpadProtocol.ledSysex(
            LaunchpadModel.MINI_MK3,
            listOf(LedInstruction(11, Lighting.Static(LpColor(127, 0, 0))))
        )
        // header F0 00 20 29 02 0D 03, then spec: 03 (rgb) 0B (led 11) 7F 00 00, then F7
        assertEquals("f0 00 20 29 02 0d 03 03 0b 7f 00 00 f7", frame.hex())
    }

    @Test fun paletteNoteOn_matchesManualExample() {
        // Manual example: light lower-left pad static red => 90 0B 05.
        val bytes = LaunchpadProtocol.padNoteOn(Pad(0, 0), velocity = 5)
        assertEquals("90 0b 05", bytes.hex())
    }

    @Test fun paletteNoteOn_channelsPerLightingMode() {
        // Ch1=static(0x90), Ch2=flash(0x91), Ch3=pulse(0x92) — the three Programmer-mode channels.
        assertEquals("90 0b 0d", LaunchpadProtocol.padNoteOn(Pad(0, 0), 13, LaunchpadProtocol.NoteMode.STATIC).hex())
        assertEquals("91 0b 0d", LaunchpadProtocol.padNoteOn(Pad(0, 0), 13, LaunchpadProtocol.NoteMode.FLASH).hex())
        assertEquals("92 0b 0d", LaunchpadProtocol.padNoteOn(Pad(0, 0), 13, LaunchpadProtocol.NoteMode.PULSE).hex())
    }

    @Test fun lpColor_ofPalette_usesExplicitIndex() {
        // An explicit palette colour must send that exact velocity, not a nearest-RGB guess.
        assertEquals(13, LpColor.ofPalette(13).paletteIndex)
        assertEquals(0, LpColor.OFF.paletteIndex)
    }

    @Test fun decode_padPressAndRelease() {
        // Note-on note 11 velocity 100 -> press; velocity 0 -> release.
        val press = LaunchpadProtocol.decode(LaunchpadModel.MINI_MK3, byteArrayOf(0x90.toByte(), 11, 100))
        assertEquals(LaunchpadEvent.PadEvent(Pad(0, 0), pressed = true), press)
        val release = LaunchpadProtocol.decode(LaunchpadModel.MINI_MK3, byteArrayOf(0x90.toByte(), 11, 0))
        assertEquals(LaunchpadEvent.PadEvent(Pad(0, 0), pressed = false), release)
    }

    @Test fun decode_buttonCc() {
        // CC 91 on Mini MK3 = UP button.
        val ev = LaunchpadProtocol.decode(LaunchpadModel.MINI_MK3, byteArrayOf(0xB0.toByte(), 91, 127))
        assertEquals(LaunchpadEvent.ButtonEvent(Button.UP, pressed = true), ev)
    }

    @Test fun modelDetection_fromPortNames() {
        assertEquals(LaunchpadModel.MINI_MK3, LaunchpadModel.detect("Launchpad Mini MK3 MIDI"))
        assertEquals(LaunchpadModel.X, LaunchpadModel.detect("Launchpad X LPX MIDI Out"))
        assertEquals(LaunchpadModel.PRO_MK3, LaunchpadModel.detect("Launchpad Pro MK3"))
        assertNull(LaunchpadModel.detect("Some Random Synth"))
    }

    @Test fun ledSysex_chunkingBoundaryFitsInOneFrame() {
        // 64 grid pads must fit comfortably under the 81-spec limit.
        val all = (0..7).flatMap { y -> (0..7).map { x -> LedInstruction(LaunchpadProtocol.noteFor(Pad(x, y)), Lighting.Static(LpColor.OFF)) } }
        val frame = LaunchpadProtocol.ledSysex(LaunchpadModel.MINI_MK3, all)
        assertTrue(frame.first().toInt() and 0xFF == 0xF0)
        assertTrue(frame.last().toInt() and 0xFF == 0xF7)
    }
}
