package io.nekohasekai.sagernet.fmt

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.http.toUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpShareTest {
    @Test
    fun httpsSettingsSurviveSharing() {
        val original = HttpBean().apply {
            initializeDefaultValues()
            serverAddress = "2001:db8::1"
            serverPort = 8443
            name = "HTTPS # тест"
            username = "user@name"
            password = "p&ss:word+#"
            security = "tls"
            sni = "tls.example"
            alpn = "h2\nhttp/1.1"
            allowInsecure = true
            utlsFingerprint = "chrome"
            certificates = "-----BEGIN CERTIFICATE-----\nabc+/=\n-----END CERTIFICATE-----"
            pinnedPeerCertificateSha256 = "ab".repeat(32)
            pinnedPeerCertificatePublicKeySha256 = "cd".repeat(32)
            pinnedPeerCertificateChainSha256 = "ef".repeat(32)
            serverNameToVerify = "verify.example\nother.example"
            echEnabled = true
            echConfigList = "abc+/="
            echQueryName = "ech.example"
            mtlsCertificate = certificates
            mtlsCertificatePrivateKey = "-----BEGIN PRIVATE KEY-----\nabc+/=\n-----END PRIVATE KEY-----"
            connectUDP = true
            uriTemplate = "https://proxy.example/udp/{target_host}/{target_port}/"
        }
        val restored = parseHttp(original.toUri()!!).apply { initializeDefaultValues() }
        assertArrayEquals(KryoConverters.serialize(original), KryoConverters.serialize(restored))
    }

    @Test
    fun insecureAliasesAndExplicitFalseAreRespected() {
        for (key in listOf("allowInsecure", "insecure", "allow_insecure")) {
            for (value in listOf("1", "true", "TRUE")) {
                assertTrue(parseHttp("https://proxy.example?$key=$value").allowInsecure)
            }
            for (value in listOf("0", "false", "", "unexpected")) {
                assertFalse(parseHttp("https://proxy.example?$key=$value").allowInsecure)
            }
        }
        assertFalse(parseHttp("https://proxy.example?allowInsecure=0&insecure=1").allowInsecure)
    }

    @Test
    fun oldLinksKeepTheirDefaultsAndHttpIgnoresTlsExtensions() {
        val https = parseHttp("https://user:pass@proxy.example#name").apply { initializeDefaultValues() }
        assertEquals(443, https.serverPort.toInt())
        assertEquals("tls", https.security)
        assertFalse(https.allowInsecure)
        assertEquals("", https.alpn)
        assertEquals("", https.sni)
        val http = parseHttp("http://proxy.example?allowInsecure=1&sni=tls.example&alpn=h2")
            .apply { initializeDefaultValues() }
        assertEquals(80, http.serverPort.toInt())
        assertEquals("none", http.security)
        assertFalse(http.allowInsecure)
        assertEquals("", http.sni)
        assertEquals("", http.alpn)
        assertFalse(http.toUri()!!.contains("?"))
    }

    @Test
    fun echDisabledStateAndAlpnOrderSurviveSharing() {
        val bean = parseHttp("https://proxy.example?alpn=http%2F1.1,h2&ech=abc%2B%2F%3D&echEnabled=0")
            .apply { initializeDefaultValues() }
        val restored = parseHttp(bean.toUri()!!).apply { initializeDefaultValues() }
        assertEquals("http/1.1\nh2", restored.alpn)
        assertFalse(restored.echEnabled)
        assertEquals("abc+/=", restored.echConfigList)
    }
}
