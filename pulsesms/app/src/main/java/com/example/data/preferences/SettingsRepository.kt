package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserProfile
import com.example.data.network.MqttTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.pulseSettingsStore by preferencesDataStore(name = "pulsesms_settings")

data class BasicModeSettings(
  val strictBasicMode: Boolean = true,
  val brokerHost: String = MqttTelemetry.DEFAULT_BROKER_HOST,
  val brokerPort: Int = MqttTelemetry.DEFAULT_TLS_PORT,
  val useTls: Boolean = true,
)

sealed interface LocalSessionState {
  data object Loading : LocalSessionState
  data object SignedOut : LocalSessionState
  data class Active(val profile: UserProfile) : LocalSessionState
}

class SettingsRepository(context: Context) {
  private val appContext = context.applicationContext

  val settings: Flow<BasicModeSettings> =
    appContext.pulseSettingsStore.data.map { prefs ->
      BasicModeSettings(
        strictBasicMode = prefs[KEY_STRICT_BASIC_MODE] ?: true,
        brokerHost = prefs[KEY_BROKER_HOST] ?: MqttTelemetry.DEFAULT_BROKER_HOST,
        brokerPort = prefs[KEY_BROKER_PORT] ?: MqttTelemetry.DEFAULT_TLS_PORT,
        useTls = prefs[KEY_USE_TLS] ?: true,
      )
    }

  val sessionState: Flow<LocalSessionState> =
    appContext.pulseSettingsStore.data.map { prefs ->
      val uid = prefs[KEY_USER_ID].orEmpty()
      val name = prefs[KEY_ACCOUNT_NAME].orEmpty()
      val phone = prefs[KEY_PHONE_NUMBER].orEmpty()
      if (uid.isBlank() || name.isBlank() || phone.isBlank()) {
        LocalSessionState.SignedOut
      } else {
        LocalSessionState.Active(
          UserProfile(
            userId = uid,
            accountName = name,
            phoneNumber = phone,
            phoneNormalized = prefs[KEY_PHONE_NORMALIZED] ?: uid,
            createdAtMillis = prefs[KEY_CREATED_AT] ?: 0L,
          )
        )
      }
    }

  suspend fun saveLocalProfile(accountName: String, phoneNumber: String): Result<UserProfile> {
    val cleanName = accountName.trim()
    val cleanPhone = phoneNumber.trim()
    val normalized = PhoneIdentityUtil.normalizePhone(cleanPhone)
    if (cleanName.length !in 2..40) {
      return Result.failure(IllegalArgumentException("Account name must be 2–40 characters."))
    }
    if (normalized.length !in 6..20) {
      return Result.failure(IllegalArgumentException("Enter a valid phone number (6+ digits)."))
    }
    val now = System.currentTimeMillis()
    val profile =
      UserProfile(
        userId = normalized,
        accountName = cleanName,
        phoneNumber = cleanPhone,
        phoneNormalized = normalized,
        createdAtMillis = now,
      )
    appContext.pulseSettingsStore.edit { prefs ->
      prefs[KEY_USER_ID] = normalized
      prefs[KEY_ACCOUNT_NAME] = cleanName
      prefs[KEY_PHONE_NUMBER] = cleanPhone
      prefs[KEY_PHONE_NORMALIZED] = normalized
      prefs[KEY_CREATED_AT] = now
    }
    return Result.success(profile)
  }

  suspend fun clearLocalProfile() {
    appContext.pulseSettingsStore.edit { prefs ->
      prefs.remove(KEY_USER_ID)
      prefs.remove(KEY_ACCOUNT_NAME)
      prefs.remove(KEY_PHONE_NUMBER)
      prefs.remove(KEY_PHONE_NORMALIZED)
      prefs.remove(KEY_CREATED_AT)
    }
  }

  suspend fun setStrictBasicMode(enabled: Boolean) {
    appContext.pulseSettingsStore.edit { it[KEY_STRICT_BASIC_MODE] = enabled }
  }

  suspend fun saveMqttConfig(host: String, port: Int, useTls: Boolean) {
    appContext.pulseSettingsStore.edit {
      it[KEY_BROKER_HOST] = host.trim().ifBlank { MqttTelemetry.DEFAULT_BROKER_HOST }
      it[KEY_BROKER_PORT] = port
      it[KEY_USE_TLS] = useTls
    }
  }

  private companion object {
    val KEY_STRICT_BASIC_MODE = booleanPreferencesKey("strict_basic_mode")
    val KEY_BROKER_HOST = stringPreferencesKey("mqtt_broker_host")
    val KEY_BROKER_PORT = intPreferencesKey("mqtt_broker_port")
    val KEY_USE_TLS = booleanPreferencesKey("mqtt_use_tls")
    val KEY_USER_ID = stringPreferencesKey("local_user_id")
    val KEY_ACCOUNT_NAME = stringPreferencesKey("local_account_name")
    val KEY_PHONE_NUMBER = stringPreferencesKey("local_phone_number")
    val KEY_PHONE_NORMALIZED = stringPreferencesKey("local_phone_normalized")
    val KEY_CREATED_AT = longPreferencesKey("local_created_at")
  }
}
