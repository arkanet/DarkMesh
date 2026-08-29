/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh.ui.discovery

import com.geeksville.mesh.discovery.DiscoveryScanState
import com.geeksville.mesh.model.ChannelOption
import kotlin.math.roundToInt

internal fun DiscoveryScanState.statusText(): String = when (this) {
    DiscoveryScanState.Idle -> "Idle"
    DiscoveryScanState.Preparing -> "Preparing"
    is DiscoveryScanState.SwitchingPreset -> "Switching to $presetName"
    is DiscoveryScanState.WaitingForRadio -> "Waiting for radio on $presetName"
    is DiscoveryScanState.Dwelling -> "Dwelling on $presetName"
    is DiscoveryScanState.CollectingResult -> "Saving $presetName"
    DiscoveryScanState.Analyzing -> "Analyzing"
    DiscoveryScanState.Restoring -> "Restoring"
    is DiscoveryScanState.Complete -> "Complete"
    DiscoveryScanState.Cancelling -> "Cancelling"
    is DiscoveryScanState.Failed -> "Failed: $message"
}

internal fun ChannelOption.displayName(): String {
    return name.lowercase()
        .split("_")
        .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}

internal fun Float?.formatDb(): String {
    return this?.let { "${(it * DECIMAL_SCALE).roundToInt() / DECIMAL_SCALE} dB" } ?: "-"
}

internal fun Float?.formatRssi(): String {
    return this?.let { "${it.roundToInt()} dBm" } ?: "-"
}

private const val DECIMAL_SCALE = 10f
