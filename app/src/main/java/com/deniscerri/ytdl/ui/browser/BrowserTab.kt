package com.deniscerri.ytdl.ui.browser

import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

data class BrowserTab(
    val id: Int,
    val webView: WebView,
    var url: String = "",
    var title: String = "New Tab",
    // Detected media URL -> the page URL it was found on. Scoping captures to a
    // page means media from a previously-visited page never leaks into the
    // download sheet of the current one.
    val interceptedUrls: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    // Detected media URL -> the request headers the page used to fetch it. Some
    // CDNs authorize via a per-request token header (Authorization/x-*), so we
    // replay these to yt-dlp to reproduce an authorized request.
    val mediaHeaders: ConcurrentHashMap<String, HashMap<String, String>> = ConcurrentHashMap(),
    // Page URL -> ordered image URLs of the image-gallery post opened on that page
    // (e.g. Douyin 图文). Page-scoped like interceptedUrls so posts don't leak.
    val imageGroups: ConcurrentHashMap<String, LinkedHashSet<String>> = ConcurrentHashMap(),
    // Page URL -> the post's title/description, used to name the saved images.
    val imageTitles: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    // True after an apex-domain DNS failure has been retried with `www.`
    // prepended, so we don't loop forever on actually-broken hosts.
    var wwwRetried: Boolean = false,
    // Last known vertical scroll offset per visited URL. Used to restore the
    // browsing position when the user goes back to a page that reloads from
    // scratch (many sites send Cache-Control: no-store, so WebView's built-in
    // back-forward scroll restoration doesn't kick in).
    val scrollPositions: HashMap<String, Int> = HashMap(),
    // Scroll offset to re-apply after the next back navigation finishes loading.
    var pendingRestoreY: Int? = null
)
