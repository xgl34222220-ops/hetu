package io.github.xgl34222220.hetu.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Title of every per-core degraded state: 「当前核心（sing-box）不支持此功能」. */
internal fun homeCoreUnsupportedTitle(core: String): String = "当前核心（$core）不支持此功能"

/**
 * The explicit per-core degraded state, drawn as the same glass card as the rest of the app:
 * a feature the running core does not provide is a calm, explained empty state, never an error
 * card or a toast. [feature] names what is missing; [detail] says why and what still works.
 * [raised] uses the dialog surface (inside a popup) instead of the page card surface.
 */
@Composable
internal fun HomeCoreUnsupportedCard(
    core: String,
    feature: String,
    detail: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = HomeIcons.Info,
    raised: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = LocalHomeColors.current
    Column(
        modifier.fillMaxWidth()
            .homeGlassPanel(HomeDims.cardShape, if (raised) c.raised else c.surface, raised = raised)
            .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp).background(c.accentSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(30.dp), tint = c.accent)
        }
        Spacer(Modifier.height(16.dp))
        // Core names and feature names are data, not vocabulary: only the fixed frame is translated.
        Text(
            homeCoreUnsupportedTitle(core), Modifier.semantics { heading() },
            color = c.t1, style = HomeType.rowTitle, textAlign = TextAlign.Center,
        )
        if (feature.isNotBlank()) {
            Text(
                feature, Modifier.padding(top = 8.dp).background(c.sunken, HomeDims.pillShape).padding(horizontal = 12.dp, vertical = 4.dp),
                color = c.t2, style = HomeType.badge, textAlign = TextAlign.Center,
            )
        }
        if (detail.isNotBlank()) {
            Text(detail, Modifier.padding(top = 12.dp), color = c.t2, style = HomeType.bodySmall, textAlign = TextAlign.Center)
        }
        if (actions != null) {
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), content = actions)
        }
    }
}
