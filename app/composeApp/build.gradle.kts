import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.kotlin.multiplatform.library")
    // Android lint for the shared code (the KMP library plugin has none built in).
    id("com.android.lint")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.sqldelight")
    id("org.jetbrains.compose")
}

// The app's version lives in ../gradle.properties (shared with androidApp).
val appVersionName = providers.gradleProperty("appVersionName").get()

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
val sherpaJvm = tasks.register<DownloadFile>("sherpaJvm") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-jvm-$sherpaVersion.jar")
    sha256.set("77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b")
    dest.set(layout.buildDirectory.file("sherpa/sherpa-onnx-jvm-$sherpaVersion.jar"))
}
val sherpaNativeLinux = tasks.register<DownloadFile>("sherpaNativeLinux") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar")
    sha256.set("30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba")
    dest.set(layout.buildDirectory.file("sherpa/sherpa-onnx-native-lib-linux-x64-$sherpaVersion.jar"))
}
val sherpaNativeWindows = tasks.register<DownloadFile>("sherpaNativeWindows") {
    url.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar")
    sha256.set("33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039")
    dest.set(layout.buildDirectory.file("sherpa/sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar"))
}

// The speech library's native part is per platform, and jpackage builds for the
// machine it runs on, so the build takes the one this machine is.
val buildingOnWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val sherpaNative = if (buildingOnWindows) sherpaNativeWindows else sherpaNativeLinux

// The desktop app uses the same font files as Android, copied at build time.
val desktopFonts = tasks.register<Sync>("desktopFonts") {
    from("src/androidMain/res/font") { include("*.ttf"); into("font") }
    into(layout.buildDirectory.dir("generated/desktopFonts"))
}

