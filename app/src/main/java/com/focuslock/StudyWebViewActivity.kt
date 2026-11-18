package com.focuslock

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale
import java.util.concurrent.Executors

class StudyWebViewActivity : AppCompatActivity() {

    companion object {
        /** True while the study screen is in front (the auto-updater waits while this is true). */
        @Volatile var active = false
    }

    private enum class Mode { NONE, FULL, MINI }

    private lateinit var webView: WebView        // study results (cards)
    private lateinit var playerView: WebView     // the video page: full screen or mini player
    private lateinit var playerFrame: FrameLayout
    private lateinit var surface: FrameLayout
    private lateinit var miniControls: FrameLayout
    private lateinit var miniPlay: TextView
    private lateinit var searchBar: EditText
    private lateinit var videoGate: VideoGate

    private val handler = Handler(Looper.getMainLooper())
    private var lastSearch = "maths grade 11 sinhala"
    private var lastRedirectAt = 0L

    private var mode = Mode.NONE
    private var playerVideoId: String? = null
    private var lastSurfaceW = 0
    private var lastSurfaceH = 0

    // Endless lists: results page (feedPager) and the list under the playing video (relatedPager)
    private var feedPager: StudyFeed.Pager? = null
    private var relatedPager: StudyFeed.Pager? = null
    private var relatedToken = 0
    private var relatedForId: String? = null
    private var relatedAt = 0L

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

    private val idRegex = Regex("^[A-Za-z0-9_-]{6,20}$")

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

    /** The player page may only ever show a watch page (no channels, home, results...). */
    private fun playerBlockReason(raw: String): String? {
        val uri = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        if (host !in allowedHosts) return "Blocked: $host"
        if (host == "consent.youtube.com" || host.endsWith("google.com")) return null
        if (uri.path != "/watch") return "Study videos only 📚"
        return blockReason(raw)
    }

    // ── Results page URL watcher ─────────────────────────────────────────────
    private fun enforceFeedUrl(url: String?) {
        if (url.isNullOrEmpty() || url.startsWith("about:") || url.startsWith("data:")) return
        val reason = blockReason(url)
        if (reason == null) {
            val uri = Uri.parse(url)
            if (uri.path == "/watch") {
                // YouTube's own results page opened a video: play it in the player instead
                openVideo(url)
                handler.post { if (webView.canGoBack()) webView.goBack() }
                return
            }
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
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirectAt < 2500L) return
        lastRedirectAt = now
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        webView.stopLoading()
        loadSearch(lastSearch)
    }

