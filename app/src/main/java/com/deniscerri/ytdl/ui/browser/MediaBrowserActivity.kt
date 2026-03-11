package com.deniscerri.ytdl.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.MenuItem
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.ui.BaseActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

class MediaBrowserActivity : BaseActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var urlEditText: TextInputEditText
    private lateinit var fab: FloatingActionButton
    private lateinit var tabCountBtn: MaterialButton
    private lateinit var tabsRecycler: RecyclerView
    private lateinit var tabsTitle: TextView
    private lateinit var tabAdapter: TabAdapter

    // ── Tab state ─────────────────────────────────────────────────────────────
    private val tabs = mutableListOf<BrowserTab>()
    private var currentTab: BrowserTab? = null
    private val nextTabId = AtomicInteger(0)

    data class BrowserTab(
        val id: Int,
        val webView: WebView,
        var url: String = "",
        var title: String = "New Tab",
        val interceptedUrls: CopyOnWriteArraySet<String> = CopyOnWriteArraySet()
    )

    // ── Media constants ───────────────────────────────────────────────────────
    private val MEDIA_EXTENSIONS = setOf(
        "m3u8", "mp4", "mp3", "m4a", "m4v", "webm", "mkv", "ts",
        "aac", "ogg", "flac", "wav", "opus", "mpd", "avi", "mov"
    )
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")
    private val MEDIA_KEYWORDS = listOf("/manifest", "/stream", "video/", "audio/")

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_browser)

        drawerLayout     = findViewById(R.id.drawer_layout)
        webviewContainer = findViewById(R.id.webview_container)
        toolbar          = findViewById(R.id.toolbar)
        urlEditText      = findViewById(R.id.url_edittext)
        fab              = findViewById(R.id.fab_media)
        tabsRecycler     = findViewById(R.id.tabs_recycler)
        tabsTitle        = findViewById(R.id.tabs_title)

        tabCountBtn = toolbar.menu.findItem(R.id.tab_count)
            .actionView!!.findViewById(R.id.tab_count_btn)

        setupToolbar()
        setupDrawer()
        setupUrlBar()
        setupBackHandler()
        setupDraggableFab()
        setFabState(hasMedia = false)

        val initialUrl = intent.getStringExtra(EXTRA_URL) ?: "about:blank"
        if (savedInstanceState != null) {
            val urls = savedInstanceState.getStringArrayList("tab_urls") ?: arrayListOf(initialUrl)
            val cur  = savedInstanceState.getInt("current_tab_index", 0)
            urls.forEachIndexed { i, url -> createTab(url, switchTo = (i == cur)) }
        } else {
            createTab(initialUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("tab_urls", ArrayList(tabs.map { it.url }))
        outState.putInt("current_tab_index", tabs.indexOf(currentTab))
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        super.onDestroy()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        // Hamburger icon opens drawer; close is inside the drawer
        toolbar.setNavigationOnClickListener { openDrawer() }
        toolbar.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.nav_back     -> currentTab?.webView?.goBack()
                R.id.nav_forward  -> currentTab?.webView?.goForward()
                R.id.nav_reload   -> currentTab?.webView?.reload()
                R.id.new_tab_menu -> { createTab("about:blank"); openDrawer() }
            }
            true
        }
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
        tabsRecycler.layoutManager = LinearLayoutManager(this)
        tabsRecycler.adapter = tabAdapter

        findViewById<MaterialButton>(R.id.close_all_btn).setOnClickListener { closeAllTabs() }
        findViewById<MaterialButton>(R.id.close_browser_btn).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.new_tab_btn).setOnClickListener {
            createTab("about:blank")
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun setupUrlBar() {
        urlEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val input = urlEditText.text?.toString()?.trim() ?: ""
                val url   = if (input.startsWith("http")) input else "https://$input"
                currentTab?.webView?.loadUrl(url)
                true
            } else false
        }
        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener {
            currentTab?.webView?.reload()
        }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    drawerLayout.closeDrawer(GravityCompat.START)
                currentTab?.webView?.canGoBack() == true ->
                    currentTab?.webView?.goBack()
                else -> finish()
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

    // ── Tab management ────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun createTab(url: String, switchTo: Boolean = true): BrowserTab {
        val webView = WebView(this)
        val tab = BrowserTab(id = nextTabId.getAndIncrement(), webView = webView, url = url)
        configureWebView(tab)

        webviewContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        webView.visibility = View.GONE
        tabs.add(tab)

        if (url != "about:blank") webView.loadUrl(url) else tab.title = getString(R.string.new_tab)

        if (switchTo) switchToTab(tab) else updateTabUi()
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(tab: BrowserTab) {
        val wv = tab.webView
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMediaFound(url: String) {
                if (url.startsWith("http")) {
                    val wasEmpty = tab.interceptedUrls.isEmpty()
                    tab.interceptedUrls.add(url)
                    if (wasEmpty && tab == currentTab) {
                        runOnUiThread { setFabState(hasMedia = true) }
                    }
                }
            }
        }, "Android")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?, request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (isMediaUrl(url, request)) {
                    val wasEmpty = tab.interceptedUrls.isEmpty()
                    tab.interceptedUrls.add(url)
                    if (wasEmpty && tab == currentTab) {
                        runOnUiThread { setFabState(hasMedia = true) }
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url ?: return
                tab.url = url
                tab.interceptedUrls.clear()
                if (tab == currentTab) {
                    urlEditText.setText(url)
                    setFabState(hasMedia = false)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { tab.url = it }
                view?.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
                if (tab == currentTab) {
                    toolbar.title = tab.title
                    urlEditText.setText(tab.url)
                    setFabState(hasMedia = tab.interceptedUrls.isNotEmpty())
                }
                tabAdapter.notifyDataSetChanged()
                wv.evaluateJavascript(OBSERVE_JS, null)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    if (tab == currentTab) toolbar.title = title
                    tabAdapter.notifyDataSetChanged()
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
        toolbar.title = tab.title
        urlEditText.setText(tab.url)
        setFabState(hasMedia = tab.interceptedUrls.isNotEmpty())
        updateTabUi()
    }

    private fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab).takeIf { it >= 0 } ?: return
        tabs.removeAt(index)
        webviewContainer.removeView(tab.webView)
        tab.webView.destroy()

        if (tab == currentTab) {
            currentTab = null
            val next = tabs.getOrNull(minOf(index, tabs.size - 1))
            if (next != null) switchToTab(next) else finish()
        } else {
            updateTabUi()
        }
    }

    private fun closeAllTabs() {
        currentTab = null
        webviewContainer.removeAllViews()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
        finish()
    }

    private fun updateTabUi() {
        val count = tabs.size
        tabCountBtn.text = count.toString()
        tabsTitle.text = "${getString(R.string.media_browser)} ($count)"
        tabAdapter.notifyDataSetChanged()
    }

    private fun openDrawer() = drawerLayout.openDrawer(GravityCompat.START)

    // ── Link long-press menu ──────────────────────────────────────────────────

    private fun showLinkMenu(url: String) {
        val truncated = if (url.length > 80) url.take(80) + "…" else url
        MaterialAlertDialogBuilder(this)
            .setTitle(truncated)
            .setItems(arrayOf(
                getString(R.string.open_current_tab),
                getString(R.string.open_new_tab),
                getString(R.string.open_background_tab)
            )) { _, which ->
                when (which) {
                    0 -> { currentTab?.webView?.loadUrl(url); currentTab?.let { it.url = url } }
                    1 -> createTab(url, switchTo = true)
                    2 -> createTab(url, switchTo = false)
                }
            }
            .show()
    }

    // ── Media detection ───────────────────────────────────────────────────────

    private fun isMediaUrl(url: String, request: WebResourceRequest?): Boolean {
        val lower = url.lowercase()
        val ext   = lower.substringAfterLast('.').substringBefore('?').substringBefore('#')
        if (ext in MEDIA_EXTENSIONS) return true
        if (MEDIA_KEYWORDS.any { lower.contains(it) }) return true
        val accept = request?.requestHeaders?.get("Accept") ?: ""
        return accept.contains("video/", ignoreCase = true) ||
               accept.contains("audio/", ignoreCase = true)
    }

    private fun collectAndShowMedia() {
        val tab = currentTab ?: return
        tab.webView.evaluateJavascript(SCAN_JS) { result ->
            val merged = LinkedHashSet<String>(tab.interceptedUrls)
            runCatching {
                val arr = result?.trim()?.removeSurrounding("\"")
                    ?.replace("\\\"", "\"") ?: "[]"
                val json = JSONArray(arr)
                for (i in 0 until json.length()) {
                    val u = json.optString(i)
                    if (u.startsWith("http")) merged.add(u)
                }
            }
            val items = merged.map { toMediaItem(it) }
            runOnUiThread { showMediaSheet(items) }
        }
    }

    private fun toMediaItem(url: String): MediaItem {
        val ext = url.lowercase().substringAfterLast('/')
            .substringAfterLast('.').substringBefore('?').substringBefore('#').take(6)
        return MediaItem(url, ext.ifBlank { "?" }, ext in AUDIO_EXTENSIONS)
    }

    private fun showMediaSheet(items: List<MediaItem>) {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_media_found, Toast.LENGTH_SHORT).show()
            return
        }
        val dialog  = BottomSheetDialog(this)
        val view    = layoutInflater.inflate(R.layout.bottomsheet_media_urls, null)

        val batchActions = view.findViewById<LinearLayout>(R.id.batch_actions)
        batchActions.isVisible = items.size > 1

        view.findViewById<MaterialButton>(R.id.download_all_btn)?.setOnClickListener {
            dialog.dismiss()
            // Return all URLs; caller picks the first or batch-queues them
            val item = items.first()
            setResult(RESULT_OK, Intent()
                .putExtra(RESULT_MEDIA_URL, item.url)
                .putStringArrayListExtra(RESULT_MEDIA_URLS, ArrayList(items.map { it.url })))
            finish()
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
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            setResult(RESULT_OK, Intent().putExtra(RESULT_MEDIA_URL, item.url))
            finish()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showOneByOneSheet(items: List<MediaItem>) {
        val dialog = BottomSheetDialog(this)
        val view   = layoutInflater.inflate(R.layout.bottomsheet_media_urls, null)
        view.findViewById<LinearLayout>(R.id.batch_actions)?.isVisible = false
        val recycler = view.findViewById<RecyclerView>(R.id.media_url_list)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = MediaUrlAdapter(items) { item ->
            dialog.dismiss()
            setResult(RESULT_OK, Intent().putExtra(RESULT_MEDIA_URL, item.url))
            finish()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showRulesDialog(items: List<MediaItem>) {
        val types = arrayOf(getString(R.string.video), getString(R.string.audio))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.download_with_rules))
            .setMessage(getString(R.string.download_type_label))
            .setItems(types) { _, typeIdx ->
                val quality = arrayOf(getString(R.string.best_quality), getString(R.string.worst_quality))
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.quality_label))
                    .setItems(quality) { _, qualIdx ->
                        // Return all URLs with type/quality metadata
                        setResult(RESULT_OK, Intent()
                            .putExtra(RESULT_MEDIA_URL, items.first().url)
                            .putStringArrayListExtra(RESULT_MEDIA_URLS, ArrayList(items.map { it.url }))
                            .putExtra(RESULT_DOWNLOAD_TYPE, if (typeIdx == 0) "video" else "audio")
                            .putExtra(RESULT_WORST_QUALITY, qualIdx == 1))
                        finish()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ── Adapters ──────────────────────────────────────────────────────────────

    private class TabAdapter(
        private val tabs: List<BrowserTab>,
        private val getCurrent: () -> BrowserTab?,
        private val onSwitch: (BrowserTab) -> Unit,
        private val onClose: (BrowserTab) -> Unit
    ) : RecyclerView.Adapter<TabAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView    = view.findViewById(R.id.tab_title)
            val url: TextView      = view.findViewById(R.id.tab_url)
            val close: android.widget.ImageButton = view.findViewById(R.id.tab_close)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
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
            holder.itemView.setOnClickListener { onSwitch(tab) }
            holder.close.setOnClickListener { onClose(tab) }
        }

        override fun getItemCount() = tabs.size
    }

    data class MediaItem(val url: String, val extension: String, val isAudio: Boolean)

    private class MediaUrlAdapter(
        private val items: List<MediaItem>,
        private val onClick: (MediaItem) -> Unit
    ) : RecyclerView.Adapter<MediaUrlAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.media_type_icon)
            val ext: TextView   = view.findViewById(R.id.media_extension)
            val url: TextView   = view.findViewById(R.id.media_url)
            val btn: View       = view.findViewById(R.id.download_btn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.media_url_item, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.ext.text = item.extension.uppercase()
            holder.url.text = try { java.net.URLDecoder.decode(item.url, "UTF-8") } catch (_: Exception) { item.url }
            holder.icon.setImageResource(
                if (item.isAudio) R.drawable.baseline_audio_file_24
                else R.drawable.baseline_video_file_24
            )
            holder.itemView.setOnClickListener { onClick(item) }
            holder.btn.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val EXTRA_URL         = "extra_url"
        const val RESULT_MEDIA_URL  = "result_media_url"
        const val RESULT_MEDIA_URLS = "result_media_urls"
        const val RESULT_DOWNLOAD_TYPE  = "result_download_type"
        const val RESULT_WORST_QUALITY  = "result_worst_quality"
    }
}
