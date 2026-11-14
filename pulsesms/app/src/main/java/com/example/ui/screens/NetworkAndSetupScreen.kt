package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.UserProfile
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkSnapshot
import com.example.data.preferences.BasicModeSettings
import com.example.ui.components.ConnectionPulseStrip
import com.example.ui.components.formatBytesCompact

@Composable
fun NetworkAndSetupScreen(
  myProfile: UserProfile?,
  network: NetworkSnapshot,
  mqtt: MqttTelemetry,
  settings: BasicModeSettings,
  pendingOutboxCount: Int,
  onSendPing: () -> Unit,
  onReconnectMqtt: () -> Unit,
  onFlushOutbox: () -> Unit,
  onToggleBasicMode: (Boolean) -> Unit,
  onSaveBrokerConfig: (host: String, port: Int, useTls: Boolean) -> Unit,
  onSignOut: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var hostInput by remember(settings.brokerHost) { mutableStateOf(settings.brokerHost) }
  var portInput by remember(settings.brokerPort) { mutableStateOf(settings.brokerPort.toString()) }
  var tlsChecked by remember(settings.useTls) { mutableStateOf(settings.useTls) }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      LazyColumn(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        item {
          Text(
            text = "Data & MQTT Link",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onBackground,
          )
          Text(
            text = "Zero ads, zero analytics, zero background auto-updates.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        item {
          ConnectionPulseStrip(
            network = network,
            mqtt = mqtt,
            pendingOutboxCount = pendingOutboxCount,
          )
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text("LIVE MQTT v3.1.1 TELEMETRY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
              TelemetryRow("Broker", "${mqtt.brokerHost}:${mqtt.brokerPort} (${if (mqtt.useTls) "TLS" else "TCP"})")
              TelemetryRow("Topic", mqtt.subscribedTopic.ifBlank { "Pending auth…" })
              TelemetryRow("Wire Sent / Recv", "${formatBytesCompact(mqtt.txBytes)} / ${formatBytesCompact(mqtt.rxBytes)}")
              TelemetryRow("Packets Sent / Recv", "${mqtt.packetsSent} / ${mqtt.packetsReceived}")
              TelemetryRow("2-Byte Ping RTT", mqtt.lastPingRttMs?.let { "$it ms" } ?: "Tap Ping below")
              if (!mqtt.lastError.isNullOrBlank()) {
                Text(
                  text = "Last notice: ${mqtt.lastError}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Button(
                  onClick = onSendPing,
                  modifier = Modifier.weight(1f).height(48.dp).testTag("mqtt_ping_button"),
                ) {
                  Icon(Icons.Default.NetworkPing, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("2B Ping")
                }
                OutlinedButton(
                  onClick = onReconnectMqtt,
                  modifier = Modifier.weight(1f).height(48.dp).testTag("mqtt_reconnect_button"),
                ) {
                  Icon(Icons.Default.Refresh, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Reconnect")
                }
              }

              if (pendingOutboxCount > 0) {
                OutlinedButton(
                  onClick = onFlushOutbox,
                  modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                  Icon(Icons.Default.Sync, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Flush $pendingOutboxCount Queued SMS")
                }
              }
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text("Strict Basic Mode", style = MaterialTheme.typography.titleMedium)
                  Text(
                    text = "Never auto-download photos. Always show 'View Photo (XX KB)' tap-to-load button.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Switch(
                  checked = settings.strictBasicMode,
                  onCheckedChange = onToggleBasicMode,
                  modifier = Modifier.testTag("strict_basic_mode_switch"),
                )
              }
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text("MQTT BROKER SETTINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
              OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("HiveMQ / MQTT Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                OutlinedTextField(
                  value = portInput,
                  onValueChange = { portInput = it.filter { c -> c.isDigit() } },
                  label = { Text("Port") },
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                  singleLine = true,
                  modifier = Modifier.weight(1f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text("TLS", style = MaterialTheme.typography.labelLarge)
                  Spacer(modifier = Modifier.width(6.dp))
                  Switch(checked = tlsChecked, onCheckedChange = { tlsChecked = it })
                }
              }
              Button(
                onClick = {
                  val parsedPort = portInput.toIntOrNull() ?: if (tlsChecked) 8883 else 1883
                  onSaveBrokerConfig(hostInput, parsedPort, tlsChecked)
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
              ) {
                Text("Apply Broker Settings")
              }
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CloudQueue, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Two-District Cloud Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
              }
              Text(
                text = "1. Install PulseSMS on both phones (city & rural district).\n" +
                  "2. Enter your Account Name & Phone Number and tap Start Chatting on each phone.\n" +
                  "3. Both phones automatically connect to HiveMQ TLS MQTT (broker.hivemq.com:8883) the instant Mobile Data turns on.\n" +
                  "4. Search your friend's phone number or name under People and start texting immediately.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = MaterialTheme.shapes.medium,
          ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
              Text("ACCOUNT · INSTANT MQTT SESSION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
              if (myProfile != null) {
                TelemetryRow("Account Name", myProfile.accountName)
                TelemetryRow("Phone Number", myProfile.phoneNumber)
                TelemetryRow("MQTT Address", "pulsesms/v1/u/${myProfile.phoneNormalized}")
              }
              OutlinedButton(
                onClick = onSignOut,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sign_out_button"),
              ) {
                Text("Switch Account")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
