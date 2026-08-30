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

package com.geeksville.mesh.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.database.dao.DiscoveryDao
import com.geeksville.mesh.model.ChannelOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalMeshDiscoveryViewModel @Inject constructor(
    private val engine: LocalMeshDiscoveryEngine,
    discoveryDao: DiscoveryDao,
) : ViewModel() {
    val scanState = engine.scanState
    val rankings = engine.rankings
    val currentSession = engine.currentSession
    val sessions = discoveryDao.getSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedReport = MutableStateFlow<DiscoveryReport?>(null)
    val selectedReport = _selectedReport.asStateFlow()

    private val _mapEvents = MutableSharedFlow<DiscoveryMap>()
    val mapEvents = _mapEvents.asSharedFlow()

    private val _listEvents = MutableSharedFlow<DiscoveryNodeList>()
    val listEvents = _listEvents.asSharedFlow()

    private val _messageEvents = MutableSharedFlow<String>()
    val messageEvents = _messageEvents.asSharedFlow()

    fun startScan(selectedPresets: Set<ChannelOption>, dwellSeconds: Long) {
        engine.startScan(selectedPresets, dwellSeconds)
    }

    fun stopScan() {
        engine.stopScan()
    }

    fun requestMap(presetResultId: Long) {
        viewModelScope.launch {
            val map = engine.buildDiscoveryMap(presetResultId)
            if (map == null || map.links.isEmpty()) {
                _messageEvents.emit("Discovery map unavailable: missing GPS links")
            } else {
                _mapEvents.emit(map)
            }
        }
    }

    fun requestList(presetResultId: Long) {
        viewModelScope.launch {
            val nodeList = engine.buildDiscoveryNodeList(presetResultId)
            if (nodeList == null) {
                _messageEvents.emit("Discovery list unavailable")
            } else {
                _listEvents.emit(nodeList)
            }
        }
    }

    fun requestReport(sessionId: Long) {
        viewModelScope.launch {
            val report = engine.buildDiscoveryReport(sessionId)
            if (report == null) {
                _messageEvents.emit("Discovery report unavailable")
            } else {
                _selectedReport.value = report
            }
        }
    }

    fun closeReport() {
        _selectedReport.value = null
    }

    fun deleteSessions(sessionIds: Set<Long>) {
        viewModelScope.launch {
            engine.deleteDiscoverySessions(sessionIds)
            if (_selectedReport.value?.session?.id in sessionIds) {
                _selectedReport.value = null
            }
            _messageEvents.emit("Discovery report deleted")
        }
    }
}
