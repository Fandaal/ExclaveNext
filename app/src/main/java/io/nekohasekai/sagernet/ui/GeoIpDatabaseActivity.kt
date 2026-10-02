/******************************************************************************
 *                                                                            *
 * Exclave Next addition: local MaxMind database management screen.            *
 *                                                                            *
 * Lists every database GeoIpDatabaseManager knows about, shows the build date  *
 * baked into the MMDB metadata (not the download time — that is what tells    *
 * you whether the data is fresh), and offers per-row update, import and        *
 * delete. Every mutating action reports what actually happened: "already up    *
 * to date" when the ETag matched is a different outcome from "updated", and     *
 * only the first one is a success the user needs to know about.                *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.DieUpdateResult
import io.nekohasekai.sagernet.bg.GeoIpDatabaseManager
import io.nekohasekai.sagernet.bg.GeoIpDefaults
import io.nekohasekai.sagernet.bg.InstalledDatabase
import io.nekohasekai.sagernet.databinding.LayoutGeoipDatabaseItemBinding
import io.nekohasekai.sagernet.databinding.LayoutGeoipDatabaseListBinding
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.text.DateFormat
import java.util.Date

class GeoIpDatabaseActivity : ThemedActivity() {

    private lateinit var binding: LayoutGeoipDatabaseListBinding
    private lateinit var adapter: DatabaseAdapter
    // File names with an update in flight; drives both the guard against a double
    // tap and the per-row progress bar.
    private val busy = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.geoip_local_databases)
        binding = LayoutGeoipDatabaseListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = DatabaseAdapter()
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.geoip_database_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_update_all -> {
            updateAll()
            true
        }
        R.id.action_import_db -> {
            importFile.launch("*/*")
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        runOnDefaultDispatcher {
            val loaded = GeoIpDatabaseManager.list()
            onMainDispatcher {
                if (!isFinishing) adapter.replace(loaded)
            }
        }
    }

    /** The mirror URL configured for this file, if the chain has such an entry. */
    private fun urlFor(fileName: String): String = when (fileName) {
        GeoIpDefaults.COUNTRY_DB -> GeoIpDefaults.COUNTRY_DB_URL
        GeoIpDefaults.ASN_DB -> GeoIpDefaults.ASN_DB_URL
        else -> ""
    }

    private fun updateOne(database: InstalledDatabase) {
        val url = urlFor(database.fileName)
        if (url.isEmpty()) {
            snackbar(getString(R.string.geoip_update_failed, "no update URL"))
            return
        }
        if (database.fileName in busy) return
        busy.add(database.fileName)
        adapter.setBusy(database.fileName, true)

        runOnDefaultDispatcher {
            val result = GeoIpDatabaseManager.updateFromUrl(database.fileName, url)
            busy.remove(database.fileName)
            onMainDispatcher {
                if (isFinishing) return@onMainDispatcher
                adapter.setBusy(database.fileName, false)
                snackbar(
                    when (result) {
                        is DieUpdateResult.Updated -> getString(R.string.geoip_updated)
                        is DieUpdateResult.AlreadyCurrent -> getString(R.string.geoip_already_up_to_date)
                        is DieUpdateResult.Failed -> getString(R.string.geoip_update_failed, result.reason)
                    }
                )
                reload()
            }
        }
    }

    private fun updateAll() {
        val targets = adapter.databases.filter { urlFor(it.fileName).isNotEmpty() }
        if (targets.isEmpty()) {
            snackbar(R.string.geoip_db_not_installed)
            return
        }
        targets.forEach { updateOne(it) }
    }

    private fun confirmDelete(database: InstalledDatabase) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.geoip_delete_confirm, database.fileName))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runOnDefaultDispatcher {
                    GeoIpDatabaseManager.delete(database.fileName)
                    onMainDispatcher {
                        if (!isFinishing) reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file == null) return@registerForActivityResult
        val fileName = contentResolver.query(file, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            } else {
                null
            }
        }?.takeIf { it.isNotBlank() } ?: file.lastPathSegment?.substringAfterLast('/')

        if (fileName.isNullOrBlank()) return@registerForActivityResult

        runOnDefaultDispatcher {
            val error = try {
                contentResolver.openInputStream(file)?.use {
                    GeoIpDatabaseManager.importStreamTo(it, fileName)
                }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            onMainDispatcher {
                if (isFinishing) return@onMainDispatcher
                if (error != null) snackbar(getString(R.string.geoip_invalid_db) + ": " + error)
                reload()
            }
        }
    }

    inner class DatabaseAdapter : RecyclerView.Adapter<DatabaseHolder>() {

        val databases = ArrayList<InstalledDatabase>()

        fun replace(loaded: List<InstalledDatabase>) {
            databases.clear()
            databases.addAll(loaded)
            notifyDataSetChanged()
        }

        fun setBusy(fileName: String, value: Boolean) {
            val index = databases.indexOfFirst { it.fileName == fileName }
            if (index < 0) return
            notifyItemChanged(index)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DatabaseHolder {
            return DatabaseHolder(
                LayoutGeoipDatabaseItemBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: DatabaseHolder, position: Int) {
            holder.bind(databases[position])
        }

        override fun getItemCount(): Int = databases.size
    }

    inner class DatabaseHolder(val binding: LayoutGeoipDatabaseItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(database: InstalledDatabase) {
            binding.dbName.text = database.fileName

            val detail = buildString {
                if (database.error != null) {
                    append(getString(R.string.geoip_invalid_db))
                    append(": ").append(database.error)
                } else if (!database.exists) {
                    append(getString(R.string.geoip_db_not_installed))
                } else {
                    database.buildDate?.let {
                        append(
                            getString(
                                R.string.geoip_db_build_date,
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.time)),
                            )
                        )
                        append("\n")
                    }
                    append(getString(R.string.geoip_db_size, formatSize(database.sizeBytes)))
                    if (database.databaseType.isNotEmpty()) {
                        append("\n").append(database.databaseType)
                    }
                }
            }
            binding.dbDetail.text = detail

            val updating = database.fileName in busy
            binding.dbProgress.isInvisible = !updating
            binding.dbUpdate.isVisible = !updating && urlFor(database.fileName).isNotEmpty()
            binding.dbDelete.isVisible = database.exists

            binding.dbUpdate.setOnClickListener { updateOne(database) }
            binding.dbDelete.setOnClickListener { confirmDelete(database) }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
