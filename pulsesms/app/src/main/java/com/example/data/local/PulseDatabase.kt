package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outbox_messages")
data class OutboxMessageEntity(
  @PrimaryKey val localId: String,
  val threadId: String,
  val senderId: String,
  val senderName: String,
  val recipientUid: String,
  val recipientName: String,
  val type: String, // "text" or "photo"
  val textBody: String,
  val photoPayloadId: String? = null,
  val photoWebpBase64: String? = null,
  val photoSizeBytes: Int = 0,
  val photoWidth: Int = 0,
  val photoHeight: Int = 0,
  val status: String = STATUS_QUEUED,
  val retryCount: Int = 0,
  val lastError: String? = null,
  val createdAtMillis: Long = System.currentTimeMillis(),
) {
  companion object {
    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_SENDING = "SENDING"
    const val STATUS_FAILED = "FAILED"
  }
}

@Entity(tableName = "cached_photos")
data class CachedPhotoEntity(
  @PrimaryKey val payloadId: String,
  val webpBytes: ByteArray,
  val width: Int,
  val height: Int,
  val sizeBytes: Int,
  val cachedAtMillis: Long = System.currentTimeMillis(),
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CachedPhotoEntity) return false
    return payloadId == other.payloadId && webpBytes.contentEquals(other.webpBytes)
  }

  override fun hashCode(): Int = 31 * payloadId.hashCode() + webpBytes.contentHashCode()
}

@Entity(tableName = "directory_entries")
data class DirectoryEntity(
  @PrimaryKey val userId: String,
  val accountName: String,
  val accountNameLower: String,
  val phoneMasked: String,
  val phoneSha256: String,
  val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "contacts")
data class ContactRecordEntity(
  @PrimaryKey val compositeId: String, // "${ownerUid}_${contactUid}"
  val ownerUid: String,
  val contactUid: String,
  val accountName: String,
  val phoneMasked: String,
  val phoneSha256: String,
  val updatedAtMillis: Long = System.currentTimeMillis(),
)

@Entity(tableName = "threads")
data class ThreadEntity(
  @PrimaryKey val compositeId: String, // "${ownerUid}_${threadId}"
  val threadId: String,
  val ownerUid: String,
  val peerUid: String,
  val peerName: String,
  val lastMessagePreview: String,
  val lastSenderId: String,
  val lastMessageAtMillis: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
  @PrimaryKey val compositeId: String, // "${ownerUid}_${id}"
  val id: String,
  val threadId: String,
  val ownerUid: String,
  val senderId: String,
  val senderName: String,
  val peerUid: String,
  val type: String,
  val textBody: String,
  val transport: String,
  val payloadBytes: Long,
  val photoPayloadId: String? = null,
  val photoSizeBytes: Long? = null,
  val photoWidth: Long? = null,
  val photoHeight: Long? = null,
  val createdAtMillis: Long,
)

@Dao
interface OutboxDao {
  @Query("SELECT * FROM outbox_messages WHERE threadId = :threadId ORDER BY createdAtMillis ASC")
  fun observeForThread(threadId: String): Flow<List<OutboxMessageEntity>>

  @Query("SELECT COUNT(*) FROM outbox_messages WHERE senderId = :senderId")
  fun observePendingCount(senderId: String): Flow<Int>

  @Query("SELECT * FROM outbox_messages WHERE senderId = :senderId ORDER BY createdAtMillis ASC")
  suspend fun getPendingForSender(senderId: String): List<OutboxMessageEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(item: OutboxMessageEntity)

  @Query("UPDATE outbox_messages SET status = :status, lastError = :lastError, retryCount = retryCount + 1 WHERE localId = :localId")
  suspend fun updateAttempt(localId: String, status: String, lastError: String?)

  @Query("UPDATE outbox_messages SET status = :status WHERE localId = :localId")
  suspend fun updateStatus(localId: String, status: String)

  @Query("DELETE FROM outbox_messages WHERE localId = :localId")
  suspend fun deleteById(localId: String)
}

@Dao
interface CachedPhotoDao {
  @Query("SELECT * FROM cached_photos WHERE payloadId = :payloadId LIMIT 1")
  suspend fun getById(payloadId: String): CachedPhotoEntity?

  @Query("SELECT payloadId FROM cached_photos")
  fun observeCachedIds(): Flow<List<String>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(entity: CachedPhotoEntity)
}

@Dao
interface DirectoryDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: DirectoryEntity)

  @Query("SELECT * FROM directory_entries WHERE userId != :excludeUid ORDER BY updatedAtMillis DESC LIMIT 50")
  suspend fun listDiscoverable(excludeUid: String): List<DirectoryEntity>

  @Query("SELECT * FROM directory_entries WHERE userId != :excludeUid AND phoneSha256 = :sha256 LIMIT 10")
  suspend fun findByPhoneHash(excludeUid: String, sha256: String): List<DirectoryEntity>

  @Query("SELECT * FROM directory_entries WHERE userId != :excludeUid AND (accountNameLower LIKE '%' || :queryLower || '%' OR phoneMasked LIKE '%' || :queryLower || '%') ORDER BY accountNameLower ASC LIMIT 30")
  suspend fun searchByName(excludeUid: String, queryLower: String): List<DirectoryEntity>
}

@Dao
interface ContactDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(contact: ContactRecordEntity)

  @Query("SELECT * FROM contacts WHERE ownerUid = :ownerUid ORDER BY LOWER(accountName) ASC")
  fun observeForOwner(ownerUid: String): Flow<List<ContactRecordEntity>>
}

@Dao
interface ThreadDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(thread: ThreadEntity)

  @Query("SELECT * FROM threads WHERE ownerUid = :ownerUid ORDER BY lastMessageAtMillis DESC")
  fun observeForOwner(ownerUid: String): Flow<List<ThreadEntity>>
}

@Dao
interface MessageDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(message: MessageEntity)

  @Query("SELECT * FROM messages WHERE ownerUid = :ownerUid AND threadId = :threadId ORDER BY createdAtMillis ASC")
  fun observeForThread(ownerUid: String, threadId: String): Flow<List<MessageEntity>>
}

@Database(
  entities = [
    OutboxMessageEntity::class,
    CachedPhotoEntity::class,
    DirectoryEntity::class,
    ContactRecordEntity::class,
    ThreadEntity::class,
    MessageEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
abstract class PulseDatabase : RoomDatabase() {
  abstract fun outboxDao(): OutboxDao
  abstract fun cachedPhotoDao(): CachedPhotoDao
  abstract fun directoryDao(): DirectoryDao
  abstract fun contactDao(): ContactDao
  abstract fun threadDao(): ThreadDao
  abstract fun messageDao(): MessageDao

  companion object {
    @Volatile private var INSTANCE: PulseDatabase? = null

    fun getInstance(context: Context): PulseDatabase =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE
            ?: Room.databaseBuilder(
                context.applicationContext,
                PulseDatabase::class.java,
                "pulsesms_outbox.db",
              )
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { INSTANCE = it }
        }
  }
}
