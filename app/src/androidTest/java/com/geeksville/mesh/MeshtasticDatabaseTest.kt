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
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.geeksville.mesh.database.MeshtasticDatabase
import org.junit.Rule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MeshtasticDatabaseTest {

    companion object {
        private const val TEST_DB = "migration-test"
        private const val TEST_DB_26_TO_27 = "migration-test-26-27"
        private const val TEST_DB_27_TO_28 = "migration-test-27-28"
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MeshtasticDatabase::class.java,
    )

    @Test
    @Throws(IOException::class)
    fun migrateAll() {
        // Create earliest version of the database.
        helper.createDatabase(TEST_DB, 3).apply {
            close()
        }

        // Open latest version of the database. Room validates the schema
        // once all migrations execute.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MeshtasticDatabase::class.java,
            TEST_DB
        ).build().apply {
            openHelper.writableDatabase.close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate26To27AddsEmptyDeviceIdWithoutLosingLocalIdentity() {
        helper.createDatabase(TEST_DB_26_TO_27, 26).apply {
            execSQL(
                """
                INSERT INTO my_node (
                    myNodeNum, model, firmwareVersion, couldUpdate, shouldUpdate,
                    currentPacketId, messageTimeoutMsec, minAppVersion, maxChannels, hasWifi
                ) VALUES (42, 'heltec-v4', '2.7.26', 0, 0, 1, 300000, 1, 8, 0)
                """.trimIndent(),
            )
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MeshtasticDatabase::class.java,
            TEST_DB_26_TO_27,
        ).build().apply {
            val migrated = nodeInfoDao().getMyNodeInfoSnapshot()
            assertEquals(42, migrated?.myNodeNum)
            assertArrayEquals(byteArrayOf(), migrated?.deviceId)
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate27To28PreservesExistingStateAndCreatesEmptyJournal() {
        helper.createDatabase(TEST_DB_27_TO_28, 27).apply {
            execSQL(
                """
                INSERT INTO my_node (
                    myNodeNum, model, firmwareVersion, couldUpdate, shouldUpdate,
                    currentPacketId, messageTimeoutMsec, minAppVersion, maxChannels, hasWifi, deviceId
                ) VALUES (42, 'heltec-v4', '2.8.1', 0, 0, 1, 300000, 1, 8, 0, x'01020304')
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO nodes (
                    num, user, long_name, short_name, position, latitude, longitude,
                    snr, rssi, last_heard, device_metrics, channel, via_mqtt, hops_away,
                    is_favorite, is_ignored, environment_metrics, power_metrics, paxcounter,
                    role, node_status
                ) VALUES (
                    42, x'', 'Node', 'NOD', x'', 1.0, 2.0,
                    3.0, -90, 4, x'', 0, 0, 1,
                    0, 0, x'', x'', x'', 'CLIENT', NULL
                )
                """.trimIndent(),
            )
            execSQL(
                """
                INSERT INTO packet (
                    uuid, myNodeNum, port_num, contact_key, received_time, read,
                    data, packet_id, routing_error, reply_id
                ) VALUES (
                    1, 42, 1, '0!0000002a', 5, 1,
                    '{"to":"!0000002a","bytes":[],"dataType":1}', 6, -1, 0
                )
                """.trimIndent(),
            )
            execSQL("INSERT INTO contact_settings (contact_key, muteUntil) VALUES ('0!0000002a', 7)")
            execSQL("INSERT INTO reactions (reply_id, user_id, emoji, timestamp) VALUES (6, '!0000002a', 'ok', 8)")
            execSQL("INSERT INTO metadata (num, proto, timestamp) VALUES (42, x'', 9)")
            execSQL(
                """
                INSERT INTO node_registry (
                    nodeId, shortName, defaultName, longName, nodeNum, publicKey,
                    latitudeI, longitudeI, lastSeen, hopCount, lastRssi
                ) VALUES (
                    '!0000002a', 'NOD', 'Meshtastic 002a', 'Node', 42,
                    x'000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f',
                    100, 200, 10, 1, -90
                )
                """.trimIndent(),
            )
            close()
        }

        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MeshtasticDatabase::class.java,
            TEST_DB_27_TO_28,
        ).build().apply {
            val readable = openHelper.readableDatabase
            assertEquals(1, readable.rowCount("my_node"))
            assertEquals(1, readable.rowCount("nodes"))
            assertEquals(1, readable.rowCount("packet"))
            assertEquals(1, readable.rowCount("contact_settings"))
            assertEquals(1, readable.rowCount("reactions"))
            assertEquals(1, readable.rowCount("metadata"))
            assertEquals(1, readable.rowCount("node_registry"))
            assertEquals(0, readable.rowCount("canonical_identity_migration"))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), nodeInfoDao().getMyNodeInfoSnapshot()?.deviceId)
            close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
