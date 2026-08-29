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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.discovery.DiscoveryNodeList
import com.geeksville.mesh.model.formatNeighborDiscoverySnr
import com.geeksville.mesh.model.neighborDiscoverySnrColor

@Composable
fun DiscoveryNodeListDialog(
    nodeList: DiscoveryNodeList,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {},
        text = {
            DiscoveryNodeListContent(nodeList = nodeList)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.okay),
                    color = colorResource(id = R.color.colorAnnotation),
                )
            }
        },
    )
}

@Composable
fun DiscoveryNodeListContent(
    nodeList: DiscoveryNodeList,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Local Mesh Discovery",
            style = MaterialTheme.typography.h6,
            textAlign = TextAlign.Center,
        )
        Text(
            text = nodeList.presetName.ifBlank { nodeList.originName },
            style = MaterialTheme.typography.subtitle1,
            textAlign = TextAlign.Center,
        )
        if (nodeList.presetName.isNotBlank()) {
            Text(
                text = nodeList.originName,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = NODE_LIST_MAX_HEIGHT_DP.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NODE_LIST_ROW_SPACING_DP.dp),
        ) {
            if (nodeList.nodes.isEmpty()) {
                Text(
                    text = stringResource(R.string.neighbor_discovery_no_neighbors),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.body1,
                )
            } else {
                nodeList.nodes.forEach { node ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = node.longName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.body1,
                        )
                        Text(
                            text = formatNeighborDiscoverySnr(node.snr),
                            color = Color(neighborDiscoverySnrColor(node.snr)),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.body1,
                        )
                    }
                }
            }
        }
    }
}

private const val NODE_LIST_MAX_HEIGHT_DP = 360
private const val NODE_LIST_ROW_SPACING_DP = 8
