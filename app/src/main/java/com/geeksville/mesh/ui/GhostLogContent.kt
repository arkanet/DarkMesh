package com.geeksville.mesh.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.geeksville.mesh.database.entity.MeshLog

private const val FILTER_PADDING_DP = 8

internal class GhostLogFilterState {
    var filter by mutableStateOf("")
    var fromFilter by mutableStateOf("")
    var toFilter by mutableStateOf("")
    var channelFilter by mutableStateOf("")
    var decodeFilter by mutableStateOf("ALL")
    var decodeMenuExpanded by mutableStateOf(false)
}

internal fun selectGhostLogs(logs: List<MeshLog>, state: GhostLogFilterState): List<MeshLog> {
    val from = parseGhostNodeId(state.fromFilter)
    val to = parseGhostNodeId(state.toFilter)
    val channel = parseGhostChannel(state.channelFilter)
    return filterDiagnosticLogs(logs, state.filter).filter { log ->
        val packet = log.meshPacket
        (state.fromFilter.isBlank() || (from != null && packet?.from?.toUInt() == from)) &&
            (state.toFilter.isBlank() || (to != null && packet?.to?.toUInt() == to)) &&
            (state.channelFilter.isBlank() || state.channelFilter.equals("ALL", ignoreCase = true) ||
                (channel != null && packet?.channel == channel)) &&
            when (state.decodeFilter) {
                "Decoded" -> packet?.hasDecoded() == true
                "Not decoded" -> packet?.hasEncrypted() == true
                else -> true
            }
    }
}

@Composable
private fun GhostLogFilterFields(state: GhostLogFilterState) {
    Column {
        OutlinedTextField(
            value = state.filter,
            onValueChange = { state.filter = it },
            label = { Text("Filter logs (node, type, text)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = FILTER_PADDING_DP.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FILTER_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(FILTER_PADDING_DP.dp),
        ) {
            OutlinedTextField(
                value = state.fromFilter,
                onValueChange = { state.fromFilter = it },
                label = { Text("FROM (hex / dec)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.toFilter,
                onValueChange = { state.toFilter = it },
                label = { Text("TO (hex / dec)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FILTER_PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(FILTER_PADDING_DP.dp),
        ) {
            OutlinedTextField(
                value = state.channelFilter,
                onValueChange = { state.channelFilter = it },
                label = { Text("CH (ALL / ID)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Column(modifier = Modifier.weight(1f)) {
                Button(onClick = { state.decodeMenuExpanded = true }) { Text(state.decodeFilter) }
                DropdownMenu(
                    expanded = state.decodeMenuExpanded,
                    onDismissRequest = { state.decodeMenuExpanded = false },
                ) {
                    listOf("ALL", "Decoded", "Not decoded").forEach { option ->
                        DropdownMenuItem(onClick = {
                            state.decodeFilter = option
                            state.decodeMenuExpanded = false
                        }) { Text(option) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GhostLogContent(
    logs: List<MeshLog>,
    visibleLogs: List<MeshLog>,
    listState: LazyListState,
    contentPadding: PaddingValues,
    state: GhostLogFilterState,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        GhostLogFilterFields(state)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = FILTER_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${visibleLogs.size} / ${logs.size} recent logs")
            Button(
                enabled = visibleLogs.isNotEmpty(),
                onClick = {
                    // Share only the currently displayed, bounded log snapshot.
                    // This may contain sensitive node identifiers and message content.
                    val export = formatDiagnosticLog(visibleLogs)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "DarkMesh diagnostic log")
                        putExtra(Intent.EXTRA_TEXT, export)
                    }
                    context.startActivity(Intent.createChooser(intent, "Export filtered logs"))
                },
            ) {
                Text("Export")
            }
        }
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                items(visibleLogs, key = { it.uuid }) { log -> DebugItem(annotateMeshLog(log)) }
            }
        }
    }
}

