package com.novelscraper.app.net

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import kotlin.time.ExperimentalTime

/**
 * The app's image loader. Covers and inline images follow the server's cache
 * headers, so a replaced cover shows up, and are fetched with [imageClient].
 * [configure] adds platform settings (the desktop disk cache).
 */
@OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
fun buildImageLoader(
    context: PlatformContext,
    configure: ImageLoader.Builder.() -> Unit = {},
): ImageLoader =
    ImageLoader.Builder(context)
        .apply(configure)
        .components {
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory = { imageClient() },
                    cacheStrategy = { CacheControlCacheStrategy() },
                ),
            )
        }
        .build()

/**
 * The client images are fetched with: the app's own, kept off this machine and
 * its local network. An image's address comes from a source, which is
 * third-party code, so it is guarded like the source's own requests
 * ([PrivateHosts]). [Net.client] itself stays unguarded: WebDAV sync may live
 * on a NAS at home.
 */
internal fun imageClient(): OkHttpClient {
    val base = Net.client
    guarded?.let { (from, client) -> if (from === base) return client }
    return PrivateHosts.guard(base.newBuilder()).build().also { guarded = base to it }
}

/** The guarded client and the [Net.client] it was built from (rebuilt if that changes). */
@Volatile private var guarded: Pair<OkHttpClient, OkHttpClient>? = null
