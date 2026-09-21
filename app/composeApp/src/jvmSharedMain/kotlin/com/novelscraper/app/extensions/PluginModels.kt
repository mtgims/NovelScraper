package com.novelscraper.app.extensions

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// What LNReader-format plugins return (lnreader/src/plugins/types/index.ts).
// Paths are site-relative keys the plugin resolves itself; they are what a novel
// or chapter is identified by.

@Serializable
data class NovelItem(
    val name: String,
    val path: String,
    val cover: String? = null,
)

@Serializable
data class ChapterItem(
    val name: String,
    val path: String,
    val releaseTime: String? = null,
    val chapterNumber: Double? = null,
    val page: String? = null,
)

@Serializable
data class SourceNovel(
    val name: String = "",
    val path: String = "",
    val cover: String? = null,
    val genres: String? = null,
    val summary: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: String? = null,
    val chapters: List<ChapterItem> = emptyList(),
    val totalPages: Int? = null,
)

@Serializable
data class SourcePage(val chapters: List<ChapterItem> = emptyList())

/** A loaded plugin's identity and capabilities, as it reports them. */
@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val site: String,
    val lang: String? = null,
    val icon: String? = null,
    val hasFilters: Boolean = false,
    val hasParsePage: Boolean = false,
    val hasResolveUrl: Boolean = false,
    /** Headers to send when loading this source's images (covers). */
    val imageRequestInit: JsonObject? = null,
)
