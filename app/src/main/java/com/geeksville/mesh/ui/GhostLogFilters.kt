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

package com.geeksville.mesh.ui

import com.geeksville.mesh.database.entity.MeshLog
import java.text.DateFormat

private const val HEX_RADIX = 16
private const val DECIMAL_RADIX = 10

internal fun parseGhostNodeId(input: String): UInt? {
    val value = input.trim()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("!") -> value.substring(1).toUIntOrNull(HEX_RADIX)
        value.startsWith("0x", ignoreCase = true) -> value.substring(2).toUIntOrNull(HEX_RADIX)
        value.any { it in 'a'..'f' || it in 'A'..'F' } -> value.toUIntOrNull(HEX_RADIX)
        else -> value.toUIntOrNull(DECIMAL_RADIX)
    }
}

internal fun parseGhostChannel(input: String): Int? {
    val value = input.trim()
    return if (value.startsWith("0x", ignoreCase = true)) {
        value.substring(2).toIntOrNull(HEX_RADIX)
    } else {
        value.toIntOrNull()
    }
}

internal fun filterDiagnosticLogs(logs: List<MeshLog>, filter: String): List<MeshLog> {
    val query = filter.trim()
    if (query.isEmpty()) return logs
    val nodeQuery = query.removePrefix("!")
    return logs.filter { log ->
        log.message_type.contains(query, ignoreCase = true) ||
            log.raw_message.contains(query, ignoreCase = true) ||
            log.fromNum.toUInt().toString(HEX_RADIX).contains(nodeQuery, ignoreCase = true)
    }
}

internal fun formatDiagnosticLog(logs: List<MeshLog>): String = buildString {
    appendLine("DarkMesh diagnostic log — filtered snapshot")
    appendLine("Entries: ${logs.size} (recent log window)")
    val dateFormat = DateFormat.getDateTimeInstance()
    logs.forEach { log ->
        appendLine("--- ${dateFormat.format(log.received_date)} | ${log.message_type} ---")
        appendLine(log.raw_message)
    }
}
