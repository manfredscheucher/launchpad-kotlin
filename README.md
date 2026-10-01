# launchpad-kotlin

A small, dependency-free **Kotlin Multiplatform** library for driving Novation Launchpad grid
controllers (Mini MK3, Pro, Pro MK3, X) from host software over USB MIDI, in **Programmer mode**.

It is a *host-side* controller: your code runs on the computer/phone and lights the device's LEDs
and reads its pad presses. It does **not** replace the device firmware (that would be a project like
[dvhdr/launchpad-pro](https://github.com/dvhdr/launchpad-pro), which runs C on the device's own chip
— a different thing entirely).

## Status

| Target | Transport | State |
|--------|-----------|-------|
| JVM (desktop) | `javax.sound.midi` | ✅ implemented |
| Android | `android.media.midi` (USB host) | ✅ implemented (needs a device to test) |
| Native (Linux/macOS/Windows) | RtMidi/PortMidi via cinterop | ⏳ planned, behind the same `MidiTransport` seam |
| JS / Wasm | Web MIDI API | ⏳ possible, not started |

## Install

Not published to Maven Central yet. Consume it as a git submodule + Gradle composite build:

```bash
git submodule add https://github.com/manfredscheucher/launchpad-kotlin.git
```

```kotlin
// settings.gradle.kts
includeBuild("launchpad-kotlin")
```

```kotlin
// your module's build.gradle.kts
dependencies { implementation("org.bytefred.launchpad:launchpad-core") }
```

## Usage

```kotlin
val lp = Launchpad()
lp.connectFirst()                                  // finds a Launchpad, enters Programmer mode
lp.setListener(object : LaunchpadListener {
    override fun onPad(pad: Pad, pressed: Boolean) {
        if (pressed) lp.setPad(pad, LpColor.RED)
    }
})
lp.setPad(Pad(0, 0), Lighting.Pulsing(LpColor.BLUE))   // bottom-left pad pulses blue
lp.disconnect()                                    // clears LEDs, restores Live mode
```

## FAQ / Troubleshooting

### ⚠️ Only some pads light up, colours look wrong, or the board shows a factory/garbage pattern

**By far the most common problem, and it bites everyone: you opened the wrong USB MIDI port.**

A Launchpad Mini MK3 (and Pro / X) exposes **two MIDI interfaces per direction** over one USB cable:

| Port name (varies by OS) | Purpose | Accepts Programmer-mode LED control? |
|--------------------------|---------|--------------------------------------|
| `…LPMiniMK3 MIDI…` (the **MIDI** interface) | note/LED I/O | ✅ **yes — use this one** |
| `…LPMiniMK3 DAW…` (the **DAW** interface) | DAW session control (Ableton) | ❌ **no — silently ignores your LED writes** |

If you send LED commands to the **DAW** port, the device **silently drops them** — no error, nothing
throws. The symptom is exactly "the board lights up wrong": factory-default lighting, garbage, or only
a few pads reacting. It is **not** a protocol bug, a palette bug, or bad wiring — it's the wrong port.

**This library already picks the right port for you** — the JVM transport filters ports to the `MIDI`
interface and skips `DAW`/`DIN` (see
[`MidiTransport.jvm.kt`](launchpad-core/src/jvmMain/kotlin/org.bytefred/launchpad/MidiTransport.jvm.kt),
the `interfaceType(name) != InterfaceType.MIDI` guard). So with `launchpad-kotlin` you shouldn't hit
this. But **if you write your own MIDI code, or use another library/DAW, this is the first thing to
check.** Enumerate the ports and open the one whose name contains `MIDI`, never `DAW`.

> Same gotcha, same fix in the C++ sibling — see [launchpad-cpp](https://github.com/manfredscheucher/launchpad-cpp).

### Batched SysEx repaint is unreliable on the Mini MK3 — prefer per-pad Note-On

Painting the whole grid in one SysEx frame (`renderBatched`) can drop or reorder LEDs on the Mini MK3,
leaving pads the wrong colour. The reliable path is one **palette Note-On per pad** (`setPad` / the
per-pad `render` list). If a full-board repaint looks partial or garbled, switch to per-pad writes.

### macOS: the device doesn't appear at all

Apple's default MIDI SPI has SysEx quirks. Add the [CoreMidi4J](https://github.com/DerekCook/CoreMidi4J)
SPI (no code change — names just gain a `CoreMIDI4J - ` prefix, which detection handles).

### Colours

LEDs are set from the device's built-in **128-colour palette** (a single index 0..127, sent as a
Note-On velocity). `LpColor.ofPalette(index)` selects an exact entry; the named constants
(`LpColor.RED`, `.GREEN`, …) are palette entries too. You may also pass RGB via
`LpColor.fromRgb888(r, g, b)` for convenience — it's mapped to the **nearest palette entry**, since
the reliable LED path across MIDI stacks is palette Note-On, not per-channel RGB SysEx.

### Coordinates

`Pad(x, y)` — `x` is the column 0..7 (left→right), `y` the row 0..7 **from the bottom**
(0 = bottom row). This matches both the device's own note numbering and a chessboard's file/rank.

## Design

Three cleanly separated layers — a hardware-independent domain model, the pure
[`LaunchpadProtocol`](docs/PROTOCOL.md) wire encoder/decoder (fully unit-tested, no hardware), and
the one `MidiTransport` platform seam (a plain `interface`; the real backend is built by the
`expect fun MidiTransport()` factory — JVM via `javax.sound.midi`, Android via `MidiManager`).
`Launchpad` is the high-level facade over them. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for
the full picture and [docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire spec.

## Test / simulate without hardware

Because `MidiTransport` is an interface, you can drive the **whole real stack** — `Launchpad`,
`LaunchpadProtocol` encode/decode, and the `LaunchpadListener` input path — with no physical device,
using the built-in [`FakeMidiTransport`](launchpad-core/src/commonMain/kotlin/org.bytefred/launchpad/FakeMidiTransport.kt).
Pass it to `Launchpad` and both directions work through the same protocol the hardware uses:

```kotlin
val fake = FakeMidiTransport()                 // a simulated Launchpad Mini MK3
val lp = Launchpad(fake)
lp.connect(fake.listDevices().first())

// OUTPUT — what the board WOULD show is captured and decoded back to (pad -> palette colour):
lp.setPad(Pad(2, 3), LpColor.ofPalette(5))
check(fake.colorAt(Pad(2, 3)) == 5)            // covers setPad AND renderBatched

// INPUT — simulate a physical pad press; it flows through the real decoder to your listener:
lp.setListener(object : LaunchpadListener {
    override fun onPad(pad: Pad, pressed: Boolean) { /* ... */ }
})
fake.emitPad(Pad(4, 1), pressed = true)
```

This is the recommended way to unit-test app logic that reacts to pad input or asserts board output
(see `FakeMidiTransportTest`). It needs no MIDI subsystem, so it runs anywhere the library does.

## Try it against real hardware

**Desktop (JVM):**

```bash
./gradlew :launchpad-demo:run
```

Lists connected devices, paints a colour gradient, and logs every pad/button press.

> **macOS:** if a plugged-in Launchpad doesn't appear, add the
> [CoreMidi4J](https://github.com/DerekCook/CoreMidi4J) SPI to `launchpad-demo` — Apple's default MIDI
> stack has SysEx quirks. No code change needed; device names just gain a `CoreMIDI4J - ` prefix,
> which detection already handles.

**Android (`launchpad-test` app):**

A minimal on-device app that does the same smoke test — connect, paint the 8×8 grid, press pads to
light them white — for a Launchpad plugged into a USB-OTG phone.

```bash
./gradlew :launchpad-test:installDebug   # then open the app and plug in a Launchpad
```

It logs to the screen and to a file (`getExternalFilesDir/launchpad-test.log`), so you can inspect a
run with `adb pull` without keeping the debug cable attached while the Launchpad occupies the port.

> **Using the library on Android:** call `LaunchpadAndroid.init(context)` once (e.g. in
> `Application.onCreate`) before constructing a `Launchpad`. Requires a phone with USB-OTG host
> support and Android 6.0+.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Permissive: you may use this in closed-source products; just
keep the copyright notice.
