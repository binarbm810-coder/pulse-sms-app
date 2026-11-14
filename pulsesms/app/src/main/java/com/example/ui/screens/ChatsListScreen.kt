package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatThread
import com.example.data.model.UserProfile
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkSnapshot
import com.example.ui.components.ConnectionPulseStrip
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatsListScreen(
  currentUserId: String,
  myProfile: UserProfile?,
  threadsState: UiState<List<ChatThread>>,
  network: NetworkSnapshot,
  mqtt: MqttTelemetry,
  pendingOutboxCount: Int,
  onOpenThread: (threadId: String, peerUid: String, peerName: String) -> Unit,
  onOpenDiscover: () -> Unit,
  onOpenNetwork: () -> Unit,
  onFlushOutboxNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onOpenDiscover,
        icon = { Icon(Icons.Default.AddComment, contentDescription = "Find friend") },
        text = { Text("New SMS") },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.testTag("fab_new_sms"),
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp),
      ) {
        Spacer(modifier = Modifier.height(10.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column {
            Text(
              text = "PulseSMS",
              style = MaterialTheme.typography.headlineLarge,
              color = MaterialTheme.colorScheme.onBackground,
            )
            if (myProfile != null) {
              Text(
                text = "${myProfile.accountName} · ${myProfile.phoneNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(999.dp),
          ) {
            Text(
              text = "BASIC MODE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        ConnectionPulseStrip(
          network = network,
          mqtt = mqtt,
          pendingOutboxCount = pendingOutboxCount,
          onClick = onOpenNetwork,
        )

        if (pendingOutboxCount > 0) {
          Spacer(modifier = Modifier.height(10.dp))
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
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
                Icon(
                  imageVector = Icons.Default.Schedule,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onTertiaryContainer,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = "$pendingOutboxCount SMS queued locally — auto-sends on signal",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
              }
              TextButton(onClick = onFlushOutboxNow) {
                Icon(Icons.Default.Refresh, contentDescription = "Retry now", modifier = Modifier.size(16.dp))
                Text("Retry")
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (threadsState) {
          is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          is UiState.Error -> {
            Card(
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                  text = "Unable to sync threads",
                  style = MaterialTheme.typography.titleMedium,
                  color = MaterialTheme.colorScheme.error,
                )
                Text(text = threadsState.message, style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
          is UiState.Success -> {
            val threads = threadsState.data
            if (threads.isEmpty()) {
              Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                  modifier = Modifier.padding(24.dp),
                ) {
                  Box(
                    modifier =
                      Modifier.size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                  ) {
                    Icon(
                      imageVector = Icons.Default.ChatBubbleOutline,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onPrimaryContainer,
                      modifier = Modifier.size(30.dp),
                    )
                  }
                  Text(
                    text = "No SMS threads yet",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                  )
                  Text(
                    text = "Search by phone number or account name to message anyone in any district on tiny data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Button(
                    onClick = onOpenDiscover,
                    modifier = Modifier.height(48.dp).testTag("empty_find_friend_button"),
                  ) {
                    Text("Find a Friend")
                  }
                }
              }
            } else {
              LazyColumn(
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                items(threads, key = { it.id }) { thread ->
                  val peerUid = thread.otherUserId(currentUserId)
                  val peerName = thread.otherUserName(currentUserId)
                  ThreadCard(
                    thread = thread,
                    peerName = peerName,
                    isFromMe = thread.lastSenderId == currentUserId,
                    onClick = { onOpenThread(thread.id, peerUid, peerName) },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ThreadCard(
  thread: ChatThread,
  peerName: String,
  isFromMe: Boolean,
  onClick: () -> Unit,
) {
  val timeStr =
    if (thread.lastMessageAtMillis > 0L) {
      SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(thread.lastMessageAtMillis))
    } else "Now"

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.medium,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .testTag("thread_card_${thread.id}"),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier.size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = peerName.take(1).uppercase(),
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = peerName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text =
            if (isFromMe) "You: ${thread.lastMessagePreview}"
            else thread.lastMessagePreview,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
