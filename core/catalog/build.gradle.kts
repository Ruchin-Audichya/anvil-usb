plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // OpenPGP signatures on distro checksum lists (lightweight API, no JCA provider needed on Android).
    implementation(libs.bouncycastle.bcpg)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The app ships the Linux manifest as an asset; tests check that exact file.
val bundledLinuxManifest = rootProject.file("app/src/main/assets/linux-manifest.json").absolutePath

// Live smoke test against Microsoft's servers and real Linux mirrors; opt-in only: ./gradlew :core:catalog:liveTest
tasks.register<Test>("liveTest") {
    description = "Resolves real Windows links from Microsoft and real Linux ISOs from distro mirrors (network required)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    systemProperty("anvil.live", "true")
    systemProperty("anvil.linuxManifest", bundledLinuxManifest)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("live") }
    systemProperty("anvil.linuxManifest", bundledLinuxManifest)
    inputs.file(bundledLinuxManifest)
}
