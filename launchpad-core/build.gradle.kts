plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

// Coordinates used by consumers (incl. kotlichess via composite build substitution).
group = "dev.scheucher.launchpad"
version = "0.1.0"

// launchpad-core: generic, transport-agnostic KMP interface to Novation Launchpad devices.
// commonMain = protocol + domain model + facade; jvmMain = javax.sound.midi; androidMain =
// android.media.midi (USB host). Native/JS can be added later behind the same MidiTransport seam.
kotlin {
    jvm()
    androidTarget()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "dev.scheucher.launchpad"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}
