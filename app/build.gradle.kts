// Top-level build file. Plugin versions are declared here (apply false) and
// applied in the module build files: composeApp (the shared code, and the
// desktop app) and androidApp (the Android app).
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
    id("com.android.lint") version "9.4.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
    id("app.cash.sqldelight") version "2.4.0" apply false
}
