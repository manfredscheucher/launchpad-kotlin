plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
}

// launchpad-test: a minimal on-device smoke test. Plug a Launchpad into a USB-OTG Android phone,
// launch the app, and it runs the same flow as launchpad-demo — connect, paint the grid, echo pad
// presses into an on-screen log. Deliberately plain Android (no Compose) to stay tiny.
android {
    namespace = "dev.scheucher.launchpad.test"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.scheucher.launchpad.test"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":launchpad-core"))
}
