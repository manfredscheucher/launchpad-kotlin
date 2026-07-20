#!/usr/bin/env bash
# Build and run the launchpad-kotlin demo (launchpad-demo) against real hardware.
#
# The demo lists connected Launchpads, connects to the first, switches it to Programmer mode, paints
# the 8x8 grid, echoes pad presses for ~15s, then clears and restores Live mode (also on Ctrl+C via
# a JVM shutdown hook).
#
# Usage:
#   ./scripts/run.sh
#
# Requires a JDK (the Gradle wrapper handles the Gradle/Kotlin toolchain). Plug a Launchpad in via
# USB; on macOS it must appear as a plain "MIDI" device (not "DAW") in Audio MIDI Setup.

set -e

DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$DIR"

# ./gradlew :launchpad-demo:run rebuilds first (incremental, fast no-op when nothing changed), so we
# never launch a stale artifact. --console=plain keeps the console output readable for a CLI demo.
exec ./gradlew :launchpad-demo:run --console=plain -q
