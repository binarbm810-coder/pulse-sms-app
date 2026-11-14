package com.example

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.AppContainer
import com.example.data.model.PhoneIdentityUtil
import com.example.data.model.UserProfile
import com.example.data.preferences.LocalSessionState
import com.example.ui.auth.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.ChatsListScreen
import com.example.ui.screens.DiscoverPeopleScreen
import com.example.ui.screens.NetworkAndSetupScreen
import com.example.ui.theme.PulseSmsTheme
import com.example.ui.viewmodel.ChatDetailViewModel
import com.example.ui.viewmodel.PulseMainViewModel
import kotlinx.coroutines.launch

private const val ROUTE_CHATS = "chats"
private const val ROUTE_DISCOVER = "discover"
private const val ROUTE_NETWORK = "network"
private const val ROUTE_CHAT_DETAIL = "chat/{threadId}/{peerUid}/{peerName}"

private fun chatRoute(threadId: String, peerUid: String, peerName: String): String {
  val safeName = Uri.encode(peerName.ifBlank { "Friend" })
  return "chat/$threadId/$peerUid/$safeName"
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      PulseSmsTheme {
        PulseAppRoot()
      }
    }
  }
}

@Composable
fun PulseAppRoot() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val container = remember(context) { AppContainer.get(context) }
  val sessionState by
    container.settingsRepository.sessionState.collectAsStateWithLifecycle(
      initialValue = LocalSessionState.Loading
    )

  when (val state = sessionState) {
    is LocalSessionState.Loading -> {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
    }
    is LocalSessionState.SignedOut -> {
      AuthScreen(
        onStartChatting = { accountName, phoneNumber ->
          scope.launch {
            container.settingsRepository.saveLocalProfile(accountName, phoneNumber)
          }
        }
      )
    }
    is LocalSessionState.Active -> {
      val profile = state.profile
      key(profile.userId) {
        ActivePulseShell(
          myProfile = profile,
          onSignOut = {
            scope.launch {
              container.mqttClient.disconnect()
              container.settingsRepository.clearLocalProfile()
            }
          },
        )
      }
    }
  }
}

@Composable
private fun ActivePulseShell(
  myProfile: UserProfile,
  onSignOut: () -> Unit,
) {
  val currentUserId = myProfile.userId
  val mainViewModel: PulseMainViewModel =
    viewModel(
      key = currentUserId,
      factory =
        viewModelFactory {
          initializer {
            val app = checkNotNull(this[APPLICATION_KEY])
            val container = AppContainer.get(app)
            PulseMainViewModel(
              repository = container.repository,
              settingsRepository = container.settingsRepository,
              mqttClient = container.mqttClient,
              networkMonitor = container.networkMonitor,
              currentUserId = currentUserId,
            )
          }
        },
    )

  PulseMainNavigation(
    currentUserId = currentUserId,
    myProfile = myProfile,
    mainViewModel = mainViewModel,
    onSignOut = onSignOut,
  )
}

