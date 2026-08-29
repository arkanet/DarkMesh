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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.activityViewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.geeksville.mesh.database.entity.DiscoverySessionEntity
import com.geeksville.mesh.discovery.DiscoveryPresetRank
import com.geeksville.mesh.discovery.DiscoveryScanState
import com.geeksville.mesh.discovery.LocalMeshDiscoveryViewModel
import com.geeksville.mesh.model.ChannelOption
import com.geeksville.mesh.model.UIViewModel
import com.geeksville.mesh.ui.ScreenFragment
import com.geeksville.mesh.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Date

@AndroidEntryPoint
class DiscoveryFragment : ScreenFragment("Discovery") {
    private val uiViewModel: UIViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppTheme {
                    DiscoveryScreen(uiViewModel = uiViewModel)
                }
            }
        }
    }
}

@Composable
private fun DiscoveryScreen(
    uiViewModel: UIViewModel,
    viewModel: LocalMeshDiscoveryViewModel = hiltViewModel(),
) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val currentSession by viewModel.currentSession.collectAsStateWithLifecycle()
    val rankings by viewModel.rankings.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    var dwellText by rememberSaveable { mutableStateOf(DEFAULT_DWELL_SECONDS.toString()) }
    var selectedPresetNames by rememberSaveable {
        mutableStateOf(listOf(ChannelOption.LONG_FAST.name))
    }

    LaunchedEffect(Unit) {
        viewModel.mapEvents.collect { uiViewModel.showDiscoveryMap(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.messageEvents.collect { uiViewModel.showSnackbar(it) }
    }

    val selectedPresets = ChannelOption.entries
        .filter { it.name in selectedPresetNames }
        .toSet()
    val dwellSeconds = dwellText.toLongOrNull()
    val canStart = !scanState.isRunning && selectedPresets.isNotEmpty() &&
            dwellSeconds != null && dwellSeconds > 0

    DiscoveryContent(
        scanState = scanState,
        currentSession = currentSession,
        rankings = rankings,
        sessions = sessions,
        selectedPresetNames = selectedPresetNames,
        dwellText = dwellText,
        canStart = canStart,
        onTogglePreset = { option ->
            selectedPresetNames = if (option.name in selectedPresetNames) {
                selectedPresetNames - option.name
            } else {
                selectedPresetNames + option.name
            }
        },
        onDwellChange = { dwellText = it.filter(Char::isDigit).take(MAX_DWELL_DIGITS) },
        onStart = {
            viewModel.startScan(selectedPresets, dwellSeconds ?: DEFAULT_DWELL_SECONDS)
        },
        onStop = viewModel::stopScan,
        onMap = viewModel::requestMap,
    )
}

@Composable
@Suppress("LongParameterList")
private fun DiscoveryContent(
    scanState: DiscoveryScanState,
    currentSession: DiscoverySessionEntity?,
    rankings: List<DiscoveryPresetRank>,
    sessions: List<DiscoverySessionEntity>,
    selectedPresetNames: List<String>,
    dwellText: String,
    canStart: Boolean,
    onTogglePreset: (ChannelOption) -> Unit,
    onDwellChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMap: (Long) -> Unit,
) {
    Surface {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DiscoveryControls(
                    scanState = scanState,
                    selectedPresetNames = selectedPresetNames,
                    dwellText = dwellText,
                    canStart = canStart,
                    onTogglePreset = onTogglePreset,
                    onDwellChange = onDwellChange,
                    onStart = onStart,
                    onStop = onStop,
                )
            }

            item { DiscoveryStatus(state = scanState, session = currentSession) }

            if (rankings.isNotEmpty()) {
                item { SectionTitle("Ranking") }
                items(rankings, key = { "rank-${it.presetResultId}" }) { rank ->
                    PresetRankItem(rank = rank, onMap = { onMap(rank.presetResultId) })
                }
            }

            if (sessions.isNotEmpty()) {
                item { SectionTitle("Recent Sessions") }
                items(sessions, key = { "session-${it.id}" }) { session ->
                    SessionItem(session = session)
                }
            }
        }
    }
}

@Composable
private fun DiscoveryControls(
    scanState: DiscoveryScanState,
    selectedPresetNames: List<String>,
    dwellText: String,
    canStart: Boolean,
    onTogglePreset: (ChannelOption) -> Unit,
    onDwellChange: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Local Mesh Discovery", style = MaterialTheme.typography.h6)
        OutlinedTextField(
            value = dwellText,
            onValueChange = onDwellChange,
            label = { Text("Dwell seconds") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        PresetGrid(
            selectedPresetNames = selectedPresetNames,
            onTogglePreset = onTogglePreset,
            enabled = !scanState.isRunning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = canStart,
                onClick = onStart,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start")
            }
            OutlinedButton(
                enabled = scanState.isRunning,
                onClick = onStop,
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}

@Composable
private fun PresetGrid(
    selectedPresetNames: List<String>,
    onTogglePreset: (ChannelOption) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ChannelOption.entries.chunked(PRESET_COLUMNS).forEach { rowOptions ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .toggleable(
                                value = option.name in selectedPresetNames,
                                enabled = enabled,
                                onValueChange = { onTogglePreset(option) },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = option.name in selectedPresetNames,
                            onCheckedChange = null,
                            enabled = enabled,
                        )
                        Text(option.displayName())
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryStatus(
    state: DiscoveryScanState,
    session: DiscoverySessionEntity?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.statusText(), style = MaterialTheme.typography.subtitle1)
            (state as? DiscoveryScanState.Dwelling)?.let { dwell ->
                LinearProgressIndicator(
                    progress = ((dwell.totalSeconds - dwell.remainingSeconds).toFloat() /
                            dwell.totalSeconds).coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${dwell.remainingSeconds}s remaining on ${dwell.presetName}")
            }
            session?.let {
                Text(
                    "${it.uniqueNodes} nodes, ${it.directNeighbors} direct, ${it.meshNeighbors} mesh"
                )
                if (!it.failureMessage.isNullOrBlank()) Text(it.failureMessage)
            }
        }
    }
}

@Composable
private fun PresetRankItem(
    rank: DiscoveryPresetRank,
    onMap: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rank.presetName, style = MaterialTheme.typography.subtitle1)
                    Text(
                        "${rank.uniqueNodes} nodes, ${rank.directNeighbors} direct, " +
                                "${rank.packetCount} packets"
                    )
                    Text(
                        "SNR ${rank.averageSnr.formatDb()}, RSSI ${rank.averageRssi.formatRssi()}"
                    )
                }
                OutlinedButton(onClick = onMap) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Map")
                }
            }
        }
    }
}

@Composable
private fun SessionItem(session: DiscoverySessionEntity) {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(formatter.format(Date(session.timestamp)), style = MaterialTheme.typography.subtitle1)
            Text("${session.presetsScanned} - ${session.completionStatus}")
            Text(
                "${session.uniqueNodes} nodes, ${session.messageCount} messages, " +
                        "${session.sensorCount} sensor packets"
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Column {
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.subtitle2)
        Divider()
    }
}

private const val DEFAULT_DWELL_SECONDS = 60L
private const val PRESET_COLUMNS = 2
private const val MAX_DWELL_DIGITS = 4
