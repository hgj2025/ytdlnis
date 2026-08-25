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
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.work.DirectDownloadWorker
import com.deniscerri.ytdl.work.ImageDownloadWorker
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
import org.json.JSONObject
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

    // Ad/tracker networks that serve decoy or preview clips (e.g. tsyndicate's
    // 30s teaser on rou.video). Never treat their media as the page's content.
    private val AD_MEDIA_HOSTS = listOf(
        "tsyndicate.com", "doubleclick.net", "googlesyndication.com",
        "adnxs.com", "juicyads.com", "exoclick.com", "exosrv.com",
        "trafficjunky.com", "trafficjunky.net", "popads.net", "popcash.net",
        "adsterra.com", "hilltopads.net", "ad-maven.com", "mgid.com"
    )

    private fun isAdHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return AD_MEDIA_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    // Progressive (single-file) media that can be fetched with one HTTP GET, so we
    // download it immediately in-process (DirectDownloadWorker) instead of handing
    // it to the yt-dlp queue. Streaming manifests (m3u8/mpd) and lone segments (ts)
    // are excluded — those need yt-dlp to assemble the segments.
    private val DIRECT_DOWNLOAD_EXTENSIONS = setOf(
        "mp4", "webm", "mkv", "mov", "m4v", "avi",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus"
    )

    // Request headers we never replay to yt-dlp: either set explicitly elsewhere
    // (referer/cookie), managed by yt-dlp/the HTTP stack, or connection-specific.
    private val SKIP_REPLAY_HEADERS = setOf(
        "referer", "cookie", "range", "host", "connection", "content-length",
        "accept-encoding", "accept-ranges", "if-modified-since", "if-none-match",
        "upgrade-insecure-requests", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest"
    )

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
          document.querySelectorAll('source[src]').forEach(function(s){ add(s.src); });
          // Custom JS players (e.g. hls.js) keep the real stream URL in a data-*
          // attribute and only build a <video> with a blob: src on play, so the
          // element scan above misses them. Pull those out directly.
          ['data-source','data-src','data-video','data-hls','data-mp4','data-url'].forEach(function(attr){
            document.querySelectorAll('['+attr+']').forEach(function(e){ add(e.getAttribute(attr)); });
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

          // Douyin (and similar) image-gallery posts have no <video>; the images
          // come back in an aweme JSON API response. Parse those responses and,
          // scoped to the opened post (modal_id), report the image URLs as a group.
          function scanImages(text) {
            try {
              if (!text || text.indexOf('"images"') < 0 || text.indexOf('aweme_id') < 0) return;
              var data = JSON.parse(text);
              var modal = null;
              try { modal = new URLSearchParams(location.search).get('modal_id'); } catch (e) {}
              (function walk(o, d) {
                if (!o || d > 6 || typeof o !== 'object') return;
                if (o.aweme_id && o.images && o.images.length &&
                    (!modal || String(o.aweme_id) === String(modal))) {
                  var urls = [];
                  o.images.forEach(function(im) {
                    var l = im && im.url_list;
                    if (l && l.length) urls.push(l[l.length - 1]);
                  });
                  if (urls.length) {
                    try { Android.onImagesFound(JSON.stringify(
                      { postId: String(o.aweme_id), title: (o.desc || ''), images: urls })); } catch (e) {}
                  }
                }
                if (Array.isArray(o)) { for (var i = 0; i < o.length; i++) walk(o[i], d + 1); }
                else { for (var k in o) { if (o[k] && typeof o[k] === 'object') walk(o[k], d + 1); } }
              })(data, 0);
            } catch (e) {}
          }

          // Many players (hls.js/dash.js) fetch the manifest via fetch()/XHR at
          // play time and never put the stream URL in the DOM, so element scanning
          // alone can't see it. Hook the network APIs: report requested URLs (media)
          // and inspect aweme responses (images). Native side filters/handles both.
          try {
            var of = window.fetch;
            if (of && !of.__ytdlnisHooked) {
              window.fetch = function(input) {
                var u = ''; try { u = typeof input === 'string' ? input : (input && input.url) || ''; } catch (e) {}
                try { report(u); } catch (e) {}
                var p = of.apply(this, arguments);
                try {
                  p.then(function(r) {
                    try {
                      if (u.indexOf('aweme') >= 0) {
                        r.clone().text().then(scanImages).catch(function(){});
                      } else {
                        var cl = parseInt(r.headers.get('content-length') || '0', 10);
                        if (cl > 0 && cl < 200000) {
                          r.clone().text().then(function(tx) {
                            if (tx.lastIndexOf('#EXTM3U', 0) === 0) { try { Android.onManifestFound(u); } catch (e) {} }
                          }).catch(function(){});
                        }
                      }
                    } catch (e) {}
                  });
                } catch (e) {}
                return p;
              };
              window.fetch.__ytdlnisHooked = true;
            }
            var oo = XMLHttpRequest.prototype.open;
            if (oo && !oo.__ytdlnisHooked) {
              XMLHttpRequest.prototype.open = function(method, url) {
                try { report(url); this.__ytU = url; } catch (e) {}
                try {
                  this.addEventListener('load', function() {
                    try {
                      var u = this.__ytU || this.responseURL || '';
                      var t = null;
                      if (this.responseType === '' || this.responseType === 'text') t = this.responseText;
                      else if (this.responseType === 'json' && this.response) t = JSON.stringify(this.response);
                      if (!t) return;
                      // HLS playlist detected by content — catches m3u8 disguised as .jpg.
                      if (t.lastIndexOf('#EXTM3U', 0) === 0) { try { Android.onManifestFound(u); } catch (e) {} }
                      else if (u.indexOf('aweme') >= 0) scanImages(t);
                    } catch (e) {}
                  });
                } catch (e) {}
                return oo.apply(this, arguments);
              };
              XMLHttpRequest.prototype.open.__ytdlnisHooked = true;
            }
          } catch (e) {}

          // Scroll-position memory. Native WebView back-scroll restoration only
          // covers the document on a full reload; SPA sites (rou.video/Douyin) do
          // client-side back (popstate, no reload) and scroll an inner container,
          // so we remember every scroller's position per URL and restore on back.
          try {
            var scrollStore = {};
            function scKey() { return location.pathname + location.search; }
            function scSelector(el) {
              if (!el || el === document || el === window ||
                  el === document.documentElement || el === document.body) return '__win';
              var path = [], depth = 0;
              while (el && el.nodeType === 1 && depth < 6) {
                var seg = el.tagName.toLowerCase();
                if (el.id) { path.unshift(seg + '#' + el.id); return path.join('>'); }
                var parent = el.parentNode;
                if (parent && parent.children) {
                  var idx = Array.prototype.indexOf.call(parent.children, el);
                  seg += ':nth-child(' + (idx + 1) + ')';
                }
                path.unshift(seg); el = parent; depth++;
              }
              return path.join('>');
            }
            function scRecord(target) {
              var k = scKey();
              var bucket = scrollStore[k] || (scrollStore[k] = {});
              if (!target || target === document || target === window ||
                  target === document.documentElement) {
                bucket.__win = window.scrollY || document.documentElement.scrollTop || 0;
              } else if (target.scrollTop !== undefined &&
                         target.scrollHeight > target.clientHeight + 4) {
                bucket[scSelector(target)] = target.scrollTop;
              }
            }
            var scThrottle = false;
            window.addEventListener('scroll', function(e) {
              if (scThrottle) return;
              scThrottle = true; setTimeout(function(){ scThrottle = false; }, 120);
              scRecord(e.target);
            }, true);
            function scRestore() {
              var m = scrollStore[scKey()]; if (!m) return;
              var tries = 0;
              (function attempt() {
                tries++;
                if (m.__win != null) window.scrollTo(0, m.__win);
                for (var sel in m) {
                  if (sel === '__win') continue;
                  try { var el = document.querySelector(sel); if (el) el.scrollTop = m[sel]; } catch (e) {}
                }
                if (tries < 16) setTimeout(attempt, 60);
              })();
            }
            window.addEventListener('popstate', function() { setTimeout(scRestore, 40); });
            window.addEventListener('pageshow', function() { setTimeout(scRestore, 40); });
          } catch (e) {}
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
                    currentTab?.let { goBackInTab(it) }
                else -> findNavController().navigateUp()
            }
        }
    }

    /**
     * Go back one page, capturing the scroll offset we saved for the destination
     * page so it can be restored once it finishes (re)loading. WebView restores
     * scroll on its own only when the page comes from the back-forward cache;
     * pages that reload from the network land at the top otherwise.
     */
    private fun goBackInTab(tab: BrowserTab) {
        val wv = tab.webView
        if (!wv.canGoBack()) return
        val history = wv.copyBackForwardList()
        val targetIndex = history.currentIndex - 1
        tab.pendingRestoreY = if (targetIndex >= 0) {
            tab.scrollPositions[history.getItemAtIndex(targetIndex).url]
        } else null
        wv.goBack()
    }

    /**
     * Re-apply a saved scroll offset, retrying while the page content grows so we
     * don't clamp to a not-yet-tall-enough document during progressive loading.
     */
    private fun restoreScroll(wv: WebView, targetY: Int, attempt: Int) {
        wv.scrollTo(0, targetY)
        if (wv.scrollY < targetY && attempt < 12) {
            wv.postDelayed({ if (isAdded) restoreScroll(wv, targetY, attempt + 1) }, 60)
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
        // WebViews are kept alive in the ViewModel, so their onPageFinished can fire
        // after this fragment has detached — touching prefs/views then would crash.
        if (!isAdded) return
        val domain = extractDomain(url) ?: return
        if (domain == "about:blank" || domain.isBlank()) return
        // Keep the real host (with "www." if the page had it) in the clickable URL,
        // otherwise "https://example.com" is saved for a site that only serves
        // "https://www.example.com" and the favorite won't open. Display still uses
        // the www-stripped domain.
        val realHost = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: domain
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.takeIf { it.isNotBlank() } ?: "https"
        val siteUrl = "$scheme://$realHost"
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(context ?: return)
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(context ?: return)
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(context ?: return)
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
                    tab.interceptedUrls[url] = tab.url
                    if (wasEmpty && tab == currentTab) {
                        activity?.runOnUiThread { if (isAdded) setFabState(hasMedia = true) }
                    }
                }
            }

            @JavascriptInterface
            fun onManifestFound(url: String) {
                // Verified by content (#EXTM3U) in JS, so bypass the extension check —
                // sites disguise HLS playlists as .jpg to dodge downloaders.
                if (!url.startsWith("http") || isAdHost(url)) return
                val wasEmpty = tab.interceptedUrls.isEmpty()
                tab.interceptedUrls[url] = tab.url
                if (wasEmpty && tab == currentTab) {
                    activity?.runOnUiThread { if (isAdded) setFabState(hasMedia = true) }
                }
            }

            @JavascriptInterface
            fun onImagesFound(json: String) {
                try {
                    val obj = JSONObject(json)
                    val arr = obj.optJSONArray("images") ?: return
                    if (arr.length() == 0) return
                    val page = tab.url
                    val set = tab.imageGroups.computeIfAbsent(page) { LinkedHashSet() }
                    synchronized(set) {
                        for (i in 0 until arr.length()) {
                            arr.optString(i).takeIf { it.startsWith("http") }?.let { set.add(it) }
                        }
                    }
                    obj.optString("title").takeIf { it.isNotBlank() }?.let { tab.imageTitles[page] = it }
                    if (tab == currentTab) {
                        activity?.runOnUiThread { if (isAdded) setFabState(hasMedia = true) }
                    }
                } catch (_: Exception) {}
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
                    tab.interceptedUrls[url] = tab.url
                    request.requestHeaders?.takeIf { it.isNotEmpty() }?.let {
                        tab.mediaHeaders[url] = HashMap(it)
                    }
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
                    tab.mediaHeaders.clear()
                    tab.imageGroups.clear()
                    tab.imageTitles.clear()
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
                tab.mediaHeaders.clear()
                tab.imageGroups.clear()
                tab.imageTitles.clear()
                // Install the fetch/XHR hooks as early as possible so the very first
                // API response (e.g. Douyin's aweme detail with the images) is caught.
                view?.evaluateJavascript(OBSERVE_JS, null)
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

                // If this load was a back navigation to a page that reloaded from
                // scratch, WebView dropped us at the top — re-apply the saved offset.
                tab.pendingRestoreY?.let { y ->
                    tab.pendingRestoreY = null
                    if (y > 0) restoreScroll(wv, y, 0)
                }

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

        // Remember where the user was on each page so we can restore the browsing
        // position after a back navigation (see goBackInTab / restoreScroll).
        wv.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            (wv.url ?: tab.url).let { u -> if (u != "about:blank") tab.scrollPositions[u] = scrollY }
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

    /**
     * Path + query of a URL with scheme and host stripped, so a domain like
     * "rou.video" doesn't false-match a path keyword like "video/" and flood the
     * download list with every request the site makes (session, watching, …).
     */
    private fun urlPathPart(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOf('/')
        return if (slash >= 0) afterScheme.substring(slash).lowercase() else ""
    }

    private fun isMediaUrl(url: String, request: WebResourceRequest?): Boolean {
        if (isAdHost(url)) return false
        val ext = urlExtension(url)
        if (ext in NON_MEDIA_EXTENSIONS) return false
        if (ext in MEDIA_EXTENSIONS) return true
        if (MEDIA_KEYWORDS.any { urlPathPart(url).contains(it) }) return true
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
        if (isAdHost(url)) return false
        val ext = urlExtension(url)
        if (ext in NON_MEDIA_EXTENSIONS) return false
        if (ext in MEDIA_EXTENSIONS) return true
        return MEDIA_KEYWORDS.any { urlPathPart(url).contains(it) }
    }

    /**
     * A short token that uniquely distinguishes this media URL from others on the
     * same page, so different videos never collapse to one shared filename.
     * Tries, in order: an id-like path segment (e.g. a video id), the filename stem,
     * then a stable short hash of the path — guaranteeing distinct URLs get distinct
     * tokens even when the page title is identical for all of them.
     */
    private fun videoIdFromUrl(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        // 1) longest id-like segment (has a digit, no dot, reasonable length)
        segments.filter { it.length in 6..60 && !it.contains('.') && it.any { c -> c.isDigit() } }
            .maxByOrNull { it.length }?.let { return it }
        // 2) filename stem (URL-decoded), if it isn't a generic name
        val stem = segments.lastOrNull()
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.substringBeforeLast('.')?.takeIf {
                it.isNotBlank() && it.lowercase() !in setOf("index", "video", "master", "playlist", "media")
            }
        if (stem != null) return stem.take(60)
        // 3) stable short hash of the whole path
        return Integer.toHexString(BrowserDownloadHistory.key(url).hashCode()).takeLast(6)
    }

    /**
     * A human-readable filename embedded in the URL path, if any. For many sites the
     * last path segment IS the real, unique title (e.g. ".../女儿的奶水 第05集【小苮儿】.mp3"),
     * which beats the shared page <title>. Returns null for opaque/generic names
     * (index/master, or hash-like ids) so callers fall back to the page title.
     */
    private fun urlFileNameTitle(url: String): String? {
        val lastSeg = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (lastSeg.isBlank()) return null
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val stem = decoded.substringBeforeLast('.').trim()
        if (stem.isBlank()) return null
        if (stem.lowercase() in setOf("index", "master", "playlist", "media", "video", "audio", "stream")) return null
        // Reject opaque ids: long, all ASCII alphanumeric/-/_ with no spaces — not a real title.
        val looksOpaque = stem.length >= 12 &&
            stem.none { it.code > 127 } && stem.none { it == ' ' } &&
            stem.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (looksOpaque) return null
        return stem.take(120)
    }

    /** Build a per-item download title. Prefers a real filename in the URL (unique &
     *  correct per item); otherwise page title plus a URL-derived token so multiple
     *  items on one page still get distinct filenames instead of colliding. */
    private fun mediaTitle(url: String, pageTitle: String?): String {
        urlFileNameTitle(url)?.let { return it }
        val base = pageTitle?.takeIf { it.isNotBlank() && it != "about:blank" && it != getString(R.string.new_tab) }
        val id = videoIdFromUrl(url)
        return if (base != null) "$base - $id" else id
    }

    private fun collectAndShowMedia() {
        val tab = currentTab ?: return
        tab.webView.evaluateJavascript(SCAN_JS) { result ->
            val merged = LinkedHashSet<String>()
            // DOM-scanned media first, so the current page's own <video>/player
            // source (usually first in document order) leads the list.
            runCatching {
                val jsonString = JSONTokener(result?.trim() ?: "\"[]\"").nextValue() as? String ?: "[]"
                val json = JSONArray(jsonString)
                for (i in 0 until json.length()) {
                    val u = json.optString(i)
                    if (u.startsWith("http") && isLikelyMedia(u)) merged.add(u)
                }
            }
            // Network-intercepted media, scoped to the current page. Entries are
            // already vetted when added (isMediaUrl / isLikelyMedia / verified HLS),
            // so don't re-filter by extension — that would drop .jpg-disguised m3u8.
            tab.interceptedUrls.forEach { (mediaUrl, pageUrl) ->
                if (pageUrl == tab.url) merged.add(mediaUrl)
            }
            // Dedupe by host/query-independent path, so the same video captured from
            // a rotating CDN host with refreshed ?exp&auth tokens collapses to one
            // entry (otherwise it looks like several videos that all download the
            // same first one).
            val seenKeys = HashSet<String>()
            val dedupedMedia = merged.filter { seenKeys.add(BrowserDownloadHistory.key(it)) }
            // Image-gallery posts (e.g. Douyin 图文) captured from aweme API responses.
            val imageUrls = tab.imageGroups[tab.url]?.let { synchronized(it) { it.toList() } }.orEmpty()
            val imageTitle = tab.imageTitles[tab.url]
            val allItems = dedupedMedia.map { toMediaItem(it) } +
                imageUrls.mapIndexed { idx, u -> toImageItem(u, idx) }
            val pageTitle = tab.title.takeIf { it.isNotBlank() && it != "about:blank" }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                // Mark (don't hide) already-downloaded items: they stay visible and
                // re-downloadable on tap, but are flagged so "download all" can skip
                // them by default. Hiding entirely made the sheet look empty/broken.
                val items = allItems.map {
                    if (BrowserDownloadHistory.isDownloaded(requireContext(), it.url))
                        it.copy(alreadyDownloaded = true) else it
                }
                showMediaSheet(items, pageTitle, imageTitle)
            }
        }
    }

    private fun toImageItem(url: String, index: Int): MediaItem {
        val ext = urlExtension(url).ifBlank { "jpg" }.take(6)
        return MediaItem(
            url = url,
            filename = "${getString(R.string.image)} ${index + 1}",
            extension = ext,
            isAudio = false,
            isImage = true
        )
    }

    private fun toMediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').substringBefore('#')
        val lastSegment = path.substringAfterLast('/')
        val ext = lastSegment.substringAfterLast('.', "").lowercase().take(6)
        // Only real media reaches interceptedUrls (images use a separate path), so an
        // image-extension entry here is a content-verified HLS playlist disguised as
        // an image — label it as a video stream so the user knows what it is.
        if (ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")) {
            // Pull a resolution hint from the parent path segment (e.g. ".../<id>-720/index.jpg").
            val parent = path.substringBeforeLast('/').substringAfterLast('/')
            val res = Regex("-(\\d{3,4})(?:p)?$").find(parent)?.groupValues?.get(1)
            return MediaItem(
                url = url,
                filename = getString(R.string.video) + (res?.let { " ${it}p" } ?: ""),
                extension = "m3u8",
                isAudio = false
            )
        }
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

    private fun showMediaSheet(items: List<MediaItem>, pageTitle: String? = null, imageTitle: String? = null) {
        if (items.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_media_found, Toast.LENGTH_SHORT).show()
            return
        }

        val images = items.filter { it.isImage }
        val media  = items.filter { !it.isImage }

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
            // "Download all" skips items already downloaded (per the request to not
            // re-download by default); tapping an item individually still re-downloads.
            val direct = media.filter { isDirectFile(it.url) && !it.alreadyDownloaded }
            val stream = media.filter { !isDirectFile(it.url) && !it.alreadyDownloaded }
            val freshImages = images.filter { !it.alreadyDownloaded }
            direct.forEach { downloadDirectFile(it.url, pageTitle) }
            if (stream.isNotEmpty()) downloadAllUrls(stream.map { it.url })
            if (freshImages.isNotEmpty()) downloadImages(freshImages.map { it.url }, imageTitle ?: pageTitle)
        }
        view.findViewById<MaterialButton>(R.id.download_one_by_one_btn)?.setOnClickListener {
            dialog.dismiss()
            showOneByOneSheet(items, imageTitle)
        }
        // "Download with rules" only applies to video/audio; hide it for pure image posts.
        view.findViewById<MaterialButton>(R.id.download_rules_btn)?.apply {
            isVisible = media.isNotEmpty()
            setOnClickListener {
                dialog.dismiss()
                showRulesDialog(media)
            }
        }

        val recycler = view.findViewById<RecyclerView>(R.id.media_url_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            downloadMediaItem(item, imageTitle ?: pageTitle)
        }
        dialog.show()
    }

    /**
     * Route a single tapped item: images and progressive files download in-process
     * immediately; streaming manifests (m3u8/mpd) go to yt-dlp.
     */
    private fun downloadMediaItem(item: MediaItem, imageTitle: String?) {
        when {
            item.isImage -> downloadImages(listOf(item.url), imageTitle)
            isDirectFile(item.url) -> downloadDirectFile(item.url, imageTitle)
            else -> downloadSingleUrl(item.url)
        }
    }

    private fun isDirectFile(url: String) = urlExtension(url) in DIRECT_DOWNLOAD_EXTENSIONS

    private fun showOneByOneSheet(items: List<MediaItem>, imageTitle: String? = null) {
        val view = layoutInflater.inflate(R.layout.bottomsheet_media_urls, null)

        view.findViewById<LinearLayout>(R.id.batch_actions)?.isVisible = false

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()

        val recycler = view.findViewById<RecyclerView>(R.id.media_url_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            downloadMediaItem(item, imageTitle)
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
        // Many CDNs (HLS streams, tokenized links) reject requests without the
        // originating page as Referer, so pass the current page URL through to yt-dlp.
        val referer = currentTab?.url?.takeIf { it.startsWith("http") && it != "about:blank" }
        // For raw media URLs yt-dlp names the file after the URL basename (e.g.
        // "index" for index.m3u8). Use the page title so the file matches the video;
        // a non-blank DownloadItem.title makes yt-dlp rewrite %(title)s to it.
        val pageTitle = currentTab?.title?.trim()
            ?.takeIf { it.isNotBlank() && it != "about:blank" && it != getString(R.string.new_tab) }
        val cookieManager = android.webkit.CookieManager.getInstance()
        val items = urls.mapIndexed { i, url ->
            val item = downloadViewModel.createDownloadItemFromResult(
                result    = downloadViewModel.createEmptyResultItem(url),
                givenType = type
            )
            if (worstQuality) item.format.format_id = "worst"
            if (referer != null && !item.extraCommands.contains("--referer")) {
                item.extraCommands = "${item.extraCommands} --referer \"$referer\"".trim()
            }
            // Token-gated CDNs (e.g. Cloudflare-protected HLS) often authorize via a
            // cookie set on the media host during playback. Forward the WebView's
            // cookies for that host so yt-dlp's manifest + segment requests pass.
            val cookie = cookieManager.getCookie(url)?.trim()?.takeIf { it.isNotBlank() }
            if (cookie != null && !item.extraCommands.contains("Cookie:")) {
                val safe = cookie.replace("\"", "")
                item.extraCommands = "${item.extraCommands} --add-header \"Cookie:$safe\"".trim()
            }
            // Replay any token/auth headers the page used when it fetched this media,
            // so CDNs that gate on a per-request header still authorize yt-dlp.
            currentTab?.mediaHeaders?.get(url)?.forEach { (k, v) ->
                val kl = k.lowercase()
                if (kl !in SKIP_REPLAY_HEADERS && v.isNotBlank() && !item.extraCommands.contains("$k:")) {
                    item.extraCommands = "${item.extraCommands} --add-header \"$k:${v.replace("\"", "")}\"".trim()
                }
            }
            // Per-video title (page title + the URL's own id) so different videos on
            // one page get distinct filenames instead of all overwriting each other.
            item.title = mediaTitle(url, pageTitle)
            item
        }
        BrowserDownloadHistory.markDownloaded(requireContext(), urls)
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

    /**
     * Download plain images (e.g. a Douyin 图文 gallery) directly, off the yt-dlp path.
     * Runs in a WorkManager worker with the page's Referer/Cookie/UA so signed image
     * CDNs still authorize, and saves into the gallery.
     */
    private fun downloadImages(urls: List<String>, title: String?) {
        if (urls.isEmpty()) return
        val referer = currentTab?.url?.takeIf { it.startsWith("http") } ?: "https://www.douyin.com/"
        val cookie  = urls.firstOrNull()
            ?.let { android.webkit.CookieManager.getInstance().getCookie(it) } ?: ""
        val ua = currentTab?.webView?.settings?.userAgentString ?: ""
        val data = Data.Builder()
            .putStringArray(ImageDownloadWorker.KEY_URLS, urls.toTypedArray())
            .putString(ImageDownloadWorker.KEY_TITLE, title?.takeIf { it.isNotBlank() })
            .putString(ImageDownloadWorker.KEY_REFERER, referer)
            .putString(ImageDownloadWorker.KEY_COOKIE, cookie)
            .putString(ImageDownloadWorker.KEY_USER_AGENT, ua)
            .build()
        WorkManager.getInstance(requireContext()).enqueue(
            OneTimeWorkRequestBuilder<ImageDownloadWorker>().setInputData(data).build()
        )
        BrowserDownloadHistory.markDownloaded(requireContext(), urls)
        Toast.makeText(
            requireContext(),
            "${urls.size} ${getString(R.string.downloading)}",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Download a single progressive media file immediately via OkHttp (off the yt-dlp
     * queue), replaying the page's Referer/Cookie/User-Agent and the exact request
     * headers the WebView used — the best shot at beating short-lived signed URLs.
     */
    private fun downloadDirectFile(url: String, title: String?) {
        val referer = currentTab?.url?.takeIf { it.startsWith("http") && it != "about:blank" } ?: ""
        val cookie  = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
        val ua = currentTab?.webView?.settings?.userAgentString ?: ""
        val fileTitle = mediaTitle(url, title ?: currentTab?.title)
        val headerJson = JSONObject().apply {
            currentTab?.mediaHeaders?.get(url)?.forEach { (k, v) ->
                if (k.lowercase() !in SKIP_REPLAY_HEADERS && v.isNotBlank()) put(k, v)
            }
        }.toString()
        val data = Data.Builder()
            .putString(DirectDownloadWorker.KEY_URL, url)
            .putString(DirectDownloadWorker.KEY_TITLE, fileTitle)
            .putString(DirectDownloadWorker.KEY_REFERER, referer)
            .putString(DirectDownloadWorker.KEY_COOKIE, cookie)
            .putString(DirectDownloadWorker.KEY_USER_AGENT, ua)
            .putString(DirectDownloadWorker.KEY_HEADERS, headerJson)
            .putBoolean(DirectDownloadWorker.KEY_IS_AUDIO, urlExtension(url) in AUDIO_EXTENSIONS)
            .build()
        WorkManager.getInstance(requireContext()).enqueue(
            OneTimeWorkRequestBuilder<DirectDownloadWorker>().setInputData(data).build()
        )
        BrowserDownloadHistory.markDownloaded(requireContext(), listOf(url))
        Toast.makeText(
            requireContext(),
            "1 ${getString(R.string.downloading)}",
            Toast.LENGTH_SHORT
        ).show()
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
        val isAudio: Boolean,
        val isImage: Boolean = false,
        val alreadyDownloaded: Boolean = false
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
            // Flag already-downloaded items with a check so the user can tell (still tappable to re-download).
            holder.filename.text = if (item.alreadyDownloaded) "✓ ${item.filename}" else item.filename
            holder.itemView.alpha = if (item.alreadyDownloaded) 0.5f else 1f
            holder.ext.text = item.extension
            holder.url.text = extractDomain(item.url) ?: item.url
            holder.icon.setImageResource(
                when {
                    item.isImage -> R.drawable.ic_image
                    item.isAudio -> R.drawable.baseline_audio_file_24
                    else         -> R.drawable.baseline_video_file_24
                }
            )
            holder.itemView.setOnClickListener { onClick(item) }
            holder.btn.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
