plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.novelscraper.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.novelscraper.app"
        minSdk = 26
        targetSdk = 34
        // Bump BOTH for every release you sideload: versionCode must increase
        // for Android to accept the install over a previous one, versionName is
        // what people see. See CHANGELOG.md.
        versionCode = 47
        versionName = "0.28.0"
    }

    // Only the ABIs we target — the phone (arm64) and the emulator (x86_64) —
    // packaged as SEPARATE APKs rather than one fat binary. The sherpa-onnx +
    // ONNX Runtime native libs are ~30MB per ABI, so a combined APK made every
    // phone download the 33.9MB x86_64 slice it can never run: 42% of the whole
    // download. Splitting gives the phone an arm64-only APK and still produces
    // an x86_64 one, so release builds stay testable on the emulator.
    // (Replaces defaultConfig.ndk.abiFilters — AGP refuses to combine the two.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // Not debuggable -> ART/Compose run optimized (debug builds are far
            // jankier). Signed with the debug key so the release APK sideloads
            // without extra key setup.
            //
            // R8 + resource shrinking: the unminified build carried 44.6MiB of
            // dex across three files for ~50 Kotlin sources, nearly all of it
            // unreachable Compose/AndroidX/sherpa API surface. The keep rules
            // in proguard-rules.pro cover the four things R8 cannot see —
            // sherpa-onnx's JNI classes, kotlinx-serialization's generated
            // serializers, Retrofit's reflective proxy, and @JavascriptInterface
            // members on the offscreen WebViews.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        // BuildConfig.DEBUG gates the OkHttp logging interceptor (net/Net.kt).
        // It is a compile-time constant, so in release the whole branch — and
        // with it the interceptor class — is eliminated.
        buildConfig = true
    }
}

// Every release build drops the phone APK at the PROJECT ROOT as
// novelscraper.apk. The Gradle output path
// (app/build/outputs/apk/release/app-<abi>-release.apk) is fine for tooling but
// hopeless for "send me the build" — and since the ABI split it holds two
// files, only one of which belongs on a phone. This puts the arm64 one in a
// single predictable place, overwriting the previous build.
//
// A single-file copy rather than a Copy task: the destination directory is the
// repo root, which CONTAINS app/build/, so declaring it as a task output makes
// Gradle infer a phantom dependency on the APK-listing task and fail the build.
// Declaring one exact output file avoids that.
//
// The emulator's x86_64 APK is deliberately left behind in the Gradle output
// dir — the only thing that wants it is `adb install` on a dev machine.
val copyApkToRoot by tasks.registering {
    description = "Copy the arm64 release APK to <repo root>/novelscraper.apk"
    group = "build"
    // Resolved here, inside the configuration block, so doLast closes over
    // plain locals. Script-level vals would drag the whole script object into
    // the closure, which the configuration cache cannot serialize.
    val src = layout.buildDirectory.file("outputs/apk/release/app-arm64-v8a-release.apk")
    val dst = rootProject.layout.projectDirectory.dir("..").file("novelscraper.apk").asFile
    inputs.file(src)
    outputs.file(dst)
    doLast {
        src.get().asFile.copyTo(dst, overwrite = true)
        logger.lifecycle("APK -> ${dst.absolutePath}")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy(copyApkToRoot)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Networking: Retrofit + OkHttp + kotlinx-serialization JSON.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Image loading (covers / inline chapter images) — shares the auth cookie.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Drag-to-reorder for the library grid.
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // MediaSession + media-style notification for background/lock-screen TTS.
    implementation("androidx.media:media:1.7.0")

    // On-device Kokoro TTS via sherpa-onnx (ONNX model + espeak-ng phonemizer +
    // voices), and tar.bz2 extraction for the downloaded model package.
    implementation(files("libs/sherpa-onnx-1.13.5.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
