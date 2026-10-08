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
 * GNU General Public License for more details.                              *
 *                                                                            *
 * You should have received a copy of the GNU General Public License         *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.exportBackup
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.QRCodeDialog

/**
 * The group popup menu, shared by the two surfaces offering group actions:
 * the "Groups" screen row (⋮ button) and a long-press on a group tab of the
 * main screen. One implementation, so the two action lists can never drift
 * apart.
 *
 * The "Edit group" entry is optional: the Groups screen has a dedicated
 * edit button on every row, so there it stays hidden; on the main screen
 * the tab is the only handle the group has, and long-press enables it.
 * It is always hidden for the ungrouped pseudo-group, matching the Groups
 * screen (its edit button is gone there too).
 *
 * Must be constructed as a Fragment field, so the CreateDocument launchers
 * register before the owning fragment reaches CREATED, as
 * registerForActivityResult requires.
 */
class GroupMenuActions(private val fragment: Fragment) : PopupMenu.OnMenuItemClickListener {

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument()
    ) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                val links = SagerDatabase.proxyDao.getByGroup(selectedGroup.id).mapNotNull {
                    try {
                        it.toLink()
                    } catch (_: Exception) {
                        null
                    }
                }.joinToString("\n")
                writeExport(data, links)
            }
        }
    }

    private val exportBackupOfAllProfiles = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument()
    ) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                val links = SagerDatabase.proxyDao.getByGroup(selectedGroup.id).mapNotNull {
                    if (it.canExportBackup()) {
                        it.requireBean().exportBackup()
                    } else null
                }.joinToString("\n")
                writeExport(data, links)
            }
        }
    }

    /** Write [links] to [uri] and confirm with a snackbar; called from a
     *  background dispatcher by the two CreateDocument launchers above. */
    private suspend fun writeExport(uri: Uri, links: String) {
        try {
            (fragment.requireActivity() as MainActivity).contentResolver.openOutputStream(
                uri
            )!!.bufferedWriter().use {
                it.write(links)
            }
            onMainDispatcher {
                fragment.snackbar(R.string.action_export_msg).show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                fragment.snackbar(e.readableMessage).show()
            }
        }
    }

    fun show(anchor: View, group: ProxyGroup, includeEdit: Boolean) {
        selectedGroup = group

        val popup = PopupMenu(anchor.context, anchor)
        popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

        if (group.type != GroupType.SUBSCRIPTION) {
            popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_export_backup)
            popup.menu.findItem(R.id.action_share).subMenu?.removeItem(R.id.action_subscription_link)
        }

        if (DataStore.experimentalFlagsProperties.getBooleanProperty("enableProfileBackup")) {
            popup.menu.findItem(R.id.action_export_backup_of_all_profiles).isVisible = true
        }

        popup.menu.findItem(R.id.action_edit_group).isVisible =
            includeEdit && !group.ungrouped

        popup.setOnMenuItemClickListener(this)
        popup.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        fun showCode(link: String) {
            QRCodeDialog(link).showAllowingStateLoss(fragment.parentFragmentManager)
        }

        when (item.itemId) {
            R.id.action_edit_group -> {
                fragment.startActivity(
                    Intent(fragment.requireContext(), GroupSettingsActivity::class.java).apply {
                        putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, selectedGroup.id)
                    }
                )
            }
            R.id.action_subscription_link_qr -> {
                showCode(selectedGroup.subscription!!.link!!)
            }
            R.id.action_subscription_link_clipboard -> {
                val link = selectedGroup.subscription!!.link!!
                runOnDefaultDispatcher {
                    onMainDispatcher {
                        SagerNet.trySetPrimaryClip(link)
                        fragment.snackbar(R.string.action_export_msg).show()
                    }
                }
            }
            R.id.action_clipboard -> {
                runOnDefaultDispatcher {
                    val links = SagerDatabase.proxyDao.getByGroup(selectedGroup.id).mapNotNull {
                        try {
                            it.toLink()
                        } catch (_: Exception) {
                            null
                        }
                    }.joinToString("\n")
                    onMainDispatcher {
                        SagerNet.trySetPrimaryClip(links)
                        fragment.snackbar(R.string.action_export_msg).show()
                    }
                }
            }
            R.id.action_file -> {
                fragment.startFilesForResult(
                    exportProfiles, "profiles_${selectedGroup.displayName()}.txt"
                )
            }
            R.id.action_export_backup_of_all_profiles_clipboard -> {
                runOnDefaultDispatcher {
                    val links = SagerDatabase.proxyDao.getByGroup(selectedGroup.id).mapNotNull {
                        if (it.canExportBackup()) {
                            it.requireBean().exportBackup()
                        } else null
                    }.joinToString("\n")
                    onMainDispatcher {
                        SagerNet.trySetPrimaryClip(links)
                        fragment.snackbar(R.string.action_export_msg).show()
                    }
                }
            }
            R.id.action_export_backup_of_all_profiles_file -> {
                fragment.startFilesForResult(
                    exportBackupOfAllProfiles, "profiles_${selectedGroup.displayName()}_backup.txt"
                )
            }
            R.id.action_subscription_sources -> {
                fragment.startActivity(
                    Intent(
                        fragment.requireContext(), SubscriptionSourcesActivity::class.java
                    ).apply {
                        putExtra(SubscriptionSourcesActivity.EXTRA_GROUP_ID, selectedGroup.id)
                    }
                )
            }
            R.id.action_clear -> {
                MaterialAlertDialogBuilder(fragment.requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.clear_profiles_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runOnDefaultDispatcher {
                            GroupManager.clearGroup(selectedGroup.id)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        return true
    }

}
