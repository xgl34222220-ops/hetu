package io.github.xgl34222220.hetu

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.Fill

/**
 * The traffic graph uses Vico rather than a one-off Canvas implementation.  This keeps the
 * animation/model pipeline consistent with the richer overview panel and gives us a stable
 * foundation for markers, zoom and longer history later without coupling those features to Home.
 */
@Composable
internal fun HxTrafficChart(
    down: List<Long>,
    up: List<Long>,
    downColor: Color,
    upColor: Color,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    val downValues = remember(down) { down.map { it.coerceAtLeast(0L) } }
    val upValues = remember(up) { up.map { it.coerceAtLeast(0L) } }

    val hasDown = downValues.size >= 2
    val hasUp = upValues.size >= 2
    if (!hasDown && !hasUp) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(if (downValues.isEmpty() && upValues.isEmpty()) "暂无采样" else "等待更多采样", style = MaterialTheme.typography.bodySmall, color = Hx.colors.textMuted)
        }
        return
    }
    LaunchedEffect(downValues, upValues) {
        modelProducer.runTransaction {
            lineModel {
                if (hasDown) series(y = downValues)
                if (hasUp) series(y = upValues)
            }
        }
    }

    val downLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(downColor)),
        areaFill = LineCartesianLayer.AreaFill.single(Fill(downColor.copy(alpha = .12f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val upLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(upColor.copy(alpha = .82f))),
        areaFill = null,
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
    )
    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = LineCartesianLayer.LineProvider.series(*listOfNotNull(downLine.takeIf { hasDown }, upLine.takeIf { hasUp }).toTypedArray()),
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        animationSpec = if (LocalHxMotionEnabled.current) HxMotion.enter() else androidx.compose.animation.core.snap(),
    )
}
