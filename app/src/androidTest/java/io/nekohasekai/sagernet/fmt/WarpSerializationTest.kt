package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.masque.MASQUEBean
import io.nekohasekai.sagernet.fmt.warpmasque.WarpMasqueBean
import io.nekohasekai.sagernet.fmt.warpmasque.parseWarpMasque
import io.nekohasekai.sagernet.fmt.warpmasque.toUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarpSerializationTest {
    @Test
    fun currentBeanAndShareLinkPreserveTransportSettings() {
        val bean = warp()
        assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(bean.clone()))
        val imported = parseWarpMasque(bean.toUri())
        assertEquals(bean.privateKey, imported.privateKey)
        assertEquals(bean.localAddress, imported.localAddress)
        assertEquals(bean.http2Address, imported.http2Address)
        assertEquals(bean.mode, imported.mode)
        assertEquals(bean.http2PingPeriod, imported.http2PingPeriod)
        assertEquals(bean.tcpKeepalivePeriod, imported.tcpKeepalivePeriod)
    }

    @Test
    fun oldWarpBackupsAreRecognizedAndMissingSettingsGetDefaults() {
        for (version in 0..1) {
            val bean = warp()
            val output = ByteBufferOutput(256, -1)
            output.writeString(bean.serverAddress)
            output.writeInt(bean.serverPort)
            output.writeInt(version)
            output.writeString(bean.http2Address)
            output.writeString(bean.privateKey)
            output.writeString(bean.endpointPublicKey)
            output.writeString(bean.localAddress)
            output.writeString(bean.sni)
            output.writeString(bean.mode)
            output.writeInt(bean.mtu)
            output.writeInt(bean.keepalivePeriod)
            output.writeInt(bean.initialPacketSize)
            output.writeBoolean(bean.allowInsecure)
            if (version >= 1) output.writeInt(bean.http2PingPeriod)
            output.writeInt(2) // AbstractBean's extra fields.
            output.writeString(bean.name)
            output.writeInt(0)
            val restored = ProxyEntity(type = 31).apply { putByteArray(output.toBytes()) }
            assertEquals(ProxyEntity.TYPE_WARP_MASQUE, restored.type)
            assertEquals(bean.privateKey, restored.warpMasqueBean!!.privateKey)
            assertEquals(if (version == 0) 0 else 17, restored.warpMasqueBean!!.http2PingPeriod.toInt())
            assertEquals(240, restored.warpMasqueBean!!.tcpKeepalivePeriod.toInt())
        }
    }

    @Test
    fun upstreamMasqueAndMalformedBackupsAreNotMisclassified() {
        val upstream = MASQUEBean().apply { initializeDefaultValues(); username = "user" }
        val bytes = KryoConverters.serialize(upstream)
        assertFalse(WarpMasqueBean.isSerialized(bytes))
        val restored = ProxyEntity(type = ProxyEntity.TYPE_MASQUE).apply { putByteArray(bytes) }
        assertEquals(ProxyEntity.TYPE_MASQUE, restored.type)
        assertEquals("user", restored.masqueBean!!.username)
        assertFalse(WarpMasqueBean.isSerialized(byteArrayOf()))
        assertFalse(WarpMasqueBean.isSerialized(KryoConverters.serialize(warp()).dropLast(4).toByteArray()))
    }

    private fun warp() = WarpMasqueBean().apply {
        initializeDefaultValues()
        name = "WARP test"
        serverAddress = "192.0.2.1"
        http2Address = "192.0.2.2"
        privateKey = "test-private-key"
        endpointPublicKey = "test-endpoint-key"
        localAddress = "172.16.0.2\n2606:4700:110::2"
        mode = WarpMasqueBean.MODE_HTTP2
        http2PingPeriod = 17
        tcpKeepalivePeriod = 0
    }
}
