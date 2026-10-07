/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.geeksville.mesh.database

import android.app.Application
import android.content.Context
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.android.Logging
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournal
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournalStates
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.prefs.UserPrefs
import com.geeksville.mesh.ui.COMPRESSED_CHATS_PREFS
import com.geeksville.mesh.util.IdentityMigrationAuthority
import com.geeksville.mesh.util.MeshtasticCanonicalIdentity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.meshtastic.proto.MeshProtos

internal data class TrustedConfigIdentity(
    val nodeNum: Int,
    val publicKey: ByteArray,
)

internal data class TrustedCanonicalIdentityCandidate(
    val request: CanonicalIdentityMigrationRequest,
    val oldOwnerCount: Int,
)

internal object TrustedCanonicalIdentityCandidateDetector {
    fun detect(
        identities: List<TrustedConfigIdentity>,
        registry: List<NodeRegistry>,
        localNodeNum: Int,
    ): List<TrustedCanonicalIdentityCandidate> = identities
        .asSequence()
        .filter { it.nodeNum != localNodeNum }
        .filter { MeshtasticCanonicalIdentity.isUsablePublicKey(it.publicKey) }
        .filter { MeshtasticCanonicalIdentity.isUsableOperationalNodeNum(it.nodeNum) }
        .filter { MeshtasticCanonicalIdentity.canonicalNodeNum(it.publicKey) == it.nodeNum }
        .distinctBy { it.nodeNum to it.publicKey.toHex() }
        .sortedWith(compareBy<TrustedConfigIdentity> { it.nodeNum }.thenBy { it.publicKey.toHex() })
        .mapNotNull { incoming ->
            val oldOwners = registry
                .filter { it.publicKey?.contentEquals(incoming.publicKey) == true }
                .mapNotNull { row -> row.operationalNodeNum() }
                .filter { it != incoming.nodeNum }
                .distinct()
                .sorted()
            if (localNodeNum in oldOwners) return@mapNotNull null
            val oldNodeNum = oldOwners.firstOrNull() ?: return@mapNotNull null
            TrustedCanonicalIdentityCandidate(
                request = CanonicalIdentityMigrationRequest(
                    oldNodeNum = oldNodeNum,
                    newNodeNum = incoming.nodeNum,
                    storedPublicKey = incoming.publicKey.copyOf(),
                    incomingPublicKey = incoming.publicKey.copyOf(),
                    authority = IdentityMigrationAuthority.TRUSTED_CONFIG,
                ),
                oldOwnerCount = oldOwners.size,
            )
        }
        .toList()
}

internal class CanonicalIdentityOutboundBlockedException(nodeNum: Int) :
    IllegalStateException("Canonical identity outbound blocked for $nodeNum")

internal class CanonicalIdentityRecoveryNotReadyException :
    IllegalStateException("Canonical identity recovery has not completed")

internal class CanonicalIdentityOutboundGate {
    private sealed interface Rule {
        data class Redirect(val nodeNum: Int) : Rule
        data object Block : Rule
    }

    private val rules = ConcurrentHashMap<Int, Rule>()

    @Volatile
    private var initialized = false

    fun markInitialized() {
        initialized = true
    }

    fun beginTransition(oldNodeNum: Int): () -> Unit {
        val previous = rules.put(oldNodeNum, Rule.Block)
        return {
            if (previous == null) {
                rules.remove(oldNodeNum, Rule.Block)
            } else {
                rules.replace(oldNodeNum, Rule.Block, previous)
            }
        }
    }

    fun redirect(oldNodeNum: Int, newNodeNum: Int) {
        rules[oldNodeNum] = Rule.Redirect(newNodeNum)
    }

    fun block(oldNodeNum: Int) {
        rules[oldNodeNum] = Rule.Block
    }

