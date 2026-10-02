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

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionSource
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    /**
     * Pull [source] and reconcile its profiles inside [proxyGroup]. Only
     * profiles whose sourceId == source.id are touched; other sources and
     * manually-added profiles (sourceId == 0) are left untouched.
     */
    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        source: SubscriptionSource,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    companion object {

        // Keyed by SubscriptionSource.id (a single source refresh).
        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        /** True while any source of [groupId] is being refreshed. */
        fun isGroupUpdating(groupId: Long): Boolean {
            val sourceIds = SagerDatabase.sourceDao.byGroup(groupId).map { it.id }
            synchronized(updating) {
                return sourceIds.any { it in updating }
            }
        }

        /** Refresh a single subscription source. */
        fun startUpdate(source: SubscriptionSource, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(source, byUser)
            }
        }

        /** Refresh every source of a group, sequentially. */
        fun startUpdateAll(groupId: Long, byUser: Boolean) {
            runOnDefaultDispatcher {
                val connected = SagerNet.started && DataStore.startedProfile > 0
                for (source in SagerDatabase.sourceDao.byGroup(groupId)) {
                    val sub = source.subscription ?: continue
                    if (!byUser && sub.updateWhenConnectedOnly && !connected) continue
                    executeUpdate(source, byUser)
                }
            }
        }

        suspend fun executeUpdate(source: SubscriptionSource, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(source.id)) cancel()
                GroupManager.postReload(source.groupId)

                val proxyGroup = SagerDatabase.groupDao.getById(source.groupId)
                if (proxyGroup == null) {
                    finishUpdate(source)
                    cancel()
                    return@coroutineScope false
                }
                val subscription = source.subscription!!
                val connected = SagerNet.started && DataStore.startedProfile > 0
                val userInterface = GroupManager.userInterface

                if (subscription.updateWhenConnectedOnly && !connected) {
                    if (!byUser || userInterface == null) {
                        finishUpdate(source)
                        cancel()
                    } else {
                        if (!userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            finishUpdate(source)
                            cancel()
                        }
                    }
                }

                try {
                    when (subscription.type) {
                        SubscriptionType.RAW -> RawUpdater
                        SubscriptionType.SIP008 -> SIP008Updater
                        SubscriptionType.AGE -> AgeUpdater
                        else -> error("unsupported")
                    }.doUpdate(proxyGroup, source, userInterface, byUser)
                    true
                } catch (e: Throwable) {
                    Logs.w(e)
                    if (byUser && userInterface != null) {
                        userInterface.onUpdateFailure(proxyGroup, e.readableMessage)
                    }
                    finishUpdate(source)
                    false
                }
            }
        }


        suspend fun finishUpdate(source: SubscriptionSource) {
            updating.remove(source.id)
            progress.remove(source.id)
            // Persist the refreshed subscription metadata (lastUpdated, bytes…).
            SagerDatabase.sourceDao.update(source)
            GroupManager.postUpdate(source.groupId)
        }

    }

}
