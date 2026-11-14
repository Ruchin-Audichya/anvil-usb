plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.ruchin.anvil.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.ruchin.anvil"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Distribution channels. The Galaxy Store build hides the Windows 11 hardware-requirement bypass
    // (store-review risk); the GitHub build keeps it.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "ALLOW_HW_BYPASS", "true")
        }
        create("galaxy") {
            dimension = "store"
            buildConfigField("boolean", "ALLOW_HW_BYPASS", "false")
        }
    }

    // Release signing comes from ~/.gradle/gradle.properties (ANVIL_KEYSTORE, …) so no key ever lives in the repo.
    val keystorePath = providers.gradleProperty("ANVIL_KEYSTORE").orNull
    signingConfigs {
        if (keystorePath != null) create("release") {
            storeFile = file(keystorePath)
            storePassword = providers.gradleProperty("ANVIL_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("ANVIL_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("ANVIL_KEY_PASSWORD").get()
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            // Shrinking stays off until release builds are exercised on devices (R8 + Hilt/serialization).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:blockdev"))
    implementation(project(":core:image"))
    implementation(project(":core:writer"))
    implementation(project(":core:download"))
    implementation(project(":core:catalog"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
}
