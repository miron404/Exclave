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

package io.nekohasekai.sagernet.fmt.http

import io.nekohasekai.sagernet.ktx.listByLineOrComma
import io.nekohasekai.sagernet.ktx.queryParameter
import libexclavecore.Libexclavecore

fun parseHttp(link: String): HttpBean {
    val url = Libexclavecore.parseURL(link)
    if (url.path != "/" && url.path != "") error("Not http proxy")

    return HttpBean().apply {
        serverAddress = url.host
        serverPort = when {
            !url.hasPort() -> if (url.scheme == "https") 443 else 80
            else -> url.port
        }
        username = url.username
        password = url.password
        name = url.fragment
        connectUDP = url.queryParameter("connectUDP").isEnabled()
        uriTemplate = url.queryParameter("uriTemplate")
        if (url.scheme == "https") {
            security = "tls"
            sni = url.queryParameter("sni")
            alpn = url.queryParameter("alpn")?.listByLineOrComma()?.joinToString("\n")
            // Prefer the canonical spelling if a link contains conflicting aliases.
            allowInsecure = (url.queryParameter("allowInsecure")
                ?: url.queryParameter("insecure")
                ?: url.queryParameter("allow_insecure")).isEnabled()
            utlsFingerprint = url.queryParameter("fp") ?: url.queryParameter("fingerprint")
            certificates = url.queryParameter("certificates")
            pinnedPeerCertificateSha256 = url.queryParameter("pcs")
                ?: url.queryParameter("pinnedPeerCertificateSha256")
            pinnedPeerCertificatePublicKeySha256 = url.queryParameter("pinnedPeerCertificatePublicKeySha256")
            pinnedPeerCertificateChainSha256 = url.queryParameter("pinnedPeerCertificateChainSha256")
            serverNameToVerify = url.queryParameter("vcn") ?: url.queryParameter("serverNameToVerify")
            echConfigList = url.queryParameter("ech")
            echQueryName = url.queryParameter("echQueryName")
            echEnabled = url.queryParameter("echEnabled")?.isEnabled()
                ?: (!echConfigList.isNullOrEmpty() || !echQueryName.isNullOrEmpty())
            mtlsCertificate = url.queryParameter("mtlsCertificate")
            mtlsCertificatePrivateKey = url.queryParameter("mtlsCertificatePrivateKey")
        }
    }
}

fun HttpBean.toUri(): String? {
    if (security != "tls" && security != "none") error("unsupported http with reality")
    if (type != "tcp" || headerType != "none") error("unsupported http with v2ray transport")

    val builder = Libexclavecore.newURL(if (security == "tls") "https" else "http").apply {
        setHostPort(serverAddress, serverPort)
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    if (username.isNotEmpty()) {
        builder.username = username
    }
    if (password.isNotEmpty()) {
        builder.password = password
    }

    // Query extensions are understood by this fork; other clients may ignore them.
    if (connectUDP) builder.addQueryParameter("connectUDP", "1")
    if (uriTemplate.isNotEmpty()) builder.addQueryParameter("uriTemplate", uriTemplate)
    if (security == "tls") {
        val parameters = mapOf(
            "sni" to sni,
            "alpn" to alpn.listByLineOrComma().joinToString(","),
            "fp" to utlsFingerprint,
            "certificates" to certificates,
            "pcs" to pinnedPeerCertificateSha256,
            "pinnedPeerCertificatePublicKeySha256" to pinnedPeerCertificatePublicKeySha256,
            "pinnedPeerCertificateChainSha256" to pinnedPeerCertificateChainSha256,
            "vcn" to serverNameToVerify,
            "ech" to echConfigList,
            "echQueryName" to echQueryName,
            "mtlsCertificate" to mtlsCertificate,
            "mtlsCertificatePrivateKey" to mtlsCertificatePrivateKey,
        )
        parameters.forEach { (key, value) ->
            if (value.isNotEmpty()) builder.addQueryParameter(key, value)
        }
        if (allowInsecure) builder.addQueryParameter("allowInsecure", "1")
        // Preserve disabled ECH even when the editor retains a config or query name.
        if (echEnabled || echConfigList.isNotEmpty() || echQueryName.isNotEmpty()) {
            builder.addQueryParameter("echEnabled", if (echEnabled) "1" else "0")
        }
    }

    return builder.string
}

private fun String?.isEnabled() = this == "1" || this.equals("true", ignoreCase = true)
