/******************************************************************************
 *                                                                            *
 * Exclave Next addition: the GeoIP provider chain editor.                      *
 *                                                                            *
 * Order IS the behaviour: lookup walks the enabled entries top to bottom and *
 * stops as soon as both country and provider are known, so drag & drop here   *
 * changes what a lookup actually does. Reordering therefore writes through to *
 * DataStore immediately — there is no save button to forget.                  *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.GeoIpDefaults
import io.nekohasekai.sagernet.bg.GeoIpEntry
import io.nekohasekai.sagernet.bg.GeoIpEntryType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutGeoipProviderItemBinding
import io.nekohasekai.sagernet.databinding.LayoutGeoipProviderListBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px

class GeoIpProviderListActivity : ThemedActivity() {

    private lateinit var binding: LayoutGeoipProviderListBinding
    private lateinit var adapter: EntryAdapter
    private lateinit var touchHelper: ItemTouchHelper

    // True while a row control (delete button, switch) is pressed — read by
    // DragCallback.getDragDirs to keep whole-card drag from stealing those
    // taps, the same suppression the main screen's config list uses.
    private var actionButtonPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutGeoipProviderListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // No LayoutManager = RecyclerView silently renders nothing. This was the
        // "empty screen" bug: adapter and data were fine, the list just had no
        // idea how to lay rows out.
        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)

        adapter = EntryAdapter()
        binding.recyclerView.adapter = adapter

        touchHelper = ItemTouchHelper(DragCallback())
        touchHelper.attachToRecyclerView(binding.recyclerView)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.geoip_provider_chain)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        adapter.reload()

        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left + dp2px(4),
                right = bars.right + dp2px(4),
                bottom = bars.bottom + dp2px(4),
            )
            insets
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * ThemedActivity.snackbarInternal throws NotImplementedError by default;
     * only MainActivity, AssetsActivity and SubscriptionSourcesActivity override
     * it. Without this, every snackbar in this screen is a crash — which is what
     * the delete button hit.
     */
    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.geoip_provider_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_add_api -> {
            promptForApiUrl()
            true
        }
        R.id.action_add_mmdb_country -> {
            addEntry(
                GeoIpEntry(
                    type = GeoIpEntryType.MMDB_COUNTRY,
                    url = GeoIpDefaults.COUNTRY_DB_URL,
                    file = GeoIpDefaults.COUNTRY_DB,
                )
            )
            true
        }
        R.id.action_add_mmdb_asn -> {
            addEntry(
                GeoIpEntry(
                    type = GeoIpEntryType.MMDB_ASN,
                    url = GeoIpDefaults.ASN_DB_URL,
                    file = GeoIpDefaults.ASN_DB,
                )
            )
            true
        }
        R.id.action_add_mmdb_custom -> {
            promptForLocalDatabase()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * A third local database (GeoIP2-City, an ISP list, …). The file name must be
     * known to the resolver, so the dialog asks for the exact name it has inside
     * externalAssets — the database screen writes it there on import.
     *
     * The capability is guessed from the name because a custom database is not
     * registered as a new entry type: a country-capable database is a Country
     * entry, anything else is treated as provider-only. That mirrors what the
     * reader can actually answer for an unknown MMDB type.
     */
    private fun promptForLocalDatabase() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.geoip_file_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geoip_add_custom_db)
            .setMessage(R.string.geoip_add_custom_db_sum)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                when {
                    name.isEmpty() -> snackbar(R.string.geoip_bad_file_name)
                    !name.endsWith(".mmdb") -> snackbar(R.string.geoip_bad_file_name)
                    else -> {
                        val providesCountry = name.contains("Country", ignoreCase = true) ||
                                name.contains("City", ignoreCase = true)
                        addEntry(
                            GeoIpEntry(
                                type = if (providesCountry) GeoIpEntryType.MMDB_COUNTRY
                                else GeoIpEntryType.MMDB_ASN,
                                file = name,
                            )
                        )
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptForApiUrl() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.geoip_url_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geoip_add_provider)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = input.text.toString().trim()
                when {
                    url.isEmpty() -> snackbar(R.string.geoip_bad_url)
                    !url.contains("{ip}") -> snackbar(R.string.geoip_no_placeholder)
                    else -> addEntry(GeoIpEntry(type = GeoIpEntryType.API, url = url))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun addEntry(entry: GeoIpEntry) {
        val chain = DataStore.geoIpChain.toMutableList()
        chain.add(entry)
        DataStore.setGeoIpChain(chain)
        adapter.reload()
    }

    private fun removeAt(position: Int) {
        val chain = DataStore.geoIpChain.toMutableList()
        if (position !in chain.indices) return
        chain.removeAt(position)
        DataStore.setGeoIpChain(chain)
        adapter.reload()
        snackbar(R.string.geoip_entry_removed)
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0,
    ) {
        // Whole-card drag, like the main screen's config list: press and hold
        // the card itself. getDragDirs suppresses the drag while a row button
        // or the switch is pressed, so tapping them never starts a drag.
        override fun getDragDirs(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int {
            return if (actionButtonPressed) 0
            else super.getDragDirs(recyclerView, viewHolder)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from < 0 || to < 0) return false
            adapter.entries.add(to, adapter.entries.removeAt(from))
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Persist only when the finger is actually released: persisting on
            // every onMove would hammer the database mid-drag.
            DataStore.setGeoIpChain(adapter.entries)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        }
    }

    inner class EntryAdapter : RecyclerView.Adapter<EntryHolder>() {

        val entries = ArrayList<GeoIpEntry>()

        fun reload() {
            entries.clear()
            entries.addAll(DataStore.geoIpChain)
            notifyDataSetChanged()
        }

        /**
         * Repaint one row without crashing when the adapter got detached between
         * the tap and this call (RecyclerView throws on notify* after detach).
         */
        fun notifyItemChangedSafely(position: Int) {
            if (position in entries.indices) notifyItemChanged(position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            return EntryHolder(
                LayoutGeoipProviderItemBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size
    }

    inner class EntryHolder(val binding: LayoutGeoipProviderItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: GeoIpEntry) {
            val capabilities = buildString {
                if (entry.providesCountry) append(getString(R.string.geoip_entry_country))
                if (entry.providesProvider) {
                    if (isNotEmpty()) append(", ")
                    append(getString(R.string.geoip_entry_provider))
                }
            }
            binding.entryName.text = if (entry.isLocal) {
                entry.file.ifEmpty { entry.type }
            } else {
                entry.url
            }
            binding.entryDetail.text = if (entry.enabled) {
                if (entry.isLocal) {
                    "$capabilities · ${getString(R.string.geoip_type_local)}"
                } else {
                    capabilities
                }
            } else {
                "$capabilities · ${getString(R.string.geoip_disabled_suffix)}"
            }

            // Detach the listener before setting state, or restoring a recycled
            // row fires it and rewrites the entry that was just bound.
            binding.entryEnabled.setOnCheckedChangeListener(null)
            binding.entryEnabled.isChecked = entry.enabled
            binding.entryEnabled.setOnCheckedChangeListener { _, checked ->
                val position = bindingAdapterPosition
                val current = adapter.entries
                if (position < 0 || position >= current.size) return@setOnCheckedChangeListener
                val target = current[position]
                if (target.enabled == checked) return@setOnCheckedChangeListener
                current[position] = target.copy(enabled = checked)
                DataStore.setGeoIpChain(current)
                // Repaint the row: the detail line states what the entry provides,
                // which changes when it is switched off.
                adapter.notifyItemChangedSafely(position)
            }

            binding.entryDelete.setOnClickListener {
                val position = bindingAdapterPosition
                if (position < 0) return@setOnClickListener
                removeAt(position)
            }

            // Whole-card drag: press-and-hold anywhere on the card moves it.
            // The delete button and the enabled switch opt out via
            // suppressDragWhilePressed, so their taps are never hijacked.
            binding.entryDelete.suppressDragWhilePressed { actionButtonPressed = it }
            binding.entryEnabled.suppressDragWhilePressed { actionButtonPressed = it }
        }
    }
}
