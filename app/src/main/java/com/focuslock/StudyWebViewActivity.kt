package com.focuslock

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class StudyWebViewActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val studyTerms = listOf(
        "study", "lesson", "past paper", "science", "history", "maths",
        "sinhala", "revision", "grade 11", "o/l", "tutorial", "lecture",
        "education", "school", "algebra", "biology", "chemistry", "physics",
        "geography", "english", "civics", "buddhism", "ict", "commerce"
    )

    private val allowedHosts = setOf(
        "m.youtube.com", "www.youtube.com", "youtube.com",
        "accounts.google.com", "consent.youtube.com"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_webview)
        webView = findViewById(R.id.studyWebView)

        webView.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            mediaPlaybackRequiresUserGesture      = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                filterUrl(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                filterUrl(url)

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectFocusStyles(view)
            }
        }
        webView.loadUrl("https://m.youtube.com")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    private fun filterUrl(raw: String): Boolean {
        val uri  = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (host !in allowedHosts) return true
        if (host.endsWith("google.com")) return false   // let auth through
        val path = uri.path.orEmpty()

        // Block Shorts
        if (path.contains("/shorts/")) {
            Toast.makeText(this, "Shorts blocked 🚫", Toast.LENGTH_SHORT).show()
            return true
        }
        // Block non-study searches
        if (path == "/results" || uri.getQueryParameter("search_query") != null) {
            val q = uri.getQueryParameter("search_query").orEmpty().lowercase(Locale.ROOT)
            if (studyTerms.none { q.contains(it) }) {
                Toast.makeText(this, "Study searches only 📚", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return false
    }

    private fun injectFocusStyles(view: WebView) {
        val js = """
            (function() {
              const css = `
                ytm-browse[page-subtype="home"],
                ytm-pivot-bar-renderer,
                ytm-rich-section-renderer,
                ytm-comments-entry-point-header-renderer,
                ytm-comment-thread-renderer,
                ytm-engagement-panel-section-list-renderer,
                ytm-reel-shelf-renderer,
                ytm-compact-autoplay-renderer { display:none!important; }
              `;
              function apply() {
                let s = document.getElementById('fl-style');
                if (!s) { s=document.createElement('style'); s.id='fl-style'; document.head.appendChild(s); }
                s.textContent = css;
              }
              apply();
              if (!window.__flObs) {
                window.__flObs = new MutationObserver(apply);
                window.__flObs.observe(document.documentElement,{childList:true,subtree:true});
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
