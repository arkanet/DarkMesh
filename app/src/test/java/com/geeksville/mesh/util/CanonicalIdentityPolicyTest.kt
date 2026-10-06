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

package com.geeksville.mesh.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalIdentityPolicyTest {
    private val canonicalKey = ByteArray(MeshtasticCanonicalIdentity.PUBLIC_KEY_SIZE_BYTES) { it.toByte() }
    private val canonicalNodeNum = MeshtasticCanonicalIdentity.canonicalNodeNum(canonicalKey)

    @Test
    fun crc32MatchesStandardFixture() {
        assertEquals(
            0xCBF43926L.toInt(),
            MeshtasticCanonicalIdentity.crc32("123456789".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun canonicalNodeNumMatchesZeroThroughOneFFixture() {
        assertEquals(0x91267E8AL.toInt(), canonicalNodeNum)
    }

    @Test
    fun canonicalNodeNumMatchesAllFfFixture() {
        val allFf = ByteArray(MeshtasticCanonicalIdentity.PUBLIC_KEY_SIZE_BYTES) { 0xFF.toByte() }

        assertEquals(0xFF6CAB0BL.toInt(), MeshtasticCanonicalIdentity.canonicalNodeNum(allFf))
    }

    @Test
    fun canonicalNodeNumPreservesSignedHighBitPattern() {
        assertTrue(canonicalNodeNum < 0)
        assertEquals(0x91267E8AL, canonicalNodeNum.toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun canonicalIdentityRequiresUsableExactLengthPublicKey() {
        assertFalse(MeshtasticCanonicalIdentity.isUsablePublicKey(ByteArray(0)))
        assertFalse(MeshtasticCanonicalIdentity.isUsablePublicKey(ByteArray(31) { 1 }))
        assertFalse(MeshtasticCanonicalIdentity.isUsablePublicKey(ByteArray(33) { 1 }))
        assertFalse(MeshtasticCanonicalIdentity.isUsablePublicKey(ByteArray(32)))
        assertTrue(MeshtasticCanonicalIdentity.isUsablePublicKey(canonicalKey))

        listOf(ByteArray(0), ByteArray(31) { 1 }, ByteArray(33) { 1 }, ByteArray(32)).forEach { key ->
            assertThrows(IllegalArgumentException::class.java) {
                MeshtasticCanonicalIdentity.canonicalNodeNum(key)
            }
        }
    }

    @Test
    fun operationalNodeNumPolicyRejectsOnlyReservedValues() {
        listOf(0, 1, 2, 3, -1).forEach { nodeNum ->
            assertFalse(MeshtasticCanonicalIdentity.isUsableOperationalNodeNum(nodeNum))
        }
        listOf(4, Int.MAX_VALUE, Int.MIN_VALUE, -2).forEach { nodeNum ->
            assertTrue(MeshtasticCanonicalIdentity.isUsableOperationalNodeNum(nodeNum))
        }
    }

    @Test
    fun caseATrustedCanonicalTransitionMigrates() {
        assertDecision(
            transition(),
            CanonicalIdentityAction.MIGRATE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.CANONICAL_TARGET_AVAILABLE,
        )
    }

    @Test
    fun caseBTrustedNonCanonicalTransitionIsRejectedAsMismatch() {
        assertDecision(
            transition(newNodeNum = canonicalNodeNum xor 0x100),
            CanonicalIdentityAction.REJECT,
            CanonicalIdentityStatus.MISMATCH,
            CanonicalIdentityReason.NON_CANONICAL_TARGET,
        )
    }

    @Test
    fun caseCLiveMeshCannotAuthorizeCanonicalMigration() {
        assertDecision(
            transition(authority = IdentityMigrationAuthority.LIVE_MESH),
            CanonicalIdentityAction.PRESERVE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.LIVE_MESH_NOT_AUTHORIZED,
        )
    }

    @Test
    fun caseDDifferentKeyAtSameNodeIsPreservedAsMismatch() {
        assertDecision(
            transition(
                newNodeNum = OLD_NODE_NUM,
                incomingPublicKey = differentKey(),
            ),
            CanonicalIdentityAction.PRESERVE,
            CanonicalIdentityStatus.MISMATCH,
            CanonicalIdentityReason.KEY_CHANGED_AT_SAME_NODE,
        )
    }

    @Test
    fun caseEDifferentKeyAtDifferentNodeIsIndependent() {
        assertDecision(
            transition(incomingPublicKey = differentKey()),
            CanonicalIdentityAction.PRESERVE,
            CanonicalIdentityStatus.INDEPENDENT,
            CanonicalIdentityReason.INDEPENDENT_IDENTITY,
        )
    }

    @Test
    fun caseFUnusableKeyCannotMigrateAcrossNodeNums() {
        listOf(
            ByteArray(0),
            ByteArray(MeshtasticCanonicalIdentity.PUBLIC_KEY_SIZE_BYTES),
            ByteArray(MeshtasticCanonicalIdentity.PUBLIC_KEY_SIZE_BYTES - 1) { 1 },
        ).forEach { unusableKey ->
            assertDecision(
                transition(incomingPublicKey = unusableKey),
                CanonicalIdentityAction.REJECT,
                CanonicalIdentityStatus.UNUSABLE,
                CanonicalIdentityReason.UNUSABLE_PUBLIC_KEY,
            )
            assertDecision(
                transition(storedPublicKey = unusableKey),
                CanonicalIdentityAction.REJECT,
                CanonicalIdentityStatus.UNUSABLE,
                CanonicalIdentityReason.UNUSABLE_PUBLIC_KEY,
            )
        }
    }

    @Test
    fun caseGCanonicalCollisionNeverOverwritesDifferentKey() {
        assertDecision(
            transition(targetState = CanonicalIdentityTargetState.DIFFERENT_USABLE_PUBLIC_KEY),
            CanonicalIdentityAction.BLOCK,
            CanonicalIdentityStatus.MISMATCH,
            CanonicalIdentityReason.TARGET_KEY_CONFLICT,
        )
    }

    @Test
    fun caseHTargetWithSameKeyConvergesOnlyWhenOldRemnantsExist() {
        assertDecision(
            transition(targetState = CanonicalIdentityTargetState.SAME_PUBLIC_KEY),
            CanonicalIdentityAction.CONVERGE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.TARGET_ALREADY_OWNS_KEY,
        )
        assertDecision(
            transition(
                targetState = CanonicalIdentityTargetState.SAME_PUBLIC_KEY,
                hasOldIdentityRemnants = false,
            ),
            CanonicalIdentityAction.PRESERVE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.ALREADY_CONVERGED,
        )
    }

    @Test
    fun caseITargetWithDifferentUsableKeyIsBlocked() {
        assertDecision(
            transition(targetState = CanonicalIdentityTargetState.DIFFERENT_USABLE_PUBLIC_KEY),
            CanonicalIdentityAction.BLOCK,
            CanonicalIdentityStatus.MISMATCH,
            CanonicalIdentityReason.TARGET_KEY_CONFLICT,
        )
    }

    @Test
    fun caseJPartialTargetRequiresDeterministicMergeability() {
        assertDecision(
            transition(targetState = CanonicalIdentityTargetState.PARTIAL_MERGEABLE),
            CanonicalIdentityAction.MIGRATE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.PARTIAL_TARGET_MERGEABLE,
        )
        assertDecision(
            transition(targetState = CanonicalIdentityTargetState.PARTIAL_AMBIGUOUS),
            CanonicalIdentityAction.BLOCK,
            CanonicalIdentityStatus.AMBIGUOUS,
            CanonicalIdentityReason.TARGET_PARTIAL_AMBIGUOUS,
        )
    }

    @Test
    fun multipleOldOwnersBlockCanonicalMigration() {
        assertDecision(
            transition(hasMultipleOldOwners = true),
            CanonicalIdentityAction.BLOCK,
            CanonicalIdentityStatus.AMBIGUOUS,
            CanonicalIdentityReason.MULTIPLE_OLD_OWNERS,
        )
    }

    @Test
    fun reservedTargetCannotQualify() {
        assertDecision(
            transition(newNodeNum = 0),
            CanonicalIdentityAction.REJECT,
            CanonicalIdentityStatus.MISMATCH,
            CanonicalIdentityReason.INVALID_TARGET_NODE_NUM,
        )
    }

    @Test
    fun noOldRemnantsMeansNoMigrationCandidate() {
        assertDecision(
            transition(hasOldIdentityRemnants = false),
            CanonicalIdentityAction.PRESERVE,
            CanonicalIdentityStatus.CONSISTENT,
            CanonicalIdentityReason.NO_OLD_IDENTITY_REMNANTS,
        )
    }

    private fun transition(
        newNodeNum: Int = canonicalNodeNum,
        storedPublicKey: ByteArray = canonicalKey.copyOf(),
        incomingPublicKey: ByteArray = canonicalKey.copyOf(),
        authority: IdentityMigrationAuthority = IdentityMigrationAuthority.TRUSTED_CONFIG,
        targetState: CanonicalIdentityTargetState = CanonicalIdentityTargetState.UNOCCUPIED,
        hasOldIdentityRemnants: Boolean = true,
        hasMultipleOldOwners: Boolean = false,
    ) = CanonicalIdentityTransition(
        oldNodeNum = OLD_NODE_NUM,
        newNodeNum = newNodeNum,
        storedPublicKey = storedPublicKey,
        incomingPublicKey = incomingPublicKey,
        authority = authority,
        targetState = targetState,
        hasOldIdentityRemnants = hasOldIdentityRemnants,
        hasMultipleOldOwners = hasMultipleOldOwners,
    )

    private fun differentKey(): ByteArray =
        ByteArray(MeshtasticCanonicalIdentity.PUBLIC_KEY_SIZE_BYTES) { (it + 1).toByte() }

    private fun assertDecision(
        transition: CanonicalIdentityTransition,
        expectedAction: CanonicalIdentityAction,
        expectedStatus: CanonicalIdentityStatus,
        expectedReason: CanonicalIdentityReason,
    ) {
        val decision = CanonicalIdentityPolicy.evaluate(transition)

        assertEquals(expectedAction, decision.action)
        assertEquals(expectedStatus, decision.status)
        assertEquals(expectedReason, decision.reason)
    }

    private companion object {
        const val OLD_NODE_NUM = 0x10203040
    }
}
