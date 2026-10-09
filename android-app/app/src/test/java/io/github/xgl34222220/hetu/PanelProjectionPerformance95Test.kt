package io.github.xgl34222220.hetu

import androidx.compose.runtime.mutableStateMapOf
import io.github.xgl34222220.hetu.panel.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.measureNanoTime

/** Presentation cache correctness and comparative host timing, never a claim about device frame rate. */
class PanelProjectionPerformance95Test {
    private class CountingList<T>(private val source: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = source.size
        override fun get(index: Int): T { reads++; return source[index] }
    }

    private data class Fixture(
        val state: ProxyComposeState,
        val providers: List<DashboardProviderUi>,
        val rules: List<ProxyRuleUi>,
        val ruleSets: List<DashboardRuleSetUi>,
        val logs: List<RefLogEntry>,
    )

    private fun fixture(groupCount: Int = 2, nodesPerGroup: Int = 3, connectionCount: Int = 4, ruleCount: Int = 5): Fixture {
        val groups = List(groupCount) { group -> ProxyGroupUi("group-$group", "Selector", "node-$group-0",
            List(nodesPerGroup) { node -> ProxyNodeUi("node-$group-$node", "VLESS", true,
                if (node % 2 == 0) 42L else -2L, "provider") }) }
        val connections = List(connectionCount) { index -> ProxyConnectionUi(
            id = "connection-$index", host = "site-$index.test:443", rule = "Domain", rulePayload = "site-$index.test",
            chain = "node-0-0 → group-0", upload = index * 20L, download = index * 100L,
            network = "tcp · TProxy", inbound = "TProxy", appName = "app-${index % 8}", packageName = "test.app${index % 8}",
            startedAt = "2026-10-09T08:30:12.123Z",
        ) }
        return Fixture(
            ProxyComposeState(running = true, panelReady = true, groups = groups, connections = connections,
                uploadTotal = 1000L, downloadTotal = 5000L),
            listOf(DashboardProviderUi("provider", "HTTP", "", "200-399", "2026-10-09T08:30:12Z",
                100L, 200L, 1000L, 1_900_000_000L, groups.flatMap { it.nodes }.map { it.name }.toSet(), true)),
            List(ruleCount) { ProxyRuleUi(it, if (it % 3 == 0) "Match" else "Domain", if (it % 3 == 0) "" else "example-$it.test", "group-0") },
            listOf(DashboardRuleSetUi("rules", "Domain", "yaml", "HTTP", 90, "2026-10-09T08:30:12Z")),
            List(20) { RefLogEntry(it, RefLogLevel.entries[it % RefLogLevel.entries.size], "08:30:12", "log-$it") },
        )
    }

    private fun project(projector: PanelDataProjector, fixture: Fixture, traffic: PanelTrafficSnapshot = PanelTrafficSnapshot(),
        delays: Map<String, Long> = emptyMap(), testing: Map<String, Boolean> = emptyMap(),
        selected: Map<String, String> = emptyMap(), subscriptions: Map<String, PanelUpdate> = emptyMap(),
        ruleSets: Map<String, PanelUpdate> = emptyMap(), testingGroups: Set<String> = emptySet(),
        testingAll: Boolean = false, switching: Map<String, String> = emptyMap()): PanelData = projector.project(
            fixture.state, false, fixture.providers, fixture.rules, fixture.ruleSets, fixture.logs,
            delays, testing, selected, subscriptions, ruleSets, traffic, testingGroups, testingAll, switching)

    private fun baseline(fixture: Fixture, traffic: PanelTrafficSnapshot = PanelTrafficSnapshot(),
        delays: Map<String, Long> = emptyMap(), testing: Map<String, Boolean> = emptyMap(),
        selected: Map<String, String> = emptyMap(), subscriptions: Map<String, PanelUpdate> = emptyMap(),
        ruleSets: Map<String, PanelUpdate> = emptyMap(), testingGroups: Set<String> = emptySet(),
        testingAll: Boolean = false, switching: Map<String, String> = emptyMap()): PanelData = baseline7585PanelData(
            fixture.state, false, fixture.providers, fixture.rules, fixture.ruleSets, fixture.logs,
            delays, testing, selected, subscriptions, ruleSets, traffic, testingGroups, testingAll, switching)

