package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatThread
import com.example.data.model.ContactEntry
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserDirectoryEntry
import com.example.data.network.MqttLiteClient
import com.example.data.network.MqttTelemetry
import com.example.data.network.NetworkMonitor
import com.example.data.network.NetworkSnapshot
import com.example.data.preferences.BasicModeSettings
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.PulseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
  data object Loading : UiState<Nothing>
  data class Success<T>(val data: T) : UiState<T>
  data class Error(val message: String) : UiState<Nothing>
}

sealed interface SearchState {
  data object Idle : SearchState
  data object Searching : SearchState
  data class Results(val items: List<UserDirectoryEntry>) : SearchState
  data class Error(val message: String) : SearchState
}

class PulseMainViewModel(
  private val repository: PulseRepository,
  private val settingsRepository: SettingsRepository,
  private val mqttClient: MqttLiteClient,
  networkMonitor: NetworkMonitor,
  private val currentUserId: String,
) : ViewModel() {

  val networkSnapshot: StateFlow<NetworkSnapshot> = networkMonitor.snapshot
  val mqttTelemetry: StateFlow<MqttTelemetry> = mqttClient.telemetry

  val settings: StateFlow<BasicModeSettings> =
    settingsRepository.settings.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
      initialValue = BasicModeSettings(),
    )

  val threadsState: StateFlow<UiState<List<ChatThread>>> =
    repository
      .observeThreads(currentUserId)
      .map<List<ChatThread>, UiState<List<ChatThread>>> { UiState.Success(it) }
      .catch { error ->
        if (error is CancellationException) throw error
        Log.w(TAG, "Error observing threads", error)
        emit(UiState.Error(error.message ?: "Failed to load chats"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val contactsState: StateFlow<UiState<List<ContactEntry>>> =
    repository
      .observeContacts(currentUserId)
      .map<List<ContactEntry>, UiState<List<ContactEntry>>> { UiState.Success(it) }
      .catch { error ->
        if (error is CancellationException) throw error
        Log.w(TAG, "Error observing contacts", error)
        emit(UiState.Error(error.message ?: "Failed to load contacts"))
      }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = UiState.Loading,
      )

  val pendingOutboxCount: StateFlow<Int> =
    repository
      .observeAllPendingCount(currentUserId)
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = 0,
      )

  private val _searchQuery = MutableStateFlow("")
  val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

  private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
  val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

  init {
    viewModelScope.launch {
      settingsRepository.settings.collect { cfg ->
        mqttClient.configureAndConnect(
          uid = currentUserId,
          host = cfg.brokerHost,
          port = cfg.brokerPort,
          useTls = cfg.useTls,
        )
      }
    }
    runSearch("")
  }

  fun onSearchQueryChanged(newQuery: String) {
    _searchQuery.value = newQuery
  }

  fun runSearch(query: String = _searchQuery.value) {
    viewModelScope.launch {
      _searchState.value = SearchState.Searching
      val res = repository.searchDirectory(query, currentUserId)
      res
        .onSuccess { _searchState.value = SearchState.Results(it) }
        .onFailure {
          _searchState.value = SearchState.Error(it.message ?: "Directory search failed")
        }
    }
  }

  fun addFriendAndOpenChat(
    entry: UserDirectoryEntry,
    onReady: (threadId: String, peerUid: String, peerName: String) -> Unit,
  ) {
    viewModelScope.launch {
      repository.addContact(currentUserId, entry)
      val peerUid = entry.userId.ifBlank { entry.id }
      val threadId = PhoneIdentityUtil.deterministicThreadId(currentUserId, peerUid)
      onReady(threadId, peerUid, entry.accountName)
    }
  }

  fun addPhoneDirectlyAndOpenChat(
    rawPhone: String,
    onReady: (threadId: String, peerUid: String, peerName: String) -> Unit,
  ) {
    val normalized = PhoneIdentityUtil.normalizePhone(rawPhone)
    if (normalized.length < 6 || normalized == currentUserId) return
    val entry =
      UserDirectoryEntry(
        id = normalized,
        userId = normalized,
        accountName = rawPhone.trim(),
        accountNameLower = rawPhone.trim().lowercase(),
        phoneMasked = PhoneIdentityUtil.maskPhone(normalized),
        phoneSha256 = PhoneIdentityUtil.sha256Hex(normalized),
      )
    addFriendAndOpenChat(entry, onReady)
  }

  fun flushOutboxNow() {
    viewModelScope.launch { repository.flushOutboxQueue(currentUserId, force = true) }
  }

  fun sendMqttPing() {
    viewModelScope.launch { mqttClient.sendPingNow() }
  }

  fun reconnectMqtt() {
    mqttClient.reconnectNow()
  }

  fun setStrictBasicMode(enabled: Boolean) {
    viewModelScope.launch { settingsRepository.setStrictBasicMode(enabled) }
  }

  fun saveBrokerConfig(host: String, port: Int, useTls: Boolean) {
    viewModelScope.launch { settingsRepository.saveMqttConfig(host, port, useTls) }
  }

  override fun onCleared() {
    super.onCleared()
    mqttClient.disconnect()
  }

  private companion object {
    const val TAG = "PulseMainVM"
    const val STOP_TIMEOUT_MS = 5000L
  }
}
