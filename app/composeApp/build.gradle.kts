import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

// One Kotlin Multiplatform module for every app. Source sets:
//   jvmSharedMain  everything that runs the same on Android and desktop: models,
//                  networking, view models, screens, theme. Android, Linux and
//                  Windows are all JVM, so plain Java/JVM libraries (OkHttp,
//                  Retrofit, commons-compress) are usable here.
//   androidMain    the Android app: Application/Activity, the TTS foreground
//                  service, WebView-based helpers, DownloadManager, resources.
//   desktopMain    the Linux/Windows app (filled in from Phase 2 on).
// Platform differences are `expect`/`actual` declarations in jvmSharedMain
// (see platform/Platform.kt), so each target is checked for completeness by the
// compiler rather than wired up at runtime.
kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    compilerOptions {
        // expect/actual objects (ScrapeRelay, Downloads, NuResolver) are Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // The default hierarchy plus a `jvmShared` group over both JVM targets, which
    // creates jvmSharedMain between commonMain and androidMain/desktopMain.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withAndroidTarget()
                withJvm()
            }
        }
    }

    sourceSets {
        val jvmSharedMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
                implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
                implementation("org.jetbrains.compose.ui:ui:1.10.3")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                // Frozen at 1.7.3 upstream (no newer release); fine for the icons used.
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")

                // Networking: Retrofit + OkHttp + kotlinx-serialization JSON.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("com.squareup.retrofit2:retrofit:2.11.0")
                implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

                // Image loading (covers / inline chapter images), through the same
                // OkHttp client as the API so requests carry the auth cookie.
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
                // Honour the server's Cache-Control/ETag (covers: max-age=300), as Coil 2
                // did by default; Coil 3 otherwise reuses a cached image forever.
                implementation("io.coil-kt.coil3:coil-network-cache-control:3.3.0")

                // Drag-to-reorder for the library grid.
                implementation("sh.calvin.reorderable:reorderable:3.1.0")

                // tar.bz2 extraction for downloaded TTS model packages.
                implementation("org.apache.commons:commons-compress:1.27.1")
            }
        }
        androidMain {
            dependencies {
                implementation("androidx.core:core-ktx:1.13.1")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.lifecycle:lifecycle-service:2.8.7")
                // MediaSession + media-style notification for background/lock-screen TTS.
                implementation("androidx.media:media:1.7.0")
                // On-device Kokoro/Piper TTS via sherpa-onnx (ONNX model + espeak-ng
                // phonemizer + voices).
                implementation(files("libs/sherpa-onnx-1.13.5.aar"))
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

android {
    namespace = "com.novelscraper.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novelscraper.app"
        minSdk = 26
        targetSdk = 34
        // Bump BOTH for every release you sideload: versionCode must increase
        // for Android to accept the install over a previous one, versionName is
        // what people see. See CHANGELOG.md.
        versionCode = 50
        versionName = "0.28.3"
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
// (composeApp/build/outputs/apk/release/composeApp-<abi>-release.apk) is fine for tooling but
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
    val src = layout.buildDirectory.file("outputs/apk/release/composeApp-arm64-v8a-release.apk")
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
