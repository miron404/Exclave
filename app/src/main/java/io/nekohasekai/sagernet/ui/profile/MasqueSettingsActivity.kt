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

import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.FrameLayout
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.masque.enrollMasqueDevice
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
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
        DataStore.serverMasqueHTTP2PingPeriod = http2PingPeriod
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
        http2PingPeriod = DataStore.serverMasqueHTTP2PingPeriod
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
        findPreference<EditTextPreference>(Key.SERVER_MASQUE_HTTP2_PING_PERIOD)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        }

        findPreference<Preference>(Key.MASQUE_ENROLL)!!.setOnPreferenceClickListener {
            askToEnroll(it)
            true
        }

        // Every transport setting below belongs to exactly one of the two: the
        // keepalive and the initial packet size only ever reach quic-go, the
        // HTTP/2 endpoint and its liveness check only the HTTP/2 transport.
        // Showing the other set would offer settings that quietly do nothing.
        val modePreference = findPreference<ListPreference>(Key.SERVER_MASQUE_MODE)!!
        val quicOnly = listOf(
            findPreference<Preference>(Key.SERVER_MASQUE_KEEPALIVE_PERIOD)!!,
            findPreference<Preference>(Key.SERVER_MASQUE_INITIAL_PACKET_SIZE)!!,
        )
        val http2Only = listOf(
            findPreference<Preference>(Key.SERVER_MASQUE_HTTP2_ADDRESS)!!,
            findPreference<Preference>(Key.SERVER_MASQUE_HTTP2_PING_PERIOD)!!,
        )
        fun showModeOf(value: Any?) {
            val http2 = value == MasqueBean.MODE_HTTP2
            for (preference in quicOnly) preference.isVisible = !http2
            for (preference in http2Only) preference.isVisible = http2
        }
        showModeOf(modePreference.value)
        modePreference.setOnPreferenceChangeListener { _, newValue ->
            showModeOf(newValue)
            true
        }
    }

    /**
     * Enrolling replaces the device material of the profile being edited, which
     * is not something to do by a stray tap, so it is confirmed first. The
     * dialog is also where Cloudflare's terms are accepted, since registering
     * creates an account with them.
     */
    private fun PreferenceFragmentCompat.askToEnroll(trigger: Preference) {
        val context = requireContext()
        val field = EditText(context).apply {
            setSingleLine()
            hint = getString(R.string.masque_enroll_name)
            setText(Build.MODEL)
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.masque_enroll)
            .setMessage(R.string.masque_enroll_message)
            .setView(FrameLayout(context).apply {
                setPadding(dp2px(24), dp2px(8), dp2px(24), 0)
                addView(field)
            })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                enroll(trigger, field.text.toString().trim())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun PreferenceFragmentCompat.enroll(trigger: Preference, deviceName: String) {
        val summary = trigger.summary
        trigger.isEnabled = false
        trigger.setSummary(R.string.masque_enroll_running)
        runOnDefaultDispatcher {
            val result = runCatching { enrollMasqueDevice(deviceName) }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                trigger.isEnabled = true
                trigger.summary = summary
                result.onSuccess { bean ->
                    findPreference<EditTextPreference>(Key.SERVER_ADDRESS)!!.text = bean.serverAddress
                    findPreference<EditTextPreference>(Key.SERVER_PRIVATE_KEY)!!.text = bean.privateKey
                    findPreference<EditTextPreference>(Key.SERVER_MASQUE_ENDPOINT_PUBLIC_KEY)!!.text =
                        bean.endpointPublicKey
                    findPreference<EditTextPreference>(Key.SERVER_LOCAL_ADDRESS)!!.text = bean.localAddress
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.masque_enroll)
                        .setMessage(
                            getString(
                                R.string.masque_enroll_done,
                                bean.localAddress.replace("\n", ", "),
                            )
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.error_title)
                        .setMessage(error.readableMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

}
