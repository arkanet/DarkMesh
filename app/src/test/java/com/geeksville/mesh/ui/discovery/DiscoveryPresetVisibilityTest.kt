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

import com.geeksville.mesh.discovery.DiscoveryTargetSelection
import com.geeksville.mesh.discovery.requireDiscoveryTargetsSupported
import com.geeksville.mesh.discovery.toDiscoveryHomeState
import com.geeksville.mesh.model.ChannelOption
import com.geeksville.mesh.model.CurrentLoRaMode
import com.geeksville.mesh.model.ModemPresetCapabilities
import com.geeksville.mesh.model.ModemPresetCapabilitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode

class DiscoveryPresetVisibilityTest {
    @Test
    fun wireCapabilityShowsOnlyElevenSelectablePresets() {
        val capabilities = capabilities(
            source = ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP,
            selectablePresets = WIRE_SELECTABLE_PRESETS,
            nativePresets = LEGACY_SELECTABLE_PRESETS,
        )

        val visible = capabilities.visibleDiscoveryPresetOptions

        assertEquals(11, visible.size)
        assertEquals(optionsFor(WIRE_SELECTABLE_PRESETS), visible.toSet())
        assertTrue(optionsFor(LEGACY_SELECTABLE_PRESETS).all(visible::contains))
        assertTrue(ChannelOption.LITE_FAST in visible)
        assertTrue(ChannelOption.LITE_SLOW in visible)
        assertTrue(ChannelOption.NARROW_FAST in visible)
        assertTrue(ChannelOption.NARROW_SLOW in visible)
        HIDDEN_PRESETS.forEach { assertFalse(it in visible) }
    }

    @Test
    fun exactLegacyCapabilityShowsOnlyNativeSeven() {
        val capabilities = capabilities(
            source = ModemPresetCapabilitySource.DARKMESH_2_7_26_EXACT_SOURCE,
            selectablePresets = LEGACY_SELECTABLE_PRESETS,
        )

        val visible = capabilities.visibleDiscoveryPresetOptions

        assertEquals(7, visible.size)
        assertEquals(optionsFor(LEGACY_SELECTABLE_PRESETS), visible.toSet())
        assertEquals(ChannelOption.entries.toSet() - visible.toSet(), HIDDEN_PRESETS + TRANSITION_PRESETS)
    }

    @Test
    fun unknownAndDisconnectedCapabilitiesShowNoPresetRows() {
        val unknown = ModemPresetCapabilities.unknown(
            currentPreset = ModemPreset.MEDIUM_FAST,
            currentRegion = RegionCode.EU_868,
        )
        val disconnected = ModemPresetCapabilities.unknown()

        assertTrue(unknown.visibleDiscoveryPresetOptions.isEmpty())
        assertTrue(disconnected.visibleDiscoveryPresetOptions.isEmpty())
    }

    @Test
    fun visibilityProjectionDoesNotChangePresetOrCustomHome() {
        val capabilities = capabilities(
            source = ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP,
            selectablePresets = WIRE_SELECTABLE_PRESETS,
            nativePresets = LEGACY_SELECTABLE_PRESETS,
        )
        val presetLora = LoRaConfig.newBuilder()
            .setUsePreset(true)
            .setRegion(RegionCode.EU_868)
            .setModemPreset(ModemPreset.MEDIUM_FAST)
            .build()
        val customLora = LoRaConfig.newBuilder()
            .setUsePreset(false)
            .setRegion(RegionCode.EU_868)
            .setBandwidth(250)
            .setSpreadFactor(9)
            .setCodingRate(5)
            .build()
        val presetHome = presetLora.toDiscoveryHomeState(RADIO)
        val customHome = customLora.toDiscoveryHomeState(RADIO)

        capabilities.visibleDiscoveryPresetOptions

        assertEquals(ChannelOption.MEDIUM_FAST, presetHome.initialTarget)
        assertEquals("MEDIUM_FAST", presetHome.presetName)
        assertNull(customHome.initialTarget)
        assertEquals("CUSTOM", customHome.presetName)
        assertEquals(CurrentLoRaMode.Custom(RegionCode.EU_868, 250, 9, 5), customLoraMode(customLora))
    }

