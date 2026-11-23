package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.measurement.TargetCurveGenerator
import com.example.domain.model.BrewEvent
import com.example.domain.model.BrewEventType
import com.example.domain.model.MissingInterval
import com.example.domain.model.PlottedPoint
import com.example.domain.model.RecipeStep
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalTeal
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@Composable
fun CumulativeWeightChartCard(
  title: String = "累計注水量曲線",
  steps: List<RecipeStep>,
  targetTotalWaterG: Double,
  actualSegments: List<List<PlottedPoint>>,
  events: List<BrewEvent>,
  missingIntervals: List<MissingInterval> = emptyList(),
  currentElapsedMs: Long = 0L,
  chartHeight: Dp = 190.dp,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val actualColor = MaterialTheme.colorScheme.primary
  val targetColor = MaterialTheme.colorScheme.onSurfaceVariant
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val gapFillColor = SignalCrimson.copy(alpha = 0.12f)

  val targetCurve = TargetCurveGenerator.generateCurve(steps)
  val recipeEndMs = (steps.maxOfOrNull { it.endSec } ?: 180) * 1000L
  val maxSampleMs = actualSegments.flatten().maxOfOrNull { it.elapsedMs } ?: 0L
  val maxXMs = max(max(recipeEndMs, maxSampleMs), currentElapsedMs).coerceAtLeast(30_000L)

  val maxActualY = actualSegments.flatten().maxOfOrNull { it.weightG } ?: 0.0
  val minActualY = actualSegments.flatten().minOfOrNull { it.weightG } ?: 0.0
  val yMin = min(0.0, minActualY - 5.0)
  val yMax = max(targetTotalWaterG * 1.12, maxActualY * 1.12).coerceAtLeast(50.0)

  ChartContainerCard(
    title = title,
    unitLabel = "g / mm:ss",
    legendItems = listOf(
      ChartLegendItem("實測曲線（實線）", actualColor, isDashed = false),
      ChartLegendItem("配方目標（虛線）", targetColor, isDashed = true),
    ),
    modifier = modifier,
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(chartHeight),
    ) {
      val leftPad = 42.dp.toPx()
      val rightPad = 10.dp.toPx()
      val topPad = 10.dp.toPx()
      val bottomPad = 24.dp.toPx()
      val plotW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
      val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)

      fun xForMs(ms: Long): Float =
        leftPad + (ms.toFloat() / maxXMs.toFloat()).coerceIn(0f, 1f) * plotW

      fun yForVal(v: Double): Float {
        val ratio = ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
        return topPad + plotH * (1f - ratio)
      }

      drawAxesAndGrid(
        textMeasurer = textMeasurer,
        leftPad = leftPad,
        topPad = topPad,
        plotW = plotW,
        plotH = plotH,
        yMin = yMin,
        yMax = yMax,
        maxXMs = maxXMs,
        gridColor = gridColor,
        labelColor = labelColor,
        yDecimals = 0,
      )

      // Shade missing intervals
      for (gap in missingIntervals) {
        val gx1 = xForMs(gap.startElapsedMs)
        val gx2 = xForMs(gap.endElapsedMs)
        if (gx2 > gx1) {
          drawRect(
            color = gapFillColor,
            topLeft = Offset(gx1, topPad),
            size = Size(gx2 - gx1, plotH),
          )
        }
      }

      // Draw target curve (dashed)
      if (targetCurve.size >= 2) {
        val targetPath = Path()
        targetCurve.forEachIndexed { idx, pt ->
          val px = xForMs(pt.elapsedMs)
          val py = yForVal(pt.targetWaterG)
          if (idx == 0) targetPath.moveTo(px, py) else targetPath.lineTo(px, py)
        }
        drawPath(
          path = targetPath,
          color = targetColor,
          style = Stroke(
            width = 2.dp.toPx(),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
          ),
        )
      }

      // Draw actual segments (solid, broken across gaps!)
      for (seg in actualSegments) {
        if (seg.isEmpty()) continue
        if (seg.size == 1) {
          val p = seg.first()
          drawCircle(
            color = actualColor,
            radius = 3.dp.toPx(),
            center = Offset(xForMs(p.elapsedMs), yForVal(p.weightG)),
          )
        } else {
          val path = Path()
          seg.forEachIndexed { idx, pt ->
            val px = xForMs(pt.elapsedMs)
            val py = yForVal(pt.weightG)
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
          }
          drawPath(
            path = path,
            color = actualColor,
            style = Stroke(
              width = 2.8.dp.toPx(),
              cap = StrokeCap.Round,
              join = StrokeJoin.Round,
            ),
          )
        }
      }

      // Draw event markers along chart
      drawEventMarkers(
        events = events,
        topPad = topPad,
        plotH = plotH,
        xForMs = ::xForMs,
      )
    }
  }
}

