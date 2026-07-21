rootProject.name = "launchpad-kotlin"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

// launchpad-core: the generic, transport-agnostic Launchpad/MIDI interface (KMP).
// launchpad-demo: a tiny JVM console app to prove the hardware works end-to-end.
// launchpad-test: a minimal Android app that runs the same smoke test on-device, for a Launchpad
//                 plugged into a USB-OTG phone (analogous to scripts/run.sh, but on Android).
include(":launchpad-core")
include(":launchpad-demo")
include(":launchpad-test")
