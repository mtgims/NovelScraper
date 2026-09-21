package com.novelscraper.app.net

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import kotlin.time.ExperimentalTime

/**
 * The app's image loader. Covers and inline images go through the same OkHttp
 * client as the API, so they carry the ns_session cookie (the cover endpoint
 * requires auth), and follow the server's cache headers so a replaced cover
 * shows up. [configure] adds platform settings (the desktop disk cache).
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
                    callFactory = { Net.client },
                    cacheStrategy = { CacheControlCacheStrategy() },
                ),
            )
        }
        .build()
