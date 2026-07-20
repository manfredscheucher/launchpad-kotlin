plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// launchpad-demo: a tiny JVM console tool to verify a real Launchpad end-to-end — lists devices,
// connects to the first one, runs a colour sweep, and prints pad/button presses. Not shipped.
dependencies {
    implementation(project(":launchpad-core"))
}

application {
    mainClass.set("dev.scheucher.launchpad.demo.MainKt")
}
