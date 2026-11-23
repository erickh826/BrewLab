package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.device.ScaleConnectionState
import com.example.domain.model.SourceType
import com.example.ui.theme.SignalCrimson
import com.example.ui.theme.SignalCrimsonBg
import com.example.ui.theme.SignalOchre
import com.example.ui.theme.SignalOchreBg
import com.example.ui.theme.SignalTeal
import com.example.ui.theme.SignalTealBg

@Composable
fun StatusPill(
  text: String,
  containerColor: Color,
  contentColor: Color,
  dotColor: Color = contentColor,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .background(containerColor)
      .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .clip(CircleShape)
        .background(dotColor),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = text,
      style = MaterialTheme.typography.labelMedium,
      color = contentColor,
    )
  }
}

@Composable
fun SourceConnectionPill(
  sourceType: SourceType,
  connectionState: ScaleConnectionState,
  modifier: Modifier = Modifier,
) {
  val (bg, fg) = when {
    sourceType == SourceType.BLE_HARDWARE -> SignalCrimsonBg to SignalCrimson
    connectionState is ScaleConnectionState.Connected -> SignalTealBg to SignalTeal
    connectionState is ScaleConnectionState.Connecting ||
      connectionState is ScaleConnectionState.Reconnecting -> SignalOchreBg to SignalOchre
    connectionState is ScaleConnectionState.Interrupted -> SignalCrimsonBg to SignalCrimson
    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
  }
  val label = if (sourceType == SourceType.BLE_HARDWARE) {
    "實體藍牙：尚未整合"
  } else {
    "模擬數據 · ${connectionState.label}"
  }
  StatusPill(text = label, containerColor = bg, contentColor = fg, modifier = modifier)
}

@Composable
fun InstrumentSectionCard(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 1.dp,
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      content()
    }
  }
}

@Composable
fun MeasurementModelNoticeCard(
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .clip(MaterialTheme.shapes.small)
      .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      text = "量測邊界說明：單一電子秤支撐濾杯、咖啡粉與分享壺，重量變化僅近似外部注水（累計注水量與注水速率），無法單獨測量濾杯滴出量、萃取率、水溫或風味結果。",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
