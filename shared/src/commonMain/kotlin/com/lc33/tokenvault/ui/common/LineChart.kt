package com.lc33.tokenvault.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lc33.tokenvault.balance.ChartPoint
import com.lc33.tokenvault.ui.miuix.AppText
import com.lc33.tokenvault.ui.miuix.AppTextStyle
import com.lc33.tokenvault.ui.miuix.appDividerColor
import com.lc33.tokenvault.ui.miuix.appSecondaryTextColor
import kotlin.math.abs
import kotlin.math.max

/** 一条折线：图例名、颜色、点集。点的 x 是 epoch 毫秒、y 是值。 */
data class LineChartSeries(
    val label: String,
    val color: Color,
    val points: List<ChartPoint>,
)

/**
 * 多序列折线图。**从零手搓 Canvas**——项目里没有图表库，也不引 material3（红线：只用
 * foundation/ui + ui/miuix）。自身不做任何聚合，只画传进来的点；坐标轴文案由调用方经
 * [xLabelOf] / [yLabelOf] 给（保证币种符号、日期本地化都走既有工具，不在这里硬编码）。
 *
 * 空数据时画 [emptyText] 而不是留白：报告刚上线、还没攒到历史时，用户需要一句解释，
 * 否则会以为功能坏了。
 */
@Composable
fun LineChart(
    series: List<LineChartSeries>,
    xLabelOf: (Long) -> String,
    yLabelOf: (Double) -> String,
    emptyText: String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 200.dp,
) {
    val hasData = series.any { it.points.isNotEmpty() }
    if (!hasData) {
        Box(
            modifier = modifier.fillMaxWidth().height(chartHeight),
            contentAlignment = Alignment.Center,
        ) {
            AppText(text = emptyText, style = AppTextStyle.Secondary, color = appSecondaryTextColor)
        }
        return
    }

    val gridColor = appDividerColor
    val labelColor = appSecondaryTextColor
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp)

    // 值域：把 0 纳入，好让"余额涨了多少"有个稳定的基准线；单点/持平时上下各撑一点。
    val allPoints = series.flatMap { it.points }
    val xs = allPoints.map { it.x }
    val ys = allPoints.map { it.y }
    val xMin = xs.min()
    val xMax = xs.max()
    val rawYMin = minOf(0.0, ys.min())
    val rawYMax = max(0.0, ys.max())
    val (yMin, yMax) = niceBounds(rawYMin, rawYMax)

    // 左槽按**实际最宽的那条刻度**算，不是写死 46dp。
    //
    // 为什么：`¥12,345.00` 这种四位数的刻度比 46dp 宽，画的时候横坐标被
    // `coerceAtLeast(0f)` 顶到 0，于是整条轴文字压在网格与折线上面——余额越大越糊，
    // 最后完全读不出。先量五条刻度再定槽宽，并且给它一个上限，免得极端长串把画布吃掉。
    val labelGap = with(LocalDensity.current) { 6.dp.toPx() }
    val widestLabelPx = (0..4).maxOf { i ->
        measurer.measure(yLabelOf(yMin + (yMax - yMin) * i / 4), labelStyle).size.width.toFloat()
    }

    Canvas(modifier = modifier.fillMaxWidth().height(chartHeight)) {
        val leftGutter = (widestLabelPx + labelGap).coerceAtLeast(46.dp.toPx())
            .coerceAtMost(size.width * 0.4f)
        val bottomGutter = 18.dp.toPx()
        val topPad = 8.dp.toPx()
        val rightPad = 10.dp.toPx()
        val plotLeft = leftGutter
        val plotTop = topPad
        val plotRight = size.width - rightPad
        val plotBottom = size.height - bottomGutter
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

        fun xAt(t: Long): Float =
            if (xMax == xMin) plotLeft + plotW / 2f
            else plotLeft + ((t - xMin).toDouble() / (xMax - xMin).toDouble()).toFloat() * plotW

        fun yAt(v: Double): Float =
            if (yMax == yMin) plotBottom - plotH / 2f
            else plotBottom - ((v - yMin) / (yMax - yMin)).toFloat() * plotH

        // 横向网格 + Y 轴刻度文字（4 格 5 线）。
        val gridLines = 4
        for (i in 0..gridLines) {
            val value = yMin + (yMax - yMin) * i / gridLines
            val y = yAt(value)
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1f,
            )
            val label = yLabelOf(value)
            val measured = measurer.measure(label, labelStyle)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = (plotLeft - 4.dp.toPx() - measured.size.width).coerceAtLeast(0f),
                    y = y - measured.size.height / 2f,
                ),
            )
        }

        // X 轴：起止两个日期。区间只有一天时只标一个。
        drawAxisLabel(measurer, xLabelOf(xMin), labelStyle, plotLeft, plotBottom + 3.dp.toPx(), anchorStart = true)
        if (xMax != xMin) {
            drawAxisLabel(measurer, xLabelOf(xMax), labelStyle, plotRight, plotBottom + 3.dp.toPx(), anchorStart = false)
        }

        // 每条序列一条线 + 数据点。
        for (line in series) {
            if (line.points.isEmpty()) continue
            val path = Path()
            line.points.forEachIndexed { index, point ->
                val px = xAt(point.x)
                val py = yAt(point.y)
                if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path = path, color = line.color, style = Stroke(width = 2.dp.toPx()))
            line.points.forEach { point ->
                drawCircle(color = line.color, radius = 2.5.dp.toPx(), center = Offset(xAt(point.x), yAt(point.y)))
            }
        }
    }
}

