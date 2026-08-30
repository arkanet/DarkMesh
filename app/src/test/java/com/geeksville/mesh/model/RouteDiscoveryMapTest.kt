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

package com.geeksville.mesh.model

import com.geeksville.mesh.database.entity.NodeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.meshtastic.proto.MeshProtos

class RouteDiscoveryMapTest {
    @Test
    fun tracerouteMapSkipsUnpositionedEndpointsAndKeepsDrawablePath() {
        val map = evaluateTracerouteMapAvailability(
            traceroute = fullTracerouteText,
            nodesByNum = mapOf(
                ORIGIN_NODE to namedNode(ORIGIN_NODE, "Origin"),
                RELAY_A_NODE to positionedNode(RELAY_A_NODE, "Relay A", RELAY_A_LAT, RELAY_A_LON),
                RELAY_B_NODE to positionedNode(RELAY_B_NODE, "Relay B", RELAY_B_LAT, RELAY_B_LON),
                DESTINATION_NODE to namedNode(DESTINATION_NODE, "Destination"),
            ),
            nodeRegistryMap = emptyMap(),
        )

        assertNotNull(map)
        assertEquals(listOf(RELAY_A_NODE, RELAY_B_NODE), map?.traceForwardList?.map { it.num })
        assertEquals(listOf(RELAY_B_NODE, RELAY_A_NODE), map?.traceBackList?.map { it.num })
    }

    @Test
    fun tracerouteMapAllowsForwardOnlyDrawablePath() {
        val map = evaluateTracerouteMapAvailability(
            traceroute = forwardOnlyTracerouteText,
            nodesByNum = mapOf(
                RELAY_A_NODE to positionedNode(RELAY_A_NODE, "Relay A", RELAY_A_LAT, RELAY_A_LON),
                RELAY_B_NODE to positionedNode(RELAY_B_NODE, "Relay B", RELAY_B_LAT, RELAY_B_LON),
            ),
            nodeRegistryMap = emptyMap(),
        )

        assertEquals(listOf(RELAY_A_NODE, RELAY_B_NODE), map?.traceForwardList?.map { it.num })
        assertEquals(emptyList<Node>(), map?.traceBackList)
    }

    @Test
    fun tracerouteMapUsesRegistryBackupPositions() {
        val map = evaluateTracerouteMapAvailability(
            traceroute = forwardOnlyTracerouteText,
            nodesByNum = emptyMap(),
            nodeRegistryMap = mapOf(
                relayARegistry.nodeId to relayARegistry,
                relayBRegistry.nodeId to relayBRegistry,
            ),
        )

        assertEquals(listOf(RELAY_A_NODE, RELAY_B_NODE), map?.traceForwardList?.map { it.num })
    }

    @Test
    fun tracerouteMapUnavailableWithLessThanTwoDrawablePoints() {
        val map = evaluateTracerouteMapAvailability(
            traceroute = forwardOnlyTracerouteText,
            nodesByNum = mapOf(
                RELAY_A_NODE to positionedNode(RELAY_A_NODE, "Relay A", RELAY_A_LAT, RELAY_A_LON),
                RELAY_B_NODE to namedNode(RELAY_B_NODE, "Relay B"),
            ),
            nodeRegistryMap = emptyMap(),
        )

        assertNull(map)
    }

    private val fullTracerouteText = """
        Route traced toward destination:

        ■ Origin (ORG)
        ⇊ -5.0 dB
        ■ Relay A (RA)
        ⇊ -6.0 dB
        ■ Relay B (RB)
        ⇊ -7.0 dB
        ■ Destination (DST)

        Route traced back to us:

        ■ Destination (DST)
        ⇊ -8.0 dB
        ■ Relay B (RB)
        ⇊ -9.0 dB
        ■ Relay A (RA)
        ⇊ -10.0 dB
        ■ Origin (ORG)
    """.trimIndent()

    private val forwardOnlyTracerouteText = """
        Route traced toward destination:

        ■ Relay A (RA)
        ⇊ -6.0 dB
        ■ Relay B (RB)
    """.trimIndent()

    private val relayARegistry = registryNode(
        nodeNum = RELAY_A_NODE,
        nodeId = "!00000003",
        longName = "Relay A",
        shortName = "RA",
        latitude = RELAY_A_LAT,
        longitude = RELAY_A_LON,
    )

    private val relayBRegistry = registryNode(
        nodeNum = RELAY_B_NODE,
        nodeId = "!00000004",
        longName = "Relay B",
        shortName = "RB",
        latitude = RELAY_B_LAT,
        longitude = RELAY_B_LON,
    )

    private companion object {
        const val ORIGIN_NODE = 1
        const val DESTINATION_NODE = 2
        const val RELAY_A_NODE = 3
        const val RELAY_B_NODE = 4
        const val RELAY_A_LAT = 45.0
        const val RELAY_A_LON = 9.0
        const val RELAY_B_LAT = 45.2
        const val RELAY_B_LON = 9.2

        fun namedNode(
            nodeNum: Int,
            longName: String,
        ): Node {
            return Node(
                num = nodeNum,
                user = MeshProtos.User.newBuilder()
                    .setId("!%08x".format(nodeNum))
                    .setLongName(longName)
                    .setShortName(longName.take(4))
                    .build(),
            )
        }

        fun positionedNode(
            nodeNum: Int,
            longName: String,
            latitude: Double,
            longitude: Double,
        ): Node {
            return namedNode(nodeNum, longName).copy(
                position = MeshProtos.Position.newBuilder()
                    .setLatitudeI((latitude * COORDINATE_SCALE).toInt())
                    .setLongitudeI((longitude * COORDINATE_SCALE).toInt())
                    .build(),
            )
        }

        fun registryNode(
            nodeNum: Int,
            nodeId: String,
            longName: String,
            shortName: String,
            latitude: Double,
            longitude: Double,
        ): NodeRegistry {
            return NodeRegistry(
                nodeId = nodeId,
                nodeNum = nodeNum,
                longName = longName,
                shortName = shortName,
                defaultName = longName,
                latitudeI = (latitude * COORDINATE_SCALE).toInt(),
                longitudeI = (longitude * COORDINATE_SCALE).toInt(),
            )
        }

        const val COORDINATE_SCALE = 1e7
    }
}
