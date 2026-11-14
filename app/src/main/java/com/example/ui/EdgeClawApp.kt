package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.AgentScreen
import com.example.ui.screens.ExplorerScreen
import com.example.ui.screens.ModelGalleryScreen
import com.example.ui.state.EdgeClawViewModel
import kotlinx.serialization.Serializable

@Serializable
data object ExplorerRoute

@Serializable
data object AgentRoute

@Serializable
data object ModelsRoute

private data class TopDestination(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val DESTINATIONS = listOf(
    TopDestination(
        route = ExplorerRoute,
        label = "Explorer",
        selectedIcon = Icons.Filled.FolderCopy,
        unselectedIcon = Icons.Outlined.FolderCopy,
        testTag = "nav_explorer"
    ),
    TopDestination(
        route = AgentRoute,
        label = "Agent",
        selectedIcon = Icons.Filled.Psychology,
        unselectedIcon = Icons.Outlined.Psychology,
        testTag = "nav_agent"
    ),
    TopDestination(
        route = ModelsRoute,
        label = "Models",
        selectedIcon = Icons.Filled.Memory,
        unselectedIcon = Icons.Outlined.Memory,
        testTag = "nav_models"
    )
)

@Composable
fun EdgeClawApp(viewModel: EdgeClawViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val explorerFiles by viewModel.explorerFiles.collectAsStateWithLifecycle()
    val indexedCount by viewModel.indexedCount.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val selectedCategory by viewModel.selectedCategory.collectAsStateWithLifecycle()
    val hasAllFilesAccess by viewModel.hasAllFilesAccess.collectAsStateWithLifecycle()
    val isIndexing by viewModel.isIndexing.collectAsStateWithLifecycle()
    val indexStatusMessage by viewModel.indexStatusMessage.collectAsStateWithLifecycle()

    val models by viewModel.models.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val engineSettings by viewModel.engineSettings.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()

    val agentMessages by viewModel.agentMessages.collectAsStateWithLifecycle()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val isAgentBusy by viewModel.isAgentBusy.collectAsStateWithLifecycle()
    val actionLogs by viewModel.actionLogs.collectAsStateWithLifecycle()
    val memoryRules by viewModel.memoryRules.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.uiBannerMessage.collectAsStateWithLifecycle()

    var draftAgentPrompt by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(bannerMessage) {
        bannerMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.refreshPermissions()
    }

    fun requestMediaAndNotificationPermissions() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        mediaPermissionLauncher.launch(perms.toTypedArray())
    }

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DESTINATIONS.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DESTINATIONS.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = ExplorerRoute,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable<ExplorerRoute> {
                            ExplorerScreen(
                                files = explorerFiles,
                                indexedCount = indexedCount,
                                searchQuery = searchQuery,
                                selectedCategory = selectedCategory,
                                hasAllFilesAccess = hasAllFilesAccess,
                                isIndexing = isIndexing,
                                indexStatusMessage = indexStatusMessage,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onSelectCategory = viewModel::selectCategory,
                                onStartIndexing = viewModel::startStorageIndexing,
                                onRequestMediaPermissions = ::requestMediaAndNotificationPermissions,
                                onAskAgentAboutFile = { file ->
                                    draftAgentPrompt = "Summarize and organize '${file.fileName}' (${file.filePath})"
                                    navigateTopLevel(AgentRoute)
                                },
                                onSafeTrashFile = viewModel::safeTrashSingleFile
                            )
                        }

                        composable<AgentRoute> {
                            AgentScreen(
                                messages = agentMessages,
                                pendingConfirmation = pendingConfirmation,
                                isAgentBusy = isAgentBusy,
                                telemetry = telemetry,
                                actionLogs = actionLogs,
                                memoryRules = memoryRules,
                                initialDraftPrompt = draftAgentPrompt,
                                onConsumeDraftPrompt = { draftAgentPrompt = "" },
                                onSendPrompt = viewModel::sendAgentPrompt,
                                onApproveBatch = viewModel::approvePendingAction,
                                onUndoBatch = viewModel::undoBatch,
                                onUpsertMemory = viewModel::upsertMemoryRule,
                                onDeleteMemory = viewModel::deleteMemoryRule,
                                onClearChat = viewModel::clearAgentChat,
                                onOpenModelsTab = { navigateTopLevel(ModelsRoute) }
                            )
                        }

                        composable<ModelsRoute> {
                            ModelGalleryScreen(
                                models = models,
                                telemetry = telemetry,
                                selectedDelegate = engineSettings.delegate,
                                downloadProgress = downloadProgress,
                                onSelectDelegate = viewModel::selectHardwareDelegate,
                                onDownloadModel = viewModel::downloadModel,
                                onCancelDownload = viewModel::cancelDownload,
                                onLoadModel = viewModel::loadModel,
                                onUnloadModel = viewModel::unloadModel,
                                onDeleteModel = viewModel::deleteModelFile,
                                onImportLocalUri = viewModel::importLocalModel
                            )
                        }
                    }
                }
            }
        }
    }
}
