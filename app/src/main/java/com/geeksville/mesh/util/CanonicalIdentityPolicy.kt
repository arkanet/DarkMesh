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

import java.util.zip.CRC32

internal enum class IdentityMigrationAuthority {
    TRUSTED_CONFIG,
    LIVE_MESH,
}

internal enum class CanonicalIdentityTargetState {
    UNOCCUPIED,
    SAME_PUBLIC_KEY,
    DIFFERENT_USABLE_PUBLIC_KEY,
    PARTIAL_MERGEABLE,
    PARTIAL_AMBIGUOUS,
}

internal enum class CanonicalIdentityAction {
    MIGRATE,
    CONVERGE,
    PRESERVE,
    REJECT,
    BLOCK,
}

internal enum class CanonicalIdentityStatus {
    CONSISTENT,
    MISMATCH,
    INDEPENDENT,
    UNUSABLE,
    AMBIGUOUS,
}

internal enum class CanonicalIdentityReason {
    CANONICAL_TARGET_AVAILABLE,
    PARTIAL_TARGET_MERGEABLE,
    TARGET_ALREADY_OWNS_KEY,
    ALREADY_CONVERGED,
    SAME_NODE_NUM,
    LIVE_MESH_NOT_AUTHORIZED,
    UNUSABLE_PUBLIC_KEY,
    INDEPENDENT_IDENTITY,
    KEY_CHANGED_AT_SAME_NODE,
    INVALID_TARGET_NODE_NUM,
    NON_CANONICAL_TARGET,
    MULTIPLE_OLD_OWNERS,
    TARGET_KEY_CONFLICT,
    TARGET_PARTIAL_AMBIGUOUS,
    NO_OLD_IDENTITY_REMNANTS,
}

internal data class CanonicalIdentityTransition(
    val oldNodeNum: Int,
    val newNodeNum: Int,
    val storedPublicKey: ByteArray,
    val incomingPublicKey: ByteArray,
    val authority: IdentityMigrationAuthority,
    val targetState: CanonicalIdentityTargetState,
    val hasOldIdentityRemnants: Boolean = true,
    val hasMultipleOldOwners: Boolean = false,
)

internal data class CanonicalIdentityDecision(
    val action: CanonicalIdentityAction,
    val status: CanonicalIdentityStatus,
    val reason: CanonicalIdentityReason,
)

internal object MeshtasticCanonicalIdentity {
    const val PUBLIC_KEY_SIZE_BYTES = 32

    private const val FIRST_UNRESERVED_NODE_NUM = 4
    private const val BROADCAST_NODE_NUM = -1

    fun isUsablePublicKey(publicKey: ByteArray): Boolean =
        publicKey.size == PUBLIC_KEY_SIZE_BYTES && publicKey.any { it != 0.toByte() }

    fun canonicalNodeNum(publicKey: ByteArray): Int {
        require(isUsablePublicKey(publicKey)) { "Canonical identity requires a usable 32-byte public key" }
        return crc32(publicKey)
    }

    /** Returns the CRC-32 bit pattern as an Int; the value may be negative. */
    fun crc32(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()

    /** Meshtastic reserves NodeNums 0 through 3 and the 0xffffffff broadcast value. */
    fun isUsableOperationalNodeNum(nodeNum: Int): Boolean =
        nodeNum !in 0 until FIRST_UNRESERVED_NODE_NUM && nodeNum != BROADCAST_NODE_NUM
}

internal object CanonicalIdentityPolicy {
    fun evaluate(transition: CanonicalIdentityTransition): CanonicalIdentityDecision =
        evaluateKeyAndNodeNums(transition)
            ?: evaluateSafetyAndAuthority(transition)
            ?: evaluateTrustedTransition(transition)