/**
 * 迷你折线（无坐标轴、无文字），供每张供应商卡的一瞥趋势用。
 *
 * 独立实现而不复用 [LineChart]：省掉网格、刻度、文字测量，只把点归一化到自己的小框里
 * 描一条线，卡片里几十像素高也读得出走势。
 */
@Composable
fun MiniSparkline(
    points: List<ChartPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    lineHeight: Dp = 32.dp,
) {
    if (points.isEmpty()) {
        Box(modifier = modifier.height(lineHeight))
        return
    }
    if (points.size == 1) {
        // 只有一个读数时画一个点，而不是留一个空框：空框看着像渲染坏了，而这个点
        // 是真实存在的一条记录（余额历史刚开始攒的时候每家都是这个形状）。
        Canvas(modifier = modifier.height(lineHeight)) {
            drawCircle(
                color = color,
                radius = 2.5.dp.toPx(),
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }
        return
    }
    val xs = points.map { it.x }
    val ys = points.map { it.y }
    val xMin = xs.min()
    val xMax = xs.max()
    val yMin = ys.min()
    val yMax = ys.max()

    Canvas(modifier = modifier.height(lineHeight)) {
        val pad = 2.dp.toPx()
        val w = (size.width - pad * 2).coerceAtLeast(1f)
        val h = (size.height - pad * 2).coerceAtLeast(1f)
        val path = Path()
        points.forEachIndexed { index, point ->
            val px = pad + if (xMax == xMin) w / 2f
            else ((point.x - xMin).toDouble() / (xMax - xMin).toDouble()).toFloat() * w
            val py = pad + if (yMax == yMin) h / 2f
            else (1f - ((point.y - yMin) / (yMax - yMin)).toFloat()) * h
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path = path, color = color, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxisLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    anchorStart: Boolean,
) {
    val measured = measurer.measure(text, style)
    val left = if (anchorStart) x else (x - measured.size.width)
    drawText(textLayoutResult = measured, topLeft = Offset(left.coerceAtLeast(0f), y))
}

/** 给 y 轴一个不那么"贴脸"的上下界：完全持平时撑开一点，否则顶部留 8% 余量。 */
private fun niceBounds(minValue: Double, maxValue: Double): Pair<Double, Double> {
    if (abs(maxValue - minValue) < 1e-9) {
        val pad = if (abs(maxValue) < 1e-9) 1.0 else abs(maxValue) * 0.1
        return (minValue - pad) to (maxValue + pad)
    }
    val span = maxValue - minValue
    val top = if (maxValue > 0) maxValue + span * 0.08 else maxValue
    val bottom = if (minValue < 0) minValue - span * 0.08 else minValue
    return bottom to top
}