    @Test fun trafficOnlyUpdateReusesNodeRuleAndLogSlicesWithoutTraversingThem() {
        val source = fixture()
        val nodes = CountingList(source.state.groups.first().nodes)
        val rules = CountingList(source.rules)
        val logs = CountingList(source.logs)
        val input = source.copy(state = source.state.copy(groups = listOf(source.state.groups.first().copy(nodes = nodes))), rules = rules, logs = logs)
        val projector = PanelDataProjector()
        val first = project(projector, input)
        nodes.reads = 0; rules.reads = 0; logs.reads = 0
        val updated = project(projector, input.copy(state = input.state.copy(uploadTotal = 3000L)),
            PanelTrafficSnapshot(upload = 500, connectionRates = mapOf("connection-0" to (123L to 456L))))
        assertSame(first.groups, updated.groups)
        assertSame(first.delays, updated.delays)
        assertSame(first.rules, updated.rules)
        assertSame(first.logs, updated.logs)
        assertSame(first.subscriptions, updated.subscriptions)
        assertSame(first.ruleSets, updated.ruleSets)
        assertSame(first.overview.subscription, updated.overview.subscription)
        assertEquals(0, nodes.reads)
        assertEquals(0, rules.reads)
        assertEquals(0, logs.reads)
        assertEquals(3000L, updated.overview.uploadTotalBytes)
        assertEquals(500L, updated.overview.uploadBytesPerSecond)
        assertEquals(123L, updated.connections.first().uploadBytesPerSecond)
        assertEquals(456L, updated.connections.first().downloadBytesPerSecond)
        assertSame(first.connections.first().chain, updated.connections.first().chain)
        assertEquals(baseline(input.copy(state = input.state.copy(uploadTotal = 3000L)),
            PanelTrafficSnapshot(upload = 500, connectionRates = mapOf("connection-0" to (123L to 456L)))), updated)
    }

    @Test fun inPlaceSnapshotMapChangesRefreshMeasurementsBusySelectionAndAvailability() {
        val input = fixture()
        val projector = PanelDataProjector()
        val delays = mutableStateMapOf<String, Long>()
        val testing = mutableStateMapOf<String, Boolean>()
        val selected = mutableStateMapOf<String, String>()
        val first = project(projector, input, delays = delays, testing = testing, selected = selected)
        delays["node-0-1"] = 77L
        val measured = project(projector, input, delays = delays, testing = testing, selected = selected)
        assertEquals(PanelDelay.Ms(77L), measured.delays["node-0-1"])
        assertEquals(first.groups.first().availableCount + 1, measured.groups.first().availableCount)
        assertSame(first.groups.first().nodes, measured.groups.first().nodes)
        assertSame(first.rules, measured.rules)
        testing["node-0-1"] = true
        selected["group-0"] = "node-0-1"
        val busy = project(projector, input, delays = delays, testing = testing, selected = selected)
        assertEquals(PanelDelay.Testing, busy.delays["node-0-1"])
        assertEquals("node-0-1", busy.groups.first().now)
        // A previously returned projection owns immutable busy/delay values.
        assertEquals(PanelDelay.Ms(77L), measured.delays["node-0-1"])
        testing.clear(); delays.clear(); selected.clear()
        assertEquals(first, project(projector, input, delays = delays, testing = testing, selected = selected))
    }

    @Test fun providerAndRuleSetTaskMapsInvalidateOnlyTheirOwnSlice() {
        val input = fixture()
        val projector = PanelDataProjector()
        val subscriptions = mutableStateMapOf<String, PanelUpdate>()
        val ruleSets = mutableStateMapOf<String, PanelUpdate>()
        val first = project(projector, input, subscriptions = subscriptions, ruleSets = ruleSets)
        subscriptions["provider"] = PanelUpdate.Updating
        val updating = project(projector, input, subscriptions = subscriptions, ruleSets = ruleSets)
        assertEquals(PanelUpdate.Updating, updating.subscriptions.single().update)
        assertEquals(PanelUpdate.Idle, first.subscriptions.single().update)
        assertSame(first.ruleSets, updating.ruleSets)
        assertSame(first.groups, updating.groups)
        ruleSets["rules"] = PanelUpdate.Failed("retry")
        val failed = project(projector, input, subscriptions = subscriptions, ruleSets = ruleSets)
        assertSame(updating.subscriptions, failed.subscriptions)
        assertEquals(PanelUpdate.Failed("retry"), failed.ruleSets.single().update)
        subscriptions.clear(); ruleSets.clear()
        assertEquals(first, project(projector, input, subscriptions = subscriptions, ruleSets = ruleSets))
    }

