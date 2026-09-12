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

package io.nekohasekai.sagernet.fmt.masque

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
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
import kotlin.io.encoding.Base64
import libexclavecore.Libexclavecore

/**
 * Enrolls a device with Cloudflare, so that a MASQUE profile can be created on
 * the phone instead of being carried over from a usque `config.json`.
 *
 * The exchange follows usque's `register` command, which is where Cloudflare's
 * undocumented API was worked out: a device is registered with a throwaway
 * WireGuard key, then the registration is amended with the MASQUE key that is
 * actually used. The account is the ordinary free WARP one the official client
 * creates.
 */

private const val API_URL = "https://api.cloudflareclient.com/v0a4471"
private const val CLIENT_VERSION = "a-6.35-4471"
private const val USER_AGENT = "WARP for Android"

/** The curve Cloudflare enrolls, as a DER object identifier body. */
private val PRIME256V1 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)

/**
 * Registers a device and returns the profile it describes. Everything the
 * tunnel needs is handed out by the second call; the HTTP/2 endpoint is not,
 * and is left empty so that the built in default applies.
 */
fun enrollMasqueDevice(deviceName: String): MasqueBean {
    val keyPair = generateDeviceKey()

    val registration = callAPI("POST", "/reg", JsonObject().apply {
        addProperty("key", Base64.Default.encode(randomBytes(32)))
        addProperty("install_id", "")
        addProperty("fcm_token", "")
        addProperty("tos", cloudflareTime())
        addProperty("model", "PC")
        addProperty("serial_number", randomBytes(8).toHex())
        addProperty("os_version", "")
        // The first call enrolls a WireGuard key because that is the only kind
        // registration accepts. It is never used.
        addProperty("key_type", "curve25519")
        addProperty("tunnel_type", "wireguard")
        addProperty("locale", "en_US")
    })
    val id = registration.getString("id")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not name a device")
    val token = registration.getString("token")?.takeIf { it.isNotEmpty() }
        ?: error("the registration did not return an access token")

    val account = callAPI("PATCH", "/reg/$id", JsonObject().apply {
        addProperty("key", Base64.Default.encode(keyPair.public.encoded))
        addProperty("key_type", "secp256r1")
        addProperty("tunnel_type", "masque")
        if (deviceName.isNotEmpty()) {
            addProperty("name", deviceName)
        }
    }, token)

    val config = account.getObject("config") ?: error("the enrollment returned no configuration")
    val peer = config.getArray("peers")?.firstOrNull() ?: error("the enrollment returned no endpoint")
    val endpoint = peer.getObject("endpoint") ?: error("the enrollment returned no endpoint address")
    val addresses = config.getObject("interface")?.getObject("addresses")
        ?: error("the enrollment assigned no address")

    return MasqueBean().applyDefaultValues().apply {
        // A v4 endpoint is reachable from a dual stack network and from a v6
        // only one alike, which is why usque prefers it too.
        serverAddress = hostOf(endpoint.getString("v4"))
            ?: hostOf(endpoint.getString("v6"))
            ?: error("the enrollment returned no endpoint address")
        privateKey = Base64.Default.encode(sec1PrivateKey(keyPair))
        endpointPublicKey = peer.getString("public_key")?.takeIf { it.isNotEmpty() }
            ?: error("the enrollment returned no endpoint key")
        localAddress = listOfNotNull(
            addresses.getString("v4")?.takeIf { it.isNotEmpty() },
            addresses.getString("v6")?.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        if (localAddress.isEmpty()) error("the enrollment assigned no address")
    }
}

/**
 * Runs one API call. When the VPN is up the call goes through it, the way every
 * other network access in the app does, so that enrolling works from a network
 * where the API itself is not reachable.
 */
private fun callAPI(method: String, path: String, body: JsonObject, token: String? = null): JsonObject {
    val response = Libexclavecore.newHttpClient().apply {
        if (SagerNet.started && DataStore.startedProfile > 0) {
            useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
        }
    }.newRequest().apply {
        setURL(API_URL + path)
        setMethod(method)
        setUserAgent(USER_AGENT)
        setHeader("CF-Client-Version", CLIENT_VERSION)
        setHeader("Content-Type", "application/json; charset=UTF-8")
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

private fun cloudflareTime(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
