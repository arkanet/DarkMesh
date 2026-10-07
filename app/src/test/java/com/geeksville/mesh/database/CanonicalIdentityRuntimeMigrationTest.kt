/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.geeksville.mesh.database

import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.util.IdentityMigrationAuthority
import com.geeksville.mesh.util.MeshtasticCanonicalIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalIdentityRuntimeMigrationTest {
    @Test(expected = CanonicalIdentityRecoveryNotReadyException::class)
    fun outboundIsClosedUntilPersistentRecoveryInitializes() {
        CanonicalIdentityOutboundGate().resolve(OLD_NODE_NUM)
    }

    @Test
    fun outboundGateRedirectsOnlyAffectedIdentityAndNeverDeadlocksAfterRecovery() {
        val gate = CanonicalIdentityOutboundGate()
        gate.markInitialized()

        assertEquals(UNRELATED_NODE_NUM, gate.resolve(UNRELATED_NODE_NUM))
        gate.beginTransition(OLD_NODE_NUM)
        assertBlocked { gate.resolve(OLD_NODE_NUM) }
        assertEquals(UNRELATED_NODE_NUM, gate.resolve(UNRELATED_NODE_NUM))

        gate.redirect(OLD_NODE_NUM, NEW_NODE_NUM)
        assertEquals(NEW_NODE_NUM, gate.resolve(OLD_NODE_NUM))
        assertEquals(NEW_NODE_NUM, gate.resolve(NEW_NODE_NUM))
    }

    @Test
    fun outboundGateRetainsRestartSafeAliasesAndResolvesChains() {
        val gate = CanonicalIdentityOutboundGate()
        gate.redirect(OLD_NODE_NUM, NEW_NODE_NUM)
        gate.redirect(NEW_NODE_NUM, NEXT_NODE_NUM)
        gate.markInitialized()

        assertEquals(NEXT_NODE_NUM, gate.resolve(OLD_NODE_NUM))
        assertEquals(NEXT_NODE_NUM, gate.resolve(NEW_NODE_NUM))
        assertEquals(UNRELATED_NODE_NUM, gate.resolve(UNRELATED_NODE_NUM))
    }

    @Test
    fun inconsistentJournalRuleBlocksOnlyStaleIdentity() {
        val gate = CanonicalIdentityOutboundGate()
        gate.block(OLD_NODE_NUM)
        gate.markInitialized()

        assertBlocked { gate.resolve(OLD_NODE_NUM) }
        assertEquals(UNRELATED_NODE_NUM, gate.resolve(UNRELATED_NODE_NUM))
    }

    @Test
    fun detectorRequiresExactUsableCanonicalTrustedIdentity() {
        val key = publicKey(3)
        val canonical = MeshtasticCanonicalIdentity.canonicalNodeNum(key)
        val registry = listOf(registry(OLD_NODE_NUM, key))

        val candidates = TrustedCanonicalIdentityCandidateDetector.detect(
            identities = listOf(
                TrustedConfigIdentity(canonical, key.copyOf()),
                TrustedConfigIdentity(canonical + 1, key.copyOf()),
                TrustedConfigIdentity(canonical, ByteArray(31) { 1 }),
                TrustedConfigIdentity(LOCAL_NODE_NUM, publicKey(9)),
            ),
            registry = registry,
            localNodeNum = LOCAL_NODE_NUM,
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals(OLD_NODE_NUM, candidate.request.oldNodeNum)
        assertEquals(canonical, candidate.request.newNodeNum)
        assertTrue(candidate.request.storedPublicKey.contentEquals(key))
        assertEquals(IdentityMigrationAuthority.TRUSTED_CONFIG, candidate.request.authority)
    }

    @Test
    fun detectorHandlesMultipleIndependentCandidatesDeterministically() {
        val firstKey = publicKey(11)
        val secondKey = publicKey(71)
        val firstNew = MeshtasticCanonicalIdentity.canonicalNodeNum(firstKey)
        val secondNew = MeshtasticCanonicalIdentity.canonicalNodeNum(secondKey)
        val registry = listOf(
            registry(OLD_NODE_NUM, firstKey),
            registry(SECOND_OLD_NODE_NUM, secondKey),
        )

        val candidates = TrustedCanonicalIdentityCandidateDetector.detect(
            identities = listOf(
                TrustedConfigIdentity(secondNew, secondKey),
                TrustedConfigIdentity(firstNew, firstKey),
                TrustedConfigIdentity(firstNew, firstKey.copyOf()),
            ),
            registry = registry,
            localNodeNum = LOCAL_NODE_NUM,
        )

        assertEquals(listOf(firstNew, secondNew).sorted(), candidates.map { it.request.newNodeNum })
    }

    @Test
    fun detectorReportsMultipleOldOwnersForC0B1PolicyToBlock() {
        val key = publicKey(21)
        val canonical = MeshtasticCanonicalIdentity.canonicalNodeNum(key)
        val candidates = TrustedCanonicalIdentityCandidateDetector.detect(
            identities = listOf(TrustedConfigIdentity(canonical, key)),
            registry = listOf(
                registry(OLD_NODE_NUM, key),
                registry(SECOND_OLD_NODE_NUM, key),
            ),
            localNodeNum = LOCAL_NODE_NUM,
        )

        assertEquals(2, candidates.single().oldOwnerCount)
        assertEquals(minOf(OLD_NODE_NUM, SECOND_OLD_NODE_NUM), candidates.single().request.oldNodeNum)
    }

    @Test
    fun detectorProducesNoCandidateWithoutStoredCryptographicContinuity() {
        val key = publicKey(31)
        val canonical = MeshtasticCanonicalIdentity.canonicalNodeNum(key)

        val candidates = TrustedCanonicalIdentityCandidateDetector.detect(
            identities = listOf(TrustedConfigIdentity(canonical, key)),
            registry = listOf(registry(OLD_NODE_NUM, publicKey(32))),
            localNodeNum = LOCAL_NODE_NUM,
        )

        assertTrue(candidates.isEmpty())
    }

    private fun registry(nodeNum: Int, key: ByteArray) = NodeRegistry(
        nodeId = DataPacket.nodeNumToDefaultId(nodeNum),
        nodeNum = nodeNum,
        publicKey = key.copyOf(),
    )

    private fun publicKey(seed: Int) = ByteArray(32) { (seed + it).toByte() }

    private fun assertBlocked(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is CanonicalIdentityOutboundBlockedException)
    }

    private companion object {
        const val LOCAL_NODE_NUM = 10
        const val OLD_NODE_NUM = 101
        const val SECOND_OLD_NODE_NUM = 202
        const val NEW_NODE_NUM = 303
        const val NEXT_NODE_NUM = 404
        const val UNRELATED_NODE_NUM = 505
    }
}