@Composable
fun PouringRateChartCard(
  title: String = "注水速率曲線",
  steps: List<RecipeStep>,
  rateSegments: List<List<PlottedPoint>>,
  events: List<BrewEvent>,
  missingIntervals: List<MissingInterval> = emptyList(),
  currentElapsedMs: Long = 0L,
  chartHeight: Dp = 165.dp,
  modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val actualColor = MaterialTheme.colorScheme.tertiary
  val targetColor = MaterialTheme.colorScheme.onSurfaceVariant
  val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
  val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
  val gapFillColor = SignalCrimson.copy(alpha = 0.12f)

  val targetCurve = TargetCurveGenerator.generateCurve(steps)
  val recipeEndMs = (steps.maxOfOrNull { it.endSec } ?: 180) * 1000L
  val maxSampleMs = rateSegments.flatten().maxOfOrNull { it.elapsedMs } ?: 0L
  val maxXMs = max(max(recipeEndMs, maxSampleMs), currentElapsedMs).coerceAtLeast(30_000L)

  val allRates = rateSegments.flatten().mapNotNull { it.rateGPerSec }
  val maxTargetRate = targetCurve.maxOfOrNull { it.targetRateGPerSec } ?: 5.0
  val maxActualRate = allRates.maxOrNull() ?: 0.0
  val minActualRate = allRates.minOrNull() ?: 0.0
  val yMin = min(0.0, minActualRate - 0.5)
  val yMax = ceil(max(maxTargetRate * 1.4, maxActualRate * 1.2).coerceAtLeast(6.0))

  ChartContainerCard(
    title = title,
    unitLabel = "g/s / mm:ss",
    legendItems = listOf(
      ChartLegendItem("實測速率（實線）", actualColor, isDashed = false),
      ChartLegendItem("階段參考均速（虛線）", targetColor, isDashed = true),
    ),
    modifier = modifier,
  ) {
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(chartHeight),
    ) {
      val leftPad = 42.dp.toPx()
      val rightPad = 10.dp.toPx()
      val topPad = 10.dp.toPx()
      val bottomPad = 24.dp.toPx()
      val plotW = (size.width - leftPad - rightPad).coerceAtLeast(10f)
      val plotH = (size.height - topPad - bottomPad).coerceAtLeast(10f)

      fun xForMs(ms: Long): Float =
        leftPad + (ms.toFloat() / maxXMs.toFloat()).coerceIn(0f, 1f) * plotW

      fun yForVal(v: Double): Float {
        val ratio = ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
        return topPad + plotH * (1f - ratio)
      }

      drawAxesAndGrid(
        textMeasurer = textMeasurer,
        leftPad = leftPad,
        topPad = topPad,
        plotW = plotW,
        plotH = plotH,
        yMin = yMin,
        yMax = yMax,
        maxXMs = maxXMs,
        gridColor = gridColor,
        labelColor = labelColor,
        yDecimals = 1,
      )

      for (gap in missingIntervals) {
        val gx1 = xForMs(gap.startElapsedMs)
        val gx2 = xForMs(gap.endElapsedMs)
        if (gx2 > gx1) {
          drawRect(
            color = gapFillColor,
            topLeft = Offset(gx1, topPad),
            size = Size(gx2 - gx1, plotH),
          )
        }
      }

      // Draw step-guide target rate (dashed)
      if (steps.isNotEmpty()) {
        val targetPath = Path()
        steps.sortedBy { it.startSec }.forEachIndexed { idx, step ->
          val sx = xForMs(step.startSec * 1000L)
          val ex = xForMs(step.endSec * 1000L)
          val ry = yForVal(step.plannedAverageRateGPerSec)
          if (idx == 0) targetPath.moveTo(sx, ry) else targetPath.lineTo(sx, ry)
          targetPath.lineTo(ex, ry)
        }
        drawPath(
          path = targetPath,
          color = targetColor,
          style = Stroke(
            width = 1.8.dp.toPx(),
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f),
          ),
        )
      }

      // Draw actual rate segments (broken where rate == null or gap occurred)
      for (seg in rateSegments) {
        val validPoints = seg.filter { it.rateGPerSec != null }
        if (validPoints.isEmpty()) continue
        if (validPoints.size == 1) {
          val p = validPoints.first()
          drawCircle(
            color = actualColor,
            radius = 2.8.dp.toPx(),
            center = Offset(xForMs(p.elapsedMs), yForVal(p.rateGPerSec!!)),
          )
        } else {
          val path = Path()
          validPoints.forEachIndexed { idx, pt ->
            val px = xForMs(pt.elapsedMs)
            val py = yForVal(pt.rateGPerSec!!)
            if (idx == 0) path.moveTo(px, py) else path.lineTo(px, py)
          }
          drawPath(
            path = path,
            color = actualColor,
            style = Stroke(
              width = 2.5.dp.toPx(),
              cap = StrokeCap.Round,
              join = StrokeJoin.Round,
            ),
          )
        }
      }

      drawEventMarkers(
        events = events,
        topPad = topPad,
        plotH = plotH,
        xForMs = ::xForMs,
      )
    }
  }
}

