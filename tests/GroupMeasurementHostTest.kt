import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.Executors

private object SystemClock { private var now = 100L; fun elapsedRealtime() = ++now }
private class MihomoControllerClient { class DelayFailure(val timedOut: Boolean) : IOException() }
private data class ProxyNodeUi(val name: String)
private data class ProxyGroupUi(val name: String, val nodes: List<ProxyNodeUi>)
private data class State(val running: Boolean)
private class Repository {
    val calls = mutableListOf<String>()
    val failures = mutableMapOf<String, Exception>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    var active = 0
    var peak = 0
    var groupCalls = 0
    suspend fun delay(node: String): Long {
        calls += node
        active++
        peak = maxOf(peak, active)
        try {
            gates[node]?.await()
            failures[node]?.let { throw it }
            return 125L
        } finally { active-- }
    }
    suspend fun groupDelay(name: String): Map<String, Long> {
        groupCalls++
        throw IOException("Unsafe group endpoint used: $name")
    }
}
private class Vm(val viewModelScope: CoroutineScope) {
    var state = State(true)
    val repo = Repository()
    val delays = mutableMapOf<String, Long>()
    val measuredAt = mutableMapOf<String, Long>()
    val testingNodes = mutableMapOf<String, Boolean>()
    val testingGroups = mutableMapOf<String, Boolean>()
    val notices = mutableListOf<String>()
    private fun toast(text: String) { notices += text }
    private fun errorText(error: Exception, fallback: String) = error.message ?: fallback
// INSERT_EXACT_PRODUCTION_METHODS
}
private var checks = 0
private fun verify(ok: Boolean, reason: String) { check(ok) { reason }; checks++ }
private fun group(vararg names: String, name: String = "automatic") = ProxyGroupUi(name, names.map(::ProxyNodeUi))
private suspend fun until(reason: String, condition: () -> Boolean) {
    withTimeout(3_000) { while (!condition()) delay(1) }
    verify(condition(), reason)
}

fun main() {
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
        runBlocking(dispatcher) {
            suspend fun scenario(block: suspend (Vm) -> Unit) {
                val vm = Vm(CoroutineScope(SupervisorJob() + dispatcher))
                try { block(vm) } finally { vm.viewModelScope.cancel(); yield() }
            }
            scenario { vm ->
                val first = listOf("a", "b", "c", "d")
                first.forEach { vm.repo.gates[it] = CompletableDeferred() }
                val batch = group("a", "b", "a", "DIRECT", "REJECT", "c", "d", "e")
                vm.testGroup(batch)
                yield()
                verify(vm.repo.groupCalls == 0, "group measurement must not invoke the selection-resetting endpoint")
                until("first wave began") { vm.repo.calls.size == 4 }
                verify(vm.repo.calls.toSet() == first.toSet(), "four distinct real nodes in first wave")
                vm.testGroup(batch)
                vm.testNode("a")
                vm.testGroup(group("a", name = "other-group"))
                yield()
                verify(vm.repo.calls.size == 4, "repeated and overlapping requests cannot duplicate active probes")
                verify(vm.testingNodes["a"] == true, "other group cannot remove an existing node's busy ownership")
                first.forEach { vm.repo.gates.getValue(it).complete(Unit) }
                until("all nodes finished") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
                verify(vm.repo.calls.size == 5 && vm.repo.calls.toSet() == setOf("a", "b", "c", "d", "e"), "all eligible nodes measured exactly once")
                verify(vm.repo.peak == 4, "group concurrency stays at four")
                verify(vm.repo.groupCalls == 0, "no selection-resetting group endpoint")
                verify(vm.delays.values.all { it == 125L }, "real individual measurements stored")
            }
            scenario { vm ->
                listOf("transport", "timeout", "failed", "ok").forEach { vm.delays[it] = 88L; vm.measuredAt[it] = 42L }
                vm.repo.failures["transport"] = IOException("controller transport failed")
                vm.repo.failures["timeout"] = MihomoControllerClient.DelayFailure(true)
                vm.repo.failures["failed"] = MihomoControllerClient.DelayFailure(false)
                vm.testGroup(group("transport", "timeout", "failed", "ok"))
                until("mixed outcomes finished") { vm.testingGroups.isEmpty() }
                verify(vm.delays["transport"] == 88L && vm.measuredAt["transport"] == 42L, "transport errors preserve value and original timestamp")
                verify(vm.delays["timeout"] == -1L && vm.measuredAt["timeout"] != 42L, "only confirmed timeout becomes timeout")
                verify(vm.delays["failed"] == -2L && vm.measuredAt["failed"] != 42L, "confirmed non-timeout probe failure remains distinct")
                verify(vm.delays["ok"] == 125L, "one node failure does not discard successful peers")
                verify(vm.testingNodes.isEmpty() && vm.repo.groupCalls == 0, "all busy flags released without group IO")
            }
            scenario { vm ->
                vm.state = State(false)
                vm.testGroup(group("a")); vm.testNode("a"); yield()
                verify(vm.repo.calls.isEmpty() && vm.repo.groupCalls == 0, "stopped core cannot issue probes")
                verify(vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty(), "stopped calls do not leave busy flags")
            }
            scenario { vm ->
                listOf("a", "b", "c", "d").forEach { vm.repo.gates[it] = CompletableDeferred() }
                vm.testGroup(group("a", "b", "c", "d", "e", "f"))
                until("wave before stop began") { vm.repo.calls.size == 4 }
                vm.state = State(false)
                vm.repo.gates.values.forEach { it.complete(Unit) }
                until("stopped wave cleaned up") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
                verify(vm.repo.calls.size == 4, "stopping prevents the next batch from launching")
            }
            scenario { vm ->
                vm.repo.gates["a"] = CompletableDeferred()
                vm.delays["a"] = 88L; vm.measuredAt["a"] = 42L
                vm.testGroup(group("a"))
                until("cancellable probe began") { vm.repo.calls.size == 1 }
                vm.viewModelScope.cancel()
                until("cancelled probe released busy flags") { vm.testingGroups.isEmpty() && vm.testingNodes.isEmpty() }
                verify(vm.delays["a"] == 88L && vm.measuredAt["a"] == 42L, "cancelled probe preserves prior evidence")
                verify(vm.repo.active == 0, "cancelled work exits the probe")
            }
        }
    }
    println("GroupMeasurementHostTest passed: $checks")
}