    @Test fun sourceReplacementUpdatesNestedNodesConnectionsRulesAndLogs() {
        val input = fixture()
        val projector = PanelDataProjector()
        val old = project(projector, input)
        val fresh = fixture(groupCount = 1, nodesPerGroup = 4, connectionCount = 2, ruleCount = 8).let {
            it.copy(state = it.state.copy(groups = it.state.groups.map { group -> group.copy(type = "URLTest", now = "node-0-3") }),
                logs = listOf(RefLogEntry(91, RefLogLevel.Error, "09:00:00", "fresh error")))
        }
        val updated = project(projector, fresh)
        assertEquals(baseline(fresh), updated)
        assertNotSame(old.groups, updated.groups)
        assertEquals("URLTest", updated.groups.single().type)
        assertEquals(4, updated.groups.single().nodes.size)
        assertEquals(2, updated.connections.size)
        assertEquals(8, updated.rules.size)
        assertEquals("fresh error", updated.logs.single().message)
    }

    @Test fun stopOrControllerFailureCannotExposeCachedListsAndRecoveryRebuilds() {
        val input = fixture()
        val projector = PanelDataProjector()
        val first = project(projector, input)
        for (state in listOf(input.state.copy(running = false), input.state.copy(controllerReadFailed = true, controllerError = "offline"))) {
            val unavailable = project(projector, input.copy(state = state))
            assertEquals(baseline(input.copy(state = state)), unavailable)
            assertTrue(unavailable.groups.isEmpty())
            assertTrue(unavailable.delays.isEmpty())
            assertTrue(unavailable.connections.isEmpty())
            assertTrue(unavailable.rules.isEmpty())
            assertTrue(unavailable.ruleSets.isEmpty())
            assertTrue(unavailable.logs.isEmpty())
            assertTrue(unavailable.subscriptions.isEmpty())
            val recovered = project(projector, input)
            assertEquals(first, recovered)
            assertNotSame(first.groups, recovered.groups)
        }
    }

    @Test fun busyAndPendingSelectionRemainCurrentWithoutRemappingLargeLists() {
        val input = fixture()
        val projector = PanelDataProjector()
        val first = project(projector, input)
        val busy = project(projector, input, testingGroups = setOf("group-0"), testingAll = true,
            switching = mapOf("group-0" to "node-0-1", "group-1" to "node-1-0"))
        assertSame(first.groups, busy.groups)
        assertSame(first.rules, busy.rules)
        assertEquals(setOf("group-0"), busy.testingGroups)
        assertTrue(busy.testingAll)
        assertEquals(mapOf("group-0" to "node-0-1"), busy.switching)
        assertEquals(baseline(input, testingGroups = setOf("group-0"), testingAll = true,
            switching = mapOf("group-0" to "node-0-1", "group-1" to "node-1-0")), busy)
    }

