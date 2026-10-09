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

package com.geeksville.mesh.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshServicePacketQueueBoundaryTest {
    @Test
    fun pausedTransportRejectsPacketWithCallerVisibleNotConnectedError() = runTest {
        val packet = TestPacket(1)
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(results)
        queue.resume()
        queue.pauseAndClear()

        assertThrows(RadioNotConnectedException::class.java) {
            queue.enqueueOrThrowNotConnected(packet)
        }

        assertEquals(listOf(packet.id to TransportResult.CANCELLED), results)
        assertEquals(0, queue.queuedCount)
        assertEquals(0, queue.waiterCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun transitionAfterConnectedCheckSurfacesRejectionForCallerOfflineFallback() = runTest {
        val packet = TestPacket(2)
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val offlineFallback = mutableListOf<TestPacket>()
        val queue = newQueue(results)
        queue.resume()

        val callerObservedConnected = true
        queue.pauseAndClear()
        try {
            assertTrue(callerObservedConnected)
            queue.enqueueOrThrowNotConnected(packet)
        } catch (_: RadioNotConnectedException) {
            offlineFallback += packet
        }

        assertEquals(listOf(packet), offlineFallback)
        assertEquals(listOf(packet.id to TransportResult.CANCELLED), results)
        assertEquals(0, queue.queuedCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun shutdownRejectsPacketAndResumeCannotReplayIt() = runTest {
        val packet = TestPacket(3)
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(results, sent)
        queue.resume()
        queue.shutdown()
        queue.resume()

        assertThrows(RadioNotConnectedException::class.java) {
            queue.enqueueOrThrowNotConnected(packet)
        }

        assertTrue(sent.isEmpty())
        assertEquals(listOf(packet.id to TransportResult.CANCELLED), results)
        assertEquals(0, queue.queuedCount)
        assertFalse(queue.hasWorker)
    }

    private fun TestScope.newQueue(
        results: MutableList<Pair<Int, TransportResult>>,
        sent: MutableList<TestPacket> = mutableListOf(),
    ) = SerializedPacketQueue(
        scope = backgroundScope,
        packetId = TestPacket::id,
        timeoutMillis = 1_000L,
        send = sent::add,
        onResult = { packet, result -> results += packet.id to result },
    )

    private data class TestPacket(val id: Int)
}
