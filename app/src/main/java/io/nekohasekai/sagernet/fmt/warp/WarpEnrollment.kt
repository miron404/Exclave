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
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.fmt.warp

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.getArray
import io.nekohasekai.sagernet.ktx.getObject
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.parseJson
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64
import libexclavecore.Libexclavecore
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/**
 * Registers a device with Cloudflare, so that a WARP profile can be made on the
 * phone instead of being carried over from a tool run on a computer.
 *
 * Both flows are the same two calls: register a device, then amend the
 * registration with the key that is actually going to be used. They are written
 * against different references, [usque](https://github.com/Diniboy1123/usque)
 * for MASQUE and
 * [bash-warp-generator](https://github.com/ImMALWARE/bash-warp-generator) for
 * WireGuard, and those reached the API at different versions. Each is kept as
 * its reference has it, because the answers are not shaped alike: the older
 * version wraps everything in `result`.
 */

private const val MASQUE_API = "https://api.cloudflareclient.com/v0a4471"
private const val MASQUE_CLIENT_VERSION = "a-6.35-4471"
private const val MASQUE_USER_AGENT = "WARP for Android"

private const val WIREGUARD_API = "https://api.cloudflareclient.com/v0i1909051800"
private const val WIREGUARD_USER_AGENT = "okhttp/3.12.1"

/**
 * The endpoint WARP clients use for WireGuard. Cloudflare answers on several
 * ports; 500 is the one that survives the most networks, which is why the
 * reference picked it over the advertised 2408.
 */
private const val WIREGUARD_ENDPOINT_ADDRESS = "162.159.192.1"
private const val WIREGUARD_ENDPOINT_PORT = 500

/** The curve Cloudflare enrolls for MASQUE, as a DER object identifier body. */
private val PRIME256V1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

/**
 * Registers a MASQUE device. Everything the tunnel needs is handed out by the
 * second call; the HTTP/2 endpoint is not, and is left empty so that the built
 * in default applies.
 */
fun enrollMasqueDevice(deviceName: String): MasqueBean {
    val keyPair = generateDeviceKey()

    val registration = callAPI(
        "POST", "$MASQUE_API/reg", MASQUE_USER_AGENT,
        clientVersion = MASQUE_CLIENT_VERSION,
        body = JsonObject().apply {
            addProperty("key", Base64.Default.encode(randomBytes(32)))
            addProperty("install_id", "")
            addProperty("fcm_token", "")
            addProperty("tos", cloudflareTime(withMillis = true))
            addProperty("model", "PC")
            addProperty("serial_number", randomBytes(8).toHex())
            addProperty("os_version", "")
            // Registration only accepts a WireGuard key, so one is made up here
            // and never used.
            addProperty("key_type", "curve25519")
            addProperty("tunnel_type", "wireguard")
            addProperty("locale", "en_US")
        },
    )
    val id = registration.getString("id")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not name a device")
    val token = registration.getString("token")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not return an access token")

    val account = callAPI(
        "PATCH", "$MASQUE_API/reg/$id", MASQUE_USER_AGENT,
        clientVersion = MASQUE_CLIENT_VERSION,
        token = token,
        body = JsonObject().apply {
            addProperty("key", Base64.Default.encode(keyPair.public.encoded))
            addProperty("key_type", "secp256r1")
            addProperty("tunnel_type", "masque")
            if (deviceName.isNotEmpty()) {
                addProperty("name", deviceName)
            }
        },
    )
    val assigned = readAssignment(account)

    return MasqueBean().applyDefaultValues().apply {
        // A v4 endpoint is reachable from a dual stack network and from a v6
        // only one alike, which is why usque prefers it too.
        serverAddress = assigned.endpointV4 ?: assigned.endpointV6
            ?: error("the enrollment returned no endpoint address")
        privateKey = Base64.Default.encode(sec1PrivateKey(keyPair))
        endpointPublicKey = assigned.peerPublicKey
        localAddress = assigned.localAddress
    }
}

/**
 * Registers a WireGuard device. The second call only flips `warp_enabled`: the
 * key that matters was already sent with the registration, because for
 * WireGuard it is the kind the API takes by default.
 */
fun enrollWireGuardDevice(): WireGuardBean {
    val deviceKey = X25519PrivateKeyParameters(SecureRandom())
    val devicePublicKey = deviceKey.generatePublicKey()

    val registration = callAPI(
        "POST", "$WIREGUARD_API/reg", WIREGUARD_USER_AGENT,
        body = JsonObject().apply {
            addProperty("install_id", "")
            addProperty("tos", cloudflareTime(withMillis = false))
            addProperty("key", Base64.Default.encode(devicePublicKey.encoded))
            addProperty("fcm_token", "")
            addProperty("type", "ios")
            addProperty("locale", "en_US")
        },
    ).result()
    val id = registration.getString("id")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not name a device")
    val token = registration.getString("token")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not return an access token")

    val account = callAPI(
        "PATCH", "$WIREGUARD_API/reg/$id", WIREGUARD_USER_AGENT,
        token = token,
        body = JsonObject().apply { addProperty("warp_enabled", true) },
    ).result()
    val assigned = readAssignment(account)

    return WireGuardBean().applyDefaultValues().apply {
        serverAddress = WIREGUARD_ENDPOINT_ADDRESS
        serverPort = WIREGUARD_ENDPOINT_PORT
        privateKey = Base64.Default.encode(deviceKey.encoded)
        peerPublicKey = assigned.peerPublicKey
        localAddress = assigned.localAddress
        // What WARP itself runs at. The WireGuard default of 1420 does not fit
        // Cloudflare's path and costs a round of fragmentation.
        mtu = 1280
    }
}

