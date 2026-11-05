plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Live smoke test against Microsoft's servers; opt-in only: ./gradlew :core:catalog:liveTest
tasks.register<Test>("liveTest") {
    description = "Resolves a real Windows 11 download link from Microsoft (network required)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    systemProperty("anvil.live", "true")
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("live") } }