    fun resolve(nodeNum: Int): Int {
        if (!initialized) throw CanonicalIdentityRecoveryNotReadyException()

        var current = nodeNum
        val visited = mutableSetOf<Int>()
        while (true) {
            if (!visited.add(current)) throw CanonicalIdentityOutboundBlockedException(nodeNum)
            when (val rule = rules[current]) {
                Rule.Block -> throw CanonicalIdentityOutboundBlockedException(nodeNum)
                is Rule.Redirect -> current = rule.nodeNum
                null -> return current
            }
        }
    }
}

internal class CanonicalIdentityExternalStateMigrator(
    private val application: Application,
) {
    fun migrate(oldNodeNum: Int, newNodeNum: Int): Boolean {
        val oldNodeId = DataPacket.nodeNumToDefaultId(oldNodeNum)
        val newNodeId = DataPacket.nodeNumToDefaultId(newNodeNum)
        return migrateCompressionPreferences(oldNodeId, newNodeId) &&
            migratePlannedMessagePreferences(oldNodeNum, newNodeNum)
    }

    private fun migrateCompressionPreferences(oldNodeId: String, newNodeId: String): Boolean {
        val prefs = application.getSharedPreferences(COMPRESSED_CHATS_PREFS, Context.MODE_PRIVATE)
        val sourceEntries = prefs.all
            .filterKeys { it.hasExactIdentitySuffix(oldNodeId) }
            .toSortedMap()
        if (sourceEntries.isEmpty()) return true
        if (sourceEntries.values.any { it !is Boolean }) return false

        val migrations = sourceEntries.map { (oldKey, sourceValue) ->
            val newKey = oldKey.replaceExactIdentitySuffix(oldNodeId, newNodeId)
            val targetValue = prefs.all[newKey]
            if (targetValue != null && targetValue !is Boolean) return false
            Triple(oldKey, newKey, sourceValue as Boolean || targetValue == true)
        }
        return prefs.edit().also { editor ->
            migrations.forEach { (oldKey, newKey, mergedValue) ->
                editor.putBoolean(newKey, mergedValue)
                if (oldKey != newKey) editor.remove(oldKey)
            }
        }.commit()
    }

    private fun migratePlannedMessagePreferences(oldNodeNum: Int, newNodeNum: Int): Boolean {
        val prefs = application.getSharedPreferences(
            UserPrefs.PlannedMessage.SHARED_PLANNED_MSG_PREFS,
            Context.MODE_PRIVATE,
        )
        val oldKey = oldNodeNum.toString()
        val newKey = newNodeNum.toString()
        val source = prefs.all[oldKey] ?: return true
        val target = prefs.all[newKey]
        if (source !is String || target != null && target !is String) return false

        val merged = mergePlannedMessages(target as String?, source)
        return prefs.edit()
            .putString(newKey, merged)
            .remove(oldKey)
            .commit()
    }
}

internal enum class CanonicalIdentityRuntimeOutcome {
    COMPLETE,
    EXTERNAL_PENDING,
    BLOCKED,
    ROOM_FAILED,
}

internal data class CanonicalIdentityRuntimeResult(
    val oldNodeNum: Int,
    val newNodeNum: Int,
    val outcome: CanonicalIdentityRuntimeOutcome,
    val detail: String,
)

