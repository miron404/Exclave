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

package io.nekohasekai.sagernet.fmt.masque

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.getBoolean
import io.nekohasekai.sagernet.ktx.getInt
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.parseJson
import kotlin.io.encoding.Base64

/**
 * MASQUE profiles are described by the enrolled device material rather than by a
 * server address alone, so the share link carries a JSON document instead of a
 * URL query. The document is a superset of usque's `config.json`, which lets the
 * very same parser accept a config file pasted as is.
 */

private const val MASQUE_SCHEME = "masque://"

fun isMasqueConfigJson(text: String): Boolean {
    val trimmed = text.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
    val json = runCatching { parseJson(trimmed) }.getOrNull() ?: return false
    if (!json.isJsonObject) return false
    val obj = json.asJsonObject
    return obj.getString("private_key") != null &&
        (obj.getString("endpoint_v4") != null || obj.getString("endpoint_v6") != null)
}

fun parseMasque(text: String): MasqueBean {
    val trimmed = text.trim()
    if (!trimmed.startsWith(MASQUE_SCHEME, ignoreCase = true)) {
        return parseMasqueConfigJson(trimmed)
    }
    var payload = trimmed.substring(MASQUE_SCHEME.length)
    var name = ""
    val fragment = payload.indexOf('#')
    if (fragment != -1) {
        name = java.net.URLDecoder.decode(payload.substring(fragment + 1), "UTF-8")
        payload = payload.substring(0, fragment)
    }
    payload = payload.trim('/')
    val json = String(
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload)
    )
    return parseMasqueConfigJson(json).apply {
        if (name.isNotEmpty()) {
            this.name = name
        }
    }
}

/**
 * Reads a usque `config.json`. `endpoint_v4` is preferred over `endpoint_v6`
 * because a v4 endpoint works on dual stack and v6 only networks alike.
 */
fun parseMasqueConfigJson(text: String): MasqueBean {
    val json = runCatching { parseJson(text.trim()) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        ?: error("invalid masque configuration")
    return MasqueBean().applyDefaultValues().apply {
        json.getString("name")?.takeIf { it.isNotEmpty() }?.also { name = it }
        serverAddress = json.getString("endpoint_v4")?.takeIf { it.isNotEmpty() }
            ?: json.getString("endpoint_v6")?.takeIf { it.isNotEmpty() }
            ?: error("missing endpoint address")
        json.getInt("port")?.takeIf { it in 1..65535 }?.also { serverPort = it }
        http2Address = json.getString("endpoint_h2_v4")?.takeIf { it.isNotEmpty() }
            ?: json.getString("endpoint_h2_v6")?.takeIf { it.isNotEmpty() }
            ?: ""
        privateKey = json.getString("private_key")?.takeIf { it.isNotEmpty() }
            ?: error("missing private key")
        endpointPublicKey = json.getString("endpoint_pub_key") ?: ""
        localAddress = listOfNotNull(
            json.getString("ipv4")?.takeIf { it.isNotEmpty() },
            json.getString("ipv6")?.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        if (localAddress.isEmpty()) error("missing assigned address")
        json.getString("sni")?.takeIf { it.isNotEmpty() }?.also { sni = it }
        when (json.getString("mode")) {
            MasqueBean.MODE_HTTP2 -> mode = MasqueBean.MODE_HTTP2
            MasqueBean.MODE_QUIC -> mode = MasqueBean.MODE_QUIC
        }
        json.getInt("mtu")?.takeIf { it > 0 }?.also { mtu = it }
        json.getInt("keepalive_period")?.takeIf { it >= 0 }?.also { keepalivePeriod = it }
        json.getInt("initial_packet_size")?.takeIf { it in 0..65535 }?.also { initialPacketSize = it }
        json.getBoolean("allow_insecure")?.also { allowInsecure = it }
        if (!allowInsecure && endpointPublicKey.isEmpty()) {
            error("missing endpoint public key")
        }
    }
}

fun MasqueBean.toUri(): String {
    val payload = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        .encode(toConfigJson().toString().toByteArray())
    val builder = StringBuilder(MASQUE_SCHEME).append(payload)
    if (name.isNotEmpty()) {
        builder.append('#').append(java.net.URLEncoder.encode(name, "UTF-8"))
    }
    return builder.toString()
}

/**
 * Renders the profile back into the usque `config.json` shape, so an exported
 * link can also be fed to usque itself.
 */
fun MasqueBean.toConfigJson(): JsonObject = JsonObject().apply {
    addProperty("private_key", privateKey)
    if (serverAddress.contains(":")) {
        addProperty("endpoint_v6", serverAddress)
    } else {
        addProperty("endpoint_v4", serverAddress)
    }
    if (http2Address.isNotEmpty()) {
        if (http2Address.contains(":")) {
            addProperty("endpoint_h2_v6", http2Address)
        } else {
            addProperty("endpoint_h2_v4", http2Address)
        }
    }
    addProperty("endpoint_pub_key", endpointPublicKey)
    for (address in localAddress.listByLineOrComma()) {
        if (address.contains(":")) {
            addProperty("ipv6", address)
        } else {
            addProperty("ipv4", address)
        }
    }
    if (serverPort != 443) {
        addProperty("port", serverPort)
    }
    if (sni != MasqueBean.DEFAULT_SNI) {
        addProperty("sni", sni)
    }
    if (mode != MasqueBean.MODE_QUIC) {
        addProperty("mode", mode)
    }
    if (mtu != 1280) {
        addProperty("mtu", mtu)
    }
    if (keepalivePeriod != 30) {
        addProperty("keepalive_period", keepalivePeriod)
    }
    if (initialPacketSize != 0) {
        addProperty("initial_packet_size", initialPacketSize)
    }
    if (allowInsecure) {
        addProperty("allow_insecure", true)
    }
}
