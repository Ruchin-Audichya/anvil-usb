plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:common"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.named<Test>("test") {
    System.getenv("ANVIL_WIMLIB")?.let { systemProperty("anvil.wimlib", it) }
}
