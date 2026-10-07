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
import org.meshtastic.proto.MeshProtos.LoRaPresetGroup
import org.meshtastic.proto.MeshProtos.LoRaRegionPresetMap

/** Pure decoder for a trusted, current-config-handshake region-preset map. */
internal object WireModemPresetCapabilityResolver {
    fun resolve(
        loraConfig: LoRaConfig,
        regionPresetMap: LoRaRegionPresetMap,
    ): ModemPresetCapabilities? {
        val decoded = decode(regionPresetMap)
        val currentGroup = decoded?.let { map ->
            map.regionGroupIndexes[loraConfig.region]?.let(map.groups::get)
        }
        val currentRegionNativePresets = currentGroup
            ?.takeIf { !loraConfig.usePreset || loraConfig.modemPreset in it.presets }
            ?.let { group ->
                nativePresets(
                    region = loraConfig.region,
                    advertisedPresets = group.presets,
                    decoded = requireNotNull(decoded),
                )
            }
        return if (decoded == null || currentGroup == null || currentRegionNativePresets == null) {
            null
        } else {
            val transitions = currentGroup.presets.associateWith { preset ->
                if (preset in currentRegionNativePresets) {
                    PresetRegionTransition.SAME_REGION
                } else {
                    PresetRegionTransition.FIRMWARE_DETERMINED_REGION_TRANSITION
                }
            }
            ModemPresetCapabilities(
                source = ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP,
                currentPreset = loraConfig.takeIf { it.usePreset }?.modemPreset,
                currentRegion = loraConfig.region,
                deviceSupportedPresets = decoded.groups.flatMapTo(linkedSetOf()) { it.presets },
                regionValidPresets = currentRegionNativePresets,
                currentMode = loraConfig.toCurrentLoRaMode(),
                currentRegionNativePresets = currentRegionNativePresets,
                advertisedSelectablePresets = currentGroup.presets,
                regionTransitionByPreset = transitions,
                defaultPreset = currentGroup.defaultPreset,
                licensedOnly = currentGroup.licensedOnly,
            )
        }
    }

    private fun decode(regionPresetMap: LoRaRegionPresetMap): DecodedMap? {
        var valid = regionPresetMap.groupsCount > 0 && regionPresetMap.regionGroupsCount > 0
        val groups = mutableListOf<DecodedGroup>()
        regionPresetMap.groupsList.forEach { group ->
            val decodedGroup = decodeGroup(group)
            if (decodedGroup == null) {
                valid = false
            } else {
                groups += decodedGroup
            }
        }
        val regionGroupIndexes = linkedMapOf<RegionCode, Int>()
        regionPresetMap.regionGroupsList.forEach { association ->
            val region = RegionCode.forNumber(association.regionValue)
            val groupIndex = association.groupIndex
            if (region == null || groupIndex !in groups.indices || region in regionGroupIndexes) {
                valid = false
            } else {
                regionGroupIndexes[region] = groupIndex
            }
        }
        return if (valid) DecodedMap(groups = groups, regionGroupIndexes = regionGroupIndexes) else null
    }

    private fun decodeGroup(group: LoRaPresetGroup): DecodedGroup? {
        var valid = group.presetsCount > 0
        val presets = linkedSetOf<ModemPreset>()
        group.presetsValueList.forEach { rawPreset ->
            val preset = ModemPreset.forNumber(rawPreset)
            if (preset == null) {
                valid = false
            } else {
                presets += preset
            }
        }
        val defaultPreset = ModemPreset.forNumber(group.defaultPresetValue)
        return defaultPreset
            ?.takeIf { valid && it in presets }
            ?.let { preset ->
                DecodedGroup(
                    presets = presets,
                    defaultPreset = preset,
                    licensedOnly = group.licensedOnly,
                )
            }
    }

    private fun nativePresets(
        region: RegionCode,
        advertisedPresets: Set<ModemPreset>,
        decoded: DecodedMap,
    ): Set<ModemPreset>? {
        val knownNativePresets = EU_NATIVE_PRESETS[region]
        return when {
            knownNativePresets == null -> advertisedPresets
            advertisedPresets.none { it in EU_PRESET_SUPERSET - knownNativePresets } -> advertisedPresets
            advertisedPresets != EU_PRESET_SUPERSET -> null
            else -> {
                // Exact 2.8.1 advertises the same reachable superset for the three EU siblings.
                // Require the complete signature before classifying any entry as a transition;
                // partial/novel forms stay UNKNOWN rather than inventing native ownership or a
                // concrete destination region.
                var completeSignature = true
                EU_NATIVE_PRESETS.forEach { (siblingRegion, _) ->
                    val siblingGroup = decoded.regionGroupIndexes[siblingRegion]
                        ?.let(decoded.groups::get)
                    if (
                        siblingGroup == null ||
                        siblingGroup.presets != EU_PRESET_SUPERSET ||
                        siblingGroup.defaultPreset != EU_DEFAULT_PRESETS.getValue(siblingRegion)
                    ) {
                        completeSignature = false
                    }
                }
                if (completeSignature) knownNativePresets else null
            }
        }
    }

    private data class DecodedMap(
        val groups: List<DecodedGroup>,
        val regionGroupIndexes: Map<RegionCode, Int>,
    )

    private data class DecodedGroup(
        val presets: Set<ModemPreset>,
        val defaultPreset: ModemPreset,
        val licensedOnly: Boolean,
    )

    private val EU_NATIVE_PRESETS = linkedMapOf(
        RegionCode.EU_868 to linkedSetOf(
            ModemPreset.LONG_FAST,
            ModemPreset.LONG_SLOW,
            ModemPreset.MEDIUM_SLOW,
            ModemPreset.MEDIUM_FAST,
            ModemPreset.SHORT_SLOW,
            ModemPreset.SHORT_FAST,
            ModemPreset.LONG_MODERATE,
        ),
        RegionCode.EU_866 to linkedSetOf(ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW),
        RegionCode.EU_N_868 to linkedSetOf(ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW),
    )

    private val EU_DEFAULT_PRESETS = mapOf(
        RegionCode.EU_868 to ModemPreset.LONG_FAST,
        RegionCode.EU_866 to ModemPreset.LITE_FAST,
        RegionCode.EU_N_868 to ModemPreset.NARROW_SLOW,
    )

    private val EU_PRESET_SUPERSET = EU_NATIVE_PRESETS.values.flatten().toSet()
}