@Singleton
class CanonicalIdentityRuntimeMigration @Inject constructor(
    private val migrationRepository: CanonicalIdentityMigrationRepository,
    application: Application,
) : Logging {
    private val externalStateMigrator = CanonicalIdentityExternalStateMigrator(application)
    private val outboundGate = CanonicalIdentityOutboundGate()
    private val orchestrationMutex = Mutex()

    internal suspend fun recoverPersistedMigrations(): List<CanonicalIdentityRuntimeResult> =
        orchestrationMutex.withLock {
            val journals = migrationRepository.getCanonicalIdentityMigrationJournals()
            val ambiguousOldNodeNums = journals
                .groupingBy(CanonicalIdentityMigrationJournal::oldNodeNum)
                .eachCount()
                .filterValues { it > 1 }
                .keys
            val results = journals.map { journal ->
                if (journal.oldNodeNum in ambiguousOldNodeNums) {
                    outboundGate.block(journal.oldNodeNum)
                    warn("Multiple canonical identity journals for old NodeNum ${journal.oldNodeNum}")
                    CanonicalIdentityRuntimeResult(
                        journal.oldNodeNum,
                        journal.newNodeNum,
                        CanonicalIdentityRuntimeOutcome.BLOCKED,
                        "multiple journals for old identity",
                    )
                } else {
                    recoverJournal(journal)
                }
            }
            outboundGate.markInitialized()
            results
        }

    internal suspend fun installTrustedConfigIdentities(
        nodeInfos: List<MeshProtos.NodeInfo>,
        localNodeNum: Int,
    ): List<CanonicalIdentityRuntimeResult> = orchestrationMutex.withLock {
        val identities = nodeInfos.mapNotNull { nodeInfo ->
            if (!nodeInfo.hasUser() || nodeInfo.user.isLicensed) return@mapNotNull null
            TrustedConfigIdentity(nodeInfo.num, nodeInfo.user.publicKey.toByteArray())
        }
        val candidates = TrustedCanonicalIdentityCandidateDetector.detect(
            identities = identities,
            registry = migrationRepository.getNodeRegistrySnapshot(),
            localNodeNum = localNodeNum,
        )
        candidates.map { candidate -> executeCandidate(candidate) }
    }

    fun canonicalizeOutboundNodeNum(nodeNum: Int): Int = outboundGate.resolve(nodeNum)

    private suspend fun executeCandidate(
        candidate: TrustedCanonicalIdentityCandidate,
    ): CanonicalIdentityRuntimeResult {
        val request = candidate.request
        info(
            "Trusted canonical identity candidate ${request.oldNodeNum} -> ${request.newNodeNum} " +
                "owners=${candidate.oldOwnerCount}",
        )
        val restorePreviousRule = outboundGate.beginTransition(request.oldNodeNum)
        val result = try {
            migrationRepository.executeCanonicalIdentityMigration(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            restorePreviousRule()
            errormsg("Canonical identity Room migration failed", failure)
            return CanonicalIdentityRuntimeResult(
                request.oldNodeNum,
                request.newNodeNum,
                CanonicalIdentityRuntimeOutcome.ROOM_FAILED,
                failure.javaClass.simpleName,
            )
        }

        return when (result.outcome) {
            CanonicalIdentityMigrationOutcome.ROOM_COMMITTED_EXTERNAL_PENDING,
            CanonicalIdentityMigrationOutcome.ALREADY_PENDING,
            CanonicalIdentityMigrationOutcome.ALREADY_COMPLETE,
            -> {
                outboundGate.redirect(request.oldNodeNum, request.newNodeNum)
                val journal = migrationRepository.getCanonicalIdentityMigrationJournal(
                    request.oldNodeNum,
                    request.newNodeNum,
                )
                if (journal == null) {
                    outboundGate.block(request.oldNodeNum)
                    CanonicalIdentityRuntimeResult(
                        request.oldNodeNum,
                        request.newNodeNum,
                        CanonicalIdentityRuntimeOutcome.BLOCKED,
                        "accepted migration has no journal",
                    )
                } else {
                    info("Canonical identity Room phase accepted ${request.oldNodeNum} -> ${request.newNodeNum}")
                    recoverJournal(journal)
                }
            }

            CanonicalIdentityMigrationOutcome.PRESERVED,
            CanonicalIdentityMigrationOutcome.REJECTED,
            CanonicalIdentityMigrationOutcome.BLOCKED,
            -> {
                restorePreviousRule()
                warn(
                    "Canonical identity candidate blocked ${request.oldNodeNum} -> ${request.newNodeNum}: " +
                        "${result.decision.reason}",
                )
                CanonicalIdentityRuntimeResult(
                    request.oldNodeNum,
                    request.newNodeNum,
                    CanonicalIdentityRuntimeOutcome.BLOCKED,
                    result.preflightFailure?.name ?: result.decision.reason.name,
                )
            }
        }
    }

    private suspend fun recoverJournal(
        journal: CanonicalIdentityMigrationJournal,
    ): CanonicalIdentityRuntimeResult {
        val validation = migrationRepository.validateCanonicalIdentityJournal(journal)
        if (!validation.isConsistent) {
            outboundGate.block(journal.oldNodeNum)
            warn(
                "Inconsistent canonical identity journal ${journal.oldNodeNum} -> ${journal.newNodeNum}: " +
                    validation.failure,
            )
            return CanonicalIdentityRuntimeResult(
                journal.oldNodeNum,
                journal.newNodeNum,
                CanonicalIdentityRuntimeOutcome.BLOCKED,
                validation.failure?.name ?: "inconsistent journal",
            )
        }

        outboundGate.redirect(journal.oldNodeNum, journal.newNodeNum)
        if (journal.state == CanonicalIdentityMigrationJournalStates.COMPLETE) {
            return CanonicalIdentityRuntimeResult(
                journal.oldNodeNum,
                journal.newNodeNum,
                CanonicalIdentityRuntimeOutcome.COMPLETE,
                "already complete",
            )
        }

        info("Recovering pending canonical identity ${journal.oldNodeNum} -> ${journal.newNodeNum}")
        if (!externalStateMigrator.migrate(journal.oldNodeNum, journal.newNodeNum)) {
            warn("Canonical identity external recovery remains pending")
            return CanonicalIdentityRuntimeResult(
                journal.oldNodeNum,
                journal.newNodeNum,
                CanonicalIdentityRuntimeOutcome.EXTERNAL_PENDING,
                "external state migration failed",
            )
        }

        return when (migrationRepository.completeCanonicalIdentityJournal(journal)) {
            CanonicalIdentityJournalCompletion.COMPLETED,
            CanonicalIdentityJournalCompletion.ALREADY_COMPLETE,
            -> {
                info("Canonical identity journal complete ${journal.oldNodeNum} -> ${journal.newNodeNum}")
                CanonicalIdentityRuntimeResult(
                    journal.oldNodeNum,
                    journal.newNodeNum,
                    CanonicalIdentityRuntimeOutcome.COMPLETE,
                    "external state complete",
                )
            }

            CanonicalIdentityJournalCompletion.INCONSISTENT -> {
                outboundGate.block(journal.oldNodeNum)
                warn("Canonical identity journal changed before completion")
                CanonicalIdentityRuntimeResult(
                    journal.oldNodeNum,
                    journal.newNodeNum,
                    CanonicalIdentityRuntimeOutcome.BLOCKED,
                    "journal changed before completion",
                )
            }
        }
    }
}

private fun NodeRegistry.operationalNodeNum(): Int? = nodeNum ?: parseExactDefaultNodeId(nodeId)

private fun parseExactDefaultNodeId(nodeId: String): Int? {
    if (nodeId.length != 9 || nodeId.firstOrNull() != '!') return null
    val value = nodeId.drop(1).toLongOrNull(16)?.toInt() ?: return null
    return value.takeIf { DataPacket.nodeNumToDefaultId(it).equals(nodeId, ignoreCase = true) }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }

private fun String.hasExactIdentitySuffix(nodeId: String): Boolean =
    length >= nodeId.length && regionMatches(length - nodeId.length, nodeId, 0, nodeId.length)

private fun String.replaceExactIdentitySuffix(oldNodeId: String, newNodeId: String): String =
    if (hasExactIdentitySuffix(oldNodeId)) dropLast(oldNodeId.length) + newNodeId else this

private fun mergePlannedMessages(target: String?, source: String): String = buildList {
    target.orEmpty().lineSequence().filter(String::isNotBlank).forEach(::add)
    source.lineSequence().filter(String::isNotBlank).forEach { if (it !in this) add(it) }
}.joinToString("\n")
