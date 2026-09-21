package com.novelscraper.app.net

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.novelscraper.app.platform.appContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Volume downloads, handed to the system DownloadManager so they get a progress
 * notification, survive the app being backgrounded, and land somewhere the user
 * can open them from.
 *
 * The download endpoints need the session cookie, which DownloadManager knows
 * nothing about, so it is copied onto the request from our own cookie jar.
 */
actual object Downloads {

    /** One volume as an EPUB. */
    actual fun volume(bookId: Int, slug: String, volume: Int, title: String) =
        enqueue(
            url = "${Net.baseUrl}api/books/$bookId/download?volume=$volume",
            fileName = downloadFileName(slug, volume),
            title = "$title · volume $volume",
        )

    /** Every volume, zipped. */
    actual fun all(bookId: Int, slug: String, title: String) =
        enqueue(
            url = "${Net.baseUrl}api/books/$bookId/download-all",
            fileName = downloadFileName(slug),
            title = "$title · all volumes",
        )

    private fun enqueue(url: String, fileName: String, title: String) {
        val ctx = appContext
        val cookie = Net.cookieJar.loadForRequest(url.toHttpUrl())
            .joinToString("; ") { "${it.name}=${it.value}" }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .apply { if (cookie.isNotEmpty()) addRequestHeader("Cookie", cookie) }
        // The public Downloads folder needs no permission from Android 10 on. Before
        // that it would need WRITE_EXTERNAL_STORAGE, so older versions get the app's
        // own folder instead (still reachable from the download notification).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            req.setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
}