@Composable
private fun PulseMainNavigation(
  currentUserId: String,
  myProfile: UserProfile,
  mainViewModel: PulseMainViewModel,
  onSignOut: () -> Unit,
) {
  val navController = rememberNavController()
  val backStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = backStackEntry?.destination?.route

  val threadsState by mainViewModel.threadsState.collectAsStateWithLifecycle()
  val contactsState by mainViewModel.contactsState.collectAsStateWithLifecycle()
  val pendingOutboxCount by mainViewModel.pendingOutboxCount.collectAsStateWithLifecycle()
  val network by mainViewModel.networkSnapshot.collectAsStateWithLifecycle()
  val mqtt by mainViewModel.mqttTelemetry.collectAsStateWithLifecycle()
  val settings by mainViewModel.settings.collectAsStateWithLifecycle()
  val searchQuery by mainViewModel.searchQuery.collectAsStateWithLifecycle()
  val searchState by mainViewModel.searchState.collectAsStateWithLifecycle()

  val showTopLevelNav = currentRoute in setOf(ROUTE_CHATS, ROUTE_DISCOVER, ROUTE_NETWORK)

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Row(modifier = Modifier.fillMaxSize()) {
      if (useRail && showTopLevelNav) {
        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
          NavigationRailItem(
            selected = currentRoute == ROUTE_CHATS,
            onClick = {
              navController.navigate(ROUTE_CHATS) {
                popUpTo(ROUTE_CHATS) { inclusive = true }
                launchSingleTop = true
              }
            },
            icon = {
              Icon(
                if (currentRoute == ROUTE_CHATS) Icons.Filled.ChatBubble
                else Icons.Outlined.ChatBubbleOutline,
                contentDescription = stringResource(R.string.nav_chats),
              )
            },
            label = { Text(stringResource(R.string.nav_chats)) },
          )
          NavigationRailItem(
            selected = currentRoute == ROUTE_DISCOVER,
            onClick = {
              navController.navigate(ROUTE_DISCOVER) {
                popUpTo(ROUTE_CHATS)
                launchSingleTop = true
              }
            },
            icon = {
              Icon(
                if (currentRoute == ROUTE_DISCOVER) Icons.Filled.People
                else Icons.Outlined.PeopleOutline,
                contentDescription = stringResource(R.string.nav_discover),
              )
            },
            label = { Text(stringResource(R.string.nav_discover)) },
          )
          NavigationRailItem(
            selected = currentRoute == ROUTE_NETWORK,
            onClick = {
              navController.navigate(ROUTE_NETWORK) {
                popUpTo(ROUTE_CHATS)
                launchSingleTop = true
              }
            },
            icon = {
              Icon(
                if (currentRoute == ROUTE_NETWORK) Icons.Filled.SignalCellularAlt
                else Icons.Outlined.SignalCellularAlt,
                contentDescription = stringResource(R.string.nav_network),
              )
            },
            label = { Text(stringResource(R.string.nav_network)) },
          )
        }
      }

      Scaffold(
        modifier = Modifier.weight(1f).fillMaxSize(),
        bottomBar = {
          if (!useRail && showTopLevelNav) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
              NavigationBarItem(
                selected = currentRoute == ROUTE_CHATS,
                onClick = {
                  navController.navigate(ROUTE_CHATS) {
                    popUpTo(ROUTE_CHATS) { inclusive = true }
                    launchSingleTop = true
                  }
                },
                icon = {
                  Icon(
                    if (currentRoute == ROUTE_CHATS) Icons.Filled.ChatBubble
                    else Icons.Outlined.ChatBubbleOutline,
                    contentDescription = stringResource(R.string.nav_chats),
                  )
                },
                label = { Text(stringResource(R.string.nav_chats)) },
              )
              NavigationBarItem(
                selected = currentRoute == ROUTE_DISCOVER,
                onClick = {
                  navController.navigate(ROUTE_DISCOVER) {
                    popUpTo(ROUTE_CHATS)
                    launchSingleTop = true
                  }
                },
                icon = {
                  Icon(
                    if (currentRoute == ROUTE_DISCOVER) Icons.Filled.People
                    else Icons.Outlined.PeopleOutline,
                    contentDescription = stringResource(R.string.nav_discover),
                  )
                },
                label = { Text(stringResource(R.string.nav_discover)) },
              )
              NavigationBarItem(
                selected = currentRoute == ROUTE_NETWORK,
                onClick = {
                  navController.navigate(ROUTE_NETWORK) {
                    popUpTo(ROUTE_CHATS)
                    launchSingleTop = true
                  }
                },
                icon = {
                  Icon(
                    if (currentRoute == ROUTE_NETWORK) Icons.Filled.SignalCellularAlt
                    else Icons.Outlined.SignalCellularAlt,
                    contentDescription = stringResource(R.string.nav_network),
                  )
                },
                label = { Text(stringResource(R.string.nav_network)) },
              )
            }
          }
        },
      ) { innerPadding ->
        NavHost(
          navController = navController,
          startDestination = ROUTE_CHATS,
          modifier =
            if (showTopLevelNav) Modifier.fillMaxSize().padding(innerPadding)
            else Modifier.fillMaxSize(),
        ) {
          composable(ROUTE_CHATS) {
            ChatsListScreen(
              currentUserId = currentUserId,
              myProfile = myProfile,
              threadsState = threadsState,
              network = network,
              mqtt = mqtt,
              pendingOutboxCount = pendingOutboxCount,
              onOpenThread = { threadId, peerUid, peerName ->
                navController.navigate(chatRoute(threadId, peerUid, peerName))
              },
              onOpenDiscover = {
                navController.navigate(ROUTE_DISCOVER) {
                  popUpTo(ROUTE_CHATS)
                  launchSingleTop = true
                }
              },
              onOpenNetwork = {
                navController.navigate(ROUTE_NETWORK) {
                  popUpTo(ROUTE_CHATS)
                  launchSingleTop = true
                }
              },
              onFlushOutboxNow = { mainViewModel.flushOutboxNow() },
            )
          }

          composable(ROUTE_DISCOVER) {
            DiscoverPeopleScreen(
              currentUserId = currentUserId,
              searchQuery = searchQuery,
              searchState = searchState,
              contactsState = contactsState,
              onQueryChange = mainViewModel::onSearchQueryChanged,
              onRunSearch = { mainViewModel.runSearch() },
              onAddAndChat = { entry ->
                mainViewModel.addFriendAndOpenChat(entry) { threadId, peerUid, peerName ->
                  navController.navigate(chatRoute(threadId, peerUid, peerName))
                }
              },
              onDirectPhoneChat = { rawPhone ->
                mainViewModel.addPhoneDirectlyAndOpenChat(rawPhone) { threadId, peerUid, peerName ->
                  navController.navigate(chatRoute(threadId, peerUid, peerName))
                }
              },
              onOpenSavedContact = { contact ->
                val tid = PhoneIdentityUtil.deterministicThreadId(currentUserId, contact.contactUid)
                navController.navigate(chatRoute(tid, contact.contactUid, contact.accountName))
              },
            )
          }

          composable(ROUTE_NETWORK) {
            NetworkAndSetupScreen(
              myProfile = myProfile,
              network = network,
              mqtt = mqtt,
              settings = settings,
              pendingOutboxCount = pendingOutboxCount,
              onSendPing = mainViewModel::sendMqttPing,
              onReconnectMqtt = mainViewModel::reconnectMqtt,
              onFlushOutbox = mainViewModel::flushOutboxNow,
              onToggleBasicMode = mainViewModel::setStrictBasicMode,
              onSaveBrokerConfig = mainViewModel::saveBrokerConfig,
              onSignOut = onSignOut,
            )
          }

          composable(
            route = ROUTE_CHAT_DETAIL,
            arguments =
              listOf(
                navArgument("threadId") { type = NavType.StringType },
                navArgument("peerUid") { type = NavType.StringType },
                navArgument("peerName") { type = NavType.StringType },
              ),
          ) { entry ->
            val threadId = entry.arguments?.getString("threadId").orEmpty()
            val peerUid = entry.arguments?.getString("peerUid").orEmpty()
            val peerName = Uri.decode(entry.arguments?.getString("peerName") ?: "Friend")

            val chatVm: ChatDetailViewModel =
              viewModel(
                key = "${currentUserId}_$threadId",
                factory =
                  viewModelFactory {
                    initializer {
                      val app = checkNotNull(this[APPLICATION_KEY])
                      val container = AppContainer.get(app)
                      ChatDetailViewModel(
                        repository = container.repository,
                        settingsRepository = container.settingsRepository,
                        networkMonitor = container.networkMonitor,
                        mqttTelemetryFlow = container.mqttClient.telemetry,
                        currentUserId = currentUserId,
                        threadId = threadId,
                        peerUid = peerUid,
                        peerName = peerName,
                      )
                    }
                  },
              )

            val messagesState by chatVm.messagesState.collectAsStateWithLifecycle()
            val outboxMessages by chatVm.outboxMessages.collectAsStateWithLifecycle()
            val cachedPhotoIds by chatVm.cachedPhotoIds.collectAsStateWithLifecycle()
            val loadedBitmaps by chatVm.loadedBitmaps.collectAsStateWithLifecycle()
            val loadingPhotoIds by chatVm.loadingPhotoIds.collectAsStateWithLifecycle()
            val photoErrors by chatVm.photoErrors.collectAsStateWithLifecycle()
            val stagedPhoto by chatVm.stagedPhoto.collectAsStateWithLifecycle()
            val isCompressing by chatVm.isCompressing.collectAsStateWithLifecycle()
            val compressionError by chatVm.compressionError.collectAsStateWithLifecycle()

            ChatScreen(
              currentUserId = currentUserId,
              myAccountName = myProfile.accountName,
              peerName = peerName,
              messagesState = messagesState,
              outboxMessages = outboxMessages,
              cachedPhotoIds = cachedPhotoIds,
              loadedBitmaps = loadedBitmaps,
              loadingPhotoIds = loadingPhotoIds,
              photoErrors = photoErrors,
              stagedPhoto = stagedPhoto,
              isCompressing = isCompressing,
              compressionError = compressionError,
              network = network,
              mqtt = mqtt,
              onBack = { navController.popBackStack() },
              onSendText = { text -> chatVm.sendText(myProfile.accountName, text) },
              onPickPhotoUri = { ctx, uri -> chatVm.compressPickedPhoto(ctx, uri) },
              onClearStagedPhoto = chatVm::clearStagedPhoto,
              onSendStagedPhoto = { caption ->
                chatVm.sendStagedPhoto(myProfile.accountName, caption)
              },
              onTapLoadPhoto = chatVm::onTapLoadPhoto,
              onRetryOutbox = chatVm::retryOutboxNow,
            )
          }
        }
      }
    }
  }
}
