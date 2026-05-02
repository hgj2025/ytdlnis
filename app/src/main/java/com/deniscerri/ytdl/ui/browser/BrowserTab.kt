package com.deniscerri.ytdl.ui.browser

import android.webkit.WebView
import java.util.concurrent.CopyOnWriteArraySet

data class BrowserTab(
    val id: Int,
    val webView: WebView,
    var url: String = "",
    var title: String = "New Tab",
    val interceptedUrls: CopyOnWriteArraySet<String> = CopyOnWriteArraySet(),
    // True after an apex-domain DNS failure has been retried with `www.`
    // prepended, so we don't loop forever on actually-broken hosts.
    var wwwRetried: Boolean = false
)
