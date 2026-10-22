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

package io.nekohasekai.sagernet.bg.test

import android.net.Network
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.bg.LocalResolver
import io.nekohasekai.sagernet.bg.proto.V2RayInstance
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.delay
import libexclavecore.Libexclavecore
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

class V2RayTestInstance(profile: ProxyEntity, val link: String, val timeout: Int, val protectPath: String = "") : V2RayInstance(
    profile,
), LocalResolver {
    lateinit var continuation: Continuation<Int>
    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            continuation = c
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                try {
                    init()
                    launch()
                    if (pluginConfigs.isNotEmpty()) {
                        delay(500L)
                    }
                    c.tryResume(Libexclavecore.urlTest(v2rayPoint, "", link, timeout))
                } catch (e: Exception) {
                    c.tryResumeWithException(e)
                }
            }
        }
    }

    @Volatile
    private var listenedNetwork: Network? = null

    companion object {
        /** The network the latest test to listen heard of. */
        @Volatile
        private var latestNetwork: Network? = null
    }

    // libexclavecore makes the system dialer once, with the resolver of the
    // first test that asks for one, and every later test in this process dials
    // through it. So a test that is over still answers lookups, and has to
    // answer them on the network the running tests see, not the one it last
    // heard of before a network change.
    override var underlyingNetwork: Network?
        get() = listenedNetwork ?: latestNetwork ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            SagerNet.connectivity.activeNetwork
        } else null
        set(value) {
            listenedNetwork = value
            latestNetwork = value
        }

    override suspend fun init() {
        super.init()
        v2rayPoint.withLocalResolver(this)
        v2rayPoint.withAlternativeSystemDialer(protectPath)
        DefaultNetworkListener.start(this) {
            underlyingNetwork = it
        }
    }

    override fun close() {
        runOnDefaultDispatcher {
            DefaultNetworkListener.stop(this)
            listenedNetwork = null
        }
        super.close()
    }

    override fun buildConfig() {
        config = buildV2RayConfig(profile, forTest = true)
    }
}