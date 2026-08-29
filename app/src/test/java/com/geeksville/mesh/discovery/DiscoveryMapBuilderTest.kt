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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryMapBuilderTest {
    @Test
    fun buildsNodeListWithPresetOriginDirectNodesFirstAndNeighborSnrFallback() {
        val nodeList = DiscoveryNodeListBuilder.build(
            presetName = PRESET_NAME,
            localNode = Node(
                num = LOCAL_NODE,
                liteLongName = LOCAL_NODE_NAME,
            ),
            localNodeNum = LOCAL_NODE.toLong(),
            nodes = listOf(
                discoveredNode(
                    nodeNum = NODE_C,
                    viaNodeNum = NODE_B,
                    hopCount = TWO_HOPS,
                    snr = null,
                    neighborSnr = MESH_NEIGHBOR_SNR,
                ),
                discoveredNode(
                    nodeNum = NODE_B,
                    neighborType = DiscoveryNeighborType.DIRECT,
                    hopCount = DIRECT_HOPS,
                    snr = DIRECT_SNR,
                ),
                discoveredNode(
                    nodeNum = NODE_F,
                    viaNodeNum = NODE_C,
                    hopCount = THREE_HOPS,
                    snr = FAR_MESH_SNR,
                ),
            ),
        )

        assertEquals(PRESET_NAME, nodeList.presetName)
        assertEquals(LOCAL_NODE_NAME, nodeList.originName)
        assertEquals(listOf(NODE_B, NODE_C, NODE_F), nodeList.nodes.map { it.nodeNum })
        assertEquals(MESH_NEIGHBOR_SNR, nodeList.nodes[1].snr)
    }

    @Test
    fun anchorsMeshRouteToFirstAndLastPositionedHopWhenLogicalEndpointsAreMissingPosition() {
        val map = requireNotNull(
            DiscoveryMapBuilder.build(
                localNode = Node(num = LOCAL_NODE),
                localNodeNum = LOCAL_NODE.toLong(),
                nodes = listOf(
                    discoveredNode(
                        nodeNum = NODE_B,
                        neighborType = DiscoveryNeighborType.DIRECT,
                        latitude = NODE_B_LATITUDE,
                        longitude = NODE_B_LONGITUDE,
                        hopCount = DIRECT_HOPS,
                    ),
                    discoveredNode(
                        nodeNum = NODE_C,
                        viaNodeNum = NODE_B,
                        hopCount = TWO_HOPS,
                    ),
                    discoveredNode(
                        nodeNum = NODE_F,
                        viaNodeNum = NODE_C,
                        latitude = NODE_F_LATITUDE,
                        longitude = NODE_F_LONGITUDE,
                        hopCount = FOUR_HOPS,
                    ),
                    discoveredNode(
                        nodeNum = NODE_G,
                        viaNodeNum = NODE_F,
                        hopCount = FIVE_HOPS,
                    ),
                ),
            )
        )

        val link = map.links.single()
        assertNull(map.localNode)
        assertEquals(NODE_B.toInt(), link.from.num)
        assertEquals(NODE_F.toInt(), link.to.num)
        assertFalse(link.isDirect)
    }

    @Test
    fun skipsAnchoredRouteWhenOnlyOneHopHasPosition() {
        val map = requireNotNull(
            DiscoveryMapBuilder.build(
                localNode = Node(num = LOCAL_NODE),
                localNodeNum = LOCAL_NODE.toLong(),
                nodes = listOf(
                    discoveredNode(
                        nodeNum = NODE_B,
                        neighborType = DiscoveryNeighborType.DIRECT,
                        latitude = NODE_B_LATITUDE,
                        longitude = NODE_B_LONGITUDE,
                        hopCount = DIRECT_HOPS,
                    ),
                    discoveredNode(
                        nodeNum = NODE_C,
                        viaNodeNum = NODE_B,
                        hopCount = TWO_HOPS,
                    ),
                    discoveredNode(
                        nodeNum = NODE_G,
                        viaNodeNum = NODE_C,
                        hopCount = THREE_HOPS,
                    ),
                ),
            )
        )

        assertEquals(emptyList<DiscoveryMapLink>(), map.links)
    }

    private fun discoveredNode(
        nodeNum: Long,
        neighborType: String = DiscoveryNeighborType.MESH,
        latitude: Double? = null,
        longitude: Double? = null,
        viaNodeNum: Long? = null,
        hopCount: Int? = null,
        snr: Float? = DEFAULT_SNR,
        neighborSnr: Float? = DEFAULT_SNR,
    ) = DiscoveredNodeEntity(
        presetResultId = PRESET_RESULT_ID,
        nodeNum = nodeNum,
        nodeId = "!$nodeNum",
        longName = "Node $nodeNum",
        shortName = "$nodeNum",
        defaultName = "Node $nodeNum",
        neighborType = neighborType,
        latitude = latitude,
        longitude = longitude,
        hopCount = hopCount,
        snr = snr,
        viaNodeNum = viaNodeNum,
        neighborSnr = neighborSnr,
    )

    private companion object {
        const val PRESET_NAME = "TINY_FAST"
        const val LOCAL_NODE_NAME = "Local node"
        const val PRESET_RESULT_ID = 1L
        const val LOCAL_NODE = 1
        const val NODE_B = 2L
        const val NODE_C = 3L
        const val NODE_F = 6L
        const val NODE_G = 7L
        const val DIRECT_HOPS = 1
        const val TWO_HOPS = 2
        const val THREE_HOPS = 3
        const val FOUR_HOPS = 4
        const val FIVE_HOPS = 5
        const val NODE_B_LATITUDE = 45.0
        const val NODE_B_LONGITUDE = 9.0
        const val NODE_F_LATITUDE = 46.0
        const val NODE_F_LONGITUDE = 10.0
        const val DEFAULT_SNR = -3.5f
        const val DIRECT_SNR = -12.0f
        const val MESH_NEIGHBOR_SNR = 4.5f
        const val FAR_MESH_SNR = 8.0f
    }
}
