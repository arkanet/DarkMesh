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

package com.geeksville.mesh.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Scaffold
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.database.entity.MeshLog
import com.geeksville.mesh.model.DebugViewModel
import com.geeksville.mesh.ui.theme.AppTheme
import dagger.hilt.android.AndroidEntryPoint
import java.text.DateFormat
import java.util.Locale

@AndroidEntryPoint
class DebugFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setBackgroundColor(ContextCompat.getColor(context, R.color.colorAdvancedBackground))
            setContent {
                val viewModel: DebugViewModel = hiltViewModel()

                AppTheme {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text(stringResource(id = R.string.debug_panel)) },
                                navigationIcon = {
                                    IconButton(onClick = { parentFragmentManager.popBackStack() }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            stringResource(id = R.string.navigate_back),
                                        )
                                    }
                                },
                                actions = {
                                    Button(onClick = viewModel::deleteAllLogs) {
                                        Text(text = stringResource(R.string.clear))
                                    }
                                }
                            )
                        },
                    ) { innerPadding ->
                        DebugScreen(
                            viewModel = viewModel,
                            contentPadding = innerPadding,
                        )
                    }
                }
            }
        }
    }
}

private const val LOG_AUTOSCROLL_THRESHOLD = 3
private const val HEX_RADIX = 16

private val REGEX_ANNOTATED_NODE_ID = Regex("\\(![0-9a-fA-F]{8}\\)$", RegexOption.MULTILINE)

/**
 * Transform the input [MeshLog] by enhancing the raw message with annotations.
 */
private fun annotateMeshLog(meshLog: MeshLog): MeshLog {
    val annotated = when (meshLog.message_type) {
        "Packet" -> meshLog.meshPacket?.let { packet ->
            annotateRawMessage(meshLog.raw_message, packet.from, packet.to)
        }

        "NodeInfo" -> meshLog.nodeInfo?.let { nodeInfo ->
            annotateRawMessage(meshLog.raw_message, nodeInfo.num)
        }

        "MyNodeInfo" -> meshLog.myNodeInfo?.let { nodeInfo ->
            annotateRawMessage(meshLog.raw_message, nodeInfo.myNodeNum)
        }

        else -> null
    }
    return if (annotated == null) {
        meshLog
    } else {
        meshLog.copy(raw_message = annotated)
    }
}

/**
 * Annotate the raw message string with the node IDs provided, in hex, if they are present.
 */
private fun annotateRawMessage(rawMessage: String, vararg nodeIds: Int): String {
    val msg = StringBuilder(rawMessage)
    var mutated = false
    nodeIds.forEach { nodeId ->
        mutated = mutated or msg.annotateNodeId(nodeId)
    }
    return if (mutated) {
        return msg.toString()
    } else {
        rawMessage
    }
}

/**
 * Look for a single node ID integer in the string and annotate it with the hex equivalent
 * if found.
 */
private fun StringBuilder.annotateNodeId(nodeId: Int): Boolean {
    val nodeIdStr = nodeId.toUInt().toString()
    indexOf(nodeIdStr).takeIf { it >= 0 }?.let { idx ->
        insert(idx + nodeIdStr.length, " (${nodeId.asNodeId()})")
        return true
    }
    return false
}

private fun Int.asNodeId(): String {
    return "!%08x".format(Locale.getDefault(), this)
}

@Composable
internal fun DebugScreen(
    viewModel: DebugViewModel = hiltViewModel(),
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val logs by viewModel.meshLog.collectAsStateWithLifecycle()
    var filters by remember { mutableStateOf(GhostLogFilters()) }
    val context = LocalContext.current
    val visibleLogs = remember(logs, filters) { filterGhostLogs(logs, filters) }
    val shouldAutoScroll by remember { derivedStateOf { listState.firstVisibleItemIndex < LOG_AUTOSCROLL_THRESHOLD } }
    if (shouldAutoScroll) {
        LaunchedEffect(visibleLogs) {
            if (visibleLogs.isNotEmpty() && !listState.isScrollInProgress) {
                listState.scrollToItem(0)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        GhostFilterControls(filters = filters, onChange = { filters = it })
private data class GhostLogFilters(
    val text: String = "",
    val from: String = "",
    val to: String = "",
    val channel: String = "",
    val decode: String = "ALL",
)

private fun filterGhostLogs(logs: List<MeshLog>, filters: GhostLogFilters): List<MeshLog> {
    val from = parseGhostNodeId(filters.from)
    val to = parseGhostNodeId(filters.to)
    val channel = parseGhostChannel(filters.channel)
    return filterDiagnosticLogs(logs, filters.text).filter { log ->
        val packet = log.meshPacket
        val fromMatches = filters.from.isBlank() || (from != null && packet?.from?.toUInt() == from)
        val toMatches = filters.to.isBlank() || (to != null && packet?.to?.toUInt() == to)
        val channelMatches = filters.channel.isBlank() ||
            filters.channel.equals("ALL", ignoreCase = true) ||
            (channel != null && packet?.channel == channel)
        val decodeMatches = when (filters.decode) {
            "Decoded" -> packet?.hasDecoded() == true
            "Not decoded" -> packet?.hasEncrypted() == true
            else -> true
        }
        fromMatches && toMatches && channelMatches && decodeMatches
    }
}

@Composable
private fun GhostFilterControls(filters: GhostLogFilters, onChange: (GhostLogFilters) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = filters.text,
        onValueChange = { onChange(filters.copy(text = it)) },
        label = { Text("Filter logs (node, type, text)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = filters.from,
            onValueChange = { onChange(filters.copy(from = it)) },
            label = { Text("FROM (hex / dec)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = filters.to,
            onValueChange = { onChange(filters.copy(to = it)) },
            label = { Text("TO (hex / dec)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = filters.channel,
            onValueChange = { onChange(filters.copy(channel = it)) },
            label = { Text("CH (ALL / ID)") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(1f)) {
            Button(onClick = { expanded = true }) { Text(filters.decode) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("ALL", "Decoded", "Not decoded").forEach { option ->
                    DropdownMenuItem(onClick = {
                        onChange(filters.copy(decode = option))
                        expanded = false
                    }) { Text(option) }
                }
            }
        }
    }
}


