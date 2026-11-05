plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// Host-side developer CLI: exercises the exact same core modules the app uses,
// against real files (ISOs, disk-image files standing in for USB sticks).
dependencies {
    implementation(project(":core:catalog"))
    implementation(project(":core:download"))
    implementation(project(":core:writer"))
    implementation(project(":core:udf"))
    implementation(project(":core:wim"))
}

application {
    mainClass.set("dev.ruchin.anvil.devcli.MainKt")
    applicationName = "anvil-dev"
}
