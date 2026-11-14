package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.data.model.ContactEntry
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserDirectoryEntry
import com.example.ui.viewmodel.SearchState
import com.example.ui.viewmodel.UiState

@Composable
fun DiscoverPeopleScreen(
  currentUserId: String,
  searchQuery: String,
  searchState: SearchState,
  contactsState: UiState<List<ContactEntry>>,
  onQueryChange: (String) -> Unit,
  onRunSearch: () -> Unit,
  onAddAndChat: (UserDirectoryEntry) -> Unit,
  onDirectPhoneChat: (String) -> Unit,
  onOpenSavedContact: (ContactEntry) -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.widthIn(max = 680.dp).fillMaxSize().padding(horizontal = 16.dp),
      ) {
        Spacer(modifier = Modifier.height(10.dp))

        Text(
          text = "Find Friends",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = "Enter an exact phone number or account name. Searches fetch < 2 KB.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            placeholder = { Text("Phone number or name…") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onRunSearch() }),
            modifier = Modifier.weight(1f).testTag("friend_search_input"),
          )
          Button(
            onClick = onRunSearch,
            modifier = Modifier.height(54.dp).testTag("friend_search_button"),
            shape = MaterialTheme.shapes.small,
          ) {
            Text("Find")
          }
        }

        if (PhoneIdentityUtil.isLikelyPhoneQuery(searchQuery)) {
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "SHA-256 phone lookup active (exact digits match)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
          contentPadding = PaddingValues(bottom = 32.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item {
            Text(
              text = "DIRECTORY RESULTS",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          when (searchState) {
            is SearchState.Idle -> {
              item {
                Text(
                  text = "Tap Find to browse active accounts or search by phone number.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            is SearchState.Searching -> {
              item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
              }
            }
            is SearchState.Error -> {
              item {
                Text(
                  text = searchState.message,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
            is SearchState.Results -> {
              if (searchState.items.isEmpty()) {
                item {
                  Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = "No matching live MQTT directory card yet. Enter a valid phone number above to message anyone directly!",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(14.dp),
                    )
                  }
                }
              } else {
                items(searchState.items, key = { "dir_${it.userId.ifBlank { it.id }}" }) { entry ->
                  DirectoryPersonCard(entry = entry, onAddAndChat = { onAddAndChat(entry) })
                }
              }
            }
          }

          val normalizedQueryPhone = PhoneIdentityUtil.normalizePhone(searchQuery)
          val alreadyListed =
            (searchState as? SearchState.Results)?.items?.any { it.userId == normalizedQueryPhone } == true
          if (
            PhoneIdentityUtil.isLikelyPhoneQuery(searchQuery) &&
            normalizedQueryPhone != currentUserId &&
            !alreadyListed
          ) {
            item {
              Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(14.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Message ${searchQuery.trim()}",
                      style = MaterialTheme.typography.titleMedium,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                      text = "Direct MQTT delivery · Queues if peer is offline",
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                  }
                  Button(
                    onClick = { onDirectPhoneChat(searchQuery) },
                    modifier = Modifier.height(48.dp).testTag("direct_phone_chat_button"),
                    shape = RoundedCornerShape(10.dp),
                  ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Text")
                  }
                }
              }
            }
          }

          if (contactsState is UiState.Success && contactsState.data.isNotEmpty()) {
            item {
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "SAVED CONTACTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            items(contactsState.data, key = { "contact_${it.contactUid}" }) { contact ->
              SavedContactCard(contact = contact, onClick = { onOpenSavedContact(contact) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DirectoryPersonCard(
  entry: UserDirectoryEntry,
  onAddAndChat: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier.size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = entry.accountName.take(1).uppercase(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = entry.accountName, style = MaterialTheme.typography.titleMedium)
        Text(
          text = entry.phoneMasked,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Button(
        onClick = onAddAndChat,
        modifier = Modifier.height(48.dp).testTag("add_chat_button_${entry.userId}"),
        shape = RoundedCornerShape(10.dp),
      ) {
        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Chat")
      }
    }
  }
}

@Composable
private fun SavedContactCard(
  contact: ContactEntry,
  onClick: () -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    shape = MaterialTheme.shapes.medium,
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .testTag("saved_contact_${contact.contactUid}"),
  ) {
    Row(
      modifier = Modifier.padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(
        modifier =
          Modifier.size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = contact.accountName.take(1).uppercase(),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(text = contact.accountName, style = MaterialTheme.typography.titleMedium)
        Text(
          text = contact.phoneMasked,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.Chat,
        contentDescription = "Open chat",
        tint = MaterialTheme.colorScheme.primary,
      )
    }
  }
}
