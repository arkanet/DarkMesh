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

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal enum class TransportResult {
    QUEUE_ACCEPTED,
    RESPONSE_OBSERVED,
    QUEUE_REJECTED,
    TIMED_OUT,
    CANCELLED,
    DISPATCH_FAILED,
}

internal enum class QueueStatusDisposition {
    QUEUE_FULL,
    EXACT_MATCH,
    ZERO_ID_SINGLE_MATCH,
    AMBIGUOUS_ZERO_ID,
    UNMATCHED,
}

internal class TransportWaiterRegistry {
    internal class Waiter(
        val packetId: Int,
        val result: CompletableDeferred<TransportResult> = CompletableDeferred(),
    )

    private val lock = Any()
    private val waiters = linkedMapOf<Int, Waiter>()

    val size: Int
        get() = synchronized(lock) { waiters.size }

    fun register(packetId: Int): Waiter? = synchronized(lock) {
        if (waiters.containsKey(packetId)) {
            null
        } else {
            Waiter(packetId).also { waiters[packetId] = it }
        }
    }

    fun completeExact(packetId: Int, result: TransportResult): Boolean {
        val waiter = synchronized(lock) { waiters.remove(packetId) } ?: return false
        return waiter.result.complete(result)
    }

    fun completeQueueStatus(
        packetId: Int,
        success: Boolean,
        isFull: Boolean,
    ): QueueStatusDisposition {
        if (success && isFull) return QueueStatusDisposition.QUEUE_FULL

        val (disposition, waiter) = synchronized(lock) {
            when {
                packetId != 0 -> {
                    val exact = waiters.remove(packetId)
                    if (exact == null) {
                        QueueStatusDisposition.UNMATCHED to null
                    } else {
                        QueueStatusDisposition.EXACT_MATCH to exact
                    }
                }

                waiters.size == 1 -> {
                    val single = waiters.values.single()
                    waiters.remove(single.packetId)
                    QueueStatusDisposition.ZERO_ID_SINGLE_MATCH to single
                }

                waiters.isEmpty() -> QueueStatusDisposition.UNMATCHED to null
                else -> QueueStatusDisposition.AMBIGUOUS_ZERO_ID to null
            }
        }

        waiter?.result?.complete(
            if (success) TransportResult.QUEUE_ACCEPTED else TransportResult.QUEUE_REJECTED
        )
        return disposition
    }

    fun discard(waiter: Waiter) {
        val removed = synchronized(lock) {
            if (waiters[waiter.packetId] === waiter) {
                waiters.remove(waiter.packetId)
                true
            } else {
                false
            }
        }
        if (removed) waiter.result.cancel()
    }

    fun cancelAll(): Int {
        val cancelled = synchronized(lock) {
            waiters.values.toList().also { waiters.clear() }
        }
        cancelled.forEach { it.result.complete(TransportResult.CANCELLED) }
        return cancelled.size
    }
}

/**
 * A lifecycle-aware FIFO with one coroutine consumer. Queue ownership and enqueue/exit
 * transitions are serialized under [lock]; radio callbacks complete waiters through the
 * independently synchronized [TransportWaiterRegistry].
 */
