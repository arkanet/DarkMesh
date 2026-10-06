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

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.MeshProtos

class MyNodeEntityTest {

    private fun myNode(
        nodeNum: Int,
        deviceId: ByteArray = byteArrayOf(),
    ) = MyNodeEntity(
        myNodeNum = nodeNum,
        model = "heltec-v4",
        firmwareVersion = "2.8.1",
        couldUpdate = false,
        shouldUpdate = false,
        currentPacketId = 1L,
        messageTimeoutMsec = 300_000,
        minAppVersion = 30200,
        maxChannels = 8,
        hasWifi = false,
        deviceId = deviceId,
    )

    @Test
    fun deviceIdIsCopiedExactlyFromMyNodeInfo() {
        val expected = ByteArray(16) { (it + 1).toByte() }
        val proto = MeshProtos.MyNodeInfo.newBuilder()
            .setDeviceId(ByteString.copyFrom(expected))
            .build()

        assertArrayEquals(expected, proto.deviceIdBytes())
    }

    @Test
    fun deviceIdSurvivesEntityModelRoundTrip() {
        val expected = ByteArray(16) { (it + 3).toByte() }

        val model = myNode(nodeNum = 10, deviceId = expected).toMyNodeInfo()

        assertArrayEquals(expected, model.deviceId)
    }

    @Test
    fun sameDeviceAndNodeNumberDoesNotRequestMigration() {
        val deviceId = byteArrayOf(1, 2, 3)

        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10, deviceId = deviceId),
            incoming = myNode(nodeNum = 10, deviceId = deviceId.copyOf()),
        )

        assertEquals(LocalDeviceContinuity.SAME_PHYSICAL_DEVICE, result)
    }

    @Test
    fun sameDeviceAndChangedNodeNumberRequestsMigration() {
        val deviceId = byteArrayOf(1, 2, 3)

        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10, deviceId = deviceId),
            incoming = myNode(nodeNum = 20, deviceId = deviceId.copyOf()),
        )

        assertEquals(LocalDeviceContinuity.SAME_PHYSICAL_DEVICE_NODE_MIGRATION, result)
    }

    @Test
    fun differentDeviceIdsNeverRequestMigration() {
        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10, deviceId = byteArrayOf(1)),
            incoming = myNode(nodeNum = 20, deviceId = byteArrayOf(2)),
        )

        assertEquals(LocalDeviceContinuity.DIFFERENT_DEVICE, result)
    }

    @Test
    fun emptyStoredDeviceIdPreservesLegacyBehavior() {
        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10),
            incoming = myNode(nodeNum = 20, deviceId = byteArrayOf(2)),
        )

        assertEquals(LocalDeviceContinuity.LEGACY_OR_UNKNOWN_IDENTITY, result)
    }

    @Test
    fun emptyIncomingDeviceIdPreservesLegacyBehavior() {
        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10, deviceId = byteArrayOf(1)),
            incoming = myNode(nodeNum = 20),
        )

        assertEquals(LocalDeviceContinuity.LEGACY_OR_UNKNOWN_IDENTITY, result)
    }

    @Test
    fun matchingBleAddressCannotAuthorizeMigrationWithoutDeviceId() {
        val storedBleAddress = "F8:5B:1B:A2:FE:21"
        val incomingBleAddress = storedBleAddress

        val result = classifyLocalDeviceContinuity(
            stored = myNode(nodeNum = 10),
            incoming = myNode(nodeNum = 20),
        )

        assertTrue(storedBleAddress == incomingBleAddress)
        assertEquals(LocalDeviceContinuity.LEGACY_OR_UNKNOWN_IDENTITY, result)
    }
}
