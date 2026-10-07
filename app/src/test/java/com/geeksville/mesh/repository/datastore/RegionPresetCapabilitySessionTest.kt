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

package com.geeksville.mesh.repository.datastore

import com.geeksville.mesh.discovery.requireDiscoveryTargetsSupported
import com.geeksville.mesh.discovery.toDiscoveryHomeState
import com.geeksville.mesh.model.ChannelOption
import com.geeksville.mesh.model.CurrentLoRaMode
import com.geeksville.mesh.model.DARKMESH_2_7_26_FIRMWARE_VERSION
import com.geeksville.mesh.model.ModemPresetCapabilityAuthority
import com.geeksville.mesh.model.ModemPresetCapabilitySource
import com.geeksville.mesh.model.PresetRegionTransition
import com.geeksville.mesh.model.discoveryPresetCatalogue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

class RegionPresetCapabilitySessionTest {
    private val authority = ModemPresetCapabilityAuthority()

    @Test
    fun unsolicitedAndMerelyStagedMapsAreNotPublished() {
        val session = RegionPresetCapabilitySession()
        val map = exactEuMap()

        session.stage(map)
        assertNull(session.activeRegionPresetMap.value)

        session.beginConfig(NONCE_A, RADIO_28)
        session.stage(map)
        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun matchingNonceAndRadioActivateTheStagedMap() {
        val session = RegionPresetCapabilitySession()
        val map = exactEuMap()

        session.beginConfig(NONCE_A, RADIO_28)
        session.stage(map)
        session.complete(NONCE_A, RADIO_28, successful = true)

        assertSame(map, session.activeRegionPresetMap.value)
    }

    @Test
    fun wrongNonceAndWrongRadioCannotActivate() {
        val session = RegionPresetCapabilitySession()
        val map = exactEuMap()
        session.beginConfig(NONCE_A, RADIO_28)
        session.stage(map)

        session.complete(NONCE_B, RADIO_28, successful = true)
        assertNull(session.activeRegionPresetMap.value)
        session.complete(NONCE_A, RADIO_27, successful = true)
        assertNull(session.activeRegionPresetMap.value)

        session.complete(NONCE_A, RADIO_28, successful = true)
        assertSame(map, session.activeRegionPresetMap.value)
    }

    @Test
    fun newHandshakeInvalidatesStagingAndStaleCompletion() {
        val session = RegionPresetCapabilitySession()
        session.beginConfig(NONCE_A, RADIO_28)
        session.stage(exactEuMap())

        session.beginConfig(NONCE_B, RADIO_28)
        session.complete(NONCE_A, RADIO_28, successful = true)
        assertNull(session.activeRegionPresetMap.value)

        session.complete(NONCE_B, RADIO_28, successful = true)
        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun failedConfigAndMissingRadioIdentityDoNotActivate() {
        val session = RegionPresetCapabilitySession()
        session.beginConfig(NONCE_A, radioId = null)
        session.stage(exactEuMap())
        session.complete(NONCE_A, radioId = null, successful = true)
        assertNull(session.activeRegionPresetMap.value)

        session.beginConfig(NONCE_B, RADIO_28)
        session.stage(exactEuMap())
        session.complete(NONCE_B, RADIO_28, successful = false)
        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun invalidationClearsBothStagedAndActiveCapability() {
        val session = RegionPresetCapabilitySession()
        session.beginConfig(NONCE_A, RADIO_28)
        session.stage(exactEuMap())
        session.invalidate()
        session.complete(NONCE_A, RADIO_28, successful = true)
        assertNull(session.activeRegionPresetMap.value)

        activate(session, exactEuMap())
        assertTrue(session.activeRegionPresetMap.value != null)
        session.invalidate()
        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun differentRadioClearsActiveCapabilityBeforeNewActivation() {
        val session = RegionPresetCapabilitySession()
        activate(session, exactEuMap())

        session.beginConfig(NONCE_B, RADIO_27)

        assertNull(session.activeRegionPresetMap.value)
        session.stage(exactEuMap())
        session.complete(NONCE_B, RADIO_28, successful = true)
        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun sameRadioKeepsActiveMapUntilAtomicReplacement() {
        val session = RegionPresetCapabilitySession()
        val firstMap = exactEuMap()
        val replacementMap = exactEuMap(duplicateCurrentPreset = true)
        activate(session, firstMap)

        session.beginConfig(NONCE_B, RADIO_28)
        session.stage(replacementMap)
        assertSame(firstMap, session.activeRegionPresetMap.value)

        session.complete(NONCE_B, RADIO_28, successful = true)
        assertSame(replacementMap, session.activeRegionPresetMap.value)
    }

    @Test
    fun successfulSameRadioHandshakeWithoutMapClearsOldCapability() {
        val session = RegionPresetCapabilitySession()
        activate(session, exactEuMap())

        session.beginConfig(NONCE_B, RADIO_28)
        assertTrue(session.activeRegionPresetMap.value != null)
        session.complete(NONCE_B, RADIO_28, successful = true)

        assertNull(session.activeRegionPresetMap.value)
    }

    @Test
    fun activatedMapFeedsExistingAuthoritySettingsAndDiscoveryWithoutChangingHome() {
        val session = RegionPresetCapabilitySession()
        val lora = presetLora()
        val loraBefore = lora.toByteArray()
        val homeBefore = lora.toDiscoveryHomeState(RADIO_28)
        activate(session, exactEuMap())

        val capabilities = resolve(session, lora, firmware = "2.8.1.8e6a88d")
        val discoveryChoices = capabilities.discoveryPresetCatalogue()
            .filter { it.selectable }
            .mapTo(linkedSetOf()) { it.option.modemPreset }

        assertEquals(ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP, capabilities.source)
        assertEquals(ModemPreset.MEDIUM_FAST, capabilities.currentPreset)
        assertEquals(EU_868_NATIVE_PRESETS, capabilities.currentRegionNativePresets)
        assertEquals(EU_ADVERTISED_PRESETS, capabilities.settingsPresetChoices().toSet())
        assertEquals(EU_ADVERTISED_PRESETS, discoveryChoices)
        assertFalse(ModemPreset.MEDIUM_TURBO in capabilities.selectablePresets)
        assertEquals(
            PresetRegionTransition.FIRMWARE_DETERMINED_REGION_TRANSITION,
            capabilities.transitionFor(ModemPreset.LITE_FAST),
        )
        requireDiscoveryTargetsSupported(listOf(ChannelOption.LITE_FAST), capabilities)
        assertThrows(IllegalArgumentException::class.java) {
            requireDiscoveryTargetsSupported(listOf(ChannelOption.MEDIUM_TURBO), capabilities)
        }
        assertArrayEquals(loraBefore, lora.toByteArray())
        assertEquals(homeBefore, lora.toDiscoveryHomeState(RADIO_28))
    }

    @Test
    fun switchFromWireRadioToExactLegacyRadioCannotLeakMap() {
        val session = RegionPresetCapabilitySession()
        activate(session, exactEuMap())

        session.beginConfig(NONCE_B, RADIO_27)
        val capabilities = resolve(session, presetLora(), DARKMESH_2_7_26_FIRMWARE_VERSION)

        assertNull(session.activeRegionPresetMap.value)
        assertEquals(ModemPresetCapabilitySource.DARKMESH_2_7_26_EXACT_SOURCE, capabilities.source)
    }

    @Test
    fun nonLegacyRadioBeforeActivationIsUnknownAndCustomRemainsCustom() {
        val session = RegionPresetCapabilitySession()
        session.beginConfig(NONCE_A, RADIO_28)

        val preset = resolve(session, presetLora(), firmware = "2.8.1.8e6a88d")
        assertEquals(ModemPresetCapabilitySource.UNKNOWN, preset.source)
        assertEquals(ModemPreset.MEDIUM_FAST, preset.currentPreset)

        session.stage(exactEuMap())
        session.complete(NONCE_A, RADIO_28, successful = true)
        val custom = resolve(session, customLora(), firmware = "2.8.1.8e6a88d")
        assertEquals(CurrentLoRaMode.Custom(RegionCode.EU_868, 250, 9, 5), custom.currentMode)
        assertNull(custom.currentPreset)
    }

    @Test
    fun malformedActivatedMapFailsSafeToUnknown() {
        val session = RegionPresetCapabilitySession()
        val malformedMap = LoRaRegionPresetMap.newBuilder()
            .addGroups(group(listOf(ModemPreset.MEDIUM_FAST), ModemPreset.MEDIUM_FAST))
            .addRegionGroups(regionGroup(RegionCode.EU_868, groupIndex = 7))
            .build()
        activate(session, malformedMap)

        val capabilities = resolve(session, presetLora(), firmware = "2.8.1.8e6a88d")

        assertEquals(ModemPresetCapabilitySource.UNKNOWN, capabilities.source)
        assertEquals(ModemPreset.MEDIUM_FAST, capabilities.currentPreset)
        assertTrue(capabilities.selectablePresets.isEmpty())
    }

    private fun activate(
        session: RegionPresetCapabilitySession,
        map: LoRaRegionPresetMap,
        nonce: Int = NONCE_A,
        radioId: String = RADIO_28,
    ) {
        session.beginConfig(nonce, radioId)
        session.stage(map)
        session.complete(nonce, radioId, successful = true)
    }

    private fun resolve(
        session: RegionPresetCapabilitySession,
        lora: LoRaConfig,
        firmware: String,
    ) = authority.resolve(
        connected = true,
        metadata = DeviceMetadata.newBuilder().setFirmwareVersion(firmware).build(),
        loraConfig = lora,
        regionPresetMap = session.activeRegionPresetMap.value,
    )

    private fun presetLora(): LoRaConfig = LoRaConfig.newBuilder()
        .setUsePreset(true)
        .setRegion(RegionCode.EU_868)
        .setModemPreset(ModemPreset.MEDIUM_FAST)
        .build()

    private fun customLora(): LoRaConfig = LoRaConfig.newBuilder()
        .setUsePreset(false)
        .setRegion(RegionCode.EU_868)
        .setBandwidth(250)
        .setSpreadFactor(9)
        .setCodingRate(5)
        .build()

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
    ): LoRaPresetGroup = LoRaPresetGroup.newBuilder()
        .addAllPresets(presets)
        .setDefaultPreset(defaultPreset)
        .setLicensedOnly(false)
        .build()

    private fun regionGroup(region: RegionCode, groupIndex: Int): LoRaRegionPresets {
        return LoRaRegionPresets.newBuilder()
            .setRegion(region)
            .setGroupIndex(groupIndex)
            .build()
    }

    private companion object {
        const val NONCE_A = 101
        const val NONCE_B = 102
        const val RADIO_28 = "x!34a844a6"
        const val RADIO_27 = "x!fe20"

        val EU_868_NATIVE_PRESETS = linkedSetOf(
            ModemPreset.LONG_FAST,
            ModemPreset.LONG_SLOW,
            ModemPreset.MEDIUM_SLOW,
            ModemPreset.MEDIUM_FAST,
            ModemPreset.SHORT_SLOW,
            ModemPreset.SHORT_FAST,
            ModemPreset.LONG_MODERATE,
        )
        val EU_ADVERTISED_PRESETS = EU_868_NATIVE_PRESETS + setOf(
            ModemPreset.LITE_FAST,
            ModemPreset.LITE_SLOW,
            ModemPreset.NARROW_FAST,
            ModemPreset.NARROW_SLOW,
        )
    }
}
