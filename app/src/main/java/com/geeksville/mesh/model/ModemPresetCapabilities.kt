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

package com.geeksville.mesh.model

import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.MeshProtos.DeviceMetadata
import org.meshtastic.proto.MeshProtos.LoRaRegionPresetMap
import javax.inject.Inject
import javax.inject.Singleton

enum class ModemPresetSupport {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
}

enum class ModemPresetCapabilitySource {
    DARKMESH_2_7_26_EXACT_SOURCE,
    WIRE_REGION_PRESET_MAP,
    UNKNOWN,
}

sealed interface CurrentLoRaMode {
    val region: RegionCode

    data class Preset(
        override val region: RegionCode,
        val modemPreset: ModemPreset,
    ) : CurrentLoRaMode

    data class Custom(
        override val region: RegionCode,
        val bandwidth: Int,
        val spreadFactor: Int,
        val codingRate: Int,
    ) : CurrentLoRaMode
}

enum class PresetRegionTransition {
    SAME_REGION,
    FIRMWARE_DETERMINED_REGION_TRANSITION,
}

internal fun LoRaConfig.toCurrentLoRaMode(): CurrentLoRaMode = if (usePreset) {
    CurrentLoRaMode.Preset(region = region, modemPreset = modemPreset)
} else {
    CurrentLoRaMode.Custom(
        region = region,
        bandwidth = bandwidth,
        spreadFactor = spreadFactor,
        codingRate = codingRate,
    )
}

data class ModemPresetCapabilities(
    val source: ModemPresetCapabilitySource,
    val currentPreset: ModemPreset?,
    val currentRegion: RegionCode?,
    val deviceSupportedPresets: Set<ModemPreset>,
    val regionValidPresets: Set<ModemPreset>?,
    val currentMode: CurrentLoRaMode? = currentPreset?.let { preset ->
        currentRegion?.let { region -> CurrentLoRaMode.Preset(region, preset) }
    },
    val currentRegionNativePresets: Set<ModemPreset>? = if (
        source != ModemPresetCapabilitySource.UNKNOWN && regionValidPresets != null
    ) {
        deviceSupportedPresets.intersect(regionValidPresets)
    } else {
        null
    },
    val advertisedSelectablePresets: Set<ModemPreset>? = if (
        source != ModemPresetCapabilitySource.UNKNOWN
    ) {
        currentRegionNativePresets
    } else {
        null
    },
    val regionTransitionByPreset: Map<ModemPreset, PresetRegionTransition> =
        advertisedSelectablePresets.orEmpty().associateWith { PresetRegionTransition.SAME_REGION },
    val defaultPreset: ModemPreset? = null,
    val licensedOnly: Boolean? = null,
) {
    val isResolved: Boolean
        get() = source != ModemPresetCapabilitySource.UNKNOWN && advertisedSelectablePresets != null

    val selectablePresets: Set<ModemPreset>
        get() = if (isResolved) requireNotNull(advertisedSelectablePresets) else emptySet()

    fun transitionFor(preset: ModemPreset): PresetRegionTransition? {
        return regionTransitionByPreset[preset]
    }

    fun supportFor(preset: ModemPreset): ModemPresetSupport = when {
        !isResolved -> ModemPresetSupport.UNKNOWN
        preset in selectablePresets -> ModemPresetSupport.SUPPORTED
        else -> ModemPresetSupport.UNSUPPORTED
    }

    fun isSelectable(preset: ModemPreset): Boolean {
        return supportFor(preset) == ModemPresetSupport.SUPPORTED
    }

    /**
     * Settings exposes only proven selectable values. The authoritative current value is retained
     * solely so an unresolved capability never renders the connected device's config unrecognizable.
     */
    fun settingsPresetChoices(): List<ModemPreset> {
        return ModemPreset.entries.filter { preset ->
            preset != ModemPreset.UNRECOGNIZED &&
                (preset in selectablePresets || preset == currentPreset)
        }
    }

    companion object {
        fun unknown(
            currentPreset: ModemPreset? = null,
            currentRegion: RegionCode? = null,
            currentMode: CurrentLoRaMode? = currentPreset?.let { preset ->
                currentRegion?.let { region -> CurrentLoRaMode.Preset(region, preset) }
            },
        ) =
            ModemPresetCapabilities(
                source = ModemPresetCapabilitySource.UNKNOWN,
                currentPreset = currentPreset,
                currentRegion = currentRegion,
                deviceSupportedPresets = emptySet(),
                regionValidPresets = null,
                currentMode = currentMode,
                currentRegionNativePresets = null,
                advertisedSelectablePresets = null,
                regionTransitionByPreset = emptyMap(),
            )
    }
}

