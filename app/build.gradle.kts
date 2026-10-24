import com.android.build.api.artifact.SingleArtifact
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.nanote"
    compileSdk = 35

    defaultConfig {
        // Branding lives in one place: applicationId here, `app_name` in strings.xml and core/Brand.kt.
        applicationId = "com.nanote"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // =====================================================================
    // Release signing (optional, env-driven): active only when a keystore
    // file is provided via ANDROID_KEYSTORE_FILE (plus password/alias env
    // vars). Local builds without these variables stay unsigned, exactly as
    // before. CI (GitHub Actions) exports the values from repo secrets.
    // =====================================================================
    val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
    val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
    val hasReleaseSigning = !releaseKeystorePath.isNullOrEmpty() &&
        File(releaseKeystorePath).exists() &&
        !releaseStorePassword.isNullOrEmpty() &&
        !releaseKeyAlias.isNullOrEmpty() &&
        !releaseKeyPassword.isNullOrEmpty()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Minification is OFF until v1.0.2 is confirmed working on the
            // user's device: the v1.0.1 crash turned out to be a Navigation
            // argument bug (literal "null" sentinel), NOT R8 — but the
            // minified build has still never been executed on real hardware.
            // Plan: keep R8 off for v1.0.2 (highest-probability-good build),
            // re-enable in v1.0.3 once launch success is confirmed, keeping
            // the built-in crash reporter as the safety net.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // aapt2 requires adaptive icons under -v26 even with minSdk 26
        disable += "ObsoleteSdkInt"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}

// =====================================================================
// Offline enforcement: Nanote must NEVER request the INTERNET
// permission in the merged manifest of any variant.
// =====================================================================

// Check 1 — fast source-manifest check on every build. The only allowed
// uses-permission tag is the deliberate `tools:node="remove"` strip for
// INTERNET, which guarantees libraries cannot reintroduce it at merge time.
val checkManifestNoInternet = tasks.register("checkManifestNoInternet") {
    doLast {
        val manifest = layout.projectDirectory.file("src/main/AndroidManifest.xml").asFile
        val text = manifest.readText()
        val tagRegex = Regex("<uses-permission\\b[^>]*>")
        tagRegex.findAll(text).forEach { m ->
            val tag = m.value
            val name = Regex("android:name=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            val stripped = tag.contains("tools:node=\"remove\"")
            when {
                name == "android.permission.INTERNET" && stripped -> Unit // deliberate strip, allowed
                else -> throw GradleException(
                    "Nanote is offline-only: unexpected <uses-permission $name> in src/main/AndroidManifest.xml. " +
                        "Storage access must go through the Storage Access Framework / photo picker instead."
                )
            }
        }
    }
}
tasks.matching { it.name.startsWith("preBuild") }.configureEach { dependsOn(checkManifestNoInternet) }

// Check 2 — scan the final packaged APK bytes. The binary manifest keeps
// permission strings in its string pool, so a merged-in INTERNET (from any
// transitive library, in UTF-8 or UTF-16) fails the build.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val scanTask = tasks.register("scanApkNoInternet$cap") {
            val apkDir = variant.artifacts.get(SingleArtifact.APK).get()
            doLast {
                val dir = apkDir.asFile
                if (!dir.exists()) return@doLast
                val apks = dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
                if (apks.isEmpty()) return@doLast
                val name = "android.permission.INTERNET"
                val needles = listOf(
                    name.toByteArray(Charsets.US_ASCII),
                    name.toByteArray(Charsets.UTF_16LE),
                    name.toByteArray(Charsets.UTF_16BE)
                )
                apks.forEach { file ->
                    val bytes = file.readBytes()
                    if (needles.any { n -> bytes.indexOf(n) >= 0 }) {
                        throw GradleException(
                            "The merged manifest of ${file.name} requests android.permission.INTERNET. " +
                                "Nanote is a local-first offline app; strip the permission with tools:node=\"remove\"."
                        )
                    }
                }
                logger.lifecycle("Nanote offline check passed for ${apks.joinToString { it.name }}")
            }
        }
        tasks.matching { it.name == "package$cap" }.configureEach { finalizedBy(scanTask) }
    }
}

fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty() || size < needle.size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}
