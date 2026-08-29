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

package com.geeksville.mesh

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.dao.DiscoveryDao
import com.geeksville.mesh.database.entity.DiscoveredNodeEntity
import com.geeksville.mesh.database.entity.DiscoveryNeighborType
import com.geeksville.mesh.database.entity.DiscoveryPresetResultEntity
import com.geeksville.mesh.database.entity.DiscoverySessionEntity
import com.geeksville.mesh.database.entity.DiscoverySessionStatus
import com.geeksville.mesh.database.entity.NodeRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos

@RunWith(AndroidJUnit4::class)
class DiscoveryDaoTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var discoveryDao: DiscoveryDao

    private val publicKey = ByteArray(32) { (it + 1).toByte() }

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MeshtasticDatabase::class.java).build()
        discoveryDao = database.discoveryDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun insertSessionResultAndNodesWithoutMutatingNodeRegistry() = runBlocking {
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = "!00000002",
                nodeNum = 2,
                longName = "Registry Node",
                shortName = "REG",
                publicKey = publicKey,
            )
        )

        val sessionId = discoveryDao.insertSession(discoverySession())
        val resultId = discoveryDao.insertPresetResultWithNodes(
            result = discoveryPresetResult(sessionId),
            nodes = listOf(
                DiscoveredNodeEntity(
                    presetResultId = 0,
                    nodeNum = 2,
                    nodeId = "!00000002",
                    longName = "Discovery Node",
                    shortName = "DSC",
                    defaultName = "!00000002",
                    neighborType = DiscoveryNeighborType.DIRECT,
                    latitude = 45.0,
                    longitude = 9.0,
                    snr = -4.5f,
                    rssi = -100,
                    packetCount = 3,
                )
            )
        )

        val session = discoveryDao.getSessions().first().single()
        val nodes = discoveryDao.getDiscoveredNodes(resultId)
        val registryNode = database.nodeRegistryDao().getById("!00000002")

        assertEquals(sessionId, session.id)
        assertEquals("LONG_FAST", discoveryDao.getPresetResults(sessionId).single().presetName)
        assertEquals("Discovery Node", nodes.single().longName)
        assertEquals("Registry Node", registryNode?.longName)
        assertArrayEquals(publicKey, registryNode?.publicKey)
    }

    @Test
    fun deletingSessionCascadesDiscoveryRows() = runBlocking {
        val sessionId = discoveryDao.insertSession(discoverySession())
        val resultId = discoveryDao.insertPresetResultWithNodes(
            result = discoveryPresetResult(sessionId),
            nodes = listOf(
                DiscoveredNodeEntity(
                    presetResultId = 0,
                    nodeNum = 3,
                    nodeId = "!00000003",
                    neighborType = DiscoveryNeighborType.MESH,
                )
            )
        )

        discoveryDao.deleteSession(sessionId)

        assertTrue(discoveryDao.getPresetResults(sessionId).isEmpty())
        assertTrue(discoveryDao.getDiscoveredNodes(resultId).isEmpty())
    }

    private fun discoverySession() = DiscoverySessionEntity(
        presetsScanned = "LONG_FAST",
        homePreset = "LongFast",
        completionStatus = DiscoverySessionStatus.IN_PROGRESS,
        totalDwellSeconds = 60,
        deviceAddress = "x00:11:22:33:44:55",
        homeLoraConfig = ConfigProtos.Config.LoRaConfig.newBuilder()
            .setUsePreset(true)
            .setModemPreset(ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST)
            .build(),
        homePrimaryChannel = ChannelProtos.ChannelSettings.newBuilder()
            .setName("home")
            .build(),
    )

    private fun discoveryPresetResult(sessionId: Long) = DiscoveryPresetResultEntity(
        sessionId = sessionId,
        presetName = "LONG_FAST",
        modemPresetValue = ConfigProtos.Config.LoRaConfig.ModemPreset.LONG_FAST.number,
        startedAt = 1,
        endedAt = 2,
        dwellSeconds = 1,
        uniqueNodes = 1,
        directNeighbors = 1,
        meshNeighbors = 0,
        messageCount = 0,
        sensorCount = 0,
        infrastructureCount = 0,
        packetCount = 3,
    )
}