    // ── Player URL watcher ───────────────────────────────────────────────────
    private fun enforcePlayerUrl(url: String?) {
        if (url.isNullOrEmpty() || url.startsWith("about:") || url.startsWith("data:")) return
        val reason = playerBlockReason(url)
        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            closePlayer()
            return
        }
        if (Uri.parse(url).path == "/watch") videoGate.onUrl(url)
    }

    private val urlWatcher = object : Runnable {
        override fun run() {
            enforceFeedUrl(webView.url)
            if (mode != Mode.NONE) enforcePlayerUrl(playerView.url)
            videoGate.tick()
            if (mode == Mode.MINI) updateMiniPlayIcon()
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

        webView      = findViewById(R.id.studyWebView)
        playerView   = findViewById(R.id.playerWebView)
        playerFrame  = findViewById(R.id.playerFrame)
        surface      = findViewById(R.id.surface)
        miniControls = findViewById(R.id.miniControls)
        miniPlay     = findViewById(R.id.miniPlay)
        searchBar    = findViewById(R.id.searchBar)
        webView.setBackgroundColor(0xFF0A0A0F.toInt())
        playerView.setBackgroundColor(0xFF000000.toInt())
        videoGate = VideoGate(this, playerView, findViewById(R.id.checkOverlay))

        // ── Back button ──────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnBack).setOnClickListener { handleBack() }

        // ── Search button ────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnSearch).setOnClickListener { performSearch() }

        // ── Search bar enter key ─────────────────────────────────────────────
        searchBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER)) {
                performSearch()
                true
            } else false
        }

        // ── Mini player controls ─────────────────────────────────────────────
        miniControls.setOnClickListener { expandPlayer() }
        miniPlay.setOnClickListener { toggleMiniPlay() }
        findViewById<TextView>(R.id.miniClose).setOnClickListener { closePlayer() }

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

        setupWebView(webView, false)
        setupWebView(playerView, true)

        // Rotating the phone while the mini player is showing: measure it again
        surface.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = r - l
            val h = b - t
            if (w != lastSurfaceW || h != lastSurfaceH) {
                lastSurfaceW = w
                lastSurfaceH = h
                if (mode == Mode.MINI) {
                    handler.postDelayed({
                        if (mode == Mode.MINI) {
                            expandPlayer()
                            minimizePlayer()
                        }
                    }, 350L)
                }
            }
        }

        // Open with Education / Maths search by default
        loadSearch(lastSearch)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView, isPlayer: Boolean) {
        w.settings.apply {
            javaScriptEnabled                     = true
            domStorageEnabled                     = true
            mediaPlaybackRequiresUserGesture      = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        w.addJavascriptInterface(Bridge(isPlayer), "FLBridge")
        if (isPlayer) {
            // YouTube's fullscreen button: show the video full screen (landscape) and back
            w.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                    enterFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }
        }
        w.webViewClient = client(isPlayer)
    }

    private fun client(isPlayer: Boolean): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean = handleNav(request.url.toString(), isPlayer)

        @Suppress("DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleNav(url, isPlayer)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            if (isPlayer) enforcePlayerUrl(url) else enforceFeedUrl(url)
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
            if (isPlayer) injectRelated(url)
        }
    }

    /** Link taps / redirects. Returns true when we handled (or blocked) the URL ourselves. */
    private fun handleNav(raw: String, isPlayer: Boolean): Boolean {
        if (!isPlayer) {
            // a card was tapped: play it in the player instead of leaving the results
            val uri = Uri.parse(raw)
            if (uri.path == "/watch" && blockReason(raw) == null) {
                openVideo(raw)
                return true
            }
            val reason = blockReason(raw) ?: return false
            Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
            return true
        }
        val reason = playerBlockReason(raw) ?: return false
        Toast.makeText(this, reason, Toast.LENGTH_SHORT).show()
        return true
    }

    // ── Endless scrolling bridge (called from the pages) ─────────────────────
    inner class Bridge(private val forPlayer: Boolean) {
        @JavascriptInterface
        fun more() {
            runOnUiThread { loadMore(forPlayer) }
        }
    }

    private val moreExecutor = Executors.newSingleThreadExecutor()

    private fun loadMore(forPlayer: Boolean) {
        val pager = (if (forPlayer) relatedPager else feedPager) ?: return
        val view = if (forPlayer) playerView else webView
        if (pager.busy) return
        if (!pager.hasMore()) {
            view.evaluateJavascript("window.flDone&&window.flDone()", null)
            return
        }
        pager.busy = true
        moreExecutor.execute {
            val cards = try { pager.next() } catch (_: Exception) { emptyList() }
            runOnUiThread {
                pager.busy = false
                if (isFinishing) return@runOnUiThread
                if (pager !== (if (forPlayer) relatedPager else feedPager)) return@runOnUiThread
                if (cards.isEmpty()) {
                    view.evaluateJavascript("window.flDone&&window.flDone()", null)
                } else {
                    view.evaluateJavascript(
                        "window.flAppend&&window.flAppend(" +
                            StudyFeed.jsString(StudyFeed.cardsHtml(cards)) + ")", null
                    )
                }
            }
        }
    }

    // ── Study videos under the playing video ─────────────────────────────────
    private fun injectRelated(url: String) {
        val uri = Uri.parse(url)
        if (uri.path != "/watch" || mode == Mode.NONE) return
        if (playerBlockReason(url) != null) return
        val id = uri.getQueryParameter("v") ?: return
        val now = SystemClock.elapsedRealtime()
        if (id == relatedForId && now - relatedAt < 4000L) return
        relatedForId = id
        relatedAt = now
        val my = ++relatedToken
        val base = lastSearch
        feedExecutor.execute {
            val cards = try { StudyFeed.fetch(base) } catch (_: Exception) { emptyList() }
            val list = cards.filter { it.id != id }
            runOnUiThread {
                if (isFinishing || my != relatedToken || mode == Mode.NONE) return@runOnUiThread
                relatedPager = StudyFeed.Pager(base, list.map { it.id } + id, 1)
                playerView.evaluateJavascript(StudyFeed.injectScript(StudyFeed.cardsHtml(list)), null)
            }
        }
    }

    // ── Player: full screen <-> mini player ──────────────────────────────────
    private fun openVideo(raw: String) {
        val uri = Uri.parse(raw)
        val host = (uri.host ?: "").lowercase(Locale.ROOT)
        val id = uri.getQueryParameter("v").orEmpty()
        if (!host.endsWith("youtube.com") || !idRegex.matches(id)) return
        if (id == playerVideoId && mode != Mode.NONE) {
            expandPlayer()
            return
        }
        playerVideoId = id
        relatedForId = null
        relatedPager = null
        expandPlayer()
        playerView.loadUrl("https://m.youtube.com/watch?v=$id")
    }

    private fun expandPlayer() {
        mode = Mode.FULL
        playerFrame.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        playerView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        playerView.translationX = 0f
        playerView.translationY = 0f
        miniControls.visibility = View.GONE
        playerFrame.visibility = View.VISIBLE
    }

    /** Shrinks the playing video into a small box at the bottom-right; the results show behind it. */
    private fun minimizePlayer() {
        if (mode != Mode.FULL || customView != null) return
        val js = "(function(){window.scrollTo(0,0);var v=document.querySelector('video');" +
            "if(!v)return '';var r=v.getBoundingClientRect();" +
            "return JSON.stringify({l:r.left,t:r.top,w:r.width,h:r.height,vw:window.innerWidth});})()"
        playerView.evaluateJavascript(js) { raw ->
            var l = 0.0
            var t = 0.0
            var w = 0.0
            var h = 0.0
            var vw = 0.0
            try {
                val s = JSONTokener(raw ?: "").nextValue()
                if (s is String && s.isNotEmpty()) {
                    val o = JSONObject(s)
                    l = o.optDouble("l", 0.0)
                    t = o.optDouble("t", 0.0)
                    w = o.optDouble("w", 0.0)
                    h = o.optDouble("h", 0.0)
                    vw = o.optDouble("vw", 0.0)
                }
            } catch (_: Exception) {}
            if (mode != Mode.FULL) return@evaluateJavascript
            if (w <= 0.0 || h <= 0.0 || vw <= 0.0) {
                closePlayer()          // no video yet (still loading): just close it
                return@evaluateJavascript
            }
            applyMini(l, t, w, h, vw)
        }
    }

    private fun applyMini(l: Double, t: Double, w: Double, h: Double, vw: Double) {
        val d = resources.displayMetrics.density
        val fw = surface.width
        val fh = surface.height
        if (fw <= 0 || fh <= 0) {
            closePlayer()
            return
        }
        val pxPerCss = fw.toDouble() / vw
        val miniW = (190 * d).toInt()
        val miniH = (miniW * (h / w)).toInt().coerceIn((60 * d).toInt(), (160 * d).toInt())
        val s = miniW / (w * pxPerCss)

        // The page keeps its full size (no reflow, keeps playing); we only scale it down and
        // slide the <video> into the top-left corner of the small clipped frame.
        playerView.layoutParams = FrameLayout.LayoutParams(fw, fh)
        playerView.pivotX = 0f
        playerView.pivotY = 0f
        playerView.scaleX = s.toFloat()
        playerView.scaleY = s.toFloat()
        playerView.translationX = (-l * pxPerCss * s).toFloat()
        playerView.translationY = (-t * pxPerCss * s).toFloat()

        val lp = FrameLayout.LayoutParams(miniW, miniH, Gravity.BOTTOM or Gravity.END)
        val m = (12 * d).toInt()
        lp.setMargins(0, 0, m, m)
        playerFrame.layoutParams = lp
        miniControls.visibility = View.VISIBLE
        mode = Mode.MINI
    }

    private fun closePlayer() {
        mode = Mode.NONE
        playerVideoId = null
        relatedPager = null
        relatedForId = null
        miniControls.visibility = View.GONE
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        playerView.translationX = 0f
        playerView.translationY = 0f
        playerView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        playerFrame.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        playerView.loadUrl("about:blank")
        playerFrame.visibility = View.GONE
        videoGate.onUrl("about:blank")
    }

    private fun toggleMiniPlay() {
        // The tap was on a native button, so let the page start playback for this moment only
        playerView.settings.mediaPlaybackRequiresUserGesture = false
        playerView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v){if(v.paused){v.play();}else{v.pause();}}})()",
            null
        )
        handler.postDelayed({ playerView.settings.mediaPlaybackRequiresUserGesture = true }, 1500L)
    }

    private fun updateMiniPlayIcon() {
        playerView.evaluateJavascript(
            "(function(){var v=document.querySelector('video');return v?(v.paused?'1':'0'):'';})()"
        ) { r ->
            miniPlay.text = if (r != null && r.contains("1")) "▶" else "⏸"
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        handler.post(urlWatcher)
    }

    override fun onPause() {
        active = false
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

    private val feedExecutor = Executors.newSingleThreadExecutor()
    private var feedToken = 0

    /** Study-only results shown as YouTube-style cards; falls back to the normal results page. */
    private fun loadSearch(query: String) {
        lastSearch = query
        val url = "https://m.youtube.com/results?search_query=${Uri.encode(query)}" +
            "&sp=${Uri.encode(videoOnlyFilter)}"
        searchBar.setText(query)
        if (mode == Mode.FULL) minimizePlayer()      // keep the video playing in the mini player
        val my = ++feedToken
        feedPager = null
        feedExecutor.execute {
            val cards = try { StudyFeed.fetch(query) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isFinishing || my != feedToken) return@runOnUiThread
                if (cards.isEmpty()) {
                    webView.loadUrl(url)
                } else {
                    feedPager = StudyFeed.Pager(query, cards.map { it.id }, 1)
                    webView.loadDataWithBaseURL(
                        url, StudyFeed.renderHtml(query, cards),
                        "text/html", "UTF-8", url
                    )
                }
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchBar.windowToken, 0)
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

    // ── Fullscreen video ─────────────────────────────────────────────────────
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private fun setVideoBars(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView) ?: return
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun enterFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            cb.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = cb
        view.setBackgroundColor(0xFF000000.toInt())
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setVideoBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        setVideoBars(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /** Back: leave fullscreen -> minimize the video -> results history -> close the study screen. */
    private fun handleBack() {
        when {
            customView != null -> exitFullscreen()
            mode == Mode.FULL -> minimizePlayer()
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        handleBack()
    }

    override fun onDestroy() {
        exitFullscreen()
        feedToken++
        relatedToken++
        feedExecutor.shutdownNow()
        moreExecutor.shutdownNow()
        handler.removeCallbacks(urlWatcher)
        videoGate.destroy()
        playerView.stopLoading()
        playerView.destroy()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
