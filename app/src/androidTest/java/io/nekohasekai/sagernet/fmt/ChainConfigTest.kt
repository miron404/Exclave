package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.BalancerBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChainConfigTest {
    private val groups = mutableListOf<Long>()

    @After
    fun cleanUp() {
        groups.forEach {
            SagerDatabase.proxyDao.deleteAll(it)
            SagerDatabase.groupDao.deleteById(it)
        }
    }

    @Test
    fun groupBalancerUsesBothLandingAndFrontForEveryCandidate() {
        val endpoints = group()
        val candidates = group()
        val front = socks(endpoints, "192.0.2.1")
        val landing = socks(endpoints, "192.0.2.2")
        val first = socks(candidates, "192.0.2.3")
        val second = socks(candidates, "192.0.2.4")
        candidates.frontProxy = front.id
        candidates.landingProxy = landing.id
        SagerDatabase.groupDao.updateGroup(candidates)
        val balancer = ProxyEntity(groupId = endpoints.id).apply {
            putBean(BalancerBean().apply {
                initializeDefaultValues()
                type = BalancerBean.TYPE_GROUP
                groupId = candidates.id
                useFrontProxy = true
                useLandingProxy = true
                strategy = "random"
            })
        }
        val config = JsonParser.parseString(buildV2RayConfig(balancer, forTest = true).config).asJsonObject
        val outbounds = config.getAsJsonArray("outbounds").associate {
            it.asJsonObject.get("tag").asString to it.asJsonObject
        }
        val selector = config.getAsJsonObject("routing").getAsJsonArray("balancers")
            .first { it.asJsonObject.get("tag").asString == "balancer-proxy" }
            .asJsonObject.getAsJsonArray("selector").map { it.asString }
        val selected = outbounds.filterKeys { tag -> selector.any { tag.startsWith(it) } }
        // A prefix must not select inner hops, or the chain can be bypassed.
        assertEquals(2, selected.size)
        val paths = selected.values.map { head ->
            val path = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            var current: JsonObject? = head
            while (current != null) {
                val outbound = current
                assertTrue("Cycle in outbound chain", seen.add(outbound.get("tag").asString))
                path += outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0]
                    .asJsonObject.get("address").asString
                current = outbound.getAsJsonObject("proxySettings")?.get("tag")?.asString?.let {
                    checkNotNull(outbounds[it]) { "Missing outbound $it" }
                }
            }
            path
        }.toSet()
        assertEquals(setOf(
            listOf(landing.socksBean!!.serverAddress, first.socksBean!!.serverAddress, front.socksBean!!.serverAddress),
            listOf(landing.socksBean!!.serverAddress, second.socksBean!!.serverAddress, front.socksBean!!.serverAddress),
        ), paths)
    }

    @Test
    fun selectorForFiveDoesNotSelectFiftyOrInnerHops() {
        val head = chainOutboundTag(TAG_AGENT, 5)
        assertFalse(chainOutboundTag(TAG_AGENT, 50).startsWith(head))
        assertFalse(hopOutboundTag(head, 7).startsWith(head))
        assertFalse(globalOutboundTag(50).startsWith(globalOutboundTag(5)))
        assertEquals(5L, profileIdOfOutboundTag(head))
    }

    private fun group() = ProxyGroup(name = "Chain regression test").apply {
        id = SagerDatabase.groupDao.createGroup(this)
        groups.add(id)
    }

    private fun socks(group: ProxyGroup, address: String) = ProxyEntity(groupId = group.id).apply {
        putBean(SOCKSBean().apply { initializeDefaultValues(); serverAddress = address })
        id = SagerDatabase.proxyDao.addProxy(this)
    }
}
