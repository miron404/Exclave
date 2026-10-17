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

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.warpmasque.WarpMasqueBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class WarpMasqueSettingsActivity : ProfileSettingsActivity<WarpMasqueBean>() {

    override fun createEntity() = WarpMasqueBean()

    override fun WarpMasqueBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverWarpMasqueMode = mode
        DataStore.serverWarpMasqueHTTP2Address = http2Address
        DataStore.serverPrivateKey = privateKey
        DataStore.serverWarpMasqueEndpointPublicKey = endpointPublicKey
        DataStore.serverLocalAddress = localAddress
        DataStore.serverSNI = sni
        DataStore.serverMTU = mtu
        DataStore.serverWarpMasqueKeepalivePeriod = keepalivePeriod
        DataStore.serverWarpMasqueInitialPacketSize = initialPacketSize
        DataStore.serverWarpMasqueHTTP2PingPeriod = http2PingPeriod
        DataStore.serverWarpMasqueTCPKeepalivePeriod = tcpKeepalivePeriod
        DataStore.serverAllowInsecure = allowInsecure
    }

    override fun WarpMasqueBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        mode = DataStore.serverWarpMasqueMode
        http2Address = DataStore.serverWarpMasqueHTTP2Address.unwrapIDN()
        privateKey = DataStore.serverPrivateKey
        endpointPublicKey = DataStore.serverWarpMasqueEndpointPublicKey
        localAddress = DataStore.serverLocalAddress
        sni = DataStore.serverSNI
        mtu = DataStore.serverMTU
        keepalivePeriod = DataStore.serverWarpMasqueKeepalivePeriod
        initialPacketSize = DataStore.serverWarpMasqueInitialPacketSize
        http2PingPeriod = DataStore.serverWarpMasqueHTTP2PingPeriod
        tcpKeepalivePeriod = DataStore.serverWarpMasqueTCPKeepalivePeriod
        allowInsecure = DataStore.serverAllowInsecure
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.warp_masque_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_MTU)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_WARP_MASQUE_KEEPALIVE_PERIOD)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_WARP_MASQUE_INITIAL_PACKET_SIZE)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_WARP_MASQUE_HTTP2_PING_PERIOD)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_WARP_MASQUE_TCP_KEEPALIVE_PERIOD)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        // Every transport setting below belongs to exactly one of the two: the
        // keepalive and the initial packet size only ever reach quic-go, the
        // HTTP/2 endpoint, its liveness check and its TCP keepalive only the
        // HTTP/2 transport.
        // Showing the other set would offer settings that quietly do nothing.
        val modePreference = findPreference<ListPreference>(Key.SERVER_WARP_MASQUE_MODE)!!
        val quicOnly = listOf(
            findPreference<Preference>(Key.SERVER_WARP_MASQUE_KEEPALIVE_PERIOD)!!,
            findPreference<Preference>(Key.SERVER_WARP_MASQUE_INITIAL_PACKET_SIZE)!!,
        )
        val http2Only = listOf(
            findPreference<Preference>(Key.SERVER_WARP_MASQUE_HTTP2_ADDRESS)!!,
            findPreference<Preference>(Key.SERVER_WARP_MASQUE_HTTP2_PING_PERIOD)!!,
            findPreference<Preference>(Key.SERVER_WARP_MASQUE_TCP_KEEPALIVE_PERIOD)!!,
        )
        fun showModeOf(value: Any?) {
            val http2 = value == WarpMasqueBean.MODE_HTTP2
            for (preference in quicOnly) preference.isVisible = !http2
            for (preference in http2Only) preference.isVisible = http2
        }
        showModeOf(modePreference.value)
        modePreference.setOnPreferenceChangeListener { _, newValue ->
            showModeOf(newValue)
            true
        }
    }

}
