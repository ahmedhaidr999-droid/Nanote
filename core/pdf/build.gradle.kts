plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nanote.core.pdf"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))

    implementation(libs.pdfium.android) {
        // pdfium-android 1.9.0 ships the legacy support library; exclude to
        // avoid duplicate classes with androidx.core.
        exclude(group = "com.android.support", module = "support-compat")
    }
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.coroutines.android)
}
