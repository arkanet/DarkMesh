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

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerializedPacketQueueTest {
    @Test
    fun enqueueDuringWorkerExitStartsReplacementWithoutStrandingPacket() = runTest {
        val first = TestPacket(1, "first")
        val second = TestPacket(2, "second")
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        var injectAtIdle = true
        lateinit var queue: SerializedPacketQueue<TestPacket>

        queue = newQueue(
            sent = sent,
            results = results,
            onIdleObserved = {
                if (injectAtIdle) {
                    injectAtIdle = false
                    assertTrue(queue.enqueue(second))
                }
            },
        )
        queue.resume()
        queue.enqueue(first)
        runCurrent()

        assertEquals(QueueStatusDisposition.EXACT_MATCH, queue.accept(first.id))
        runCurrent()

        assertEquals(listOf(first, second), sent)
        assertSame(first, sent[0])
        assertSame(second, sent[1])
        assertEquals(QueueStatusDisposition.EXACT_MATCH, queue.accept(second.id))
        runCurrent()
        assertFalse(queue.hasWorker)
        assertEquals(0, queue.queuedCount)
    }

    @Test
    fun concurrentEnqueueCreatesOnlyOneConsumer() = runTest {
        val sent = mutableListOf<TestPacket>()
        val activeWorkers = AtomicInteger()
        val maximumWorkers = AtomicInteger()
        val workerStarts = AtomicInteger()
        val queue = newQueue(
            sent = sent,
            onWorkerStarted = {
                workerStarts.incrementAndGet()
                val active = activeWorkers.incrementAndGet()
                maximumWorkers.updateAndGet { current -> maxOf(current, active) }
            },
            onWorkerStopped = { activeWorkers.decrementAndGet() },
        )
        queue.resume()

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val threads = listOf(TestPacket(11, "a"), TestPacket(12, "b")).map { packet ->
            Thread {
                ready.countDown()
                start.await()
                queue.enqueue(packet)
            }.also(Thread::start)
        }
        ready.await()
        start.countDown()
        threads.forEach(Thread::join)

        runCurrent()
        assertEquals(1, sent.size)
        assertEquals(1, workerStarts.get())
        assertEquals(1, maximumWorkers.get())

        queue.accept(sent.single().id)
        runCurrent()
        assertEquals(2, sent.size)
        queue.accept(sent.last().id)
        runCurrent()

        assertEquals(setOf(11, 12), sent.map { it.id }.toSet())
        assertEquals(1, workerStarts.get())
        assertEquals(1, maximumWorkers.get())
        assertFalse(queue.hasWorker)
    }

    @Test
    fun dispatchesPacketsInFifoOrderWithoutChangingIdentity() = runTest {
        val packets = listOf(
            TestPacket(21, "one"),
            TestPacket(22, "two"),
            TestPacket(23, "three"),
        )
        val sent = mutableListOf<TestPacket>()
        val queue = newQueue(sent = sent)
        queue.resume()
        packets.forEach(queue::enqueue)

        runCurrent()
        packets.forEachIndexed { index, packet ->
            assertEquals(index + 1, sent.size)
            assertSame(packet, sent[index])
            assertEquals(QueueStatusDisposition.EXACT_MATCH, queue.accept(packet.id))
            runCurrent()
        }

        assertEquals(packets, sent)
    }

    @Test
    fun unrelatedQueueStatusDoesNotCompleteLiveWaiter() = runTest {
        val packet = TestPacket(31, "live")
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        queue.enqueue(packet)
        runCurrent()

        assertEquals(
            QueueStatusDisposition.UNMATCHED,
            queue.handleQueueStatus(packetId = 999, success = true, isFull = false),
        )
        runCurrent()

        assertEquals(1, queue.waiterCount)
        assertTrue(results.isEmpty())
        queue.shutdown()
        runCurrent()
    }

    @Test
    fun ambiguousZeroIdQueueStatusCompletesNoWaiter() = runTest {
        val registry = TransportWaiterRegistry()
        val first = registry.register(41)!!
        val second = registry.register(42)!!

        assertEquals(
            QueueStatusDisposition.AMBIGUOUS_ZERO_ID,
            registry.completeQueueStatus(packetId = 0, success = true, isFull = false),
        )

        assertFalse(first.result.isCompleted)
        assertFalse(second.result.isCompleted)
        assertEquals(2, registry.size)
        assertEquals(2, registry.cancelAll())
    }

    @Test
    fun zeroIdQueueStatusCompletesTheOnlyLiveWaiter() = runTest {
        val registry = TransportWaiterRegistry()
        val waiter = registry.register(51)!!

        assertEquals(
            QueueStatusDisposition.ZERO_ID_SINGLE_MATCH,
            registry.completeQueueStatus(packetId = 0, success = true, isFull = false),
        )

        assertEquals(TransportResult.QUEUE_ACCEPTED, waiter.result.await())
        assertEquals(0, registry.size)
    }

    @Test
    fun exactQueueStatusCompletesOnlyExactWaiter() = runTest {
        val registry = TransportWaiterRegistry()
        val first = registry.register(61)!!
        val second = registry.register(62)!!

        assertEquals(
            QueueStatusDisposition.EXACT_MATCH,
            registry.completeQueueStatus(packetId = 62, success = true, isFull = false),
        )

        assertFalse(first.result.isCompleted)
        assertEquals(TransportResult.QUEUE_ACCEPTED, second.result.await())
        assertEquals(1, registry.size)
        registry.cancelAll()
    }

    @Test
    fun retiredWaiterCannotRemoveNewWaiterWithReusedPacketId() = runTest {
        val registry = TransportWaiterRegistry()
        val retired = registry.register(63)!!
        assertTrue(registry.completeExact(63, TransportResult.QUEUE_ACCEPTED))
        val current = registry.register(63)!!

        registry.discard(retired)

        assertEquals(1, registry.size)
        assertFalse(current.result.isCompleted)
        assertTrue(registry.completeExact(63, TransportResult.QUEUE_ACCEPTED))
        assertEquals(TransportResult.QUEUE_ACCEPTED, current.result.await())
    }

    @Test
    fun waiterIsRegisteredBeforeSynchronousQueueStatusDuringDispatch() = runTest {
        val packet = TestPacket(64, "synchronous")
        val results = mutableListOf<Pair<Int, TransportResult>>()
        lateinit var queue: SerializedPacketQueue<TestPacket>
        queue = SerializedPacketQueue(
            scope = backgroundScope,
            packetId = TestPacket::id,
            timeoutMillis = TIMEOUT_MILLIS,
            send = { sent ->
                assertSame(packet, sent)
                assertEquals(
                    QueueStatusDisposition.EXACT_MATCH,
                    queue.handleQueueStatus(sent.id, success = true, isFull = false),
                )
            },
            onResult = { sent, result -> results += sent.id to result },
        )
        queue.resume()

        assertTrue(queue.enqueue(packet))
        runCurrent()

        assertEquals(listOf(packet.id to TransportResult.QUEUE_ACCEPTED), results)
        assertEquals(0, queue.waiterCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun fullQueueStatusIsNonTerminal() = runTest {
        val registry = TransportWaiterRegistry()
        val waiter = registry.register(71)!!

        assertEquals(
            QueueStatusDisposition.QUEUE_FULL,
            registry.completeQueueStatus(packetId = 71, success = true, isFull = true),
        )

        assertFalse(waiter.result.isCompleted)
        assertEquals(1, registry.size)
        registry.cancelAll()
    }

    @Test
    fun timeoutRemovesWaiterAndAdvancesQueue() = runTest {
        val first = TestPacket(81, "timeout")
        val second = TestPacket(82, "next")
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        queue.enqueue(first)
        queue.enqueue(second)
        runCurrent()

        advanceTimeBy(TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(listOf(first, second), sent)
        assertEquals(first.id to TransportResult.TIMED_OUT, results.single())
        assertEquals(1, queue.waiterCount)
        queue.accept(second.id)
        runCurrent()
        assertEquals(0, queue.waiterCount)
    }

    @Test
    fun lateQueueStatusAfterTimeoutIsIgnored() = runTest {
        val packet = TestPacket(91, "late")
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        queue.enqueue(packet)
        runCurrent()

        advanceTimeBy(TIMEOUT_MILLIS)
        runCurrent()

        assertEquals(0, queue.waiterCount)
        assertEquals(
            QueueStatusDisposition.UNMATCHED,
            queue.handleQueueStatus(packet.id, success = true, isFull = false),
        )
        assertEquals(listOf(packet.id to TransportResult.TIMED_OUT), results)
    }

    @Test
    fun disconnectCancelsActiveAndQueuedPackets() = runTest {
        val packets = listOf(
            TestPacket(101, "active"),
            TestPacket(102, "queued-a"),
            TestPacket(103, "queued-b"),
        )
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        packets.forEach(queue::enqueue)
        runCurrent()

        queue.pauseAndClear()
        runCurrent()

        assertEquals(listOf(packets.first()), sent)
        assertEquals(
            packets.map { it.id }.toSet(),
            results.filter { it.second == TransportResult.CANCELLED }.map { it.first }.toSet(),
        )
        assertEquals(packets.size, results.size)
        packets.forEach { packet ->
            assertEquals(
                1,
                results.count { it == (packet.id to TransportResult.CANCELLED) },
            )
        }
        assertEquals(0, queue.waiterCount)
        assertEquals(0, queue.queuedCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun reconnectWaitsForCancelledConsumerToReleaseOwnership() {
        val first = TestPacket(104, "disconnecting")
        val second = TestPacket(105, "reconnected")
        val sent = CopyOnWriteArrayList<TestPacket>()
        val results = CopyOnWriteArrayList<Pair<Int, TransportResult>>()
        val activeWorkers = AtomicInteger()
        val maximumWorkers = AtomicInteger()
        val workerStarts = AtomicInteger()
        val blockFirstRelease = AtomicBoolean(true)
        val firstSent = CountDownLatch(1)
        val secondSent = CountDownLatch(1)
        val releaseReached = CountDownLatch(1)
        val allowRelease = CountDownLatch(1)
        val workersStopped = CountDownLatch(2)
        val parent = SupervisorJob()
        val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val queue = SerializedPacketQueue(
            scope = CoroutineScope(parent + dispatcher),
            packetId = TestPacket::id,
            timeoutMillis = TIMEOUT_MILLIS,
            send = { packet ->
                sent += packet
                if (packet === first) firstSent.countDown() else secondSent.countDown()
            },
            onResult = { packet, result -> results += packet.id to result },
            onWorkerStarted = {
                workerStarts.incrementAndGet()
                val active = activeWorkers.incrementAndGet()
                maximumWorkers.updateAndGet { current -> maxOf(current, active) }
            },
            onWorkerStopped = {
                activeWorkers.decrementAndGet()
                workersStopped.countDown()
            },
            onBeforeWorkerRelease = {
                if (blockFirstRelease.compareAndSet(true, false)) {
                    releaseReached.countDown()
                    allowRelease.await()
                }
            },
        )

        try {
            queue.resume()
            assertTrue(queue.enqueue(first))
            assertTrue(firstSent.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            queue.pauseAndClear()
            assertTrue(releaseReached.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            queue.resume()
            assertTrue(queue.enqueue(second))
            assertEquals(listOf(first), sent.toList())
            assertEquals(1, workerStarts.get())
            assertEquals(1, activeWorkers.get())

            allowRelease.countDown()
            assertTrue(secondSent.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(QueueStatusDisposition.EXACT_MATCH, queue.accept(second.id))
            assertTrue(workersStopped.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            assertEquals(listOf(first, second), sent.toList())
            assertEquals(2, workerStarts.get())
            assertEquals(1, maximumWorkers.get())
            assertEquals(
                setOf(
                    first.id to TransportResult.CANCELLED,
                    second.id to TransportResult.QUEUE_ACCEPTED,
                ),
                results.toSet(),
            )
            assertEquals(2, results.size)
            assertEquals(0, queue.waiterCount)
            assertEquals(0, queue.queuedCount)
            assertFalse(queue.hasWorker)
        } finally {
            allowRelease.countDown()
            queue.shutdown()
            parent.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun cancellationDuringOutstandingWaitTerminatesActiveAndQueuedPackets() = runTest {
        val packets = listOf(
            TestPacket(111, "active"),
            TestPacket(112, "queued-a"),
            TestPacket(113, "queued-b"),
        )
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val parent = SupervisorJob()
        val queueScope = CoroutineScope(parent + StandardTestDispatcher(testScheduler))
        val queue = SerializedPacketQueue(
            scope = queueScope,
            packetId = TestPacket::id,
            timeoutMillis = TIMEOUT_MILLIS,
            send = sent::add,
            onResult = { item, result -> results += item.id to result },
        )
        queue.resume()
        packets.forEach { assertTrue(queue.enqueue(it)) }
        runCurrent()

        parent.cancel()
        runCurrent()

        assertEquals(listOf(packets.first()), sent)
        assertEquals(packets.size, results.size)
        packets.forEach { packet ->
            assertEquals(
                1,
                results.count { it == (packet.id to TransportResult.CANCELLED) },
            )
        }
        assertEquals(0, queue.waiterCount)
        assertEquals(0, queue.queuedCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun shutdownIsIdempotentAndCompletesEachAcceptedPacketOnce() = runTest {
        val packets = listOf(
            TestPacket(114, "active"),
            TestPacket(115, "queued"),
        )
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        packets.forEach { assertTrue(queue.enqueue(it)) }
        runCurrent()

        queue.shutdown()
        queue.shutdown()
        runCurrent()

        assertEquals(listOf(packets.first()), sent)
        assertEquals(packets.size, results.size)
        packets.forEach { packet ->
            assertEquals(
                1,
                results.count { it == (packet.id to TransportResult.CANCELLED) },
            )
        }
        assertEquals(0, queue.waiterCount)
        assertEquals(0, queue.queuedCount)
        assertFalse(queue.hasWorker)
    }

    @Test
    fun timeoutNeverRetransmitsPacketAutomatically() = runTest {
        val packet = TestPacket(121, "once")
        val sent = mutableListOf<TestPacket>()
        val results = mutableListOf<Pair<Int, TransportResult>>()
        val queue = newQueue(sent = sent, results = results)
        queue.resume()
        queue.enqueue(packet)
        runCurrent()

        advanceTimeBy(TIMEOUT_MILLIS * 5)
        runCurrent()

        assertEquals(listOf(packet), sent)
        assertEquals(listOf(packet.id to TransportResult.TIMED_OUT), results)
        assertEquals(0, queue.waiterCount)
        assertEquals(0, queue.queuedCount)
    }

    private fun TestScope.newQueue(
        sent: MutableList<TestPacket>,
        results: MutableList<Pair<Int, TransportResult>> = mutableListOf(),
        onWorkerStarted: () -> Unit = {},
        onWorkerStopped: () -> Unit = {},
        onIdleObserved: () -> Unit = {},
        onBeforeWorkerRelease: () -> Unit = {},
    ) = SerializedPacketQueue(
        scope = backgroundScope,
        packetId = TestPacket::id,
        timeoutMillis = TIMEOUT_MILLIS,
        send = sent::add,
        onResult = { packet, result -> results += packet.id to result },
        onWorkerStarted = onWorkerStarted,
        onWorkerStopped = onWorkerStopped,
        onIdleObserved = onIdleObserved,
        onBeforeWorkerRelease = onBeforeWorkerRelease,
    )

    private fun SerializedPacketQueue<TestPacket>.accept(packetId: Int) =
        handleQueueStatus(packetId = packetId, success = true, isFull = false)

    private data class TestPacket(val id: Int, val payload: String)

    private companion object {
        const val TIMEOUT_MILLIS = 1_000L
        const val BARRIER_TIMEOUT_SECONDS = 5L
    }
}