    @Test fun largeProjectionReportsBaselineAndCachedTimesWithExactOutputParity() {
        val input = fixture(groupCount = 100, nodesPerGroup = 300, connectionCount = 2000, ruleCount = 10000)
        val projector = PanelDataProjector()
        fun traffic(index: Int) = PanelTrafficSnapshot(index * 100L, index * 500L,
            input.state.connections.associate { it.id to (index.toLong() to index * 3L) },
            List(60) { (it + index).toFloat() }, List(60) { (it * 3 + index).toFloat() })
        repeat(5) { index ->
            val rates = traffic(index)
            assertEquals(baseline(input, rates), project(projector, input, rates))
        }
        val baselineNs = mutableListOf<Long>()
        val cachedNs = mutableListOf<Long>()
        var prior: PanelData? = null
        repeat(15) { offset ->
            val rates = traffic(offset + 5)
            var before: PanelData? = null
            var after: PanelData? = null
            // Alternate order to reduce simple warmup/GC ordering bias; no timing threshold in CI.
            if (offset % 2 == 0) {
                baselineNs += measureNanoTime { before = baseline(input, rates) }
                cachedNs += measureNanoTime { after = project(projector, input, rates) }
            } else {
                cachedNs += measureNanoTime { after = project(projector, input, rates) }
                baselineNs += measureNanoTime { before = baseline(input, rates) }
            }
            assertEquals(before, after)
            prior?.let { previous ->
                assertSame(previous.groups, after!!.groups)
                assertSame(previous.rules, after!!.rules)
                assertSame(previous.logs, after!!.logs)
                assertSame(previous.delays, after!!.delays)
            }
            prior = after
        }
        fun percentile(values: List<Long>, fraction: Double): Double = values.sorted()[((values.size - 1) * fraction).toInt()] / 1_000_000.0
        val report = """{
  "fixture": {"groups":100,"nodesPerGroup":300,"connections":2000,"rules":10000},
  "samples":15,
  "baselineCommit":"7585c268",
  "baselineMedianMs":${percentile(baselineNs, .5)},
  "cachedMedianMs":${percentile(cachedNs, .5)},
  "baselineP95Ms":${percentile(baselineNs, .95)},
  "cachedP95Ms":${percentile(cachedNs, .95)},
  "exactOutputParity":true,
  "stableSlicesReused":true,
  "scope":"Host JVM presentation projection only; not Android device FPS or network latency"
}"""
        val output = File("build/outputs/ui93/panel-projection-performance95.json")
        output.parentFile.mkdirs()
        output.writeText(report)
        println("PANEL_PROJECTION_PERFORMANCE95 $report")
    }
    @Test fun consecutiveCounterPollsReuseStaticProjectionAndRefreshEveryLiveValue() {
        val input = fixture()
        val projector = PanelDataProjector()
        val first = project(projector, input)
        var prior = first
        repeat(4) { index ->
            val round = index + 1L
            val fresh = input.copy(state = input.state.copy(
                connections = input.state.connections.map { it.copy(upload = it.upload + 100L * round,
                    download = it.download + 900L * round) }, uploadTotal = 1000L + round, downloadTotal = 5000L + round))
            val traffic = PanelTrafficSnapshot(round * 10L, round * 20L,
                fresh.state.connections.associate { it.id to (round * 3L to round * 7L) })
            val updated = project(projector, fresh, traffic)
            assertEquals(baseline(fresh, traffic), updated)
            updated.connections.forEachIndexed { connectionIndex, connection ->
                assertSame(prior.connections[connectionIndex].chain, connection.chain)
                assertEquals(fresh.state.connections[connectionIndex].upload, connection.uploadTotalBytes)
                assertEquals(fresh.state.connections[connectionIndex].download, connection.downloadTotalBytes)
                assertEquals(round * 3L, connection.uploadBytesPerSecond)
                assertEquals(round * 7L, connection.downloadBytesPerSecond)
            }
            assertSame(first.groups, updated.groups)
            assertSame(first.rules, updated.rules)
            prior = updated
        }
        val reset = input.copy(state = input.state.copy(connections = input.state.connections.map { it.copy(upload = 0L, download = 0L) }))
        val zeroed = project(projector, reset)
        assertEquals(baseline(reset), zeroed)
        zeroed.connections.forEachIndexed { index, connection ->
            assertSame(first.connections[index].chain, connection.chain)
            assertEquals(0L, connection.uploadTotalBytes)
            assertEquals(0L, connection.downloadTotalBytes)
            assertEquals(0L, connection.uploadBytesPerSecond)
            assertEquals(0L, connection.downloadBytesPerSecond)
        }
        // Returned objects are immutable even after later polls reset the same IDs.
        assertEquals(100L, first.connections[1].downloadTotalBytes)
        assertEquals(420L, prior.connections[1].uploadTotalBytes)
        assertEquals(3700L, prior.connections[1].downloadTotalBytes)
    }

    @Test fun eachChangedStaticFieldForTheSameConnectionIdRebuildsItsProjection() {
        val input = fixture(connectionCount = 1)
        val source = input.state.connections.single()
        val changes: List<(ProxyConnectionUi) -> ProxyConnectionUi> = listOf(
            { it.copy(host = "192.0.2.2:80") },
            { it.copy(startedAt = "2026-10-09T09:45:30.001Z") },
            { it.copy(network = "udp · Tun") },
            { it.copy(inbound = "Tun") },
            { it.copy(appName = "updated application") },
            { it.copy(packageName = "updated.package") },
            { it.copy(chain = "DIRECT → changed-group") },
            { it.copy(rule = "Match") },
            { it.copy(rulePayload = "changed.test") },
        )
        changes.forEach { change ->
            val projector = PanelDataProjector()
            val before = project(projector, input)
            val fresh = input.copy(state = input.state.copy(connections = listOf(change(source))))
            val after = project(projector, fresh)
            assertEquals(baseline(fresh), after)
            assertEquals(source.id, after.connections.single().id)
            assertNotSame(before.connections.single().chain, after.connections.single().chain)
            assertSame(before.groups, after.groups)
        }
    }

