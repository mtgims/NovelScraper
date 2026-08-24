package com.novelscraper.app.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** One translation group read off a NovelUpdates series page. */
@Serializable
data class NuGroup(
    val name: String,
    val count: Int,          // chapters visible in the release table (best-effort)
    val firstExtnu: String,  // the group's earliest visible chapter link (/extnu/…)
    val firstLabel: String = "",
)

object NuExtract {
    /** URL pattern that indicates a NovelUpdates series page (where groups live). */
    fun isSeriesUrl(url: String?): Boolean =
        url != null && Regex("novelupdates\\.com/series/[^/]+", RegexOption.IGNORE_CASE).containsMatchIn(url)

    fun isNovelUpdatesHost(url: String?): Boolean =
        url != null && Regex("://[^/]*novelupdates\\.com", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** JS run in the WebView over the series page's release table (#myTable). Groups
     *  each row by its translation group, counts visible chapters, and keeps the
     *  earliest chapter's /extnu/ link (lowest number parsed from the label). */
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
            var num = m ? parseFloat(m[0]) : 1e9;
            if(!map[name]) map[name] = {name:name, count:0, minNum:1e9, firstExtnu:href, firstLabel:label};
            var e = map[name];
            e.count++;
            if(num < e.minNum){ e.minNum = num; e.firstExtnu = href; e.firstLabel = label; }
          });
          return JSON.stringify(Object.keys(map).map(function(k){
            var e = map[k];
            return {name:e.name, count:e.count, firstExtnu:e.firstExtnu, firstLabel:e.firstLabel};
          }));
        })()
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    /** Parse the (double-encoded) result of evaluateJavascript into groups, sorted
     *  by chapter count desc. Returns empty on any parse issue. */
    fun parse(evalResult: String?): List<NuGroup> {
        if (evalResult == null || evalResult == "null") return emptyList()
        return try {
            // evaluateJavascript returns the JS string as a JSON string literal.
            val inner = json.parseToJsonElement(evalResult).jsonPrimitive.content
            json.decodeFromString<List<NuGroup>>(inner)
                .filter { it.name.isNotBlank() && it.firstExtnu.isNotBlank() }
                .sortedByDescending { it.count }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
