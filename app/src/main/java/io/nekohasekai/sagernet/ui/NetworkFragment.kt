/******************************************************************************
 * Copyright (C) 2021 by nekohasekai <contact-git@sekai.icu>                  *
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

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.warp.enrollMasqueDevice
import io.nekohasekai.sagernet.fmt.warp.enrollWireGuardDevice
import io.nekohasekai.sagernet.fmt.warp.warpAddresses
import io.nekohasekai.sagernet.ktx.*

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = bars.left,
                right = bars.right,
                bottom = bars.bottom + dp2px(64),
            )
            insets
        }
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }
        binding.probeCertificate.setOnClickListener {
            startActivity(Intent(requireContext(), ProbeCertActivity::class.java))
        }

        val warpButtons = listOf(binding.generateWarpMasque, binding.generateWarpWireGuard)
        binding.generateWarpMasque.setOnClickListener { clicked ->
            askToRegister(warpButtons, clicked as Button, R.string.warp_profile_masque) {
                enrollMasqueDevice(Build.MODEL)
            }
        }
        binding.generateWarpWireGuard.setOnClickListener { clicked ->
            askToRegister(warpButtons, clicked as Button, R.string.warp_profile_wireguard) {
                enrollWireGuardDevice()
            }
        }

    }

    /**
     * Registering creates an account with Cloudflare, so it is confirmed first
     * and the dialog is where their terms are accepted.
     */
    private fun askToRegister(
        buttons: List<Button>,
        clicked: Button,
        @StringRes profileName: Int,
        enroll: () -> AbstractBean,
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.warp_generate)
            .setMessage(R.string.warp_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                register(buttons, clicked, getString(profileName), enroll)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun register(
        buttons: List<Button>,
        clicked: Button,
        profileName: String,
        enroll: () -> AbstractBean,
    ) {
        val label = clicked.text
        for (button in buttons) {
            button.isEnabled = false
        }
        clicked.setText(R.string.warp_running)
        runOnDefaultDispatcher {
            val result = runCatching {
                enroll().also { bean ->
                    bean.name = profileName
                    ProfileManager.createProfile(DataStore.selectedGroupForImport(), bean)
                }
            }
            onMainDispatcher {
                if (!isAdded) return@onMainDispatcher
                for (button in buttons) {
                    button.isEnabled = true
                }
                clicked.text = label
                result.onSuccess { bean ->
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.warp_generate)
                        .setMessage(getString(R.string.warp_done, bean.warpAddresses().replace("\n", ", ")))
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
