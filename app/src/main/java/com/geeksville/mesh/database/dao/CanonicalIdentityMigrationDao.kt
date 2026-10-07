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

package com.geeksville.mesh.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.geeksville.mesh.database.entity.CanonicalIdentityMigrationJournal
import com.geeksville.mesh.database.entity.ContactSettings
import com.geeksville.mesh.database.entity.MetadataEntity
import com.geeksville.mesh.database.entity.NodeRegistry
import com.geeksville.mesh.database.entity.Packet
import com.geeksville.mesh.database.entity.ReactionEntity

/** Room primitives used only by the canonical identity transaction repository. */
@Dao
@Suppress("TooManyFunctions")
interface CanonicalIdentityMigrationDao {
    @Query("SELECT * FROM node_registry")
    suspend fun getNodeRegistrySnapshot(): List<NodeRegistry>

    @Upsert
    suspend fun upsertNodeRegistry(node: NodeRegistry)

    @Query("DELETE FROM node_registry WHERE nodeId = :nodeId")
    suspend fun deleteNodeRegistry(nodeId: String)

    @Query("SELECT * FROM metadata WHERE num IN (:oldNodeNum, :newNodeNum)")
    suspend fun getMetadata(oldNodeNum: Int, newNodeNum: Int): List<MetadataEntity>

    @Upsert
    suspend fun upsertMetadata(metadata: MetadataEntity)

    @Query("DELETE FROM metadata WHERE num = :nodeNum")
    suspend fun deleteMetadata(nodeNum: Int)

    @Query("SELECT * FROM packet")
    suspend fun getPacketSnapshot(): List<Packet>

    @Update
    suspend fun updatePacket(packet: Packet)

    @Query("SELECT * FROM contact_settings")
    suspend fun getContactSettingsSnapshot(): List<ContactSettings>

    @Upsert
    suspend fun upsertContactSettings(settings: ContactSettings)

    @Query("DELETE FROM contact_settings WHERE contact_key = :contactKey")
    suspend fun deleteContactSettings(contactKey: String)

    @Query("SELECT * FROM reactions WHERE user_id IN (:oldUserId, :newUserId)")
    suspend fun getReactions(oldUserId: String, newUserId: String): List<ReactionEntity>

    @Upsert
    suspend fun upsertReaction(reaction: ReactionEntity)

    @Query("DELETE FROM reactions WHERE user_id = :userId")
    suspend fun deleteReactions(userId: String)

    @Query(
        """
        SELECT * FROM canonical_identity_migration
        WHERE old_node_num = :oldNodeNum AND new_node_num = :newNodeNum
        LIMIT 1
        """,
    )
    suspend fun getJournal(oldNodeNum: Int, newNodeNum: Int): CanonicalIdentityMigrationJournal?

    @Query(
        """
        SELECT * FROM canonical_identity_migration
        ORDER BY old_node_num, new_node_num
        """,
    )
    suspend fun getJournals(): List<CanonicalIdentityMigrationJournal>

    @Upsert
    suspend fun upsertJournal(journal: CanonicalIdentityMigrationJournal)

    @Query(
        """
        UPDATE canonical_identity_migration
        SET state = :state, updated_at = :updatedAt
        WHERE old_node_num = :oldNodeNum AND new_node_num = :newNodeNum
        """,
    )
    suspend fun updateJournalState(
        oldNodeNum: Int,
        newNodeNum: Int,
        state: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE canonical_identity_migration
        SET state = :completeState, updated_at = :updatedAt
        WHERE old_node_num = :oldNodeNum
          AND new_node_num = :newNodeNum
          AND state = :pendingState
        """,
    )
    suspend fun completePendingJournal(
        oldNodeNum: Int,
        newNodeNum: Int,
        pendingState: String,
        completeState: String,
        updatedAt: Long,
    ): Int
}
