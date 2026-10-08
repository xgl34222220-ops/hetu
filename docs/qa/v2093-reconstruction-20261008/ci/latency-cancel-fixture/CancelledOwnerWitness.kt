import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun main() = runBlocking {
    val parent = SupervisorJob()
    val scope = CoroutineScope(parent + Dispatchers.IO)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val busy = AtomicBoolean(true)
    val publishedLate = AtomicBoolean(false)
    val child = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                currentCoroutineContext().ensureActive()
            }
            publishedLate.set(true)
        } finally { busy.set(false) }
    }
    check(entered.await(5, TimeUnit.SECONDS))
    parent.cancel()
    check(!child.isActive)
    check(!child.isCompleted)
    check(busy.get())
    check(parent.children.none { it.isActive })
    check(!parent.children.all { it.isCompleted })
    println("cancelled while transport held: isActive=${child.isActive}, isCompleted=${child.isCompleted}, busy=${busy.get()}, oldHelperPremature=true")
    release.countDown()
    withTimeout(5_000) { child.join() }
    check(child.isCompleted)
    check(!busy.get())
    check(!publishedLate.get())
    check(parent.children.all { it.isCompleted })
    println("transport released and cancellation completed: isCompleted=${child.isCompleted}, busy=${busy.get()}, latePublished=${publishedLate.get()}, newHelperReady=true")
}
