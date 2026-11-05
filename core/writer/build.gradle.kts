plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:blockdev"))
    api(project(":core:image"))
    api(project(":core:udf"))
    api(project(":core:wim"))
    api(project(":core:fat"))
    api(project(":core:unattend"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    // Opt-in real-ISO end-to-end test (see WindowsUsbWriterIsoTest).
    System.getenv("ANVIL_WIN_ISO")?.let { environment("ANVIL_WIN_ISO", it) }
    inputs.property("winIso", System.getenv("ANVIL_WIN_ISO") ?: "")
}
