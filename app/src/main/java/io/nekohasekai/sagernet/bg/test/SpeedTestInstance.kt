/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  miron404                                               *
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
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.test

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.parseJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import speedtest.Speedtest
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * What a speed test measured: [bytes] arrived over [elapsedMillis], counted
 * from the first byte, after [connectMillis] to get the response started.
 */
class SpeedTestResult(val bytes: Long, val elapsedMillis: Long, val connectMillis: Long) {
    val bytesPerSecond get() = bytes * 1000 / elapsedMillis.coerceAtLeast(1)
}

/**
 * A test instance that downloads through the profile, the way the profile
 * carries traffic when it runs, chain and the front and landing proxies of
 * its group included. libexclavecore keeps the dialer of an instance to
 * itself, so the download comes in through an ipc inbound on a socket of its
 * own, which the speedtest package of the core dials.
 */
class SpeedTestInstance(profile: ProxyEntity) : V2RayTestInstance(profile, "", 0, protectPath()) {

    companion object {
        private val sockets = AtomicInteger()

        // Like the URL test: with the system TUN the instance has to keep its
        // sockets out of the VPN itself.
        private fun protectPath() = if (DataStore.tunImplementation == TunImplementation.SYSTEM && DataStore.serviceMode == Key.MODE_VPN && SagerNet.started && DataStore.startedProfile > 0) {
            SagerNet.deviceStorage.noBackupFilesDir.toString() + "/protect_path"
        } else ""
    }

    private val socket = File(SagerNet.deviceStorage.noBackupFilesDir, "speedtest_${sockets.incrementAndGet()}.sock")

    override fun loadConfig() {
        val json = parseJson(config.config).asJsonObject
        val inbounds = json.getAsJsonArray("inbounds") ?: JsonArray().also { json.add("inbounds", it) }
        socket.delete()
        inbounds.add(JsonObject().apply {
            addProperty("tag", "speedtest-in")
            addProperty("protocol", "ipc")
            addProperty("listen", socket.path)
        })
        v2rayPoint.loadConfig(json.toString())
    }

    /**
     * Cancel the caller and the download stops, but this returns only once
     * the work on the instance is over, so that closing the instance after
     * it never races starting it.
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun doSpeedTest(
        link: String,
        maxBytes: Long,
        connections: Int,
        connectTimeout: Int,
        duration: Int,
        onProgress: (bytes: Long, elapsedMillis: Long) -> Unit,
    ): SpeedTestResult {
        // Canceled before it runs, a test returns at once when it does.
        val test = Speedtest.newTest(socket.path)
        val processError = AtomicReference<Throwable>()
        processes = GuardedProcessPool {
            Logs.w(it)
            processError.set(it)
            test.cancel()
        }
        val work = GlobalScope.async(Dispatchers.Default) {
            init()
            launch()
            if (pluginConfigs.isNotEmpty()) {
                delay(500L)
            }
            val result = try {
                test.run(link, maxBytes, connections, connectTimeout, duration) { bytes, elapsedMillis ->
                    onProgress(bytes, elapsedMillis)
                }
            } catch (e: Exception) {
                throw processError.get() ?: e
            }
            SpeedTestResult(result.bytes, result.elapsedMillis, result.connectMillis)
        }
        try {
            return work.await()
        } catch (e: CancellationException) {
            test.cancel()
            withContext(NonCancellable) { work.join() }
            throw e
        }
    }

    override fun close() {
        super.close()
        socket.delete()
    }
}
