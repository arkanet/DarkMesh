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

import com.geeksville.mesh.discovery.requireDiscoveryTargetsSupported
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.MeshProtos.DeviceMetadata
import org.meshtastic.proto.MeshProtos.LoRaPresetGroup
import org.meshtastic.proto.MeshProtos.LoRaRegionPresetMap
import org.meshtastic.proto.MeshProtos.LoRaRegionPresets

class WireModemPresetCapabilityResolverTest {
    private val authority = ModemPresetCapabilityAuthority()

    @Test
    fun exactEu868WireMapSeparatesNativeAndAdvertisedPresets() {
        val capabilities = resolve(lora(), exactEuMap())

        assertEquals(ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP, capabilities.source)
        assertEquals(CurrentLoRaMode.Preset(RegionCode.EU_868, ModemPreset.MEDIUM_FAST), capabilities.currentMode)
        assertEquals(EU_868_NATIVE_PRESETS, capabilities.currentRegionNativePresets)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.advertisedSelectablePresets)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.selectablePresets)
        assertEquals(ModemPreset.LONG_FAST, capabilities.defaultPreset)
        assertEquals(false, capabilities.licensedOnly)
        EU_868_NATIVE_PRESETS.forEach { preset ->
            assertEquals(PresetRegionTransition.SAME_REGION, capabilities.transitionFor(preset))
        }
        TRANSITION_PRESETS.forEach { preset ->
            assertEquals(
                PresetRegionTransition.FIRMWARE_DETERMINED_REGION_TRANSITION,
                capabilities.transitionFor(preset),
            )
        }
    }

    @Test
    fun exactEu868WireMapDoesNotInventTurboPresetsAndRetainsCurrent() {
        val capabilities = resolve(lora(), exactEuMap())

        assertEquals(ModemPreset.MEDIUM_FAST, capabilities.currentPreset)
        assertFalse(ModemPreset.SHORT_TURBO in capabilities.selectablePresets)
        assertFalse(ModemPreset.LONG_TURBO in capabilities.selectablePresets)
        assertFalse(ModemPreset.MEDIUM_TURBO in capabilities.selectablePresets)
        assertNull(capabilities.transitionFor(ModemPreset.MEDIUM_TURBO))
    }

    @Test
    fun validWireMapOutranksExactLegacyStaticSource() {
        val capabilities = authority.resolve(
            connected = true,
            metadata = metadata(DARKMESH_2_7_26_FIRMWARE_VERSION),
            loraConfig = lora(),
            regionPresetMap = exactEuMap(),
        )

        assertEquals(ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP, capabilities.source)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.selectablePresets)
    }

    @Test
    fun unrelatedFirmwareWithoutWireMapRemainsUnknownWithoutEnumExpansion() {
        val capabilities = authority.resolve(
            connected = true,
            metadata = metadata("2.8.1.8e6a88d"),
            loraConfig = lora(),
        )

        assertEquals(ModemPresetCapabilitySource.UNKNOWN, capabilities.source)
        assertEquals(ModemPreset.MEDIUM_FAST, capabilities.currentPreset)
        assertEquals(CurrentLoRaMode.Preset(RegionCode.EU_868, ModemPreset.MEDIUM_FAST), capabilities.currentMode)
        assertEquals(listOf(ModemPreset.MEDIUM_FAST), capabilities.settingsPresetChoices())
        assertTrue(capabilities.selectablePresets.isEmpty())
        assertEquals(ModemPresetSupport.UNKNOWN, capabilities.supportFor(ModemPreset.LONG_FAST))
    }

    @Test
    fun missingCurrentRegionFailsSafeToUnknown() {
        val map = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(listOf(ModemPreset.LONG_FAST), ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.US, 0))
            .build()

        assertUnknown(resolve(lora(), map))
    }

    @Test
    fun invalidGroupIndexFailsSafeToUnknown() {
        val map = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(listOf(ModemPreset.LONG_FAST), ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 7))
            .build()

        assertUnknown(resolve(lora(), map))
    }

    @Test
    fun emptyGroupFailsSafeToUnknown() {
        val map = LoRaRegionPresetMap.newBuilder()
            .addGroups(LoRaPresetGroup.newBuilder().setDefaultPreset(ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .build()

        assertUnknown(resolve(lora(), map))
    }

    @Test
    fun currentPresetMissingFromCurrentGroupFailsSafeToUnknown() {
        val map = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(listOf(ModemPreset.LONG_FAST), ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .build()

        assertUnknown(resolve(lora(), map))
    }

    @Test
    fun malformedPresetAndDefaultFailSafeToUnknown() {
        val unknownPreset = LoRaRegionPresetMap.newBuilder()
            .addGroups(
                LoRaPresetGroup.newBuilder()
                    .addPresetsValue(999)
                    .setDefaultPresetValue(999)
            )
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .build()
        val defaultOutsideGroup = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(listOf(ModemPreset.MEDIUM_FAST), ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .build()

        assertUnknown(resolve(lora(), unknownPreset))
        assertUnknown(resolve(lora(), defaultOutsideGroup))
    }

    @Test
    fun partialEuTransitionSignatureFailsSafeToUnknown() {
        val map = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(EU_ADVERTISED_PRESETS.toList(), ModemPreset.LONG_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .build()

        assertUnknown(resolve(lora(), map))
    }

    @Test
    fun duplicatePresetsAreDeterministicallyDeduplicated() {
        val map = exactEuMap(duplicateCurrentPreset = true)
        val capabilities = resolve(lora(), map)

        assertEquals(ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP, capabilities.source)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.selectablePresets)
        assertEquals(EU_ADVERTISED_PRESETS.size, capabilities.selectablePresets.size)
    }

    @Test
    fun customModeRetainsExactManualFieldsWithoutNamedCurrentPreset() {
        val custom = LoRaConfig.newBuilder()
            .setUsePreset(false)
            .setRegion(RegionCode.EU_868)
            .setBandwidth(250)
            .setSpreadFactor(9)
            .setCodingRate(5)
            .build()
        val before = custom.toByteArray()

        val capabilities = resolve(custom, exactEuMap())

        assertEquals(CurrentLoRaMode.Custom(RegionCode.EU_868, 250, 9, 5), capabilities.currentMode)
        assertNull(capabilities.currentPreset)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.selectablePresets)
        assertArrayEquals(before, custom.toByteArray())
    }

    @Test
    fun legacyResolverDoesNotNormalizeIgnoredManualFields() {
        val authoritative = lora().toBuilder()
            .setBandwidth(250)
            .setSpreadFactor(9)
            .setCodingRate(7)
            .build()
        val before = authoritative.toByteArray()

        val capabilities = authority.resolve(
            connected = true,
            metadata = metadata(DARKMESH_2_7_26_FIRMWARE_VERSION),
            loraConfig = authoritative,
        )

        assertEquals(CurrentLoRaMode.Preset(RegionCode.EU_868, ModemPreset.MEDIUM_FAST), capabilities.currentMode)
        assertArrayEquals(before, authoritative.toByteArray())
    }

    @Test
    fun settingsAndDiscoveryProjectionsUseTheSameWireSelectableSet() {
        val capabilities = resolve(lora(), exactEuMap())
        val settings = capabilities.settingsPresetChoices().toSet()
        val discovery = capabilities.discoveryPresetCatalogue()
            .filter { it.selectable }
            .mapTo(linkedSetOf()) { it.option.modemPreset }

        assertEquals(EU_ADVERTISED_PRESETS, settings)
        assertEquals(EU_ADVERTISED_PRESETS, discovery)
        requireDiscoveryTargetsSupported(listOf(ChannelOption.LITE_FAST), capabilities)
        assertThrows(IllegalArgumentException::class.java) {
            requireDiscoveryTargetsSupported(listOf(ChannelOption.MEDIUM_TURBO), capabilities)
        }
    }

    @Test
    fun resolverDoesNotMutateWireMapOrLoraConfig() {
        val lora = lora()
        val map = exactEuMap()
        val loraBefore = lora.toByteArray()
        val mapBefore = map.toByteArray()

        resolve(lora, map)

        assertArrayEquals(loraBefore, lora.toByteArray())
        assertArrayEquals(mapBefore, map.toByteArray())
    }

    private fun resolve(
        loraConfig: LoRaConfig,
        regionPresetMap: LoRaRegionPresetMap,
    ): ModemPresetCapabilities {
        return authority.resolve(
            connected = true,
            metadata = metadata("2.8.1.8e6a88d"),
            loraConfig = loraConfig,
            regionPresetMap = regionPresetMap,
        )
    }

    private fun assertUnknown(capabilities: ModemPresetCapabilities) {
        assertEquals(ModemPresetCapabilitySource.UNKNOWN, capabilities.source)
        assertEquals(ModemPreset.MEDIUM_FAST, capabilities.currentPreset)
        assertTrue(capabilities.selectablePresets.isEmpty())
        assertEquals(ModemPresetSupport.UNKNOWN, capabilities.supportFor(ModemPreset.LONG_FAST))
    }

    private fun lora(): LoRaConfig = LoRaConfig.newBuilder()
        .setUsePreset(true)
        .setRegion(RegionCode.EU_868)
        .setModemPreset(ModemPreset.MEDIUM_FAST)
        .build()

    private fun metadata(firmwareVersion: String): DeviceMetadata {
        return DeviceMetadata.newBuilder().setFirmwareVersion(firmwareVersion).build()
    }

    private fun exactEuMap(duplicateCurrentPreset: Boolean = false): LoRaRegionPresetMap {
        val currentPresets = EU_ADVERTISED_PRESETS.toMutableList().apply {
            if (duplicateCurrentPreset) add(ModemPreset.MEDIUM_FAST)
        }
        return LoRaRegionPresetMap.newBuilder()
            .addGroups(group(currentPresets, ModemPreset.LONG_FAST))
            .addGroups(group(EU_ADVERTISED_PRESETS.toList(), ModemPreset.LITE_FAST))
            .addGroups(group(EU_ADVERTISED_PRESETS.toList(), ModemPreset.NARROW_SLOW))
            .addRegionGroups(regionGroup(RegionCode.EU_868, 0))
            .addRegionGroups(regionGroup(RegionCode.EU_866, 1))
            .addRegionGroups(regionGroup(RegionCode.EU_N_868, 2))
            .build()
    }

    private fun group(
        presets: List<ModemPreset>,
        defaultPreset: ModemPreset,
    ): LoRaPresetGroup {
        return LoRaPresetGroup.newBuilder()
            .addAllPresets(presets)
            .setDefaultPreset(defaultPreset)
            .setLicensedOnly(false)
            .build()
    }

    private fun regionGroup(region: RegionCode, groupIndex: Int): LoRaRegionPresets {
        return LoRaRegionPresets.newBuilder()
            .setRegion(region)
            .setGroupIndex(groupIndex)
            .build()
    }

    private companion object {
        val EU_868_NATIVE_PRESETS = linkedSetOf(
            ModemPreset.LONG_FAST,
            ModemPreset.LONG_SLOW,
            ModemPreset.MEDIUM_SLOW,
            ModemPreset.MEDIUM_FAST,
            ModemPreset.SHORT_SLOW,
            ModemPreset.SHORT_FAST,
            ModemPreset.LONG_MODERATE,
        )
        val TRANSITION_PRESETS = linkedSetOf(
            ModemPreset.LITE_FAST,
            ModemPreset.LITE_SLOW,
            ModemPreset.NARROW_FAST,
            ModemPreset.NARROW_SLOW,
        )
        val EU_ADVERTISED_PRESETS = EU_868_NATIVE_PRESETS + TRANSITION_PRESETS
    }
}
