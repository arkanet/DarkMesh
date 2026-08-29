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
import com.geeksville.mesh.database.entity.DiscoveryNeighborType
import com.geeksville.mesh.model.Node

@Suppress("ReturnCount")
internal object DiscoveryMapBuilder {
    fun build(
        nodes: List<DiscoveredNodeEntity>,
        localNode: Node?,
        localNodeNum: Long?,
    ): DiscoveryMap? {
        if (nodes.isEmpty()) return null

        val mapNodes = nodes.map { it.toMapNode() }
        val mapNodeByNum = (listOfNotNull(localNode) + mapNodes)
            .associateBy { it.num.toLong() }
        val observedLinks = buildObservedDiscoveryLinks(nodes, localNodeNum)
        val discoveryGraph = buildDiscoveryGraph(observedLinks)

        val positionedObservedLinks = observedLinks.mapNotNull { link ->
            link.toPositionedMapLink(mapNodeByNum)
        }
        val anchoredRouteLinks = buildAnchoredRouteLinks(
            nodes = nodes,
            localNodeNum = localNodeNum,
            graph = discoveryGraph,
            mapNodeByNum = mapNodeByNum,
        )

        return DiscoveryMap(
            localNode = localNode?.takeIf { it.hasMapPosition() },
            nodes = (listOfNotNull(localNode) + mapNodes).distinctBy { it.num },
            links = (positionedObservedLinks + anchoredRouteLinks).distinctBy {
                "${it.from.num}:${it.to.num}:${it.isDirect}"
            },
        )
    }

    private fun buildObservedDiscoveryLinks(
        nodes: List<DiscoveredNodeEntity>,
        localNodeNum: Long?,
    ): List<RawDiscoveryLink> {
        val directLinks = localNodeNum?.let { originNodeNum ->
            nodes.filter { it.neighborType == DiscoveryNeighborType.DIRECT }
                .map { discovered ->
                    RawDiscoveryLink(
                        fromNum = originNodeNum,
                        toNum = discovered.nodeNum,
                        snr = discovered.snr,
                        isDirect = true,
                    )
                }
        }.orEmpty()

        val meshLinks = nodes.mapNotNull { discovered ->
            val viaNodeNum = discovered.viaNodeNum ?: return@mapNotNull null
            RawDiscoveryLink(
                fromNum = viaNodeNum,
                toNum = discovered.nodeNum,
                snr = discovered.neighborSnr,
                isDirect = false,
            )
        }

        return directLinks + meshLinks
    }

    private fun RawDiscoveryLink.toPositionedMapLink(
        mapNodeByNum: Map<Long, Node>,
    ): DiscoveryMapLink? {
        val fromNode = mapNodeByNum[fromNum]?.takeIf { it.hasMapPosition() } ?: return null
        val toNode = mapNodeByNum[toNum]?.takeIf { it.hasMapPosition() } ?: return null
        return DiscoveryMapLink(
            from = fromNode,
            to = toNode,
            snr = snr,
            isDirect = isDirect,
        )
    }

    private fun buildAnchoredRouteLinks(
        nodes: List<DiscoveredNodeEntity>,
        localNodeNum: Long?,
        graph: Map<Long, Set<Long>>,
        mapNodeByNum: Map<Long, Node>,
    ): List<DiscoveryMapLink> {
        localNodeNum ?: return emptyList()

        return nodes.filter { discovered ->
            discovered.neighborType == DiscoveryNeighborType.MESH &&
                discovered.hopCount?.let { it > MAX_DIRECT_HOPS } == true
        }.mapNotNull { discovered ->
            val routeEndpointsHavePosition = hasMapPosition(localNodeNum, mapNodeByNum) &&
                hasMapPosition(discovered.nodeNum, mapNodeByNum)
            if (routeEndpointsHavePosition) return@mapNotNull null

            val path = shortestPath(localNodeNum, discovered.nodeNum, graph)
                ?: return@mapNotNull null
            val positionedPath = path.mapNotNull { nodeNum ->
                mapNodeByNum[nodeNum]?.takeIf { it.hasMapPosition() }
            }
            val fromNode = positionedPath.firstOrNull() ?: return@mapNotNull null
            val toNode = positionedPath.lastOrNull() ?: return@mapNotNull null
            if (fromNode.num == toNode.num) return@mapNotNull null

            DiscoveryMapLink(
                from = fromNode,
                to = toNode,
                snr = discovered.snr,
                isDirect = false,
            )
        }
    }

    private fun buildDiscoveryGraph(links: List<RawDiscoveryLink>): Map<Long, Set<Long>> {
        val graph = mutableMapOf<Long, MutableSet<Long>>()
        links.forEach { link ->
            graph.getOrPut(link.fromNum) { mutableSetOf() } += link.toNum
            graph.getOrPut(link.toNum) { mutableSetOf() } += link.fromNum
        }
        return graph
    }

    private fun shortestPath(
        startNodeNum: Long,
        endNodeNum: Long,
        graph: Map<Long, Set<Long>>,
    ): List<Long>? {
        if (startNodeNum == endNodeNum) return listOf(startNodeNum)

        val visited = mutableSetOf(startNodeNum)
        val queue = ArrayDeque<List<Long>>()
        queue.add(listOf(startNodeNum))

        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val currentNodeNum = path.last()
            graph[currentNodeNum].orEmpty().sorted().forEach { nextNodeNum ->
                if (!visited.add(nextNodeNum)) return@forEach
                val nextPath = path + nextNodeNum
                if (nextNodeNum == endNodeNum) return nextPath
                queue.add(nextPath)
            }
        }

        return null
    }

    private fun hasMapPosition(
        nodeNum: Long,
        mapNodeByNum: Map<Long, Node>,
    ): Boolean {
        return mapNodeByNum[nodeNum]?.hasMapPosition() == true
    }

    private fun DiscoveredNodeEntity.toMapNode(): Node {
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
    private const val MAX_DIRECT_HOPS = 1
}

private data class RawDiscoveryLink(
    val fromNum: Long,
    val toNum: Long,
    val snr: Float?,
    val isDirect: Boolean,
)