    @Test
    fun hiddenTargetCannotEnterSelectionOrPassFinalWriteGuard() {
        val capabilities = capabilities(
            source = ModemPresetCapabilitySource.DARKMESH_2_7_26_EXACT_SOURCE,
            selectablePresets = LEGACY_SELECTABLE_PRESETS,
        )
        val home = LoRaConfig.newBuilder()
            .setUsePreset(true)
            .setRegion(RegionCode.EU_868)
            .setModemPreset(ModemPreset.MEDIUM_FAST)
            .build()
            .toDiscoveryHomeState(RADIO)
        val selection = DiscoveryTargetSelection()

        assertEquals(
            setOf(ChannelOption.MEDIUM_FAST.name),
            selection.onHomeChanged(home, capabilities, scanRunning = false),
        )
        assertEquals(
            setOf(ChannelOption.MEDIUM_FAST.name),
            selection.toggle(ChannelOption.LONG_TURBO, capabilities),
        )
        assertThrows(IllegalArgumentException::class.java) {
            requireDiscoveryTargetsSupported(listOf(ChannelOption.LONG_TURBO), capabilities)
        }
    }

    @Test
    fun settingsAndDiscoveryUseTheSameSelectableProjection() {
        val capabilities = capabilities(
            source = ModemPresetCapabilitySource.WIRE_REGION_PRESET_MAP,
            selectablePresets = WIRE_SELECTABLE_PRESETS,
            nativePresets = LEGACY_SELECTABLE_PRESETS,
        )
        val discovery = capabilities.visibleDiscoveryPresetOptions
            .mapTo(linkedSetOf()) { it.modemPreset }

        assertEquals(capabilities.settingsPresetChoices().toSet(), discovery)
        assertEquals(capabilities.selectablePresets, discovery)
    }

    private fun capabilities(
        source: ModemPresetCapabilitySource,
        selectablePresets: Set<ModemPreset>,
        nativePresets: Set<ModemPreset> = selectablePresets,
    ) = ModemPresetCapabilities(
        source = source,
        currentPreset = ModemPreset.MEDIUM_FAST,
        currentRegion = RegionCode.EU_868,
        deviceSupportedPresets = selectablePresets,
        regionValidPresets = nativePresets,
        currentRegionNativePresets = nativePresets,
        advertisedSelectablePresets = selectablePresets,
    )

    private fun optionsFor(presets: Set<ModemPreset>): Set<ChannelOption> =
        ChannelOption.entries.filterTo(linkedSetOf()) { it.modemPreset in presets }

    private fun customLoraMode(lora: LoRaConfig) = CurrentLoRaMode.Custom(
        region = lora.region,
        bandwidth = lora.bandwidth,
        spreadFactor = lora.spreadFactor,
        codingRate = lora.codingRate,
    )

    private companion object {
        const val RADIO = "radio"

        val LEGACY_SELECTABLE_PRESETS = linkedSetOf(
            ModemPreset.LONG_FAST,
            ModemPreset.LONG_SLOW,
            ModemPreset.MEDIUM_SLOW,
            ModemPreset.MEDIUM_FAST,
            ModemPreset.SHORT_SLOW,
            ModemPreset.SHORT_FAST,
            ModemPreset.LONG_MODERATE,
        )
        val TRANSITION_MODEM_PRESETS = linkedSetOf(
            ModemPreset.LITE_FAST,
            ModemPreset.LITE_SLOW,
            ModemPreset.NARROW_FAST,
            ModemPreset.NARROW_SLOW,
        )
        val WIRE_SELECTABLE_PRESETS = LEGACY_SELECTABLE_PRESETS + TRANSITION_MODEM_PRESETS
        val TRANSITION_PRESETS = optionsForStatic(TRANSITION_MODEM_PRESETS)
        val HIDDEN_PRESETS = linkedSetOf(
            ChannelOption.VERY_LONG_SLOW,
            ChannelOption.LONG_TURBO,
            ChannelOption.MEDIUM_TURBO,
            ChannelOption.SHORT_TURBO,
            ChannelOption.TINY_FAST,
            ChannelOption.TINY_SLOW,
        )

        private fun optionsForStatic(presets: Set<ModemPreset>): Set<ChannelOption> =
            ChannelOption.entries.filterTo(linkedSetOf()) { it.modemPreset in presets }
    }
}
