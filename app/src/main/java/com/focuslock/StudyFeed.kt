package com.focuslock

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Builds a YouTube-app-style list of STUDY videos for a search query.
 * fetch() is blocking -> call it on a background thread.
 * Every card links to the normal watch page, so VideoGate still checks the video.
 */
object StudyFeed {
    data class Card(
        val id: String,
        val title: String,
        val channel: String,
        val meta: String,
        val length: String
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val ID_RE = Regex("^[A-Za-z0-9_-]{6,20}$")
    private const val MAX_CARDS = 30

    // Videos we listed ourselves (study searches, already filtered): no AI re-check needed
    private val trusted: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isTrusted(id: String): Boolean = trusted.contains(id)

    // ── Fetch ────────────────────────────────────────────────────────────────
    fun fetch(query: String): List<Card> {
        val cards = fetchRaw(query)
        for (c in cards) trusted.add(c.id)
        return cards
    }

    private fun fetchRaw(query: String): List<Card> {
        val q = URLEncoder.encode(query, "UTF-8")
        // sp=EgIQAQ%3D%3D  ->  "Type: Video" (no playlists / channels / shorts shelves)
        val conn = URL("https://www.youtube.com/results?search_query=$q&sp=EgIQAQ%3D%3D&hl=en")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 7000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9,si;q=0.8")
        conn.setRequestProperty("Cookie", "CONSENT=YES+1")
        if (conn.responseCode != 200) return emptyList()
        val sb = StringBuilder()
        conn.inputStream.bufferedReader().use { r ->
            val buf = CharArray(16384)
            while (sb.length < 2_000_000) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return parse(sb.toString())
    }

    // ── Parse ────────────────────────────────────────────────────────────────
    fun parse(html: String): List<Card> {
        val json = extractInitialData(html) ?: return emptyList()
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<Card>()
        walk(root, out, HashSet())
        return out.filter { !StudyFilter.isBlockedText(it.title + " " + it.channel) }
            .take(MAX_CARDS)
    }

    private fun extractInitialData(html: String): String? {
        val m = Regex("""ytInitialData['"\]]*\s*=\s*\{""").find(html) ?: return null
        val start = m.range.last
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until html.length) {
            val c = html[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private val rendererKeys = listOf("videoRenderer", "compactVideoRenderer", "videoWithContextRenderer")

    private fun walk(node: Any?, out: MutableList<Card>, seen: MutableSet<String>) {
        if (out.size >= MAX_CARDS * 2) return
        when (node) {
            is JSONObject -> {
                for (k in rendererKeys) {
                    val r = node.optJSONObject(k) ?: continue
                    val c = toCard(r) ?: continue
                    if (seen.add(c.id)) out.add(c)
                }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), out, seen)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out, seen)
        }
    }

    private fun toCard(o: JSONObject): Card? {
        val id = o.optString("videoId")
        if (!ID_RE.matches(id)) return null
        val title = text(o.opt("title")).ifBlank { text(o.opt("headline")) }.trim()
        if (title.isBlank()) return null
        // no length = live / upcoming / short clip -> skip
        val length = text(o.opt("lengthText")).trim()
        if (length.isBlank()) return null
        val channel = text(o.opt("ownerText")).ifBlank { text(o.opt("longBylineText")) }
            .ifBlank { text(o.opt("shortBylineText")) }.trim()
        val views = text(o.opt("shortViewCountText")).ifBlank { text(o.opt("viewCountText")) }.trim()
        val ago = text(o.opt("publishedTimeText")).trim()
        val meta = listOf(views, ago).filter { it.isNotBlank() }.joinToString(" • ")
        return Card(id, title, channel, meta, length)
    }

    private fun text(n: Any?): String {
        val o = n as? JSONObject ?: return ""
        val simple = o.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    // ── Endless scrolling: a different study search each time the list runs out ──
    fun variants(query: String): List<String> {
        val q = query.trim()
        return listOf(
            q, "$q lesson", "$q past paper", "$q revision", "$q model paper",
            "$q important questions", "$q tutorial", "$q exam tips", "$q explained"
        ).distinct()
    }

    /** Hands out more cards (blocking: use a background thread). */
    class Pager(query: String, seenIds: Collection<String>, startStep: Int) {
        private val queries = StudyFeed.variants(query)
        private val seen = HashSet<String>(seenIds)
        private var step = startStep
        @Volatile var busy = false

        fun hasMore(): Boolean = step < queries.size

        fun next(): List<Card> {
            val out = ArrayList<Card>()
            while (out.isEmpty() && step < queries.size) {
                val cards = try { StudyFeed.fetch(queries[step]) } catch (_: Exception) { emptyList() }
                step++
                for (c in cards) if (seen.add(c.id)) out.add(c)
            }
            return out
        }
    }

    // ── Render (dark YouTube look) ───────────────────────────────────────────
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private val avatarColors = listOf(
        "#E53935", "#8E24AA", "#3949AB", "#039BE5", "#00897B", "#7CB342", "#F4511E", "#6D4C41"
    )

    private val cardRules = listOf(
        "a.card" to "display:block;color:inherit;text-decoration:none;margin-bottom:14px",
        ".thumb" to "position:relative;width:100%;padding-top:56.25%;background:#1e1e1e;overflow:hidden",
        ".thumb img" to "position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover",
        ".dur" to "position:absolute;right:8px;bottom:8px;background:rgba(0,0,0,.82);color:#fff;" +
            "font-size:12px;font-weight:500;padding:2px 5px;border-radius:4px",
        ".row" to "display:flex;padding:10px 12px 4px 12px",
        ".av" to "flex:0 0 36px;height:36px;border-radius:50%;margin-right:12px;color:#fff;" +
            "font-weight:600;font-size:16px;display:flex;align-items:center;justify-content:center",
        ".tx" to "min-width:0;flex:1",
        ".t" to "font-size:15px;line-height:1.3;font-weight:500;display:-webkit-box;" +
            "-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden",
        ".m" to "color:#aaa;font-size:12px;margin-top:4px;line-height:1.35"
    )

    private fun cardCss(scope: String): String =
        cardRules.joinToString("") { scope + it.first + "{" + it.second + "}" }

    /** JS string literal for [s] (safe inside evaluateJavascript). */
    fun jsString(s: String): String =
        JSONObject.quote(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    fun cardsHtml(cards: List<Card>): String {
        val sb = StringBuilder()
        for (c in cards) {
            val letter = (c.channel.trim().firstOrNull() ?: 'S').uppercaseChar().toString()
            val color = avatarColors[(c.channel.hashCode() and 0x7fffffff) % avatarColors.size]
            val sub = listOf(c.channel, c.meta).filter { it.isNotBlank() }.joinToString(" • ")
            sb.append("<a class=\"card\" href=\"https://m.youtube.com/watch?v=").append(c.id).append("\">")
            sb.append("<div class=\"thumb\"><img loading=\"lazy\" src=\"https://i.ytimg.com/vi/")
                .append(c.id).append("/hqdefault.jpg\" alt=\"\">")
            sb.append("<span class=\"dur\">").append(esc(c.length)).append("</span></div>")
            sb.append("<div class=\"row\"><div class=\"av\" style=\"background:").append(color)
                .append("\">").append(esc(letter)).append("</div><div class=\"tx\">")
            sb.append("<div class=\"t\">").append(esc(c.title)).append("</div>")
            sb.append("<div class=\"m\">").append(esc(sub)).append("</div></div></div></a>")
        }
        return sb.toString()
    }

    // Shared by the results page: append more cards when the bottom is near
    private const val PAGE_JS = """
window.flAppend=function(html){var l=document.getElementById('fl-list');if(!l)return;
var d=document.createElement('div');d.innerHTML=html;while(d.firstChild)l.appendChild(d.firstChild);
if(window.__flIO){var s=document.getElementById('fl-sent');window.__flIO.unobserve(s);window.__flIO.observe(s);}};
window.flDone=function(){if(window.__flIO)window.__flIO.disconnect();
var e=document.getElementById('fl-end');if(e)e.textContent='• • •';};
if('IntersectionObserver' in window){
window.__flIO=new IntersectionObserver(function(e){if(e[0].isIntersecting&&window.FLBridge)FLBridge.more();},{rootMargin:'1200px'});
window.__flIO.observe(document.getElementById('fl-sent'));}
"""

    fun renderHtml(query: String, cards: List<Card>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>")
        sb.append("*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}")
        sb.append("body{margin:0;background:#0f0f0f;color:#fff;font-family:Roboto,Arial,sans-serif}")
        sb.append(".hdr{padding:10px 14px;color:#aaa;font-size:12px}")
        sb.append(".empty{padding:40px 24px;text-align:center;color:#aaa;font-size:14px}")
        sb.append(cardCss(""))
        sb.append("</style></head><body>")
        sb.append("<div class=\"hdr\">Study results · ").append(esc(query)).append("</div>")
        if (cards.isEmpty()) {
            sb.append("<div class=\"empty\">No study videos found.<br>Try another topic.</div>")
        }
        sb.append("<div id=\"fl-list\">").append(cardsHtml(cards)).append("</div>")
        sb.append("<div id=\"fl-sent\" style=\"height:1px\"></div>")
        sb.append("<div id=\"fl-end\" style=\"height:56px;text-align:center;color:#555\"></div>")
        sb.append("<script>").append(PAGE_JS).append("</script></body></html>")
        return sb.toString()
    }

    /**
     * JS that puts a "More study videos" list under the playing video on YouTube's own watch page
     * (YouTube's related videos are hidden), and keeps it there. Scrolling near the end asks Kotlin for more.
     */
    private const val INJECT_JS = """
(function(){
  var ID='fl-related';
  window.flAppend=function(html){var l=document.getElementById('fl-list');if(!l)return;
    var d=document.createElement('div');d.innerHTML=html;while(d.firstChild)l.appendChild(d.firstChild);
    if(window.__flIO&&window.__flSent){window.__flIO.unobserve(window.__flSent);window.__flIO.observe(window.__flSent);}};
  window.flDone=function(){if(window.__flIO)window.__flIO.disconnect();
    var e=document.getElementById('fl-end');if(e)e.textContent='• • •';};
  function build(){
    var c=document.createElement('div');c.id=ID;
    c.style.cssText='background:#0f0f0f;color:#fff;padding-top:8px;font-family:Roboto,Arial,sans-serif';
    c.innerHTML='<style>#fl-related *{box-sizing:border-box}'+__CSS__+
      '</style><div style="padding:10px 14px;color:#aaa;font-size:12px">More study videos</div>'+
      '<div id="fl-list">'+__HTML__+'</div><div id="fl-sent" style="height:1px"></div>'+
      '<div id="fl-end" style="height:64px;text-align:center;color:#555"></div>';
    return c;
  }
  function ensure(){
    if(document.getElementById(ID))return;
    var host=document.querySelector('ytm-watch')||document.body;
    host.appendChild(build());
    var s=document.getElementById('fl-sent');window.__flSent=s;
    if('IntersectionObserver' in window){
      if(window.__flIO)window.__flIO.disconnect();
      window.__flIO=new IntersectionObserver(function(e){if(e[0].isIntersecting&&window.FLBridge)FLBridge.more();},{rootMargin:'1200px'});
      window.__flIO.observe(s);
    }
  }
  ensure();
  if(!window.__flKeep){window.__flKeep=setInterval(ensure,1500);}
})();
"""

    fun injectScript(cardsHtml: String): String =
        INJECT_JS.replace("__CSS__", jsString(cardCss("#fl-related ")))
            .replace("__HTML__", jsString(cardsHtml))
}
