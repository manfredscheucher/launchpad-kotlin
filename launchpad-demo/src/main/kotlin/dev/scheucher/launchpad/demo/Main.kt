package dev.scheucher.launchpad.demo

import dev.scheucher.launchpad.Button
import dev.scheucher.launchpad.Launchpad
import dev.scheucher.launchpad.LaunchpadListener
import dev.scheucher.launchpad.Lighting
import dev.scheucher.launchpad.LpColor
import dev.scheucher.launchpad.Pad

/**
 * Manual hardware check. Run with:  ./gradlew :launchpad-demo:run
 *
 * Mirrors the proven digitalfritz flow: connect, switch to Programmer mode, clear, then light each
 * pad with an explicit PALETTE colour (Note-On velocity) — no RGB, no SysEx frames. Pressing a pad
 * lights it white; releasing restores its palette colour. Runs for ~15s, then restores Live mode.
 */
fun main() {
    val lp = Launchpad()
    val devices = lp.available()
    if (devices.isEmpty()) {
        println("No Launchpad found. Plug one in via USB and try again.")
        return
    }
    println("Found devices:")
    devices.forEachIndexed { i, d -> println("  [$i] ${d.name}  (model=${d.model ?: "unknown"})") }

    lp.connect(devices.first())
    println("Connected to ${devices.first().name} as model ${lp.model}. Programmer mode on.")

    // Clear the board and restore Live mode even on Ctrl+C: a JVM shutdown hook runs disconnect()
    // during an interrupted exit, so the board never stays lit. Guarded so the normal path (below)
    // doesn't double-disconnect.
    val done = java.util.concurrent.atomic.AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread {
        if (done.compareAndSet(false, true)) runCatching { lp.disconnect() }
    })

    lp.setListener(object : LaunchpadListener {
        override fun onPad(pad: Pad, pressed: Boolean) {
            println("PAD (${pad.x},${pad.y}) ${if (pressed) "down" else "up"}")
            lp.setPad(pad, if (pressed) LpColor.ofPalette(3) /*white*/ else paletteFor(pad))
        }
        override fun onButton(button: Button, pressed: Boolean) {
            println("BUTTON $button ${if (pressed) "down" else "up"}")
        }
    })

    // Paint every pad with a distinct palette colour, one Note-On per pad — exactly like
    // digitalfritz's updateGrid/setLED. Each row is a different hue so you can see all 64 pads.
    for (y in 0..7) for (x in 0..7) lp.setPad(Pad(x, y), paletteFor(Pad(x, y)))
    println("Board painted with palette colours. Press pads (they turn white). Holding ~15s...")

    Thread.sleep(15_000)

    if (done.compareAndSet(false, true)) {
        lp.disconnect()
        println("Disconnected, device returned to Live mode.")
    }
}

// A fixed palette colour per pad: row selects a hue from the official Novation palette, so the whole
// board shows 8 clearly different colours. These are palette indices (velocities), NOT RGB.
private fun paletteFor(pad: Pad): LpColor {
    val rowPalette = intArrayOf(5, 9, 13, 21, 37, 45, 49, 53) // red,orange,yellow,green,cyan,blue,indigo,magenta
    return LpColor.ofPalette(rowPalette[pad.y])
}