/**
 * The addresses the device was assigned, for showing a registration back. The
 * two beans have no ancestor that knows about them.
 */
fun AbstractBean.warpAddresses(): String = when (this) {
    is MasqueBean -> localAddress
    is WireGuardBean -> localAddress
    else -> ""
}

/** The endpoint, its key and the addresses assigned to this device. */
private class Assignment(
    val peerPublicKey: String,
    val endpointV4: String?,
    val endpointV6: String?,
    val localAddress: String,
)

private fun readAssignment(account: JsonObject): Assignment {
    val config = account.getObject("config") ?: error("the enrollment returned no configuration")
    val peer = config.getArray("peers")?.firstOrNull() ?: error("the enrollment returned no endpoint")
    val endpoint = peer.getObject("endpoint") ?: error("the enrollment returned no endpoint address")
    val addresses = config.getObject("interface")?.getObject("addresses")
        ?: error("the enrollment assigned no address")
    val localAddress = listOfNotNull(
        addresses.getString("v4")?.takeIf { it.isNotEmpty() },
        addresses.getString("v6")?.takeIf { it.isNotEmpty() },
    ).joinToString("\n")
    if (localAddress.isEmpty()) error("the enrollment assigned no address")
    return Assignment(
        peerPublicKey = peer.getString("public_key")?.takeIf { it.isNotEmpty() }
            ?: error("the enrollment returned no endpoint key"),
        endpointV4 = hostOf(endpoint.getString("v4")),
        endpointV6 = hostOf(endpoint.getString("v6")),
        localAddress = localAddress,
    )
}

/** Unwraps the envelope the older API version answers with. */
private fun JsonObject.result(): JsonObject =
    getObject("result") ?: error("the enrollment API answered without a result")

/**
 * Runs one API call. When the VPN is up the call goes through it, the way every
 * other network access in the app does, so that registering works from a
 * network where the API itself is not reachable.
 */
private fun callAPI(
    method: String,
    url: String,
    userAgent: String,
    body: JsonObject,
    token: String? = null,
    clientVersion: String? = null,
): JsonObject {
    val response = Libexclavecore.newHttpClient().apply {
        if (SagerNet.started && DataStore.startedProfile > 0) {
            useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
        }
    }.newRequest().apply {
        setURL(url)
        setMethod(method)
        setUserAgent(userAgent)
        setHeader("Content-Type", "application/json; charset=UTF-8")
        if (clientVersion != null) {
            setHeader("CF-Client-Version", clientVersion)
        }
        if (token != null) {
            setHeader("Authorization", "Bearer $token")
        }
        setContentString(body.toString())
    }.execute()
    return runCatching { parseJson(response.contentString) }.getOrNull()
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?: error("the enrollment API answered with something other than JSON")
}

private fun generateDeviceKey(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
    initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
}.generateKeyPair()

/**
 * Encodes the key the way RFC 5915 describes, which is what Go's
 * `x509.ParseECPrivateKey` reads and therefore what both this fork's outbound
 * and usque expect. Android hands out PKCS#8 instead, and the structure nested
 * inside it leaves the curve out, so the key is re-encoded rather than
 * unwrapped.
 */
private fun sec1PrivateKey(keyPair: KeyPair): ByteArray {
    val private = keyPair.private as ECPrivateKey
    val public = keyPair.public as ECPublicKey
    val point = byteArrayOf(0x04) + fixedWidth(public.w.affineX) + fixedWidth(public.w.affineY)
    return derValue(
        0x30,
        derValue(0x02, byteArrayOf(0x01)) +
            derValue(0x04, fixedWidth(private.s)) +
            derValue(0xA0, derValue(0x06, PRIME256V1)) +
            derValue(0xA1, derValue(0x03, byteArrayOf(0x00) + point)),
    )
}

private fun derValue(tag: Int, content: ByteArray): ByteArray {
    val length = when {
        content.size < 0x80 -> byteArrayOf(content.size.toByte())
        content.size < 0x100 -> byteArrayOf(0x81.toByte(), content.size.toByte())
        else -> byteArrayOf(0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte())
    }
    return byteArrayOf(tag.toByte()) + length + content
}

/** The 32 byte big endian form the curve uses, without the sign byte Java adds. */
private fun fixedWidth(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    val padded = ByteArray(32)
    val taken = minOf(32, bytes.size)
    bytes.copyInto(padded, 32 - taken, bytes.size - taken, bytes.size)
    return padded
}

/** Strips the always zero port Cloudflare reports its endpoints with. */
private fun hostOf(endpoint: String?): String? {
    val trimmed = endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.startsWith("[")) {
        val closing = trimmed.indexOf(']')
        return if (closing > 1) trimmed.substring(1, closing) else null
    }
    return trimmed.substringBeforeLast(':').takeIf { it.isNotEmpty() }
}

private fun randomBytes(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

private fun cloudflareTime(withMillis: Boolean): String {
    val pattern = if (withMillis) "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ss'Z'"
    return SimpleDateFormat(pattern, Locale.US).apply {
        if (!withMillis) timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
