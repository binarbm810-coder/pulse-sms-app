package com.example.data

import android.content.Context
import com.example.data.local.PulseDatabase
import com.example.data.network.MqttLiteClient
import com.example.data.network.NetworkMonitor
import com.example.data.preferences.SettingsRepository
import com.example.data.repository.PulseRepository

class AppContainer(context: Context) {
  private val appContext = context.applicationContext
  private val database = PulseDatabase.getInstance(appContext)
  val networkMonitor = NetworkMonitor(appContext)
  val mqttClient = MqttLiteClient(networkMonitor)
  val settingsRepository = SettingsRepository(appContext)

  val repository =
    PulseRepository(
      settingsRepository = settingsRepository,
      directoryDao = database.directoryDao(),
      contactDao = database.contactDao(),
      threadDao = database.threadDao(),
      messageDao = database.messageDao(),
      outboxDao = database.outboxDao(),
      cachedPhotoDao = database.cachedPhotoDao(),
      mqttClient = mqttClient,
      networkMonitor = networkMonitor,
    )

  companion object {
    @Volatile private var INSTANCE: AppContainer? = null

    fun get(context: Context): AppContainer =
      INSTANCE
        ?: synchronized(this) {
          INSTANCE ?: AppContainer(context).also { INSTANCE = it }
        }
  }
}
