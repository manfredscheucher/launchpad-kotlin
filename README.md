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
dependencies { implementation("dev.scheucher.launchpad:launchpad-core") }
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
the one `expect/actual` `MidiTransport` platform seam. `Launchpad` is the high-level facade over
them. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full picture and
[docs/PROTOCOL.md](docs/PROTOCOL.md) for the wire spec.

## Try it against real hardware

```bash
./gradlew :launchpad-demo:run
```

Lists connected devices, paints a colour gradient, and logs every pad/button press.

> **macOS:** if a plugged-in Launchpad doesn't appear, add the
> [CoreMidi4J](https://github.com/DerekCook/CoreMidi4J) SPI to `launchpad-demo` — Apple's default MIDI
> stack has SysEx quirks. No code change needed; device names just gain a `CoreMIDI4J - ` prefix,
> which detection already handles.

> **Android:** call `LaunchpadAndroid.init(context)` once (e.g. in `Application.onCreate`) before
> constructing a `Launchpad`. Requires a phone with USB-OTG host support and Android 6.0+.

## License

BSD 3-Clause — see [LICENSE](LICENSE). Permissive: you may use this in closed-source products; just
keep the copyright notice.
