package com.example.data.network

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

enum class MqttConnectionState {
  OFFLINE,
  CONNECTING,
  CONNECTED,
  SIGNAL_WAIT,
}

data class MqttTelemetry(
  val state: MqttConnectionState = MqttConnectionState.OFFLINE,
  val brokerHost: String = DEFAULT_BROKER_HOST,
  val brokerPort: Int = DEFAULT_TLS_PORT,
  val useTls: Boolean = true,
  val txBytes: Long = 0L,
  val rxBytes: Long = 0L,
  val packetsSent: Int = 0,
  val packetsReceived: Int = 0,
  val lastPingRttMs: Long? = null,
  val subscribedTopic: String = "",
  val lastError: String? = null,
) {
  companion object {
    const val DEFAULT_BROKER_HOST = "broker.hivemq.com"
    const val DEFAULT_TLS_PORT = 8883
    const val DEFAULT_TCP_PORT = 1883
  }
}

data class MqttInboundNotice(
  val topic: String,
  val payloadText: String,
  val wireBytes: Int,
)

/**
 * Ultra-lightweight pure Kotlin MQTT v3.1.1 client over TLS/TCP.
 * - Persistent session (cleanSession = false) so HiveMQ queues QoS-1 SMS while rural data is offline
 * - 2-byte PINGREQ / PINGRESP keepalive
 * - Retained topics for directory presence & on-demand tap-to-load 15KB WebP photo payloads
 */
