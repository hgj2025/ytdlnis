package com.deniscerri.ytdl.ui.browser

import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicInteger

class BrowserViewModel : ViewModel() {
    val tabs = mutableListOf<BrowserTab>()
    var currentTabIndex: Int = -1
    val nextTabId = AtomicInteger(0)

    val currentTab: BrowserTab?
        get() = tabs.getOrNull(currentTabIndex)

    fun setCurrentTab(tab: BrowserTab?) {
        currentTabIndex = if (tab == null) -1 else tabs.indexOf(tab)
    }

    override fun onCleared() {
        super.onCleared()
        tabs.forEach { it.webView.destroy() }
        tabs.clear()
    }
}
