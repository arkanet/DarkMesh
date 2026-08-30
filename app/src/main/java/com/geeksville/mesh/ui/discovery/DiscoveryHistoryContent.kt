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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.database.entity.DiscoverySessionEntity
import com.geeksville.mesh.discovery.DiscoveryPresetRank
import java.text.DateFormat
import java.util.Date

internal fun LazyListScope.discoveryRankingSection(
    rankings: List<DiscoveryPresetRank>,
    onMap: (Long) -> Unit,
    onList: (Long) -> Unit,
) {
    item { SectionTitle("Ranking") }
    items(rankings, key = { "rank-${it.presetResultId}" }) { rank ->
        PresetRankItem(
            rank = rank,
            onMap = { onMap(rank.presetResultId) },
            onList = { onList(rank.presetResultId) },
        )
    }
}

@Suppress("LongParameterList")
internal fun LazyListScope.discoverySessionSection(
    sessions: List<DiscoverySessionEntity>,
    selectedSessionIds: Set<Long>,
    onSessionClick: (Long) -> Unit,
    onSessionLongClick: (Long) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAllSessions: () -> Unit,
    onDeleteSelectedSessions: () -> Unit,
) {
    item { SectionTitle("Recent Sessions") }
    if (selectedSessionIds.isNotEmpty()) {
        item {
            DiscoverySelectionBar(
                selectedCount = selectedSessionIds.size,
                allSelected = selectedSessionIds.size == sessions.size,
                onClear = onClearSelection,
                onSelectAll = onSelectAllSessions,
                onDelete = onDeleteSelectedSessions,
            )
        }
    }
    items(sessions, key = { "session-${it.id}" }) { session ->
        SessionItem(
            session = session,
            selected = session.id in selectedSessionIds,
            selectionMode = selectedSessionIds.isNotEmpty(),
            onClick = { onSessionClick(session.id) },
            onLongClick = { onSessionLongClick(session.id) },
        )
    }
}

@Composable
private fun PresetRankItem(
    rank: DiscoveryPresetRank,
    onMap: () -> Unit,
    onList: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PresetRankText(rank = rank, modifier = Modifier.weight(1f))
                PresetRankActions(onMap = onMap, onList = onList)
            }
        }
    }
}

@Composable
private fun PresetRankText(
    rank: DiscoveryPresetRank,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(rank.presetName, style = MaterialTheme.typography.subtitle1)
        Text(
            "${rank.uniqueNodes} nodes, ${rank.directNeighbors} direct, " +
                "${rank.packetCount} packets"
        )
        Text("SNR ${rank.averageSnr.formatDb()}, RSSI ${rank.averageRssi.formatRssi()}")
    }
}

@Composable
private fun PresetRankActions(
    onMap: () -> Unit,
    onList: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onMap) {
            Icon(Icons.Default.Map, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Map")
        }
        OutlinedButton(onClick = onList) {
            Icon(Icons.AutoMirrored.Default.List, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("List")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: DiscoverySessionEntity,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        backgroundColor = if (selected) {
            MaterialTheme.colors.primary.copy(alpha = SELECTED_CARD_ALPHA)
        } else {
            MaterialTheme.colors.surface
        },
    ) {
        SessionItemContent(
            session = session,
            selected = selected,
            selectionMode = selectionMode,
            dateText = formatter.format(Date(session.timestamp)),
        )
    }
}

@Composable
private fun SessionItemContent(
    session: DiscoverySessionEntity,
    selected: Boolean,
    selectionMode: Boolean,
    dateText: String,
) {
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(dateText, style = MaterialTheme.typography.subtitle1)
                Text("${session.presetsScanned} - ${session.completionStatus}")
                Text(
                    "${session.uniqueNodes} nodes, ${session.directNeighbors} direct, " +
                        "${session.messageCount} messages"
                )
            }
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                )
            }
        }
    }
}

@Composable
private fun DiscoverySelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$selectedCount selected", modifier = Modifier.weight(1f))
            IconButton(onClick = onSelectAll) {
                Icon(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = stringResource(R.string.select_all),
                    tint = if (allSelected) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.onSurface
                    },
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
            OutlinedButton(onClick = onClear) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

internal fun Set<Long>.toggled(id: Long): Set<Long> {
    return if (id in this) this - id else this + id
}

private const val SELECTED_CARD_ALPHA = 0.24f
