package com.example.ui.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.PhoneIdentityUtil

@Composable
fun AuthScreen(
  onStartChatting: (accountName: String, phoneNumber: String) -> Unit,
) {
  var accountName by remember { mutableStateOf("") }
  var phoneNumber by remember { mutableStateOf("") }
  var errorText by remember { mutableStateOf<String?>(null) }

  val invalidNameMsg = stringResource(R.string.error_invalid_name)
  val invalidPhoneMsg = stringResource(R.string.error_invalid_phone)

  fun submitLogin() {
    val trimmedName = accountName.trim()
    val trimmedPhone = phoneNumber.trim()
    val normalized = PhoneIdentityUtil.normalizePhone(trimmedPhone)
    if (trimmedName.length !in 2..40) {
      errorText = invalidNameMsg
      return
    }
    if (normalized.length !in 6..20) {
      errorText = invalidPhoneMsg
      return
    }
    errorText = null
    onStartChatting(trimmedName, trimmedPhone)
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 20.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier =
          Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Spacer(modifier = Modifier.height(6.dp))

        Image(
          painter = painterResource(id = R.drawable.img_pulsesms_hero),
          contentDescription = "City to rural village signal pulse illustration",
          modifier =
            Modifier.fillMaxWidth()
              .height(164.dp)
              .clip(RoundedCornerShape(20.dp)),
          contentScale = ContentScale.Crop,
        )

        Surface(
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(999.dp),
        ) {
          Text(
            text = "BASIC MODE · MQTT + SMS MESSENGER",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          )
        }

        Text(
          text = stringResource(R.string.sign_in_headline),
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )

        Text(
          text = stringResource(R.string.sign_in_subtitle),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            OutlinedTextField(
              value = accountName,
              onValueChange = {
                accountName = it
                errorText = null
              },
              label = { Text(stringResource(R.string.label_account_name)) },
              placeholder = { Text("e.g., Asha Village") },
              leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("login_name_input"),
            )

            OutlinedTextField(
              value = phoneNumber,
              onValueChange = {
                phoneNumber = it
                errorText = null
              },
              label = { Text(stringResource(R.string.label_phone_number)) },
              placeholder = { Text("e.g., +91 98765 43210") },
              supportingText = {
                Text("Friends find and message you instantly using your phone number or name.")
              },
              leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
              keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { submitLogin() }),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("login_phone_input"),
            )
          }
        }

        if (errorText != null) {
          Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            shape = MaterialTheme.shapes.small,
          ) {
            Text(
              text = errorText!!,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
          }
        }

        Button(
          onClick = { submitLogin() },
          modifier =
            Modifier.fillMaxWidth()
              .height(54.dp)
              .testTag("start_chatting_button"),
          shape = MaterialTheme.shapes.medium,
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
          Text(
            text = stringResource(R.string.btn_start_chatting),
            style = MaterialTheme.typography.labelLarge,
          )
        }

        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            FeatureRow(
              icon = Icons.Default.Bolt,
              title = "Zero-Overhead MQTT Link",
              detail = "2-byte keepalive pings & sub-150B SMS packets on Mobile Data.",
            )
            FeatureRow(
              icon = Icons.Default.Image,
              title = "15 KB WebP Tap-to-Load Photos",
              detail = "Never auto-downloads media until you tap View Photo.",
            )
            FeatureRow(
              icon = Icons.Default.CloudQueue,
              title = "Offline Outbox Queue",
              detail = "Saves unsent SMS locally and flushes the instant signal returns.",
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, detail: String) {
  Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Box(
      modifier =
        Modifier.size(34.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(18.dp),
      )
    }
    Column {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = detail,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