private fun DrawScope.drawAxesAndGrid(
  textMeasurer: TextMeasurer,
  leftPad: Float,
  topPad: Float,
  plotW: Float,
  plotH: Float,
  yMin: Double,
  yMax: Double,
  maxXMs: Long,
  gridColor: Color,
  labelColor: Color,
  yDecimals: Int,
) {
  val labelStyle = TextStyle(
    fontFamily = JetBrainsMonoFamily,
    fontSize = 10.sp,
    color = labelColor,
  )

  val yTicks = 4
  for (i in 0..yTicks) {
    val frac = i.toFloat() / yTicks.toFloat()
    val y = topPad + plotH * (1f - frac)
    drawLine(
      color = gridColor,
      start = Offset(leftPad, y),
      end = Offset(leftPad + plotW, y),
      strokeWidth = 1.dp.toPx(),
    )
    val value = yMin + (yMax - yMin) * frac
    val text = String.format("%.${yDecimals}f", value)
    val measured = textMeasurer.measure(text, labelStyle)
    drawText(
      textLayoutResult = measured,
      topLeft = Offset(
        (leftPad - measured.size.width - 6.dp.toPx()).coerceAtLeast(0f),
        y - measured.size.height / 2f,
      ),
    )
  }

  val xTicks = 3
  for (i in 0..xTicks) {
    val frac = i.toFloat() / xTicks.toFloat()
    val x = leftPad + plotW * frac
    drawLine(
      color = gridColor.copy(alpha = 0.45f),
      start = Offset(x, topPad),
      end = Offset(x, topPad + plotH),
      strokeWidth = 1.dp.toPx(),
    )
    val ms = (maxXMs * frac).toLong()
    val label = BrewFormat.formatMmSs(ms)
    val measured = textMeasurer.measure(label, labelStyle)
    val tx = when (i) {
      0 -> x
      xTicks -> (x - measured.size.width).coerceAtLeast(leftPad)
      else -> x - measured.size.width / 2f
    }
    drawText(
      textLayoutResult = measured,
      topLeft = Offset(tx, topPad + plotH + 5.dp.toPx()),
    )
  }
}

private fun DrawScope.drawEventMarkers(
  events: List<BrewEvent>,
  topPad: Float,
  plotH: Float,
  xForMs: (Long) -> Float,
) {
  for (ev in events) {
    val x = xForMs(ev.elapsedMs)
    val color = when (ev.type) {
      BrewEventType.POUR_START -> SignalTeal
      BrewEventType.POUR_STOP -> SignalOchre
      BrewEventType.TOUCH_EQUIPMENT,
      BrewEventType.MOVE_EQUIPMENT,
      BrewEventType.DISCONNECTED,
      BrewEventType.APP_BACKGROUNDED,
      BrewEventType.CLOCK_RESET -> SignalCrimson
      BrewEventType.RECONNECTED -> SignalTeal
      BrewEventType.NOTE -> Color(0xFF6B5E53)
    }
    drawLine(
      color = color.copy(alpha = 0.55f),
      start = Offset(x, topPad),
      end = Offset(x, topPad + plotH),
      strokeWidth = 1.3.dp.toPx(),
      pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f),
    )
    drawCircle(
      color = color,
      radius = 3.5.dp.toPx(),
      center = Offset(x, topPad + 4.dp.toPx()),
    )
  }
}

data class ChartLegendItem(
  val label: String,
  val color: Color,
  val isDashed: Boolean,
)

@Composable
private fun ChartContainerCard(
  title: String,
  unitLabel: String,
  legendItems: List<ChartLegendItem>,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
      .padding(horizontal = 14.dp, vertical = 12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = unitLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Row(
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      legendItems.forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Canvas(modifier = Modifier.size(width = 18.dp, height = 6.dp)) {
            drawLine(
              color = item.color,
              start = Offset(0f, size.height / 2f),
              end = Offset(size.width, size.height / 2f),
              strokeWidth = 2.4.dp.toPx(),
              pathEffect = if (item.isDashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f) else null,
            )
          }
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = item.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
    Spacer(modifier = Modifier.height(10.dp))
    content()
  }
}
