package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.brew.BrewPhase
import com.example.ui.BrewLabViewModel
import com.example.ui.screens.BrewScreen
import com.example.ui.screens.DeviceSettingsScreen
import com.example.ui.screens.DiagnosticsScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.RecipeEditorScreen
import com.example.ui.screens.RecipesScreen
import com.example.ui.screens.SessionDetailScreen
import com.example.ui.theme.BrewLabTheme

object BrewRoutes {
  const val HOME = "home"
  const val BREW = "brew"
  const val RECIPES = "recipes"
  const val HISTORY = "history"
  const val RECIPE_EDIT = "recipe_edit?recipeId={recipeId}"
  const val SESSION_DETAIL = "session_detail/{sessionId}"
  const val DEVICE_SETTINGS = "device_settings"
  const val DIAGNOSTICS = "diagnostics"

  fun recipeEdit(recipeId: String? = null): String =
    if (recipeId == null) "recipe_edit" else "recipe_edit?recipeId=$recipeId"

  fun sessionDetail(sessionId: String): String = "session_detail/$sessionId"
}

private data class TopLevelDestination(
  val route: String,
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

private val TopLevelDestinations = listOf(
  TopLevelDestination(
    route = BrewRoutes.HOME,
    label = "首頁",
    selectedIcon = Icons.Filled.Home,
    unselectedIcon = Icons.Outlined.Home,
    testTag = "nav_home",
  ),
  TopLevelDestination(
    route = BrewRoutes.BREW,
    label = "沖煮",
    selectedIcon = Icons.Filled.Coffee,
    unselectedIcon = Icons.Outlined.Coffee,
    testTag = "nav_brew",
  ),
  TopLevelDestination(
    route = BrewRoutes.RECIPES,
    label = "配方",
    selectedIcon = Icons.AutoMirrored.Filled.ListAlt,
    unselectedIcon = Icons.AutoMirrored.Outlined.ListAlt,
    testTag = "nav_recipes",
  ),
  TopLevelDestination(
    route = BrewRoutes.HISTORY,
    label = "紀錄",
    selectedIcon = Icons.Filled.History,
    unselectedIcon = Icons.Outlined.History,
    testTag = "nav_history",
  ),
)

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      BrewLabTheme {
        BrewLabRoot()
      }
    }
  }

  override fun onStart() {
    super.onStart()
    (application as BrewLabApplication).brewCoordinator.onAppReturnedToForeground()
  }

  override fun onStop() {
    super.onStop()
    (application as BrewLabApplication).brewCoordinator.onAppMovedToBackground()
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrewLabRoot(
  viewModel: BrewLabViewModel = viewModel(),
) {
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val currentRoute = currentDestination?.route

  val recipes by viewModel.recipes.collectAsStateWithLifecycle()
  val sessions by viewModel.sessions.collectAsStateWithLifecycle()
  val liveState by viewModel.liveBrewState.collectAsStateWithLifecycle()
  val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
  val deviceInfo by viewModel.deviceInfo.collectAsStateWithLifecycle()
  val selectedSourceType by viewModel.selectedSourceType.collectAsStateWithLifecycle()
  val smoothingWindow by viewModel.smoothingWindow.collectAsStateWithLifecycle()
  val demoNoiseG by viewModel.demoNoiseG.collectAsStateWithLifecycle()
  val demoSimConfig by viewModel.demoSimConfig.collectAsStateWithLifecycle()
  val diagnostics by viewModel.diagnosticsState.collectAsStateWithLifecycle()

  val isTopLevel = TopLevelDestinations.any { dest ->
    currentDestination?.hierarchy?.any { it.route == dest.route } == true
  }

  val screenTitle = when {
    currentRoute == BrewRoutes.HOME -> "BrewLab"
    currentRoute == BrewRoutes.BREW -> "即時沖煮"
    currentRoute == BrewRoutes.RECIPES -> "配方管理"
    currentRoute == BrewRoutes.HISTORY -> "沖煮紀錄"
    currentRoute?.startsWith("recipe_edit") == true -> "編輯配方"
    currentRoute?.startsWith("session_detail") == true -> "沖煮報告"
    currentRoute == BrewRoutes.DEVICE_SETTINGS -> "裝置設定"
    currentRoute == BrewRoutes.DIAGNOSTICS -> "系統診斷"
    else -> "BrewLab"
  }

  fun navigateTopLevel(route: String) {
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useRail = maxWidth >= 600.dp

    Row(modifier = Modifier.fillMaxSize()) {
      if (useRail && isTopLevel) {
        NavigationRail(
          containerColor = MaterialTheme.colorScheme.surface,
        ) {
          TopLevelDestinations.forEach { dest ->
            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
            NavigationRailItem(
              selected = selected,
              onClick = { navigateTopLevel(dest.route) },
              icon = {
                Icon(
                  imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                  contentDescription = dest.label,
                )
              },
              label = { Text(dest.label) },
              modifier = Modifier.testTag(dest.testTag),
            )
          }
        }
      }

      Scaffold(
        modifier = Modifier.weight(1f),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
          TopAppBar(
            title = { Text(screenTitle) },
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.background,
              titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
            navigationIcon = {
              if (!isTopLevel) {
                IconButton(
                  onClick = { navController.popBackStack() },
                  modifier = Modifier.testTag("topbar_back_button"),
                ) {
                  Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
              }
            },
            actions = {
              IconButton(
                onClick = {
                  if (currentRoute != BrewRoutes.DIAGNOSTICS) {
                    navController.navigate(BrewRoutes.DIAGNOSTICS) { launchSingleTop = true }
                  }
                },
                modifier = Modifier.testTag("topbar_diagnostics_button"),
              ) {
                Icon(Icons.Filled.MonitorHeart, contentDescription = "系統診斷")
              }
              IconButton(
                onClick = {
                  if (currentRoute != BrewRoutes.DEVICE_SETTINGS) {
                    navController.navigate(BrewRoutes.DEVICE_SETTINGS) { launchSingleTop = true }
                  }
                },
                modifier = Modifier.testTag("topbar_device_settings_button"),
              ) {
                Icon(Icons.Filled.Tune, contentDescription = "裝置設定")
              }
            },
          )
        },
        bottomBar = {
          if (!useRail && isTopLevel) {
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
            ) {
              TopLevelDestinations.forEach { dest ->
                val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                NavigationBarItem(
                  selected = selected,
                  onClick = { navigateTopLevel(dest.route) },
                  icon = {
                    Icon(
                      imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                      contentDescription = dest.label,
                    )
                  },
                  label = { Text(dest.label) },
                  modifier = Modifier.testTag(dest.testTag),
                )
              }
            }
          }
        },
      ) { innerPadding ->
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        ) {
          NavHost(
            navController = navController,
            startDestination = BrewRoutes.HOME,
          ) {
            composable(BrewRoutes.HOME) {
              HomeScreen(
                liveState = liveState,
                deviceInfo = deviceInfo,
                connectionState = connectionState,
                selectedSourceType = selectedSourceType,
                recipes = recipes,
                recentSessions = sessions,
                onConnectOrDisconnect = {
                  if (connectionState.isConnected) viewModel.disconnectScale() else viewModel.connectScale()
                },
                onGoToBrew = { navigateTopLevel(BrewRoutes.BREW) },
                onGoToRecipes = { navigateTopLevel(BrewRoutes.RECIPES) },
                onOpenSession = { id -> navController.navigate(BrewRoutes.sessionDetail(id)) },
                onOpenDeviceSettings = { navController.navigate(BrewRoutes.DEVICE_SETTINGS) },
              )
            }

            composable(BrewRoutes.BREW) {
              BrewScreen(
                liveState = liveState,
                recipes = recipes,
                selectedSourceType = selectedSourceType,
                connectionState = connectionState,
                demoSimConfig = demoSimConfig,
                onSelectRecipe = { viewModel.selectRecipe(it) },
                onToggleReminder = { viewModel.setSetupReminderExpanded(it) },
                onConnect = { viewModel.connectScale() },
                onDisconnect = { viewModel.disconnectScale() },
                onTareBeforeStart = { viewModel.tareScaleBeforeStart() },
                onStartRecording = { viewModel.startBrewRecording() },
                onFinishRecording = { viewModel.finishBrewRecording() },
                onRecordEvent = { type, note -> viewModel.recordManualEvent(type, note) },
                onSetDemoAutoPhase = { viewModel.setDemoAutoPhase(it) },
                onSetDemoManualRate = { viewModel.setDemoManualPourRate(it) },
                onSimulateInterruption = { viewModel.simulateDemoInterruption() },
                onUpdatePendingNotes = { viewModel.updatePendingNotes(it) },
                onSaveReviewedSession = {
                  viewModel.saveReviewedSession { savedId ->
                    navController.navigate(BrewRoutes.sessionDetail(savedId))
                  }
                },
                onDiscardReviewedSession = { viewModel.discardReviewedSession() },
              )
            }

            composable(BrewRoutes.RECIPES) {
              RecipesScreen(
                recipes = recipes,
                selectedRecipeId = liveState.selectedRecipe?.id,
                isRecording = liveState.phase != BrewPhase.IDLE,
                onSelectForBrew = {
                  viewModel.selectRecipe(it)
                  navigateTopLevel(BrewRoutes.BREW)
                },
                onCreateRecipe = { navController.navigate(BrewRoutes.recipeEdit(null)) },
                onEditRecipe = { recipe -> navController.navigate(BrewRoutes.recipeEdit(recipe.id)) },
                onDuplicateRecipe = { viewModel.duplicateRecipe(it) },
                onDeleteRecipe = { viewModel.deleteRecipe(it) },
              )
            }

            composable(
              route = BrewRoutes.RECIPE_EDIT,
              arguments = listOf(
                navArgument("recipeId") {
                  type = NavType.StringType
                  nullable = true
                  defaultValue = null
                },
              ),
            ) { backStackEntry ->
              val recipeId = backStackEntry.arguments?.getString("recipeId")
              val editing = recipes.firstOrNull { it.id == recipeId }
              RecipeEditorScreen(
                initialRecipe = editing,
                validateRecipe = { viewModel.validateRecipe(it) },
                onSave = { updated, isNew ->
                  viewModel.saveRecipe(updated, isNew) {
                    navController.popBackStack()
                  }
                },
                onCancel = { navController.popBackStack() },
              )
            }

            composable(BrewRoutes.HISTORY) {
              HistoryScreen(
                sessions = sessions,
                onOpenSession = { sessionId ->
                  navController.navigate(BrewRoutes.sessionDetail(sessionId))
                },
              )
            }

            composable(
              route = BrewRoutes.SESSION_DETAIL,
              arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
              val sessionId = backStackEntry.arguments?.getString("sessionId") ?: return@composable
              val detailState by viewModel.observeSessionDetail(sessionId)
                .collectAsStateWithLifecycle(initialValue = null)
              val pair = detailState
              if (pair != null) {
                SessionDetailScreen(
                  record = pair.first,
                  analysis = pair.second,
                  onSaveNotes = { notes -> viewModel.updateSessionNotes(sessionId, notes) },
                  onDeleteSession = {
                    viewModel.deleteSession(sessionId) {
                      navController.popBackStack()
                    }
                  },
                )
              }
            }

            composable(BrewRoutes.DEVICE_SETTINGS) {
              DeviceSettingsScreen(
                selectedSourceType = selectedSourceType,
                connectionState = connectionState,
                deviceInfo = deviceInfo,
                smoothingWindow = smoothingWindow,
                demoNoiseG = demoNoiseG,
                isRecording = liveState.phase != BrewPhase.IDLE,
                futureSensors = diagnostics.futureSensors,
                onSelectSourceType = { viewModel.selectSourceType(it) },
                onSelectSmoothingWindow = { viewModel.setSmoothingWindow(it) },
                onSelectDemoNoise = { viewModel.setDemoNoise(it) },
                onConnect = { viewModel.connectScale() },
                onDisconnect = { viewModel.disconnectScale() },
              )
            }

            composable(BrewRoutes.DIAGNOSTICS) {
              DiagnosticsScreen(
                diagnostics = diagnostics,
                onSimulateInterruption = { viewModel.simulateDemoInterruption() },
              )
            }
          }
        }
      }
    }
  }
}
