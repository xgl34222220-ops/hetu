package io.github.xgl34222220.bichen

/**
 * Pure derivation overload for the proxy panel's node list.
 *
 * The panel builds LazyListScope content, which is not itself a composable context. Keeping this
 * calculation pure prevents the list builder from accidentally calling Compose's @Composable
 * remember(List, String) overload while preserving the current call site until the panel is split
 * into smaller composables.
 */
internal fun <T> remember(
    groups: List<ProxyGroupUi>,
    query: String,
    calculation: () -> T,
): T = calculation()
