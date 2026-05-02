package com.deniscerri.ytdl.ui.downloads

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.deniscerri.ytdl.MainActivity
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.viewmodel.DownloadCardViewModel
import com.deniscerri.ytdl.database.viewmodel.DownloadViewModel
import com.deniscerri.ytdl.util.NavbarUtil
import com.deniscerri.ytdl.util.NotificationUtil
import com.deniscerri.ytdl.util.UiUtil
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class DownloadQueueMainFragment : Fragment(){
    private lateinit var downloadViewModel: DownloadViewModel
    private lateinit var downloadCardViewModel: DownloadCardViewModel
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var headerActive: TextView
    private lateinit var headerQueued: TextView
    private lateinit var emptyActive: TextView
    private lateinit var mainActivity: MainActivity
    private lateinit var notificationUtil: NotificationUtil
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        mainActivity = activity as MainActivity
        notificationUtil = NotificationUtil(mainActivity)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return inflater.inflate(R.layout.fragment_download_queue_main_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        downloadViewModel = ViewModelProvider(requireActivity())[DownloadViewModel::class.java]
        downloadCardViewModel = ViewModelProvider(requireActivity())[DownloadCardViewModel::class.java]

        topAppBar = view.findViewById(R.id.downloads_toolbar)
        val isInNavBar = NavbarUtil.getNavBarItems(requireActivity()).any { n -> n.itemId == R.id.downloadQueueMainFragment && n.isVisible }
        if (isInNavBar) {
            topAppBar.navigationIcon = null
        }else{
            mainActivity.hideBottomNavigation()
        }
        topAppBar.setNavigationOnClickListener {
            mainActivity.onBackPressedDispatcher.onBackPressed()
        }

        headerActive = view.findViewById(R.id.header_active)
        headerQueued = view.findViewById(R.id.header_queued)
        emptyActive  = view.findViewById(R.id.empty_active)

        initMenu()

        // Notification "reconfigure" entry: an errored item was tapped from a notification.
        // Skip directly into the download editor without surfacing the legacy errored tab.
        val reconfigureID = arguments?.getLong("reconfigure")
        if (arguments?.getString("tab") != null && reconfigureID != null && reconfigureID != 0L) {
            notificationUtil.cancelErroredNotification(reconfigureID.toInt())
            lifecycleScope.launch {
                kotlin.runCatching {
                    val item = withContext(Dispatchers.IO){
                        downloadViewModel.getItemByID(reconfigureID)
                    }
                    downloadCardViewModel.setResultItem(downloadViewModel.createResultItemFromDownload(item))
                    downloadCardViewModel.setDownloadItem(item)
                    findNavController().navigate(
                        R.id.downloadBottomSheetDialog,
                        bundleOf("type" to item.type)
                    )
                }
            }
        }
        arguments?.clear()

        if (sharedPreferences.getBoolean("show_count_downloads", false)){
            lifecycleScope.launch {
                downloadViewModel.activeDownloadsCount.collectLatest {
                    headerActive.text = if (it > 0)
                        "${getString(R.string.downloading)} · $it"
                    else getString(R.string.downloading)
                    emptyActive.isVisible = it == 0
                }
            }
            lifecycleScope.launch {
                downloadViewModel.queuedDownloadsCount.collectLatest {
                    headerQueued.text = if (it > 0)
                        "${getString(R.string.in_queue)} · $it"
                    else getString(R.string.in_queue)
                }
            }
        } else {
            lifecycleScope.launch {
                downloadViewModel.activeDownloadsCount.collectLatest {
                    emptyActive.isVisible = it == 0
                }
            }
        }
    }

    private fun initMenu() {
        topAppBar.setOnMenuItemClickListener { m: MenuItem ->
            try{
                when(m.itemId){
                    R.id.clear_all -> {
                        UiUtil.showGenericDeleteAllDialog(requireContext()) {
                            downloadViewModel.deleteAll()
                        }
                    }
                    R.id.clear_queue -> {
                        UiUtil.showGenericDeleteAllDialog(requireContext()) {
                            downloadViewModel.cancelAllDownloads()
                        }
                    }
                }
            }catch (e: Exception){
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            }

            true
        }
    }

    fun scrollToActive(){
        view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.queue_scroll)
            ?.smoothScrollTo(0, 0)
    }
}
