package com.novelscraper.app.platform

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual val appVersion: String
    get() = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

actual val updateAssetName: String = "novelscraper.apk"

/**
 * Hand the downloaded package to the system installer.
 *
 * The app never replaces itself: Android does that, after asking, and only if
 * the reader has allowed this app to install packages. Anything else would be a
 * program quietly replacing itself on someone's phone.
 */
actual suspend fun installUpdate(file: File): Boolean = withContext(Dispatchers.Main) {
    try {
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("Updates", "couldn't open the installer: ${e.message}")
        false
    }
}
