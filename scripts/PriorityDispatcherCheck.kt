import com.github.michaelbull.result.coroutines.coroutineBinding
import indi.dmzz_yyhyy.lightnovelreader.coroutine.PriorityDispatcher
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

private const val TIMEOUT = 2_000L

private suspend fun barrier(dispatcher: PriorityDispatcher) {
    val reached = CompletableDeferred<Unit>()
    dispatcher.dispatch(EmptyCoroutineContext, Runnable { reached.complete(Unit) })
    withTimeout(TIMEOUT) { reached.await() }
}

private suspend fun withDispatcher(
    capacity: Int,
    backend: CoroutineDispatcher = Dispatchers.Unconfined,
    block: suspend (PriorityDispatcher, CoroutineScope) -> Unit,
) {
    val dispatcher = PriorityDispatcher(capacity, backendDispatcher = backend)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        withTimeout(TIMEOUT) { block(dispatcher, scope) }
        withTimeout(TIMEOUT) { dispatcher.close() }
    } finally {
        scope.cancel()
        // Failed baseline checks must not hang the whole JVM on a wedged dispatcher.
        withTimeoutOrNull(100) { dispatcher.close() }
    }
}

private suspend fun nestedRequestsRespectCapacity() = withDispatcher(5, Dispatchers.IO) { dispatcher, scope ->
    val entered = Channel<Unit>(Channel.UNLIMITED)
    val release = CompletableDeferred<Unit>()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val responses = AtomicInteger()
    val resumed = AtomicInteger()
    val requests = List(10) {
        scope.async(start = CoroutineStart.UNDISPATCHED) {
            withContext(dispatcher) {
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    // Same nested Job shape as Wenku8's coroutineBinding + IO request.
                    coroutineBinding<Int, Throwable> {
                        withContext(Dispatchers.IO) {
                            entered.send(Unit)
                            release.await()
                            responses.incrementAndGet()
                        }
                        resumed.incrementAndGet()
                    }
                } finally {
                    active.decrementAndGet()
                }
            }
        }
    }
    barrier(dispatcher)
    repeat(5) { entered.receive() }
    check(active.get() == 5) { "expected exactly five admitted requests" }
    release.complete(Unit)
    try {
        requests.awaitAll()
    } finally {
        println("  nested requests: responses=${responses.get()} resumed=${resumed.get()} peak=${peak.get()}")
    }
    check(responses.get() == 10 && resumed.get() == 10)
    check(peak.get() == 5 && active.get() == 0)
}

private suspend fun pendingFamilySharesPermit(childPriority: Int, parentPriority: Int) =
    withDispatcher(1) { dispatcher, _ ->
        val blocker = Job()
        val parent = Job()
        val child = Job(parent)
        val independent = Job()
        val events = Channel<String>(Channel.UNLIMITED)
        try {
            dispatcher.dispatch(blocker, Runnable {})
            barrier(dispatcher)
            // Child is already pending when its ancestor is first registered.
            dispatcher.dispatch(child + PriorityDispatcher.Priority(childPriority), Runnable {
                events.trySend("child")
                child.complete()
            })
            dispatcher.dispatch(parent + PriorityDispatcher.Priority(parentPriority), Runnable {
                events.trySend("parent")
                parent.complete()
            })
            dispatcher.dispatch(independent + PriorityDispatcher.Priority(0), Runnable {
                events.trySend("independent")
                independent.complete()
            })
            barrier(dispatcher)
            blocker.complete()
            val actual = List(3) { events.receive() }
            val familyOrder = if (childPriority > parentPriority) listOf("child", "parent")
                else listOf("parent", "child")
            check(actual == familyOrder + "independent") { "unexpected pending order: $actual" }
            parent.join()
            independent.join()
        } finally {
            blocker.cancel()
            parent.cancel()
            independent.cancel()
        }
    }

private suspend fun independentPriorityAndFifo() = withDispatcher(1) { dispatcher, _ ->
    val blocker = Job()
    val jobs = List(4) { Job() }
    val events = Channel<Int>(Channel.UNLIMITED)
    try {
        dispatcher.dispatch(blocker, Runnable {})
        barrier(dispatcher)
        listOf(0, 10, 10, -1).forEachIndexed { index, priority ->
            dispatcher.dispatch(jobs[index] + PriorityDispatcher.Priority(priority), Runnable {
                events.trySend(index)
                jobs[index].complete()
            })
        }
        barrier(dispatcher)
        blocker.complete()
        val actual = List(4) { events.receive() }
        check(actual == listOf(1, 2, 0, 3)) { "priority/FIFO changed: $actual" }
        jobs.forEach { it.join() }
    } finally {
        blocker.cancel()
        jobs.forEach { it.cancel() }
    }
}

private suspend fun parentCancellationReleasesPermit() = withDispatcher(1) { dispatcher, scope ->
    val entered = CompletableDeferred<Unit>()
    val cleanup = CompletableDeferred<Unit>()
    val request = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) {
            try {
                coroutineScope {
                    withContext(Dispatchers.IO) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            } finally {
                cleanup.complete(Unit)
            }
        }
    }
    entered.await()
    val next = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) { "next" }
    }
    barrier(dispatcher)
    request.cancel()
    cleanup.await()
    request.join()
    check(next.await() == "next")
}

