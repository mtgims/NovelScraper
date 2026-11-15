package com.novelscraper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.novelscraper.app.extensions.Extensions
import com.novelscraper.app.extensions.MissingCovers
import com.novelscraper.app.extensions.NovelItem
import com.novelscraper.app.library.LibBook
import com.novelscraper.app.net.Net

/** An image from the web, sent with [headers] (some sources want a Referer). */
@Composable
fun RemoteImage(
    url: String?,
    headers: Map<String, String>,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(url)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * A listed novel's cover: the list's own, else looked up while the novel is on
 * screen, for sources whose lists leave covers out ([MissingCovers]).
 */
@Composable
fun listedCover(pluginId: String, novel: NovelItem): String? {
    val cover by produceState(novel.cover, pluginId, novel.path) {
        if (value.isNullOrBlank()) MissingCovers.of(pluginId, novel.path)?.let { value = it }
    }
    return cover
}

/** The cover image URL for a library novel, or null for none. */
fun coverUrl(book: LibBook): String? = book.cover?.takeIf { it.isNotBlank() }

/** A novel's cover filling [modifier], over its initials (shown while loading or
 *  when there is no cover). */
@Composable
fun BookCover(book: LibBook, modifier: Modifier, initialsStyle: TextStyle = MaterialTheme.typography.headlineMedium) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text(book.title.take(2).uppercase(), style = initialsStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        RemoteImage(coverUrl(book), Extensions.imageHeaders(book.pluginId), book.title, Modifier.fillMaxSize())
    }
}
