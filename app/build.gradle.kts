plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Version -----------------------------------------------------------------------------------------------
// Release builds take their version from the git tag: CI passes -PappVersionName=X.Y.Z for a tag vX.Y.Z, and
// X.Y.Z-ci.N for other builds. Local builds are "<latest tag>-dev". The version code is derived from the name, so
// every kind of build upgrades cleanly: release X.Y.Z < its CI builds < a local dev build < release X.Y.(Z+1).
val latestTag: String = providers.exec {
    commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*")
    isIgnoreExitValue = true
}.standardOutput.asText.getOrElse("").trim().removePrefix("v")

val appVersionName: String = providers.gradleProperty("appVersionName").orNull
    ?: (latestTag.ifEmpty { "0.0.0" } + "-dev")

fun versionCodeOf(name: String): Int {
    val v = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(name)?.groupValues ?: return 1
    val base = v[1].toInt() * 1_000_000 + v[2].toInt() * 10_000 + v[3].toInt() * 100
    val extra = when {
        name.endsWith("-dev") -> 99
        "-ci." in name -> 1 + (name.substringAfter("-ci.").toIntOrNull() ?: 0).coerceIn(0, 97)
        else -> 0
    }
    return base + extra
}

android {
    namespace = "dev.hyperdesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hyperdesk"
        minSdk = 33
        targetSdk = 35
        versionName = appVersionName
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toInt() ?: versionCodeOf(appVersionName)
        buildConfigField("String", "REPO", "\"3ig/hyperdesk\"")
        // Every phone with USB-C video output that runs HyperOS is 64-bit ARM; this keeps the other
        // architectures' copies of the TLS library (used for Wireless debugging) out of the APK.
        ndk { abiFilters += "arm64-v8a" }
    }

    // The release key lives outside the repo; its location and passwords come from ~/.gradle/gradle.properties
    // locally, and from repository secrets on GitHub Actions. See docs/RELEASE.md.
    val releaseStore = providers.gradleProperty("hyperdeskReleaseStoreFile").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = providers.gradleProperty("hyperdeskReleaseStorePassword").get()
                keyAlias = providers.gradleProperty("hyperdeskReleaseKeyAlias").get()
                keyPassword = providers.gradleProperty("hyperdeskReleaseKeyPassword").get()
            }
        }
    }
    val signing = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
        logger.warn("hyperdeskReleaseStoreFile not set: APKs are signed with the debug key and cannot update a release install")
    }

    buildTypes {
        debug {
            // Same app ID as the release build on purpose: two copies would both try to run the desktop on the
            // monitor. Signed with the release key when it is available, so a local build installs over a
            // release and the next release installs over it.
            signingConfig = signing
        }
        release {
            // No R8: the shell helper is started by class name through app_process, and the Wireless debugging
            // client relies on reflection. Both would break if classes were renamed or removed.
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    // Compress the dex files inside the APK. Without code shrinking (see release{} above) the BouncyCastle library
    // that the Wireless debugging client depends on is shipped whole, and stored uncompressed it doubles the download.
    packaging { dex { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    // On-device ADB client, so the shell helper can be started without a computer (see Adb.kt).
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
