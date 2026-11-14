package com.example.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.OutboxMessageEntity
import com.example.data.media.CompressedPhotoResult
import com.example.data.media.WebpImageCompressor
import com.example.data.model.ChatMessage
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkSnapshot
import com.example.ui.components.ConnectionPulseStrip
import com.example.ui.components.TapToLoadPhotoCard
import com.example.ui.components.formatBytesCompact
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  currentUserId: String,
  myAccountName: String,
  peerName: String,
  messagesState: UiState<List<ChatMessage>>,
  outboxMessages: List<OutboxMessageEntity>,
  cachedPhotoIds: Set<String>,
  loadedBitmaps: Map<String, Bitmap>,
  loadingPhotoIds: Set<String>,
  photoErrors: Map<String, String>,
  stagedPhoto: CompressedPhotoResult?,
  isCompressing: Boolean,
  compressionError: String?,
  network: NetworkSnapshot,
  mqtt: MqttTelemetry,
  onBack: () -> Unit,
  onSendText: (String) -> Unit,
  onPickPhotoUri: (android.content.Context, android.net.Uri) -> Unit,
  onClearStagedPhoto: () -> Unit,
  onSendStagedPhoto: (caption: String) -> Unit,
  onTapLoadPhoto: (payloadId: String) -> Unit,
  onRetryOutbox: () -> Unit,
) {
  val context = LocalContext.current
  var inputText by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  val photoPickerLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        onPickPhotoUri(context, uri)
      }
    }

  val messageCount =
    (messagesState as? UiState.Success)?.data?.size?.plus(outboxMessages.size) ?: outboxMessages.size

  LaunchedEffect(messageCount) {
    if (messageCount > 0) {
      listState.animateScrollToItem(messageCount - 1)
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets(0, 0, 0, 0),
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(text = peerName, style = MaterialTheme.typography.titleLarge)
            Text(
              text = "Basic Mode · Tap-to-Load Photos",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to chats")
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
      )
    },
    bottomBar = {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (isCompressing) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "Compressing photo to ≤15 KB WebP…",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }
        }

        if (!compressionError.isNullOrBlank()) {
          Text(
            text = compressionError,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }

        if (stagedPhoto != null) {
          StagedWebpPreviewBar(
            staged = stagedPhoto,
            onClear = onClearStagedPhoto,
            onSend = {
              onSendStagedPhoto(inputText)
              inputText = ""
            },
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          IconButton(
            onClick = {
              photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
              )
            },
            modifier = Modifier.size(48.dp).testTag("pick_photo_button"),
          ) {
            Icon(
              imageVector = Icons.Default.PhotoCamera,
              contentDescription = "Attach 15KB compressed photo",
              tint = MaterialTheme.colorScheme.primary,
            )
          }

          OutlinedTextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = {
              Text(
                if (stagedPhoto != null) "Add short caption (optional)…"
                else "Type ultra-light SMS…",
              )
            },
            maxLines = 4,
            modifier = Modifier.weight(1f).testTag("sms_input"),
            shape = RoundedCornerShape(16.dp),
          )

          Button(
            onClick = {
              if (stagedPhoto != null) {
                onSendStagedPhoto(inputText)
                inputText = ""
              } else if (inputText.isNotBlank()) {
                onSendText(inputText)
                inputText = ""
              }
            },
            enabled = stagedPhoto != null || inputText.isNotBlank(),
            modifier = Modifier.height(50.dp).testTag("send_sms_button"),
            shape = RoundedCornerShape(14.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
          ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send SMS")
          }
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(modifier = Modifier.widthIn(max = 680.dp).fillMaxSize()) {
        ConnectionPulseStrip(
          network = network,
          mqtt = mqtt,
          pendingOutboxCount = outboxMessages.size,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          onClick = onRetryOutbox,
        )

        when (messagesState) {
          is UiState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              CircularProgressIndicator()
            }
          }
          is UiState.Error -> {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
              Text(
                text = messagesState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
          is UiState.Success -> {
            val msgs = messagesState.data
            if (msgs.isEmpty() && outboxMessages.isEmpty()) {
              Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                  text = "Fast SMS box ready. Messages use ~100 B via HiveMQ MQTT and queue offline in Room automatically if signal drops.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            } else {
              LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(msgs, key = { "fs_${it.id}" }) { msg ->
                  val isMine = msg.senderId == currentUserId
                  ChatMessageBubble(
                    message = msg,
                    isMine = isMine,
                    isCachedLocally = msg.photoPayloadId?.let { cachedPhotoIds.contains(it) } == true,
                    loadedBitmap = msg.photoPayloadId?.let { loadedBitmaps[it] },
                    isLoadingPhoto = msg.photoPayloadId?.let { loadingPhotoIds.contains(it) } == true,
                    photoError = msg.photoPayloadId?.let { photoErrors[it] },
                    onTapLoadPhoto = { msg.photoPayloadId?.let(onTapLoadPhoto) },
                  )
                }

                items(outboxMessages, key = { "out_${it.localId}" }) { queued ->
                  QueuedOutboxBubble(item = queued)
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
private fun ChatMessageBubble(
  message: ChatMessage,
  isMine: Boolean,
  isCachedLocally: Boolean,
  loadedBitmap: Bitmap?,
  isLoadingPhoto: Boolean,
  photoError: String?,
  onTapLoadPhoto: () -> Unit,
) {
  val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
  val bubbleColor =
    if (isMine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
  val textColor =
    if (isMine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

  val timeLabel =
    if (message.createdAtMillis > 0L) {
      SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.createdAtMillis))
    } else "Now"

  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
    Surface(
      color = bubbleColor,
      shape =
        RoundedCornerShape(
          topStart = 16.dp,
          topEnd = 16.dp,
          bottomStart = if (isMine) 16.dp else 4.dp,
          bottomEnd = if (isMine) 4.dp else 16.dp,
        ),
      tonalElevation = if (isMine) 0.dp else 2.dp,
      modifier = Modifier.widthIn(max = 310.dp),
    ) {
      Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        if (message.type == ChatMessage.TYPE_PHOTO && !message.photoPayloadId.isNullOrBlank()) {
          TapToLoadPhotoCard(
            payloadId = message.photoPayloadId,
            sizeBytes = message.photoSizeBytes ?: 0L,
            width = message.photoWidth ?: 0L,
            height = message.photoHeight ?: 0L,
            isSender = isMine,
            isCachedLocally = isCachedLocally,
            loadedBitmap = loadedBitmap,
            isLoading = isLoadingPhoto,
            errorText = photoError,
            onTapLoad = onTapLoadPhoto,
          )
        }

        if (message.textBody.isNotBlank()) {
          Text(
            text = message.textBody,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor,
          )
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "$timeLabel · ${message.payloadBytes}B",
            style = MaterialTheme.typography.labelSmall,
            color = textColor.copy(alpha = 0.72f),
          )
          if (isMine) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = "Delivered",
              modifier = Modifier.size(12.dp),
              tint = textColor.copy(alpha = 0.8f),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun QueuedOutboxBubble(item: OutboxMessageEntity) {
  Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
    Surface(
      color = MaterialTheme.colorScheme.tertiaryContainer,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
      modifier = Modifier.widthIn(max = 300.dp).testTag("queued_bubble_${item.localId}"),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.type == ChatMessage.TYPE_PHOTO) {
          Text(
            text = "WebP Photo (${formatBytesCompact(item.photoSizeBytes.toLong())}) queued",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
        if (item.textBody.isNotBlank()) {
          Text(
            text = item.textBody,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = "Queued offline",
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
          )
          Text(
            text = "${item.status} · Auto-sends on signal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      }
    }
  }
}

@Composable
private fun StagedWebpPreviewBar(
  staged: CompressedPhotoResult,
  onClear: () -> Unit,
  onSend: () -> Unit,
) {
  val previewBmp = remember(staged.webpBytes) { WebpImageCompressor.decodeWebpBytes(staged.webpBytes) }
  val savedPct =
    if (staged.originalBytes > 0) {
      ((1.0 - staged.compressedBytes.toDouble() / staged.originalBytes.toDouble()) * 100.0)
        .coerceIn(0.0, 99.9)
    } else 95.0

  Surface(
    color = MaterialTheme.colorScheme.primaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth().testTag("staged_photo_preview"),
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (previewBmp != null) {
        Image(
          bitmap = previewBmp.asImageBitmap(),
          contentDescription = "Compressed WebP preview",
          modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
          contentScale = ContentScale.Crop,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "WebP Ready: ${formatBytesCompact(staged.compressedBytes.toLong())}",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
          text = "${staged.width}×${staged.height} · Q${staged.qualityUsed} · %.1f%% smaller".format(savedPct),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Button(onClick = onSend, modifier = Modifier.height(44.dp)) {
        Text("Send")
      }
      IconButton(onClick = onClear) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Discard compressed photo",
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }
  }
}
