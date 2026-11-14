package com.example.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.OutboxMessageEntity
import com.example.data.media.CompressedPhotoResult
import com.example.data.media.WebpImageCompressor
import com.example.data.model.ChatMessage
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkSnapshot
import com.example.data.preferences.BasicModeSettings
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatDetailViewModel(
  private val repository: PulseRepository,
  settingsRepository: SettingsRepository,
  networkMonitor: NetworkMonitor,
  mqttTelemetryFlow: StateFlow<MqttTelemetry>,
  private val currentUserId: String,
  private val threadId: String,
  private val peerUid: String,
  private val peerName: String,
) : ViewModel() {

  val networkSnapshot: StateFlow<NetworkSnapshot> = networkMonitor.snapshot
  val mqttTelemetry: StateFlow<MqttTelemetry> = mqttTelemetryFlow

  private val basicSettings: StateFlow<BasicModeSettings> =
    settingsRepository.settings.stateIn(
      scope = viewModelScope,
      started = SharingStarted.Eagerly,
      initialValue = BasicModeSettings(),
    )

  val messagesState: StateFlow<UiState<List<ChatMessage>>> =
    repository
      .observeMessages(threadId, currentUserId)
      .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
      .catch { error ->
        if (error is CancellationException) throw error
        Log.w(TAG, "Error observing messages", error)
        emit(UiState.Error(error.message ?: "Failed to load messages"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val outboxMessages: StateFlow<List<OutboxMessageEntity>> =
    repository
      .observeOutbox(threadId)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList(),
      )

  val cachedPhotoIds: StateFlow<Set<String>> =
    repository
      .observeCachedPhotoIds()
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptySet(),
      )

  private val _loadedBitmaps = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
  val loadedBitmaps: StateFlow<Map<String, Bitmap>> = _loadedBitmaps.asStateFlow()

  init {
    viewModelScope.launch {
      combine(messagesState, basicSettings, networkSnapshot) { msgUi, cfg, net ->
        Triple(msgUi, cfg, net)
      }.collect { (msgUi, cfg, net) ->
        if (!cfg.strictBasicMode && net.isOnline && msgUi is UiState.Success) {
          msgUi.data.forEach { msg ->
            val pid = msg.photoPayloadId
            if (msg.type == ChatMessage.TYPE_PHOTO && !pid.isNullOrBlank()) {
              onTapLoadPhoto(pid)
            }
          }
        }
      }
    }
  }

  private val _loadingPhotoIds = MutableStateFlow<Set<String>>(emptySet())
  val loadingPhotoIds: StateFlow<Set<String>> = _loadingPhotoIds.asStateFlow()

  private val _photoErrors = MutableStateFlow<Map<String, String>>(emptyMap())
  val photoErrors: StateFlow<Map<String, String>> = _photoErrors.asStateFlow()

  private val _stagedPhoto = MutableStateFlow<CompressedPhotoResult?>(null)
  val stagedPhoto: StateFlow<CompressedPhotoResult?> = _stagedPhoto.asStateFlow()

  private val _isCompressing = MutableStateFlow(false)
  val isCompressing: StateFlow<Boolean> = _isCompressing.asStateFlow()

  private val _compressionError = MutableStateFlow<String?>(null)
  val compressionError: StateFlow<String?> = _compressionError.asStateFlow()

  fun sendText(senderName: String, text: String) {
    if (text.isBlank()) return
    viewModelScope.launch {
      repository.enqueueAndSendText(
        threadId = threadId,
        recipientUid = peerUid,
        recipientName = peerName,
        senderName = senderName,
        text = text,
      )
    }
  }

  fun compressPickedPhoto(context: Context, uri: Uri) {
    viewModelScope.launch {
      _isCompressing.value = true
      _compressionError.value = null
      val res = WebpImageCompressor.compressUriToWebp(context, uri)
      _isCompressing.value = false
      res
        .onSuccess { _stagedPhoto.value = it }
        .onFailure { _compressionError.value = it.message ?: "Failed to compress image" }
    }
  }

  fun clearStagedPhoto() {
    _stagedPhoto.value = null
    _compressionError.value = null
  }

  fun sendStagedPhoto(senderName: String, caption: String) {
    val photo = _stagedPhoto.value ?: return
    _stagedPhoto.value = null
    viewModelScope.launch {
      repository.enqueueAndSendPhoto(
        threadId = threadId,
        recipientUid = peerUid,
        recipientName = peerName,
        senderName = senderName,
        compressed = photo,
        caption = caption,
      )
    }
  }

  /**
   * Strict Basic Mode: only fetches photo payload when user taps 'View Photo (XX KB)'.
   */
  fun onTapLoadPhoto(payloadId: String) {
    if (_loadedBitmaps.value.containsKey(payloadId) || _loadingPhotoIds.value.contains(payloadId)) {
      return
    }
    viewModelScope.launch {
      _loadingPhotoIds.update { it + payloadId }
      _photoErrors.update { it - payloadId }

      val res = repository.fetchPhotoOnTap(payloadId)
      res
        .onSuccess { cached ->
          val bmp =
            withContext(Dispatchers.Default) {
              WebpImageCompressor.decodeWebpBytes(cached.webpBytes)
            }
          if (bmp != null) {
            _loadedBitmaps.update { it + (payloadId to bmp) }
          } else {
            _photoErrors.update { it + (payloadId to "Invalid WebP image") }
          }
        }
        .onFailure { err ->
          _photoErrors.update { it + (payloadId to (err.message ?: "Tap to retry")) }
        }
      _loadingPhotoIds.update { it - payloadId }
    }
  }

  fun retryOutboxNow() {
    viewModelScope.launch { repository.flushOutboxQueue(force = true) }
  }

  private companion object {
    const val TAG = "ChatDetailVM"
    const val STOP_TIMEOUT_MS = 5000L
  }
}
