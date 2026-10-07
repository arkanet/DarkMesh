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

package com.geeksville.mesh.database

import androidx.room.withTransaction
import com.geeksville.mesh.CoroutineDispatchers
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.database.dao.CanonicalIdentityMigrationDao
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournal
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournalStates
import com.geeksville.mesh.database.entity.ContactSettings
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.database.entity.ReactionEntity
import com.geeksville.mesh.util.CanonicalIdentityAction
import com.geeksville.mesh.util.CanonicalIdentityDecision
import com.geeksville.mesh.util.CanonicalIdentityPolicy
import com.geeksville.mesh.util.CanonicalIdentityTargetState
import com.geeksville.mesh.util.CanonicalIdentityTransition
import com.geeksville.mesh.util.IdentityMigrationAuthority
import com.geeksville.mesh.util.MeshtasticCanonicalIdentity
import kotlinx.coroutines.withContext
import javax.inject.Inject

internal data class CanonicalIdentityMigrationRequest(
    val oldNodeNum: Int,
    val newNodeNum: Int,
    val storedPublicKey: ByteArray,
    val incomingPublicKey: ByteArray,
    val authority: IdentityMigrationAuthority,
)

internal enum class CanonicalIdentityMigrationOutcome {
    ROOM_COMMITTED_EXTERNAL_PENDING,
    ALREADY_PENDING,
    ALREADY_COMPLETE,
    PRESERVED,
    REJECTED,
    BLOCKED,
}

internal enum class CanonicalIdentityMigrationPreflightFailure {
    OLD_KEY_OWNERSHIP_MISMATCH,
    JOURNAL_IDENTITY_MISMATCH,
    JOURNAL_STATE_MISMATCH,
}

internal data class CanonicalIdentityMigrationResult(
    val outcome: CanonicalIdentityMigrationOutcome,
    val decision: CanonicalIdentityDecision,
    val preflightFailure: CanonicalIdentityMigrationPreflightFailure? = null,
)

internal enum class CanonicalIdentityJournalValidationFailure {
    UNKNOWN_STATE,
    UNUSABLE_PUBLIC_KEY,
    INVALID_NODE_NUMS,
    NON_CANONICAL_TARGET,
    SOURCE_REMAINS,
    TARGET_NOT_EXACT,
    DUPLICATE_KEY_OWNER,
}

internal data class CanonicalIdentityJournalValidation(
    val journal: CanonicalIdentityMigrationJournal,
    val isConsistent: Boolean,
    val failure: CanonicalIdentityJournalValidationFailure? = null,
)

internal enum class CanonicalIdentityJournalCompletion {
    COMPLETED,
    ALREADY_COMPLETE,
    INCONSISTENT,
}

