/******************************************************************************
 *                                                                            *
 * Exclave Next addition: edit a single subscription source of a group.       *
 *                                                                            *
 * A group may own several SubscriptionSource rows (see SubscriptionSource).  *
 * This screen creates or edits one of them, reusing the subscription half of *
 * the old group settings (type, link, filters, UA, headers, auto-update).    *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.SubscriptionSource
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.PasswordSummaryProvider

@Suppress("UNCHECKED_CAST")
class SubscriptionSourceEditActivity(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    var dirty = false

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            MaterialAlertDialogBuilder(this@SubscriptionSourceEditActivity)
                .setTitle(R.string.unsaved_changes_prompt)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher { saveAndExit() }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .show()
        }
    }

    fun SubscriptionSource.init() {
        DataStore.editingSourceId = id
        DataStore.editingSourceGroupId = groupId
        DataStore.subscriptionSourceName = name ?: ""
        val sub = subscription ?: SubscriptionBean().applyDefaultValues()
        DataStore.subscriptionType = sub.type
        DataStore.subscriptionLink = sub.link
        DataStore.subscriptionDeduplication = sub.deduplication
        DataStore.subscriptionUpdateWhenConnectedOnly = sub.updateWhenConnectedOnly
        DataStore.subscriptionUserAgent = sub.customUserAgent
        DataStore.subscriptionAutoUpdate = sub.autoUpdate
        DataStore.subscriptionAutoUpdateDelay = sub.autoUpdateDelay
        DataStore.subscriptionLastUpdated = sub.lastUpdated
        DataStore.subscriptionBytesUsed = sub.bytesUsed
        DataStore.subscriptionBytesRemaining = sub.bytesRemaining
        DataStore.subscriptionExpiryDate = sub.expiryDate
        DataStore.subscriptionNameFilter = sub.nameFilter
        DataStore.subscriptionNameFilter1 = sub.nameFilter1
        DataStore.subscriptionHTTPHeaders = sub.httpHeaders
        DataStore.subscriptionAgePrivateKey = sub.agePrivateKey
    }

    fun SubscriptionSource.serialize() {
        groupId = DataStore.editingSourceGroupId
        name = DataStore.subscriptionSourceName
        subscription = SubscriptionBean().applyDefaultValues().apply {
            type = DataStore.subscriptionType
            link = DataStore.subscriptionLink
            deduplication = DataStore.subscriptionDeduplication
            updateWhenConnectedOnly = DataStore.subscriptionUpdateWhenConnectedOnly
            customUserAgent = DataStore.subscriptionUserAgent
            autoUpdate = DataStore.subscriptionAutoUpdate
            autoUpdateDelay = DataStore.subscriptionAutoUpdateDelay
            lastUpdated = DataStore.subscriptionLastUpdated
            bytesUsed = DataStore.subscriptionBytesUsed
            bytesRemaining = DataStore.subscriptionBytesRemaining
            expiryDate = DataStore.subscriptionExpiryDate
            nameFilter = DataStore.subscriptionNameFilter
            nameFilter1 = DataStore.subscriptionNameFilter1
            httpHeaders = DataStore.subscriptionHTTPHeaders
            agePrivateKey = DataStore.subscriptionAgePrivateKey
        }
    }

    fun needSave(): Boolean = dirty

    fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.subscription_source_preferences)

        findPreference<EditTextPreference>(Key.SUBSCRIPTION_HTTP_HEADERS)!!.apply {
            dialogMessage = getString(R.string.format, "\nKey1: Value1\nKey2: Value2")
        }
        val subscriptionType = findPreference<ListPreference>(Key.SUBSCRIPTION_TYPE)!!
        val agePrivateKey = findPreference<EditTextPreference>(Key.SUBSCRIPTION_AGE_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }

        fun updateSubscriptionType(type: Int = DataStore.subscriptionType) {
            agePrivateKey.isVisible = type == SubscriptionType.AGE
        }
        updateSubscriptionType()
        subscriptionType.setOnPreferenceChangeListener { _, newValue ->
            updateSubscriptionType((newValue as String).toInt())
            true
        }

        val subscriptionAutoUpdate = findPreference<SwitchPreference>(Key.SUBSCRIPTION_AUTO_UPDATE)!!
        val subscriptionAutoUpdateDelay = findPreference<EditTextPreference>(Key.SUBSCRIPTION_AUTO_UPDATE_DELAY)!!
        subscriptionAutoUpdateDelay.isEnabled = subscriptionAutoUpdate.isChecked
        subscriptionAutoUpdateDelay.setOnPreferenceChangeListener { _, newValue ->
            newValue as String
            newValue.toIntOrNull() != null && newValue.toInt() >= 15
        }
        subscriptionAutoUpdate.setOnPreferenceChangeListener { _, newValue ->
            subscriptionAutoUpdateDelay.isEnabled = (newValue as Boolean)
            true
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "sourceId"
        const val EXTRA_GROUP_ID = "groupId"
        const val KEY_DIRTY = "dirty"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.subscription_source)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val sourceId = intent.getLongExtra(EXTRA_SOURCE_ID, 0L)
            val groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
            runOnDefaultDispatcher {
                if (sourceId == 0L) {
                    SubscriptionSource(groupId = groupId).apply {
                        subscription = SubscriptionBean().applyDefaultValues()
                        init()
                    }
                } else {
                    val entity = SagerDatabase.sourceDao.getById(sourceId)
                    if (entity == null) {
                        onMainDispatcher { finish() }
                        return@runOnDefaultDispatcher
                    }
                    entity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                    DataStore.profileCacheStore.registerChangeListener(this@SubscriptionSourceEditActivity)
                }
            }
        } else {
            savedInstanceState.getBoolean(KEY_DIRTY).let {
                dirty = it
                onBackPressedCallback.isEnabled = it
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DIRTY, dirty)
    }

    suspend fun saveAndExit() {
        if (DataStore.subscriptionLink.isNullOrBlank()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@SubscriptionSourceEditActivity)
                    .setTitle(R.string.error_title)
                    .setMessage(R.string.subscription_source_empty_link)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        val sourceId = DataStore.editingSourceId
        if (sourceId == 0L) {
            val source = SubscriptionSource().apply { serialize() }
            val created = GroupManager.createSource(source)
            // Pull it right away so the group gets its profiles.
            GroupUpdater.startUpdate(created, true)
        } else if (needSave()) {
            val entity = SagerDatabase.sourceDao.getById(sourceId)
            if (entity == null) {
                finish()
                return
            }
            GroupManager.updateSource(entity.apply { serialize() })
        }

        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_delete -> {
            if (DataStore.editingSourceId == 0L) {
                finish()
            } else {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.subscription_source_delete_prompt)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.deleteSource(DataStore.editingSourceId)
                        }
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        R.id.action_apply -> {
            runOnDefaultDispatcher { saveAndExit() }
            true
        }
        else -> false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            dirty = true
            onBackPressedCallback.isEnabled = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        val activity: SubscriptionSourceEditActivity
            get() = requireActivity() as SubscriptionSourceEditActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity.apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    left = bars.left,
                    right = bars.right,
                    bottom = bars.bottom,
                )
                insets
            }
        }
    }
}
