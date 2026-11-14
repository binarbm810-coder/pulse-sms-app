package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PulseDatabase
import com.example.data.media.WebpImageCompressor
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserDirectoryEntry
import com.example.data.network.MqttLiteClient
import com.example.data.network.NetworkMonitor
import com.example.data.preferences.LocalSessionState
import com.example.data.preferences.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PulseRepositoryTest {

  private lateinit var context: Context
  private lateinit var db: PulseDatabase
  private lateinit var settingsRepository: SettingsRepository
  private lateinit var networkMonitor: NetworkMonitor
  private lateinit var mqttClient: MqttLiteClient
  private lateinit var repository: PulseRepository

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db =
      Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    settingsRepository = SettingsRepository(context)
    networkMonitor = NetworkMonitor(context)
    mqttClient = MqttLiteClient(networkMonitor)
    repository =
      PulseRepository(
        settingsRepository = settingsRepository,
        directoryDao = db.directoryDao(),
        contactDao = db.contactDao(),
        threadDao = db.threadDao(),
        messageDao = db.messageDao(),
        outboxDao = db.outboxDao(),
        cachedPhotoDao = db.cachedPhotoDao(),
        mqttClient = mqttClient,
        networkMonitor = networkMonitor,
      )
  }

  @After
  fun tearDown() {
    mqttClient.disconnect()
    db.close()
  }

  @Test
  fun saveLocalProfile_and_directorySearch_succeedsWithoutGoogleAuth() = runBlocking {
    val badName = settingsRepository.saveLocalProfile("A", "+91 98765 43210")
    assertTrue(badName.isFailure)

    val badPhone = settingsRepository.saveLocalProfile("Asha Village", "123")
    assertTrue(badPhone.isFailure)

    val alice = settingsRepository.saveLocalProfile("Asha Village", "+91 98765 43210").getOrThrow()
    assertEquals("919876543210", alice.userId)
    val state = settingsRepository.sessionState.first { it is LocalSessionState.Active }
    assertEquals("Asha Village", (state as LocalSessionState.Active).profile.accountName)

    repository.addContact(
      ownerUid = alice.userId,
      entry =
        UserDirectoryEntry(
          id = "919811122233",
          userId = "919811122233",
          accountName = "Rohan City",
          accountNameLower = "rohan city",
          phoneMasked = PhoneIdentityUtil.maskPhone("919811122233"),
          phoneSha256 = PhoneIdentityUtil.sha256Hex("919811122233"),
        ),
    )

    val byPhone = repository.searchDirectory("+91 98111 22233", alice.userId).getOrThrow()
    assertTrue(byPhone.any { it.userId == "919811122233" && it.accountName == "Rohan City" })

    val byName = repository.searchDirectory("rohan", alice.userId).getOrThrow()
    assertTrue(byName.any { it.userId == "919811122233" })
  }

  @Test
  fun offlineOutbox_and_compressedWebpCache_workLocally() = runBlocking {
    val alice = settingsRepository.saveLocalProfile("Asha Village", "+91 98765 43210").getOrThrow()
    val peerUid = "919822233344"
    val threadId = PhoneIdentityUtil.deterministicThreadId(alice.userId, peerUid)

    repository.enqueueAndSendText(threadId, peerUid, "Bob", alice.accountName, "Hello from village!").getOrThrow()

    val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    Canvas(bmp).apply {
      drawColor(Color.rgb(11, 79, 74))
      drawCircle(320f, 240f, 120f, Paint().apply { color = Color.rgb(52, 211, 153) })
    }
    val compressed = WebpImageCompressor.compressBitmapToWebp(bmp)
    bmp.recycle()
    assertTrue(compressed.compressedBytes <= WebpImageCompressor.HARD_CEILING_BYTES)

    repository.enqueueAndSendPhoto(threadId, peerUid, "Bob", alice.accountName, compressed, "Tower").getOrThrow()

    val queued = db.outboxDao().getPendingForSender(alice.userId)
    assertEquals(2, queued.size)
    val photoOutbox = queued.first { it.type == "photo" }
    val cached = repository.fetchPhotoOnTap(requireNotNull(photoOutbox.photoPayloadId)).getOrThrow()
    assertEquals(compressed.compressedBytes, cached.sizeBytes)
  }

  @Test
  fun mqttPackets_and_wireEscaping_areLightweightAndLossless() {
    val connect = MqttLiteClient.buildConnectPacket("psms_919876543210", 45, cleanSession = false)
    val publish = MqttLiteClient.buildPublishPacket("pulsesms/v1/u/9198/sms", "T|1|Hi".toByteArray(), 1, 1, retain = true)
    val unsub = MqttLiteClient.buildUnsubscribePacket(2, "pulsesms/v1/photo/img1")

    assertTrue(connect.size < 45)
    assertTrue((publish[0].toInt() and 0x01) == 1) // retain bit set
    assertTrue(unsub.isNotEmpty())

    val raw = "Hello | village\nline2\\end"
    assertEquals(raw, PhoneIdentityUtil.unescapeWireField(PhoneIdentityUtil.escapeWireField(raw)))
  }
}