/** Room phase and journal validation for a trusted canonical identity migration. */
class CanonicalIdentityMigrationRepository @Inject constructor(
    private val database: MeshtasticDatabase,
    private val dispatchers: CoroutineDispatchers,
) {
    internal suspend fun getNodeRegistrySnapshot(): List<NodeRegistry> = withContext(dispatchers.io) {
        database.canonicalIdentityMigrationDao().getNodeRegistrySnapshot()
    }

    internal suspend fun getCanonicalIdentityMigrationJournals(): List<CanonicalIdentityMigrationJournal> =
        withContext(dispatchers.io) {
            database.canonicalIdentityMigrationDao().getJournals()
        }

    internal suspend fun getCanonicalIdentityMigrationJournal(
        oldNodeNum: Int,
        newNodeNum: Int,
    ): CanonicalIdentityMigrationJournal? = withContext(dispatchers.io) {
        database.canonicalIdentityMigrationDao().getJournal(oldNodeNum, newNodeNum)
    }

    internal suspend fun validateCanonicalIdentityJournal(
        journal: CanonicalIdentityMigrationJournal,
    ): CanonicalIdentityJournalValidation = withContext(dispatchers.io) {
        database.withTransaction {
            validateJournal(database.canonicalIdentityMigrationDao(), journal)
        }
    }

    internal suspend fun completeCanonicalIdentityJournal(
        journal: CanonicalIdentityMigrationJournal,
    ): CanonicalIdentityJournalCompletion = withContext(dispatchers.io) {
        database.withTransaction {
            val dao = database.canonicalIdentityMigrationDao()
            val current = dao.getJournal(journal.oldNodeNum, journal.newNodeNum)
                ?: return@withTransaction CanonicalIdentityJournalCompletion.INCONSISTENT
            val validation = validateJournal(dao, current)
            if (!validation.isConsistent) {
                return@withTransaction CanonicalIdentityJournalCompletion.INCONSISTENT
            }
            if (current.state == CanonicalIdentityMigrationJournalStates.COMPLETE) {
                return@withTransaction CanonicalIdentityJournalCompletion.ALREADY_COMPLETE
            }
            val updated = dao.completePendingJournal(
                oldNodeNum = current.oldNodeNum,
                newNodeNum = current.newNodeNum,
                pendingState = CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
                completeState = CanonicalIdentityMigrationJournalStates.COMPLETE,
                updatedAt = System.currentTimeMillis(),
            )
            if (updated == 1) {
                CanonicalIdentityJournalCompletion.COMPLETED
            } else {
                CanonicalIdentityJournalCompletion.INCONSISTENT
            }
        }
    }

    internal suspend fun executeCanonicalIdentityMigration(
        request: CanonicalIdentityMigrationRequest,
    ): CanonicalIdentityMigrationResult = withContext(dispatchers.io) {
        database.withTransaction {
            executeInTransaction(database.canonicalIdentityMigrationDao(), request)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun executeInTransaction(
        dao: CanonicalIdentityMigrationDao,
        request: CanonicalIdentityMigrationRequest,
    ): CanonicalIdentityMigrationResult {
        val registry = dao.getNodeRegistrySnapshot()
        val preflight = registry.preflight(request)
        val decision = CanonicalIdentityPolicy.evaluate(preflight.transition)
        val journal = dao.getJournal(request.oldNodeNum, request.newNodeNum)

        if (journal != null) {
            return evaluateCommittedJournal(request, preflight, decision, journal)
        }

        if (decision.action !in setOf(CanonicalIdentityAction.MIGRATE, CanonicalIdentityAction.CONVERGE)) {
            return CanonicalIdentityMigrationResult(decision.action.toOutcome(), decision)
        }

        val source = preflight.sourceRows.singleOrNull()
        if (source?.publicKey?.contentEquals(request.storedPublicKey) != true) {
            return CanonicalIdentityMigrationResult(
                outcome = CanonicalIdentityMigrationOutcome.BLOCKED,
                decision = decision,
                preflightFailure = CanonicalIdentityMigrationPreflightFailure.OLD_KEY_OWNERSHIP_MISMATCH,
            )
        }

        val oldNodeId = DataPacket.nodeNumToDefaultId(request.oldNodeNum)
        val newNodeId = DataPacket.nodeNumToDefaultId(request.newNodeNum)
        val targetRegistry = mergeRegistryRows(
            rows = preflight.sourceRows + preflight.targetRows,
            newNodeId = newNodeId,
            newNodeNum = request.newNodeNum,
            publicKey = request.incomingPublicKey,
        )

        migratePackets(dao, oldNodeId, newNodeId)
        migrateContactSettings(dao, oldNodeId, newNodeId)
        migrateReactions(dao, oldNodeId, newNodeId)

        dao.upsertNodeRegistry(targetRegistry)
        migrateMetadata(dao, request.oldNodeNum, request.newNodeNum)

        (preflight.sourceRows + preflight.targetRows)
            .asSequence()
            .map(NodeRegistry::nodeId)
            .filter { it != newNodeId }
            .distinct()
            .forEach { dao.deleteNodeRegistry(it) }

        val now = System.currentTimeMillis()
        dao.upsertJournal(
            CanonicalIdentityMigrationJournal(
                oldNodeNum = request.oldNodeNum,
                newNodeNum = request.newNodeNum,
                publicKey = request.incomingPublicKey.copyOf(),
                state = CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING,
                createdAt = now,
                updatedAt = now,
            ),
        )

        return CanonicalIdentityMigrationResult(
            outcome = CanonicalIdentityMigrationOutcome.ROOM_COMMITTED_EXTERNAL_PENDING,
            decision = decision,
        )
    }

    private suspend fun validateJournal(
        dao: CanonicalIdentityMigrationDao,
        journal: CanonicalIdentityMigrationJournal,
    ): CanonicalIdentityJournalValidation {
        val failure = when {
            journal.state != CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING &&
                journal.state != CanonicalIdentityMigrationJournalStates.COMPLETE ->
                CanonicalIdentityJournalValidationFailure.UNKNOWN_STATE

            !MeshtasticCanonicalIdentity.isUsablePublicKey(journal.publicKey) ->
                CanonicalIdentityJournalValidationFailure.UNUSABLE_PUBLIC_KEY

            journal.oldNodeNum == journal.newNodeNum ||
                !MeshtasticCanonicalIdentity.isUsableOperationalNodeNum(journal.newNodeNum) ->
                CanonicalIdentityJournalValidationFailure.INVALID_NODE_NUMS

            MeshtasticCanonicalIdentity.canonicalNodeNum(journal.publicKey) != journal.newNodeNum ->
                CanonicalIdentityJournalValidationFailure.NON_CANONICAL_TARGET

            else -> validateCommittedRegistryState(dao.getNodeRegistrySnapshot(), journal)
        }
        return CanonicalIdentityJournalValidation(
            journal = journal,
            isConsistent = failure == null,
            failure = failure,
        )
    }

    @Suppress("ReturnCount")
    private fun evaluateCommittedJournal(
        request: CanonicalIdentityMigrationRequest,
        preflight: RegistryPreflight,
        decision: CanonicalIdentityDecision,
        journal: CanonicalIdentityMigrationJournal,
    ): CanonicalIdentityMigrationResult {
        if (request.authority != IdentityMigrationAuthority.TRUSTED_CONFIG) {
            return CanonicalIdentityMigrationResult(decision.action.toOutcome(), decision)
        }

        if (!journal.publicKey.contentEquals(request.storedPublicKey) ||
            !journal.publicKey.contentEquals(request.incomingPublicKey)
        ) {
            return CanonicalIdentityMigrationResult(
                outcome = CanonicalIdentityMigrationOutcome.BLOCKED,
                decision = decision,
                preflightFailure = CanonicalIdentityMigrationPreflightFailure.JOURNAL_IDENTITY_MISMATCH,
            )
        }

        val canonicalTarget = preflight.targetRows.singleOrNull()
        val committedStateIsIntact = preflight.sourceRows.isEmpty() &&
            canonicalTarget?.nodeId == DataPacket.nodeNumToDefaultId(request.newNodeNum) &&
            canonicalTarget.nodeNum == request.newNodeNum &&
            canonicalTarget.publicKey?.contentEquals(journal.publicKey) == true
        val knownJournalState =
            journal.state == CanonicalIdentityMigrationJournalStates.ROOM_COMMITTED_EXTERNAL_PENDING ||
            journal.state == CanonicalIdentityMigrationJournalStates.COMPLETE

        if (!committedStateIsIntact || !knownJournalState) {
            return CanonicalIdentityMigrationResult(
                outcome = CanonicalIdentityMigrationOutcome.BLOCKED,
                decision = decision,
                preflightFailure = CanonicalIdentityMigrationPreflightFailure.JOURNAL_STATE_MISMATCH,
            )
        }

        val outcome = if (journal.state == CanonicalIdentityMigrationJournalStates.COMPLETE) {
            CanonicalIdentityMigrationOutcome.ALREADY_COMPLETE
        } else {
            CanonicalIdentityMigrationOutcome.ALREADY_PENDING
        }
        return CanonicalIdentityMigrationResult(outcome, decision)
    }

    private suspend fun migratePackets(
        dao: CanonicalIdentityMigrationDao,
        oldNodeId: String,
        newNodeId: String,
    ) {
        dao.getPacketSnapshot().forEach { packet ->
            val migratedContactKey = packet.contact_key.replaceExactIdentitySuffix(oldNodeId, newNodeId)
            val migratedData = if (packet.data.status == MessageStatus.QUEUED && packet.data.to == oldNodeId) {
                packet.data.copy(to = newNodeId).also { it.errorMessage = packet.data.errorMessage }
            } else {
                packet.data
            }
            if (migratedContactKey != packet.contact_key || migratedData !== packet.data) {
                dao.updatePacket(packet.copy(contact_key = migratedContactKey, data = migratedData))
            }
        }
    }

    private suspend fun migrateContactSettings(
        dao: CanonicalIdentityMigrationDao,
        oldNodeId: String,
        newNodeId: String,
    ) {
        val settings = dao.getContactSettingsSnapshot()
        val byContact = settings.associateBy(ContactSettings::contact_key)
        settings.filter { it.contact_key.hasExactIdentitySuffix(oldNodeId) }.forEach { old ->
            val newContactKey = old.contact_key.replaceExactIdentitySuffix(oldNodeId, newNodeId)
            val mergedMuteUntil = maxOf(old.muteUntil, byContact[newContactKey]?.muteUntil ?: Long.MIN_VALUE)
            dao.upsertContactSettings(ContactSettings(newContactKey, mergedMuteUntil))
            dao.deleteContactSettings(old.contact_key)
        }
    }

    private suspend fun migrateReactions(
        dao: CanonicalIdentityMigrationDao,
        oldNodeId: String,
        newNodeId: String,
    ) {
        val reactions = dao.getReactions(oldNodeId, newNodeId)
        val targetByKey = reactions
            .filter { it.userId == newNodeId }
            .associateBy { it.replyId to it.emoji }
        reactions.filter { it.userId == oldNodeId }.forEach { old ->
            val target = targetByKey[old.replyId to old.emoji]
            dao.upsertReaction(
                ReactionEntity(
                    replyId = old.replyId,
                    userId = newNodeId,
                    emoji = old.emoji,
                    timestamp = maxOf(old.timestamp, target?.timestamp ?: Long.MIN_VALUE),
                ),
            )
        }
        dao.deleteReactions(oldNodeId)
    }

    private suspend fun migrateMetadata(
        dao: CanonicalIdentityMigrationDao,
        oldNodeNum: Int,
        newNodeNum: Int,
    ) {
        val metadata = dao.getMetadata(oldNodeNum, newNodeNum)
        val source = metadata.firstOrNull { it.num == oldNodeNum }
        val target = metadata.firstOrNull { it.num == newNodeNum }
        val newest = listOfNotNull(source, target).maxWithOrNull(
            compareBy<MetadataEntity> { it.timestamp }.thenBy { if (it.num == newNodeNum) 1 else 0 },
        )
        if (newest != null) {
            dao.upsertMetadata(newest.copy(num = newNodeNum))
            dao.deleteMetadata(oldNodeNum)
        }
    }
}

private fun validateCommittedRegistryState(
    registry: List<NodeRegistry>,
    journal: CanonicalIdentityMigrationJournal,
): CanonicalIdentityJournalValidationFailure? {
    val oldNodeId = DataPacket.nodeNumToDefaultId(journal.oldNodeNum)
    val newNodeId = DataPacket.nodeNumToDefaultId(journal.newNodeNum)
    val sourceRows = registry.filter { it.nodeNum == journal.oldNodeNum || it.nodeId == oldNodeId }
    if (sourceRows.isNotEmpty()) return CanonicalIdentityJournalValidationFailure.SOURCE_REMAINS

    val targetRows = registry.filter { it.nodeNum == journal.newNodeNum || it.nodeId == newNodeId }
    val target = targetRows.singleOrNull()
        ?: return CanonicalIdentityJournalValidationFailure.TARGET_NOT_EXACT
    if (target.nodeId != newNodeId ||
        target.nodeNum != journal.newNodeNum ||
        target.publicKey?.contentEquals(journal.publicKey) != true
    ) {
        return CanonicalIdentityJournalValidationFailure.TARGET_NOT_EXACT
    }

    val duplicateOwner = registry.any { row ->
        row.nodeId != target.nodeId && row.publicKey?.contentEquals(journal.publicKey) == true
    }
    return if (duplicateOwner) {
        CanonicalIdentityJournalValidationFailure.DUPLICATE_KEY_OWNER
    } else {
        null
    }
}

private data class RegistryPreflight(
    val sourceRows: List<NodeRegistry>,
    val targetRows: List<NodeRegistry>,
    val transition: CanonicalIdentityTransition,
)

private fun List<NodeRegistry>.preflight(request: CanonicalIdentityMigrationRequest): RegistryPreflight {
    val oldNodeId = DataPacket.nodeNumToDefaultId(request.oldNodeNum)
    val newNodeId = DataPacket.nodeNumToDefaultId(request.newNodeNum)
    val sourceRows = filter { it.nodeNum == request.oldNodeNum || it.nodeId == oldNodeId }
    val targetRows = filter { it.nodeNum == request.newNodeNum || it.nodeId == newNodeId }
    val expectedRows = (sourceRows + targetRows).mapTo(mutableSetOf(), NodeRegistry::nodeId)
    val hasUnexpectedKeyOwner = any { row ->
        row.publicKey?.contentEquals(request.storedPublicKey) == true && row.nodeId !in expectedRows
    }
    val hasMultipleOldOwners = sourceRows.size > 1 ||
        sourceRows.any { it in targetRows } ||
        hasUnexpectedKeyOwner

    return RegistryPreflight(
        sourceRows = sourceRows,
        targetRows = targetRows,
        transition = CanonicalIdentityTransition(
            oldNodeNum = request.oldNodeNum,
            newNodeNum = request.newNodeNum,
            storedPublicKey = request.storedPublicKey,
            incomingPublicKey = request.incomingPublicKey,
            authority = request.authority,
            targetState = targetRows.toTargetState(request.incomingPublicKey),
            hasOldIdentityRemnants = sourceRows.isNotEmpty(),
            hasMultipleOldOwners = hasMultipleOldOwners,
        ),
    )
}

private fun List<NodeRegistry>.toTargetState(incomingPublicKey: ByteArray): CanonicalIdentityTargetState = when {
    isEmpty() -> CanonicalIdentityTargetState.UNOCCUPIED
    size > 1 -> CanonicalIdentityTargetState.PARTIAL_AMBIGUOUS
    first().publicKey?.let(MeshtasticCanonicalIdentity::isUsablePublicKey) != true ->
        CanonicalIdentityTargetState.PARTIAL_MERGEABLE
    first().publicKey?.contentEquals(incomingPublicKey) == true -> CanonicalIdentityTargetState.SAME_PUBLIC_KEY
    else -> CanonicalIdentityTargetState.DIFFERENT_USABLE_PUBLIC_KEY
}

private fun mergeRegistryRows(
    rows: List<NodeRegistry>,
    newNodeId: String,
    newNodeNum: Int,
    publicKey: ByteArray,
): NodeRegistry {
    val newestFirst = rows.sortedWith(
        compareByDescending<NodeRegistry> { it.lastSeen }
            .thenByDescending { it.nodeId == newNodeId }
            .thenBy(NodeRegistry::nodeId),
    )

    fun <T> newestValue(selector: (NodeRegistry) -> T?): T? = newestFirst.firstNotNullOfOrNull(selector)

    return NodeRegistry(
        nodeId = newNodeId,
        shortName = newestValue(NodeRegistry::shortName),
        defaultName = newestValue(NodeRegistry::defaultName),
        longName = newestValue(NodeRegistry::longName),
        nodeNum = newNodeNum,
        publicKey = publicKey.copyOf(),
        latitudeI = newestValue(NodeRegistry::latitudeI),
        longitudeI = newestValue(NodeRegistry::longitudeI),
        lastSeen = rows.maxOf(NodeRegistry::lastSeen),
        hopCount = newestValue(NodeRegistry::hopCount),
        lastRssi = newestValue(NodeRegistry::lastRssi),
    )
}

private fun String.hasExactIdentitySuffix(nodeId: String): Boolean =
    length >= nodeId.length && regionMatches(length - nodeId.length, nodeId, 0, nodeId.length)

private fun String.replaceExactIdentitySuffix(oldNodeId: String, newNodeId: String): String =
    if (hasExactIdentitySuffix(oldNodeId)) dropLast(oldNodeId.length) + newNodeId else this

private fun CanonicalIdentityAction.toOutcome(): CanonicalIdentityMigrationOutcome = when (this) {
    CanonicalIdentityAction.PRESERVE -> CanonicalIdentityMigrationOutcome.PRESERVED
    CanonicalIdentityAction.REJECT -> CanonicalIdentityMigrationOutcome.REJECTED
    CanonicalIdentityAction.BLOCK -> CanonicalIdentityMigrationOutcome.BLOCKED
    CanonicalIdentityAction.MIGRATE,
    CanonicalIdentityAction.CONVERGE,
    -> error("Accepted actions must execute inside the Room transaction")
}
