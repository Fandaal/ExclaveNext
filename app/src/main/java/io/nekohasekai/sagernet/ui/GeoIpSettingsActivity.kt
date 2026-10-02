/******************************************************************************
 *                                                                            *
 * Exclave Next addition: GeoIP settings.                                      *
 *                                                                            *
 * Entry point of the GeoIP feature. Two screens hang off it:                   *
 *   - GeoIpProviderListActivity — the ordered provider chain (drag & drop)     *
 *   - GeoIpDatabaseActivity     — local MaxMind files (update / import)        *
 *                                                                            *
 * Plus a test lookup that shows what every chain entry returned for one IP,   *
 * which is the only honest way to see whether the fallback order is correct.   *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.annotation.LayoutRes
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.GeoIpAnnotator
import io.nekohasekai.sagernet.bg.GeoInfo
import io.nekohasekai.sagernet.bg.GeoInfoTraceLine
import io.nekohasekai.sagernet.bg.trace
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class GeoIpSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // layout_config_settings ships a bare toolbar; without this the options
        // menu (test lookup) never inflates.
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.geoip_settings)
            setDisplayHomeAsUpEnabled(true)
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings, MyPreferenceFragmentCompat())
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.geoip_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.geoip_test_lookup -> {
            testLookup()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    fun testLookup() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.geoip_test_ip_hint)
            setText("5.9.255.1")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.geoip_test_lookup)
            .setMessage(R.string.geoip_test_lookup_sum)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isEmpty()) return@setPositiveButton
                runOnDefaultDispatcher {
                    val lines = GeoIpAnnotator.trace(ip)
                    val result = GeoIpAnnotator.lookup(ip)
                    onMainDispatcher { showTrace(ip, result, lines) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTrace(
        ip: String,
        result: GeoInfo,
        lines: List<GeoInfoTraceLine>,
    ) {
        val body = buildString {
            for (line in lines) {
                val detail = when {
                    !line.consulted -> "— ${line.error}"
                    line.error.isNotEmpty() -> "✗ ${line.error}"
                    line.country.isNotEmpty() && line.provider.isNotEmpty() ->
                        "country=${line.country}, provider=${line.provider}"
                    line.country.isNotEmpty() -> "country=${line.country}"
                    line.provider.isNotEmpty() -> "provider=${line.provider}"
                    else -> "no data"
                }
                append("• ").append(line.label).append(" → ").append(detail).append("\n")
            }
            append("\n").append(result.tag())
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.geoip_trace) + " · " + ip)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        private val activity: GeoIpSettingsActivity
            get() = requireActivity() as GeoIpSettingsActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            addPreferencesFromResource(R.xml.geoip_preferences)

            findPreference<Preference>("geoip_provider_chain")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), GeoIpProviderListActivity::class.java))
                true
            }
            findPreference<Preference>("geoip_local_databases")!!.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), GeoIpDatabaseActivity::class.java))
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // The two child screens write straight to DataStore, so their effect
            // on the summaries is only visible when this fragment comes back.
            val chain = DataStore.geoIpChain
            findPreference<Preference>("geoip_provider_chain")!!.summary = getString(
                R.string.geoip_provider_chain_sum
            ) + "\n" + getString(
                R.string.geoip_active_n_of_m, chain.count { it.enabled }, chain.size
            )

            runOnDefaultDispatcher {
                val databases = io.nekohasekai.sagernet.bg.GeoIpDatabaseManager.list()
                onMainDispatcher {
                    if (!isAdded) return@onMainDispatcher
                    val installed = databases.count { it.exists }
                    findPreference<Preference>("geoip_local_databases")!!.summary =
                        getString(R.string.geoip_local_databases_sum) + "\n" +
                                "$installed / ${databases.size}"
                }
            }
        }
    }
}
