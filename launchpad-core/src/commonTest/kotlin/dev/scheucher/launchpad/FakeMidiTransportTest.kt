package dev.scheucher.launchpad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the WHOLE real Launchpad stack ([Launchpad] + [LaunchpadProtocol] + listener) through a
 * [FakeMidiTransport], with no hardware — the simulation the app uses to develop and test.
 */
class FakeMidiTransportTest {

    @Test fun listsAndOpensTheSimulatedDevice() {
        val fake = FakeMidiTransport()
        val lp = Launchpad(fake)
        val dev = fake.listDevices().single()
        assertEquals("fake-launchpad", dev.id)
        assertTrue(!lp.isConnected)
        lp.connect(dev)
        assertTrue(lp.isConnected)
    }

    @Test fun setPadIsCapturedAndDecodableAsAColour() {
        val fake = FakeMidiTransport()
        val lp = Launchpad(fake)
        lp.connect(fake.listDevices().first())
        fake.clearSent() // drop the enter-programmer-mode + clear frames from connect

        lp.setPad(Pad(2, 3), LpColor.ofPalette(5))
        assertEquals(5, fake.colorAt(Pad(2, 3)), "the pad should be recorded as palette colour 5")
        assertNull(fake.colorAt(Pad(0, 0)), "an untouched pad has no colour")
    }

    @Test fun batchedRenderIsDecodedPerPad() {
        val fake = FakeMidiTransport()
        val lp = Launchpad(fake)
        lp.connect(fake.listDevices().first())
        fake.clearSent()

        lp.renderBatched(listOf(
            LedInstruction(LaunchpadProtocol.noteFor(Pad(0, 0)), Lighting.Static(LpColor.ofPalette(21))),
            LedInstruction(LaunchpadProtocol.noteFor(Pad(7, 7)), Lighting.Static(LpColor.ofPalette(5))),
        ))
        assertEquals(21, fake.colorAt(Pad(0, 0)))
        assertEquals(5, fake.colorAt(Pad(7, 7)))
    }

    @Test fun emittedPadPressReachesTheListenerViaTheRealDecoder() {
        val fake = FakeMidiTransport()
        val lp = Launchpad(fake)
        val presses = mutableListOf<Pair<Pad, Boolean>>()
        lp.setListener(object : LaunchpadListener {
            override fun onPad(pad: Pad, pressed: Boolean) { presses += pad to pressed }
        })
        lp.connect(fake.listDevices().first())

        fake.emitPad(Pad(4, 1), pressed = true)
        fake.emitPad(Pad(4, 1), pressed = false)

        assertEquals(listOf(Pad(4, 1) to true, Pad(4, 1) to false), presses)
    }
}
