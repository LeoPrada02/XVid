// The Android app: a thin wrapper (share target, foreground service,
// notifications, media store) around the :core module.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds come from .github/workflows/release.yml, which sets these from the
// version tag, the repo and the GitHub secrets. Local builds are "-dev" builds that
// don't check for updates.
fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val appVersion = env("XVID_VERSION") ?: "0.1.0-dev"
val (major, minor, patch) = Regex("""(\d+)\.(\d+)\.(\d+)(-dev)?""").matchEntire(appVersion)?.destructured
    ?: error("XVID_VERSION must look like 1.2.0, not $appVersion")
// Android only installs updates with a higher versionCode, so minor and patch get two digits each.
require(minor.toInt() < 100 && patch.toInt() < 100) { "Minor and patch versions must stay below 100: $appVersion" }

android {
    namespace = "app.xvid"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.xvid"
        minSdk = 26
        targetSdk = 35
        versionCode = major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
        versionName = appVersion
        // "owner/name" of the GitHub repo whose Releases the app checks for updates. It comes
        // from the build (GitHub Actions sets GITHUB_REPOSITORY), never from the code.
        buildConfigField("String", "RELEASES_REPO", "\"${env("GITHUB_REPOSITORY").orEmpty()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // One APK per CPU type instead of one with all of them: Python and ffmpeg are big.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        env("XVID_KEYSTORE_FILE")?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = env("XVID_KEYSTORE_PASSWORD")
                keyAlias = env("XVID_KEY_ALIAS")
                keyPassword = env("XVID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        // youtubedl-android runs Python and ffmpeg from extracted native libraries.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.work:work-runtime:2.12.0")
    // Scans the pairing QR code. Runs in Google Play services: no camera permission, and small.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // Plays PC library videos inside the app: other players wouldn't trust the PCs' private
    // certificate authority, so it streams over the app's own connection (OkHttp, pinned to that CA).
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
}
