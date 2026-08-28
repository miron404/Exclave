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

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.ktx.unwrapIDN

class MasqueSettingsActivity : ProfileSettingsActivity<MasqueBean>() {

    override fun createEntity() = MasqueBean()

    override fun MasqueBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverMasqueMode = mode
        DataStore.serverMasqueHTTP2Address = http2Address
        DataStore.serverPrivateKey = privateKey
        DataStore.serverMasqueEndpointPublicKey = endpointPublicKey
        DataStore.serverLocalAddress = localAddress
        DataStore.serverSNI = sni
        DataStore.serverMTU = mtu
        DataStore.serverMasqueKeepalivePeriod = keepalivePeriod
        DataStore.serverMasqueInitialPacketSize = initialPacketSize
        DataStore.serverAllowInsecure = allowInsecure
    }

    override fun MasqueBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress.unwrapIDN()
        serverPort = DataStore.serverPort
        mode = DataStore.serverMasqueMode
        http2Address = DataStore.serverMasqueHTTP2Address.unwrapIDN()
        privateKey = DataStore.serverPrivateKey
        endpointPublicKey = DataStore.serverMasqueEndpointPublicKey
        localAddress = DataStore.serverLocalAddress
        sni = DataStore.serverSNI
        mtu = DataStore.serverMTU
        keepalivePeriod = DataStore.serverMasqueKeepalivePeriod
        initialPacketSize = DataStore.serverMasqueInitialPacketSize
        allowInsecure = DataStore.serverAllowInsecure
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.masque_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
        findPreference<EditTextPreference>(Key.SERVER_MTU)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_MASQUE_KEEPALIVE_PERIOD)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }
        findPreference<EditTextPreference>(Key.SERVER_MASQUE_INITIAL_PACKET_SIZE)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        // The HTTP/2 endpoint is a different address than the QUIC one, so it is
        // only worth showing once that transport is selected.
        val modePreference = findPreference<ListPreference>(Key.SERVER_MASQUE_MODE)!!
        val http2AddressPreference = findPreference<EditTextPreference>(Key.SERVER_MASQUE_HTTP2_ADDRESS)!!
        http2AddressPreference.isVisible = modePreference.value == MasqueBean.MODE_HTTP2
        modePreference.setOnPreferenceChangeListener { _, newValue ->
            http2AddressPreference.isVisible = newValue == MasqueBean.MODE_HTTP2
            true
        }
    }

}
