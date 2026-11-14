package com.example.data.model

import java.security.MessageDigest

data class UserProfile(
  val userId: String = "",
  val accountName: String = "",
  val phoneNumber: String = "",
  val phoneNormalized: String = "",
  val createdAtMillis: Long = System.currentTimeMillis(),
)

data class UserDirectoryEntry(
  val id: String = "",
  val userId: String = "",
  val accountName: String = "",
  val accountNameLower: String = "",
  val phoneMasked: String = "",
  val phoneSha256: String = "",
  val discoverable: Boolean = true,
)

data class ContactEntry(
  val id: String = "",
  val userId: String = "",
  val contactUid: String = "",
  val accountName: String = "",
  val phoneMasked: String = "",
  val phoneSha256: String = "",
)

data class ChatThread(
  val id: String = "",
  val participantIds: List<String> = emptyList(),
  val participantNames: List<String> = emptyList(),
  val lastMessagePreview: String = "",
  val lastSenderId: String = "",
  val lastMessageAtMillis: Long = 0L,
) {
  fun otherUserId(myUid: String): String =
    participantIds.firstOrNull { it != myUid } ?: participantIds.firstOrNull().orEmpty()

  fun otherUserName(myUid: String): String {
    val idx = participantIds.indexOfFirst { it != myUid }
    return if (idx in participantNames.indices) participantNames[idx] else "Contact"
  }
}

data class ChatMessage(
  val id: String = "",
  val senderId: String = "",
  val senderName: String = "",
  val participantIds: List<String> = emptyList(),
  val type: String = TYPE_TEXT,
  val textBody: String = "",
  val transport: String = TRANSPORT_MQTT,
  val payloadBytes: Long = 0L,
  val photoPayloadId: String? = null,
  val photoSizeBytes: Long? = null,
  val photoWidth: Long? = null,
  val photoHeight: Long? = null,
  val createdAtMillis: Long = 0L,
) {
  companion object {
    const val TYPE_TEXT = "text"
    const val TYPE_PHOTO = "photo"
    const val TRANSPORT_MQTT = "mqtt"
    const val TRANSPORT_OFFLINE_FLUSH = "offline_flush"
  }
}

object PhoneIdentityUtil {
  fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }

  fun isLikelyPhoneQuery(query: String): Boolean {
    val trimmed = query.trim()
    val digitCount = trimmed.count { it.isDigit() }
    return digitCount >= 6 && trimmed.all { it.isDigit() || it in setOf('+', '-', ' ', '(', ')') }
  }

  fun maskPhone(normalizedDigits: String): String {
    if (normalizedDigits.length <= 4) return "•••• $normalizedDigits"
    val suffix = normalizedDigits.takeLast(4)
    return "•••• ••$suffix"
  }

  fun sha256Hex(normalizedDigits: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(normalizedDigits.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun deterministicThreadId(uidA: String, uidB: String): String {
    val sorted = listOf(uidA, uidB).sorted()
    return "${sorted[0]}_${sorted[1]}"
  }

  fun sortedParticipantsWithNames(
    uidA: String,
    nameA: String,
    uidB: String,
    nameB: String,
  ): Pair<List<String>, List<String>> {
    return if (uidA <= uidB) {
      listOf(uidA, uidB) to listOf(nameA.take(40), nameB.take(40))
    } else {
      listOf(uidB, uidA) to listOf(nameB.take(40), nameA.take(40))
    }
  }

  fun escapeWireField(value: String): String =
    value.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n").replace("\r", "")

  fun unescapeWireField(value: String): String {
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '\\' && i + 1 < value.length) {
        when (val next = value[i + 1]) {
          '\\' -> {
            sb.append('\\')
            i += 2
            continue
          }
          'p' -> {
            sb.append('|')
            i += 2
            continue
          }
          'n' -> {
            sb.append('\n')
            i += 2
            continue
          }
          else -> {
            sb.append(next)
            i += 2
            continue
          }
        }
      }
      sb.append(c)
      i++
    }
    return sb.toString()
  }
}
