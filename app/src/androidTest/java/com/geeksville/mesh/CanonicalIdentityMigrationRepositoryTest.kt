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
import com.geeksville.mesh.database.CanonicalIdentityMigrationOutcome
import com.geeksville.mesh.database.CanonicalIdentityMigrationPreflightFailure
import com.geeksville.mesh.database.CanonicalIdentityMigrationRepository
import com.geeksville.mesh.database.CanonicalIdentityMigrationRequest
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournalStates
import com.geeksville.mesh.database.entity.ContactSettings
import com.geeksville.mesh.database.entity.DiscoveredNodeEntity
import com.geeksville.mesh.database.entity.DiscoveryNeighborType
import com.geeksville.mesh.database.entity.DiscoveryPresetResultEntity
import com.geeksville.mesh.database.entity.DiscoverySessionEntity
import com.geeksville.mesh.database.entity.MeshLog
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.MyNodeEntity
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.database.entity.ReactionEntity
import com.geeksville.mesh.util.CanonicalIdentityAction
import com.geeksville.mesh.util.CanonicalIdentityReason
import com.geeksville.mesh.util.IdentityMigrationAuthority
import com.geeksville.mesh.util.MeshtasticCanonicalIdentity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.user

@RunWith(AndroidJUnit4::class)
class CanonicalIdentityMigrationRepositoryTest {
    private lateinit var database: MeshtasticDatabase
    private lateinit var repository: CanonicalIdentityMigrationRepository

