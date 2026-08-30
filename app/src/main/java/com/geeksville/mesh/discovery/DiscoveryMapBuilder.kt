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

import com.geeksville.mesh.database.entity.DiscoveredNodeEntity
import com.geeksville.mesh.model.Node

internal object DiscoveryMapBuilder {
    fun build(
        nodes: List<DiscoveredNodeEntity>,
        localNode: Node?,
        localNodeNum: Long?,
        presetName: String = "",
        knownNodeByNum: Map<Int, Node> = emptyMap(),
    ): DiscoveryMap? {
        if (nodes.isEmpty()) return null

        val directNodes = nodes.filter { it.isZeroHopDirectDiscoveryNode() }
        val mapNodes = directNodes.map { it.toMapNode(knownNodeByNum) }
        val mapNodeByNum = (listOfNotNull(localNode) + mapNodes)
            .associateBy { it.num.toLong() }
        val localMapNode = localNodeNum
            ?.let(mapNodeByNum::get)
            ?.takeIf { it.hasMapPosition() }
        val links = localMapNode?.let { origin ->
            directNodes.mapNotNull { discovered ->
                val toNode = mapNodeByNum[discovered.nodeNum]?.takeIf { it.hasMapPosition() }
                    ?: return@mapNotNull null
                DiscoveryMapLink(
                    from = origin,
                    to = toNode,
                    snr = discovered.snr,
                    isDirect = true,
                )
            }
        }.orEmpty()

        return DiscoveryMap(
            localNode = localMapNode,
            nodes = (listOfNotNull(localNode) + mapNodes).distinctBy { it.num },
            links = links.distinctBy {
                "${it.from.num}:${it.to.num}"
            },
            nodeList = DiscoveryNodeListBuilder.build(
                presetName = presetName,
                nodes = nodes,
                localNode = localNode,
                localNodeNum = localNodeNum,
            ),
        )
    }

    private fun DiscoveredNodeEntity.toMapNode(knownNodeByNum: Map<Int, Node>): Node {
        knownNodeByNum[nodeNum.toInt()]?.takeIf { it.hasMapPosition() }?.let { return it }

        return Node(
            num = nodeNum.toInt(),
            liteNodeId = nodeId,
            liteDefaultName = defaultName ?: nodeId,
            liteLongName = longName ?: defaultName ?: nodeId,
            liteShortName = shortName ?: nodeId.takeLast(DEFAULT_SHORT_NAME_LENGTH),
            liteLatitude = latitude,
            liteLongitude = longitude,
        )
    }

    private fun Node.hasMapPosition(): Boolean {
        return validPosition != null || validLiteNode
    }

    private const val DEFAULT_SHORT_NAME_LENGTH = 4
}
