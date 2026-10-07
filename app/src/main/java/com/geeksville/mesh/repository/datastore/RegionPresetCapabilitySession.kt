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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.proto.MeshProtos.LoRaRegionPresetMap

/** Volatile trust boundary for region-preset data received during a full-config transaction. */
internal class RegionPresetCapabilitySession {
    private data class ConfigTransaction(
        val nonce: Int,
        val radioId: String,
        val stagedMap: LoRaRegionPresetMap? = null,
    )

    private val lock = Any()
    private val _activeRegionPresetMap = MutableStateFlow<LoRaRegionPresetMap?>(null)
    val activeRegionPresetMap: StateFlow<LoRaRegionPresetMap?> = _activeRegionPresetMap.asStateFlow()

    private var configTransaction: ConfigTransaction? = null
    private var activeRadioId: String? = null

    fun beginConfig(nonce: Int, radioId: String?) = synchronized(lock) {
        val trustedRadioId = radioId?.takeIf(String::isNotBlank)
        if (activeRadioId != trustedRadioId) {
            activeRadioId = null
            _activeRegionPresetMap.value = null
        }
        configTransaction = trustedRadioId?.let { ConfigTransaction(nonce = nonce, radioId = it) }
    }

    fun stage(regionPresetMap: LoRaRegionPresetMap) = synchronized(lock) {
        configTransaction = configTransaction?.copy(stagedMap = regionPresetMap)
    }

    fun complete(
        nonce: Int,
        radioId: String?,
        successful: Boolean,
    ) = synchronized(lock) {
        val transaction = configTransaction
        val matches = transaction?.nonce == nonce && transaction.radioId == radioId
        if (matches) {
            if (successful) {
                activeRadioId = transaction.radioId
                _activeRegionPresetMap.value = transaction.stagedMap
            }
            configTransaction = null
        }
    }

    fun invalidate() = synchronized(lock) {
        configTransaction = null
        activeRadioId = null
        _activeRegionPresetMap.value = null
    }
}
