package com.novelscraper.app.net

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** One translation group read off a NovelUpdates series page. `latestNum` is the
 *  highest chapter number that group has released (accurate even though NU only
 *  shows recent rows, since recent releases are the newest); `extnu` links to that
 *  latest chapter and is resolved to the translator site, then rewound to ch.1. */
@Serializable
data class NuGroup(
    val name: String,
    val latestLabel: String = "",
    val latestNum: Double = 0.0,
    val extnu: String,
)

object NuExtract {
    fun isSeriesUrl(url: String?): Boolean =
        url != null && Regex("novelupdates\\.com/series/[^/]+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun isNovelUpdatesHost(url: String?): Boolean =
        url != null && Regex("://[^/]*novelupdates\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** JS over the series page's release table (#myTable): per translation group,
     *  keep the HIGHEST-numbered (latest) chapter and its /extnu/ link. */
    val EXTRACT_JS = """
        (function(){
          var rows = document.querySelectorAll('#myTable tr');
          var map = {};
          rows.forEach(function(tr){
            var g = tr.querySelector('a[href*="/group/"]');
            var c = tr.querySelector('a.chp-release');
            if(!g||!c) return;
            var name = (g.textContent||'').trim();
            var href = c.href;
            var label = (c.getAttribute('title')||c.textContent||'').trim();
            var m = label.match(/\d+(\.\d+)?/);
            var num = m ? parseFloat(m[0]) : 0;
            if(!map[name]) map[name] = {name:name, latestLabel:label, latestNum:num, extnu:href};
            else if(num > map[name].latestNum){ map[name].latestLabel=label; map[name].latestNum=num; map[name].extnu=href; }
          });
          return JSON.stringify(Object.keys(map).map(function(k){return map[k];}));
        })()
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse the (double-encoded) evaluateJavascript result, sorted by how far the
     *  group has translated (latest chapter desc). Empty on any parse issue. */
    fun parse(evalResult: String?): List<NuGroup> {
        if (evalResult == null || evalResult == "null") return emptyList()
        return try {
            val inner = json.parseToJsonElement(evalResult).jsonPrimitive.content
            json.decodeFromString<List<NuGroup>>(inner)
                .filter { it.name.isNotBlank() && it.extnu.isNotBlank() }
                .sortedByDescending { it.latestNum }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // A trailing chapter number near the end of the path (…/chapter-58, …-58.html).
    private val TRAILING_NUM = Regex("(\\d+)(\\D{0,6})$")

    /** Rewind a translator chapter URL to chapter 1 (…/…-58 -> …/…-1) so the
     *  scraper enumerates the whole novel forward. Returns the URL unchanged if the
     *  path has no trailing chapter number (non-sequential slug). */
    fun toChapterOne(url: String): String {
        val uri = Uri.parse(url)
        val path = uri.path ?: return url
        val m = TRAILING_NUM.find(path) ?: return url
        if (m.groupValues[1] == "1") return url
        val newPath = path.substring(0, m.range.first) + "1" + m.groupValues[2]
        return uri.buildUpon().path(newPath).build().toString()
    }
}
