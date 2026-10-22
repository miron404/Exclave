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

package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.DialogInterface
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.test.SpeedTestInstance
import io.nekohasekai.sagernet.bg.test.SpeedTestResult
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressListBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.getColour
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.FormatFileSizeCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** A speed in megabits per second, the unit speed tests are quoted in. */
fun Context.formatSpeed(bytesPerSecond: Long): String {
    val mbps = bytesPerSecond * 8 / 1e6
    val pattern = when {
        mbps >= 100 -> "%.0f"
        mbps >= 10 -> "%.1f"
        else -> "%.2f"
    }
    return getString(R.string.speed_test_mbps, String.format(Locale.getDefault(), pattern, mbps))
}

/**
 * Runs the speed test on profiles one after another, or as many at once as
 * the settings allow, and shows how each is doing. The results go to the
 * profiles, for the list to show and sort by.
 */
class SpeedTestDialog(private val fragment: Fragment) {

    private enum class State { QUEUED, TESTING, DONE, FAILED, UNSUPPORTED }

    private class Row(val profile: ProxyEntity) {
        @Volatile
        var state = State.QUEUED

        /** While testing, the speed so far. */
        @Volatile
        var bytesPerSecond = 0L
        var result: SpeedTestResult? = null
        var error: String? = null
    }

    private val context = fragment.requireContext()
    private val binding = LayoutProgressListBinding.inflate(fragment.layoutInflater)
    private val rows = ArrayList<Row>()
    private val adapter = Adapter()
    private lateinit var dialog: AlertDialog

    /** Tests [load] gives, which runs off the main thread. */
    fun start(load: suspend () -> List<ProxyEntity>) {
        binding.listView.layoutManager = FixedLinearLayoutManager(binding.listView)
        binding.listView.itemAnimator = DefaultItemAnimator()
        binding.listView.adapter = adapter
        lateinit var mainJob: Job
        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.speed_test)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                mainJob.cancel()
            }
            .setNeutralButton(" ", null)
            .setCancelable(false)
            .show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
        mainJob = runOnDefaultDispatcher {
            val profiles = load()
            if (profiles.isEmpty()) {
                onMainDispatcher {
                    dialog.dismiss()
                    Toast.makeText(context, R.string.speed_test_nothing, Toast.LENGTH_SHORT).show()
                }
                return@runOnDefaultDispatcher
            }
            val queue = ConcurrentLinkedQueue(profiles.map { Row(it) })
            onMainDispatcher {
                rows.addAll(queue)
                adapter.notifyItemRangeInserted(0, rows.size)
                updateCount(0)
            }
            var finished = 0
            try {
                (1..DataStore.speedTestConcurrency).map {
                    launch {
                        while (isActive) {
                            val row = queue.poll() ?: break
                            test(row)
                            onMainDispatcher { updateCount(++finished) }
                        }
                    }
                }.joinAll()
            } finally {
                // For a group sorted by speed to sort again.
                withContext(NonCancellable) {
                    GroupManager.postReload(DataStore.currentGroupId())
                }
            }
            onMainDispatcher {
                binding.progressCircular.isGone = true
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.ok)
            }
        }
    }

    private suspend fun test(row: Row) {
        row.state = State.TESTING
        update(row)
        val profile = row.profile
        try {
            val result = SpeedTestInstance(profile).use {
                it.doSpeedTest(
                    DataStore.speedTestURL,
                    DataStore.speedTestMaxBytes,
                    DataStore.speedTestConnections,
                    DataStore.speedTestConnectTimeout,
                    DataStore.speedTestDuration,
                ) { bytes, elapsedMillis ->
                    row.bytesPerSecond = bytes * 1000 / elapsedMillis.coerceAtLeast(1)
                    update(row)
                }
            }
            row.result = result
            row.state = State.DONE
            profile.speed = result.bytesPerSecond
        } catch (e: CancellationException) {
            // Not a failure of the profile: the dialog was closed.
            throw e
        } catch (e: PluginManager.PluginNotFoundException) {
            row.error = e.readableMessage
            row.state = State.UNSUPPORTED
            update(row)
            return
        } catch (e: Exception) {
            row.error = e.readableMessage
            row.state = State.FAILED
            profile.speed = ProxyEntity.SPEED_FAILED
        }
        update(row)
        ProfileManager.updateProfile(profile)
    }

    private fun update(row: Row) {
        binding.listView.post {
            val index = rows.indexOf(row)
            if (index >= 0) adapter.notifyItemChanged(index)
        }
    }

    private fun updateCount(finished: Int) {
        binding.progressCircular.apply {
            isVisible = true
            setProgressCompat(finished * 100 / rows.size.coerceAtLeast(1), true)
        }
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text = "$finished/${rows.size}"
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutProfileBinding.inflate(fragment.layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position])
        }

        override fun getItemCount() = rows.size
    }

    private inner class Holder(val binding: LayoutProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.edit.isGone = true
            binding.share.isGone = true
            binding.deleteIcon.isGone = true
        }

        fun bind(row: Row) {
            binding.profileName.text = row.profile.displayName()
            binding.profileType.text = row.profile.displayType()
            val status = binding.profileStatus
            val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
            when (row.state) {
                State.QUEUED -> {
                    status.setText(R.string.speed_test_queued)
                    status.setTextColor(secondary)
                }
                State.TESTING -> {
                    if (row.bytesPerSecond > 0) {
                        status.text = context.getString(R.string.speed_test_running, context.formatSpeed(row.bytesPerSecond))
                    } else {
                        status.setText(R.string.connection_test_testing)
                    }
                    status.setTextColor(secondary)
                }
                State.DONE -> {
                    val result = row.result!!
                    status.text = context.getString(
                        R.string.speed_test_result,
                        context.formatSpeed(result.bytesPerSecond),
                        FormatFileSizeCompat.formatFileSize(context, result.bytes, DataStore.useIECUnit),
                        String.format(Locale.getDefault(), "%.1f", result.elapsedMillis / 1000.0),
                        result.connectMillis,
                    )
                    status.setTextColor(context.getColour(R.color.material_green_500))
                }
                State.FAILED -> {
                    status.text = row.error
                    status.setTextColor(context.getColour(R.color.material_red_500))
                }
                State.UNSUPPORTED -> {
                    status.text = row.error
                    status.setTextColor(secondary)
                }
            }
            if (row.state == State.FAILED) {
                binding.content.setOnClickListener {
                    fragment.alert(row.error ?: "<?>").show()
                }
            } else {
                binding.content.setOnClickListener {}
            }
        }
    }
}