// The shared code of every app, as a Kotlin Multiplatform library. Source sets:
//   jvmSharedMain  everything that runs the same on Android and desktop: models,
//                  networking, view models, screens, theme, extensions. Android,
//                  Linux and Windows are all JVM, so plain Java/JVM libraries
//                  (OkHttp, Retrofit, commons-compress) are usable here.
//   androidMain    Android specifics: the TTS foreground service, WebView-based
//                  helpers, DownloadManager, resources. The Android app itself
//                  (Application, Activity, packaging) is the androidApp module.
//   desktopMain    the Linux/Windows app, packaged here.
// Platform differences are `expect`/`actual` declarations in jvmSharedMain
// (see platform/Platform.kt), so each target is checked for completeness by the
// compiler rather than wired up at runtime.
kotlin {
    android {
        namespace = "com.novelscraper.app.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Fonts, icons and themes live here (res/), used by the code and the app.
        androidResources { enable = true }
        // Host tests (Robolectric) run the real framework code, resources included.
        withHostTest { isIncludeAndroidResources = true }
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
                // The Android library target (AGP's KMP plugin) and the desktop JVM.
                withCompilations {
                    it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm ||
                        it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm
                }
            }
        }
    }

    sourceSets {
        getByName("jvmSharedMain") {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
                implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
                implementation("org.jetbrains.compose.ui:ui:1.12.0")
                implementation("org.jetbrains.compose.material3:material3:1.9.0")
                // Frozen at 1.7.3 upstream (no newer release); fine for the icons used.
                implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.2")

                // Networking: Retrofit + OkHttp + kotlinx-serialization JSON.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("com.squareup.retrofit2:retrofit:2.11.0")
                implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
                implementation("com.squareup.okhttp3:okhttp:4.12.0")
                implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

                // Image loading (covers / inline chapter images), through the same
                // OkHttp client as the API so requests carry the auth cookie.
                implementation("io.coil-kt.coil3:coil-compose:3.6.3")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
                // Honour the server's Cache-Control/ETag (covers: max-age=300), as Coil 2
                // did by default; Coil 3 otherwise reuses a cached image forever.
                implementation("io.coil-kt.coil3:coil-network-cache-control:3.6.3")

                // Drag-to-reorder for the library grid.
                implementation("sh.calvin.reorderable:reorderable:3.1.0")

                // The local library database (see sqldelight {} below).
                implementation("app.cash.sqldelight:runtime:2.4.0")
                implementation("app.cash.sqldelight:coroutines-extensions:2.4.0")

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
                implementation("app.cash.sqldelight:android-driver:2.4.0")
                // On-device Kokoro/Piper TTS via sherpa-onnx (ONNX model + espeak-ng
                // phonemizer + voices). compileOnly: a library can't bundle a local
                // .aar, so androidApp packages it.
                compileOnly(files("libs/sherpa-onnx-1.13.5.aar"))
            }
        }
        getByName("desktopMain") {
            // Fonts copied from androidMain/res/font (see desktopFonts above).
            resources.srcDir(desktopFonts)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
                // BackHandler: Esc / the back gesture, through the navigation dispatcher.
                implementation("org.jetbrains.compose.ui:ui-backhandler:1.12.0")
                // The HTML parser Android's Html.fromHtml uses; see platform/HtmlPlainText.kt.
                implementation("org.ccil.cowan.tagsoup:tagsoup:1.2.1")
                // SQLite over JDBC (bundles the native library) for the library database.
                implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
                // A real browser (Chromium through JCEF) for sites that ask for a
                // browser check; its runtime is fetched on first use, not shipped.
                implementation("dev.datlag:kcef:2025.03.23")
                // The browser draws off-screen through JOGL, whose native libraries
                // ship as their own artifacts; without them it can't paint.
                implementation("org.jogamp.gluegen:gluegen-rt:2.5.0:natives-linux-amd64")
                implementation("org.jogamp.jogl:jogl-all:2.5.0:natives-linux-amd64")

                // Media keys, and the desktop's media widget, through MPRIS on D-Bus.
                // The desktop's media keys (MPRIS): a Linux protocol on a Unix
                // socket. The library is carried everywhere because the code that
                // speaks it is compiled everywhere; on Windows it is never
                // connected to, and nothing loads the socket transport.
                implementation("com.github.hypfvieh:dbus-java-core:5.1.1")
                implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")

                // sherpa-onnx for the Kokoro/Piper voices: the JVM binding plus
                // the native library for whichever platform is being built.
                implementation(files(sherpaJvm.flatMap { it.dest }, sherpaNative.flatMap { it.dest }))
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        getByName("androidHostTest") {
            dependencies {
                implementation(kotlin("test"))
                implementation("junit:junit:4.13.2")
                // Runs the real Android framework (Html.fromHtml) on the JVM.
                implementation("org.robolectric:robolectric:4.17")
            }
        }
    }
}

