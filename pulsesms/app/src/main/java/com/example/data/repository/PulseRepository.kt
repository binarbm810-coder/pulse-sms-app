package com.example.data.repository

import android.util.Base64
import com.example.data.local.CachedPhotoDao
import com.example.data.local.CachedPhotoEntity
import com.example.data.local.ContactDao
import com.example.data.local.ContactRecordEntity
import com.example.data.local.DirectoryDao
import com.example.data.local.DirectoryEntity
import com.example.data.local.MessageDao
import com.example.data.local.MessageEntity
import com.example.data.local.OutboxDao
import com.example.data.local.OutboxMessageEntity
import com.example.data.local.ThreadDao
import com.example.data.local.ThreadEntity
import com.example.data.media.CompressedPhotoResult
import com.example.data.model.ChatMessage
import com.example.data.model.ChatThread
import com.example.data.model.ContactEntry
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserDirectoryEntry
import com.example.data.model.UserProfile
import com.example.data.network.MqttConnectionState
import com.example.data.network.MqttInboundNotice
import com.example.data.network.MqttLiteClient
import com.example.data.network.NetworkMonitor
import com.example.data.preferences.LocalSessionState
import com.example.data.preferences.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class PulseRepository(
  private val settingsRepository: SettingsRepository,
  private val directoryDao: DirectoryDao,
  private val contactDao: ContactDao,
  private val threadDao: ThreadDao,
  private val messageDao: MessageDao,
  private val outboxDao: OutboxDao,
  private val cachedPhotoDao: CachedPhotoDao,
  private val mqttClient: MqttLiteClient,
  private val networkMonitor: NetworkMonitor,
) {
  private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val flushMutex = Mutex()
  @Volatile private var activeProfile: UserProfile? = null

  init {
    repoScope.launch {
      settingsRepository.sessionState.collect { state ->
        activeProfile = (state as? LocalSessionState.Active)?.profile
      }
    }

    // Flush queued offline SMS the exact millisecond MQTT finishes connecting on data return
    repoScope.launch {
      mqttClient.telemetry.collect { telemetry ->
        val profile = activeProfile
        if (telemetry.state == MqttConnectionState.CONNECTED && profile != null) {
          publishMyDirectoryBeacon(profile)
          flushOutboxQueue(profile.userId)
        }
      }
    }

    // Process real-time incoming MQTT messages and compact directory discovery cards
    repoScope.launch {
      mqttClient.inboundNotices.collect { notice ->
        handleInboundMqtt(notice)
      }
    }
  }

  private suspend fun requireActiveProfile(): UserProfile {
    activeProfile?.let { return it }
    val session = settingsRepository.sessionState.first()
    return (session as? LocalSessionState.Active)?.profile
      ?: throw IllegalStateException("Please enter your Name and Phone Number first.")
  }

  suspend fun publishMyDirectoryBeacon(profile: UserProfile) {
    val sha256 = PhoneIdentityUtil.sha256Hex(profile.phoneNormalized)
    val masked = PhoneIdentityUtil.maskPhone(profile.phoneNormalized)
    val escapedName = PhoneIdentityUtil.escapeWireField(profile.accountName)
    val payload = "D|${profile.phoneNormalized}|$escapedName|$masked|$sha256"
    mqttClient.publishRetained("${MqttLiteClient.DIR_TOPIC_PREFIX}${profile.phoneNormalized}", payload, qos = 0)
  }

  suspend fun searchDirectory(query: String, currentUserId: String): Result<List<UserDirectoryEntry>> {
    val trimmed = query.trim()
    return try {
      val entities =
        when {
          trimmed.isEmpty() -> directoryDao.listDiscoverable(currentUserId)
          PhoneIdentityUtil.isLikelyPhoneQuery(trimmed) -> {
            val hash = PhoneIdentityUtil.sha256Hex(PhoneIdentityUtil.normalizePhone(trimmed))
            directoryDao.findByPhoneHash(currentUserId, hash)
          }
          else -> directoryDao.searchByName(currentUserId, trimmed.lowercase())
        }
      Result.success(
        entities.map { entity ->
          UserDirectoryEntry(
            id = entity.userId,
            userId = entity.userId,
            accountName = entity.accountName,
            accountNameLower = entity.accountNameLower,
            phoneMasked = entity.phoneMasked,
            phoneSha256 = entity.phoneSha256,
            discoverable = true,
          )
        }
      )
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  fun observeContacts(userId: String): Flow<List<ContactEntry>> =
    contactDao.observeForOwner(userId).map { list ->
      list.map {
        ContactEntry(
          id = it.contactUid,
          userId = it.ownerUid,
          contactUid = it.contactUid,
          accountName = it.accountName,
          phoneMasked = it.phoneMasked,
          phoneSha256 = it.phoneSha256,
        )
      }
    }

  suspend fun addContact(ownerUid: String, entry: UserDirectoryEntry): Result<Unit> {
    val contactUid = entry.userId.ifBlank { entry.id }
    val normalizedDigits = PhoneIdentityUtil.normalizePhone(contactUid)
    val sha =
      entry.phoneSha256.ifBlank { PhoneIdentityUtil.sha256Hex(normalizedDigits.ifBlank { contactUid }) }
    val masked =
      entry.phoneMasked.ifBlank { PhoneIdentityUtil.maskPhone(normalizedDigits.ifBlank { contactUid }) }

    directoryDao.upsert(
      DirectoryEntity(
        userId = contactUid,
        accountName = entry.accountName,
        accountNameLower = entry.accountName.lowercase(),
        phoneMasked = masked,
        phoneSha256 = sha,
      )
    )
    contactDao.upsert(
      ContactRecordEntity(
        compositeId = "${ownerUid}_$contactUid",
        ownerUid = ownerUid,
        contactUid = contactUid,
        accountName = entry.accountName,
        phoneMasked = masked,
        phoneSha256 = sha,
      )
    )
    return Result.success(Unit)
  }

  fun observeThreads(userId: String): Flow<List<ChatThread>> =
    threadDao.observeForOwner(userId).map { list ->
      list.map { t ->
        ChatThread(
          id = t.threadId,
          participantIds = listOf(userId, t.peerUid),
          participantNames = listOf("Me", t.peerName),
          lastMessagePreview = t.lastMessagePreview,
          lastSenderId = t.lastSenderId,
          lastMessageAtMillis = t.lastMessageAtMillis,
        )
      }
    }

  fun observeMessages(threadId: String, userId: String): Flow<List<ChatMessage>> =
    messageDao.observeForThread(userId, threadId).map { list ->
      list.map { m ->
        ChatMessage(
          id = m.id,
          senderId = m.senderId,
          senderName = m.senderName,
          participantIds = listOf(userId, m.peerUid),
          type = m.type,
          textBody = m.textBody,
          transport = m.transport,
          payloadBytes = m.payloadBytes,
          photoPayloadId = m.photoPayloadId,
          photoSizeBytes = m.photoSizeBytes,
          photoWidth = m.photoWidth,
          photoHeight = m.photoHeight,
          createdAtMillis = m.createdAtMillis,
        )
      }
    }

  fun observeOutbox(threadId: String): Flow<List<OutboxMessageEntity>> =
    outboxDao.observeForThread(threadId)

  fun observeAllPendingCount(userId: String): Flow<Int> = outboxDao.observePendingCount(userId)

  fun observeCachedPhotoIds(): Flow<Set<String>> =
    cachedPhotoDao.observeCachedIds().map { it.toSet() }

  /**
   * Always writes to local Room outbox first (zero lost SMS on rural signal drop),
   * then publishes over HiveMQ MQTT immediately if connected.
   */
  suspend fun enqueueAndSendText(
    threadId: String,
    recipientUid: String,
    recipientName: String,
    senderName: String,
    text: String,
  ): Result<Unit> {
    val profile = requireActiveProfile()
    val clean = text.trim()
    if (clean.isEmpty()) return Result.success(Unit)

    val entity =
      OutboxMessageEntity(
        localId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}",
        threadId = threadId,
        senderId = profile.userId,
        senderName = senderName.take(40).ifBlank { profile.accountName },
        recipientUid = recipientUid,
        recipientName = recipientName.take(40).ifBlank { "Friend" },
        type = ChatMessage.TYPE_TEXT,
        textBody = clean.take(1000),
        status = OutboxMessageEntity.STATUS_QUEUED,
      )
    outboxDao.upsert(entity)
    if (networkMonitor.currentIsOnline()) {
      flushOutboxQueue(profile.userId)
    }
    return Result.success(Unit)
  }

  /**
   * Stages compressed <=15-24KB WebP locally, caches for instant sender preview,
   * queues in outbox, and flushes over HiveMQ MQTT.
   */
  suspend fun enqueueAndSendPhoto(
    threadId: String,
    recipientUid: String,
    recipientName: String,
    senderName: String,
    compressed: CompressedPhotoResult,
    caption: String,
  ): Result<Unit> {
    val profile = requireActiveProfile()
    val payloadId = "img_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    val msgId = "msg_${UUID.randomUUID().toString().replace("-", "").take(16)}"

    cachedPhotoDao.insert(
      CachedPhotoEntity(
        payloadId = payloadId,
        webpBytes = compressed.webpBytes,
        width = compressed.width,
        height = compressed.height,
        sizeBytes = compressed.compressedBytes,
      )
    )

    val entity =
      OutboxMessageEntity(
        localId = msgId,
        threadId = threadId,
        senderId = profile.userId,
        senderName = senderName.take(40).ifBlank { profile.accountName },
        recipientUid = recipientUid,
        recipientName = recipientName.take(40).ifBlank { "Friend" },
        type = ChatMessage.TYPE_PHOTO,
        textBody = caption.trim().take(200),
        photoPayloadId = payloadId,
        photoWebpBase64 = compressed.base64,
        photoSizeBytes = compressed.compressedBytes,
        photoWidth = compressed.width,
        photoHeight = compressed.height,
        status = OutboxMessageEntity.STATUS_QUEUED,
      )
    outboxDao.upsert(entity)
    if (networkMonitor.currentIsOnline()) {
      flushOutboxQueue(profile.userId)
    }
    return Result.success(Unit)
  }

  suspend fun flushOutboxQueue(userId: String? = null, force: Boolean = false) {
    val uid = userId ?: activeProfile?.userId ?: return
    if (!force && !networkMonitor.currentIsOnline()) return

    flushMutex.withLock {
      val pending = outboxDao.getPendingForSender(uid)
      for (item in pending) {
        if (!force && !networkMonitor.currentIsOnline()) break
        deliverOutboxItem(item)
      }
    }
  }

  private suspend fun deliverOutboxItem(item: OutboxMessageEntity) {
    outboxDao.updateStatus(item.localId, OutboxMessageEntity.STATUS_SENDING)

    // 1. If photo message, first upload the <=25KB WebP payload to its retained on-demand MQTT topic
    if (
      item.type == ChatMessage.TYPE_PHOTO &&
      !item.photoPayloadId.isNullOrBlank() &&
      !item.photoWebpBase64.isNullOrBlank()
    ) {
      val photoWire = "${item.photoWidth}|${item.photoHeight}|${item.photoSizeBytes}|${item.photoWebpBase64}"
      val photoSentBytes =
        mqttClient.publishRetained("${MqttLiteClient.PHOTO_TOPIC_PREFIX}${item.photoPayloadId}", photoWire)
      if (photoSentBytes == null) {
        outboxDao.updateAttempt(
          item.localId,
          OutboxMessageEntity.STATUS_QUEUED,
          "Waiting for MQTT link to upload photo",
        )
        return
      }
    }

    // 2. Publish tiny UTF-8 SMS or photo-notice frame to recipient's MQTT inbox
    val escapedSender = PhoneIdentityUtil.escapeWireField(item.senderName)
    val escapedBody = PhoneIdentityUtil.escapeWireField(item.textBody)
    val now = item.createdAtMillis
    val frame =
      if (item.type == ChatMessage.TYPE_PHOTO) {
        "P|${item.localId}|${item.senderId}|$escapedSender|$now|${item.photoPayloadId}|${item.photoSizeBytes}|${item.photoWidth}|${item.photoHeight}|$escapedBody"
      } else {
        "T|${item.localId}|${item.senderId}|$escapedSender|$now|$escapedBody"
      }

    val sentBytes = mqttClient.publishCompactNotice(item.recipientUid, frame)
    if (sentBytes == null) {
      outboxDao.updateAttempt(
        item.localId,
        OutboxMessageEntity.STATUS_QUEUED,
        "Waiting for MQTT connection",
      )
      return
    }

    val transportUsed =
      if (item.retryCount > 0) ChatMessage.TRANSPORT_OFFLINE_FLUSH
      else ChatMessage.TRANSPORT_MQTT

    val preview =
      if (item.type == ChatMessage.TYPE_PHOTO) {
        val kb = item.photoSizeBytes / 1024.0
        "Photo (%.1f KB)".format(kb)
      } else {
        item.textBody.take(80)
      }

    // Persist sent message locally & remove from outbox
    messageDao.upsert(
      MessageEntity(
        compositeId = "${item.senderId}_${item.localId}",
        id = item.localId,
        threadId = item.threadId,
        ownerUid = item.senderId,
        senderId = item.senderId,
        senderName = item.senderName,
        peerUid = item.recipientUid,
        type = item.type,
        textBody = item.textBody,
        transport = transportUsed,
        payloadBytes = sentBytes.toLong(),
        photoPayloadId = item.photoPayloadId,
        photoSizeBytes = if (item.type == ChatMessage.TYPE_PHOTO) item.photoSizeBytes.toLong() else null,
        photoWidth = if (item.type == ChatMessage.TYPE_PHOTO) item.photoWidth.toLong() else null,
        photoHeight = if (item.type == ChatMessage.TYPE_PHOTO) item.photoHeight.toLong() else null,
        createdAtMillis = now,
      )
    )
    threadDao.upsert(
      ThreadEntity(
        compositeId = "${item.senderId}_${item.threadId}",
        threadId = item.threadId,
        ownerUid = item.senderId,
        peerUid = item.recipientUid,
        peerName = item.recipientName,
        lastMessagePreview = preview,
        lastSenderId = item.senderId,
        lastMessageAtMillis = now,
      )
    )
    outboxDao.deleteById(item.localId)
  }

  private suspend fun handleInboundMqtt(notice: MqttInboundNotice) {
    val myUid = activeProfile?.userId
    val payload = notice.payloadText
    if (payload.isBlank()) return

    if (notice.topic.startsWith(MqttLiteClient.DIR_TOPIC_PREFIX)) {
      val parts = payload.split("|", limit = 5)
      if (parts.size == 5 && parts[0] == "D") {
        val uid = parts[1]
        val name = PhoneIdentityUtil.unescapeWireField(parts[2])
        val masked = parts[3]
        val sha256 = parts[4]
        if (uid.isNotBlank() && name.isNotBlank() && uid != myUid) {
          directoryDao.upsert(
            DirectoryEntity(
              userId = uid,
              accountName = name,
              accountNameLower = name.lowercase(),
              phoneMasked = masked,
              phoneSha256 = sha256,
            )
          )
        }
      }
      return
    }

    val ownerUid = myUid ?: return
    if (!notice.topic.startsWith("pulsesms/v1/u/$ownerUid/")) return

    when {
      payload.startsWith("T|") -> {
        val parts = payload.split("|", limit = 6)
        if (parts.size == 6) {
          val msgId = parts[1]
          val senderUid = parts[2]
          val senderName = PhoneIdentityUtil.unescapeWireField(parts[3]).ifBlank { senderUid }
          val createdMs = parts[4].toLongOrNull() ?: System.currentTimeMillis()
          val textBody = PhoneIdentityUtil.unescapeWireField(parts[5])
          val threadId = PhoneIdentityUtil.deterministicThreadId(ownerUid, senderUid)

          recordInboundDirectoryHint(senderUid, senderName)
          messageDao.upsert(
            MessageEntity(
              compositeId = "${ownerUid}_$msgId",
              id = msgId,
              threadId = threadId,
              ownerUid = ownerUid,
              senderId = senderUid,
              senderName = senderName,
              peerUid = senderUid,
              type = ChatMessage.TYPE_TEXT,
              textBody = textBody,
              transport = ChatMessage.TRANSPORT_MQTT,
              payloadBytes = notice.wireBytes.toLong(),
              createdAtMillis = createdMs,
            )
          )
          threadDao.upsert(
            ThreadEntity(
              compositeId = "${ownerUid}_$threadId",
              threadId = threadId,
              ownerUid = ownerUid,
              peerUid = senderUid,
              peerName = senderName,
              lastMessagePreview = textBody.take(80),
              lastSenderId = senderUid,
              lastMessageAtMillis = createdMs,
            )
          )
        }
      }
      payload.startsWith("P|") -> {
        val parts = payload.split("|", limit = 10)
        if (parts.size == 10) {
          val msgId = parts[1]
          val senderUid = parts[2]
          val senderName = PhoneIdentityUtil.unescapeWireField(parts[3]).ifBlank { senderUid }
          val createdMs = parts[4].toLongOrNull() ?: System.currentTimeMillis()
          val photoPayloadId = parts[5]
          val sizeBytes = parts[6].toLongOrNull() ?: 0L
          val width = parts[7].toLongOrNull() ?: 0L
          val height = parts[8].toLongOrNull() ?: 0L
          val caption = PhoneIdentityUtil.unescapeWireField(parts[9])
          val threadId = PhoneIdentityUtil.deterministicThreadId(ownerUid, senderUid)

          recordInboundDirectoryHint(senderUid, senderName)
          messageDao.upsert(
            MessageEntity(
              compositeId = "${ownerUid}_$msgId",
              id = msgId,
              threadId = threadId,
              ownerUid = ownerUid,
              senderId = senderUid,
              senderName = senderName,
              peerUid = senderUid,
              type = ChatMessage.TYPE_PHOTO,
              textBody = caption,
              transport = ChatMessage.TRANSPORT_MQTT,
              payloadBytes = notice.wireBytes.toLong(),
              photoPayloadId = photoPayloadId,
              photoSizeBytes = sizeBytes,
              photoWidth = width,
              photoHeight = height,
              createdAtMillis = createdMs,
            )
          )
          val kb = sizeBytes / 1024.0
          threadDao.upsert(
            ThreadEntity(
              compositeId = "${ownerUid}_$threadId",
              threadId = threadId,
              ownerUid = ownerUid,
              peerUid = senderUid,
              peerName = senderName,
              lastMessagePreview = "Photo (%.1f KB)".format(kb),
              lastSenderId = senderUid,
              lastMessageAtMillis = createdMs,
            )
          )
        }
      }
    }
  }

  private suspend fun recordInboundDirectoryHint(senderUid: String, senderName: String) {
    val digits = PhoneIdentityUtil.normalizePhone(senderUid).ifBlank { senderUid }
    directoryDao.upsert(
      DirectoryEntity(
        userId = senderUid,
        accountName = senderName,
        accountNameLower = senderName.lowercase(),
        phoneMasked = PhoneIdentityUtil.maskPhone(digits),
        phoneSha256 = PhoneIdentityUtil.sha256Hex(digits),
      )
    )
  }

  /**
   * Tap-to-Load Photo Handler (Strict Basic Mode):
   * 1. Checks local Room cache first -> 0 network bytes.
   * 2. Otherwise subscribes on-demand to retained MQTT photo topic, downloads <=25KB WebP once, and caches it.
   */
  suspend fun fetchPhotoOnTap(payloadId: String): Result<CachedPhotoEntity> {
    cachedPhotoDao.getById(payloadId)?.let { cached ->
      return Result.success(cached)
    }
    return mqttClient.fetchRetainedPhoto(payloadId).mapCatching { wirePayload ->
      val parts = wirePayload.split("|", limit = 4)
      require(parts.size == 4) { "Invalid photo packet" }
      val width = parts[0].toIntOrNull() ?: 320
      val height = parts[1].toIntOrNull() ?: 240
      val bytes = Base64.decode(parts[3], Base64.DEFAULT)
      val entity =
        CachedPhotoEntity(
          payloadId = payloadId,
          webpBytes = bytes,
          width = width,
          height = height,
          sizeBytes = bytes.size,
        )
      cachedPhotoDao.insert(entity)
      entity
    }
  }
}
