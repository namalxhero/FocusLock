package com.focuslock

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Watches the WebView URL. When a /watch video opens it covers the player,
 * pauses it, reads title/description, asks VideoJudge, then either reveals
 * the video or closes the study screen (back to Focus Lock home).
 */
class VideoGate(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val overlay: View
) {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Boolean>()
    private var currentId: String? = null
    private var token = 0
    private var pendingTitle = ""

    init {
        VideoJudge.androidPackage = activity.packageName
        VideoJudge.androidCert = Updater.certSha1(activity)
    }

    private fun videoIdOf(url: String): String? {
        val uri = Uri.parse(url)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (!host.endsWith("youtube.com") || uri.path != "/watch") return null
        return uri.getQueryParameter("v")
    }

    fun onUrl(url: String) {
        val id = videoIdOf(url)
        if (id == null) {
            if (currentId != null) {
                currentId = null
                token++
                overlay.visibility = View.GONE
            }
            return
        }
        if (id == currentId) return
        currentId = id
        val my = ++token

        if (!Regex("^[A-Za-z0-9_-]{6,20}$").matches(id)) { reject("bad video id"); return }

        // Opened from our own study results: already vetted, no need to ask the AI again
        if (StudyFeed.isTrusted(id)) cache[id] = true

        val known = cache[id]
        if (known == true) {
            overlay.visibility = View.GONE
            handler.postDelayed({ startPlayback() }, 1200L)
            return
        }
        if (known == false) { reject("blocked earlier"); return }

        overlay.visibility = View.VISIBLE
        pause()
        handler.postDelayed({ collect(id, my, 0) }, 300L)
        // Safety net: never leave the screen covered for more than ~24s
        handler.postDelayed({ watchdog(id, my) }, 24000L)
    }

    /** Called every ~0.6s: keep the video paused while the check is running. */
    fun tick() {
        if (overlay.visibility == View.VISIBLE) pause()
    }

    fun destroy() {
        token++
        handler.removeCallbacksAndMessages(null)
    }

    private fun collect(id: String, my: Int, attempt: Int) {
        if (my != token) return
        val js = """
            (function(){
              var id='__ID__';
              function meta(n){var e=document.querySelector('meta[property="'+n+'"],meta[name="'+n+'"]');return e&&e.content?e.content:'';}
              var title='',desc='',channel='';
              try{
                var r=window.ytInitialPlayerResponse;
                if(r&&r.videoDetails&&r.videoDetails.videoId===id){
                  title=r.videoDetails.title||'';
                  desc=r.videoDetails.shortDescription||'';
                  channel=r.videoDetails.author||'';
                }
              }catch(e){}
              if(!title&&meta('og:url').indexOf(id)>=0){
                title=meta('og:title');
                desc=meta('og:description');
              }
              // m.youtube.com navigates without reloading, so the meta tags above can be stale.
              // The on-screen watch-page elements are live: read the title / channel / description from them.
              function txt(sel){var e=document.querySelector(sel);return e&&e.textContent?e.textContent.trim():'';}
              var here=(location.search||'').indexOf(id)>=0;
              if(here){
                if(!title)title=txt('.slim-video-information-title,ytm-slim-video-information-renderer h2,ytm-slim-video-metadata-section-renderer h2,h1.slim-video-metadata-title');
                if(!channel)channel=txt('.slim-owner-channel-name,ytm-slim-owner-renderer .yt-core-attributed-string,ytm-slim-owner-renderer a');
                if(!desc)desc=txt('ytm-expandable-video-description-body-renderer,.expandable-video-description-body-main');
                // last resort once the page has had time to load: the tab title
                if(!title&&__LATE__)title=(document.title||'').replace(/\s*-\s*YouTube\s*$/i,'').trim();
              }
              var v=document.querySelectorAll('video');
              for(var i=0;i<v.length;i++){try{v[i].pause();}catch(e){}}
              return JSON.stringify({title:title.trim(),desc:desc.trim().slice(0,1200),channel:channel.trim()});
            })()
        """.trimIndent().replace("__ID__", id).replace("__LATE__", if (attempt >= 5) "true" else "false")

        webView.evaluateJavascript(js) { raw ->
            if (my != token) return@evaluateJavascript
            var title = ""
            var desc = ""
            var channel = ""
            try {
                val s = JSONTokener(raw).nextValue()
                if (s is String) {
                    val o = JSONObject(s)
                    title = o.optString("title")
                    desc = o.optString("desc")
                    channel = o.optString("channel")
                }
            } catch (_: Exception) {}

            if (title.isBlank() && desc.isBlank() && attempt < 10) {
                handler.postDelayed({ collect(id, my, attempt + 1) }, 400L)
                return@evaluateJavascript
            }
            pendingTitle = title
            Thread {
                val verdict = VideoJudge.judgeVideo(id, title, channel, desc)
                activity.runOnUiThread { apply(id, my, verdict) }
            }.start()
        }
    }

    /** The AI took too long: decide from the title with the keyword filter instead. */
    private fun watchdog(id: String, my: Int) {
        if (my != token || activity.isFinishing || cache.containsKey(id)) return
        val ok = StudyFilter.isStudyQuery(pendingTitle)
        apply(id, my, VideoJudge.Verdict(ok, if (ok) "keyword match" else "check timed out", "timeout"))
    }

    /** The tap that opened the video happened in another WebView, so allow playback for a moment. */
    private fun startPlayback() {
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){try{v.play()}catch(e){}})",
            null
        )
        handler.postDelayed({ webView.settings.mediaPlaybackRequiresUserGesture = true }, 2500L)
    }

    private fun apply(id: String, my: Int, v: VideoJudge.Verdict) {
        if (my != token || activity.isFinishing) return
        cache[id] = v.study
        if (v.aiNote.isNotEmpty()) {
            Toast.makeText(activity, "⚠️ AI check ක්‍රියා කළේ නැහැ (${v.aiNote})", Toast.LENGTH_LONG).show()
        }
        if (v.study) {
            overlay.visibility = View.GONE
            startPlayback()
        } else {
            reject(v.reason)
        }
    }

    private fun reject(reason: String) {
        Toast.makeText(activity, "🚫 Study video එකක් නෙවෙයි ($reason)", Toast.LENGTH_LONG).show()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        // Back to the Focus Lock home screen
        try {
            activity.startActivity(
                Intent(activity, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (_: Exception) {}
        activity.finish()
    }

    private fun pause() {
        webView.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){try{v.pause()}catch(e){}})",
            null
        )
    }
}
