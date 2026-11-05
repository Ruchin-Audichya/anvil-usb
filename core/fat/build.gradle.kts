plugins { alias(libs.plugins.kotlin.jvm) }

dependencies {
    api(project(":core:common"))
    api(project(":core:blockdev"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.truth)
    testRuntimeOnly(libs.junit.platform.launcher)
}
