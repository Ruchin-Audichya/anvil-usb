plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:common"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}
