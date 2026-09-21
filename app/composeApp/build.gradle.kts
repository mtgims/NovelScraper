import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

// The app's version, shared by the Android APK and the desktop app. Bump BOTH for
// every release: versionCode must increase for Android to accept the install over
// a previous one, versionName is what people see. See ../CHANGELOG.md.
val appVersionCode = 53
val appVersionName = "0.30.1"

// sherpa-onnx publishes its desktop JVM binding on GitHub releases, not Maven.
// Downloaded into the build directory and checked against a pinned SHA-256.
abstract class DownloadFile : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputFile abstract val dest: RegularFileProperty

    @TaskAction
    fun download() {
        val out = dest.get().asFile
        val tmp = File(out.parentFile, out.name + ".tmp").apply { parentFile.mkdirs() }
        URI(url.get()).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256").digest(tmp.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (digest != sha256.get()) {
            tmp.delete()
            throw GradleException("${url.get()}: SHA-256 $digest, expected ${sha256.get()}")
        }
        tmp.renameTo(out)
    }
}

val sherpaVersion = "1.13.8"
val sherpaJvm by tasks.registering(DownloadFile::class) {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-jvm-$sherpaVersion.jar")
    sha256.set("77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b")
    dest.set(layout.buildDirectory.file("sherpa/sherpa-onnx-jvm-$sherpaVersion.jar"))
}
val sherpaNativeLinux by tasks.registering(DownloadFile::class) {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar")
    sha256.set("30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba")
    dest.set(layout.buildDirectory.file("sherpa/sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar"))
}

// The desktop app uses the same font files as Android, copied at build time.
val desktopFonts by tasks.registering(Sync::class) {
    from("src/androidMain/res/font") { include("*.ttf"); into("font") }
    into(layout.buildDirectory.dir("generated/desktopFonts"))
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

                // QuickJS, the JavaScript engine that runs source extensions (plugins).
                implementation("io.github.dokar3:quickjs-kt:1.0.15")
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
            // Fonts copied from androidMain/res/font (see desktopFonts above).
            resources.srcDir(desktopFonts)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
                // BackHandler: Esc / the back gesture, through the navigation dispatcher.
                implementation("org.jetbrains.compose.ui:ui-backhandler:1.10.3")
                // The HTML parser Android's Html.fromHtml uses; see platform/HtmlPlainText.kt.
                implementation("org.ccil.cowan.tagsoup:tagsoup:1.2.1")
                // sherpa-onnx for the Kokoro/Piper voices: the JVM binding plus the
                // native library for Linux x64 (downloaded by sherpaJvm/sherpaNativeLinux).
                implementation(files(sherpaJvm.flatMap { it.dest }, sherpaNativeLinux.flatMap { it.dest }))
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
                implementation("junit:junit:4.13.2")
                // Runs the real Android framework (Html.fromHtml) on the JVM.
                implementation("org.robolectric:robolectric:4.17")
            }
        }
    }
}

