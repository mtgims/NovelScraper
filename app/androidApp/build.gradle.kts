// The Android app: the shell around the shared code in :composeApp (App,
// MainActivity, the manifest's application and activity), plus packaging.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.novelscraper.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.novelscraper.app"
        minSdk = 26
        targetSdk = 34
        // From ../gradle.properties, shared with the desktop app.
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
    }

    // Only the ABIs we target (the phone, arm64, and the emulator, x86_64),
    // packaged as SEPARATE APKs rather than one fat binary. The sherpa-onnx and
    // ONNX Runtime native libs are ~30MB per ABI, so a combined APK made every
    // phone download the x86_64 slice it can never run. Splitting gives the phone
    // an arm64-only APK and still produces an x86_64 one for the emulator.
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
            // Not debuggable, so ART/Compose run optimized. Signed with the debug
            // key so the release APK sideloads without extra key setup.
            //
            // R8 + resource shrinking. The keep rules in proguard-rules.pro cover
            // what R8 cannot see: sherpa-onnx's JNI classes, kotlinx-serialization's
            // generated serializers, Retrofit's reflective proxy, and
            // @JavascriptInterface members on the offscreen WebViews.
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

    // Lint the shared code (:composeApp) too, as part of this app.
    lint { checkDependencies = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG is handed to the shared code at startup (App.kt); it
        // gates the OkHttp request logging.
        buildConfig = true
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // sherpa-onnx (on-device voices); :composeApp compiles against it.
    implementation(files("../composeApp/libs/sherpa-onnx-1.13.5.aar"))
}

// Every release build drops the phone APK at the PROJECT ROOT as
// novelscraper.apk: one predictable file to install or send (the Gradle output
// dir holds two APKs since the ABI split, only one of which belongs on a phone).
// The emulator's x86_64 APK stays in the Gradle output dir.
//
// A single-file copy rather than a Copy task: the destination directory is the
// repo root, which contains the build dirs, so declaring it as a task output
// makes Gradle infer a phantom dependency and fail. One exact output file avoids
// that.
val copyApkToRoot = tasks.register("copyApkToRoot") {
    description = "Copy the arm64 release APK to <repo root>/novelscraper.apk"
    group = "build"
    // Resolved here so doLast closes over plain locals (configuration cache).
    val src = layout.buildDirectory.file("outputs/apk/release/androidApp-arm64-v8a-release.apk")
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
