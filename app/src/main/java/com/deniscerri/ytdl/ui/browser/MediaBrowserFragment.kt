package com.deniscerri.ytdl.ui.browser

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.net.URI

class MediaBrowserFragment : Fragment() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var urlEditText: TextInputEditText
    private lateinit var fab: FloatingActionButton
    private lateinit var tabCountBtn: MaterialButton
    private lateinit var tabsRecycler: RecyclerView
    private lateinit var tabsTitle: TextView
    private lateinit var tabAdapter: TabAdapter

    // Home page views
    private lateinit var homePage: View
    private lateinit var favoritesRecycler: RecyclerView
    private lateinit var recentRecycler: RecyclerView
    private lateinit var favoritesEmpty: TextView
    private lateinit var recentEmpty: TextView

    // ── Tab state (backed by Activity-scoped ViewModel so WebViews survive navigation) ──
    private lateinit var browserViewModel: BrowserViewModel
    private val tabs get() = browserViewModel.tabs
    private var currentTab: BrowserTab?
        get() = browserViewModel.currentTab
        set(value) { browserViewModel.setCurrentTab(value) }
    private val nextTabId get() = browserViewModel.nextTabId

    // ── Site data ─────────────────────────────────────────────────────────────
    data class SiteItem(val url: String, val domain: String, val title: String = domain)

    private val recentSites = mutableListOf<SiteItem>()
    private val favoriteSites = mutableListOf<SiteItem>()
    private val PREFS_KEY_RECENT = "browser_recent_sites"
    private val PREFS_KEY_FAVORITES = "browser_favorite_sites"
    private val MAX_RECENT = 20

    // ── Site colors ───────────────────────────────────────────────────────────
    private val SITE_COLORS = intArrayOf(
        0xFF607D8B.toInt(), 0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(),
        0xFFEF5350.toInt(), 0xFFAB47BC.toInt(), 0xFF42A5F5.toInt(),
        0xFFFF7043.toInt(), 0xFF66BB6A.toInt(), 0xFFEC407A.toInt(),
        0xFF8D6E63.toInt()
    )

    // ── Media constants ───────────────────────────────────────────────────────
    private val MEDIA_EXTENSIONS = setOf(
        "m3u8", "mp4", "mp3", "m4a", "m4v", "webm", "mkv", "ts",
        "aac", "ogg", "flac", "wav", "opus", "mpd", "avi", "mov"
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")
    // Hard reject these — pages send "Accept: video/*,*/*" for non-media subresources
    // (e.g. preload-tagged JS), so we can't trust headers alone.
    private val NON_MEDIA_EXTENSIONS = setOf(
        "js", "mjs", "css", "html", "htm", "json", "xml", "svg",
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "avif",
        "woff", "woff2", "ttf", "otf", "eot", "txt", "pdf", "map",
        "wasm", "zip", "gz"
    )
    private val MEDIA_KEYWORDS = listOf("/manifest", "video/", "audio/")

    private val SCAN_JS = """
        (function() {
          var urls = [], seen = {};
          function add(u) {
            if (u && u.startsWith('http') && !seen[u]) { seen[u] = true; urls.push(u); }
          }
          document.querySelectorAll('video,audio').forEach(function(e) {
            add(e.src); add(e.currentSrc);
            e.querySelectorAll('source').forEach(function(s){ add(s.src); });
          });
          return JSON.stringify(urls);
        })()
    """.trimIndent()

    private val OBSERVE_JS = """
        (function() {
          if (window.__ytdlnisBrowserObserving) return;
          window.__ytdlnisBrowserObserving = true;
          function report(u) { if (u && u.startsWith('http')) Android.onMediaFound(u); }
          new MutationObserver(function(ms) {
            ms.forEach(function(m) {
              m.addedNodes.forEach(function(n) {
                if (!n.nodeName) return;
                var t = n.nodeName.toUpperCase();
                if (t === 'VIDEO' || t === 'AUDIO') { report(n.src); report(n.currentSrc); }
                if (n.querySelectorAll) n.querySelectorAll('video,audio,source').forEach(function(e){
                  report(e.src); report(e.currentSrc);
                });
              });
            });
          }).observe(document, {childList: true, subtree: true});
        })();
    """.trimIndent()

    // ── ViewModels ────────────────────────────────────────────────────────────
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_media_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        browserViewModel        = ViewModelProvider(requireActivity())[BrowserViewModel::class.java]
        downloadViewModel       = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        downloadCardViewModel   = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]

        drawerLayout     = view.findViewById(R.id.drawer_layout)
        webviewContainer = view.findViewById(R.id.webview_container)
        urlEditText      = view.findViewById(R.id.url_edittext)
        fab              = view.findViewById(R.id.fab_media)
        tabsRecycler     = view.findViewById(R.id.tabs_recycler)
        tabsTitle        = view.findViewById(R.id.tabs_title)
        tabCountBtn      = view.findViewById(R.id.tab_count_btn)

        // Home page
        homePage          = view.findViewById(R.id.home_page)
        favoritesRecycler = view.findViewById(R.id.favorites_recycler)
        recentRecycler    = view.findViewById(R.id.recent_recycler)
        favoritesEmpty    = view.findViewById(R.id.favorites_empty)
        recentEmpty       = view.findViewById(R.id.recent_empty)

        setupTabCountBtn()
        setupDrawer()
        setupUrlBar()
        setupBackHandler()
        setupDraggableFab()
        setFabState(hasMedia = false)
        setupHomePage()

        if (tabs.isNotEmpty()) {
            // Reattach existing WebViews from ViewModel (navigated back from another tab).
            // Only refresh callbacks that reference fragment views — do NOT call
            // addJavascriptInterface again, which would trigger a page reload.
            tabs.forEach { tab ->
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                refreshWebViewCallbacks(tab)
                webviewContainer.addView(tab.webView, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
                tab.webView.visibility = View.GONE
            }
            val cur = currentTab ?: tabs.first()
            switchToTab(cur)
        } else if (savedInstanceState != null) {
            val urls = savedInstanceState.getStringArrayList("tab_urls") ?: arrayListOf("about:blank")
            val cur  = savedInstanceState.getInt("current_tab_index", 0)
            urls.forEachIndexed { i, url -> createTab(url, switchTo = (i == cur)) }
        } else {
            createTab("about:blank")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("tab_urls", ArrayList(tabs.map { it.url }))
        outState.putInt("current_tab_index", tabs.indexOf(currentTab))
    }

    override fun onDestroyView() {
        // Detach WebViews from the container but keep them alive in the ViewModel
        // so they can be reattached when the user navigates back to this tab.
        webviewContainer.removeAllViews()
        super.onDestroyView()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupTabCountBtn() {
        tabCountBtn.setOnClickListener { openDrawer() }
    }

    private fun setupDrawer() {
        tabAdapter = TabAdapter(
            tabs       = tabs,
            getCurrent = { currentTab },
            onSwitch   = { tab ->
                switchToTab(tab)
                drawerLayout.closeDrawer(GravityCompat.START)
            },
            onClose    = { tab -> closeTab(tab) }
        )
        tabsRecycler.layoutManager = LinearLayoutManager(requireContext())
        tabsRecycler.adapter = tabAdapter

        requireView().findViewById<MaterialButton>(R.id.close_all_btn)
            .setOnClickListener { closeAllTabs() }
    }

    private fun setupUrlBar() {
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadInCurrentTab(urlEditText.text?.toString().orEmpty())
                true
            } else false
        }
        requireView().findViewById<ImageButton>(R.id.btn_reload)
            .setOnClickListener { currentTab?.webView?.reload() }
    }

    private fun loadInCurrentTab(input: String) {
        loadInTab(currentTab ?: return, normalizeUrl(input))
    }

    /** All user-initiated loads should go through here so the www-retry flag resets. */
    private fun loadInTab(tab: BrowserTab, url: String) {
        tab.wwwRetried = false
        tab.webView.loadUrl(url)
    }

    /**
     * Accepts what the user typed and returns a loadable URL.
     * - Already-schemed URLs pass through.
     * - Bare hosts (anything with a dot or `localhost`) get `https://` prepended.
     * - Anything else is treated as a search query.
     */
    private fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "about:blank"
        if (trimmed.matches(Regex("^[a-z][a-z0-9+\\-.]*://.*", RegexOption.IGNORE_CASE))) {
            return trimmed
        }
        val looksLikeHost = !trimmed.contains(' ') &&
            (trimmed.contains('.') || trimmed.equals("localhost", ignoreCase = true))
        if (looksLikeHost) return "https://$trimmed"
        val query = java.net.URLEncoder.encode(trimmed, "UTF-8")
        return "https://www.google.com/search?q=$query"
    }

    private fun setupBackHandler() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            when {
                drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    drawerLayout.closeDrawer(GravityCompat.START)
                currentTab?.webView?.canGoBack() == true ->
                    currentTab?.webView?.goBack()
                else -> findNavController().navigateUp()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        var dX = 0f
        var dY = 0f
        var lastAction = 0

        fab.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    lastAction = MotionEvent.ACTION_DOWN
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    lastAction = MotionEvent.ACTION_MOVE
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (lastAction == MotionEvent.ACTION_DOWN) {
                        collectAndShowMedia()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setFabState(hasMedia: Boolean) {
        if (hasMedia) {
            fab.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(fab, com.google.android.material.R.attr.colorPrimary)
            )
            fab.isEnabled = true
            fab.alpha = 1f
        } else {
            fab.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(fab, com.google.android.material.R.attr.colorSurfaceVariant)
            )
            fab.isEnabled = false
            fab.alpha = 0.6f
        }
    }

    // ── Home page (recent + favorites) ────────────────────────────────────────

    private fun setupHomePage() {
        loadSitesFromPrefs()

        favoritesRecycler.layoutManager = GridLayoutManager(requireContext(), 4)
        recentRecycler.layoutManager = GridLayoutManager(requireContext(), 4)

        favoritesRecycler.adapter = SiteAdapter(favoriteSites,
            onClick = { site -> currentTab?.let { loadInTab(it, site.url) } },
            onLongClick = { site ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(site.domain)
                    .setItems(arrayOf(getString(R.string.remove_from_favorites))) { _, _ ->
                        favoriteSites.remove(site)
                        saveFavorites()
                        refreshHomePage()
                    }
                    .show()
            }
        )

        recentRecycler.adapter = SiteAdapter(recentSites,
            onClick = { site -> currentTab?.let { loadInTab(it, site.url) } },
            onLongClick = { site ->
                val items = mutableListOf(getString(R.string.add_to_favorites), getString(R.string.remove_from_recent))
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(site.domain)
                    .setItems(items.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> {
                                if (favoriteSites.none { it.domain == site.domain }) {
                                    favoriteSites.add(site)
                                    saveFavorites()
                                    refreshHomePage()
                                }
                            }
                            1 -> {
                                recentSites.remove(site)
                                saveRecent()
                                refreshHomePage()
                            }
                        }
                    }
                    .show()
            }
        )

        refreshHomePage()
    }

    private fun refreshHomePage() {
        favoritesEmpty.isVisible = favoriteSites.isEmpty()
        favoritesRecycler.isVisible = favoriteSites.isNotEmpty()
        recentEmpty.isVisible = recentSites.isEmpty()
        recentRecycler.isVisible = recentSites.isNotEmpty()
        favoritesRecycler.adapter?.notifyDataSetChanged()
        recentRecycler.adapter?.notifyDataSetChanged()
    }

    private fun updateHomePageVisibility() {
        val isNewTab = currentTab?.url.isNullOrBlank() || currentTab?.url == "about:blank"
        homePage.isVisible = isNewTab
        webviewContainer.isVisible = !isNewTab
    }

    private fun addToRecent(url: String, title: String?) {
        val domain = extractDomain(url) ?: return
        if (domain == "about:blank" || domain.isBlank()) return
        val siteUrl = "https://$domain"
        recentSites.removeAll { it.domain == domain }
        recentSites.add(0, SiteItem(siteUrl, domain, title?.takeIf { it.isNotBlank() } ?: domain))
        if (recentSites.size > MAX_RECENT) recentSites.removeAt(recentSites.lastIndex)
        saveRecent()
        if (homePage.isVisible) refreshHomePage()
    }

    private fun extractDomain(url: String): String? {
        return try {
            URI(url).host?.removePrefix("www.")
        } catch (_: Exception) { null }
    }

    private fun loadSitesFromPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        recentSites.clear()
        favoriteSites.clear()
        runCatching {
            val recentJson = prefs.getString(PREFS_KEY_RECENT, "[]") ?: "[]"
            val arr = JSONArray(recentJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                recentSites.add(SiteItem(obj.getString("url"), obj.getString("domain"),
                    obj.optString("title", obj.getString("domain"))))
            }
        }
        runCatching {
            val favJson = prefs.getString(PREFS_KEY_FAVORITES, "[]") ?: "[]"
            val arr = JSONArray(favJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                favoriteSites.add(SiteItem(obj.getString("url"), obj.getString("domain"),
                    obj.optString("title", obj.getString("domain"))))
            }
        }
    }

    private fun saveRecent() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val arr = JSONArray()
        recentSites.forEach { s ->
            val obj = org.json.JSONObject()
            obj.put("url", s.url)
            obj.put("domain", s.domain)
            obj.put("title", s.title)
            arr.put(obj)
        }
        prefs.edit().putString(PREFS_KEY_RECENT, arr.toString()).apply()
    }

    private fun saveFavorites() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val arr = JSONArray()
        favoriteSites.forEach { s ->
            val obj = org.json.JSONObject()
            obj.put("url", s.url)
            obj.put("domain", s.domain)
            obj.put("title", s.title)
            arr.put(obj)
        }
        prefs.edit().putString(PREFS_KEY_FAVORITES, arr.toString()).apply()
    }

    // ── Tab management ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String, switchTo: Boolean = true): BrowserTab {
        val webView = WebView(requireContext())
        val tab = BrowserTab(id = nextTabId.getAndIncrement(), webView = webView, url = url)
        initWebView(tab)

        webviewContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        webView.visibility = View.GONE
        tabs.add(tab)

        if (url != "about:blank") loadInTab(tab, url) else tab.title = getString(R.string.new_tab)

        if (switchTo) switchToTab(tab) else updateTabUi()
        return tab
    }

    /** Full one-time setup: called only when creating a new tab. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(tab: BrowserTab) {
        val wv = tab.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }
        // JS interface is set once — re-adding it causes a page reload on many WebView versions.
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaFound(url: String) {
                if (url.startsWith("http") && isLikelyMedia(url)) {
                    val wasEmpty = tab.interceptedUrls.isEmpty()
                    tab.interceptedUrls.add(url)
                    if (wasEmpty && tab == currentTab) {
                        activity?.runOnUiThread { if (isAdded) setFabState(hasMedia = true) }
                    }
                }
            }
        }, "Android")
        refreshWebViewCallbacks(tab)
    }

    /**
     * Refresh only the callbacks that close over fragment-view references
     * (WebViewClient, WebChromeClient, longClickListener). Safe to call every time
     * the fragment view is recreated without causing a WebView reload.
     */
    private fun refreshWebViewCallbacks(tab: BrowserTab) {
        val wv = tab.webView

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isMediaUrl(url, request)) {
                    val wasEmpty = tab.interceptedUrls.isEmpty()
                    tab.interceptedUrls.add(url)
                    if (wasEmpty && tab == currentTab) {
                        activity?.runOnUiThread { if (isAdded) setFabState(hasMedia = true) }
                    }
                }
                return null
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Fires on both full page loads and SPA pushState/replaceState navigations.
                // Clear intercepted URLs whenever the page URL changes so stale media
                // from previous pages doesn't appear in the download sheet.
                if (!isReload && url != null && url != tab.url) {
                    tab.interceptedUrls.clear()
                    tab.url = url
                    if (tab == currentTab) {
                        activity?.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            urlEditText.setText(if (url == "about:blank") "" else url)
                            setFabState(hasMedia = false)
                        }
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                val failed = request.url?.toString() ?: return
                // Apex-domain DNS failure (e.g. "https://baidu.com" can't resolve in
                // some networks) — retry once with "www." prepended. Stop after one
                // retry so genuinely-broken hosts don't loop.
                if (!tab.wwwRetried && shouldRetryWithWww(failed, error?.errorCode)) {
                    tab.wwwRetried = true
                    val retry = withWwwHost(failed) ?: return
                    activity?.runOnUiThread { view?.loadUrl(retry) }
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url ?: return
                tab.url = url
                tab.interceptedUrls.clear()
                if (tab == currentTab) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        urlEditText.setText(if (url == "about:blank") "" else url)
                        urlEditText.clearFocus()
                        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(urlEditText.windowToken, 0)
                        setFabState(hasMedia = false)
                        updateHomePageVisibility()
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { tab.url = it }
                view?.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
                if (tab == currentTab) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        urlEditText.setText(if (tab.url == "about:blank") "" else tab.url)
                        setFabState(hasMedia = tab.interceptedUrls.isNotEmpty())
                        updateHomePageVisibility()
                    }
                }
                tabAdapter.notifyDataSetChanged()
                wv.evaluateJavascript(OBSERVE_JS, null)

                if (url != null && url != "about:blank") {
                    addToRecent(url, tab.title)
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    activity?.runOnUiThread { if (isAdded) tabAdapter.notifyDataSetChanged() }
                }
            }
        }

        wv.setOnLongClickListener {
            val hr  = wv.hitTestResult
            val url = hr.extra
            if (!url.isNullOrBlank() && url.startsWith("http") &&
                hr.type != WebView.HitTestResult.UNKNOWN_TYPE &&
                hr.type != WebView.HitTestResult.EDIT_TEXT_TYPE
            ) {
                showLinkMenu(url)
                true
            } else false
        }
    }

    private fun switchToTab(tab: BrowserTab) {
        currentTab?.webView?.visibility = View.GONE
        currentTab = tab
        tab.webView.visibility = View.VISIBLE
        urlEditText.setText(if (tab.url == "about:blank") "" else tab.url)
        setFabState(hasMedia = tab.interceptedUrls.isNotEmpty())
        updateTabUi()
        updateHomePageVisibility()
    }

    private fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        webviewContainer.removeView(tab.webView)
        tab.webView.destroy()

        if (tab == currentTab) {
            currentTab = null
            val next = tabs.getOrNull(minOf(index, tabs.size - 1))
            if (next != null) switchToTab(next) else {
                createTab("about:blank")
            }
        } else {
            updateTabUi()
        }
    }

    private fun closeAllTabs() {
        currentTab = null
        webviewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        drawerLayout.closeDrawer(GravityCompat.START)
        createTab("about:blank")
    }

    private fun updateTabUi() {
        val count = tabs.size
        tabCountBtn.text = count.toString()
        tabsTitle.text = "${getString(R.string.pages)} ($count)"
        tabAdapter.notifyDataSetChanged()
    }

    private fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)

    // ── Link long-press menu ──────────────────────────────────────────────────

    private fun showLinkMenu(url: String) {
        val truncated = if (url.length > 80) url.take(80) + "…" else url
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(truncated)
            .setItems(arrayOf(
                getString(R.string.open_current_tab),
                getString(R.string.open_new_tab),
                getString(R.string.open_background_tab)
            )) { _, which ->
                when (which) {
                    0 -> { currentTab?.let { loadInTab(it, url); it.url = url } }
                    1 -> createTab(url, switchTo = true)
                    2 -> createTab(url, switchTo = false)
                }
            }
            .show()
    }

    // ── Media detection ───────────────────────────────────────────────────────

    private fun shouldRetryWithWww(failedUrl: String, errorCode: Int?): Boolean {
        val host = runCatching { URI(failedUrl).host }.getOrNull() ?: return false
        if (host.startsWith("www.", ignoreCase = true)) return false
        // Subdomained hosts (e.g. m.example.com, api.example.com) shouldn't get
        // "www." prepended — only single-level apex domains like "example.com".
        if (host.count { it == '.' } != 1) return false
        // Limit retry to network-resolution failures; HTTP 4xx/5xx are app errors.
        return errorCode == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
               errorCode == android.webkit.WebViewClient.ERROR_CONNECT ||
               errorCode == android.webkit.WebViewClient.ERROR_TIMEOUT ||
               errorCode == android.webkit.WebViewClient.ERROR_UNKNOWN
    }

    private fun withWwwHost(url: String): String? {
        return runCatching {
            val u = URI(url)
            val newHost = "www.${u.host}"
            URI(u.scheme, u.userInfo, newHost, u.port, u.path, u.query, u.fragment).toString()
        }.getOrNull()
    }

    private fun urlExtension(url: String): String {
        val path = url.lowercase().substringBefore('?').substringBefore('#')
        // Take the last path segment first, then the extension within that segment.
        // Doing it the other way around picks up dots in directory names like /api.v2/data.
        return path.substringAfterLast('/').substringAfterLast('.', "")
    }

    private fun isMediaUrl(url: String, request: WebResourceRequest?): Boolean {
        val ext = urlExtension(url)
        if (ext in NON_MEDIA_EXTENSIONS) return false
        if (ext in MEDIA_EXTENSIONS) return true
        if (MEDIA_KEYWORDS.any { url.lowercase().contains(it) }) return true
        // Accept-header fallback: only when there is no extension at all
        // (extensionless streaming endpoints). Otherwise too many JS/CSS
        // requests carry "Accept: */*" or wildcard headers and slip through.
        if (ext.isNotEmpty()) return false
        val accept = request?.requestHeaders?.get("Accept") ?: ""
        return accept.startsWith("video/", ignoreCase = true) ||
               accept.startsWith("audio/", ignoreCase = true) ||
               accept.contains("application/vnd.apple.mpegurl", ignoreCase = true) ||
               accept.contains("application/dash+xml", ignoreCase = true)
    }

    private fun isLikelyMedia(url: String): Boolean {
        val ext = urlExtension(url)
        if (ext in NON_MEDIA_EXTENSIONS) return false
        if (ext in MEDIA_EXTENSIONS) return true
        return MEDIA_KEYWORDS.any { url.lowercase().contains(it) }
    }

    private fun collectAndShowMedia() {
        val tab = currentTab ?: return
        tab.webView.evaluateJavascript(SCAN_JS) { result ->
            val merged = LinkedHashSet<String>()
            tab.interceptedUrls.forEach { if (isLikelyMedia(it)) merged.add(it) }
            runCatching {
                val jsonString = JSONTokener(result?.trim() ?: "\"[]\"").nextValue() as? String ?: "[]"
                val json = JSONArray(jsonString)
                for (i in 0 until json.length()) {
                    val u = json.optString(i)
                    if (u.startsWith("http") && isLikelyMedia(u)) merged.add(u)
                }
            }
            val items = merged.map { toMediaItem(it) }
            val pageTitle = tab.title.takeIf { it.isNotBlank() && it != "about:blank" }
            activity?.runOnUiThread { if (isAdded) showMediaSheet(items, pageTitle) }
        }
    }

    private fun toMediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val ext = lastSegment.substringAfterLast('.', "").lowercase().take(6)
        val decoded = runCatching { java.net.URLDecoder.decode(lastSegment, "UTF-8") }
            .getOrDefault(lastSegment)
        val filename = decoded.takeIf { it.isNotBlank() } ?: extractDomain(url) ?: url
        return MediaItem(
            url       = url,
            filename  = filename,
            extension = ext.ifBlank { "?" },
            isAudio   = ext in AUDIO_EXTENSIONS
        )
    }

    private fun showMediaSheet(items: List<MediaItem>, pageTitle: String? = null) {
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_media_found, Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.bottomsheet_media_urls, null)

        view.findViewById<TextView>(R.id.sheet_title)?.text =
            pageTitle ?: getString(R.string.detected_media_urls)
        view.findViewById<TextView>(R.id.sheet_subtitle)?.text =
            "${items.size} · ${getString(R.string.detected_media_urls)}"

        // Batch actions (visible when > 1 item)
        val batchActions = view.findViewById<LinearLayout>(R.id.batch_actions)
        batchActions.isVisible = items.size > 1

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()

        view.findViewById<MaterialButton>(R.id.download_all_btn)?.setOnClickListener {
            dialog.dismiss()
            downloadAllUrls(items.map { it.url })
        }
        view.findViewById<MaterialButton>(R.id.download_one_by_one_btn)?.setOnClickListener {
            dialog.dismiss()
            showOneByOneSheet(items)
        }
        view.findViewById<MaterialButton>(R.id.download_rules_btn)?.setOnClickListener {
            dialog.dismiss()
            showRulesDialog(items)
        }

        val recycler = view.findViewById<RecyclerView>(R.id.media_url_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            downloadSingleUrl(item.url)
        }
        dialog.show()
    }

    private fun showOneByOneSheet(items: List<MediaItem>) {
        val view = layoutInflater.inflate(R.layout.bottomsheet_media_urls, null)

        view.findViewById<LinearLayout>(R.id.batch_actions)?.isVisible = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()

        val recycler = view.findViewById<RecyclerView>(R.id.media_url_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            downloadSingleUrl(item.url)
        }
        dialog.show()
    }

    private fun showRulesDialog(items: List<MediaItem>) {
        val types = arrayOf(getString(R.string.video), getString(R.string.audio))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.download_with_rules))
            .setMessage(getString(R.string.download_type_label))
            .setItems(types) { _, typeIdx ->
                val quality = arrayOf(getString(R.string.best_quality), getString(R.string.worst_quality))
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.quality_label))
                    .setItems(quality) { _, qualIdx ->
                        val dlType = if (typeIdx == 0) DownloadType.video else DownloadType.audio
                        downloadAllUrlsWithType(items.map { it.url }, dlType, qualIdx == 1)
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Download logic ────────────────────────────────────────────────────────

    private fun downloadSingleUrl(url: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val type  = downloadViewModel.getDownloadType(
            DownloadType.valueOf(prefs.getString("preferred_download_type", "video")!!),
            url
        )
        downloadAllUrlsWithType(listOf(url), type, false)
    }

    private fun downloadAllUrls(urls: List<String>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val type  = DownloadType.valueOf(prefs.getString("preferred_download_type", "video")!!)
        downloadAllUrlsWithType(urls, type, false)
    }

    private fun downloadAllUrlsWithType(urls: List<String>, type: DownloadType, worstQuality: Boolean) {
        val items = urls.map { url ->
            val item = downloadViewModel.createDownloadItemFromResult(
                result    = downloadViewModel.createEmptyResultItem(url),
                givenType = type
            )
            if (worstQuality) item.format.format_id = "worst"
            item
        }
        lifecycleScope.launch(Dispatchers.IO) {
            downloadViewModel.queueDownloads(items)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    requireContext(),
                    "${items.size} ${getString(R.string.downloading)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ── Inner adapters ────────────────────────────────────────────────────────

    private inner class SiteAdapter(
        private val sites: List<SiteItem>,
        private val onClick: (SiteItem) -> Unit,
        private val onLongClick: (SiteItem) -> Unit
    ) : RecyclerView.Adapter<SiteAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val letter: TextView = view.findViewById(R.id.site_letter)
            val circle: View     = view.findViewById(R.id.site_circle)
            val name: TextView   = view.findViewById(R.id.site_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_site, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val site = sites[position]
            val firstChar = site.domain.firstOrNull()?.uppercaseChar() ?: '?'
            holder.letter.text = firstChar.toString()
            holder.name.text = site.domain

            val colorIndex = (site.domain.hashCode() and 0x7FFFFFFF) % SITE_COLORS.size
            val bg = holder.circle.background
            if (bg is GradientDrawable) {
                bg.setColor(SITE_COLORS[colorIndex])
            } else {
                val drawable = GradientDrawable()
                drawable.shape = GradientDrawable.OVAL
                drawable.setColor(SITE_COLORS[colorIndex])
                holder.circle.background = drawable
            }

            holder.itemView.setOnClickListener { onClick(site) }
            holder.itemView.setOnLongClickListener { onLongClick(site); true }
        }

        override fun getItemCount() = sites.size
    }

    private inner class TabAdapter(
        private val tabs: List<BrowserTab>,
        private val getCurrent: () -> BrowserTab?,
        private val onSwitch: (BrowserTab) -> Unit,
        private val onClose: (BrowserTab) -> Unit
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val content: View      = view.findViewById(R.id.tab_content)
            val title: TextView    = view.findViewById(R.id.tab_title)
            val url: TextView      = view.findViewById(R.id.tab_url)
            val close: android.widget.ImageButton = view.findViewById(R.id.tab_close)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.tab_browser_item, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tab       = tabs[position]
            val isCurrent = (tab == getCurrent())
            holder.title.text = tab.title.ifBlank { tab.url }
            holder.url.text   = tab.url
            holder.title.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            holder.itemView.alpha = if (isCurrent) 1f else 0.8f
            holder.content.setOnClickListener { onSwitch(tab) }
            holder.close.setOnClickListener { onClose(tab) }
        }

        override fun getItemCount() = tabs.size
    }

    data class MediaItem(
        val url: String,
        val filename: String,
        val extension: String,
        val isAudio: Boolean
    )

    private inner class MediaUrlAdapter(
        private val items: List<MediaItem>,
        private val onClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<MediaUrlAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView     = view.findViewById(R.id.media_type_icon)
            val filename: TextView  = view.findViewById(R.id.media_filename)
            val ext: TextView       = view.findViewById(R.id.media_extension)
            val url: TextView       = view.findViewById(R.id.media_url)
            val btn: View           = view.findViewById(R.id.download_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.media_url_item, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.filename.text = item.filename
            holder.ext.text = item.extension
            holder.url.text = extractDomain(item.url) ?: item.url
            holder.icon.setImageResource(
                if (item.isAudio) R.drawable.baseline_audio_file_24
                else R.drawable.baseline_video_file_24
            )
            holder.itemView.setOnClickListener { onClick(item) }
            holder.btn.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
