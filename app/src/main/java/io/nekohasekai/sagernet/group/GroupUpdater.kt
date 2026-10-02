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
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

@Suppress("EXPERIMENTAL_API_USAGE")
abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    // Names repeats "X", "X (1)", "X (2)"... exactly as the old per-proxy loop did, but
    // resumes each base name where it last stopped instead of walking its whole chain
    // again, which was quadratic when thousands of proxies share a name.
    protected fun renameDuplicateNames(proxies: List<AbstractBean>): List<AbstractBean> {
        val proxiesMap = LinkedHashMap<String, AbstractBean>()
        val lastTaken = HashMap<String, Pair<Int, String>>()
        for (proxy in proxies) {
            val base = proxy.displayName()
            var (index, name) = lastTaken[base] ?: (0 to base)
            while (proxiesMap.containsKey(name)) {
                index++
                name = name.replace(" (${index - 1})", "")
                name = "$name ($index)"
            }
            if (index > 0) proxy.name = name
            lastTaken[base] = index to name
            proxiesMap[proxy.displayName()] = proxy
        }
        return proxiesMap.values.toList()
    }

    // Keeps the first proxy of each key and reports the rest the way the old LinkedHashSet
    // loop did, but takes the first one's position from a map instead of indexOf.
    protected fun <K : Any> deduplicate(
        proxies: List<AbstractBean>,
        duplicate: MutableList<String>,
        key: (AbstractBean) -> K,
    ): List<AbstractBean> {
        val firstIndex = HashMap<K, Int>()
        val uniqueNames = HashMap<K, String>()
        val unique = ArrayList<AbstractBean>()
        for (p in proxies) {
            val proxy = key(p)
            val index = firstIndex[proxy]
            if (index == null) {
                firstIndex[proxy] = unique.size
                uniqueNames[proxy] = p.displayName()
                unique.add(p)
                continue
            }
            val name = uniqueNames[proxy]!!.replace(" ($index)", "")
            if (name.isNotEmpty()) {
                duplicate.add("$name ($index)")
                uniqueNames[proxy] = ""
            }
            duplicate.add(p.displayName() + " ($index)")
        }
        return unique
    }

    // One transaction instead of one per added proxy: each write transaction also
    // notifies the service process (multi-instance invalidation).
    protected fun commitProxies(toAdd: List<ProxyEntity>, toUpdate: List<ProxyEntity>, toDelete: List<ProxyEntity>) {
        SagerDatabase.runInTransaction {
            SagerDatabase.proxyDao.insert(toAdd)
            SagerDatabase.proxyDao.updateProxy(toUpdate)
            SagerDatabase.proxyDao.deleteProxy(toDelete)
        }
    }

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return coroutineScope {
                if (!updating.add(proxyGroup.id)) cancel()
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription!!
                val connected = SagerNet.started && DataStore.startedProfile > 0
                val userInterface = GroupManager.userInterface

                if (subscription.updateWhenConnectedOnly && !connected) {
                    if (!byUser || userInterface == null) {
                        finishUpdate(proxyGroup)
                        cancel()
                    } else {
                        if (!userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                            finishUpdate(proxyGroup)
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
                    }.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: Throwable) {
                    Logs.w(e)
                    if (byUser && userInterface != null) {
                        userInterface.onUpdateFailure(proxyGroup, e.readableMessage)
                    }
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}