    private fun evaluateKeyAndNodeNums(transition: CanonicalIdentityTransition): CanonicalIdentityDecision? = when {
        !MeshtasticCanonicalIdentity.isUsablePublicKey(transition.storedPublicKey) ||
            !MeshtasticCanonicalIdentity.isUsablePublicKey(transition.incomingPublicKey) -> decision(
            action = CanonicalIdentityAction.REJECT,
            status = CanonicalIdentityStatus.UNUSABLE,
            reason = CanonicalIdentityReason.UNUSABLE_PUBLIC_KEY,
        )

        !transition.storedPublicKey.contentEquals(transition.incomingPublicKey) &&
            transition.oldNodeNum == transition.newNodeNum -> decision(
            action = CanonicalIdentityAction.PRESERVE,
            status = CanonicalIdentityStatus.MISMATCH,
            reason = CanonicalIdentityReason.KEY_CHANGED_AT_SAME_NODE,
        )

        !transition.storedPublicKey.contentEquals(transition.incomingPublicKey) -> decision(
            action = CanonicalIdentityAction.PRESERVE,
            status = CanonicalIdentityStatus.INDEPENDENT,
            reason = CanonicalIdentityReason.INDEPENDENT_IDENTITY,
        )

        transition.oldNodeNum == transition.newNodeNum -> decision(
            action = CanonicalIdentityAction.PRESERVE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.SAME_NODE_NUM,
        )

        !MeshtasticCanonicalIdentity.isUsableOperationalNodeNum(transition.newNodeNum) -> decision(
            action = CanonicalIdentityAction.REJECT,
            status = CanonicalIdentityStatus.MISMATCH,
            reason = CanonicalIdentityReason.INVALID_TARGET_NODE_NUM,
        )

        MeshtasticCanonicalIdentity.canonicalNodeNum(transition.incomingPublicKey) != transition.newNodeNum -> decision(
            action = CanonicalIdentityAction.REJECT,
            status = CanonicalIdentityStatus.MISMATCH,
            reason = CanonicalIdentityReason.NON_CANONICAL_TARGET,
        )

        else -> null
    }

    private fun evaluateSafetyAndAuthority(
        transition: CanonicalIdentityTransition,
    ): CanonicalIdentityDecision? = when {
        transition.hasMultipleOldOwners -> decision(
            action = CanonicalIdentityAction.BLOCK,
            status = CanonicalIdentityStatus.AMBIGUOUS,
            reason = CanonicalIdentityReason.MULTIPLE_OLD_OWNERS,
        )

        transition.targetState == CanonicalIdentityTargetState.DIFFERENT_USABLE_PUBLIC_KEY -> decision(
            action = CanonicalIdentityAction.BLOCK,
            status = CanonicalIdentityStatus.MISMATCH,
            reason = CanonicalIdentityReason.TARGET_KEY_CONFLICT,
        )

        transition.targetState == CanonicalIdentityTargetState.PARTIAL_AMBIGUOUS -> decision(
            action = CanonicalIdentityAction.BLOCK,
            status = CanonicalIdentityStatus.AMBIGUOUS,
            reason = CanonicalIdentityReason.TARGET_PARTIAL_AMBIGUOUS,
        )

        transition.authority != IdentityMigrationAuthority.TRUSTED_CONFIG -> decision(
            action = CanonicalIdentityAction.PRESERVE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.LIVE_MESH_NOT_AUTHORIZED,
        )

        else -> null
    }

    private fun evaluateTrustedTransition(transition: CanonicalIdentityTransition): CanonicalIdentityDecision = when {
        !transition.hasOldIdentityRemnants &&
            transition.targetState == CanonicalIdentityTargetState.SAME_PUBLIC_KEY -> decision(
                action = CanonicalIdentityAction.PRESERVE,
                status = CanonicalIdentityStatus.CONSISTENT,
                reason = CanonicalIdentityReason.ALREADY_CONVERGED,
            )

        !transition.hasOldIdentityRemnants -> decision(
            action = CanonicalIdentityAction.PRESERVE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.NO_OLD_IDENTITY_REMNANTS,
        )

        transition.targetState == CanonicalIdentityTargetState.UNOCCUPIED -> decision(
            action = CanonicalIdentityAction.MIGRATE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.CANONICAL_TARGET_AVAILABLE,
        )

        transition.targetState == CanonicalIdentityTargetState.SAME_PUBLIC_KEY -> decision(
            action = CanonicalIdentityAction.CONVERGE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.TARGET_ALREADY_OWNS_KEY,
        )

        transition.targetState == CanonicalIdentityTargetState.PARTIAL_MERGEABLE -> decision(
            action = CanonicalIdentityAction.MIGRATE,
            status = CanonicalIdentityStatus.CONSISTENT,
            reason = CanonicalIdentityReason.PARTIAL_TARGET_MERGEABLE,
        )

        else -> error("Conflicting target state must be handled before migration eligibility")
    }

    private fun decision(
        action: CanonicalIdentityAction,
        status: CanonicalIdentityStatus,
        reason: CanonicalIdentityReason,
    ) = CanonicalIdentityDecision(action = action, status = status, reason = reason)
}
