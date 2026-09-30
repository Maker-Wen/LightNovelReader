@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import androidx.compose.runtime.State
import androidx.compose.runtime.snapshots.Snapshot
import indi.dmzz_yyhyy.lightnovelreader.data.setting.AbstractSettingState
import io.nightfish.lightnovelreader.api.userdata.UserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private class QueuedMainDispatcher : MainCoroutineDispatcher() {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    var onDispatch: () -> Unit = {}
    override val immediate: MainCoroutineDispatcher get() = this
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.add(block)
        onDispatch()
    }
    fun drain() {
        while (true) (queue.poll() ?: return).run()
    }
}

private class MemoryUserData<T>(initial: T?, private val onEmission: () -> Unit = {}) :
    UserData<T>("check.setting") {
    val values = MutableStateFlow(initial)
    val emissions = AtomicInteger()
    val activeCollectors = AtomicInteger()
    val collectedOnMain = AtomicBoolean()
    private val mainThread = Thread.currentThread()
    override suspend fun set(value: T) { values.value = value }
    override suspend fun get(): T? = values.value
    override fun getFlow(): Flow<T?> = flow {
        activeCollectors.incrementAndGet()
        try {
            values.collect {
                if (Thread.currentThread() === mainThread) collectedOnMain.set(true)
                emit(it)
                emissions.incrementAndGet()
                onEmission()
            }
        } finally {
            activeCollectors.decrementAndGet()
        }
    }
}

private class Settings(scope: CoroutineScope) : AbstractSettingState(scope) {
    fun <T> bind(data: UserData<T>, initial: T, safe: Boolean): State<T> =
        if (safe) data.safeAsState(initial) else data.asState(initial)
}

private fun await(main: QueuedMainDispatcher, message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (System.nanoTime() < deadline) {
        main.drain()
        if (condition()) return
        Thread.sleep(1)
    }
    error(message)
}

private fun <T> initialValue(main: QueuedMainDispatcher, safe: Boolean, initial: T, saved: T) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val started = CountDownLatch(1)
    val data = MemoryUserData(saved) { started.countDown() }
    val outer = Snapshot.takeMutableSnapshot()
    val composition = outer.takeNestedMutableSnapshot()
    main.onDispatch = { started.countDown() }
    lateinit var state: State<T>
    try {
        composition.enter {
            state = Settings(scope).bind(data, initial, safe)
            // Wait for either an IO publication or a queued Main collection. Main cannot run
            // until this composition finishes, matching the Android UI thread's ordering.
            check(started.await(3, TimeUnit.SECONDS)) { "Collection was neither queued nor started" }
        }
        composition.apply().check()
        outer.apply().check()
        await(main, "Saved value was lost after nested snapshot applied: ${state.value}") {
            state.value == saved
        }
        check(!data.collectedOnMain.get()) { "UserData collection moved onto the UI thread" }
    } finally {
        composition.dispose()
        outer.dispose()
        scope.cancel()
        await(main, "Collector survived cancelled owner") { data.activeCollectors.get() == 0 }
        main.onDispatch = {}
    }
}

private fun updatesAndCancellation(main: QueuedMainDispatcher, safe: Boolean) {
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.Main)
    val data = MemoryUserData<String>(null)
    val state = Settings(scope).bind(data, "default", safe)
    try {
        await(main, "Initial missing value was not collected") { data.emissions.get() == 1 }
        check(state.value == "default")
        data.values.value = "GitHub"
        await(main, "Subsequent setting change did not reach state") { state.value == "GitHub" }
        data.values.value = null
        await(main, "Removed setting did not restore default") { state.value == "default" }
        check(!data.collectedOnMain.get()) { "UserData collection moved onto the UI thread" }
        scope.cancel()
        await(main, "Cancellation did not stop the upstream collection") {
            job.isCompleted && data.activeCollectors.get() == 0
        }
        data.values.value = "after-cancel"
        main.drain()
        check(state.value == "default") { "Cancelled setting state still changed" }
    } finally {
        scope.cancel()
        main.drain()
    }
}

fun main() {
    val main = QueuedMainDispatcher()
    Dispatchers.setMain(main)
    var passed = 0
    var failed = 0
    fun checkCase(name: String, action: () -> Unit) {
        try {
            action()
            println("PASS $name")
            passed++
        } catch (error: Throwable) {
            println("FAIL $name: ${error.message}")
            failed++
        }
    }
    try {
        for (safe in listOf(false, true)) {
            val helper = if (safe) "safeAsState" else "asState"
            checkCase("$helper restores Boolean across nested composition") {
                initialValue(main, safe, false, true)
            }
            checkCase("$helper restores String across nested composition") {
                initialValue(main, safe, "LnrAPI", "GitHub")
            }
            checkCase("$helper restores Float across nested composition") {
                initialValue(main, safe, 16f, 23f)
            }
            checkCase("$helper observes updates/null on IO and stops with its owner") {
                updatesAndCancellation(main, safe)
            }
        }
    } finally {
        Dispatchers.resetMain()
    }
    println("$passed passed; $failed failed")
    check(failed == 0) { "Setting state regression checks failed" }
}
