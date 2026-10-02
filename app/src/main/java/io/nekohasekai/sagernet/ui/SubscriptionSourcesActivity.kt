/******************************************************************************
 *                                                                            *
 * Exclave Next addition: subscription sources of a group.                    *
 *                                                                            *
 * Lists every SubscriptionSource that belongs to a group and lets the user   *
 * add, update, edit and delete them individually. Reached from the group's   *
 * options menu. Manual profiles (sourceId == 0) are not shown here.          *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionSource
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionSourceItemBinding
import io.nekohasekai.sagernet.databinding.LayoutSubscriptionSourcesBinding
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*

class SubscriptionSourcesActivity : ThemedActivity(), GroupManager.Listener {

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
    }

    private lateinit var binding: LayoutSubscriptionSourcesBinding
    private lateinit var adapter: SourceAdapter
    private var groupId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        if (groupId == 0L) {
            finish()
            return
        }

        binding = LayoutSubscriptionSourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left + dp2px(4),
                right = bars.right + dp2px(4),
                bottom = bars.bottom + dp2px(4),
            )
            insets
        }

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.subscription_sources)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = SourceAdapter()
        binding.recyclerView.adapter = adapter

        GroupManager.addListener(this)
        adapter.reload()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.subscription_sources_menu, menu)
        return true
    }

    private fun editSource(sourceId: Long) {
        startActivity(Intent(this, SubscriptionSourceEditActivity::class.java).apply {
            putExtra(SubscriptionSourceEditActivity.EXTRA_GROUP_ID, groupId)
            putExtra(SubscriptionSourceEditActivity.EXTRA_SOURCE_ID, sourceId)
        })
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add_source_manual -> {
                editSource(0L)
                return true
            }
            R.id.action_add_source_clipboard -> {
                val text = SagerNet.getClipboardText()
                if (!isHTTPorHTTPSURL(text.trim())) {
                    snackbar(R.string.subscription_source_clipboard_not_url).show()
                    return true
                }
                runOnDefaultDispatcher {
                    val source = SubscriptionSource(groupId = groupId).apply {
                        subscription = io.nekohasekai.sagernet.database.SubscriptionBean()
                            .applyDefaultValues().apply { link = text.trim() }
                    }
                    val created = GroupManager.createSource(source)
                    GroupUpdater.startUpdate(created, true)
                }
                return true
            }
            R.id.action_update_all_sources -> {
                GroupUpdater.startUpdateAll(groupId, true)
                return true
            }
        }
        return false
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        GroupManager.removeListener(this)
        super.onDestroy()
    }

    // GroupManager.Listener — refresh when sources/profiles of our group change.
    override suspend fun groupAdd(group: io.nekohasekai.sagernet.database.ProxyGroup) {}
    override suspend fun groupUpdated(group: io.nekohasekai.sagernet.database.ProxyGroup) {
        if (group.id == groupId) adapter.reload()
    }
    override suspend fun groupRemoved(groupId: Long) {
        if (groupId == this.groupId) onMainDispatcher { finish() }
    }
    override suspend fun groupUpdated(groupId: Long) {
        if (groupId == this.groupId) adapter.reload()
    }

    inner class SourceAdapter : RecyclerView.Adapter<SourceHolder>() {

        val sources = ArrayList<SubscriptionSource>()

        fun reload() {
            runOnDefaultDispatcher {
                val loaded = SagerDatabase.sourceDao.byGroup(groupId)
                onMainDispatcher {
                    sources.clear()
                    sources.addAll(loaded)
                    notifyDataSetChanged()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceHolder {
            return SourceHolder(
                LayoutSubscriptionSourceItemBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: SourceHolder, position: Int) {
            holder.bind(sources[position])
        }

        override fun getItemCount(): Int = sources.size
    }

    inner class SourceHolder(val binding: LayoutSubscriptionSourceItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(source: SubscriptionSource) {
            val sub = source.subscription
            binding.sourceName.text = source.displayName()
            binding.sourceLink.text = sub?.link ?: ""

            val updating = source.id in GroupUpdater.updating
            binding.subscriptionUpdateProgress.isInvisible = !updating
            binding.sourceUpdate.isInvisible = updating

            runOnDefaultDispatcher {
                val count = SagerDatabase.proxyDao.getByGroupAndSource(groupId, source.id).size
                val lastUpdated = sub?.lastUpdated ?: 0L
                val status = when {
                    count == 0 -> getString(R.string.group_status_empty)
                    lastUpdated <= 0L -> resources.getQuantityString(
                        R.plurals.group_status_proxies, count, count
                    )
                    else -> resources.getQuantityString(
                        R.plurals.group_status_proxies_subscription, count, count,
                        DateUtils.getRelativeTimeSpanString(this@SubscriptionSourcesActivity, lastUpdated * 1000)
                    )
                }
                onMainDispatcher {
                    binding.sourceStatus.text = status
                }
            }

            binding.content.setOnClickListener { editSource(source.id) }
            binding.edit.setOnClickListener { editSource(source.id) }
            binding.sourceUpdate.setOnClickListener {
                GroupUpdater.startUpdate(source, true)
            }
        }
    }
}
