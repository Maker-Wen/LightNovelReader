package indi.dmzz_yyhyy.lightnovelreader.coroutine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext


@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class PriorityDispatcher(
    private val maxConcurrency: Int,
    private val defaultPriority: Int = 0,
    startPaused: Boolean = false,
    backendDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CoroutineDispatcher() {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be greater than 0" }
    }

    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workerScope = CoroutineScope(SupervisorJob() + backendDispatcher)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closed = CompletableDeferred<Unit>()

    init {
        controlScope.launch {
            runCoordinator(startPaused)
        }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val priority = context[Priority]?.value ?: defaultPriority
        val job = context[Job]
        if (commands.trySend(Command.Enqueue(priority, block, job)).isFailure) {
            reject(job, block)
        }
    }

    suspend fun close() {
        if (!closed.isCompleted) {
            commands.trySend(Command.Shutdown)
        }
        closed.await()
    }

    private fun reject(job: Job?, block: Runnable) {
        job?.cancel(CancellationException("dispatcher is closed"))
        // A rejected continuation still has to run to perform cancellation cleanup.
        Dispatchers.IO.dispatch(EmptyCoroutineContext, block)
    }

    private suspend fun runCoordinator(startPaused: Boolean) {
        val readyTasks = TaskHeap()
        val pendingStarts = TaskHeap()
        val knownJobs = mutableSetOf<Job>()
        val activeJobs = mutableSetOf<Job>()
        var paused = startPaused
        var acceptingTasks = true
        var nextSequence = 0L

        fun activeAncestor(job: Job?): Job? =
            generateSequence(job) { it.parent }.firstOrNull { it in activeJobs }

        fun knownAncestor(job: Job?): Job? =
            generateSequence(job) { it.parent }.lastOrNull { it in knownJobs }

        fun enqueue(priority: Int, block: Runnable, job: Job?) {
            val task = ScheduledTask(
                priority = priority,
                sequence = nextSequence++,
                block = block,
                job = job,
            )

            if (job == null) {
                readyTasks.add(task)
                return
            }

            if (job !in knownJobs) {
                knownJobs += job
                job.invokeOnCompletion {
                    commands.trySend(Command.JobCompleted(job))
                }
            }

            if (job.isCompleted || activeAncestor(job) != null) {
                readyTasks.add(task)
            } else {
                pendingStarts.add(task)
            }
        }

        fun launchTask(task: ScheduledTask) {
            workerScope.launch {
                runCatching { task.block.run() }
                    .onFailure(Throwable::printStackTrace)
            }
        }

        fun launchReadyTasks() {
            if (paused) {
                return
            }

            while (readyTasks.isNotEmpty()) {
                launchTask(readyTasks.removeFirst())
            }

            while (pendingStarts.isNotEmpty()) {
                val task = if (activeJobs.size < maxConcurrency) {
                    pendingStarts.removeFirst()
                } else {
                    // Descendants may have queued before their ancestor was admitted.
                    pendingStarts.removeFirstMatching {
                        it.job?.isCompleted == true || activeAncestor(it.job) != null
                    } ?: break
                }
                val job = task.job
                if (job != null && !job.isCompleted && activeAncestor(job) == null) {
                    // Only ancestors dispatched here share a permit; an external scope's
                    // common SupervisorJob must not merge independent requests.
                    val requestJob = knownAncestor(job) ?: job
                    if (!requestJob.isCompleted) activeJobs += requestJob
                }
                launchTask(task)
            }
        }

        for (command in commands) {
            when (command) {
                is Command.Enqueue -> {
                    if (acceptingTasks || knownAncestor(command.job) != null) {
                        enqueue(command.priority, command.block, command.job)
                    } else {
                        reject(command.job, command.block)
                    }
                }

                is Command.JobCompleted -> {
                    activeJobs -= command.job
                    knownJobs -= command.job
                }

                Command.Pause -> paused = true
                Command.Resume -> paused = false
                Command.Shutdown -> {
                    acceptingTasks = false
                    paused = false
                }
            }

            launchReadyTasks()

            if (!acceptingTasks && activeJobs.isEmpty() && pendingStarts.isEmpty() && readyTasks.isEmpty()) {
                break
            }
        }

        commands.close()
        // dispatch() may have queued work just before close won the race.
        for (command in commands) {
            if (command is Command.Enqueue) reject(command.job, command.block)
        }
        workerScope.coroutineContext[Job]?.children?.toList()?.joinAll()
        workerScope.cancel()
        controlScope.cancel()
        closed.complete(Unit)
    }

    class Priority(val value: Int) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Priority>
    }

    private sealed interface Command {
        data class Enqueue(
            val priority: Int,
            val block: Runnable,
            val job: Job?,
        ) : Command

        data class JobCompleted(val job: Job) : Command
        data object Pause : Command
        data object Resume : Command
        data object Shutdown : Command
    }

    private data class ScheduledTask(
        val priority: Int,
        val sequence: Long,
        val block: Runnable,
        val job: Job?,
    )

    private class TaskHeap {
        private val items = mutableListOf<ScheduledTask>()

        fun add(task: ScheduledTask) {
            items += task
            siftUp(items.lastIndex)
        }

        fun removeFirst(): ScheduledTask {
            return removeAt(0)
        }

        fun removeFirstMatching(predicate: (ScheduledTask) -> Boolean): ScheduledTask? {
            var best = -1
            for (index in items.indices) {
                if (predicate(items[index]) && (best == -1 || items[index] > items[best])) {
                    best = index
                }
            }
            return if (best == -1) null else removeAt(best)
        }

        private fun removeAt(index: Int): ScheduledTask {
            val first = items[index]
            val last = items.removeAt(items.lastIndex)
            if (index < items.size) {
                items[index] = last
                if (index > 0 && items[index] > items[(index - 1) / 2]) {
                    siftUp(index)
                } else {
                    siftDown(index)
                }
            }
            return first
        }

        fun isEmpty(): Boolean = items.isEmpty()

        fun isNotEmpty(): Boolean = items.isNotEmpty()

        private fun siftUp(startIndex: Int) {
            var index = startIndex
            while (index > 0) {
                val parentIndex = (index - 1) / 2
                if (items[parentIndex] >= items[index]) {
                    return
                }
                items.swap(parentIndex, index)
                index = parentIndex
            }
        }

        private fun siftDown(startIndex: Int = 0) {
            var index = startIndex
            while (true) {
                val left = index * 2 + 1
                val right = left + 1
                if (left >= items.size) {
                    return
                }

                var best = left
                if (right < items.size && items[right] > items[left]) {
                    best = right
                }

                if (items[index] >= items[best]) {
                    return
                }

                items.swap(index, best)
                index = best
            }
        }

        private fun MutableList<ScheduledTask>.swap(first: Int, second: Int) {
            val tmp = this[first]
            this[first] = this[second]
            this[second] = tmp
        }

        private operator fun ScheduledTask.compareTo(other: ScheduledTask): Int {
            val priorityComparison = priority.compareTo(other.priority)
            return if (priorityComparison != 0) {
                priorityComparison
            } else {
                other.sequence.compareTo(sequence)
            }
        }
    }
}
