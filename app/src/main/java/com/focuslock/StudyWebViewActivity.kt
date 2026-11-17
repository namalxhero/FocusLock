package com.focuslock

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class StudyWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var searchBar: EditText
    private lateinit var videoGate: VideoGate

    private val handler = Handler(Looper.getMainLooper())
    private var lastSearch = "maths grade 11 sinhala"
    private var lastRedirectAt = 0L

    // ── Keyword chips ────────────────────────────────────────────────────────
    private val chips = listOf(
        "Maths" to "maths grade 11 sinhala",
        "Science" to "science grade 11 sinhala",
        "Sinhala" to "sinhala language grade 11",
        "English" to "english grade 11 ol",
        "History" to "history grade 11 sinhala",
        "Biology" to "biology grade 11 sinhala",
        "Chemistry" to "chemistry grade 11 sinhala",
        "Physics" to "physics grade 11 sinhala",
        "Geography" to "geography grade 11 sinhala",
        "Commerce" to "commerce grade 11 sinhala",
        "ICT" to "ict grade 11 sinhala",
        "Buddhism" to "buddhism grade 11 sinhala",
        "Civics" to "civics grade 11 sinhala",
        "Past Papers" to "ol past paper sinhala",
        "Education" to "education sinhala tutorial"
    )

    private val allowedHosts = setOf(
        "m.youtube.com", "www.youtube.com", "youtube.com",
        "accounts.google.com", "consent.youtube.com"
    )

    private fun isStudyQuery(q: String) = StudyFilter.isStudyQuery(q)

    // YouTube search filter "Type: Video" -> no playlists, channels or mixes
    private val videoOnlyFilter = "EgIQAQ=="

    /** Returns a message if this URL must be blocked, otherwise null. */
    private fun blockReason(raw: String): String? {
        val uri = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (host !in allowedHosts) return "Blocked: $host"
        if (host == "consent.youtube.com" || host.endsWith("google.com")) return null

        val path = uri.path.orEmpty()
        if (path.contains("/shorts")) return "Shorts blocked 🚫"
        if (path.isEmpty() || path == "/" || path.startsWith("/feed") ||
            path.startsWith("/hashtag") || path.startsWith("/gaming")
        ) return "Home / feeds blocked 🚫"

        val q = uri.getQueryParameter("search_query")
        if (q != null && !isStudyQuery(q)) return "📚 Study searches only!"
        return null
    }

    // YouTube navigates inside the page without a real page load, so we also
    // check the current URL regularly and push the user back to a study search.
    private fun enforceUrl(url: String?) {
        if (url.isNullOrEmpty() || url.startsWith("about:") || url.startsWith("data:")) return
        val reason = blockReason(url)
        if (reason == null) {
            val uri = Uri.parse(url)
            val q = uri.getQueryParameter("search_query")
            if (uri.path.orEmpty().startsWith("/results") && !q.isNullOrBlank()) {
                val sp = Uri.decode(uri.getQueryParameter("sp").orEmpty())
                if (sp != videoOnlyFilter) {
                    // Results page without the "Video" filter -> reload videos only
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastRedirectAt >= 1500L) {
                        lastRedirectAt = now
                        webView.stopLoading()
                        loadSearch(q)
                    }
                    return
                }
                lastSearch = q
            }
            videoGate.onUrl(url)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < 2500L) return
        lastRedirectAt = now
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        webView.stopLoading()
        loadSearch(lastSearch)
    }

    private val urlWatcher = object : Runnable {
        override fun run() {
            enforceUrl(webView.url)
            videoGate.tick()
            handler.postDelayed(this, 600L)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_webview)

        // Keep the top bar below the status bar and above the keyboard / nav bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.studyRoot)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        webView   = findViewById(R.id.studyWebView)
        searchBar = findViewById(R.id.searchBar)
        webView.setBackgroundColor(0xFF0A0A0F.toInt())
        videoGate = VideoGate(this, webView, findViewById(R.id.checkOverlay))

        // ── Back button ──────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack() else finish()
        }

        // ── Search button ────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnSearch).setOnClickListener {
            performSearch()
        }

        // ── Search bar enter key ─────────────────────────────────────────────
        searchBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                performSearch()
                true
            } else false
        }

        // ── Build keyword chips (YouTube-style pill filters) ────────────────────
        val container = findViewById<LinearLayout>(R.id.chipContainer)
        val chipViews = mutableListOf<TextView>()

        fun selectChip(selected: TextView) {
            for (c in chipViews) {
                val isSel = c === selected
                c.setBackgroundResource(if (isSel) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                c.setTextColor(if (isSel) 0xFF0F0F0F.toInt() else 0xFFF1F1F1.toInt())
            }
        }

        for ((index, pair) in chips.withIndex()) {
            val (label, query) = pair
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(32, 16, 32, 16)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6, 0, 6, 0) }
                layoutParams = lp
                setBackgroundResource(R.drawable.bg_chip_unselected)
                setTextColor(0xFFF1F1F1.toInt())
                setOnClickListener {
                    selectChip(this)
                    loadSearch(query)
                }
            }
            chipViews.add(chip)
            container.addView(chip)
            if (index == 0) selectChip(chip)   // highlight "Maths" (default search) on open
        }

        // ── WebView setup ────────────────────────────────────────────────────
        webView.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            mediaPlaybackRequiresUserGesture      = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = filterUrl(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                filterUrl(url)

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                enforceUrl(url)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@StudyWebViewActivity,
                        "Load error: ${error.description}", Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@StudyWebViewActivity,
                        "HTTP ${errorResponse.statusCode}", Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                injectFocusStyles(view)
            }
        }

        // Open with Education / Maths search by default
        loadSearch(lastSearch)
    }

    override fun onResume() {
        super.onResume()
        handler.post(urlWatcher)
    }

    override fun onPause() {
        handler.removeCallbacks(urlWatcher)
        super.onPause()
    }

    // ── Search ───────────────────────────────────────────────────────────────
    private fun performSearch() {
        val q = searchBar.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()

        if (!isStudyQuery(q)) {
            Toast.makeText(this, "📚 Study topics only! (maths, science, sinhala...)", Toast.LENGTH_SHORT).show()
            return
        }
        loadSearch(q)
    }

    private fun loadSearch(query: String) {
        lastSearch = query
        val url = "https://m.youtube.com/results?search_query=${Uri.encode(query)}" +
            "&sp=${Uri.encode(videoOnlyFilter)}"
        webView.loadUrl(url)
        searchBar.setText(query)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    // ── URL filter for normal page loads ─────────────────────────────────────
    private fun filterUrl(raw: String): Boolean {
        val reason = blockReason(raw) ?: return false
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        return true
    }

    // ── Inject CSS to hide non-study elements ────────────────────────────────
    private fun injectFocusStyles(view: WebView) {
        val js = """
            (function() {
              var css = [
                'ytm-browse[page-subtype="home"]',
                'ytm-pivot-bar-renderer',
                'ytm-rich-section-renderer',
                'ytm-reel-shelf-renderer',
                'ytm-compact-autoplay-renderer',
                'ytm-comments-entry-point-header-renderer',
                'ytm-shorts-lockup-view-model',
                'ytm-shorts-lockup-view-model-v2',
                'ytm-item-section-renderer[section-identifier="related-items"]',
                '#shorts-container',
                'ytm-reel-item-renderer',
                '[data-content-type="shorts"]',
                'a[href^="/shorts/"]'
              ].join(',') + '{display:none!important}';
              function apply(){
                if(document.getElementById('fl')) return;
                var s=document.createElement('style');
                s.id='fl';
                s.textContent=css;
                (document.head||document.documentElement).appendChild(s);
              }
              apply();
              if(!window.__fl){
                window.__fl=new MutationObserver(apply);
                window.__fl.observe(document.documentElement,{childList:true,subtree:true});
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(urlWatcher)
        videoGate.destroy()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