data class DiscoveryPresetAvailability(
    val option: ChannelOption,
    val support: ModemPresetSupport,
) {
    val selectable: Boolean get() = support == ModemPresetSupport.SUPPORTED
}

fun ModemPresetCapabilities.discoveryPresetCatalogue(): List<DiscoveryPresetAvailability> {
    return ChannelOption.entries.map { option ->
        DiscoveryPresetAvailability(option, supportFor(option.modemPreset))
    }
}

fun ModemPresetCapabilities.allowsLoRaSave(
    original: LoRaConfig,
    candidate: LoRaConfig,
): Boolean {
    return when {
        !candidate.usePreset -> true
        isSelectable(candidate.modemPreset) -> true
        // Preserve an unresolved/current value while preventing selection of another unproven value.
        else -> original.usePreset && candidate.modemPreset == original.modemPreset
    }
}

/**
 * Single authority for modem-preset support. The exact identity match is intentional: neither an
 * Android enum value nor a version-family guess is device capability evidence. The supported set
 * comes from DarkMesh firmware commit 28711617ab13e107dd80e897a890090e43aab906, whose version is
 * exactly [DARKMESH_2_7_26_FIRMWARE_VERSION].
 */
@Singleton
class ModemPresetCapabilityAuthority @Inject constructor() {
    fun resolve(
        connected: Boolean,
        metadata: DeviceMetadata?,
        loraConfig: LoRaConfig?,
        regionPresetMap: LoRaRegionPresetMap? = null,
    ): ModemPresetCapabilities {
        val currentPreset = loraConfig?.takeIf { it.usePreset }?.modemPreset
        val currentRegion = loraConfig?.region
        val currentMode = loraConfig?.toCurrentLoRaMode()
        val exactFirmware = metadata?.firmwareVersion == DARKMESH_2_7_26_FIRMWARE_VERSION
        return when {
            !connected || loraConfig == null ->
                ModemPresetCapabilities.unknown(currentPreset, currentRegion, currentMode)
            regionPresetMap != null -> WireModemPresetCapabilityResolver.resolve(loraConfig, regionPresetMap)
                ?: ModemPresetCapabilities.unknown(currentPreset, currentRegion, currentMode)
            !exactFirmware -> ModemPresetCapabilities.unknown(currentPreset, currentRegion, currentMode)
            else -> {
                val deviceSupported = DARKMESH_2_7_26_DEVICE_PRESETS
                val regionValid = regionValidPresets(loraConfig.region)
                ModemPresetCapabilities(
                    source = ModemPresetCapabilitySource.DARKMESH_2_7_26_EXACT_SOURCE,
                    currentPreset = currentPreset,
                    currentRegion = currentRegion,
                    deviceSupportedPresets = deviceSupported,
                    regionValidPresets = regionValid,
                    currentMode = currentMode,
                )
            }
        }
    }

    private fun regionValidPresets(region: RegionCode): Set<ModemPreset>? {
        val regionInfo = RegionInfo.entries.firstOrNull { it.regionCode == region } ?: return null
        val regionSpanMhz = regionInfo.freqEnd - regionInfo.freqStart
        val wideLoraMultiplier = if (region == RegionCode.LORA_24) WIDE_LORA_MULTIPLIER else 1f

        return ChannelOption.entries
            .filter { option ->
                option.bandwidth * wideLoraMultiplier <= regionSpanMhz + BANDWIDTH_EPSILON_MHZ
            }
            .mapTo(linkedSetOf()) { it.modemPreset }
    }

    private companion object {
        private const val WIDE_LORA_MULTIPLIER = 3.25f
        private const val BANDWIDTH_EPSILON_MHZ = 0.000_001f
    }
}

internal const val DARKMESH_2_7_26_FIRMWARE_VERSION = "2.7.26.2871161"

internal val DARKMESH_2_7_26_DEVICE_PRESETS = linkedSetOf(
    ModemPreset.LONG_FAST,
    ModemPreset.LONG_SLOW,
    ModemPreset.MEDIUM_SLOW,
    ModemPreset.MEDIUM_FAST,
    ModemPreset.SHORT_SLOW,
    ModemPreset.SHORT_FAST,
    ModemPreset.LONG_MODERATE,
    ModemPreset.SHORT_TURBO,
    ModemPreset.LONG_TURBO,
)