android {
    namespace = "com.novelscraper.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.novelscraper.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
    // Resources of the shared source set (the plugin host, built-in extensions)
    // aren't packaged into the APK on their own; they're read as Java resources.
    sourceSets["main"].resources.srcDir("src/jvmSharedMain/resources")

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

// html-parity tests (platform/HtmlParity*Test.kt): both test tasks read the
// fixtures and goldens from src/htmlParity. -PhtmlParityRecord=true makes the
// Android test rewrite the goldens; -PhtmlParityExtra=<dir> checks a local folder.
tasks.withType<Test>().configureEach {
    // Desktop tests keep settings/data/downloads in the build dir, never in ~.
    val home = layout.buildDirectory.dir("test-home").get().asFile
    environment("XDG_CONFIG_HOME", File(home, "config").path)
    environment("XDG_DATA_HOME", File(home, "data").path)
    environment("XDG_CACHE_HOME", File(home, "cache").path)
    environment("XDG_DOWNLOAD_DIR", File(home, "downloads").path)
    systemProperty("htmlParity.dir", layout.projectDirectory.dir("src/htmlParity").asFile.absolutePath)
    systemProperty("htmlParity.record", providers.gradleProperty("htmlParityRecord").getOrElse("false"))
    systemProperty("htmlParity.extra", providers.gradleProperty("htmlParityExtra").getOrElse(""))
    // LivePluginTest (real sites) runs only with -PlivePlugins=<id>[,<id>...].
    systemProperty("live.plugins", providers.gradleProperty("livePlugins").getOrElse(""))
    systemProperty("live.repo", providers.gradleProperty("liveRepo").getOrElse(""))
    testLogging { if (providers.gradleProperty("livePlugins").isPresent) showStandardStreams = true }
}

// The desktop app (Linux; Windows later). `./gradlew :composeApp:run` starts it.
compose.desktop {
    application {
        mainClass = "com.novelscraper.app.MainKt"
        nativeDistributions {
            // The JDK modules beyond Compose's defaults (from suggestRuntimeModules).
            modules("java.instrument", "jdk.unsupported")
            packageName = "novelscraper"
            packageVersion = appVersionName
            description = "Read and listen to web novels"
            vendor = "mtgims"
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
        }
    }
}

// A single-file AppImage of the desktop app for Linux, dropped at the repo root
// as novelscraper-x86_64.AppImage (like the APK): the app image Compose builds
// (with its own Java runtime) wrapped by appimagetool. Both AppImage tools are
// downloaded into the build directory and checked against pinned SHA-256s.
val appImageTool by tasks.registering(DownloadFile::class) {
    url.set("https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage")
    sha256.set("ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0")
    dest.set(layout.buildDirectory.file("appimage-tools/appimagetool-x86_64.AppImage"))
}
val appImageRuntime by tasks.registering(DownloadFile::class) {
    url.set("https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64")
    sha256.set("2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d")
    dest.set(layout.buildDirectory.file("appimage-tools/runtime-x86_64"))
}

abstract class BuildAppImage : DefaultTask() {
    @get:InputDirectory abstract val appImageDir: DirectoryProperty
    @get:InputFile abstract val icon: RegularFileProperty
    @get:InputFile abstract val tool: RegularFileProperty
    @get:InputFile abstract val runtime: RegularFileProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:OutputFile abstract val output: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun build() {
        val appDir = workDir.get().dir("NovelScraper.AppDir").asFile
        appDir.deleteRecursively()
        fs.copy { from(appImageDir); into(appDir) }
        icon.get().asFile.copyTo(File(appDir, "novelscraper.png"), overwrite = true)
        File(appDir, "novelscraper.desktop").writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=NovelScraper
            Comment=Read and listen to web novels
            Exec=novelscraper
            Icon=novelscraper
            Categories=Office;Viewer;
            Terminal=false
            """.trimIndent() + "\n",
        )
        File(appDir, "AppRun").apply {
            writeText(
                """
                #!/bin/sh
                HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
                exec "${'$'}HERE/bin/novelscraper" "${'$'}@"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        tool.get().asFile.setExecutable(true)
        val out = output.get().asFile
        out.delete()
        exec.exec {
            // Run appimagetool without FUSE; ARCH names the target.
            environment("APPIMAGE_EXTRACT_AND_RUN", "1")
            environment("ARCH", "x86_64")
            commandLine(tool.get().asFile.path, "--no-appstream", "--runtime-file", runtime.get().asFile.path,
                appDir.path, out.path)
        }
        logger.lifecycle("AppImage -> ${out.absolutePath}")
    }
}

val packageLinuxAppImage by tasks.registering(BuildAppImage::class) {
    description = "Build <repo root>/novelscraper-x86_64.AppImage"
    group = "distribution"
    dependsOn("createDistributable")
    appImageDir.set(layout.buildDirectory.dir("compose/binaries/main/app/novelscraper"))
    icon.set(layout.projectDirectory.file("src/desktopMain/resources/icon.png"))
    tool.set(appImageTool.flatMap { it.dest })
    runtime.set(appImageRuntime.flatMap { it.dest })
    workDir.set(layout.buildDirectory.dir("appimage"))
    output.set(rootProject.layout.projectDirectory.dir("..").file("novelscraper-x86_64.AppImage"))
}
