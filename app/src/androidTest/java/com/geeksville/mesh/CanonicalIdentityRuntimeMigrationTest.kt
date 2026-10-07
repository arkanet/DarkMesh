/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.geeksville.mesh

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geeksville.mesh.database.CanonicalIdentityMigrationRepository
import com.geeksville.mesh.database.CanonicalIdentityOutboundBlockedException
import com.geeksville.mesh.database.CanonicalIdentityRuntimeMigration
import com.geeksville.mesh.database.CanonicalIdentityRuntimeOutcome
import com.geeksville.mesh.database.MeshtasticDatabase
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournal
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournalStates
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.prefs.UserPrefs
import com.geeksville.mesh.ui.COMPRESSED_CHATS_PREFS
import com.geeksville.mesh.util.MeshtasticCanonicalIdentity
import com.geeksville.mesh.util.PkiUtils
import com.google.protobuf.ByteString
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
import org.meshtastic.proto.user

@RunWith(AndroidJUnit4::class)
class CanonicalIdentityRuntimeMigrationTest {
    private lateinit var application: Application
    private lateinit var database: MeshtasticDatabase
    private lateinit var repository: CanonicalIdentityMigrationRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        application = context.applicationContext as Application
        database = Room.inMemoryDatabaseBuilder(context, MeshtasticDatabase::class.java).build()
        repository = CanonicalIdentityMigrationRepository(database, CoroutineDispatchers())
        compressionPrefs().edit().clear().commit()
        plannedMessagePrefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        compressionPrefs().edit().clear().commit()
        plannedMessagePrefs().edit().clear().commit()
        database.close()
    }

    @Test
    fun trustedInstallMigratesExternalStateThenCompletesJournal() = runBlocking {
        val key = publicKey(1)
        val oldNodeNum = 42
        val newNodeNum = MeshtasticCanonicalIdentity.canonicalNodeNum(key)
        val oldNodeId = DataPacket.nodeNumToDefaultId(oldNodeNum)
        val newNodeId = DataPacket.nodeNumToDefaultId(newNodeNum)
        installRegistry(oldNodeNum, key)
        database.nodeInfoDao().upsert(
            MetadataEntity(
                num = oldNodeNum,
                proto = MeshProtos.DeviceMetadata.getDefaultInstance(),
                timestamp = 99L,
            ),
        )

        compressionPrefs().edit()
            .putBoolean("7$oldNodeId", true)
            .putBoolean("7$newNodeId", false)
            .putBoolean("7${oldNodeId}suffix", true)
            .commit()
        plannedMessagePrefs().edit()
            .putString(oldNodeNum.toString(), "MON 10:00 — source\nTUE 11:00 — shared")
            .putString(newNodeNum.toString(), "WED 12:00 — target\nTUE 11:00 — shared")
            .commit()

        val runtime = newRuntime()
        runtime.recoverPersistedMigrations()
        val result = runtime.installTrustedConfigIdentities(
            nodeInfos = listOf(nodeInfo(newNodeNum, key)),
            localNodeNum = LOCAL_NODE_NUM,
        ).single()

        assertEquals(CanonicalIdentityRuntimeOutcome.COMPLETE, result.outcome)
        assertEquals(newNodeNum, runtime.canonicalizeOutboundNodeNum(oldNodeNum))
        assertTrue(compressionPrefs().getBoolean("7$newNodeId", false))
        assertFalse(compressionPrefs().contains("7$oldNodeId"))
        assertTrue(compressionPrefs().getBoolean("7${oldNodeId}suffix", false))
        assertEquals(
            "WED 12:00 — target\nTUE 11:00 — shared\nMON 10:00 — source",
            plannedMessagePrefs().getString(newNodeNum.toString(), null),
        )
        assertFalse(plannedMessagePrefs().contains(oldNodeNum.toString()))
        assertEquals(
            CanonicalIdentityMigrationJournalStates.COMPLETE,
            database.canonicalIdentityMigrationDao().getJournal(oldNodeNum, newNodeNum)?.state,
        )
        assertNull(database.nodeRegistryDao().getById(oldNodeId))
        val canonicalRegistry = database.nodeRegistryDao().getById(newNodeId)
        assertArrayEquals(key, canonicalRegistry?.publicKey)
        assertEquals(ByteString.copyFrom(key), PkiUtils.effectivePublicKey(node = null, registry = canonicalRegistry))
        assertFalse(PkiUtils.isMismatchPublicKey(PkiUtils.registryPublicKey(canonicalRegistry)))
        assertEquals(
            listOf(newNodeNum),
            database.canonicalIdentityMigrationDao()
                .getMetadata(oldNodeNum, newNodeNum)
                .map(MetadataEntity::num),
        )
    }

    @Test
    fun externalFailureLeavesPendingAndRepositoryRecreationRecoversIdempotently() = runBlocking {
        val key = publicKey(11)
        val oldNodeNum = 52
        val newNodeNum = MeshtasticCanonicalIdentity.canonicalNodeNum(key)
        installRegistry(oldNodeNum, key)
        plannedMessagePrefs().edit().putInt(oldNodeNum.toString(), 7).commit()

        val firstRuntime = newRuntime()
        firstRuntime.recoverPersistedMigrations()
        val first = firstRuntime.installTrustedConfigIdentities(
            nodeInfos = listOf(nodeInfo(newNodeNum, key)),
            localNodeNum = LOCAL_NODE_NUM,
        ).single()

        assertEquals(CanonicalIdentityRuntimeOutcome.EXTERNAL_PENDING, first.outcome)
        assertEquals(newNodeNum, firstRuntime.canonicalizeOutboundNodeNum(oldNodeNum))
        assertEquals(
            CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
            database.canonicalIdentityMigrationDao().getJournal(oldNodeNum, newNodeNum)?.state,
        )

        plannedMessagePrefs().edit()
            .remove(oldNodeNum.toString())
            .putString(oldNodeNum.toString(), "THU 13:00 — recovered")
            .commit()
        val recreatedRuntime = newRuntime()
        val recovered = recreatedRuntime.recoverPersistedMigrations().single()
        val repeated = recreatedRuntime.recoverPersistedMigrations().single()

        assertEquals(CanonicalIdentityRuntimeOutcome.COMPLETE, recovered.outcome)
        assertEquals(CanonicalIdentityRuntimeOutcome.COMPLETE, repeated.outcome)
        assertEquals(newNodeNum, recreatedRuntime.canonicalizeOutboundNodeNum(oldNodeNum))
        assertEquals(
            CanonicalIdentityMigrationJournalStates.COMPLETE,
            database.canonicalIdentityMigrationDao().getJournal(oldNodeNum, newNodeNum)?.state,
        )
        assertEquals(
            "THU 13:00 — recovered",
            plannedMessagePrefs().getString(newNodeNum.toString(), null),
        )
    }

    @Test
    fun inconsistentPendingJournalIsRetainedAndBlocksOnlyStaleIdentity() = runBlocking {
        val key = publicKey(21)
        val oldNodeNum = 62
        val newNodeNum = MeshtasticCanonicalIdentity.canonicalNodeNum(key)
        database.canonicalIdentityMigrationDao().upsertJournal(
            CanonicalIdentityMigrationJournal(
                oldNodeNum = oldNodeNum,
                newNodeNum = newNodeNum,
                publicKey = key,
                state = CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val runtime = newRuntime()
        val result = runtime.recoverPersistedMigrations().single()

        assertEquals(CanonicalIdentityRuntimeOutcome.BLOCKED, result.outcome)
        assertTrue(
            runCatching { runtime.canonicalizeOutboundNodeNum(oldNodeNum) }.exceptionOrNull()
                is CanonicalIdentityOutboundBlockedException,
        )
        assertEquals(UNRELATED_NODE_NUM, runtime.canonicalizeOutboundNodeNum(UNRELATED_NODE_NUM))
        assertEquals(
            CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
            database.canonicalIdentityMigrationDao().getJournal(oldNodeNum, newNodeNum)?.state,
        )
    }

    @Test
    fun multipleCandidatesAreIndependentAndConflictDoesNotCorruptValidMigration() = runBlocking {
        val validKey = publicKey(31)
        val blockedKey = publicKey(71)
        val conflictingKey = publicKey(111)
        val validOld = 72
        val blockedOld = 82
        val validNew = MeshtasticCanonicalIdentity.canonicalNodeNum(validKey)
        val blockedNew = MeshtasticCanonicalIdentity.canonicalNodeNum(blockedKey)
        installRegistry(validOld, validKey)
        installRegistry(blockedOld, blockedKey)
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = DataPacket.nodeNumToDefaultId(blockedNew),
                nodeNum = blockedNew,
                publicKey = conflictingKey,
            ),
        )

        val runtime = newRuntime()
        runtime.recoverPersistedMigrations()
        val results = runtime.installTrustedConfigIdentities(
            nodeInfos = listOf(nodeInfo(blockedNew, blockedKey), nodeInfo(validNew, validKey)),
            localNodeNum = LOCAL_NODE_NUM,
        )

        assertEquals(CanonicalIdentityRuntimeOutcome.COMPLETE, results.single { it.oldNodeNum == validOld }.outcome)
        assertEquals(CanonicalIdentityRuntimeOutcome.BLOCKED, results.single { it.oldNodeNum == blockedOld }.outcome)
        assertEquals(
            CanonicalIdentityMigrationJournalStates.COMPLETE,
            database.canonicalIdentityMigrationDao().getJournal(validOld, validNew)?.state,
        )
        assertNull(database.canonicalIdentityMigrationDao().getJournal(blockedOld, blockedNew))
        assertArrayEquals(
            blockedKey,
            database.nodeRegistryDao().getById(DataPacket.nodeNumToDefaultId(blockedOld))?.publicKey,
        )
        assertArrayEquals(
            conflictingKey,
            database.nodeRegistryDao().getById(DataPacket.nodeNumToDefaultId(blockedNew))?.publicKey,
        )
    }

    private fun newRuntime() = CanonicalIdentityRuntimeMigration(repository, application)

    private suspend fun installRegistry(nodeNum: Int, key: ByteArray) {
        database.nodeRegistryDao().upsert(
            NodeRegistry(
                nodeId = DataPacket.nodeNumToDefaultId(nodeNum),
                nodeNum = nodeNum,
                publicKey = key.copyOf(),
            ),
        )
    }

    private fun nodeInfo(nodeNum: Int, key: ByteArray): MeshProtos.NodeInfo =
        MeshProtos.NodeInfo.newBuilder()
            .setNum(nodeNum)
            .setUser(
                user {
                    id = DataPacket.nodeNumToDefaultId(nodeNum)
                    longName = "Canonical $nodeNum"
                    shortName = "CAN"
                    hwModel = MeshProtos.HardwareModel.ANDROID_SIM
                    this.publicKey = ByteString.copyFrom(key)
                },
            )
            .build()

    private fun publicKey(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun compressionPrefs() =
        application.getSharedPreferences(COMPRESSED_CHATS_PREFS, Context.MODE_PRIVATE)

    private fun plannedMessagePrefs() = application.getSharedPreferences(
        UserPrefs.PlannedMessage.SHARED_PLANNED_MSG_PREFS,
        Context.MODE_PRIVATE,
    )

    private companion object {
        const val LOCAL_NODE_NUM = 900
        const val UNRELATED_NODE_NUM = 901
    }
}
