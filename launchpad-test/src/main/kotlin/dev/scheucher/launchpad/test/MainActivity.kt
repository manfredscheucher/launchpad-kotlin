package dev.scheucher.launchpad.test

import android.app.Activity
import android.content.Context
import android.media.midi.MidiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button as AndroidButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.scheucher.launchpad.Button
import dev.scheucher.launchpad.Launchpad
import dev.scheucher.launchpad.LaunchpadAndroid
import dev.scheucher.launchpad.LaunchpadListener
import dev.scheucher.launchpad.LpColor
import dev.scheucher.launchpad.Pad
import kotlin.concurrent.thread

/**
 * On-device smoke test for launchpad-kotlin, mirroring launchpad-demo: connect to the Launchpad,
 * paint the 8x8 grid with palette colours, and echo pad presses (pressed pad turns white) into an
 * on-screen log. The log is also written to a file (path shown below) so it can be pulled with adb
 * without keeping the Mac cable attached while the Launchpad occupies the phone's only USB-C port.
 *
 * A single [Launchpad] instance is reused across button taps; reconnecting disconnects the previous
 * session first so the USB-MIDI port is released (opening it twice fails on Android).
 */
class MainActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var midiManager: MidiManager
    private var logFile: java.io.File? = null

    /** The one Launchpad instance, guarded by [lock] since taps run on background threads. */
    private val lock = Any()
    private var launchpad: Launchpad? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: android.media.midi.MidiDeviceInfo) { log("» device added") }
        override fun onDeviceRemoved(device: android.media.midi.MidiDeviceInfo) { log("» device removed") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        logView = TextView(this).apply { setPadding(16, 16, 16, 16); textSize = 12f }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(button("Connect + Paint") { connectAndPaint() }, equalWeight())
            addView(button("Flash green") { flash() }, equalWeight())
            addView(button("Disconnect") { disconnect() }, equalWeight())
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true  // inset by status/navigation bars; no overlap, no hard-coded spacings
            addView(buttonRow)
            addView(scroll)
        }
        setContentView(root)

        logFile = java.io.File(getExternalFilesDir(null), "launchpad-test.log").also {
            runCatching { it.writeText("") }  // truncate on each launch
        }

        LaunchpadAndroid.init(this)
        midiManager = getSystemService(Context.MIDI_SERVICE) as MidiManager
        midiManager.registerDeviceCallback(deviceCallback, ui)

        log("launchpad-test — plug a Launchpad into the phone via USB-OTG, then tap Connect + Paint.")
        log("Log file: ${logFile?.absolutePath}")
    }

    private fun connectAndPaint() = thread(name = "lp-connect") {
        synchronized(lock) {
            // Reuse one instance; release any previous session so the USB-MIDI port is free.
            launchpad?.let { runCatching { it.disconnect() } }

            val lp = Launchpad()
            val devices = lp.available()
            if (devices.isEmpty()) { log("No Launchpad found. Is it plugged in and powered?"); return@thread }
            log("Devices: ${devices.joinToString { "${it.name} [model=${it.model}]" }}")

            runCatching { lp.connect(devices.first()) }
                .onFailure { log("connect() failed: ${it.message}"); return@thread }
            launchpad = lp
            log("Connected (model=${lp.model}). Painting…")

            lp.setListener(object : LaunchpadListener {
                override fun onPad(pad: Pad, pressed: Boolean) {
                    log("PAD (${pad.x},${pad.y}) ${if (pressed) "down" else "up"}")
                    runCatching { lp.setPad(pad, if (pressed) LpColor.ofPalette(3) else paletteFor(pad)) }
                }
                override fun onButton(button: Button, pressed: Boolean) { log("BUTTON $button ${if (pressed) "down" else "up"}") }
            })

            for (y in 0..7) for (x in 0..7) lp.setPad(Pad(x, y), paletteFor(Pad(x, y)))
            log("Painted 64 pads. Press pads — they turn white while held.")
        }
    }

    private fun flash() = thread(name = "lp-flash") {
        synchronized(lock) {
            val lp = launchpad
            if (lp == null || !lp.isConnected) { log("Not connected — tap Connect + Paint first."); return@thread }
            log("Flashing whole board green…")
            lp.flashAll(LpColor.ofPalette(21))
        }
    }

    private fun disconnect() = thread(name = "lp-disconnect") {
        synchronized(lock) {
            launchpad?.let { runCatching { it.disconnect() } }
            launchpad = null
            log("Disconnected — board cleared, Live mode restored.")
        }
    }

    override fun onStop() {
        super.onStop()
        thread { synchronized(lock) { launchpad?.let { runCatching { it.disconnect() } }; launchpad = null } }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { midiManager.unregisterDeviceCallback(deviceCallback) }
    }

    private fun button(label: String, onClick: () -> Unit) = AndroidButton(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun equalWeight() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

    private fun log(line: String) {
        runCatching { logFile?.appendText(line + "\n") }
        ui.post { if (::logView.isInitialized) logView.append(line + "\n") }
    }
}

// A fixed palette colour per pad: the row selects a hue from the Novation palette, so the whole
// board shows 8 clearly different colours. These are palette indices (velocities), not RGB.
private fun paletteFor(pad: Pad): LpColor {
    val rowPalette = intArrayOf(5, 9, 13, 21, 37, 45, 49, 53) // red,orange,yellow,green,cyan,blue,indigo,magenta
    return LpColor.ofPalette(rowPalette[pad.y])
}
