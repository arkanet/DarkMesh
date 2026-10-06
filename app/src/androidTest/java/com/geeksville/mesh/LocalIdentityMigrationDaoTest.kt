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
import com.geeksville.mesh.database.dao.NodeInfoDao
import com.geeksville.mesh.database.dao.PacketDao
import com.geeksville.mesh.database.entity.LocalDeviceContinuity
import com.geeksville.mesh.database.entity.MyNodeEntity
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.database.entity.Packet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.user

@RunWith(AndroidJUnit4::class)
class LocalIdentityMigrationDaoTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var nodeInfoDao: NodeInfoDao
    private lateinit var packetDao: PacketDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MeshtasticDatabase::class.java).build()
        nodeInfoDao = database.nodeInfoDao()
        packetDao = database.packetDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun deviceIdPersistsThroughRoom() {
        val expected = ByteArray(16) { (it + 1).toByte() }

        nodeInfoDao.setMyNodeInfo(myNode(nodeNum = OLD_NODE_NUM, deviceId = expected))

        assertArrayEquals(expected, nodeInfoDao.getMyNodeInfoSnapshot()?.deviceId)
    }

    @Test
    fun samePhysicalDeviceMigrationPreservesHistoryAndRemoteIdentity() = runBlocking {
        val deviceId = ByteArray(16) { (it + 1).toByte() }
        val originalPacket = packet(ownerNodeNum = OLD_NODE_NUM)
        nodeInfoDao.setMyNodeInfo(myNode(nodeNum = OLD_NODE_NUM, deviceId = deviceId))
        packetDao.insert(originalPacket)

        val result = nodeInfoDao.installNodeDB(
            myInfo = myNode(nodeNum = NEW_NODE_NUM, deviceId = deviceId.copyOf()),
            nodes = listOf(node(NEW_NODE_NUM), node(REMOTE_NODE_NUM)),
        )

        assertEquals(LocalDeviceContinuity.SAME_PHYSICAL_DEVICE_NODE_MIGRATION, result)
        assertEquals(0, packetCountForOwner(OLD_NODE_NUM))
        assertEquals(1, packetCountForOwner(NEW_NODE_NUM))
        val migratedPacket = packetDao.getAllPackets(Portnums.PortNum.TEXT_MESSAGE_APP_VALUE).first().single()
        assertEquals(REMOTE_NODE_ID, migratedPacket.data.from)
        assertEquals(REMOTE_CONTACT_KEY, migratedPacket.contact_key)
        assertTrue(nodeInfoDao.nodeDBbyNum().first().containsKey(REMOTE_NODE_NUM))
    }

    @Test
    fun differentPhysicalDeviceDoesNotMergeHistory() = runBlocking {
        nodeInfoDao.setMyNodeInfo(myNode(nodeNum = OLD_NODE_NUM, deviceId = byteArrayOf(1)))
        packetDao.insert(packet(ownerNodeNum = OLD_NODE_NUM))

        val result = nodeInfoDao.installNodeDB(
            myInfo = myNode(nodeNum = NEW_NODE_NUM, deviceId = byteArrayOf(2)),
            nodes = listOf(node(NEW_NODE_NUM)),
        )

        assertEquals(LocalDeviceContinuity.DIFFERENT_DEVICE, result)
        assertEquals(1, packetCountForOwner(OLD_NODE_NUM))
        assertEquals(0, packetCountForOwner(NEW_NODE_NUM))
    }

    @Test
    fun migrationIsRepeatSafe() = runBlocking {
        val deviceId = byteArrayOf(1, 2, 3)
        nodeInfoDao.setMyNodeInfo(myNode(nodeNum = OLD_NODE_NUM, deviceId = deviceId))
        packetDao.insert(packet(ownerNodeNum = OLD_NODE_NUM))
        val incoming = myNode(nodeNum = NEW_NODE_NUM, deviceId = deviceId.copyOf())
        val nodes = listOf(node(NEW_NODE_NUM))

        val first = nodeInfoDao.installNodeDB(incoming, nodes)
        val second = nodeInfoDao.installNodeDB(incoming, nodes)

        assertEquals(LocalDeviceContinuity.SAME_PHYSICAL_DEVICE_NODE_MIGRATION, first)
        assertEquals(LocalDeviceContinuity.SAME_PHYSICAL_DEVICE, second)
        assertEquals(0, packetCountForOwner(OLD_NODE_NUM))
        assertEquals(1, packetCountForOwner(NEW_NODE_NUM))
    }

    @Test
    fun failedInstallRollsBackOwnershipMigration() = runBlocking {
        val deviceId = byteArrayOf(1, 2, 3)
        nodeInfoDao.setMyNodeInfo(myNode(nodeNum = OLD_NODE_NUM, deviceId = deviceId))
        packetDao.insert(packet(ownerNodeNum = OLD_NODE_NUM))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_local_identity_install
            BEFORE INSERT ON my_node
            WHEN NEW.myNodeNum = $NEW_NODE_NUM
            BEGIN
                SELECT RAISE(ABORT, 'forced transaction failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching {
            nodeInfoDao.installNodeDB(
                myInfo = myNode(nodeNum = NEW_NODE_NUM, deviceId = deviceId.copyOf()),
                nodes = listOf(node(NEW_NODE_NUM)),
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(OLD_NODE_NUM, nodeInfoDao.getMyNodeInfoSnapshot()?.myNodeNum)
        assertEquals(1, packetCountForOwner(OLD_NODE_NUM))
        assertEquals(0, packetCountForOwner(NEW_NODE_NUM))
    }

    private fun myNode(nodeNum: Int, deviceId: ByteArray) = MyNodeEntity(
        myNodeNum = nodeNum,
        model = "heltec-v4",
        firmwareVersion = "2.8.1",
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 1L,
        messageTimeoutMsec = 300_000,
        minAppVersion = 30200,
        maxChannels = 8,
        hasWifi = false,
        deviceId = deviceId,
    )

    private fun node(nodeNum: Int) = NodeEntity(
        num = nodeNum,
        user = user {
            id = DataPacket.nodeNumToDefaultId(nodeNum)
            longName = "Node $nodeNum"
            shortName = "N$nodeNum"
            hwModel = MeshProtos.HardwareModel.HELTEC_V4
        },
        longName = "Node $nodeNum",
        shortName = "N$nodeNum",
    )

    private fun packet(ownerNodeNum: Int) = Packet(
        uuid = 0L,
        myNodeNum = ownerNodeNum,
        port_num = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
        contact_key = REMOTE_CONTACT_KEY,
        received_time = 1L,
        read = true,
        data = DataPacket(
            to = DataPacket.nodeNumToDefaultId(REMOTE_NODE_NUM),
            bytes = "history".encodeToByteArray(),
            dataType = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
            from = REMOTE_NODE_ID,
        ),
    )

    private fun packetCountForOwner(nodeNum: Int): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM packet WHERE myNodeNum = ?",
            arrayOf(nodeNum),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    companion object {
        private const val OLD_NODE_NUM = 10
        private const val NEW_NODE_NUM = 20
        private const val REMOTE_NODE_NUM = 30
        private const val REMOTE_NODE_ID = "!0000001e"
        private const val REMOTE_CONTACT_KEY = "0$REMOTE_NODE_ID"
    }
}
