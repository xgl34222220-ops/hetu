package io.github.xgl34222220.hetu

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.xgl34222220.hetu.home.HetuHomeV2
import io.github.xgl34222220.hetu.home.HomeWanExtras

/** New presentation, using the same lifecycle and guarded actions as V20.81. */
@Composable
internal fun NewUiHome(vm: HetuViewModel, bottom: Dp, onDetail: (Boolean) -> Unit) {
    val nav = LocalNav.current
    val tracked = vm.providers.filter { it.hasSubscriptionInfo && it.total > 0L }
    val rt = vm.runtime
    HetuHomeV2(
        data = CompactHomeData(
            running = vm.state.running, busy = vm.operation != null,
            operation = when (vm.operation) {
                HxRunOp.Start -> HomeOperation.Start
                HxRunOp.Stop -> HomeOperation.Stop
                HxRunOp.Restart -> HomeOperation.Restart
                HxRunOp.Reload -> HomeOperation.Reload
                null -> null
            },
            refreshing = vm.refreshing, testing = vm.siteTesting,
            uptimeSeconds = rt.elapsedSeconds, core = vm.state.core,
            mode = vm.state.mode, config = vm.state.config, message = vm.state.message,
            pendingSettings = vm.settingsRevision >= 0 && vm.settingsPending(), delays = vm.siteDelays,
            latencyTargets = ProxyLatencyTargets.load(vm.prefs).map { it.name },
            wan = rt.wanAddress, lan = rt.lanAddress, countryCode = rt.wanCountryCode,
            region = rt.wanRegion.ifBlank { listOf(rt.wanCountry, rt.wanCity).filter { it.isNotBlank() }.distinct().joinToString(" · ") },
            isp = rt.wanIsp, asn = rt.wanAsn, lanInterface = rt.lanInterface,
            up = vm.upRate, down = vm.downRate,
            used = tracked.sumOf { it.used }, total = tracked.sumOf { it.total },
            memory = rt.rssBytes.takeIf { it > 0 } ?: vm.state.memoryBytes,
            cpu = vm.cpuPercent, connections = vm.state.connections.size,
        ),
        groups = vm.state.groups, trafficMode = vm.state.trafficMode,
        startupError = vm.startupError, corePid = rt.pid, coreVersion = vm.coreVersion,
        bottomPadding = bottom, onToggle = vm::toggle, onReload = vm::reload,
        onRestart = vm::restart, onDelay = vm::measureSites, onRefresh = vm::pullRefresh,
        onTrafficMode = vm::setTrafficMode, onOpenNode = { vm.openPanel("proxies") },
        onOpenSubscription = { vm.openPanel("providers") },
        onOpenBasicSettings = { nav.push(HxRoute.Network) },
        onOpenConfigs = { nav.push(HxRoute.Configs) },
        onViewConfig = { nav.push(HxRoute.ConfigEditor) },
        onDismissStartupError = { vm.startupError = null }, onDetailVisibleChange = onDetail,
        cpuAffinity = rt.cpuAffinity, currentCpu = rt.currentCpu.takeIf { it >= 0 },
        resourceSamples = vm.resourceSamples.toList(),
        wanExtras = HomeWanExtras(
            city = rt.wanCity, organization = rt.wanOrganization, ipType = rt.wanIpType,
            timezone = rt.wanTimezone, coordinates = rt.wanCoordinates,
            state = rt.wanState, error = rt.wanError,
        ),
        operationText = vm.operationText,
    )
}