    private val publicKey = ByteArray(32) { it.toByte() }
    private val canonicalNodeNum = MeshtasticCanonicalIdentity.canonicalNodeNum(publicKey)
    private val oldNodeId = DataPacket.nodeNumToDefaultId(OLD_NODE_NUM)
    private val canonicalNodeId get() = DataPacket.nodeNumToDefaultId(canonicalNodeNum)

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, MeshtasticDatabase::class.java).build()
        repository = CanonicalIdentityMigrationRepository(database, CoroutineDispatchers())
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun emptyTargetMigratesOwnedStateAndPreservesHistoricalEndpoints() = runBlocking {
        installSourceRegistry()
        database.nodeInfoDao().upsert(metadata(OLD_NODE_NUM, timestamp = 20L))
        database.nodeInfoDao().setMyNodeInfo(myNode(LOCAL_OWNER_NODE_NUM, byteArrayOf(1, 2, 3)))
        database.packetDao().insert(packet(status = MessageStatus.QUEUED, to = oldNodeId, from = oldNodeId))
        database.packetDao().insert(packet(status = MessageStatus.DELIVERED, to = oldNodeId, from = oldNodeId))
        database.packetDao().insert(
            packet(
                status = MessageStatus.DELIVERED,
                to = UNRELATED_NODE_ID,
                from = UNRELATED_NODE_ID,
                contactKey = "$oldNodeId-unrelated",
            ),
        )
        database.packetDao().upsertContactSettings(listOf(ContactSettings("7$oldNodeId", 30L)))
        database.packetDao().insert(ReactionEntity(7, oldNodeId, "wave", 40L))
        database.meshLogDao().insert(
            MeshLog(
                uuid = "preserved-log",
                message_type = "Packet",
                received_date = 45L,
                raw_message = "from legacy identity",
                fromNum = OLD_NODE_NUM,
                portNum = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
            ),
        )
        val discoverySessionId = database.discoveryDao().insertSession(
            DiscoverySessionEntity(
                timestamp = 46L,
                presetsScanned = "LONG_FAST",
                homePreset = "LongFast",
                totalDwellSeconds = 60L,
                deviceAddress = "00:11:22:33:44:55",
                homeLoraConfig = null,
            ),
        )
        val discoveryResultId = database.discoveryDao().insertPresetResultWithNodes(
            result = DiscoveryPresetResultEntity(
                sessionId = discoverySessionId,
                presetName = "LONG_FAST",
                modemPresetValue = 0,
                startedAt = 46L,
                endedAt = 47L,
                dwellSeconds = 1L,
                uniqueNodes = 1,
                directNeighbors = 1,
                meshNeighbors = 0,
                messageCount = 0,
                sensorCount = 0,
                infrastructureCount = 0,
                packetCount = 1,
            ),
            nodes = listOf(
                DiscoveredNodeEntity(
                    presetResultId = 0,
                    nodeNum = OLD_NODE_NUM.toLong(),
                    nodeId = oldNodeId,
                    neighborType = DiscoveryNeighborType.DIRECT,
                ),
            ),
        )
        val packetUuidsBefore = database.canonicalIdentityMigrationDao()
            .getPacketSnapshot()
            .map(Packet::uuid)
            .sorted()

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.ROOM_COMMITTED_EXTERNAL_PENDING, result.outcome)
        assertEquals(CanonicalIdentityAction.MIGRATE, result.decision.action)
        assertNull(database.nodeRegistryDao().getById(oldNodeId))
        val registry = database.nodeRegistryDao().getById(canonicalNodeId)
        assertEquals(canonicalNodeNum, registry?.nodeNum)
        assertArrayEquals(publicKey, registry?.publicKey)
        assertEquals(20L, metadataTimestamp(canonicalNodeNum))
        assertFalse(metadataExists(OLD_NODE_NUM))

        val packets = database.canonicalIdentityMigrationDao().getPacketSnapshot()
        assertEquals(packetUuidsBefore, packets.map(Packet::uuid).sorted())
        val queued = packets.single { it.data.status == MessageStatus.QUEUED }
        val historical = packets.single { it.data.status == MessageStatus.DELIVERED && it.contact_key == "3$canonicalNodeId" }
        val unrelated = packets.single { it.contact_key.endsWith("-unrelated") }
        assertEquals("3$canonicalNodeId", queued.contact_key)
        assertEquals(canonicalNodeId, queued.data.to)
        assertEquals(oldNodeId, queued.data.from)
        assertEquals(oldNodeId, historical.data.to)
        assertEquals(oldNodeId, historical.data.from)
        assertEquals("$oldNodeId-unrelated", unrelated.contact_key)
        assertEquals(LOCAL_OWNER_NODE_NUM, packets.single { it.uuid == queued.uuid }.myNodeNum)

        assertEquals(30L, database.packetDao().getContactSettings("7$canonicalNodeId")?.muteUntil)
        assertNull(database.packetDao().getContactSettings("7$oldNodeId"))
        val reactions = database.canonicalIdentityMigrationDao().getReactions(oldNodeId, canonicalNodeId)
        assertEquals(listOf(canonicalNodeId), reactions.map(ReactionEntity::userId).distinct())
        assertEquals(
            CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
            database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum)?.state,
        )
        assertEquals(LOCAL_OWNER_NODE_NUM, database.nodeInfoDao().getMyNodeInfoSnapshot()?.myNodeNum)
        val log = database.openHelper.readableDatabase.query(
            "SELECT from_num, message FROM log WHERE uuid = 'preserved-log'",
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0) to cursor.getString(1)
        }
        assertEquals(OLD_NODE_NUM to "from legacy identity", log)
        val discovered = database.discoveryDao().getDiscoveredNodes(discoveryResultId).single()
        assertEquals(OLD_NODE_NUM.toLong(), discovered.nodeNum)
        assertEquals(oldNodeId, discovered.nodeId)
    }

    @Test
    fun sameKeyTargetConvergesUsingNewestNonNullRegistryFields() = runBlocking {
        installSourceRegistry(lastSeen = 10L, shortName = "OLD", longName = "Old name")
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = canonicalNodeId,
                nodeNum = canonicalNodeNum,
                publicKey = publicKey.copyOf(),
                longName = "Canonical name",
                shortName = null,
                lastSeen = 20L,
            ),
        )

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityAction.CONVERGE, result.decision.action)
        val merged = database.nodeRegistryDao().getById(canonicalNodeId)
        assertEquals("Canonical name", merged?.longName)
        assertEquals("OLD", merged?.shortName)
        assertEquals(20L, merged?.lastSeen)
        assertNull(database.nodeRegistryDao().getById(oldNodeId))
    }

    @Test
    fun partialTargetWithoutUsableKeyIsMergedDeterministically() = runBlocking {
        installSourceRegistry(lastSeen = 30L, shortName = "SRC", longName = "Source")
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = canonicalNodeId,
                nodeNum = canonicalNodeNum,
                publicKey = null,
                defaultName = "Target default",
                lastSeen = 20L,
            ),
        )

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.ROOM_COMMITTED_EXTERNAL_PENDING, result.outcome)
        assertEquals(CanonicalIdentityReason.PARTIAL_TARGET_MERGEABLE, result.decision.reason)
        val merged = database.nodeRegistryDao().getById(canonicalNodeId)
        assertArrayEquals(publicKey, merged?.publicKey)
        assertEquals("Source", merged?.longName)
        assertEquals("Target default", merged?.defaultName)
        assertEquals(30L, merged?.lastSeen)
    }

    @Test
    fun differentUsableTargetKeyBlocksWithoutMutationOrJournal() = runBlocking {
        val conflictingKey = ByteArray(32) { (it + 64).toByte() }
        installSourceRegistry()
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = canonicalNodeId,
                nodeNum = canonicalNodeNum,
                publicKey = conflictingKey,
                lastSeen = 20L,
            ),
        )

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.BLOCKED, result.outcome)
        assertEquals(CanonicalIdentityReason.TARGET_KEY_CONFLICT, result.decision.reason)
        assertArrayEquals(publicKey, database.nodeRegistryDao().getById(oldNodeId)?.publicKey)
        assertArrayEquals(conflictingKey, database.nodeRegistryDao().getById(canonicalNodeId)?.publicKey)
        assertNull(database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum))
    }

    @Test
    fun duplicateOldKeyOwnerBlocksWithoutMutation() = runBlocking {
        installSourceRegistry()
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = UNRELATED_NODE_ID,
                nodeNum = UNRELATED_NODE_NUM,
                publicKey = publicKey.copyOf(),
            ),
        )

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.BLOCKED, result.outcome)
        assertEquals(CanonicalIdentityReason.MULTIPLE_OLD_OWNERS, result.decision.reason)
        assertTrue(database.nodeRegistryDao().getById(oldNodeId) != null)
        assertTrue(database.nodeRegistryDao().getById(UNRELATED_NODE_ID) != null)
        assertNull(database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum))
    }

    @Test
    fun collisionsKeepHighestTimestampOrStrongestMute() = runBlocking {
        installSourceRegistry()
        database.nodeInfoDao().upsert(metadata(OLD_NODE_NUM, timestamp = 90L))
        database.nodeInfoDao().upsert(metadata(canonicalNodeNum, timestamp = 80L))
        database.packetDao().upsertContactSettings(
            listOf(
                ContactSettings("2$oldNodeId", 100L),
                ContactSettings("2$canonicalNodeId", 200L),
            ),
        )
        database.packetDao().insert(ReactionEntity(11, oldNodeId, "ok", 300L))
        database.packetDao().insert(ReactionEntity(11, canonicalNodeId, "ok", 400L))

        repository.executeCanonicalIdentityMigration(request())

        assertEquals(90L, metadataTimestamp(canonicalNodeNum))
        assertEquals(200L, database.packetDao().getContactSettings("2$canonicalNodeId")?.muteUntil)
        val reaction = database.canonicalIdentityMigrationDao()
            .getReactions(oldNodeId, canonicalNodeId)
            .single { it.replyId == 11 && it.emoji == "ok" }
        assertEquals(canonicalNodeId, reaction.userId)
        assertEquals(400L, reaction.timestamp)
    }

    @Test
    fun repeatedExecutionIsNoOpForPendingAndCompleteJournal() = runBlocking {
        installSourceRegistry()

        val first = repository.executeCanonicalIdentityMigration(request())
        val pendingJournal = database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum)
        val second = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.ROOM_COMMITTED_EXTERNAL_PENDING, first.outcome)
        assertEquals(CanonicalIdentityMigrationOutcome.ALREADY_PENDING, second.outcome)
        assertEquals(1, tableCount("canonical_identity_migration"))
        assertEquals(1, tableCount("node_registry"))
        assertEquals(
            1,
            database.canonicalIdentityMigrationDao().updateJournalState(
                oldNodeNum = OLD_NODE_NUM,
                newNodeNum = canonicalNodeNum,
                state = CanonicalIdentityMigrationJournalStates.COMPLETE,
                updatedAt = requireNotNull(pendingJournal).updatedAt + 1,
            ),
        )

        val complete = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.ALREADY_COMPLETE, complete.outcome)
        assertEquals(1, tableCount("canonical_identity_migration"))
        assertEquals(1, tableCount("node_registry"))
    }

    @Test
    fun liveMeshAuthorityCannotTriggerMigration() = runBlocking {
        installSourceRegistry()

        val result = repository.executeCanonicalIdentityMigration(
            request(authority = IdentityMigrationAuthority.LIVE_MESH),
        )

        assertEquals(CanonicalIdentityMigrationOutcome.PRESERVED, result.outcome)
        assertEquals(CanonicalIdentityReason.LIVE_MESH_NOT_AUTHORIZED, result.decision.reason)
        assertTrue(database.nodeRegistryDao().getById(oldNodeId) != null)
        assertNull(database.nodeRegistryDao().getById(canonicalNodeId))
        assertNull(database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum))
    }

    @Test
    fun sourceKeyOwnershipMismatchBlocksAcceptedPolicy() = runBlocking {
        installSourceRegistry(publicKey = ByteArray(32) { (it + 1).toByte() })

        val result = repository.executeCanonicalIdentityMigration(request())

        assertEquals(CanonicalIdentityMigrationOutcome.BLOCKED, result.outcome)
        assertEquals(
            CanonicalIdentityMigrationPreflightFailure.OLD_KEY_OWNERSHIP_MISMATCH,
            result.preflightFailure,
        )
        assertTrue(database.nodeRegistryDao().getById(oldNodeId) != null)
        assertNull(database.nodeRegistryDao().getById(canonicalNodeId))
    }

    @Test
    fun forcedJournalFailureRollsBackWritesThatAlreadyStarted() = runBlocking {
        installSourceRegistry()
        database.nodeRegistryDao().upsert(
            NodeRegistry(nodeId = UNRELATED_NODE_ID, nodeNum = UNRELATED_NODE_NUM, lastSeen = 99L),
        )
        database.nodeInfoDao().upsert(node(UNRELATED_NODE_NUM))
        database.nodeInfoDao().upsert(metadata(OLD_NODE_NUM, 50L))
        database.packetDao().insert(packet(MessageStatus.QUEUED, oldNodeId, oldNodeId))
        database.packetDao().upsertContactSettings(listOf(ContactSettings("3$oldNodeId", 60L)))
        database.packetDao().insert(ReactionEntity(9, oldNodeId, "rollback", 70L))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_canonical_identity_journal
            BEFORE INSERT ON canonical_identity_migration
            BEGIN
                SELECT RAISE(ABORT, 'forced transaction failure');
            END
            """.trimIndent(),
        )

        val failure = runCatching { repository.executeCanonicalIdentityMigration(request()) }

        assertTrue(failure.isFailure)
        assertArrayEquals(publicKey, database.nodeRegistryDao().getById(oldNodeId)?.publicKey)
        assertNull(database.nodeRegistryDao().getById(canonicalNodeId))
        assertTrue(database.nodeRegistryDao().getById(UNRELATED_NODE_ID) != null)
        assertEquals(1, tableRowCount("nodes", "num", UNRELATED_NODE_NUM))
        assertEquals(50L, metadataTimestamp(OLD_NODE_NUM))
        assertFalse(metadataExists(canonicalNodeNum))
        val packet = database.canonicalIdentityMigrationDao().getPacketSnapshot().single()
        assertEquals("3$oldNodeId", packet.contact_key)
        assertEquals(oldNodeId, packet.data.to)
        assertEquals(60L, database.packetDao().getContactSettings("3$oldNodeId")?.muteUntil)
        assertEquals(oldNodeId, database.canonicalIdentityMigrationDao().getReactions(oldNodeId, canonicalNodeId).single().userId)
        assertNull(database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum))
    }

    @Test
    fun c0aOwnershipAndC0bIdentityMigrationsRemainIndependent() = runBlocking {
        val deviceId = byteArrayOf(9, 8, 7)
        installSourceRegistry()
        database.nodeInfoDao().setMyNodeInfo(myNode(LOCAL_OWNER_NODE_NUM, deviceId))
        database.packetDao().insert(packet(MessageStatus.DELIVERED, oldNodeId, oldNodeId))

        repository.executeCanonicalIdentityMigration(request())
        var migratedPacket = database.canonicalIdentityMigrationDao().getPacketSnapshot().single()
        assertEquals(LOCAL_OWNER_NODE_NUM, migratedPacket.myNodeNum)
        assertEquals("3$canonicalNodeId", migratedPacket.contact_key)

        database.nodeInfoDao().installNodeDB(
            myInfo = myNode(NEW_LOCAL_OWNER_NODE_NUM, deviceId.copyOf()),
            nodes = listOf(node(NEW_LOCAL_OWNER_NODE_NUM)),
        )

        migratedPacket = database.canonicalIdentityMigrationDao().getPacketSnapshot().single()
        assertEquals(NEW_LOCAL_OWNER_NODE_NUM, migratedPacket.myNodeNum)
        assertEquals("3$canonicalNodeId", migratedPacket.contact_key)
        assertEquals(oldNodeId, migratedPacket.data.to)
        assertEquals(CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
            database.canonicalIdentityMigrationDao().getJournal(OLD_NODE_NUM, canonicalNodeNum)?.state)
    }

    private suspend fun installSourceRegistry(
        publicKey: ByteArray = this.publicKey,
        lastSeen: Long = 10L,
        shortName: String? = "OLD",
        longName: String? = "Legacy identity",
    ) {
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = oldNodeId,
                nodeNum = OLD_NODE_NUM,
                publicKey = publicKey,
                shortName = shortName,
                longName = longName,
                lastSeen = lastSeen,
            ),
        )
    }

    private fun request(
        authority: IdentityMigrationAuthority = IdentityMigrationAuthority.TRUSTED_CONFIG,
    ) = CanonicalIdentityMigrationRequest(
        oldNodeNum = OLD_NODE_NUM,
        newNodeNum = canonicalNodeNum,
        storedPublicKey = publicKey.copyOf(),
        incomingPublicKey = publicKey.copyOf(),
        authority = authority,
    )

    private fun packet(
        status: MessageStatus,
        to: String,
        from: String,
        contactKey: String = "3$oldNodeId",
    ) = Packet(
        uuid = 0L,
        myNodeNum = LOCAL_OWNER_NODE_NUM,
        port_num = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
        contact_key = contactKey,
        received_time = status.ordinal.toLong(),
        read = true,
        data = DataPacket(
            to = to,
            bytes = status.name.encodeToByteArray(),
            dataType = Portnums.PortNum.TEXT_MESSAGE_APP_VALUE,
            from = from,
            status = status,
        ),
    )

    private fun metadata(nodeNum: Int, timestamp: Long) = MetadataEntity(
        num = nodeNum,
        proto = MeshProtos.DeviceMetadata.getDefaultInstance(),
        timestamp = timestamp,
    )

    private fun myNode(nodeNum: Int, deviceId: ByteArray) = MyNodeEntity(
        myNodeNum = nodeNum,
        model = "test",
        firmwareVersion = "2.8.1",
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 1L,
        messageTimeoutMsec = 300_000,
        minAppVersion = 1,
        maxChannels = 8,
        hasWifi = false,
        deviceId = deviceId,
    )

    private fun node(nodeNum: Int) = NodeEntity(
        num = nodeNum,
        user = user {
            id = DataPacket.nodeNumToDefaultId(nodeNum)
            longName = "Local"
            shortName = "LOC"
            hwModel = MeshProtos.HardwareModel.ANDROID_SIM
        },
        longName = "Local",
        shortName = "LOC",
    )

    private fun metadataTimestamp(nodeNum: Int): Long = database.openHelper.readableDatabase.query(
        "SELECT timestamp FROM metadata WHERE num = ?",
        arrayOf(nodeNum),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun metadataExists(nodeNum: Int): Boolean = database.openHelper.readableDatabase.query(
        "SELECT 1 FROM metadata WHERE num = ?",
        arrayOf(nodeNum),
    ).use { it.moveToFirst() }

    private fun tableCount(table: String): Int = database.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM $table",
    ).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun tableRowCount(table: String, column: String, value: Int): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE $column = ?",
            arrayOf(value),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    companion object {
        private const val OLD_NODE_NUM = 42
        private const val LOCAL_OWNER_NODE_NUM = 100
        private const val NEW_LOCAL_OWNER_NODE_NUM = 101
        private const val UNRELATED_NODE_NUM = 77
        private const val UNRELATED_NODE_ID = "!0000004d"
    }
}
