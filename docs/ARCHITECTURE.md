# Architecture

`launchpad-kotlin` is a host-side controller library in three cleanly separated layers, so the
hardware-independent logic can be unit-tested without a device and the platform-specific MIDI
plumbing is isolated behind a single seam.

```
Application
    │  Pad, Button, LpColor, Lighting
    ▼
Launchpad (facade)  ──►  LaunchpadListener  (input callbacks)
    │  encode/decode
    ▼
LaunchpadProtocol   (pure functions, no I/O — fully unit-tested)
    │  ByteArray
    ▼
MidiTransport       (interface: open / list / send / receive / close; real via expect fun factory, or FakeMidiTransport)
    │  raw MIDI
    ▼
Device (USB MIDI)
```

## Layers

**Domain model** (`Model.kt`, `LaunchpadModel.kt`) — what applications program against:
`Pad(x, y)`, the `Button` enum, `LpColor`, and the `Lighting` sealed interface
(`Static` / `Flashing` / `Pulsing`). `LaunchpadModel` holds the per-model constants (SysEx product
id, edge-button CC numbers). No MIDI bytes appear here.

**`LaunchpadProtocol`** — pure functions that encode/decode the Novation MIDI/SysEx wire format
(see [PROTOCOL.md](PROTOCOL.md)). No hardware, no state, no I/O — just `ByteArray` in and out, which
makes it fully unit-testable against the official Programmer's Reference byte sequences
(`LaunchpadProtocolTest`).

**`MidiTransport`** — the one platform seam: `open`, `listDevices`, `send`, `setReceiver`, `close`.
It is a plain `interface`, so it can be substituted — the real backend is built by the
`expect fun MidiTransport()` factory, while [`FakeMidiTransport`](../launchpad-core/src/commonMain/kotlin/org.bytefred/launchpad/FakeMidiTransport.kt)
(common, no hardware) drives the whole stack in tests/simulation (see the README "Test / simulate
without hardware"). Each target provides its own factory implementation:

- **JVM (desktop)** — `javax.sound.midi`; pairs input/output ports by normalised name, filters to
  the plain MIDI interface (skips DAW/DIN — see below), and paces Note-On sends slightly to avoid
  reordering on async MIDI stacks.
- **Android** — `android.media.midi` (USB host). Requires a one-time `LaunchpadAndroid.init(context)`
  (e.g. in `Application.onCreate`) because the platform MIDI manager needs a `Context`. Port names
  are empty here, so it can't filter by name — it opens the **second (last)** input/output port
  pair, which is the MIDI interface (see below). `close()` flushes briefly before tearing the port
  down so the clear + Live-mode messages on `disconnect` aren't cut off.

> **Both platforms must open the MIDI interface, not the DAW one.** An MK3 Launchpad exposes two
> USB-MIDI interfaces; only the second ("MIDI") accepts Programmer-mode LED control. Sending to the
> DAW interface silently does nothing. See [PROTOCOL.md](PROTOCOL.md#two-usb-midi-interfaces--use-the-right-one).

`Launchpad` is the high-level facade tying the three together: `connect` / `connectFirst`,
`render(...)` / `setPad(...)` / `setButton(...)`, `clear`, `disconnect`, and input via a
`LaunchpadListener`.

## Adding a platform

Adding Native (Linux/macOS/Windows via RtMidi/PortMidi) or JS/Wasm (Web MIDI) means adding one new
`MidiTransport` implementation for that target plus its `actual fun MidiTransport()` factory. The
domain model and `LaunchpadProtocol` are shared unchanged — that is the point of keeping all I/O
behind the transport seam.

## Relationship to launchpad-cpp

The C++ sibling [`launchpad-cpp`](https://github.com/manfredscheucher/launchpad-cpp) implements the
same wire protocol with the same domain concepts (models, bottom-left-origin coordinates, palette
colours, button names). The two libraries are independent but kept conceptually aligned; each is
idiomatic for its language (Kotlin listeners here, `std::function` callbacks there).
