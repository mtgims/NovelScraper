package com.novelscraper.app.net

// Network helpers whose implementation depends on the platform.

/** File names for saved EPUBs: one volume, or the whole novel. */
fun downloadFileName(slug: String, volume: Int? = null): String {
    val safe = slug.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifEmpty { "book" }
    return if (volume != null) "$safe-volume-$volume.epub" else "$safe.epub"
}
