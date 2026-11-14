package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.data.network.MqttConnectionState
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkSnapshot
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CoralAlert
import com.example.ui.theme.EmeraldBright

fun formatBytesCompact(bytes: Long): String {
  return when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
  }
}

@Composable
fun ConnectionPulseStrip(
  network: NetworkSnapshot,
  mqtt: MqttTelemetry,
  pendingOutboxCount: Int,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  val dotColor =
    when {
      !network.isOnline -> CoralAlert
      mqtt.state == MqttConnectionState.CONNECTED -> EmeraldBright
      mqtt.state == MqttConnectionState.CONNECTING -> AmberSignal
      else -> AmberSignal
    }

  val statusLabel =
    when {
      !network.isOnline -> "SIGNAL WAIT"
      mqtt.state == MqttConnectionState.CONNECTED -> "MQTT LIVE · ${network.transportLabel}"
      mqtt.state == MqttConnectionState.CONNECTING -> "HANDSHAKE · ${network.transportLabel}"
      else -> "CLOUD READY · ${network.transportLabel}"
    }

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    shape = RoundedCornerShape(14.dp),
    modifier =
      modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .testTag("connection_pulse_strip"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f),
      ) {
        Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
        Text(
          text = statusLabel,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (pendingOutboxCount > 0) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(999.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = "Queued messages",
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
              )
              Text(
                text = "$pendingOutboxCount queued",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        }
        Text(
          text = "↑${formatBytesCompact(mqtt.txBytes)} ↓${formatBytesCompact(mqtt.rxBytes)}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

/**
 * Strict Basic Mode Tap-to-Load Photo Widget:
 * - Zero image bytes downloaded until the receiver explicitly taps "View Photo (XX KB)".
 * - Once tapped, renders the decoded ultra-compressed WebP bitmap and caches it locally in Room.
 */
@Composable
fun TapToLoadPhotoCard(
  payloadId: String,
  sizeBytes: Long,
  width: Long,
  height: Long,
  isSender: Boolean,
  isCachedLocally: Boolean,
  loadedBitmap: Bitmap?,
  isLoading: Boolean,
  errorText: String?,
  onTapLoad: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showZoomDialog by remember { mutableStateOf(false) }
  val sizeLabel = formatBytesCompact(sizeBytes)

  if (loadedBitmap != null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      AnimatedVisibility(visible = true, enter = fadeIn()) {
        Image(
          bitmap = loadedBitmap.asImageBitmap(),
          contentDescription = "Compressed WebP photo ($sizeLabel)",
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 120.dp, max = 240.dp)
              .clip(RoundedCornerShape(12.dp))
              .clickable { showZoomDialog = true }
              .testTag("loaded_photo_image_$payloadId"),
          contentScale = ContentScale.Crop,
        )
      }
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Default.CloudDone,
          contentDescription = null,
          modifier = Modifier.size(13.dp),
          tint =
            if (isSender) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            else MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "WebP · $sizeLabel (${width}×$height)",
          style = MaterialTheme.typography.labelSmall,
          color =
            if (isSender) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    if (showZoomDialog) {
      Dialog(onDismissRequest = { showZoomDialog = false }) {
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
              bitmap = loadedBitmap.asImageBitmap(),
              contentDescription = "Expanded WebP photo",
              modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)),
              contentScale = ContentScale.Fit,
            )
            Text(
              text = "Ultra-Compressed WebP · $sizeLabel (${width}×$height)",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  } else {
    Surface(
      color =
        if (isSender) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surfaceVariant,
      shape = RoundedCornerShape(12.dp),
      modifier = modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = if (errorText != null) Icons.Default.CloudOff else Icons.Default.Image,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint =
              if (isSender) MaterialTheme.colorScheme.onPrimary
              else MaterialTheme.colorScheme.primary,
          )
          Column {
            Text(
              text = if (isCachedLocally) "Photo cached locally" else "Basic Mode · Photo locked",
              style = MaterialTheme.typography.titleMedium,
              color =
                if (isSender) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text =
                if (errorText != null) errorText
                else if (isCachedLocally) "$sizeLabel WebP ready (0 B network)"
                else "0 B downloaded · ${width}×$height WebP",
              style = MaterialTheme.typography.labelSmall,
              color =
                if (errorText != null) MaterialTheme.colorScheme.error
                else if (isSender) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        Button(
          onClick = onTapLoad,
          enabled = !isLoading,
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("view_photo_button_$payloadId"),
          shape = RoundedCornerShape(10.dp),
          colors =
            if (isSender) {
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onPrimary,
                contentColor = MaterialTheme.colorScheme.primary,
              )
            } else {
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              )
            },
        ) {
          if (isLoading) {
            CircularProgressIndicator(
              modifier = Modifier.size(18.dp),
              strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Loading ($sizeLabel)…", style = MaterialTheme.typography.labelLarge)
          } else {
            Icon(
              imageVector = Icons.Default.Download,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (isCachedLocally) "Open Photo ($sizeLabel)" else "View Photo ($sizeLabel)",
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
    }
  }
}
