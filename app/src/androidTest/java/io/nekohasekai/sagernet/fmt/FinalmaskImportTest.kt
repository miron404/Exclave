package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.group.RawUpdater
import libexclavecore.Libexclavecore
import kotlin.io.encoding.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinalmaskImportTest {
    private val fragment = """{"tcp":[{"type":"fragment","settings":{"length":"10-20","packets":"tlshello","interval":"10-20"}}]}"""
    private val publicKey = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(ByteArray(32) { 1 })

    private fun link(transport: String, security: String = "reality") =
        "vless://00000000-0000-4000-8000-000000000001@proxy.example:443" +
            "?encryption=none&type=$transport&security=$security&sni=tls.example&pbk=$publicKey" +
            (if (transport == "tcp") "&flow=xtls-rprx-vision" else "") +
            (if (transport == "xhttp") "&mode=stream-one&path=%2Ftunnel" else "") + "#Test"

    private fun masked(link: String, masks: String = fragment) = Libexclavecore.parseURL(link).apply {
        addQueryParameter("fm", masks)
    }.string

    @Test
    fun tcpAndXhttpImportExactlyAsWithoutFragmentation() {
        for (transport in listOf("tcp", "xhttp", "splithttp", "http", "grpc", "ws", "httpupgrade")) {
            val security = if (transport in listOf("ws", "httpupgrade")) "tls" else "reality"
            val plain = parseV2Ray(link(transport, security)).apply { initializeDefaultValues() }
            val imported = parseV2Ray(masked(link(transport, security))).apply { initializeDefaultValues() }
            assertArrayEquals(transport, KryoConverters.serialize(plain), KryoConverters.serialize(imported))
        }
    }

    @Test
    fun base64SubscriptionKeepsAllFragmentedEntries() {
        // Reproduce a subscription with both Vision TCP and REALITY XHTTP,
        // without including a user's subscription credentials in the fixture.
        val links = (1..25).map { i -> masked(link(if (i % 5 == 0) "xhttp" else "tcp")) }
        val parsed = RawUpdater.parseRaw(Base64.encode(links.joinToString("\n").toByteArray()))
        assertNotNull(parsed)
        assertEquals(25, parsed!!.size)
    }

    @Test
    fun xhttpStillRejectsUnsupportedUdpMasks() {
        val error = runCatching {
            parseV2Ray(masked(link("xhttp"), """{"udp":[{"type":"unsupported"}]}"""))
        }.exceptionOrNull()
        assertNotNull(error)
    }
}