// The local library: novels, chapters, downloaded text, reading progress,
// collections. Schema and queries are the .sq files under
// src/jvmSharedMain/sqldelight; the Kotlin API is generated from them.
// Android 8 (minSdk 26) ships SQLite 3.18, so the dialect is pinned there and
// newer syntax (UPSERT, window functions) fails the build instead of the phone.
sqldelight {
    databases {
        create("LibraryDb") {
            packageName.set("com.novelscraper.app.db")
            srcDirs.setFrom("src/jvmSharedMain/sqldelight")
            dialect("app.cash.sqldelight:sqlite-3-18-dialect:2.4.0")
            schemaOutputDirectory.set(file("src/jvmSharedMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
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
    systemProperty("live.search", providers.gradleProperty("liveSearch").getOrElse(""))
    // LiveKokoroTest fetches a speech model and speaks with it.
    systemProperty("live.tts", providers.gradleProperty("liveTts").getOrElse(""))
    systemProperty("live.tts.out", providers.gradleProperty("liveTtsOut").getOrElse(""))
    // LiveBrowserTest drives the browser on this machine; needs a display.
    systemProperty("live.browser", providers.gradleProperty("liveBrowser").getOrElse(""))
    systemProperty("live.dump", providers.gradleProperty("liveDump").getOrElse(""))
    // Everything else runs without one: a test must never open a window, nor go
    // looking for a site's check in the browser the developer happens to have.
    systemProperty("novelscraper.tests", "true")
    // LiveChromeDownloadTest fetches a browser (a few hundred megabytes).
    systemProperty("fetch.browser", providers.gradleProperty("fetchBrowser").getOrElse(""))
    testLogging {
        if (providers.gradleProperty("livePlugins").isPresent ||
            providers.gradleProperty("liveBrowser").isPresent ||
            providers.gradleProperty("fetchBrowser").isPresent ||
            providers.gradleProperty("liveTts").isPresent
        ) showStandardStreams = true
    }
}

// The desktop app, Linux and Windows. `./gradlew :composeApp:run` starts it.
compose.desktop {
    application {
        mainClass = "com.novelscraper.app.MainKt"
        nativeDistributions {
            // The JDK modules beyond Compose's defaults (from suggestRuntimeModules);
            // java.sql is the JDBC API the library database's SQLite driver needs.
            modules("java.instrument", "java.management", "java.sql", "jdk.security.auth", "jdk.unsupported")
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "novelscraper"
            packageVersion = appVersionName
            description = "Read and listen to web novels"
            vendor = "mtgims"
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                // A per-user install, so it needs no administrator, and a shortcut
                // where a Windows program is looked for.
                perUserInstall = true
                menu = true
                menuGroup = "NovelScraper"
                shortcut = true
                dirChooser = true
                // Fixed, and never changed again: Windows uses it to know that a
                // new installer replaces this program rather than adding another.
                upgradeUuid = "6b4a1f4e-9a5a-4a1e-9a0e-4f1c3f0a52d7"
            }
        }
    }
}

// A single-file AppImage of the desktop app for Linux, dropped at the repo root
// as novelscraper-x86_64.AppImage (like the APK): the app image Compose builds
// (with its own Java runtime) wrapped by appimagetool. Both AppImage tools are
// downloaded into the build directory and checked against pinned SHA-256s.
val appImageTool = tasks.register<DownloadFile>("appImageTool") {
    url.set("https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage")
    sha256.set("ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0")
    dest.set(layout.buildDirectory.file("appimage-tools/appimagetool-x86_64.AppImage"))
}
val appImageRuntime = tasks.register<DownloadFile>("appImageRuntime") {
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
                # Java's windows are always X11 (XWayland under a Wayland session),
                # so the embedded browser (site checks) has to draw on X11 too:
                # a Wayland surface in an X11 window crashes Chromium outright.
                # Chromium reads both of these when it picks its backend.
                export OZONE_PLATFORM=x11
                export XDG_SESSION_TYPE=x11
                exec "${'$'}HERE/bin/novelscraper" "${'$'}@"
                """.trimIndent() + "\n",
            )
            setExecutable(true)
        }
        tool.get().asFile.setExecutable(true)
        val out = output.get().asFile
        // Build beside the old one and move it into place. A running AppImage is
        // a squashfs mounted from this very file: writing over it pulls the app's
        // own classes out from under it, and it dies with SIGBUS. A move leaves
        // the old file's contents alone for as long as something is reading them.
        val staged = File(out.parentFile, out.name + ".new")
        staged.delete()
        exec.exec {
            // Run appimagetool without FUSE; ARCH names the target.
            environment("APPIMAGE_EXTRACT_AND_RUN", "1")
            environment("ARCH", "x86_64")
            commandLine(tool.get().asFile.path, "--no-appstream", "--runtime-file", runtime.get().asFile.path,
                appDir.path, staged.path)
        }
        Files.move(
            staged.toPath(), out.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        out.setExecutable(true)
        logger.lifecycle("AppImage -> ${out.absolutePath}")
    }
}

val packageLinuxAppImage = tasks.register<BuildAppImage>("packageLinuxAppImage") {
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