    @Test fun closedConnectionMetadataIsEvictedWhileSurvivingIdsKeepTheirProjection() {
        val input = fixture(connectionCount = 2)
        val projector = PanelDataProjector()
        val first = project(projector, input)
        val survivor = input.copy(state = input.state.copy(connections = listOf(input.state.connections[1])))
        val trimmed = project(projector, survivor)
        assertEquals(baseline(survivor), trimmed)
        assertSame(first.connections[1].chain, trimmed.connections.single().chain)
        val returned = project(projector, input)
        assertEquals(first, returned)
        assertNotSame(first.connections[0].chain, returned.connections[0].chain)
        assertSame(first.connections[1].chain, returned.connections[1].chain)
        val empty = input.copy(state = input.state.copy(connections = emptyList()))
        assertTrue(project(projector, empty).connections.isEmpty())
        val reopened = project(projector, input)
        reopened.connections.forEachIndexed { index, connection -> assertNotSame(returned.connections[index].chain, connection.chain) }
        assertEquals(first, reopened)
    }

    @Test fun stoppedAndFailedSnapshotsClearPerConnectionMetadataBeforeRecovery() {
        val input = fixture()
        for (state in listOf(input.state.copy(running = false), input.state.copy(controllerReadFailed = true))) {
            val projector = PanelDataProjector()
            val before = project(projector, input)
            assertTrue(project(projector, input.copy(state = state)).connections.isEmpty())
            val after = project(projector, input)
            assertEquals(before, after)
            before.connections.forEachIndexed { index, connection -> assertNotSame(connection.chain, after.connections[index].chain) }
        }
    }

    @Test fun changingCounterPollsReportBaselineAndCachedTimesWithStaticReferenceReuse() {
        val input = fixture(groupCount = 100, nodesPerGroup = 300, connectionCount = 2000, ruleCount = 10000)
        val projector = PanelDataProjector()
        fun poll(index: Int) = input.copy(state = input.state.copy(
            connections = input.state.connections.map { it.copy(upload = it.upload + index * 100L, download = it.download + index * 800L) },
            uploadTotal = input.state.uploadTotal + index * 200_000L, downloadTotal = input.state.downloadTotal + index * 1_600_000L))
        fun traffic(index: Int) = PanelTrafficSnapshot(index * 100L, index * 500L,
            input.state.connections.associate { it.id to (index * 2L to index * 7L) },
            List(60) { (it + index).toFloat() }, List(60) { (it * 3 + index).toFloat() })
        repeat(5) { index ->
            val fresh = poll(index)
            val rates = traffic(index)
            assertEquals(baseline(fresh, rates), project(projector, fresh, rates))
        }
        val baselineNs = mutableListOf<Long>()
        val cachedNs = mutableListOf<Long>()
        var prior: PanelData? = null
        repeat(15) { offset ->
            val fresh = poll(offset + 5)
            val rates = traffic(offset + 5)
            var before: PanelData? = null
            var after: PanelData? = null
            if (offset % 2 == 0) {
                baselineNs += measureNanoTime { before = baseline(fresh, rates) }
                cachedNs += measureNanoTime { after = project(projector, fresh, rates) }
            } else {
                cachedNs += measureNanoTime { after = project(projector, fresh, rates) }
                baselineNs += measureNanoTime { before = baseline(fresh, rates) }
            }
            assertEquals(before, after)
            prior?.let { previous ->
                assertSame(previous.groups, after!!.groups)
                assertSame(previous.rules, after!!.rules)
                after!!.connections.forEachIndexed { index, connection ->
                    assertSame(previous.connections[index].chain, connection.chain)
                    assertEquals(fresh.state.connections[index].upload, connection.uploadTotalBytes)
                    assertEquals(fresh.state.connections[index].download, connection.downloadTotalBytes)
                }
            }
            prior = after
        }
        fun percentile(values: List<Long>, fraction: Double): Double = values.sorted()[((values.size - 1) * fraction).toInt()] / 1_000_000.0
        val report = """{
  "fixture": {"groups":100,"nodesPerGroup":300,"connections":2000,"rules":10000},
  "samples":15,
  "scenario":"Every poll replaces connection objects and changes all upload/download counters and rates",
  "baselineCommit":"7585c268",
  "baselineMedianMs":${percentile(baselineNs, .5)},
  "cachedMedianMs":${percentile(cachedNs, .5)},
  "baselineP95Ms":${percentile(baselineNs, .95)},
  "cachedP95Ms":${percentile(cachedNs, .95)},
  "exactOutputParity":true,
  "staticConnectionChainsReused":true,
  "liveCountersRefreshed":true,
  "scope":"Host JVM presentation projection only; not Android device FPS or network latency"
}"""
        val output = File("build/outputs/ui93/panel-projection-poll-performance95.json")
        output.parentFile.mkdirs()
        output.writeText(report)
        println("PANEL_PROJECTION_POLL_PERFORMANCE95 $report")
    }

}

