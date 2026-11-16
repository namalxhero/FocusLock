package com.focuslock

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
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

    // ── Study terms filter ───────────────────────────────────────────────────
    private val studyTerms = listOf(
        "study", "lesson", "past paper", "science", "history", "maths",
        "sinhala", "revision", "grade 11", "o/l", "tutorial", "lecture",
        "education", "school", "algebra", "biology", "chemistry", "physics",
        "geography", "english", "civics", "buddhism", "ict", "commerce",
        "ol", "grade11", "a/l", "grade 10", "medium"
    )

    private val allowedHosts = setOf(
        "m.youtube.com", "www.youtube.com", "youtube.com",
        "accounts.google.com", "consent.youtube.com"
    )

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

        // ── Build keyword chips ───────────────────────────────────────────────
        val container = findViewById<LinearLayout>(R.id.chipContainer)
        for ((label, query) in chips) {
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(0xFF7FDBBB.toInt())
                setPadding(24, 8, 24, 8)
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1A3A2A.toInt())
                    cornerRadius = 40f
                    setStroke(1, 0xFF2A5A44.toInt())
                }
                background = bg
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(6, 0, 6, 0) }
                layoutParams = lp
                setOnClickListener { loadSearch(query) }
            }
            container.addView(chip)
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

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    Toast.makeText(
                        this@StudyWebViewActivity,
                        "Load error: ${error.description}", Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
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
        loadSearch("maths grade 11 sinhala")
    }

    // ── Search ───────────────────────────────────────────────────────────────
    private fun performSearch() {
        val q = searchBar.text.toString().trim()
        if (q.isEmpty()) return
        hideKeyboard()

        val lower = q.lowercase(Locale.ROOT)
        if (studyTerms.none { lower.contains(it) }) {
            Toast.makeText(this, "📚 Study topics only! (maths, science, sinhala...)", Toast.LENGTH_SHORT).show()
            return
        }
        loadSearch(q)
    }

    private fun loadSearch(query: String) {
        val url = "https://m.youtube.com/results?search_query=${Uri.encode(query)}"
        webView.loadUrl(url)
        searchBar.setText(query)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
    }

    // ── URL filter ───────────────────────────────────────────────────────────
    private fun filterUrl(raw: String): Boolean {
        val uri  = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (host !in allowedHosts) {
            Toast.makeText(this, "Blocked: $host", Toast.LENGTH_SHORT).show()
            return true
        }
        if (host.endsWith("google.com")) return false

        val path = uri.path.orEmpty()

        // Block Shorts
        if (path.contains("/shorts/")) {
            Toast.makeText(this, "Shorts blocked 🚫", Toast.LENGTH_SHORT).show()
            return true
        }

        // Block non-study searches
        if (uri.getQueryParameter("search_query") != null) {
            val q = uri.getQueryParameter("search_query").orEmpty().lowercase(Locale.ROOT)
            if (studyTerms.none { q.contains(it) }) {
                Toast.makeText(this, "📚 Study searches only!", Toast.LENGTH_SHORT).show()
                return true
            }
        }

        return false
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
                '#shorts-container',
                'ytm-reel-item-renderer',
                '[data-content-type="shorts"]'
              ].join(',') + '{display:none!important}';
              function apply(){
                var s=document.getElementById('fl');
                if(!s){s=document.createElement('style');s.id='fl';document.head.appendChild(s);}
                s.textContent=css;
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
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
