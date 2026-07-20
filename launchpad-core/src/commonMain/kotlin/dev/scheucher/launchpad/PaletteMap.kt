package dev.scheucher.launchpad

/**
 * Maps an arbitrary [LpColor] to the nearest entry in the Launchpad's 128-colour velocity palette.
 *
 * This is only needed for the *flashing* LED-SysEx type (type 1), which the firmware accepts as
 * palette indices rather than RGB. Static and pulsing use full RGB, so they never come through
 * here. The table below is a coarse but sufficient sample of the official palette (approximate RGB
 * per entry, in 0..127 space); nearest-match keeps flashing colours visually close to the RGB
 * colour the caller asked for.
 */
internal object PaletteMap {
    // A representative subset of the 128-entry palette: (index, r, g, b) in 0..127.
    // Covers the primaries/secondaries and a few brightness levels we care about.
    private val entries = intArrayOf(
        // index, r,  g,  b
        0,   0,   0,   0,    // off / black
        3,  127, 127, 127,   // white
        5,  127,  0,   0,    // red (full)
        9,  127, 40,  0,     // orange
        13, 127, 127, 0,     // yellow
        21, 0,   127, 0,     // green (full)
        37, 0,   127, 127,   // cyan
        45, 0,   0,   127,   // blue (full)
        49, 40,  0,   127,   // indigo / violet
        53, 127, 0,   127,   // magenta / purple
        1,  60,  60,  60,    // dim white / grey
        7,  60,  0,   0,     // dim red
        23, 0,   60,  0,     // dim green
        47, 0,   0,   60,    // dim blue
    )

    fun nearest(c: LpColor): Int {
        var bestIndex = 0
        var bestDist = Int.MAX_VALUE
        var i = 0
        while (i < entries.size) {
            val idx = entries[i]; val r = entries[i + 1]; val g = entries[i + 2]; val b = entries[i + 3]
            val dr = c.r - r; val dg = c.g - g; val db = c.b - b
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) { bestDist = dist; bestIndex = idx }
            i += 4
        }
        return bestIndex
    }
}
