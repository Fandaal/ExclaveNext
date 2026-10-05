/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileListBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.toConf
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.group.Protocols
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.ui.profile.*
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipInputStream
import kotlin.concurrent.timerTask
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat

// Short alias for the span flag used on the test counter; spelled out three
// times per render otherwise.
private const val SPAN = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE

@android.annotation.SuppressLint("ClickableViewAccessibility")
fun View.suppressDragWhilePressed(setPressed: (Boolean) -> Unit) {
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressed(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setPressed(false)
        }
        false
    }
}

class ConfigurationFragment @JvmOverloads constructor(
    val select: Boolean = false, val selectedItem: ProxyEntity? = null, val titleRes: Int = 0
) : ToolbarFragment(R.layout.layout_group_list),
    PopupMenu.OnMenuItemClickListener,
    Toolbar.OnMenuItemClickListener {

    // One callback for this screen, enabled whenever the search field is open
        // OR selection mode is on. Registered once, like the original code, so
        // there is nothing to re-arm.
        //
        // A second, separate callback for selection mode was the wrong approach:
        // it had to be kept in sync with this one, and every attempt either left
        // Back dead or sent it somewhere that did nothing. Selection mode is
        // handled here instead, where Back is already routed.
        val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
            override fun handleOnBackPressed() {
                val fragment = currentGroupFragment()

                // Selection mode first: Back leaves the selection, and the
                // search field is left alone.
                if (fragment != null && fragment.isSelectionMode()) {
                    // setSelectionMode(false) inside has already re-synced this
                    // callback (synchronously on the main thread): it stays
                    // armed while the search field is open, so the next Back
                    // collapses the search instead of finishing the activity.
                    // Do NOT recompute isEnabled here — the sync reads
                    // SearchView.isIconified(), which does not change while a
                    // selection is being exited.
                    fragment.exitSelectionMode()
                    return
                }

                searchView?.onActionViewCollapsed()
                searchView?.clearFocus()
                // The query survives onActionViewCollapsed(), so clear it here —
                // otherwise the fragment would keep showing the filtered bar with
                // no search field visible to explain it.
                searchView?.setQuery("", false)
                // Collapsing the field just changed what this callback should do,
                // so re-read the state: nothing is left to consume a Back press.
                fragment?.syncBackCallback()
            }
        }

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    lateinit var adapter: GroupPagerAdapter
    lateinit var tabLayout: TabLayout
    lateinit var groupPager: ViewPager2
    var searchView: SearchView? = null

    // Whether the search field is currently on screen. Read straight from
    // SearchView instead of tracking it by hand: isIconified() is public in
    // appcompat (verified against 1.8.0 with javap) and is set false by
    // onSearchClicked(), true by onActionViewCollapsed()/onCloseClicked().
    // The previous hand-rolled proxy (focus OR non-empty query) needed three
    // separate sync points and still went stale, which is what made Back
    // unusable. Note this is NOT the same as isFocused: SearchView is a
    // ViewGroup, so it is unfocused while its inner editor holds the focus.
    val searchFieldOpen: Boolean get() = searchView?.isIconified == false

    /** The GroupFragment for the tab in front of the user — the one the
     *  Back callback and the selection bar both act on. */
    private fun currentGroupFragment(): GroupFragment? = childFragmentManager
        .findFragmentByTag("f" + selectedGroup.id) as? GroupFragment
    val selectedGroup get() = if (tabLayout.isGone && adapter.groupList.size > 0) adapter.groupList[0] else (if (adapter.groupList.size > 0 && tabLayout.selectedTabPosition > -1) adapter.groupList[tabLayout.selectedTabPosition] else ProxyGroup())
    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    val updateSelectedCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageScrolled(
            position: Int, positionOffset: Float, positionOffsetPixels: Int
        ) {
            if (adapter.groupList.size > position) {
                DataStore.selectedGroup = adapter.groupList[position].id
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            parentFragmentManager.beginTransaction()
                .setReorderingAllowed(false)
                .detach(this)
                .attach(this)
                .commit()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!select) {
            toolbar.inflateMenu(R.menu.add_profile_menu)
            toolbar.setOnMenuItemClickListener(this)
        } else {
            toolbar.setTitle(titleRes)
            toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
            toolbar.setNavigationOnClickListener {
                requireActivity().finish()
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.group_tab)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
            )
            insets
        }

        searchView = toolbar.findViewById(R.id.action_search)
        searchView?.maxWidth = Int.MAX_VALUE
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(query: String?): Boolean {
                query?.let {
                    try {
                        val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
                        fragment?.adapter?.filter(query)
                        // Tell the fragment about the query too: it keeps its
                        // own bar in sync, which is the only bulk-action entry
                        // point while the search field owns the toolbar.
                        fragment?.onSearchFilterChanged(query)
                    } catch (_: Exception) {}
                }
                return false
            }
        })
        searchView?.setOnQueryTextFocusChangeListener { _, _ ->
            // Nothing to compute or remember here: searchFieldOpen reads
            // isIconified() on demand. The listener exists only because the
            // field's open/closed state can change without any other call
            // site of ours running (tapping the search icon, or the close
            // button collapsing an empty field), and Back has to follow.
            currentGroupFragment()?.syncBackCallback()
        }
        searchView?.let {
            // override onBackPressedCallback of MainActivity
            (requireActivity() as? MainActivity)?.onBackPressedDispatcher?.addCallback(this, onBackPressedCallback)
        }

        groupPager = view.findViewById(R.id.group_pager)
        tabLayout = view.findViewById(R.id.group_tab)
        adapter = GroupPagerAdapter()
        ProfileManager.addListener(adapter)
        GroupManager.addListener(adapter)

        groupPager.adapter = adapter
        groupPager.offscreenPageLimit = 2

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                searchView?.onActionViewCollapsed()
                searchView?.clearFocus()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
                fragment?.adapter?.filter("")
                fragment?.onSearchFilterChanged("")
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}

        })

        TabLayoutMediator(tabLayout, groupPager) { tab, position ->
            if (adapter.groupList.size > position) {
                tab.text = adapter.groupList[position].displayName()
            }
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()

        toolbar.setOnClickListener {

            val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)

            if (fragment != null) {
                val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                val selectedProfileIndex = fragment.adapter.configurationIdList.indexOf(
                    selectedProxy
                )
                if (selectedProfileIndex != -1) {
                    val layoutManager = fragment.layoutManager
                    val first = layoutManager.findFirstVisibleItemPosition()
                    val last = layoutManager.findLastVisibleItemPosition()

                    if (selectedProfileIndex !in first..last) {
                        fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                        return@setOnClickListener
                    }

                }

                fragment.configurationListView.scrollTo(0)
            }

        }

        toolbar.setOnLongClickListener {
            val selectedProxy = selectedItem
                ?: SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                ?: return@setOnLongClickListener true
            val groupIndex = adapter.groupList.indexOfFirst {
                it.id == selectedProxy.groupId
            }
            if (groupIndex < 0) return@setOnLongClickListener true
            DataStore.selectedGroup = selectedProxy.groupId
            groupPager.currentItem = groupIndex

            val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
            if (fragment != null) {
                val selectedProfileIndex = fragment.adapter.configurationIdList.indexOfFirst {
                    it == selectedProxy.id
                }
                if (selectedProfileIndex > 0) {
                    fragment.configurationListView.scrollTo(selectedProfileIndex, true)
                }
            }

            true
        }

        (requireActivity() as? MainActivity)?.onBackPressedCallback?.isEnabled = false
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) {
            GroupManager.removeListener(adapter)
            ProfileManager.removeListener(adapter)
        }

        super.onDestroy()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        val fragment = (childFragmentManager.findFragmentByTag("f" + selectedGroup.id) as GroupFragment?)
        fragment?.configurationListView?.apply {
            if (!hasFocus()) requestFocus()
        }
        return super.onKeyDown(ketCode, event)
    }

    val importFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) runOnDefaultDispatcher {
            try {
                val ctx = requireContext()
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
                val txtFiles = tree?.listFiles()?.filter {
                    it.isFile && (it.name?.endsWith(".txt", ignoreCase = true) == true)
                } ?: emptyList()

                if (txtFiles.isEmpty()) {
                    onMainDispatcher {
                        snackbar(getString(R.string.no_txt_in_folder)).show()
                    }
                    return@runOnDefaultDispatcher
                }

                val proxies = mutableListOf<AbstractBean>()
                for (doc in txtFiles) {
                    try {
                        val fileText = ctx.contentResolver.openInputStream(doc.uri)?.use {
                            it.bufferedReader().readText()
                        } ?: continue
                        RawUpdater.parseRaw(fileText)?.let { pl -> proxies.addAll(pl) }
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }

                if (proxies.isEmpty()) {
                    onMainDispatcher {
                        snackbar(getString(R.string.no_proxies_found_in_file)).show()
                    }
                } else import(proxies)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    // Download a subscription URL and parse it into proxy beans, so a URL
    // pasted from the clipboard can be imported into the CURRENT group instead
    // of spawning a new subscription group. Mirrors RawUpdater's HTTP path.
    private fun fetchSubscriptionProxies(url: String): List<AbstractBean>? {
        val response = libexclavecore.Libexclavecore.newHttpClient().apply {
            if (SagerNet.started && DataStore.startedProfile > 0) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }.newRequest().apply {
            setURL(url)
            setUserAgent(USER_AGENT)
        }.execute()
        return RawUpdater.parseRaw(response.contentString)
    }

    // A subscription URL was pasted/opened: ask whether to add it as an
    // updatable subscription source of the current group, or import its
    // configs once as manual profiles (sourceId == 0). Must be called from a
    // background dispatcher; the dialog itself is shown on the main thread.
    private suspend fun offerSubscriptionOrImport(url: String) {
        onMainDispatcher {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.subscription_source_add)
                .setMessage(getString(R.string.subscription_import_prompt, url))
                .setPositiveButton(R.string.subscription_import_as_source) { _, _ ->
                    runOnDefaultDispatcher {
                        val groupId = DataStore.currentGroupId()
                        val source = SubscriptionSource(groupId = groupId).apply {
                            subscription = SubscriptionBean().applyDefaultValues().apply { link = url }
                        }
                        val created = GroupManager.createSource(source)
                        GroupUpdater.startUpdate(created, true)
                        onMainDispatcher {
                            snackbar(getString(R.string.subscription_source_added)).show()
                        }
                    }
                }
                .setNegativeButton(R.string.subscription_import_once) { _, _ ->
                    runOnDefaultDispatcher {
                        val fetched = try {
                            fetchSubscriptionProxies(url)
                        } catch (e: Exception) {
                            Logs.w(e)
                            null
                        }
                        if (!fetched.isNullOrEmpty()) {
                            import(fetched)
                        } else onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_subscription)).show()
                        }
                    }
                }
                .setNeutralButton(android.R.string.cancel, null)
                .show()
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        var fileText = ""
        if (file != null) runOnDefaultDispatcher {
            try {
                val fileName = requireContext().contentResolver.query(file, null, null, null, null)
                    ?.use { cursor ->
                        cursor.moveToFirst()
                        cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
                            .let(cursor::getString)
                    }

                val proxies = mutableListOf<AbstractBean>()
                if (fileName != null && fileName.endsWith(".zip")) {
                    // try parse wireguard zip
                    val zip = ZipInputStream(requireContext().contentResolver.openInputStream(file)!!)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        fileText = zip.bufferedReader().readText()
                        RawUpdater.parseRaw(fileText)?.let { pl -> proxies.addAll(pl) }
                        zip.closeEntry()
                    }
                    runCatching {
                        zip.close()
                    }
                } else {
                    fileText = requireContext().contentResolver.openInputStream(file)!!.use {
                        it.bufferedReader().readText()
                    }
                    RawUpdater.parseRaw(fileText)?.let { pl -> proxies.addAll(pl) }
                }

                if (proxies.isEmpty()) {
                    if (!fileText.contains("\n") && !fileText.contains("\r") && isHTTPorHTTPSURL(fileText)) {
                        offerSubscriptionOrImport(fileText.trim())
                    } else {
                        onMainDispatcher {
                            snackbar(getString(R.string.no_proxies_found_in_file)).show()
                        }
                    }
                } else import(proxies)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    val importBackupFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) runOnDefaultDispatcher {
            try {
                val text = requireContext().contentResolver.openInputStream(file)!!.use {
                    it.bufferedReader().readText()
                }
                val proxies = parseBackupLines(text)
                if (proxies.isNotEmpty()) {
                    import(proxies)
                } else onMainDispatcher {
                    snackbar(getString(R.string.no_proxies_found_in_file)).show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    snackbar(e.readableMessage).show()
                }
            }
        }
    }

    suspend fun import(proxies: List<AbstractBean>) {
        if (proxies.size <= 32) {
            finishImport(proxies)
        } else {
            val name = SagerDatabase.groupDao.getById(DataStore.selectedGroupForImport())!!.displayName()
            onMainDispatcher {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.profile_import)
                    .setMessage(resources.getQuantityString(R.plurals.profile_multi_import_message, proxies.size, proxies.size, name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            finishImport(proxies)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private suspend fun finishImport(proxies: List<AbstractBean>) {
        val targetId = DataStore.selectedGroupForImport()
        val targetIndex = adapter.groupList.indexOfFirst { it.id == targetId }

        for (proxy in proxies) {
            ProfileManager.createProfile(targetId, proxy)
        }
        onMainDispatcher {
            if (adapter.groupList.isEmpty() || selectedGroup.id != targetId) {
                if (targetIndex != -1) {
                    tabLayout.getTabAt(targetIndex)?.select()
                } else {
                    DataStore.selectedGroup = targetId
                    adapter.reload()
                }
            }

            snackbar(
                requireContext().resources.getQuantityString(
                    R.plurals.added, proxies.size, proxies.size
                )
            ).show()

            val group = SagerDatabase.groupDao.getById(targetId)!!
            GroupManager.updateGroup(group, reconfigureUpdater = false)
        }

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> {
                startActivity(Intent(context, ScannerActivity::class.java))
            }
            R.id.action_import_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    runOnDefaultDispatcher {
                        try {
                            val proxies = RawUpdater.parseRaw(text)
                            if (proxies.isNullOrEmpty()) {
                                if (!text.contains("\n") && !text.contains("\r") && isHTTPorHTTPSURL(text)) {
                                    // Subscription URL in clipboard: offer to add it as an
                                    // updatable source of the current group, or import once.
                                    offerSubscriptionOrImport(text.trim())
                                } else onMainDispatcher {
                                    snackbar(getString(R.string.no_proxies_found_in_clipboard)).show()
                                }
                            } else {
                                import(proxies)
                            }
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
            }
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
            }
            R.id.action_import_folder -> {
                try {
                    importFolder.launch(null)
                } catch (_: Exception) {
                    snackbar(getString(R.string.file_manager_missing)).show()
                }
            }
            R.id.action_import_backup_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (text.isBlank()) {
                    snackbar(getString(R.string.clipboard_empty)).show()
                } else {
                    runOnDefaultDispatcher {
                        try {
                            val proxies = parseBackupLines(text)
                            if (proxies.isNotEmpty()) {
                                import(proxies)
                            } else onMainDispatcher {
                                snackbar(getString(R.string.no_proxies_found_in_file)).show()
                            }
                        } catch (e: Exception) {
                            Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
            }
            R.id.action_import_backup_file -> {
                startFilesForResult(importBackupFile, "*/*")
            }
            R.id.action_new_socks -> {
                startActivity(Intent(requireActivity(), SocksSettingsActivity::class.java))
            }
            R.id.action_new_http -> {
                startActivity(Intent(requireActivity(), HttpSettingsActivity::class.java))
            }
            R.id.action_new_ss -> {
                startActivity(Intent(requireActivity(), ShadowsocksSettingsActivity::class.java))
            }
            R.id.action_new_ssr -> {
                startActivity(Intent(requireActivity(), ShadowsocksRSettingsActivity::class.java))
            }
            R.id.action_new_vmess -> {
                startActivity(Intent(requireActivity(), VMessSettingsActivity::class.java))
            }
            R.id.action_new_vless -> {
                startActivity(Intent(requireActivity(), VLESSSettingsActivity::class.java))
            }
            R.id.action_new_trojan -> {
                startActivity(Intent(requireActivity(), TrojanSettingsActivity::class.java))
            }
            R.id.action_new_naive -> {
                startActivity(Intent(requireActivity(), NaiveSettingsActivity::class.java))
            }
            R.id.action_new_hysteria2 -> {
                startActivity(Intent(requireActivity(), Hysteria2SettingsActivity::class.java))
            }
            R.id.action_new_mieru -> {
                startActivity(Intent(requireActivity(), MieruSettingsActivity::class.java))
            }
            R.id.action_new_tuic5 -> {
                startActivity(Intent(requireActivity(), Tuic5SettingsActivity::class.java))
            }
            R.id.action_new_ssh -> {
                startActivity(Intent(requireActivity(), SSHSettingsActivity::class.java))
            }
            R.id.action_new_wg -> {
                startActivity(Intent(requireActivity(), WireGuardSettingsActivity::class.java))
            }
            R.id.action_new_juicity -> {
                startActivity(Intent(requireActivity(), JuicitySettingsActivity::class.java))
            }
            R.id.action_new_http3 -> {
                startActivity(Intent(requireActivity(), Http3SettingsActivity::class.java))
            }
            R.id.action_new_anytls -> {
                startActivity(Intent(requireActivity(), AnyTLSSettingsActivity::class.java))
            }
            R.id.action_new_shadowquic -> {
                startActivity(Intent(requireActivity(), ShadowQUICSettingsActivity::class.java))
            }
            R.id.action_new_trusttunnel -> {
                startActivity(Intent(requireActivity(), TrustTunnelSettingsActivity::class.java))
            }
            R.id.action_new_snell -> {
                startActivity(Intent(requireActivity(), SnellSettingsActivity::class.java))
            }
            R.id.action_new_config -> {
                startActivity(Intent(requireActivity(), ConfigSettingsActivity::class.java))
            }
            R.id.action_new_chain -> {
                startActivity(Intent(requireActivity(), ChainSettingsActivity::class.java))
            }
            R.id.action_new_balancer -> {
                startActivity(Intent(requireActivity(), BalancerSettingsActivity::class.java))
            }
            R.id.action_selection_mode -> {
                // Enter selection mode from the toolbar. Nothing is checked on
                // entry — the user picks from a clean slate.
                if (select) return true
                val fragment = childFragmentManager.findFragmentByTag(
                    "f" + selectedGroup.id
                ) as? GroupFragment
                fragment?.enterSelectionMode()
            }
            R.id.action_clear_traffic_statistics -> {
                runOnDefaultDispatcher {
                    clearTraffic(SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId()))
                }
            }
            R.id.action_connection_test_clear_results -> {
                runOnDefaultDispatcher {
                    clearTestResults(SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId()))
                }
            }
            R.id.action_remove_duplicate -> {
                runOnDefaultDispatcher {
                    deduplicate(SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId()))
                }
            }
            R.id.action_connection_test_delete_unavailable -> {
                runOnDefaultDispatcher {
                    deleteUnavailable(SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId()))
                }
            }
            R.id.action_connection_url_test -> {
                urlTest()
            }
            R.id.action_annotate_geoip -> {
                annotateGeoip()
            }
            R.id.action_resolve_domains -> {
                resolveDomains()
            }
            R.id.action_speed_test -> {
                speedTest()
            }
            R.id.action_update_subscription -> {
                runOnDefaultDispatcher {
                    val currentGroup = DataStore.currentGroup()
                    if (SagerDatabase.sourceDao.countByGroup(currentGroup.id) > 0L) {
                        GroupUpdater.startUpdateAll(currentGroup.id, true)
                    } else {
                        snackbar(R.string.group_not_a_subscription).show()
                    }
                }
            }
        }
        return true
    }

    // --- Exclave Next: bulk operations over an explicit profile set ---------
    // Each of these was inlined in the menu handler before; they are extracted
    // so that the group-wide toolbar entries and the selection-mode entries
    // run the exact same code over different target sets. All four are
    // `suspend`-friendly: callers run them on the default dispatcher.

    /** Zero the traffic counters of the given profiles. */
    suspend fun clearTraffic(profiles: List<ProxyEntity>) {
        val toClear = profiles.filter { it.tx != 0L || it.rx != 0L }
        toClear.forEach {
            it.tx = 0
            it.rx = 0
        }
        if (toClear.isNotEmpty()) ProfileManager.updateProfile(toClear)
    }

    /** Reset the URL-test verdict of the given profiles. */
    suspend fun clearTestResults(profiles: List<ProxyEntity>) {
        val toClear = profiles.filter { it.status != 0 }
        toClear.forEach {
            it.status = 0
            it.ping = 0
            it.error = null
        }
        if (toClear.isNotEmpty()) ProfileManager.updateProfile(toClear)
    }

    /** Profiles that failed the URL test (status 3 / 2), i.e. everything the
     *  app does not consider alive or untested. Mirrors the original rule:
     *  status -1 (plugin missing), 0 (untested) and 1 (alive) are kept. */
    private fun unavailableIn(profiles: List<ProxyEntity>): List<ProxyEntity> =
        profiles.filter { it.status != -1 && it.status != 0 && it.status != 1 }

    /** Second and later occurrences of the same server within `profiles`. */
    private fun duplicatesIn(profiles: List<ProxyEntity>): List<ProxyEntity> {
        val seen = LinkedHashSet<Protocols.Deduplication>()
        val dup = mutableListOf<ProxyEntity>()
        for (p in profiles) {
            if (!seen.add(Protocols.Deduplication(p.requireBean(), p.displayType()))) {
                dup += p
            }
        }
        return dup
    }

    /** "Are you sure you want to remove these profiles?" + up to 20 names —
     *  the same cap the original group-wide deletes used. */
    private fun deleteConfirmMessage(profiles: List<ProxyEntity>): String {
        return getString(R.string.delete_multi_confirm_prompt) + "\n" +
                profiles.mapIndexedNotNull { index, profile ->
                    when {
                        index < 20 -> profile.displayName()
                        index == 20 -> "......"
                        else -> null
                    }
                }.joinToString("\n")
    }

    /** Confirm, then delete. The rows are pulled out of the visible list first
     *  so the UI updates immediately, and only then deleted in the database.
     *  `onConfirmed` runs when the user accepts — not before — so a cancelled
     *  dialog leaves the selection exactly as it was. */
    suspend fun deleteProfiles(
        profiles: List<ProxyEntity>,
        onConfirmed: (() -> Unit)? = null,
    ) {
        if (profiles.isEmpty()) return
        onMainDispatcher {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(deleteConfirmMessage(profiles))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    for (profile in profiles) {
                        adapter.groupFragments[DataStore.selectedGroup]?.adapter?.apply {
                            val index = configurationIdList.indexOf(profile.id)
                            if (index >= 0) {
                                configurationIdList.removeAt(index)
                                configurationList.remove(profile.id)
                                notifyItemRemoved(index)
                            }
                        }
                    }
                    onConfirmed?.invoke()
                    runOnDefaultDispatcher {
                        for (profile in profiles) {
                            ProfileManager.deleteProfile2(profile.groupId, profile.id)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    suspend fun deleteUnavailable(profiles: List<ProxyEntity>) {
        deleteProfiles(unavailableIn(profiles))
    }

    suspend fun deduplicate(profiles: List<ProxyEntity>) {
        deleteProfiles(duplicatesIn(profiles))
    }

    /** Copy the share links of the given profiles to the clipboard as one
     *  newline-separated block — the same shape RawUpdater.parseRaw accepts, so
     *  a copied selection can be pasted straight back in.
     *  Profiles that cannot be expressed as a link (custom config, chain,
     *  balancer, and the types without a share link) are skipped rather than
     *  silently producing a broken entry. */
    fun shareLinks(profiles: List<ProxyEntity>) {
        val links = mutableListOf<String>()
        for (profile in profiles) {
            val link = try {
                if (profile.wgBean != null) {
                    profile.wgBean?.toConf()
                } else if (profile.hasShareLink()) {
                    profile.toLink()
                } else {
                    null
                }
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            if (!link.isNullOrBlank()) links += link
        }
        if (links.isEmpty()) {
            snackbar(getString(R.string.action_export_err))
            return
        }
        val success = SagerNet.trySetPrimaryClip(links.joinToString("\n"))
        if (success) {
            snackbar(getString(R.string.selection_shared, links.size))
        } else {
            snackbar(getString(R.string.action_export_err))
        }
    }

    // Multi-round counter. Three different quantities are in play, so each is shown
    // with its own denominator instead of the old "56/122 (2/3)" which mixed them:
    //   alive  — profiles that answered with a LIVE ping, out of the whole group.
    //            A finished-but-dead profile is NOT counted here. Green, like a
    //            live ping in the list below.
    //   round  — attempts made in the current round, out of the profiles that round
    //            started with. Moves on every attempt, including retries.
    //   the last pair counts the dead ones, so it wears the red that "unavailable"
    //            wears in the list below (material_red_500).
    // "done" (final verdicts, alive or not) drives the progress bar only, since
    // that is what makes it monotonic; it is not shown as a number.
    private fun updateTestCounter(
        dialog: AlertDialog,
        alive: Int,
        total: Int,
        roundAttempt: Int,
        roundTotal: Int,
        round: Int,
        rounds: Int,
    ) {
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        val green = ForegroundColorSpan(
            requireContext().getColour(R.color.material_green_500)
        )
        val red = ForegroundColorSpan(
            requireContext().getColour(R.color.material_red_500)
        )
        val builder = SpannableStringBuilder()
        // Span over the numerator only: the denominators are group/round sizes,
        // not results, so they keep the default colour.
        builder.append("$alive").setSpan(green, 0, "$alive".length, SPAN)
        builder.append("/$total")
        if (rounds > 1) {
            builder.append(" • $round/$rounds • $roundAttempt/")
            // Only the DENOMINATOR is red: it is the number of profiles this round
            // still had to check, i.e. the ones that have not answered yet. The
            // attempts count next to it is progress, not a failure.
            val start = builder.length
            builder.append("$roundTotal")
            builder.setSpan(red, start, builder.length, SPAN)
        }
        neutral.text = builder
    }

    inner class TestDialog {
        val binding = LayoutProgressListBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                close()
                cancel()
            }
            .setNeutralButton(" ", null)
            .setCancelable(false)
        lateinit var cancel: () -> Unit
        val results = ArrayList<ProxyEntity>()
        val adapter = TestAdapter()
        val scrollTimer = Timer("insert timer")
        var currentTask: TimerTask? = null

        fun insert(profile: ProxyEntity) {
            binding.listView.post {
                results.add(profile)
                val index = results.size - 1
                adapter.notifyItemInserted(index)
                // Follow the test as it happens: scroll only if this row is
                // already near the viewport. Auto-scrolling on every insert
                // fights the user and, with several workers finishing at once,
                // drags the list to the bottom and back. The original app did
                // this unconditionally, which was fine when rows appeared one
                // at a time.
                if (isRowVisible(index)) {
                    scrollToPosition(index)
                }
            }
        }

        private fun isRowVisible(index: Int): Boolean {
            val lm = binding.listView.layoutManager as? LinearLayoutManager ?: return true
            if (index < 0) return false
            val first = lm.findFirstVisibleItemPosition()
            val last = lm.findLastVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return false
            // Keep a margin of a couple of rows around the viewport so the
            // insertion is visible, but don't yank the list across the screen.
            val margin = 2
            return index >= first - margin && index <= last + margin
        }

        private fun scrollToPosition(index: Int) {
            try {
                scrollTimer.schedule(timerTask {
                    binding.listView.post {
                        if (currentTask == this) binding.listView.smoothScrollToPosition(index)
                    }
                }.also {
                    currentTask?.cancel()
                    currentTask = it
                }, 500L)
            } catch (ignored: Exception) {
            }
        }

        fun update(profile: ProxyEntity) {
            binding.listView.post {
                // Keyed by id rather than indexOf(): value-equality works today only because
                // `results` holds the same mutated instances, so it is correct but
                // silent about it. An id lookup cannot drift if that ever changes.
                val index = results.indexOfLast { it.id == profile.id }
                if (index < 0) return@post
                adapter.notifyItemChanged(index)
            }
        }

        fun close() {
            try {
                scrollTimer.schedule(timerTask {
                    scrollTimer.cancel()
                }, 0)
            } catch (ignored: Exception) {
            }
        }

        init {
            binding.listView.layoutManager = FixedLinearLayoutManager(binding.listView)
            binding.listView.itemAnimator = DefaultItemAnimator()
            binding.listView.adapter = adapter
        }

        inner class TestAdapter : RecyclerView.Adapter<TestResultHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                TestResultHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))

            override fun onBindViewHolder(holder: TestResultHolder, position: Int) {
                holder.bind(results[position])
            }

            override fun getItemCount() = results.size
        }

        inner class TestResultHolder(val binding: LayoutProfileBinding) : RecyclerView.ViewHolder(
            binding.root
        ) {
            init {
                binding.edit.isGone = true
                binding.share.isGone = true
                binding.deleteIcon.isGone = true
            }

            fun bind(profile: ProxyEntity) {
                binding.profileName.text = profile.displayName()
                binding.profileType.text = profile.displayType()

                when (profile.status) {
                    -1 -> {
                        binding.profileStatus.text = profile.error
                        binding.profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    }
                    0 -> {
                        binding.profileStatus.setText(R.string.connection_test_testing)
                        binding.profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                    }
                    1 -> {
                        binding.profileStatus.text = getString(R.string.available, profile.ping)
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                    }
                    2 -> {
                        binding.profileStatus.text = profile.error
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                    3 -> {
                        binding.profileStatus.setText(R.string.unavailable)
                        binding.profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    }
                }

                if (profile.status == 3) {
                    binding.content.setOnClickListener {
                        alert(profile.error ?: "<?>").show()
                    }
                } else {
                    binding.content.setOnClickListener {}
                }
            }
        }

    }

    private fun ProxyEntity.useBrowserForwarder(): Boolean {
        return when (val bean = requireBean()) {
            is StandardV2RayBean -> {
                when (bean.type) {
                    "ws" -> bean.wsUseBrowserForwarder
                    "splithttp" -> bean.shUseBrowserForwarder
                    else -> false
                }
            }
            is ChainBean -> {
                SagerDatabase.proxyDao.getEntities(bean.proxies).any {
                    it.useBrowserForwarder()
                }
            }
            is BalancerBean -> {
                SagerDatabase.proxyDao.getEntities(bean.proxies).any {
                    it.useBrowserForwarder()
                }
            }
            else -> false
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    /** URL test.
     *  Without arguments it tests the whole current group (the toolbar entry
     *  point). With an explicit list it tests exactly those profiles — that is
     *  the selection-mode path, so a checked subset (or a search-filtered set)
     *  is what gets probed instead of the whole group. */
    fun urlTest(targets: List<ProxyEntity>? = null) {
        val test = TestDialog()
        val dialog = test.builder.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
        val testJobs = mutableListOf<Job>()

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            var profilesUnfiltered = targets ?: SagerDatabase.proxyDao.getByGroup(group.id)
            profilesUnfiltered = profilesUnfiltered.filter {
                !it.useBrowserForwarder()
            }

            val profileCount = profilesUnfiltered.size

            // Written from every worker at once — must be atomic.
            val finishedProfileCount = java.util.concurrent.atomic.AtomicInteger(0)

            // Profiles that answered with a LIVE ping (status == 1), counted
            // separately from "has a final verdict": a profile can be finished
            // and still dead. This is what the first counter number shows.
            val aliveProfileCount = java.util.concurrent.atomic.AtomicInteger(0)

            // Attempts made in the CURRENT round, and how many profiles that
            // round started with. Reset per round; the global counter above
            // keeps rising so the progress bar never jumps backwards.
            val roundDoneCount = java.util.concurrent.atomic.AtomicInteger(0)
            val link = DataStore.connectionTestURL
            val timeout = DataStore.connectionTestTimeout
            val rounds = DataStore.connectionTestRounds.coerceAtLeast(1)

            // Rows are NOT inserted here: TestDialog.insert() is called by each
            // worker when it actually picks a profile up (first round only), so
            // the list grows in step with the test instead of showing every row
            // as "testing" before a single one has been probed.

            // A profile is considered dead only if it fails EVERY round.
            // We keep retrying only the ones that haven't succeeded yet.
            var pending = ConcurrentLinkedQueue(profilesUnfiltered)

            for (round in 1..rounds) {
                if (pending.isEmpty()) break
                if (!isActive) break

                val roundList = pending.toList()
                val stillPending = ConcurrentLinkedQueue<ProxyEntity>()
                val queue = ConcurrentLinkedQueue(roundList)

                // Attempts made so far in this round, out of the ones it started with.
                roundDoneCount.set(0)
                val roundTotal = roundList.size

                // Which round the number on screen belongs to. A post queued on the
                // main dispatcher before this bump still describes the PREVIOUS
                // round, and rendering it would flash the fresh 0/N over the new
                // round's first results. Older posts drop out here instead.
                val currentRound = java.util.concurrent.atomic.AtomicInteger(round)
                onMainDispatcher {
                    updateTestCounter(
                        dialog, aliveProfileCount.get(), profileCount,
                        roundDoneCount.get(), roundTotal, round, rounds
                    )
                }

                testJobs.clear()

                // Diagnostics for the "thread count does nothing" report: track how
                // many probes are genuinely in flight, and when each one started, so
                // a run can be checked for real overlap instead of guessed at.
                val inFlight = java.util.concurrent.atomic.AtomicInteger(0)
                val peakInFlight = java.util.concurrent.atomic.AtomicInteger(0)
                val testStartMs = SystemClock.elapsedRealtime()
                val workerStarts = java.util.Collections.synchronizedList(
                    ArrayList<Long>(profilesUnfiltered.size)
                )

                repeat(DataStore.connectionTestConcurrency) { workerIndex ->
                    testJobs.add(launch {
                        while (isActive) {
                            val profile = queue.poll() ?: break
                            // Every attempt counts for the round counter, even when
                            // the profile gets re-queued — otherwise the round
                            // denominator would shrink as failures re-enter it.
                            // Only the increment is needed: the number shown is read
                            // at render time, not captured here. Capturing it here
                            // is what made the count jump backwards, because a slow
                            // probe's stale number would land after a fast one's.
                            roundDoneCount.incrementAndGet()
                            workerStarts.add(SystemClock.elapsedRealtime() - testStartMs)
                            val now = inFlight.incrementAndGet()
                            // Not getAndUpdate(): that's API 24, and the legacy
                            // flavor still targets minSdk 21.
                            synchronized(peakInFlight) {
                                if (now > peakInFlight.get()) peakInFlight.set(now)
                            }
                            // First round: add the row when the worker
                            // actually starts on it, so the list follows the
                            // test instead of being pre-filled. Later rounds
                            // reuse the row that already exists.
                            if (round == 1) {
                                profile.status = 0
                                test.insert(profile)
                            } else {
                                // On a retry round, mark the profile as testing again.
                                profile.status = 0
                                test.update(profile)
                            }

                            // "Will this be probed again?" — decided where the profile is re-queued.
                            // Same behaviour as the previous `profile !in stillPending`
                            // probe (which worked because the re-queue stores the same
                            // instance), but explicit rather than identity-dependent.
                            var requeued = false
                            try {
                                val instance = if (DataStore.tunImplementation == TunImplementation.SYSTEM && DataStore.serviceMode == Key.MODE_VPN && SagerNet.started && DataStore.startedProfile > 0) {
                                    V2RayTestInstance(profile, link, timeout, protectPath = SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path")
                                } else {
                                    V2RayTestInstance(profile, link, timeout)
                                }
                                val result = instance.use {
                                    it.doTest()
                                }
                                profile.status = 1
                                profile.ping = result
                                profile.error = null
                                // A live ping: counted once per profile, since a
                                // profile that succeeds is never re-queued.
                                aliveProfileCount.incrementAndGet()
                            } catch (e: PluginManager.PluginNotFoundException) {
                                // Plugin missing — retrying won't help, don't re-queue.
                                profile.status = -1
                                profile.error = e.readableMessage
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.readableMessage
                                // Only genuine failures (status 3) get another chance.
                                if (round < rounds) {
                                    stillPending.add(profile)
                                    requeued = true
                                }
                            }

                            // A profile counts as finished only when it will not be
                            // probed again: it succeeded, the plugin is missing, or
                            // it just failed its last round.
                            val isFinal = !requeued && (profile.status == 1 || profile.status == -1 || round >= rounds)
                            if (isFinal) {
                                val done = finishedProfileCount.incrementAndGet()
                                onMainDispatcher {
                                    // A post from an earlier round describes numbers
                                    // that are no longer on screen; drop it rather than
                                    // paint a stale count over the new round.
                                    if (currentRound.get() != round) return@onMainDispatcher
                                    test.binding.progressCircular.apply {
                                        isVisible = true
                                        // Shipped as android:indeterminate="true". Material does switch it to
                                        // determinate by itself, but only after the
                                        // running indeterminate cycle finishes
                                        // (~2s), and every setProgressCompat() call
                                        // re-arms that request. Flip it once, here,
                                        // so the bar tracks the count immediately.
                                        if (isIndeterminate) isIndeterminate = false
                                        setProgressCompat(
                                            ((done.toDouble() / profileCount.toDouble()) * 100).toInt(),
                                            true
                                        )
                                    }
                                    updateTestCounter(
                                        dialog, aliveProfileCount.get(), profileCount,
                                        roundDoneCount.get(), roundTotal, round, rounds
                                    )
                                }
                            } else {
                                // Progress inside the round still moves even though
                                // the global count does not — otherwise the counter
                                // looks frozen while profiles are being retried.
                                onMainDispatcher {
                                    if (currentRound.get() != round) return@onMainDispatcher
                                    updateTestCounter(
                                        dialog, aliveProfileCount.get(), profileCount,
                                        roundDoneCount.get(), roundTotal, round, rounds
                                    )
                                }
                            }

                            test.update(profile)
                            ProfileManager.updateProfile(profile)
                            inFlight.decrementAndGet()
                        }
                    })
                }

                testJobs.joinAll()
                pending = stillPending

                // Log real concurrency so "the thread setting does nothing" can be
                // confirmed or ruled out from a bug report alone. Starts are
                // compared in 250ms buckets: if N workers really overlapped,
                // several probes share a bucket instead of landing apart.
                val roundMs = SystemClock.elapsedRealtime() - testStartMs
                val buckets = workerStarts.groupingBy { it / 250L }.eachCount().toSortedMap()
                io.nekohasekai.sagernet.ktx.Logs.i(
                    "URLTEST round=$round/$rounds profiles=$profileCount " +
                        "workers=${DataStore.connectionTestConcurrency} " +
                        "probes=${workerStarts.size} " +
                        "peakInFlight=${peakInFlight.get()} " +
                        "elapsedMs=$roundMs starts250ms=$buckets"
                )
            }

            test.close()
            onMainDispatcher {
                test.binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
            }
        }
        test.cancel = {
            mainJob.cancel()
            runOnDefaultDispatcher {
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
    }

    // --- Exclave Next: GeoIP / ISP annotation of the current group ----------
    // Resolves each profile's server to an IP, looks up country + provider
    // (local MaxMind MMDB first, then the online API fallback), and writes the
    // "<name> 🇩🇪 Germany (Hetzner)" tag into the profile display name. No new
    // database columns — the annotation lives entirely inside bean.name.
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun annotateGeoip(targets: List<ProxyEntity>? = null) {
        val test = TestDialog()
        val dialog = test.builder.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false

        if (!io.nekohasekai.sagernet.bg.GeoIpAnnotator.isChainUsable()) {
            snackbar(getString(R.string.geoip_db_missing)).show()
        }

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            // Selection mode passes the checked profiles; the toolbar entry
            // point passes nothing and gets the whole group.
            val profiles = targets ?: SagerDatabase.proxyDao.getByGroup(group.id)
            val profileCount = profiles.size
            var finished = 0

            io.nekohasekai.sagernet.bg.GeoIpAnnotator.ensureInit()

            val queue = ConcurrentLinkedQueue(profiles)
            val jobs = mutableListOf<Job>()
            // DNS/GeoIP are IO-bound; a small worker pool keeps the UI responsive.
            repeat(DataStore.connectionTestConcurrency.coerceAtLeast(1)) {
                jobs.add(launch {
                    while (isActive) {
                        val profile = queue.poll() ?: break
                        profile.status = 0
                        test.insert(profile)
                        try {
                            val bean = profile.requireBean()
                            val host = bean.serverAddress ?: ""
                            val ip = io.nekohasekai.sagernet.bg.GeoIpAnnotator.resolveToIp(host)
                            if (ip != null) {
                                val info = io.nekohasekai.sagernet.bg.GeoIpAnnotator.lookup(ip)
                                val newName = io.nekohasekai.sagernet.bg.GeoIpAnnotator
                                    .annotateName(bean.name ?: "", info)
                                bean.name = newName
                                profile.putBean(bean)
                                profile.status = if (info.isKnown) 1 else 3
                                profile.error = if (info.isKnown) info.tag() else "Unknown"
                            } else {
                                profile.status = 3
                                profile.error = "DNS error"
                            }
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }
                        onMainDispatcher {
                            finished++
                            test.binding.progressCircular.apply {
                                isVisible = true
                                setProgressCompat(
                                    ((finished.toDouble() / profileCount.toDouble()) * 100).toInt(),
                                    true
                                )
                            }
                            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text =
                                "$finished/$profileCount"
                        }
                        test.update(profile)
                        ProfileManager.updateProfile(profile)
                    }
                })
            }
            jobs.joinAll()
            test.close()
            onMainDispatcher {
                test.binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
                snackbar(getString(R.string.annotate_done, profileCount)).show()
            }
        }
        test.cancel = {
            mainJob.cancel()
            runOnDefaultDispatcher {
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
    }

    // --- Exclave Next: resolve domain names in configs to IP ----------------
    // Replaces bean.serverAddress with the resolved IP where it holds a domain.
    // Purely a rewrite of the stored config: profiles from subscriptions keep
    // their sourceId, so the next subscription refresh may still reconcile
    // them by address+port+type and re-add the domain — the user asked for the
    // rewrite without worrying about that linkage.
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun resolveDomains(targets: List<ProxyEntity>? = null) {
        val test = TestDialog()
        val dialog = test.builder.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            // Selection mode passes the checked profiles; the toolbar entry
            // point passes nothing and gets the whole group.
            val profiles = targets ?: SagerDatabase.proxyDao.getByGroup(group.id)
            val profileCount = profiles.size
            var finished = 0
            var resolved = 0

            val queue = ConcurrentLinkedQueue(profiles)
            val jobs = mutableListOf<Job>()
            // DNS is IO-bound; reuse the connection-test worker count.
            repeat(DataStore.connectionTestConcurrency.coerceAtLeast(1)) {
                jobs.add(launch {
                    while (isActive) {
                        val profile = queue.poll() ?: break
                        profile.status = 0
                        test.insert(profile)
                        try {
                            val bean = profile.requireBean()
                            val host = bean.serverAddress ?: ""
                            if (host.isEmpty()) {
                                profile.status = 3
                                profile.error = "No server address"
                            } else {
                                val ip = io.nekohasekai.sagernet.bg.GeoIpAnnotator.resolveToIp(host)
                                if (ip == null) {
                                    profile.status = 3
                                    profile.error = "DNS error"
                                } else if (ip != host) {
                                    // A domain turned into an IP: write it back.
                                    bean.serverAddress = ip
                                    profile.putBean(bean)
                                    profile.status = 1
                                    profile.error = "$host → $ip"
                                    resolved++
                                } else {
                                    // Already an IP — nothing to do.
                                    profile.status = 1
                                    profile.error = null
                                }
                            }
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }
                        onMainDispatcher {
                            finished++
                            test.binding.progressCircular.apply {
                                isVisible = true
                                setProgressCompat(
                                    ((finished.toDouble() / profileCount.toDouble()) * 100).toInt(),
                                    true
                                )
                            }
                            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text =
                                "$finished/$profileCount"
                        }
                        test.update(profile)
                        ProfileManager.updateProfile(profile)
                    }
                })
            }
            jobs.joinAll()
            test.close()
            onMainDispatcher {
                test.binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
                snackbar(getString(R.string.resolve_done, resolved, profileCount)).show()
            }
        }
        test.cancel = {
            mainJob.cancel()
            runOnDefaultDispatcher {
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
    }

    // --- Exclave Next: speed test of the current group ----------------------
    // Starts each profile as a local SOCKS proxy (via the core) and measures
    // ping + download. Low concurrency by default so probes don't share the
    // link. The measured download speed is written into the profile ping field
    // used by the test dialog and appended to the display name.
    @Suppress("EXPERIMENTAL_API_USAGE")
    fun speedTest(targets: List<ProxyEntity>? = null) {
        val test = TestDialog()
        val dialog = test.builder.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false

        val mainJob = runOnDefaultDispatcher {
            val group = DataStore.currentGroup()
            // Everything in the group/selection is probed, including profiles a
            // previous round marked dead (🚩): they must get another chance on
            // every re-run instead of silently dropping out of the pool. Only the
            // browser-forwarder profiles are skipped (their transport can't be
            // driven through a local SOCKS inbound).
            val profiles = (targets ?: SagerDatabase.proxyDao.getByGroup(group.id)).filter {
                !it.useBrowserForwarder()
            }

            val profileCount = profiles.size
            var finished = 0

            val pingUrl = "http://cp.cloudflare.com/"
            // Configurable speed test parameters (Settings → Protocol), replacing
            // the former hard-coded values: one worker, 5 s, a fixed 10 MB URL.
            val timeout = DataStore.connectionTestTimeout
            val maxDuration = DataStore.speedTestTimeout.coerceAtLeast(1).toLong()
            val sizeMb = DataStore.speedDlSizeMb.coerceAtLeast(1)
            val downloadUrls = DataStore.speedDlTestUrls.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { url ->
                    url.replace("{size}", (sizeMb * 1024 * 1024).toString())
                        .replace("{size_mb}", sizeMb.toString())
                }
                .ifEmpty { listOf("https://speed.cloudflare.com/__down?bytes=${sizeMb * 1024 * 1024}") }

            val queue = ConcurrentLinkedQueue(profiles)
            val jobs = mutableListOf<Job>()
            // Single worker by default (parallel probes split the bandwidth and
            // skew results); SPEED_MAX_WORKERS raises it when the user accepts
            // the skew in exchange for throughput.
            val workers = DataStore.speedMaxWorkers.coerceAtLeast(1)
            repeat(workers) {
                jobs.add(launch {
                    while (isActive) {
                        val profile = queue.poll() ?: break
                        profile.status = 0
                        test.insert(profile)
                        try {
                            val result = io.nekohasekai.sagernet.bg.test.SpeedTestInstance(
                                profile, pingUrl, downloadUrls, timeout, maxDuration
                            ).use { it.doTest() }

                            if (result.alive) {
                                profile.status = 1
                                profile.ping = result.pingMs
                                profile.error = "${result.downloadMbps} Mb/s"
                                // Compose "<emoji> <name...> ↓<Mbps>" in one place, so
                                // both the marker and the value are replaced on every
                                // run and never stack. Re-running uses the canonical
                                // GeoIpAnnotator helpers instead of its own regex.
                                val marker = when {
                                    // Ping passed but no bytes moved: black flag, no value.
                                    result.downloadMbps <= 0.0 -> "🏴"
                                    result.downloadMbps >= 50 -> "✨"
                                    result.downloadMbps >= 25 -> "⭐️"
                                    result.downloadMbps >= 10 -> "🏁"
                                    else -> "🏳️"
                                }
                                val bean = profile.requireBean()
                                // Zero downloads are dropped by composeSpeedName
                                // itself — the rule lives in one place only.
                                bean.name = io.nekohasekai.sagernet.bg.GeoIpAnnotator
                                    .composeSpeedName(bean.name ?: "", marker, result.downloadMbps)
                                profile.putBean(bean)
                            } else {
                                profile.status = 3
                                profile.error = "Dead"
                                // Ping never passed: triangular flag, no number. 🚩
                                // (not the dead 🏴) so the profile stays distinct in the
                                // list and still participates in future test rounds.
                                val bean = profile.requireBean()
                                bean.name = io.nekohasekai.sagernet.bg.GeoIpAnnotator
                                    .composeSpeedName(bean.name ?: "", "🚩", null)
                                profile.putBean(bean)
                            }
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.error = e.readableMessage
                        }
                        onMainDispatcher {
                            finished++
                            test.binding.progressCircular.apply {
                                isVisible = true
                                setProgressCompat(
                                    ((finished.toDouble() / profileCount.toDouble()) * 100).toInt(),
                                    true
                                )
                            }
                            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text =
                                "$finished/$profileCount"
                        }
                        test.update(profile)
                        ProfileManager.updateProfile(profile)
                    }
                })
            }
            jobs.joinAll()
            test.close()
            onMainDispatcher {
                test.binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
                snackbar(getString(R.string.speed_test_done, profileCount)).show()
            }
        }
        test.cancel = {
            mainJob.cancel()
            runOnDefaultDispatcher {
                GroupManager.postReload(DataStore.currentGroupId())
            }
        }
    }

    inner class GroupPagerAdapter : FragmentStateAdapter(this),
        ProfileManager.Listener,
        GroupManager.Listener {

        var selectedGroupIndex = 0
        var groupList: ArrayList<ProxyGroup> = ArrayList()
        var groupFragments: HashMap<Long, GroupFragment> = HashMap()

        fun reload(now: Boolean = false) {

            if (!select) {
                groupPager.unregisterOnPageChangeCallback(updateSelectedCallback)
            }

            runOnDefaultDispatcher {
                var newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                if (newGroupList.isEmpty()) {
                    SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true))
                    newGroupList = ArrayList(SagerDatabase.groupDao.allGroups())
                }
                newGroupList.find { it.ungrouped }?.let {
                    if (SagerDatabase.proxyDao.countByGroup(it.id) == 0L) {
                        newGroupList.remove(it)
                    }
                }

                var selectedGroup = selectedItem?.groupId ?: DataStore.currentGroupId()
                var set = false
                if (selectedGroup > 0L) {
                    selectedGroupIndex = newGroupList.indexOfFirst { it.id == selectedGroup }
                    set = true
                } else if (groupList.size == 1) {
                    selectedGroup = groupList[0].id
                    if (DataStore.selectedGroup != selectedGroup) {
                        DataStore.selectedGroup = selectedGroup
                    }
                }

                try {
                    requireActivity()
                } catch (e: Exception) {
                    Logs.w(e)
                    return@runOnDefaultDispatcher
                }
                val runFunc = if (now) requireActivity()::runOnUiThread else groupPager::post
                runFunc {
                    groupList = newGroupList
                    notifyDataSetChanged()
                    if (set) groupPager.setCurrentItem(selectedGroupIndex, false)
                    val hideTab = groupList.size < 2
                    tabLayout.isGone = hideTab
                    toolbar.elevation = if (hideTab) 0F else dp2px(4).toFloat()
                    if (!select) {
                        groupPager.registerOnPageChangeCallback(updateSelectedCallback)
                    }
                }
            }
        }

        init {
            reload(true)
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun createFragment(position: Int): Fragment {
            return GroupFragment().apply {
                proxyGroup = groupList[position]
                groupFragments[proxyGroup.id] = this
                if (position == selectedGroupIndex) {
                    selected = true
                }
            }
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        override fun containsItem(itemId: Long): Boolean {
            return groupList.any { it.id == itemId }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            tabLayout.post {
                groupList.add(group)

                if (groupList.any { !it.ungrouped }) tabLayout.post {
                    tabLayout.visibility = View.VISIBLE
                }

                notifyItemInserted(groupList.size - 1)
                tabLayout.getTabAt(groupList.size - 1)?.select()
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            tabLayout.post {
                val index = groupList.indexOfFirst { it.id == groupId }
                if (index == -1) return@post
                groupList.removeAt(index)
                notifyItemRemoved(index)
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) return

            tabLayout.post {
                tabLayout.getTabAt(index)?.text = group.displayName()
            }
        }

        override suspend fun groupUpdated(groupId: Long) = Unit

        override suspend fun onAdd(profile: ProxyEntity) {
            if (groupList.find { it.id == profile.groupId } == null) {
                DataStore.selectedGroup = profile.groupId
                reload()
            }
        }

        override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) = Unit

        override suspend fun onUpdated(profile: ProxyEntity) = Unit

        override suspend fun onRemoved(groupId: Long, profileId: Long) {
            val group = groupList.find { it.id == groupId } ?: return
            if (group.ungrouped && SagerDatabase.proxyDao.countByGroup(groupId) == 0L) {
                reload()
            }
        }
    }

    class GroupFragment : Fragment() {

        lateinit var proxyGroup: ProxyGroup
        var selected = false
        var scrolled = false
        val showBackup = DataStore.experimentalFlagsProperties.getBooleanProperty("enableProfileBackup")

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
        ): View? {
            return LayoutProfileListBinding.inflate(inflater).root
        }

        lateinit var undoManager: UndoSnackbarManager<ProxyEntity>
        lateinit var adapter: ConfigurationAdapter

        override fun onSaveInstanceState(outState: Bundle) {
            super.onSaveInstanceState(outState)

            if (::proxyGroup.isInitialized) {
                outState.putParcelable("proxyGroup", proxyGroup)
            }
            if (::layoutManager.isInitialized) {
                outState.putInt("scrollPosition", layoutManager.findFirstVisibleItemPosition())
            }
        }

        override fun onViewStateRestored(savedInstanceState: Bundle?) {
            super.onViewStateRestored(savedInstanceState)

            savedInstanceState?.getParcelable<ProxyGroup>("proxyGroup")?.also {
                proxyGroup = it
                onViewCreated(requireView(), null)
            }
            savedInstanceState?.getInt("scrollPosition", -1)?.takeIf { it >= 0 }?.let { pos ->
                scrolled = true
                configurationListView.scrollToPosition(pos)
            }
        }

        private val isEnabled: Boolean
            get() {
                return ((activity as? MainActivity)
                    ?: return false).state.let { it.canStop || it == BaseService.State.Stopped }
            }

        private var actionButtonPressed = false

        private fun isProfileEditable(id: Long): Boolean {
            return ((activity as? MainActivity)
                ?: return false).state == BaseService.State.Stopped || id != DataStore.selectedProxy
        }

        lateinit var layoutManager: LinearLayoutManager
        lateinit var configurationListView: RecyclerView

        // --- Exclave Next: contextual selection bar --------------------------
        // Deliberately NOT in the toolbar: while the SearchView is expanded it
        // takes over the whole toolbar, so any selection actions placed there
        // would disappear exactly when the user is filtering. This bar is part
        // of the list fragment and stays reachable during a search.
        private var selectionBar: View? = null
        private var selectionCount: TextView? = null
        private var selectionAllButton: View? = null
        private var selectionMoreButton: View? = null
        private var selectionCloseButton: View? = null
        private var selectionEnterButton: View? = null

        /** True while the SearchView has a non-empty query. The bar stays
         *  available in that state, because an expanded SearchView owns the
         *  whole toolbar and the toolbar entry point is unreachable — this is
         *  the only way to reach the bulk actions on a filtered list. */
        private var filterActive = false

        /** Called by the parent whenever the search query changes. */
        fun onSearchFilterChanged(query: String) {
            filterActive = query.isNotEmpty()
            syncBackCallback()
            updateSelectionBar()
        }

        /** Arms this screen's single Back callback while it has something to do:
         *  an open search field, or selection mode. Called from the one place
         *  selection mode changes, from the search focus changes, and after
         *  Back has consumed a press. Both inputs are read from the live state
         *  (searchFieldOpen / adapter.isSelectionMode), never from a flag this
         *  screen has to remember. */
        fun syncBackCallback() {
            val searchOpen = (parent as? ConfigurationFragment)?.searchFieldOpen ?: false
            (parent as? ConfigurationFragment)?.onBackPressedCallback?.isEnabled =
                    isSelectionMode() || searchOpen
        }

        /** Entry point into selection mode, from the toolbar or the bar.
         *  setSelectionMode() syncs the Back callback itself. */
        fun enterSelectionMode() {
            if (!::adapter.isInitialized) return
            adapter.startSelectionMode()
            updateSelectionBar()
        }

        fun updateSelectionBar() {
            if (!::adapter.isInitialized) return
            val bar = selectionBar ?: return
            val checked = adapter.checkedCount()
            val visible = adapter.configurationIdList.size
            // Shown while the mode is on, or while a filter is narrowing the
            // list. Both cases mean there is a set of profiles the user can act
            // on right now — driven by the explicit mode flag, not by whether
            // anything happens to be checked.
            val inMode = adapter.isSelectionMode
            val show = inMode || filterActive
            bar.isVisible = show
            if (!show) return

            selectionCount?.text = if (checked > 0) {
                resources.getQuantityString(R.plurals.selected_count, checked, checked)
            } else {
                // Nothing picked yet (mode just opened, or a filter is on):
                // show how many rows are on screen so the bar is not blank.
                getString(R.string.selection_matched, visible)
            }
            // The close button exits the mode, so it belongs whenever the mode
            // is on — including before anything is checked.
            selectionCloseButton?.isVisible = inMode
            // The enter button is only needed while the mode is OFF; once the
            // checkboxes are up it would just be a duplicate way in.
            selectionEnterButton?.isVisible = !inMode
            val allVisible = visible > 0 && checked == visible
            selectionAllButton?.contentDescription = getString(
                if (allVisible) R.string.action_select_none else R.string.action_select_all
            )
        }

        /** Leaves selection mode entirely — the only paths here are the close
         *  button and Back. Unchecking everything is NOT an exit; it is the
         *  select-all button, and the mode stays up.
         *  setSelectionMode() syncs the Back callback itself. */
        fun exitSelectionMode() {
            if (!::adapter.isInitialized) return
            adapter.setSelectionMode(false)
            updateSelectionBar()
        }

        fun isSelectionMode(): Boolean =
            ::adapter.isInitialized && adapter.isSelectionMode

        private val selectionMenuListener = object : PopupMenu.OnMenuItemClickListener {
            override fun onMenuItemClick(item: MenuItem): Boolean {
                if (!::adapter.isInitialized) return true
                when (item.itemId) {
                    R.id.action_select_all -> adapter.selectAll()
                    R.id.action_select_none -> adapter.clearChecked()
                    // Every action below takes the checked profiles explicitly,
                    // so a selection (or a search-filtered set) is operated on
                    // instead of the whole group.
                    R.id.action_selection_url_test ->
                        withSelection { runOnDefaultDispatcher { requirePrent().urlTest(it) } }
                    R.id.action_selection_annotate_geoip ->
                        withSelection { requirePrent().annotateGeoip(it) }
                    R.id.action_selection_resolve_domains ->
                        withSelection { requirePrent().resolveDomains(it) }
                    R.id.action_selection_speed_test ->
                        withSelection { requirePrent().speedTest(it) }
                    R.id.action_selection_clear_test_results ->
                        withSelection { runOnDefaultDispatcher { requirePrent().clearTestResults(it) } }
                    R.id.action_selection_clear_traffic ->
                        withSelection { runOnDefaultDispatcher { requirePrent().clearTraffic(it) } }
                    R.id.action_selection_delete_unavailable ->
                        withSelection { runOnDefaultDispatcher { requirePrent().deleteUnavailable(it) } }
                    R.id.action_selection_deduplicate ->
                        withSelection { runOnDefaultDispatcher { requirePrent().deduplicate(it) } }
                    R.id.action_selection_share -> withSelection { requirePrent().shareLinks(it) }
                    R.id.action_selection_delete -> deleteChecked()
                }
                return true
            }
        }

        /** Runs a bulk operation against the checked profiles. The bar is only
         *  reachable in selection mode, so an empty set means "the visible
         *  (filtered) list" — matching what the user is looking at. */
        private inline fun withSelection(crossinline block: (List<ProxyEntity>) -> Unit) {
            val targets = adapter.selectedProfiles()
            if (targets.isEmpty()) return
            block(targets)
        }

        private fun showSelectionMenu(anchor: View) {
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.profile_selection_menu, popup.menu)
            val checked = if (::adapter.isInitialized) adapter.checkedCount() else 0
            popup.menu.findItem(R.id.action_select_none).isVisible = checked > 0
            popup.setOnMenuItemClickListener(selectionMenuListener)
            popup.show()
        }

        private fun deleteChecked() {
            val targets = adapter.selectedProfiles()
            if (targets.isEmpty()) return
            // Reuse the outer fragment's confirm-and-delete so selection mode
            // and the group-wide deletes behave identically (same dialog, same
            // list mutation, same deletion path). The selection is only dropped
            // once the user actually confirms.
            runOnDefaultDispatcher {
                requirePrent().deleteProfiles(targets) {
                    adapter.clearChecked()
                    updateSelectionBar()
                }
            }
        }

        val parent get() = parentFragment as? ConfigurationFragment
        fun requirePrent() = requireParentFragment() as ConfigurationFragment

        override fun onResume() {
            super.onResume()

            if (::adapter.isInitialized) {
                if (adapter.itemCount == 0) {
                    runOnDefaultDispatcher { adapter.reloadProfiles() }
                }
            } else {
                onViewCreated(requireView(), null)
            }
            checkOrderMenu()

            if (showBackup) {
                (parentFragment as? ToolbarFragment)
                    ?.toolbar?.menu?.findItem(R.id.action_import_backup)?.isVisible = true
            }

            if (::configurationListView.isInitialized) {
                configurationListView.requestFocus()
            }
        }

        fun checkOrderMenu() {
            if (parent?.select == true) return

            val pf = parentFragment as? ToolbarFragment ?: return
            val menu = pf.toolbar.menu
            val origin = menu.findItem(R.id.action_order_origin)
            val byName = menu.findItem(R.id.action_order_by_name)
            val byDelay = menu.findItem(R.id.action_order_by_delay)
            when (proxyGroup.order) {
                GroupOrder.ORIGIN -> {
                    origin.isChecked = true
                }
                GroupOrder.BY_NAME -> {
                    byName.isChecked = true
                }
                GroupOrder.BY_DELAY -> {
                    byDelay.isChecked = true
                }
            }

            fun updateTo(order: Int) {
                if (proxyGroup.order == order) return
                runOnDefaultDispatcher {
                    proxyGroup.order = order
                    GroupManager.updateGroup(proxyGroup, reconfigureUpdater = false)
                }
            }

            origin.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.ORIGIN)
                true
            }
            byName.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_NAME)
                true
            }
            byDelay.setOnMenuItemClickListener {
                it.isChecked = true
                updateTo(GroupOrder.BY_DELAY)
                true
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            val parent = parent ?: return
            if (!::proxyGroup.isInitialized) return

            configurationListView = view.findViewById(R.id.configuration_list)
            selectionBar = view.findViewById(R.id.selection_bar)
            selectionCount = view.findViewById(R.id.selection_count)
            selectionAllButton = view.findViewById(R.id.selection_select_all)
            selectionMoreButton = view.findViewById(R.id.selection_more)

            selectionCloseButton = view.findViewById(R.id.selection_close)
            selectionEnterButton = view.findViewById(R.id.selection_enter)

            selectionCloseButton?.setOnClickListener {
                exitSelectionMode()
            }
            // Second way into selection mode, reachable while the search field
            // owns the toolbar. Checks nothing — the user picks from a clean slate.
            selectionEnterButton?.setOnClickListener {
                enterSelectionMode()
            }
            selectionAllButton?.setOnClickListener {
                if (!::adapter.isInitialized) return@setOnClickListener
                // Toggles between "check everything visible" and "uncheck
                // everything". The second case does NOT leave selection mode —
                // only the close button and Back do that.
                if (adapter.checkedCount() == adapter.configurationIdList.size) {
                    adapter.clearChecked()
                } else {
                    adapter.selectAll()
                }
            }
            selectionMoreButton?.setOnClickListener {
                showSelectionMenu(it)
            }
            updateSelectionBar()

            ViewCompat.setOnApplyWindowInsetsListener(configurationListView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    left = bars.left + dp2px(4),
                    right = bars.right + dp2px(4),
                    bottom = bars.bottom + dp2px(64),
                )
                insets
            }
            layoutManager = FixedLinearLayoutManager(configurationListView)
            configurationListView.layoutManager = layoutManager
            adapter = ConfigurationAdapter()
            ProfileManager.addListener(adapter)
            GroupManager.addListener(adapter)
            configurationListView.adapter = adapter
            configurationListView.setItemViewCacheSize(20)

            if (!parent.select) {
                undoManager = UndoSnackbarManager(activity as MainActivity, adapter)
            }

            if (!parent.select && proxyGroup.type == GroupType.BASIC) {
                ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
                ) {
                    override fun getSwipeDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ): Int {
                        return 0
                    }

                    override fun getDragDirs(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) = if (isEnabled && !actionButtonPressed && !adapter.isSelectionMode) super.getDragDirs(
                        recyclerView, viewHolder
                    ) else 0

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    }

                    override fun onMove(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
                    ): Boolean {
                        adapter.move(
                            viewHolder.adapterPosition, target.adapterPosition
                        )
                        return true
                    }

                    override fun clearView(
                        recyclerView: RecyclerView,
                        viewHolder: RecyclerView.ViewHolder,
                    ) {
                        super.clearView(recyclerView, viewHolder)
                        adapter.commitMove()
                    }
                }).attachToRecyclerView(configurationListView)

            }

        }

        override fun onDestroy() {
            if (::adapter.isInitialized) {
                ProfileManager.removeListener(adapter)
                GroupManager.removeListener(adapter)
            }

            super.onDestroy()

            if (!::undoManager.isInitialized) return
            undoManager.flush()
        }

        inner class ConfigurationAdapter : RecyclerView.Adapter<ConfigurationHolder>(),
            ProfileManager.Listener,
            GroupManager.Listener,
            UndoSnackbarManager.Interface<ProxyEntity> {

            init {
                setHasStableIds(true)
            }

            var configurationIdList: MutableList<Long> = mutableListOf()
            val configurationList = HashMap<Long, ProxyEntity>()
            val pendingDeletedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

            private fun getItem(profileId: Long): ProxyEntity? {
                var profile = configurationList[profileId]
                if (profile == null) {
                    profile = ProfileManager.getProfile(profileId)
                    if (profile != null) {
                        configurationList[profileId] = profile
                    }
                }
                return profile
            }

            private fun getItemAt(index: Int) = getItem(configurationIdList[index])

            override fun onCreateViewHolder(
                parent: ViewGroup,
                viewType: Int,
            ): ConfigurationHolder {
                return ConfigurationHolder(
                    LayoutInflater.from(parent.context)
                        .inflate(R.layout.layout_profile, parent, false)
                )
            }

            override fun getItemId(position: Int): Long {
                return configurationIdList[position]
            }

            override fun onBindViewHolder(holder: ConfigurationHolder, position: Int) {
                try {
                    getItemAt(position)?.let {
                        holder.bind(it)
                    }
                } catch (ignored: NullPointerException) { // when group deleted
                }
            }

            override fun getItemCount(): Int {
                return configurationIdList.size
            }
            var activeSelectionId: Long = -1

            // --- Exclave Next: multi-select -----------------------------------
            // Ids of the checked profiles. Kept as ids rather than positions so
            // it survives filtering, reordering and list reloads. `filter()` below
            // narrows `configurationIdList` to the search hits, and every
            // "select all" here works off that list — so with an active search
            // only the visible hits are selected, which is what the user sees.
            val checkedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

            // Selection mode is an explicit state, NOT a function of the checked
            // set. Unchecking everything must leave the mode standing (the user
            // keeps choosing), so the mode can only be left deliberately: the
            // close button, Back, or switching away. Deriving it from
            // `checkedIds.isNotEmpty()` — as this did before — made the two
            // inseparable and forced a first row to be pre-checked on entry.
            private var selectionModeOn = false

            val isSelectionMode: Boolean get() = selectionModeOn

            /** Turns the mode on (showing checkboxes on every row) or off
             *  (restoring the normal tap-to-activate rows and hiding the bar).
             *  Turning it on checks nothing: the user starts from a clean slate.
             *
             *  The Back callback is synced HERE, not at the call sites. There
             *  are three ways into the mode — the toolbar item, the bar's enter
             *  button, and selectAll() turning it on implicitly — and arming at
             *  each of them is how Back ended up dead on one of them. Mode state
             *  and the Back callback are one concern, so they change together
             *  in the one place the mode actually changes. */
            fun setSelectionMode(enabled: Boolean) {
                if (selectionModeOn == enabled) return
                selectionModeOn = enabled
                if (!enabled) checkedIds.clear()
                // Full rebind: the mode decides both the checkbox visibility and
                // which click listener each row gets, and that listener is
                // installed in ConfigurationHolder.bind().
                notifyDataSetChanged()
                runOnMainDispatcher {
                    syncBackCallback()
                    updateSelectionBar()
                }
            }

            /** Drops checks that are no longer on screen.
             *  Needed because the selection is scoped to what the list shows:
             *  `filter()` narrows `configurationIdList` to the search hits, so a
             *  check on a row that the filter hides would otherwise keep
             *  selection mode alive with no way to clear it from the bar. */
            private fun pruneChecked() {
                if (checkedIds.isEmpty()) return
                val visible = configurationIdList.toSet()
                checkedIds.retainAll { it in visible }
            }

            /** The profiles an operation should act on: the checked ones if any,
             *  otherwise every profile currently visible in the list. */
            fun selectedProfiles(): List<ProxyEntity> {
                val visible = configurationIdList.mapNotNull { getItem(it) }
                if (checkedIds.isEmpty()) return visible
                return visible.filter { it.id in checkedIds }
            }

            fun checkedCount(): Int = configurationIdList.count { it in checkedIds }

            fun isChecked(id: Long): Boolean = id in checkedIds

            fun toggleChecked(id: Long) {
                if (!checkedIds.remove(id)) checkedIds.add(id)
                val index = configurationIdList.indexOf(id)
                if (index != -1) notifyItemChanged(index, "PAYLOAD_CHECK_CHANGE")
                runOnMainDispatcher { updateSelectionBar() }
            }

            /** Enter selection mode with nothing checked. Used by the toolbar button:
             *  the user picks from a clean slate rather than having a row they
             *  never touched silently pre-selected. */
            fun startSelectionMode() {
                setSelectionMode(true)
            }

            /** Check every currently *visible* profile — i.e. the search hits
             *  while a filter is active, the whole group otherwise. */
            fun selectAll() {
                // Checking rows implies the mode: the checkboxes have to appear,
                // otherwise the user would see "N selected" with nothing to look
                // at and no way to undo it row by row. This is reachable from the
                // bar during a search, where the mode is otherwise still off.
                if (!selectionModeOn) setSelectionMode(true)
                val visible = configurationIdList.toList()
                visible.forEach { checkedIds.add(it) }
                // Full rebind, not a payload: entering selection mode also has
                // to swap each row's click listener over to the toggle handler,
                // and that listener is installed in ConfigurationHolder.bind().
                notifyDataSetChanged()
                runOnMainDispatcher { updateSelectionBar() }
            }

            /** Unchecks everything but STAYS in selection mode — the user keeps picking.
             *  Only setSelectionMode(false) (close button / Back) leaves the mode. */
            fun clearChecked() {
                if (checkedIds.isEmpty()) return
                checkedIds.clear()
                // Full rebind again: rows must get their normal
                // "make active" click listener back and hide the checkbox.
                notifyDataSetChanged()
                runOnMainDispatcher { updateSelectionBar() }
            }

            fun refreshSelection() {
                val newId = DataStore.selectedProxy
                if (activeSelectionId == newId) return
                val oldId = activeSelectionId
                activeSelectionId = newId

                listOf(oldId, newId).forEach { id ->
                    val index = configurationIdList.indexOf(id)
                    if (index != -1) notifyItemChanged(index, "PAYLOAD_SELECTION_CHANGE")
                }
            }

            override fun onBindViewHolder(
                holder: ConfigurationHolder,
                position: Int,
                payloads: MutableList<Any>
            ) {
                if (payloads.contains("PAYLOAD_CHECK_CHANGE")) {
                    holder.bindCheckState()
                } else if (payloads.contains("PAYLOAD_SELECTION_CHANGE")) {
                    val entityId = configurationIdList[position]

                    val isSelected = (entityId == activeSelectionId)
                    val isStarted = isSelected && SagerNet.started && DataStore.startedProfile == entityId

                    holder.setActiveHighlight(isSelected)
                    holder.deleteButton.isEnabled = !isStarted
                } else {
                    super.onBindViewHolder(holder, position, payloads)
                }
            }

            private val updated = HashSet<ProxyEntity>()

            fun filter(name: String) {
                if (name.isEmpty()) {
                    reloadProfiles()
                    return
                }
                configurationIdList.clear()
                val lower = name.lowercase()
                configurationIdList.addAll(configurationList.filter {
                    it.value.displayName().lowercase().contains(lower) ||
                            it.value.displayType().lowercase().contains(lower) ||
                            it.value.displayAddress().lowercase().contains(lower)
                }.keys)
                // Keep selection tied to what is on screen: rows the filter
                // hides are dropped from the selection, so the bar count and
                // the checkboxes never disagree.
                pruneChecked()
                notifyDataSetChanged()
            }

            fun move(from: Int, to: Int) {
                val first = getItemAt(from) ?: return
                var previousOrder = first.userOrder
                val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                    -1, to + 1 downTo from
                )
                for (i in range) {
                    val next = getItemAt(i + step) ?: return
                    val order = next.userOrder
                    next.userOrder = previousOrder
                    previousOrder = order
                    configurationIdList[i] = next.id
                    updated.add(next)
                }
                first.userOrder = previousOrder
                configurationIdList[to] = first.id
                updated.add(first)
                notifyItemMoved(from, to)
            }

            fun commitMove() = runOnDefaultDispatcher {
                updated.forEach { SagerDatabase.proxyDao.updateProxy(it) }
                updated.clear()
            }

            fun remove(pos: Int) {
                if (pos < 0) return
                configurationIdList.removeAt(pos)
                notifyItemRemoved(pos)
            }

            override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
                for ((index, item) in actions) {
                    pendingDeletedIds.remove(item.id)
                    configurationListView.post {
                        if (!configurationIdList.contains(item.id)) {
                            configurationList[item.id] = item
                            val safeIndex = index.coerceIn(0, configurationIdList.size)
                            configurationIdList.add(safeIndex, item.id)
                            notifyItemInserted(safeIndex)
                        }
                    }
                }
            }

            override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
                val profiles = actions.map { it.second }
                runOnDefaultDispatcher {
                    for (entity in profiles) {
                        ProfileManager.deleteProfile(entity.groupId, entity.id)
                    }
                }
            }

            override suspend fun onAdd(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return

                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    val pos = itemCount
                    configurationList[profile.id] = profile
                    configurationIdList.add(profile.id)
                    notifyItemInserted(pos)
                }
            }

            override suspend fun onUpdated(profile: ProxyEntity) {
                if (profile.groupId != proxyGroup.id) return
                val index = configurationIdList.indexOf(profile.id)
                if (index < 0) return
                configurationListView.post {
                    if (::undoManager.isInitialized) {
                        undoManager.flush()
                    }
                    configurationList[profile.id] = profile
                    notifyItemChanged(index)
                }
            }

            override suspend fun onUpdated(profileId: Long, trafficStats: TrafficStats) {
                val index = configurationIdList.indexOf(profileId)
                if (index != -1) {
                    val profile = configurationList[profileId]
                    if (profile != null) {
                        profile.stats = trafficStats
                        onMainDispatcher {
                            val holder = configurationListView.findViewHolderForAdapterPosition(index) as? ConfigurationHolder
                            if (holder != null && holder.entity.id == profileId) {
                                holder.updateTraffic(profile)
                            }
                        }
                    }
                }
            }

            override suspend fun onRemoved(groupId: Long, profileId: Long) {
                if (groupId != proxyGroup.id) return

                pendingDeletedIds.remove(profileId)
                configurationListView.post {
                    val index = configurationIdList.indexOf(profileId)
                    if (index >= 0) {
                        configurationIdList.removeAt(index)
                        configurationList.remove(profileId)
                        notifyItemRemoved(index)
                    }
                }
            }

            override suspend fun groupAdd(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit

            override suspend fun groupUpdated(group: ProxyGroup) {
                if (group.id != proxyGroup.id) return
                proxyGroup = group
                reloadProfiles()
            }

            override suspend fun groupUpdated(groupId: Long) {
                if (groupId != proxyGroup.id) return
                proxyGroup = SagerDatabase.groupDao.getById(groupId)!!
                reloadProfiles()
            }

            fun reloadProfiles() {
                val selectedItem = try {
                    requirePrent().selectedItem
                } catch (ignored: IllegalStateException) {
                    return
                }

                activeSelectionId = selectedItem?.id ?: DataStore.selectedProxy

                var newProfiles = SagerDatabase.proxyDao.getByGroup(proxyGroup.id)
                newProfiles = newProfiles.filter { it.id !in pendingDeletedIds }
                when (proxyGroup.order) {
                    GroupOrder.BY_NAME -> {
                        newProfiles = newProfiles.sortedBy { it.displayName() }

                    }
                    GroupOrder.BY_DELAY -> {
                        newProfiles = newProfiles.sortedBy { if (it.status == 1) it.ping else 114514 }
                    }
                }

                configurationList.clear()
                configurationList.putAll(newProfiles.associateBy { it.id })
                val newProfileIds = newProfiles.map { it.id }

                var selectedProfileIndex = -1

                if (selected) {
                    val selectedProxy = selectedItem?.id ?: DataStore.selectedProxy
                    selectedProfileIndex = newProfileIds.indexOf(selectedProxy)
                }

                configurationListView.post {
                    configurationIdList.clear()
                    configurationIdList.addAll(newProfileIds)
                    pruneChecked()
                    notifyDataSetChanged()

                    if (selectedProfileIndex != -1 && !scrolled) {
                        layoutManager.scrollToPositionWithOffset(selectedProfileIndex, 0)
                        scrolled = true
                    } else if (newProfiles.isNotEmpty() && !scrolled) {
                        layoutManager.scrollToPositionWithOffset(0, 0)
                        scrolled = true
                    }

                }
            }

        }

        val profileAccess = Mutex()
        val reloadAccess = Mutex()

        inner class ConfigurationHolder(val view: View) : RecyclerView.ViewHolder(view),
            PopupMenu.OnMenuItemClickListener {

            lateinit var entity: ProxyEntity

            val profileName: TextView = view.findViewById(R.id.profile_name)
            val profileType: TextView = view.findViewById(R.id.profile_type)
            val profileAddress: TextView = view.findViewById(R.id.profile_address)
            val profileStatus: TextView = view.findViewById(R.id.profile_status)

            val trafficText: TextView = view.findViewById(R.id.traffic_text)
            val card: MaterialCardView = view as MaterialCardView
            val editButton: ImageView = view.findViewById(R.id.edit)
            val shareLayout: LinearLayout = view.findViewById(R.id.share)
            val shareLayer: LinearLayout = view.findViewById(R.id.share_layer)
            val shareButton: ImageView = view.findViewById(R.id.shareIcon)
            val deleteButton: ImageView = view.findViewById(R.id.deleteIcon)
            val selectionBox: androidx.appcompat.widget.AppCompatCheckBox =
                view.findViewById(R.id.selection_box)

            /** Checkbox + row highlight for the multi-select state. Split out so
             *  it can be rebound from a payload without a full re-bind. */
            fun bindCheckState() {
                if (!::entity.isInitialized) return
                val inSelection = parent?.let { !it.select && adapter.isSelectionMode } ?: false
                selectionBox.isVisible = inSelection
                if (inSelection) {
                    selectionBox.isChecked = adapter.isChecked(entity.id)
                }
            }

            /** Active-profile highlight: a thin stroke around the card instead
             *  of the old 4dp left stripe (2dp now, and it wraps the whole row).
             *  Same theme colour the stripe used, so light/dark themes keep
             *  working unchanged. */
            fun setActiveHighlight(active: Boolean) {
                if (active) {
                    card.setStrokeColor(view.context.getColorAttr(R.attr.selectedColorPrimary))
                    card.strokeWidth = dp2px(2)
                } else {
                    card.strokeWidth = 0
                }
            }

            fun bind(proxyEntity: ProxyEntity) {
                val parent = parent ?: return

                entity = proxyEntity

                if (parent.select) {
                    view.setOnClickListener {
                        (requireActivity() as SelectCallback).returnProfile(proxyEntity.id)
                    }
                } else if (adapter.isSelectionMode) {
                    // Selection mode: a tap checks/unchecks the row instead of
                    // switching the active profile. Long-press is untouched —
                    // it still drags the row.
                    view.setOnClickListener {
                        adapter.toggleChecked(proxyEntity.id)
                    }
                } else {
                    val pa = activity as MainActivity

                    view.setOnClickListener {
                        runOnDefaultDispatcher {
                            var update: Boolean
                            profileAccess.withLock {
                                update = DataStore.selectedProxy != proxyEntity.id
                                DataStore.selectedProxy = proxyEntity.id
                            }

                            if (update) {
                                onMainDispatcher {
                                    adapter.refreshSelection()
                                }

                                if (pa.state.canStop && reloadAccess.tryLock()) {
                                    SagerNet.reloadService()
                                    reloadAccess.unlock()
                                }
                            } else if (SagerNet.isTv) {
                                if (SagerNet.started) {
                                    SagerNet.stopService()
                                } else {
                                    SagerNet.startService()
                                }
                            }
                        }

                    }
                }

                profileName.text = proxyEntity.displayName()
                profileType.text = proxyEntity.displayType()

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx

                val stats = proxyEntity.stats
                if (stats != null) {
                    rx += stats.rxTotal
                    tx += stats.txTotal
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        FormatFileSizeCompat.formatFileSize(view.context, tx, DataStore.useIECUnit),
                        FormatFileSizeCompat.formatFileSize(view.context, rx, DataStore.useIECUnit)
                    )
                }

                var address = proxyEntity.displayAddress()

                if (proxyEntity.requireBean().name.isEmpty() || !parent.alwaysShowAddress) {
                    address = ""
                }

                profileAddress.text = address
                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isEmpty()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                } else if (proxyEntity.status == 1) {
                    profileStatus.text = getString(R.string.available, proxyEntity.ping)
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_green_500))
                } else {
                    profileStatus.setTextColor(requireContext().getColour(R.color.material_red_500))
                    if (proxyEntity.status == 2) {
                        profileStatus.text = proxyEntity.error
                    }
                }

                if (proxyEntity.status == 3) {
                    profileStatus.setText(R.string.unavailable)
                    profileStatus.setOnClickListener {
                        alert(proxyEntity.error ?: "<?>").show()
                    }
                } else {
                    profileStatus.setOnClickListener(null)
                }

                editButton.setOnClickListener {
                    proxyEntity.settingIntent(it.context, proxyGroup.type == GroupType.SUBSCRIPTION)?.let {
                        editProfileLauncher.launch(it)
                    }
                }

                deleteButton.setOnClickListener { view ->
                    view.post {
                        adapter.let {
                            val index = it.configurationIdList.indexOf(proxyEntity.id)
                            if (index >= 0) {
                                it.remove(index)
                                it.pendingDeletedIds.add(proxyEntity.id)
                                undoManager.remove(index to proxyEntity)
                            }
                        }
                    }
                }

                // suppress ItemTouchHelper drag while a row button is held, to avoid conflict with parent item's long-press
                deleteButton.suppressDragWhilePressed { actionButtonPressed = it }
                editButton.suppressDragWhilePressed { actionButtonPressed = it }
                shareLayout.suppressDragWhilePressed { actionButtonPressed = it }

                // Per-row buttons are hidden in selection mode: the bar is the action
                // surface then, and leaving them visible invites mis-taps.
                editButton.isGone = parent.select || adapter.isSelectionMode
                deleteButton.isGone = parent.select || adapter.isSelectionMode
                shareButton.isGone = parent.select || adapter.isSelectionMode

                // Multi-select state is part of a normal bind too, so a row that
                // scrolls in or is rebound elsewhere shows its checkbox.
                bindCheckState()

                runOnDefaultDispatcher {
                    val selected = (parent.selectedItem?.id
                        ?: DataStore.selectedProxy) == proxyEntity.id
                    val started = selected && SagerNet.started && DataStore.startedProfile == proxyEntity.id
                    onMainDispatcher {
                        deleteButton.isEnabled = !started
                        setActiveHighlight(selected)
                    }

                    fun showShare(anchor: View) {
                        val popup = PopupMenu(requireContext(), anchor)
                        popup.menuInflater.inflate(R.menu.profile_share_menu, popup.menu)

                        if (!proxyEntity.hasShareLink() && proxyEntity.wgBean == null) {
                            popup.menu.removeItem(R.id.action_qr)
                            popup.menu.removeItem(R.id.action_clipboard)
                        }
                        if (showBackup && proxyEntity.canExportBackup()) {
                            popup.menu.findItem(R.id.action_export_backup).isVisible = true
                        }

                        popup.setOnMenuItemClickListener(this@ConfigurationHolder)
                        popup.show()
                    }

                    // This block re-shows the share button, so it must respect
                    // selection mode too — otherwise it would undo the
                    // isGone set above and leave the button on screen.
                    if (!parent.select && !adapter.isSelectionMode) {
                        val isInsecure = DataStore.profileSecurityAdvisory && proxyEntity.requireBean().isInsecure
                        onMainDispatcher {
                            if (isInsecure) {
                                shareLayer.setBackgroundColor(Color.RED)
                                shareButton.setImageResource(R.drawable.ic_baseline_warning_24)
                                shareButton.setColorFilter(Color.WHITE)
                            } else {
                                shareLayer.setBackgroundColor(Color.TRANSPARENT)
                                shareButton.setImageResource(R.drawable.ic_social_share)
                                shareButton.setColorFilter(Color.GRAY)

                            }
                            shareButton.isVisible = true
                            if (isInsecure) {
                                shareLayout.setOnClickListener {
                                    MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(R.string.insecure_warn)
                                        .setMessage(R.string.insecure_warning_detail)
                                        .setPositiveButton(android.R.string.ok) { _, _ ->
                                            showShare(it)
                                        }
                                        .show()
                                }
                            } else {
                                shareLayout.setOnClickListener {
                                    showShare(it)
                                }
                            }

                        }
                    }
                }

            }

            fun updateTraffic(proxyEntity: ProxyEntity) {
                val parent = parent ?: return
                if (!::entity.isInitialized) return

                entity.stats = proxyEntity.stats

                var rx = proxyEntity.rx
                var tx = proxyEntity.tx

                val stats = proxyEntity.stats
                if (stats != null) {
                    rx += stats.rxTotal
                    tx += stats.txTotal
                }

                val showTraffic = rx + tx != 0L
                trafficText.isVisible = showTraffic
                if (showTraffic) {
                    trafficText.text = view.context.getString(
                        R.string.traffic,
                        FormatFileSizeCompat.formatFileSize(view.context, tx, DataStore.useIECUnit),
                        FormatFileSizeCompat.formatFileSize(view.context, rx, DataStore.useIECUnit)
                    )
                }

                var address = proxyEntity.displayAddress()
                if (proxyEntity.requireBean().name.isEmpty() || !parent.alwaysShowAddress) {
                    address = ""
                }

                (trafficText.parent as View).isGone = (!showTraffic || proxyEntity.status <= 0) && address.isEmpty()

                if (proxyEntity.status <= 0) {
                    if (showTraffic) {
                        profileStatus.text = trafficText.text
                        profileStatus.setTextColor(requireContext().getColorAttr(android.R.attr.textColorSecondary))
                        trafficText.text = ""
                    } else {
                        profileStatus.text = ""
                    }
                }
            }

            fun showCode(link: String) {
                QRCodeDialog(link).showAllowingStateLoss(parentFragmentManager)
            }

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                (activity as MainActivity).snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            override fun onMenuItemClick(item: MenuItem): Boolean {
                try {
                    when (item.itemId) {
                        R.id.action_qr -> {
                            if (entity.wgBean != null) {
                                entity.wgBean?.toConf()?.let { showCode(it) }
                            } else {
                                entity.toLink()?.let { showCode(it) }
                            }
                        }
                        R.id.action_clipboard -> {
                            if (entity.wgBean != null) {
                                entity.wgBean?.toConf()?.let { export(it) }
                            } else {
                                entity.toLink()?.let { export(it) }
                            }
                        }
                        R.id.action_export_config_clipboard -> export(entity.exportConfig().first)
                        R.id.action_export_config_file -> {
                            val cfg = entity.exportConfig()
                            DataStore.serverConfig = cfg.first
                            startFilesForResult(
                                (parentFragment as ConfigurationFragment).exportConfig, cfg.second
                            )
                        }
                        R.id.action_export_backup_clipboard -> export(entity.requireBean().exportBackup())
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    (activity as MainActivity).snackbar(e.readableMessage).show()
                    return true
                }
                return true
            }
        }

        private val editProfileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    if (DataStore.currentProfile == DataStore.editingId) {
                        needReload()
                    }
                }
            }

    }

    private val exportConfig = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    (requireActivity() as MainActivity).contentResolver.openOutputStream(data)!!
                        .bufferedWriter()
                        .use {
                            it.write(DataStore.serverConfig)
                        }
                    onMainDispatcher {
                        snackbar(getString(R.string.action_export_msg)).show()
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        snackbar(e.readableMessage).show()
                    }
                }

            }
        }
    }
}