internal class SerializedPacketQueue<T>(
    private val scope: CoroutineScope,
    private val packetId: (T) -> Int,
    private val timeoutMillis: Long,
    private val send: (T) -> Unit,
    private val onResult: (T, TransportResult) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {},
    private val onWorkerStarted: () -> Unit = {},
    private val onWorkerStopped: () -> Unit = {},
    private val onIdleObserved: () -> Unit = {},
    private val onBeforeWorkerRelease: () -> Unit = {},
) {
    private val lock = Any()
    private val queue = ArrayDeque<T>()
    private val waiters = TransportWaiterRegistry()
    private val scopeJob = scope.coroutineContext[Job]

    private var worker: Job? = null
    private var running = false
    private var closed = false

    init {
        scopeJob?.invokeOnCompletion { terminateForScopeCancellation() }
    }

    internal val queuedCount: Int
        get() = synchronized(lock) { queue.size }

    internal val waiterCount: Int
        get() = waiters.size

    internal val hasWorker: Boolean
        get() = synchronized(lock) { worker != null }

    fun resume() {
        val workerToStart = synchronized(lock) {
            if (closed || scopeJob?.isActive == false) return
            running = true
            createWorkerLocked()
        }
        workerToStart?.start()
    }

    fun enqueue(item: T): Boolean {
        var rejected = false
        val workerToStart = synchronized(lock) {
            if (scopeJob?.isActive == false) {
                running = false
                closed = true
            }
            if (!running || closed) {
                rejected = true
                null
            } else {
                queue.addLast(item)
                createWorkerLocked()
            }
        }

        if (rejected) {
            reportResult(item, TransportResult.CANCELLED)
            return false
        }

        workerToStart?.start()
        return true
    }

    fun handleQueueStatus(
        packetId: Int,
        success: Boolean,
        isFull: Boolean,
    ): QueueStatusDisposition = waiters.completeQueueStatus(packetId, success, isFull)

    fun completeExact(packetId: Int, result: TransportResult): Boolean =
        waiters.completeExact(packetId, result)

    fun clearQueued() {
        val discarded = synchronized(lock) { drainQueueLocked() }
        discarded.forEach { reportResult(it, TransportResult.CANCELLED) }
    }

    fun pauseAndClear() {
        stop(closing = false)
    }

    fun shutdown() {
        stop(closing = true)
    }

    private fun stop(closing: Boolean) {
        val (workerToCancel, discarded) = synchronized(lock) {
            running = false
            if (closing) closed = true
            val activeWorker = worker
            activeWorker to drainQueueLocked()
        }

        waiters.cancelAll()
        workerToCancel?.cancel()
        val replacement = synchronized(lock) {
            // A lazy worker can be cancelled before its body (and therefore its finally block)
            // ever runs. Active workers retain ownership until drain() finishes, preventing a
            // reconnect from overlapping the old consumer while it unwinds.
            if (worker === workerToCancel && workerToCancel?.isCompleted == true) {
                worker = null
                createWorkerLocked()
            } else {
                null
            }
        }
        discarded.forEach { reportResult(it, TransportResult.CANCELLED) }
        replacement?.start()
    }

    private fun createWorkerLocked(): Job? {
        if (
            !running ||
            closed ||
            scopeJob?.isActive == false ||
            queue.isEmpty() ||
            worker != null
        ) {
            return null
        }

        lateinit var newWorker: Job
        newWorker = scope.launch(start = CoroutineStart.LAZY) {
            drain(newWorker)
        }
        worker = newWorker
        return newWorker
    }

    private suspend fun drain(owner: Job) {
        try {
            onWorkerStarted()
            while (currentCoroutineContext().isActive) {
                val item = synchronized(lock) {
                    if (!running || closed || worker !== owner) null else queue.pollFirst()
                }

                if (item == null) {
                    onIdleObserved()
                    return
                }

                val result = dispatchAndAwait(owner, item)
                reportResult(item, result)
            }
        } catch (exception: CancellationException) {
            // The active item reports cancellation from dispatchAndAwait. Cancellation before
            // dispatch has no item to report and is completed by pause/shutdown queue draining.
        } catch (throwable: Throwable) {
            onError(throwable)
        } finally {
            try {
                onBeforeWorkerRelease()
            } catch (throwable: Throwable) {
                onError(throwable)
            }

            var scopeCancelled = false
            val (replacement, discarded) = synchronized(lock) {
                if (worker === owner) worker = null
                scopeCancelled = scopeJob?.isActive == false
                if (scopeCancelled) {
                    running = false
                    closed = true
                    null to drainQueueLocked()
                } else {
                    createWorkerLocked() to emptyList()
                }
            }
            if (scopeCancelled) waiters.cancelAll()
            discarded.forEach { reportResult(it, TransportResult.CANCELLED) }
            try {
                onWorkerStopped()
            } catch (throwable: Throwable) {
                onError(throwable)
            } finally {
                replacement?.start()
            }
        }
    }

    private suspend fun dispatchAndAwait(owner: Job, item: T): TransportResult {
        val id = packetId(item)
        val waiter = waiters.register(id)
        if (waiter == null) {
            onError(IllegalStateException("Duplicate live transport packet ID: ${id.toUInt()}"))
            return TransportResult.DISPATCH_FAILED
        }

        return try {
            val dispatched = synchronized(lock) {
                if (!owner.isActive || !running || closed || worker !== owner) {
                    false
                } else {
                    send(item)
                    true
                }
            }
            if (!dispatched) {
                TransportResult.CANCELLED
            } else {
                withTimeout(timeoutMillis) { waiter.result.await() }
            }
        } catch (_: TimeoutCancellationException) {
            TransportResult.TIMED_OUT
        } catch (_: CancellationException) {
            TransportResult.CANCELLED
        } catch (throwable: Throwable) {
            onError(throwable)
            TransportResult.DISPATCH_FAILED
        } finally {
            waiters.discard(waiter)
        }
    }

    private fun drainQueueLocked(): List<T> = buildList(queue.size) {
        while (true) add(queue.pollFirst() ?: break)
    }

    private fun terminateForScopeCancellation() {
        val discarded = synchronized(lock) {
            running = false
            closed = true
            worker = null
            drainQueueLocked()
        }
        waiters.cancelAll()
        discarded.forEach { reportResult(it, TransportResult.CANCELLED) }
    }

    private fun reportResult(item: T, result: TransportResult) {
        try {
            onResult(item, result)
        } catch (throwable: Throwable) {
            onError(throwable)
        }
    }
}

internal fun <T> SerializedPacketQueue<T>.enqueueOrThrowNotConnected(item: T) {
    if (!enqueue(item)) throw RadioNotConnectedException()
}
