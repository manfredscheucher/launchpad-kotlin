# Launchpad Programmer-mode protocol

The wire spec implemented by `launchpad-kotlin`. The sibling
[`launchpad-cpp`](https://github.com/manfredscheucher/launchpad-cpp) implements the same spec
(it carries its own copy of this document). Values are from the official Novation *Launchpad Mini
[MK3] / Pro / Pro MK3 / X Programmer's Reference* manuals.

The `LaunchpadProtocol` object encodes and decodes exactly the messages below, and is unit-tested
against these byte sequences in `LaunchpadProtocolTest`.

All multi-byte values are hex. MIDI channel 1 = status low-nibble 0.

## Two USB-MIDI interfaces — use the right one

An MK3-era Launchpad exposes **two** MIDI in/out interfaces over USB:

- **DAW** interface (the *first* one) — used by DAWs for Session mode. It does **not** accept
  Programmer-mode LED control.
- **MIDI** interface (the *second* one) — the one that receives Custom-mode / Programmer-mode input
  and accepts LED control.

**LED writes must go to the MIDI interface, not the DAW interface.** Sending Programmer-mode LED
messages to the DAW port silently does nothing (the device stays in its default lighting), which
looks like a flaky or half-working connection.

Picking the right port is platform-specific:
- **JVM** (`javax.sound.midi`): the ports carry names — select the one whose name contains `MIDI`
  and skip `DAW`/`DIN`.
- **Android** (`android.media.midi`): port names are empty. The port order follows the USB
  interface order, so the **second (last)** input/output port pair is the MIDI interface.

## SysEx header

Every device SysEx starts with:

```
F0 00 20 29 02 <productId> ...
```

- `00 20 29` = Novation manufacturer id, `02` = fixed.
- Product id per model: **Mini MK3 = 0x0D**, **Pro = 0x10**, **Pro MK3 = 0x0E**, **X = 0x0C**.

## Enter / leave Programmer mode

Programmer mode gives full external LED control and makes every pad/button send MIDI.

| Model | Enter Programmer mode | Back to normal |
|-------|----------------------|----------------|
| Mini MK3 / Pro MK3 / X | `F0 00 20 29 02 <pid> 0E 01 F7` | `... 0E 00 F7` |
| Pro (original) | `F0 00 20 29 02 10 21 01 F7` (Live mode) | `... 21 00 F7` |

Send the enter-sequence right after opening the output port; send the leave-sequence before closing.

## Grid pad layout

The 8×8 grid uses Note numbers **11..88**, where `note = (row+1)*10 + (col+1)`, with **row 0 = bottom
row** and **col 0 = left column**. Bottom-left pad = note 11, top-right = note 88.

```
row 7 (top):    81 82 83 84 85 86 87 88
row 6:          71 72 73 74 75 76 77 78
row 5:          61 62 63 64 65 66 67 68
row 4:          51 52 53 54 55 56 57 58
row 3:          41 42 43 44 45 46 47 48
row 2:          31 32 33 34 35 36 37 38
row 1:          21 22 23 24 25 26 27 28
row 0 (bottom): 11 12 13 14 15 16 17 18
```

In the domain model this is `Pad(x, y)`, with `x` = column and `y` = row, both 0..7.

## Setting LEDs — palette Note-On

The reliable, cross-stack way to light a pad is a **Note-On with a palette velocity**:

```
90 <note> <velocity>      velocity = palette colour index 0..127, 0 = off
```

- Channel 1 (`90`) = static, Channel 2 (`91`) = flashing, Channel 3 (`92`) = pulsing.
  These map to `Lighting.Static` / `Flashing` / `Pulsing`.
- Note-off is a Note-On with velocity 0 (or `80 <note> 00`).

Edge (non-grid) buttons are lit with **Control Change** instead:

```
B0 <cc> <value>           value = palette colour index, 0 = off
```

> A batched RGB "LED lighting" SysEx (command `03`, true 0..127-per-channel colour) also exists in
> the manual, and `LaunchpadProtocol.ledSysex(...)` can encode it, but palette Note-On is the
> default because it works reliably everywhere; the RGB SysEx did not light LEDs on all tested host
> MIDI stacks. RGB colours passed via `LpColor.fromRgb888(...)` are mapped to the nearest palette
> entry.

## Colour palette (common entries)

The velocity selects a colour from the device's built-in 128-entry palette. Frequently used entries:

| Index | Colour | Index | Colour |
|-------|--------|-------|--------|
| 0  | off        | 21 | green |
| 1  | low white  | 37 | cyan |
| 3  | white      | 45 | blue |
| 5  | red        | 49 | indigo/violet |
| 9  | orange     | 53 | magenta/purple |
| 13 | yellow     | 23 | dark green |

See the manual's colour table for all 128 entries.

## Edge-button CC numbers

The navigation arrows differ per model; the top-row mode buttons and scene column are the same
across the MK3-era models. Button names follow Novation's own labels on the Mini MK3.

| Button | Mini MK3 | Pro |
|--------|----------|-----|
| Up / Down / Left / Right | 91 / 92 / 93 / 94 | 80 / 70 / 91 / 92 |
| Session / Drums / Keys / User | 95 / 96 / 97 / 98 | same |
| Logo | 99 | 99 |
| Scene 0..7 (top→bottom) | 89,79,69,59,49,39,29,19 | same |

## Input decoding

- Note-On (`90`) with velocity > 0 = grid pad **press**; velocity 0 = **release**.
- Control Change (`B0`) with value > 0 = edge-button press. Match the CC number against the tables
  above to identify the button.
- Pro-family devices may report arrow buttons as Note-On rather than CC.
