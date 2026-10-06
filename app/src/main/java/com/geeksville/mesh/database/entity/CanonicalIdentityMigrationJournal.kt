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

package com.geeksville.mesh.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

object CanonicalIdentityMigrationJournalStates {
    const val ROOM_COMMITTED_EXTERNAL_PENDING = "ROOM_COMMITTED_EXTERNAL_PENDING"
    const val COMPLETE = "COMPLETE"
}

@Entity(
    tableName = "canonical_identity_migration",
    primaryKeys = ["old_node_num", "new_node_num"],
    indices = [Index(value = ["state"])],
)
data class CanonicalIdentityMigrationJournal(
    @ColumnInfo(name = "old_node_num") val oldNodeNum: Int,
    @ColumnInfo(name = "new_node_num") val newNodeNum: Int,
    @ColumnInfo(name = "public_key", typeAffinity = ColumnInfo.BLOB) val publicKey: ByteArray,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
