import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Release signing credentials are NEVER committed (see documentation/RELEASE.md).
 * Resolution order:
 *  1. Environment variables (CI secrets):
 *     NANOTE_STORE_FILE, NANOTE_STORE_PASSWORD, NANOTE_KEY_ALIAS, NANOTE_KEY_PASSWORD
 *  2. An untracked local keystore/keystore.properties file (gitignored).
 * When neither is present the release build falls back to the debug key so CI
 * artifacts remain installable — such builds are clearly NOT Play-Store releases.
 */
val keystoreProperties = Properties().apply {
    val envStore = System.getenv("NANOTE_STORE_FILE")
    val envStorePassword = System.getenv("NANOTE_STORE_PASSWORD")
    val envKeyAlias = System.getenv("NANOTE_KEY_ALIAS")
    val envKeyPassword = System.getenv("NANOTE_KEY_PASSWORD")
    if (!envStore.isNullOrBlank() && !envStorePassword.isNullOrBlank()) {
        put("storeFile", envStore)
        put("storePassword", envStorePassword)
        put("keyAlias", envKeyAlias ?: "nanote")
        put("keyPassword", envKeyPassword ?: envStorePassword)
    } else {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) load(FileInputStream(f))
    }
}

android {
    namespace = "com.nanote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nanote.app"
        minSdk = 26
        targetSdk = 35
        // Part 4 final integration release (Part 1 = 1.1.0, Part 2 = 1.2.0,
        // Part 3 = 1.3.0, Part 4 integration = 1.4.0).
        versionCode = 5
        versionName = "1.4.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            if (keystoreProperties.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No credentials provided (e.g. CI without configured secrets):
                // sign with the debug key so the artifact is still installable.
                // This is NOT a Play-Store release build.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Modules
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:navigation"))
    implementation(project(":core:design"))
    implementation(project(":core:database"))
    implementation(project(":core:storage"))
    implementation(project(":core:data"))
    implementation(project(":core:graphics"))
    implementation(project(":core:pdf"))
    implementation(project(":feature:home"))
    implementation(project(":feature:library"))
    implementation(project(":feature:notebooks"))
    implementation(project(":feature:folders"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:editor"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    // Application-level PDFBox init (NanoteApp initializes the resource loader).
    implementation(libs.pdfbox.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsizeclass)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
