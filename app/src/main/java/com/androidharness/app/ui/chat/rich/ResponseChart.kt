package com.androidharness.app.ui.chat.rich

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.core.response.RichResponse
import com.androidharness.app.ui.common.TextButton
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat

/** Nice decimal ticks also keep close readings distinct (the values are not rounded to the tick step). */
internal class ResponseChartAxis private constructor(val ticks: List<BigDecimal>) {
    private val base = ticks.first()
    private val span = ticks.last() - base
    val labels = ticks.map {
        val n = it.stripTrailingZeros()
        if (n.signum() != 0 && (n.scale() < -6 || n.abs() < BigDecimal("0.0001"))) n.toString() else n.toPlainString()
    }
    fun position(value: Double): Float = (BigDecimal.valueOf(value) - base).divide(span, MathContext.DECIMAL64).toFloat()

    companion object {
        fun from(low: Double, high: Double, bars: Boolean): ResponseChartAxis {
            var min = BigDecimal.valueOf(if (bars) minOf(0.0, low) else low)
            var max = BigDecimal.valueOf(if (bars) maxOf(0.0, high) else high)
            if (min.compareTo(max) == 0) {
                val pad = if (min.signum() == 0) BigDecimal.ONE else min.abs().movePointLeft(1)
                if (!bars) min -= pad
                max += pad
            }
            val target = (max - min).divide(BigDecimal.valueOf(4))
            val decade = BigDecimal.ONE.scaleByPowerOfTen(target.precision() - target.scale() - 1)
            val step = listOf(1L, 2L, 5L, 10L).map { decade * BigDecimal.valueOf(it) }.first { it >= target }
            val first = min.divide(step, 0, RoundingMode.FLOOR)
            val last = max.divide(step, 0, RoundingMode.CEILING)
            val ticks = (0..(last - first).intValueExact()).map { (first + BigDecimal.valueOf(it.toLong())) * step }
            return ResponseChartAxis(ticks)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ResponseChart(chart: RichResponse.Node.Chart, onFollowUp: ((RichResponse.FollowUp) -> Unit)?) {
    val ui = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val locale = ui.resources.configuration.locales[0]
    val valueFormat = remember(locale) { NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 340
    } }
    val series = remember(chart) { chart.series.ifEmpty { listOf(RichResponse.Series(chart.title, chart.points)) } }
    val palette = listOf(colors.primary, colors.tertiary, colors.secondary, colors.error, colors.onSurface, colors.outline)
    var visible by rememberSaveable(chart) { mutableStateOf(List(series.size) { true }) }
    var selected by rememberSaveable(chart) { mutableIntStateOf(chart.points.lastIndex) }
    var start by rememberSaveable(chart) { mutableIntStateOf(0) }
    var end by rememberSaveable(chart) { mutableIntStateOf(chart.points.lastIndex) }
    var data by rememberSaveable(chart) { mutableStateOf(false) }
    val contextChange = LocalResponseContext.current
    val active = series.indices.filter { visible[it] }
    val current = selected.coerceIn(start, end)
    val bars = chart.kind != "line"
    val axis = remember(series, visible, start, end, bars) {
        val values = active.flatMap { series[it].points.subList(start, end + 1) }
        ResponseChartAxis.from(values.minOf { it.value }, values.maxOf { it.value }, bars)
    }
    val positions = remember(axis) { active.map { index -> series[index].points.subList(start, end + 1).map { axis.position(it.value) } } }
    val count = end - start + 1
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    fun cell(index: Int) = if (rtl) count - 1 - index else index
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val tickHeight = with(density) { measurer.measure("0", labelStyle, maxLines = 1).size.height.toDp() }
    val plotHeight = maxOf(150.dp, (tickHeight + 5.dp) * axis.labels.size)
    fun context(): String = "Range: ${chart.points[start].label} to ${chart.points[end].label}\nSelected: ${chart.points[selected.coerceIn(start, end)].label}\n" +
        series.indices.filter { visible[it] }.joinToString("\n") { "${series[it].name}: ${RichResponse.number(series[it].points[selected.coerceIn(start, end)].value)} ${chart.unit}" }
    ResponsePanel {
        Text(chart.title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(chart.points[current].label, style = MaterialTheme.typography.labelMedium)
            active.forEach { index -> Text("${series[index].name}: ${valueFormat.format(series[index].points[current].value)} ${chart.unit}".trim(),
                style = MaterialTheme.typography.labelMedium, color = palette[index]) }
        }
        if (series.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            series.forEachIndexed { index, item -> FilterChip(visible[index], onClick = {
                if (!visible[index] || visible.count { it } > 1) {
                    visible = visible.toMutableList().also { it[index] = !it[index] }
                    contextChange?.invoke(chart.title, context())
                }
            }, label = { Text(item.name, color = palette[index]) }) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.height(plotHeight), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                axis.labels.asReversed().forEach { label ->
                    Text(label, style = labelStyle, color = colors.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height(plotHeight).semantics {
                    contentDescription = ui.getString(R.string.ui_values_use_show_data_for_all_values, chart.title, count)
                }.pointerInput(chart, start, end, visible, rtl) { detectTapGestures { tap ->
                    selected = start + cell((tap.x / size.width.coerceAtLeast(1) * count).toInt().coerceIn(0, count - 1))
                    contextChange?.invoke(chart.title, context())
                } }) {
                    val cellWidth = size.width / count
                    val inset = tickHeight.toPx() / 2
                    fun y(portion: Float) = size.height - inset - portion * (size.height - 2 * inset)
                    axis.ticks.forEachIndexed { i, tick ->
                        val lineY = y(i.toFloat() / axis.ticks.lastIndex)
                        drawLine(if (tick.signum() == 0) colors.outline else colors.outlineVariant.copy(alpha = .65f),
                            Offset(0f, lineY), Offset(size.width, lineY), 1.dp.toPx())
                    }
                    active.forEachIndexed { group, index ->
                        val heights = positions[group]
                        val color = palette[index]
                        if (!bars) {
                            val path = Path()
                            heights.forEachIndexed { i, value ->
                                val x = cellWidth * (cell(i) + .5f)
                                if (i == 0) path.moveTo(x, y(value)) else path.lineTo(x, y(value))
                            }
                            drawPath(path, color, style = Stroke(2.dp.toPx()))
                            heights.forEachIndexed { i, value ->
                                drawCircle(color, if (start + i == current) 5.dp.toPx() else 3.dp.toPx(), Offset(cellWidth * (cell(i) + .5f), y(value)))
                            }
                        } else {
                            val zero = y(axis.position(0.0))
                            val width = cellWidth * .76f / active.size
                            heights.forEachIndexed { i, value ->
                                val py = y(value)
                                drawRoundRect(if (start + i == current) color else color.copy(alpha = .5f),
                                    Offset(cellWidth * (cell(i) + .12f) + (if (rtl) active.lastIndex - group else group) * width, minOf(py, zero)),
                                    Size(width * .9f, kotlin.math.abs(py - zero)), CornerRadius(2.dp.toPx()))
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // Category labels share the same cells as the marks. Long labels stay available in data view.
                    val cellWidth = constraints.maxWidth / count
                    val allFit = count <= 8 && (start..end).all {
                        measurer.measure(chart.points[it].label, labelStyle, maxLines = 1).size.width + with(density) { 4.dp.roundToPx() } <= cellWidth
                    }
                    Row(Modifier.fillMaxWidth()) {
                        if (allFit) for (i in start..end) Text(chart.points[i].label, Modifier.weight(1f),
                            style = labelStyle, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else {
                            Text(chart.points[start].label, Modifier.weight(1f), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (end > start) Text(chart.points[end].label, Modifier.weight(1f), style = labelStyle,
                                textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (chart.points.size > 2) {
            Text(stringResource(R.string.rich_chart_range), style = MaterialTheme.typography.labelMedium)
            RangeSlider(value = start.toFloat()..end.toFloat(), onValueChange = { window ->
                start = window.start.toInt().coerceIn(0, chart.points.lastIndex - 1)
                end = window.endInclusive.toInt().coerceIn(start + 1, chart.points.lastIndex)
                contextChange?.invoke(chart.title, context())
            }, valueRange = 0f..chart.points.lastIndex.toFloat(), steps = chart.points.size - 2)
            if (start != 0 || end != chart.points.lastIndex) TextButton(onClick = { start = 0; end = chart.points.lastIndex; contextChange?.invoke(chart.title, context()) }) {
                Text(stringResource(R.string.rich_reset_range))
            }
        }
        if (chart.caption.isNotBlank()) Text(chart.caption, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { data = !data }) { Text(stringResource(if (data) R.string.ui_hide_data else R.string.ui_show_data)) }
        if (data) Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { Text(stringResource(R.string.rich_chart_label), Modifier.width(120.dp)); active.forEach { Text(series[it].name, Modifier.width(120.dp)) } }
            for (index in start..end) Row {
                TextButton(onClick = { selected = index; contextChange?.invoke(chart.title, context()) }, Modifier.width(120.dp).semantics { contentDescription = ui.getString(R.string.rich_select_point, chart.points[index].label) }) { Text(chart.points[index].label) }
                active.forEach { Text("${valueFormat.format(series[it].points[index].value)} ${chart.unit}", Modifier.width(120.dp).padding(vertical = 12.dp)) }
            }
        }
        onFollowUp?.let { follow -> TextButton(onClick = { follow(RichResponse.followUp(ui.getString(R.string.rich_discuss_chart_prompt), chart.title, context())) }) {
            Text(stringResource(R.string.rich_discuss_chart))
        } }
    }
}
