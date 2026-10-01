package io.github.xgl34222220.hetu

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Actual Root/VM/repository selection contract; every Root and client boundary is isolated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi", application = Application::class,
    shadows = [ConceptRootBridgeShadow::class, ConceptMihomoClientShadow::class,
        ConceptRuntimeInspectorShadow::class, NodeSelectionStopControllerShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodeSelectionRootContractTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var vm: HetuViewModel

    @Before fun prepare() {
        app.getSharedPreferences("hetu", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("enableBlur", false).putBoolean("liquidGlass", false)
            .putBoolean("proxySelectorShowHidden", true).commit()
        NodeSelectionStopIo.nextGate.set(null)
        vm = newConceptTestVm(app)
        setConceptState(vm, conceptRunningState())
        enableConceptActionMode(vm)
    }

    @After fun close() {
        if (::vm.isInitialized) rule.runOnIdle { closeConceptTestVm(vm) }
        NodeSelectionStopIo.nextGate.set(null)
    }

    private fun render(tab: HxTab = HxTab.Panel) {
        vm.openPanel("proxies")
        vm.tab = tab
        rule.setContent {
            HetuAppTheme("light", dynamic = false) {
                CompositionLocalProvider(LocalHxMotionEnabled provides false) { HetuRoot(vm) }
            }
        }
        rule.waitForIdle()
    }

    private fun tag(value: String) = rule.onNodeWithTag(value, useUnmergedTree = true)
    private fun node(group: ProxyGroupUi, name: String) = tag("strategy-node-${group.name}-$name")
    private fun expand(group: ProxyGroupUi) = tag("strategy-group-${group.name}").performScrollTo().performClick()
    private fun finished() = rule.waitUntil(10_000) {
        shadowOf(Looper.getMainLooper()).idle()
        vm.pendingSelection.isEmpty()
    }
    private fun assertObserved(group: ProxyGroupUi, actual: String, requested: String) {
        assertEquals(actual, vm.state.groups.first { it.name == group.name }.now)
        rule.onNode(hasText(actual) and hasAnyAncestor(hasTestTag("strategy-group-${group.name}")),
            useUnmergedTree = true).assertExists()
        node(group, actual).assertIsSelected()
        node(group, requested).assertIsNotSelected()
    }
    private fun assertError(text: String) =
        rule.onNode(hasText(text, substring = true), useUnmergedTree = true).assertIsDisplayed()

    @Test fun stoppedRootAndVmCannotSendSelectionRequests() {
        val group = vm.state.groups.first()
        setConceptState(vm, vm.state.copy(running = false))
        render()
        rule.onNodeWithText("代理未运行").assertIsDisplayed()
        tag("strategy-group-${group.name}").assertDoesNotExist()
        rule.runOnIdle { vm.select(group.name, group.nodes[1].name) }
        rule.waitForIdle()
        assertEquals(0, conceptSelectionRequestCount())
        assertTrue(vm.pendingSelection.isEmpty())
        assertFalse(ConceptTestIo.calls.any { it == "GET /proxies" })
    }

    @Test fun realStopTransitionRejectsVisibleNodeClicksAndDirectVmRequests() {
        val group = vm.state.groups.first()
        val stop = ConceptSelectionGate(20_000L)
        ConceptTestIo.selectionGates += stop
        NodeSelectionStopIo.nextGate.set(stop)
        render(HxTab.Home)
        rule.onNodeWithText("停止").performClick()
        assertTrue("the actual VM stop must reach its isolated controller", stop.awaitStarted())
        assertEquals(HxRunOp.Stop, vm.operation)
        assertTrue("exercise the transition before running becomes false", vm.state.running)
        tag("dock-tab-1").performClick()
        expand(group)
        node(group, group.nodes[1].name).performClick()
        rule.runOnIdle { vm.select(group.name, group.nodes[2].name) }
        assertEquals(0, conceptSelectionRequestCount())
        assertTrue(vm.pendingSelection.isEmpty())
        stop.succeed()
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.operation == null && !vm.state.running
        }
        rule.onNodeWithText("代理未运行").assertIsDisplayed()
        rule.runOnIdle { vm.select(group.name, group.nodes[1].name) }
        assertEquals(0, conceptSelectionRequestCount())
        assertEquals(1, ConceptTestIo.calls.count { it == "controller:stop" })
    }

    @Test fun putCompletingAfterStopStartsDoesNotIssueConfirmationOrChangeTheCheck() =
        selectionCompletesDuringStop(holdingReadback = false)

    @Test fun readbackCompletingAfterStopStartsDoesNotWriteIntoTheCurrentGroup() =
        selectionCompletesDuringStop(holdingReadback = true)

    private fun selectionCompletesDuringStop(holdingReadback: Boolean) {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        val selection = if (holdingReadback) blockNextConceptProxyRead(20_000L)
            else blockNextConceptSelection(20_000L)
        node(group, requested).performClick()
        assertTrue(selection.awaitStarted())
        rule.onNodeWithContentDescription("收起策略 ${group.name}").performClick()
        val stop = ConceptSelectionGate(20_000L)
        ConceptTestIo.selectionGates += stop
        NodeSelectionStopIo.nextGate.set(stop)
        tag("dock-tab-0").performClick()
        rule.onNodeWithText("停止").performClick()
        assertTrue(stop.awaitStarted())
        assertEquals(HxRunOp.Stop, vm.operation)
        selection.succeed()
        finished()
        assertEquals("the already-issued PUT is not claimed to be undone", requested,
            ConceptTestIo.state.groups.first { it.name == group.name }.now)
        assertEquals(1, conceptSelectionRequestCount())
        assertEquals(if (holdingReadback) 1 else 0, ConceptTestIo.calls.count { it == "GET /proxies" })
        tag("dock-tab-1").performClick()
        expand(group)
        assertObserved(group, group.now, requested)
        assertEquals(HxRunOp.Stop, vm.operation)
        stop.succeed()
        rule.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.operation == null && !vm.state.running
        }
        assertEquals(1, conceptSelectionRequestCount())
        assertEquals(if (holdingReadback) 1 else 0, ConceptTestIo.calls.count { it == "GET /proxies" })
    }

    @Test fun vmRejectsUnknownGroupsNonmembersNonselectableGroupsAndCurrentNode() {
        val group = vm.state.groups.first()
        val balanced = group.copy(name = "负载均衡", type = "LoadBalance")
        setConceptState(vm, vm.state.copy(groups = vm.state.groups + balanced))
        render()
        expand(balanced)
        node(balanced, balanced.nodes[1].name).performScrollTo().performClick()
        rule.runOnIdle {
            vm.select("已删除的策略组", group.nodes[1].name)
            vm.select(group.name, "已移除的节点")
            vm.select(balanced.name, balanced.nodes[1].name)
            vm.select(group.name, group.now)
        }
        rule.waitForIdle()
        assertEquals(0, conceptSelectionRequestCount())
        assertTrue(vm.pendingSelection.isEmpty())
        assertFalse(ConceptTestIo.calls.any { it == "GET /proxies" })
    }

    @Test fun mismatchedReadbackKeepsObservedCheckShowsErrorAndAllowsConfirmedRetry() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        ConceptTestIo.nextSelectionResultNode.set(group.now)
        node(group, requested).performClick()
        finished()
        assertObserved(group, group.now, requested)
        assertError("核心尚未确认所选节点，当前为 ${group.now}")
        assertEquals(1, conceptSelectionRequestCount())
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /proxies" })
        node(group, requested).performClick()
        finished()
        node(group, requested).assertIsSelected()
        node(group, group.now).assertIsNotSelected()
        assertEquals(requested, vm.state.groups.first { it.name == group.name }.now)
        assertEquals(2, conceptSelectionRequestCount())
        assertEquals(2, ConceptTestIo.calls.count { it == "GET /proxies" })
    }

    @Test fun differentObservedNodeWinsOverBothTheRequestedAndCachedNodes() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        val actual = group.nodes[2].name
        expand(group)
        ConceptTestIo.nextSelectionResultNode.set(actual)
        node(group, requested).performClick()
        finished()
        assertObserved(group, actual, requested)
        node(group, group.now).assertIsNotSelected()
        assertError("核心尚未确认所选节点，当前为 $actual")
        assertEquals(1, conceptSelectionRequestCount())
    }

    @Test fun failedReadbackDoesNotTurnPutSuccessIntoACheckAndRetryCanConfirmIt() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        ConceptTestIo.nextProxyReadFailure.set(IOException("测试回读断线"))
        node(group, requested).performClick()
        finished()
        assertEquals("the PUT reached the core but its result is not known by the VM", requested,
            ConceptTestIo.state.groups.first { it.name == group.name }.now)
        assertObserved(group, group.now, requested)
        assertError("切换结果尚未确认：测试回读断线")
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /proxies" })
        node(group, requested).performClick()
        finished()
        node(group, requested).assertIsSelected()
        assertEquals(2, conceptSelectionRequestCount())
        assertEquals(2, ConceptTestIo.calls.count { it == "GET /proxies" })
    }

    @Test fun missingGroupOrCurrentNodeIsUnconfirmedAndPreservesTheObservedCheck() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        val withoutNow = JSONObject().put(group.name, JSONObject().put("type", "Selector")).toString()
        for (snapshot in listOf("{}", withoutNow)) {
            ConceptTestIo.nextProxyReadJson.set(snapshot)
            node(group, requested).performClick()
            finished()
            assertObserved(group, group.now, requested)
            assertError("切换结果尚未确认：无法读取策略组「${group.name}」的当前节点")
        }
        assertEquals(2, conceptSelectionRequestCount())
        assertEquals(2, ConceptTestIo.calls.count { it == "GET /proxies" })
    }

    @Test fun pendingReadbackSurvivesCollapsePanelAndDockNavigationWithoutAnotherPut() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        val readback = blockNextConceptProxyRead(20_000L)
        node(group, requested).performClick()
        assertTrue("PUT must finish before the controlled GET is held", readback.awaitStarted())
        assertEquals(requested, vm.pendingSelection[group.name])
        assertObserved(group, group.now, requested)
        rule.onNodeWithContentDescription("收起策略 ${group.name}").performClick()
        tag("strategy-expanded-${group.name}").assertDoesNotExist()
        tag("panel-tab-overview").performClick()
        tag("dock-tab-0").performClick()
        tag("dock-tab-1").performClick()
        tag("panel-tab-proxies").performClick()
        expand(group)
        assertObserved(group, group.now, requested)
        node(group, requested).performTouchInput { click() }
        rule.runOnIdle { vm.select(group.name, group.nodes[2].name) }
        assertEquals(requested, vm.pendingSelection[group.name])
        assertEquals(1, conceptSelectionRequestCount())
        assertEquals(1, ConceptTestIo.calls.count { it == "GET /proxies" })
        readback.succeed()
        finished()
        node(group, requested).assertIsSelected()
        node(group, group.now).assertIsNotSelected()
        assertEquals(1, conceptSelectionRequestCount())
    }

    @Test fun cancelledReadbackReleasesPendingWithoutAnOptimisticCheck() {
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        val readback = blockNextConceptProxyRead()
        node(group, requested).performClick()
        assertTrue(readback.awaitStarted())
        readback.cancel()
        finished()
        assertObserved(group, group.now, requested)
        node(group, requested).performClick()
        finished()
        node(group, requested).assertIsSelected()
        assertEquals(2, conceptSelectionRequestCount())
    }

    @Test fun strictConfirmationPreservesExistingPutAndConnectionCleanupOrder() {
        vm.prefs.edit().putBoolean("proxySelectorDisconnectOnSelect", true).commit()
        render()
        val group = vm.state.groups.first()
        val requested = group.nodes[1].name
        expand(group)
        node(group, requested).performClick()
        finished()
        node(group, requested).assertIsSelected()
        assertEquals(listOf("GET /proxies", "GET /connections", "PUT /proxies/${group.name}:$requested",
            "DELETE /connections/concept-browser", "DELETE /connections/concept-udp", "GET /proxies"),
            ConceptTestIo.calls.filter { it.startsWith("GET /proxies") || it == "GET /connections" ||
                it.startsWith("PUT /proxies/") || it.startsWith("DELETE /connections/") })
        assertEquals(2, vm.prefs.getInt("proxyLastSelectionClosed", -1))
        assertEquals(0, vm.prefs.getInt("proxyLastSelectionCloseFailed", -1))
    }
}

internal object NodeSelectionStopIo {
    val nextGate = AtomicReference<ConceptSelectionGate?>(null)
}

/** Override only the stop effect; controller state and repository/client code remain real. */
@Implements(value = ProxyComposeController::class, isInAndroidSdk = false, callThroughByDefault = true)
class NodeSelectionStopControllerShadow {
    @Implementation fun stop(progress: (String) -> Unit, continuation: Continuation<Any?>): Any? =
        (suspend {
            withContext(Dispatchers.IO) {
                val gate = checkNotNull(NodeSelectionStopIo.nextGate.getAndSet(null)) { "Unplanned controller stop" }
                ConceptTestIo.calls += "controller:stop"
                try { gate.awaitOutcome() } finally { ConceptTestIo.selectionGates.remove(gate) }
                ConceptTestIo.state = ConceptTestIo.state.copy(running = false)
                JSONObject().put("ok", true)
            }
        }).startCoroutineUninterceptedOrReturn(continuation)
}
