package com.novelscraper.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

/** A series read off NovelUpdates: its metadata (title/author, for the scrape) plus
 *  the translation groups to choose from. */
@Serializable
data class NuSeries(
    val title: String = "",
    val author: String = "",
    val groups: List<NuGroup> = emptyList(),
)

object NuExtract {
    fun isSeriesUrl(url: String?): Boolean =
        url != null && Regex("novelupdates\\.com/series/[^/]+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun isNovelUpdatesHost(url: String?): Boolean =
        url != null && Regex("://[^/]*novelupdates\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** JS over the series page: the novel's title (.seriestitlenu) + author(s)
     *  (#showauthors), and per translation group the HIGHEST-numbered (latest)
     *  chapter with its /extnu/ link. */
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
          var t = document.querySelector('.seriestitlenu');
          var title = t ? (t.textContent||'').trim() : '';
          var au = [].slice.call(document.querySelectorAll('#showauthors a'))
                     .map(function(a){return (a.textContent||'').trim();})
                     .filter(function(x){return x;});
          return JSON.stringify({
            title: title,
            author: au.join(', '),
            groups: Object.keys(map).map(function(k){return map[k];})
          });
        })()
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse the (double-encoded) evaluateJavascript result into a series with its
     *  groups sorted by how far each has translated (latest chapter desc). */
    fun parse(evalResult: String?): NuSeries {
        if (evalResult == null || evalResult == "null") return NuSeries()
        return try {
            val inner = json.parseToJsonElement(evalResult).jsonPrimitive.content
            val s = json.decodeFromString<NuSeries>(inner)
            s.copy(groups = s.groups
                .filter { it.name.isNotBlank() && it.extnu.isNotBlank() }
                .sortedByDescending { it.latestNum })
        } catch (e: Exception) {
            NuSeries()
        }
    }

    // A trailing chapter number near the end of the path (…/chapter-58, …-58.html).
    private val TRAILING_NUM = Regex("(\\d+)(\\D{0,6})$")

    /** Rewind a translator chapter URL to chapter 1 (…/…-58 -> …/…-1) so the
     *  scraper enumerates the whole novel forward. Returns the URL unchanged if the
     *  path has no trailing chapter number (non-sequential slug). */
    fun toChapterOne(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val path = parsed.encodedPath
        val m = TRAILING_NUM.find(path) ?: return url
        if (m.groupValues[1] == "1") return url
        val newPath = path.substring(0, m.range.first) + "1" + m.groupValues[2]
        return parsed.newBuilder().encodedPath(newPath).build().toString()
    }
}
