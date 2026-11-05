plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}
