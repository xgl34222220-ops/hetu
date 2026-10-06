package io.github.xgl34222220.hetu

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

    LaunchedEffect(downValues, upValues) {
        val d = if (downValues.size >= 2) downValues else listOf(0L, downValues.lastOrNull() ?: 0L)
        val u = if (upValues.size >= 2) upValues else listOf(0L, upValues.lastOrNull() ?: 0L)
        modelProducer.runTransaction {
            lineModel {
                series(y = d)
                series(y = u)
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
                lineProvider = LineCartesianLayer.LineProvider.series(downLine, upLine),
            ),
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        animationSpec = HxMotion.enter(),
    )
}