// Frozen 7585c268 projection, copied for output parity and before/after timing only.
// No original acceptance test or production timeout/safety assertion is changed.
private fun groupType(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
    "selector", "select" -> "Selector"
    "urltest", "url-test" -> "URLTest"
    "fallback" -> "Fallback"
    "loadbalance", "load-balance" -> "LoadBalance"
    else -> raw.ifBlank { "Group" }
}

private fun baseline7585PanelData(
    state: ProxyComposeState,
    starting: Boolean,
    providers: List<DashboardProviderUi>,
    rules: List<ProxyRuleUi>,
    ruleSets: List<DashboardRuleSetUi>,
    logs: List<RefLogEntry>,
    delays: Map<String, Long>,
    testing: Map<String, Boolean>,
    selectedLocal: Map<String, String>,
    subscriptionUpdates: Map<String, PanelUpdate>,
    ruleSetUpdates: Map<String, PanelUpdate>,
    sampler: PanelTrafficSnapshot,
    testingGroups: Set<String> = emptySet(),
    testingAll: Boolean = false,
    switching: Map<String, String> = emptyMap(),
): PanelData {
    if (!state.running) return PanelData(status = if (starting) PanelStatus.Starting else PanelStatus.NotRunning)
    if (state.controllerReadFailed) return PanelData(status = PanelStatus.Running,
        readError = state.controllerError.ifBlank { "控制接口读取失败，请重试或检查 API 设置" })
    val groupNames = state.groups.mapTo(HashSet()) { it.name }
    val groups = state.groups.map { g ->
        PanelGroup(
            name = g.name,
            type = groupType(g.type),
            nodes = g.nodes.map { n ->
                val kind = when {
                    n.name in groupNames -> PanelNodeKind.Group
                    n.name.equals("DIRECT", true) || n.name.equals("REJECT", true) -> PanelNodeKind.Direct
                    else -> PanelNodeKind.Proxy
                }
                PanelNode(n.name, if (kind == PanelNodeKind.Group) "策略组" else n.type, n.udp, n.provider, kind)
            },
            now = selectedLocal[g.name] ?: g.now,
            hidden = g.hidden,
            availableCount = g.nodes.count { (delays[it.name] ?: it.lastDelay ?: -1L) > 0L },
        )
    }
    val nodeDelays = HashMap<String, PanelDelay>()
    state.groups.forEach { g -> g.nodes.forEach { n -> if (n.name !in groupNames && n.lastDelay != null) nodeDelays[n.name] = panelDelayOf(n.lastDelay) } }
    delays.forEach { (name, ms) -> nodeDelays[name] = panelDelayOf(ms) }
    testing.keys.forEach { nodeDelays[it] = PanelDelay.Testing }

    val rates = sampler.connectionRates
    val connections = state.connections.map { c ->
        val rate = rates[c.id]
        val host = c.host
        val bare = host.substringBeforeLast(':')
        PanelConnection(
            id = c.id,
            host = host,
            time = c.startedAt.takeIf { it.isNotBlank() },
            timeLabel = formatStarted(c.startedAt),
            network = c.network.substringBefore(" · ").uppercase(Locale.ROOT),
            inbound = c.inbound,
            kind = when {
                bare.count { it == ':' } >= 2 -> "IPv6"
                bare.all { it.isDigit() || it == '.' } -> "IPv4"
                else -> "FQDN"
            },
            app = c.appName,
            packageName = c.packageName,
            // Mihomo lists the chain leaf-first; the panel shows group → node.
            chain = c.chain.split(" → ").filter { it.isNotBlank() }.asReversed(),
            rule = listOf(c.rule, c.rulePayload).filter { it.isNotBlank() }.joinToString(" · "),
            uploadBytesPerSecond = rate?.first ?: 0L,
            downloadBytesPerSecond = rate?.second ?: 0L,
            uploadTotalBytes = c.upload,
            downloadTotalBytes = c.download,
        )
    }
    val tracked = providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val ranks = connections.groupBy { it.app.ifBlank { "其他应用" } }.map { (app, items) ->
        PanelRank(
            app = app, packageName = items.first().packageName,
            downloadBytesPerSecond = items.sumOf { it.downloadBytesPerSecond }, uploadBytesPerSecond = items.sumOf { it.uploadBytesPerSecond },
            connections = items.size, totalBytes = items.sumOf { it.uploadTotalBytes + it.downloadTotalBytes },
        )
    }
    return PanelData(
        status = PanelStatus.Running,
        globalMode = state.trafficMode.equals("global", ignoreCase = true),
        groups = groups,
        delays = nodeDelays,
        // The core is up but the controller has not answered once yet: placeholders, not empty states.
        loading = !state.panelReady && state.groups.isEmpty() && state.connections.isEmpty(),
        testingGroups = testingGroups,
        testingAll = testingAll,
        // Only a pick the core has not confirmed yet counts as switching.
        switching = switching.filter { (group, node) -> state.groups.firstOrNull { it.name == group }?.now != node },
        overview = PanelOverview(
            strategyCount = groups.count { !it.hidden && !it.isGlobal },
            ruleCount = rules.size,
            connectionCount = connections.size,
            subscription = if (tracked.isEmpty()) null else PanelOverviewSubscription(
                usedBytes = tracked.sumOf { it.used }, totalBytes = tracked.sumOf { it.total },
                expire = tracked.map { it.expire }.filter { it > 0L }.minOrNull()?.let(::formatExpire),
                subscriptionCount = providers.size, nodeCount = providers.sumOf { it.nodes.size },
            ),
            uploadBytesPerSecond = sampler.upload, downloadBytesPerSecond = sampler.download,
            uploadTotalBytes = state.uploadTotal, downloadTotalBytes = state.downloadTotal,
            uploadTrend = sampler.uploadTrend.toList(), downloadTrend = sampler.downloadTrend.toList(),
            ranks = ranks,
        ),
        subscriptions = providers.map { p ->
            PanelSubscription(
                name = p.name, expire = p.expire.takeIf { it > 0L }?.let(::formatExpire), updatedAt = formatUpdated(p.updatedAt),
                uploadBytes = p.upload, downloadBytes = p.download, totalBytes = if (p.hasSubscriptionInfo) p.total else 0L,
                update = subscriptionUpdates[p.name] ?: PanelUpdate.Idle,
            )
        },
        connections = connections,
        rules = rules.map { PanelRule(it.type, it.payload.ifBlank { if (it.type.equals("Match", true)) "所有其他流量" else "" }, it.proxy) },
        ruleSets = ruleSets.map {
            PanelRuleSet(it.name, it.ruleCount, it.behavior, it.format, it.vehicleType, formatUpdated(it.updatedAt), ruleSetUpdates[it.name] ?: PanelUpdate.Idle)
        },
        // refParseLogs19 returns oldest first; the panel model is newest first.
        logs = logs.asReversed().map {
            PanelLogEntry(
                it.index,
                when (it.level) {
                    RefLogLevel.Debug -> PanelLogLevel.Debug
                    RefLogLevel.Info -> PanelLogLevel.Info
                    RefLogLevel.Warn -> PanelLogLevel.Warn
                    RefLogLevel.Error -> PanelLogLevel.Error
                },
                it.time, it.message,
            )
        },
    )
}

/** “2026-10-02T08:00:00.12Z” → the local clock time “16:00:00”; anything unparseable keeps its time part. */
private fun formatStarted(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return try {
        java.time.OffsetDateTime.parse(value).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime()
            .withNano(0).format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
    } catch (_: Exception) {
        if (value.length >= 19 && value[10] == 'T') value.substring(11, 19) else value
    }
}

private fun formatExpire(expire: Long): String {
    val millis = if (expire < 10_000_000_000L) expire * 1000L else expire
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
}

/** “2026-10-01T20:30:11Z” → “10-01 20:30”; anything else is passed through; blank → null. */
private fun formatUpdated(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    return if (value.length >= 16 && value[4] == '-' && value[7] == '-') value.substring(5, 16).replace('T', ' ') else value.replace('T', ' ')
}
