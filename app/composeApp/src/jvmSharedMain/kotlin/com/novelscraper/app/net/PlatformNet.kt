package com.novelscraper.app.net

// Network helpers whose implementation depends on the platform.

/** A file name safe on every platform: the novel's title reduced to plain
 *  characters, never empty. */
private fun safeName(slug: String): String =
    slug.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "book" }

/** One volume's EPUB. Without a number (a novel short enough to be one file),
 *  the name carries no volume at all. */
fun epubFileName(slug: String, volume: Int? = null): String =
    if (volume != null) "${safeName(slug)}-volume-$volume.epub" else "${safeName(slug)}.epub"

/** Several volumes travel together as one zip of EPUBs. */
fun zipFileName(slug: String): String = "${safeName(slug)}.zip"