private suspend fun childCancellationKeepsParentPermit() = withDispatcher(1) { dispatcher, scope ->
    val entered = CompletableDeferred<Job>()
    val childCleanup = CompletableDeferred<Unit>()
    val parentHolding = CompletableDeferred<Unit>()
    val releaseParent = CompletableDeferred<Unit>()
    val nextEntered = CompletableDeferred<Unit>()
    val parent = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) {
            coroutineScope {
                val child = launch {
                    try {
                        withContext(Dispatchers.IO) {
                            entered.complete(coroutineContext[Job]!!)
                            awaitCancellation()
                        }
                    } finally {
                        childCleanup.complete(Unit)
                    }
                }
                child.join()
            }
            parentHolding.complete(Unit)
            releaseParent.await()
        }
    }
    val child = entered.await()
    val next = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) { nextEntered.complete(Unit) }
    }
    barrier(dispatcher)
    child.cancel()
    childCleanup.await()
    parentHolding.await()
    barrier(dispatcher)
    check(!nextEntered.isCompleted) { "child cancellation released its parent's permit" }
    releaseParent.complete(Unit)
    parent.await()
    next.await()
}

private suspend fun completedPendingJobDoesNotLeakPermit() = withDispatcher(1) { dispatcher, _ ->
    val blocker = Job()
    val completed = Job()
    val next = Job()
    val ranCompleted = CompletableDeferred<Unit>()
    val ranNext = CompletableDeferred<Unit>()
    try {
        dispatcher.dispatch(blocker, Runnable {})
        barrier(dispatcher)
        dispatcher.dispatch(completed, Runnable { ranCompleted.complete(Unit) })
        barrier(dispatcher)
        completed.complete()
        barrier(dispatcher)
        dispatcher.dispatch(next, Runnable { ranNext.complete(Unit); next.complete() })
        blocker.complete()
        ranCompleted.await()
        ranNext.await()
        next.join()
    } finally {
        blocker.cancel()
        completed.cancel()
        next.cancel()
    }
}

private suspend fun closeDrainsExistingRequestsAndRejectsNewOnes() = withDispatcher(1) { dispatcher, scope ->
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val resumed = CompletableDeferred<Unit>()
    val unexpected = CompletableDeferred<Unit>()
    val active = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) {
            coroutineScope { withContext(Dispatchers.IO) { entered.complete(Unit); release.await() } }
            resumed.complete(Unit)
        }
    }
    entered.await()
    val pending = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) { "accepted before close" }
    }
    barrier(dispatcher)
    val closing = scope.async(start = CoroutineStart.UNDISPATCHED) { dispatcher.close() }
    val closingAgain = scope.async(start = CoroutineStart.UNDISPATCHED) { dispatcher.close() }
    val rejected = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) { unexpected.complete(Unit) }
    }
    rejected.join()
    check(rejected.isCancelled && !unexpected.isCompleted)
    check(!closing.isCompleted) { "close returned before the active request completed" }
    release.complete(Unit)
    active.await()
    resumed.await()
    check(pending.await() == "accepted before close")
    closing.await()
    closingAgain.await()
    val afterClose = scope.async(start = CoroutineStart.UNDISPATCHED) {
        withContext(dispatcher) { unexpected.complete(Unit) }
    }
    afterClose.join()
    check(afterClose.isCancelled && !unexpected.isCompleted)
}

private suspend fun closeRejectsAlreadyBufferedNewRequests() = withDispatcher(1) { dispatcher, scope ->
    val coordinatorBlocked = CompletableDeferred<Unit>()
    val releaseCoordinator = CountDownLatch(1)
    val unexpected = CompletableDeferred<Unit>()
    try {
        // Unconfined backend keeps the actor here until both commands are buffered.
        dispatcher.dispatch(EmptyCoroutineContext, Runnable {
            coordinatorBlocked.complete(Unit)
            check(releaseCoordinator.await(TIMEOUT, TimeUnit.MILLISECONDS))
        })
        coordinatorBlocked.await()
        val closing = scope.async(start = CoroutineStart.UNDISPATCHED) { dispatcher.close() }
        val rejected = scope.async(start = CoroutineStart.UNDISPATCHED) {
            withContext(dispatcher) { unexpected.complete(Unit) }
        }
        releaseCoordinator.countDown()
        closing.await()
        rejected.join()
        check(rejected.isCancelled && !unexpected.isCompleted) {
            "a request buffered behind Shutdown escaped rejection"
        }
    } finally {
        releaseCoordinator.countDown()
    }
}

fun main() = runBlocking {
    val checks = listOf<Pair<String, suspend () -> Unit>>(
        "nested requests finish at capacity" to ::nestedRequestsRespectCapacity,
        "pending child follows admitted parent" to { pendingFamilySharesPermit(-10, 10) },
        "high-priority pending child shares parent's permit" to { pendingFamilySharesPermit(10, -10) },
        "independent priority and FIFO" to ::independentPriorityAndFifo,
        "parent cancellation frees its permit" to ::parentCancellationReleasesPermit,
        "child cancellation keeps parent's permit" to ::childCancellationKeepsParentPermit,
        "completed pending job cannot leak a permit" to ::completedPendingJobDoesNotLeakPermit,
        "close drains accepted jobs and rejects new jobs" to ::closeDrainsExistingRequestsAndRejectsNewOnes,
        "close rejects new requests already buffered behind shutdown" to ::closeRejectsAlreadyBufferedNewRequests,
    )
    val failures = mutableListOf<String>()
    for ((name, check) in checks) {
        runCatching { check() }
            .onSuccess { println("PASS: $name") }
            .onFailure { failure ->
                failures += name
                println("FAIL: $name (${failure::class.simpleName}: ${failure.message})")
            }
    }
    check(failures.isEmpty()) { "${failures.size}/${checks.size} priority dispatcher checks failed: $failures" }
    println("PASS: all ${checks.size} priority dispatcher checks")
}