class MqttLiteClient(
  private val networkMonitor: NetworkMonitor,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeMutex = Mutex()
  private val packetIdGen = AtomicInteger(1)
  private val pendingPhotoFetches = ConcurrentHashMap<String, CompletableDeferred<String>>()

  private val _telemetry = MutableStateFlow(MqttTelemetry())
  val telemetry: StateFlow<MqttTelemetry> = _telemetry.asStateFlow()

  private val _inboundNotices = MutableSharedFlow<MqttInboundNotice>(extraBufferCapacity = 64)
  val inboundNotices: SharedFlow<MqttInboundNotice> = _inboundNotices.asSharedFlow()

  private var socket: Socket? = null
  private var outputStream: OutputStream? = null
  private var readerJob: Job? = null
  private var pingJob: Job? = null
  private var connectJob: Job? = null

  private var activeUid: String? = null
  private var targetHost: String = MqttTelemetry.DEFAULT_BROKER_HOST
  private var targetPort: Int = MqttTelemetry.DEFAULT_TLS_PORT
  private var targetUseTls: Boolean = true
  private var lastPingSentAtMs: Long = 0L

  init {
    scope.launch {
      networkMonitor.snapshot.collect { snap ->
        val uid = activeUid
        if (!snap.isOnline) {
          closeSocketInternal(MqttConnectionState.SIGNAL_WAIT)
        } else if (uid != null && _telemetry.value.state != MqttConnectionState.CONNECTED) {
          ensureConnected(uid, targetHost, targetPort, targetUseTls)
        }
      }
    }
  }

  fun configureAndConnect(
    uid: String,
    host: String = targetHost,
    port: Int = targetPort,
    useTls: Boolean = targetUseTls,
  ) {
    val configChanged = host != targetHost || port != targetPort || useTls != targetUseTls || uid != activeUid
    activeUid = uid
    targetHost = host
    targetPort = port
    targetUseTls = useTls
    _telemetry.update { it.copy(brokerHost = host, brokerPort = port, useTls = useTls) }
    if (configChanged) {
      closeSocketInternal(MqttConnectionState.OFFLINE)
    }
    ensureConnected(uid, host, port, useTls)
  }

  fun disconnect() {
    activeUid = null
    connectJob?.cancel()
    closeSocketInternal(MqttConnectionState.OFFLINE)
  }

  fun reconnectNow() {
    val uid = activeUid ?: return
    closeSocketInternal(MqttConnectionState.OFFLINE)
    ensureConnected(uid, targetHost, targetPort, targetUseTls)
  }

  private fun ensureConnected(uid: String, host: String, port: Int, useTls: Boolean) {
    if (!networkMonitor.currentIsOnline()) {
      _telemetry.update { it.copy(state = MqttConnectionState.SIGNAL_WAIT, lastError = null) }
      return
    }
    if (connectJob?.isActive == true || _telemetry.value.state == MqttConnectionState.CONNECTED) return

    connectJob =
      scope.launch {
        _telemetry.update {
          it.copy(
            state = MqttConnectionState.CONNECTING,
            brokerHost = host,
            brokerPort = port,
            useTls = useTls,
            lastError = null,
          )
        }
        try {
          val newSocket = createConnectedSocket(host, port, useTls)
          val out = newSocket.getOutputStream()
          val input = DataInputStream(BufferedInputStream(newSocket.getInputStream()))
          socket = newSocket
          outputStream = out

          val clientId = "psms_${uid.take(18)}"
          val connectPacket = buildConnectPacket(clientId, keepAliveSec = 45, cleanSession = false)
          writeRaw(out, connectPacket)

          // Wait for CONNACK
          val firstByte = input.readUnsignedByte()
          val remainingLen = readRemainingLength(input)
          val payload = ByteArray(remainingLen)
          input.readFully(payload)
          recordRx(2 + remainingLen)

          val packetType = (firstByte ushr 4) and 0x0F
          if (packetType != PACKET_CONNACK || payload.size < 2 || payload[1].toInt() != 0) {
            error("MQTT CONNACK rejected (${payload.getOrNull(1)})")
          }

          val inboxTopic = "pulsesms/v1/u/$uid/#"
          val directoryTopic = "pulsesms/v1/dir/+"
          writeRaw(out, buildSubscribePacket(nextPacketId(), inboxTopic, qos = 1))
          writeRaw(out, buildSubscribePacket(nextPacketId(), directoryTopic, qos = 0))

          _telemetry.update {
            it.copy(
              state = MqttConnectionState.CONNECTED,
              subscribedTopic = inboxTopic,
              lastError = null,
            )
          }

          startReaderLoop(input)
          startKeepAliveLoop()
        } catch (e: Exception) {
          Log.w(TAG, "MQTT connect error: ${e.message}")
          closeSocketInternal(
            if (networkMonitor.currentIsOnline()) MqttConnectionState.OFFLINE
            else MqttConnectionState.SIGNAL_WAIT,
            error = e.message ?: "MQTT handshake failed",
          )
        }
      }
  }

  /**
   * Sends a compact MQTT QoS-1 PUBLISH frame (~80-200 bytes for SMS or photo notice).
   * Returns total wire bytes written, or null if offline/disconnected (caller keeps item in outbox).
   */
  suspend fun publishCompactNotice(recipientUid: String, payloadText: String): Int? =
    publishToTopic(
      topic = "pulsesms/v1/u/$recipientUid/sms",
      payloadText = payloadText,
      qos = 1,
      retain = false,
    )

  /**
   * Publishes a retained MQTT packet (used for compact directory cards & tap-to-load compressed WebP payloads).
   */
  suspend fun publishRetained(topic: String, payloadText: String, qos: Int = 1): Int? =
    publishToTopic(topic = topic, payloadText = payloadText, qos = qos, retain = true)

  private suspend fun publishToTopic(
    topic: String,
    payloadText: String,
    qos: Int,
    retain: Boolean,
  ): Int? =
    withContext(Dispatchers.IO) {
      val out = outputStream
      if (_telemetry.value.state != MqttConnectionState.CONNECTED || out == null) {
        return@withContext null
      }
      val packet =
        buildPublishPacket(
          topic = topic,
          payload = payloadText.toByteArray(Charsets.UTF_8),
          qos = qos,
          packetId = if (qos > 0) nextPacketId() else 0,
          retain = retain,
        )
      return@withContext try {
        writeRaw(out, packet)
        packet.size
      } catch (e: Exception) {
        Log.w(TAG, "MQTT publish failed: ${e.message}")
        closeSocketInternal(MqttConnectionState.SIGNAL_WAIT, e.message)
        null
      }
    }

  /**
   * Tap-to-Load Photo Fetcher:
   * Subscribes temporarily to the retained photo topic ONLY when the user taps "View Photo",
   * downloads the compressed <=25KB WebP payload once, and unsubscribes immediately.
   */
  suspend fun fetchRetainedPhoto(payloadId: String, timeoutMs: Long = 8_000L): Result<String> =
    withContext(Dispatchers.IO) {
      val out = outputStream
      if (_telemetry.value.state != MqttConnectionState.CONNECTED || out == null) {
        return@withContext Result.failure(IllegalStateException("Connect to Mobile Data / Wi-Fi to load photo"))
      }
      val topic = "$PHOTO_TOPIC_PREFIX$payloadId"
      val deferred = CompletableDeferred<String>()
      pendingPhotoFetches[payloadId] = deferred
      try {
        writeRaw(out, buildSubscribePacket(nextPacketId(), topic, qos = 1))
        val payload = withTimeout(timeoutMs) { deferred.await() }
        Result.success(payload)
      } catch (e: Exception) {
        Result.failure(IllegalStateException("Could not load photo ($payloadId): ${e.message ?: "timeout"}"))
      } finally {
        pendingPhotoFetches.remove(payloadId)
        outputStream?.let { activeOut ->
          runCatching { writeRaw(activeOut, buildUnsubscribePacket(nextPacketId(), topic)) }
        }
      }
    }

  suspend fun sendPingNow(): Boolean =
    withContext(Dispatchers.IO) {
      val out = outputStream ?: return@withContext false
      if (_telemetry.value.state != MqttConnectionState.CONNECTED) return@withContext false
      return@withContext try {
        lastPingSentAtMs = System.currentTimeMillis()
        writeRaw(out, byteArrayOf(0xC0.toByte(), 0x00)) // 2-byte PINGREQ
        true
      } catch (e: Exception) {
        closeSocketInternal(MqttConnectionState.SIGNAL_WAIT, e.message)
        false
      }
    }

  private fun startReaderLoop(input: DataInputStream) {
    readerJob?.cancel()
    readerJob =
      scope.launch {
        try {
          while (isActive) {
            val header = input.readUnsignedByte()
            val remLen = readRemainingLength(input)
            val body = ByteArray(remLen)
            if (remLen > 0) input.readFully(body)
            recordRx(1 + remainingLengthByteCount(remLen) + remLen)

            when ((header ushr 4) and 0x0F) {
              PACKET_PUBLISH -> {
                val qos = (header ushr 1) and 0x03
                if (body.size >= 2) {
                  val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                  var idx = 2 + topicLen
                  val topic = String(body, 2, topicLen.coerceAtMost(body.size - 2), Charsets.UTF_8)
                  if (qos > 0 && idx + 2 <= body.size) {
                    val pktId = ((body[idx].toInt() and 0xFF) shl 8) or (body[idx + 1].toInt() and 0xFF)
                    idx += 2
                    outputStream?.let { out ->
                      runCatching {
                        writeRaw(
                          out,
                          byteArrayOf(0x40, 0x02, (pktId ushr 8).toByte(), (pktId and 0xFF).toByte()),
                        )
                      }
                    }
                  }
                  val msgPayload =
                    if (idx <= body.size) String(body, idx, body.size - idx, Charsets.UTF_8) else ""
                  if (topic.startsWith(PHOTO_TOPIC_PREFIX)) {
                    val payloadId = topic.removePrefix(PHOTO_TOPIC_PREFIX)
                    pendingPhotoFetches[payloadId]?.complete(msgPayload)
                  } else {
                    _inboundNotices.tryEmit(MqttInboundNotice(topic, msgPayload, 2 + remLen))
                  }
                }
              }
              PACKET_PINGRESP -> {
                if (lastPingSentAtMs > 0L) {
                  val rtt = (System.currentTimeMillis() - lastPingSentAtMs).coerceAtLeast(1L)
                  _telemetry.update { it.copy(lastPingRttMs = rtt) }
                }
              }
              PACKET_PUBACK, PACKET_SUBACK, PACKET_UNSUBACK -> Unit
            }
          }
        } catch (e: Exception) {
          if (isActive) {
            closeSocketInternal(
              if (networkMonitor.currentIsOnline()) MqttConnectionState.OFFLINE
              else MqttConnectionState.SIGNAL_WAIT,
            )
          }
        }
      }
  }

  private fun startKeepAliveLoop() {
    pingJob?.cancel()
    pingJob =
      scope.launch {
        while (isActive && _telemetry.value.state == MqttConnectionState.CONNECTED) {
          delay(40_000L)
          sendPingNow()
        }
      }
  }

  private suspend fun writeRaw(out: OutputStream, bytes: ByteArray) {
    writeMutex.withLock {
      out.write(bytes)
      out.flush()
      _telemetry.update {
        it.copy(txBytes = it.txBytes + bytes.size, packetsSent = it.packetsSent + 1)
      }
    }
  }

  private fun recordRx(bytes: Int) {
    _telemetry.update {
      it.copy(rxBytes = it.rxBytes + bytes, packetsReceived = it.packetsReceived + 1)
    }
  }

  private fun closeSocketInternal(newState: MqttConnectionState, error: String? = null) {
    readerJob?.cancel()
    pingJob?.cancel()
    pendingPhotoFetches.values.forEach { it.cancel() }
    pendingPhotoFetches.clear()
    runCatching { socket?.close() }
    socket = null
    outputStream = null
    _telemetry.update { it.copy(state = newState, lastError = error) }
  }

  private fun createConnectedSocket(host: String, port: Int, useTls: Boolean): Socket {
    return if (useTls) {
      val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
      val ssl = factory.createSocket()
      ssl.connect(InetSocketAddress(host, port), 6_000)
      ssl.soTimeout = 65_000
      ssl
    } else {
      Socket().apply {
        connect(InetSocketAddress(host, port), 6_000)
        soTimeout = 65_000
      }
    }
  }

  private fun nextPacketId(): Int {
    val next = packetIdGen.getAndIncrement()
    return if (next > 65535) {
      packetIdGen.set(1)
      1
    } else {
      next
    }
  }

  companion object {
    private const val TAG = "MqttLiteClient"
    const val PHOTO_TOPIC_PREFIX = "pulsesms/v1/photo/"
    const val DIR_TOPIC_PREFIX = "pulsesms/v1/dir/"
    private const val PACKET_CONNACK = 2
    private const val PACKET_PUBLISH = 3
    private const val PACKET_PUBACK = 4
    private const val PACKET_SUBACK = 9
    private const val PACKET_UNSUBACK = 11
    private const val PACKET_PINGRESP = 13

    fun buildConnectPacket(
      clientId: String,
      keepAliveSec: Int,
      cleanSession: Boolean = false,
    ): ByteArray {
      val varHeaderAndPayload = ByteArrayOutputStream()
      writeUtf8(varHeaderAndPayload, "MQTT")
      varHeaderAndPayload.write(4) // Protocol Level 3.1.1
      varHeaderAndPayload.write(if (cleanSession) 0x02 else 0x00)
      varHeaderAndPayload.write((keepAliveSec ushr 8) and 0xFF)
      varHeaderAndPayload.write(keepAliveSec and 0xFF)
      writeUtf8(varHeaderAndPayload, clientId)

      val body = varHeaderAndPayload.toByteArray()
      val out = ByteArrayOutputStream()
      out.write(0x10)
      writeRemainingLength(out, body.size)
      out.write(body)
      return out.toByteArray()
    }

    fun buildSubscribePacket(packetId: Int, topic: String, qos: Int): ByteArray {
      val body = ByteArrayOutputStream()
      body.write((packetId ushr 8) and 0xFF)
      body.write(packetId and 0xFF)
      writeUtf8(body, topic)
      body.write(qos and 0x03)

      val payload = body.toByteArray()
      val out = ByteArrayOutputStream()
      out.write(0x82)
      writeRemainingLength(out, payload.size)
      out.write(payload)
      return out.toByteArray()
    }

    fun buildUnsubscribePacket(packetId: Int, topic: String): ByteArray {
      val body = ByteArrayOutputStream()
      body.write((packetId ushr 8) and 0xFF)
      body.write(packetId and 0xFF)
      writeUtf8(body, topic)

      val payload = body.toByteArray()
      val out = ByteArrayOutputStream()
      out.write(0xA2)
      writeRemainingLength(out, payload.size)
      out.write(payload)
      return out.toByteArray()
    }

    fun buildPublishPacket(
      topic: String,
      payload: ByteArray,
      qos: Int,
      packetId: Int,
      retain: Boolean = false,
    ): ByteArray {
      val body = ByteArrayOutputStream()
      writeUtf8(body, topic)
      if (qos > 0) {
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
      }
      body.write(payload)

      val raw = body.toByteArray()
      val out = ByteArrayOutputStream()
      val flags = (if (qos == 1) 0x02 else 0x00) or (if (retain) 0x01 else 0x00)
      out.write(0x30 or flags)
      writeRemainingLength(out, raw.size)
      out.write(raw)
      return out.toByteArray()
    }

    private fun writeUtf8(out: ByteArrayOutputStream, text: String) {
      val utf8 = text.toByteArray(Charsets.UTF_8)
      out.write((utf8.size ushr 8) and 0xFF)
      out.write(utf8.size and 0xFF)
      out.write(utf8)
    }

    private fun writeRemainingLength(out: ByteArrayOutputStream, length: Int) {
      var x = length
      do {
        var encodedByte = x % 128
        x /= 128
        if (x > 0) encodedByte = encodedByte or 128
        out.write(encodedByte)
      } while (x > 0)
    }

    private fun readRemainingLength(input: DataInputStream): Int {
      var multiplier = 1
      var value = 0
      var encodedByte: Int
      do {
        encodedByte = input.readUnsignedByte()
        value += (encodedByte and 127) * multiplier
        multiplier *= 128
      } while ((encodedByte and 128) != 0)
      return value
    }

    private fun remainingLengthByteCount(len: Int): Int =
      when {
        len < 128 -> 1
        len < 16384 -> 2
        len < 2097152 -> 3
        else -> 4
      }
  }
}
