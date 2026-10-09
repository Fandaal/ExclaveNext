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

package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionSource
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCancellableCoroutine {
            var resumed = false
            fun tryResume(value: Boolean) {
                if (!resumed) {
                    resumed = true
                    it.resume(value)
                }
            }
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> tryResume(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> tryResume(false) }
                    .setOnCancelListener { _ -> tryResume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        source: SubscriptionSource?,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
    ) {
        // Exclave Next: a group holds MANY subscription sources, and an update
        // run covers ONE of them (GroupUpdater.executeUpdate works per
        // source). Naming the GROUP here showed the same word in the title
        // and in the first line of the body, once per subscription — useless
        // for telling two dialogs apart. Identify the run by the subscription
        // the user named; the group name stays in the title only when there
        // is no source to name (should not happen in practice).
        val title = source?.displayName() ?: group.displayName()
        if (changed == 0 && duplicate.isEmpty()) {
            onMainDispatcher {
                context.snackbar(context.getString(R.string.group_no_difference, title)).show()
            }
            return
        }
        // No "%s: " prefix here — the name is already the dialog title, and
        // repeating it in the body's first line was the redundancy being
        // reported. The plural now carries the count only, and starts with a
        // capital because it opens the message as a sentence.
        val summary = context.resources.getQuantityString(R.plurals.group_updated, changed, changed)
        var status = summary + "\n\n"
        if (added.isNotEmpty()) {
            status += context.getString(
                    R.string.group_added, added.joinToString("\n", postfix = "\n\n")
            )
        }
        if (updated.isNotEmpty()) {
            status += context.getString(R.string.group_changed,
                    updated.map { it }.joinToString("\n", postfix = "\n\n") {
                        if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                    })
        }
        if (deleted.isNotEmpty()) {
            status += context.getString(
                    R.string.group_deleted, deleted.joinToString("\n", postfix = "\n\n")
            )
        }
        if (duplicate.isNotEmpty()) {
            status += context.getString(
                    R.string.group_duplicate, duplicate.joinToString("\n", postfix = "\n\n")
            )
        }

        onMainDispatcher {
            // The summary is born when the diff dialog CLOSES, not when it
            // opens. A snackbar lives in the activity window and a dialog is a
            // separate, always-higher window: creating the snackbar
            // before/with the dialog made it slip under and die by timeout
            // (the focus loss only arrives after the dialog has traversed, so
            // the queue could not know in time — two attempts at patching that
            // race both failed). Creating it on dismissal cannot race by
            // construction. It still enters the queue: an update run is
            // usually still in flight (blockingRuns), so it waits with every
            // other summary and they all drain together afterwards.
            // NOTE: no `apply` here — inside it, `context` would resolve to
            // the BUILDER's Context and not to this activity.
            MaterialAlertDialogBuilder(context).setTitle(title)
                .setMessage(status.trim()).setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener { context.snackbar("$title: $summary").show() }
                .show()
        }
    }

    override suspend fun onUpdateFailure(
        group: ProxyGroup,
        source: SubscriptionSource?,
        message: String,
    ) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }

    // GroupUpdater wraps a whole update run in these; forwarding to the
    // activity keeps its snackbar queue from releasing a summary under a
    // diff dialog that a later source of the same run is still going to open.
    override suspend fun beginBlockingRun() {
        onMainDispatcher { context.beginBlockingRun() }
    }

    override suspend fun endBlockingRun() {
        onMainDispatcher { context.endBlockingRun() }
    }

}