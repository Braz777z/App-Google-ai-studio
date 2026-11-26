package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.compiler.ApkCompilerClient
import com.example.data.StudioSection
import com.example.data.ThemePreference
import com.example.notifications.StudioNotificationHelper
import com.example.ui.StudioViewModel
import com.example.ui.components.CompileApkDialog
import com.example.ui.components.ImmersiveFloatingPill
import com.example.ui.components.KeystoreVaultDialog
import com.example.ui.components.PinShortcutDialog
import com.example.ui.components.StudioBottomBar
import com.example.ui.components.StudioErrorOverlay
import com.example.ui.components.StudioNavigationRail
import com.example.ui.components.StudioProgressBar
import com.example.ui.components.StudioWebView
import com.example.ui.components.WorkspaceSheet
import com.example.ui.theme.StudioPortalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StudioPortalRoot()
        }
    }
}

@Composable
fun StudioPortalRoot(
    viewModel: StudioViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    var askedNotificationOnStartup by remember { mutableStateOf(false) }

    var pendingKsPass by remember { mutableStateOf("") }
    var pendingKeyAlias by remember { mutableStateOf("") }
    var pendingKeyPass by remember { mutableStateOf("") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            viewModel.showStatusMessage(context.getString(R.string.notification_permission_denied))
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    readBoundedBytesFromUri(context, uri, maxBytes = 100L * 1024L * 1024L, defaultName = "projet.zip")
                }
                loaded.onSuccess { (name, bytes) ->
                    viewModel.onProjectZipLoaded(bytes, name)
                }.onFailure { err ->
                    viewModel.onProjectZipLoadError(err.message ?: "Impossible de lire le fichier .zip.")
                }
            }
        }
    }

    val keystorePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    readBoundedBytesFromUri(context, uri, maxBytes = 5L * 1024L * 1024L, defaultName = "keystore.jks")
                }
                loaded.onSuccess { (name, bytes) ->
                    viewModel.onKeystoreLoaded(
                        bytes = bytes,
                        fileName = name,
                        storePassword = pendingKsPass,
                        alias = pendingKeyAlias,
                        keyPassword = pendingKeyPass
                    )
                }.onFailure { err ->
                    viewModel.showStatusMessage(err.message ?: "Impossible de lire le keystore.")
                }
            }
        }
    }

    val apkSaveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val compiled = uiState.pendingCompiledApk
        if (uri != null && compiled != null) {
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(compiled.apkBytes) }
                        true
                    }.getOrDefault(false)
                }
                if (ok) {
                    viewModel.clearPendingCompiledApk()
                    viewModel.showStatusMessage(
                        context.getString(R.string.compile_apk_saved, compiled.fileName)
                    )
                }
            }
        }
    }

    var pendingExportJks by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val jksExportSafLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-pkcs12")
    ) { uri ->
        val pending = pendingExportJks
        pendingExportJks = null
        if (uri != null && pending != null) {
            coroutineScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(pending.second) }
                        true
                    }.getOrDefault(false)
                }
                if (ok) {
                    viewModel.showStatusMessage(
                        context.getString(R.string.vault_exported_jks, pending.first)
                    )
                }
            }
        }
    }

    LaunchedEffect(uiState.isReady, uiState.preferences.notificationsEnabled) {
        if (
            uiState.isReady &&
            uiState.preferences.notificationsEnabled &&
            !askedNotificationOnStartup &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !StudioNotificationHelper.hasNotificationPermission(context)
        ) {
            askedNotificationOnStartup = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val isDarkTheme = when (uiState.preferences.themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        val message = uiState.statusBannerMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearStatusMessage()
        }
    }

    BackHandler(enabled = uiState.canGoBack) {
        viewModel.requestGoBack()
    }

    BackHandler(enabled = !uiState.canGoBack && uiState.preferences.isImmersiveMode) {
        viewModel.setImmersiveMode(false)
    }

    StudioPortalTheme(themePreference = uiState.preferences.themePreference) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            val useNavigationRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    )
                },
                bottomBar = {
                    if (!useNavigationRail) {
                        AnimatedVisibility(
                            visible = !uiState.preferences.isImmersiveMode && !isImeVisible,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            StudioBottomBar(
                                activeSection = uiState.activeSection,
                                isWorkspaceOpen = uiState.isWorkspaceSheetOpen,
                                onSelectSection = viewModel::navigateToSection,
                                onOpenWorkspace = { viewModel.setWorkspaceSheetOpen(true) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useNavigationRail && !uiState.preferences.isImmersiveMode) {
                        StudioNavigationRail(
                            activeSection = uiState.activeSection,
                            isWorkspaceOpen = uiState.isWorkspaceSheetOpen,
                            onSelectSection = viewModel::navigateToSection,
                            onOpenWorkspace = { viewModel.setWorkspaceSheetOpen(true) }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .let { mod ->
                                if (uiState.preferences.isImmersiveMode) {
                                    mod.windowInsetsPadding(WindowInsets.safeDrawing)
                                } else {
                                    mod.statusBarsPadding()
                                }
                            }
                    ) {
                        if (uiState.isReady) {
                            StudioWebView(
                                initialUrl = uiState.initialUrl,
                                isDesktopMode = uiState.preferences.isDesktopMode,
                                textZoomPercent = uiState.preferences.textZoomPercent,
                                isDarkTheme = isDarkTheme,
                                webCommands = viewModel.webCommands,
                                onPageStarted = viewModel::onPageStarted,
                                onPageFinished = viewModel::onPageFinished,
                                onProgressChanged = viewModel::onProgressChanged,
                                onNavigationStateChanged = viewModel::onNavigationStateChanged,
                                onMainFrameError = viewModel::onMainFrameError,
                                onAgentThinkingStarted = viewModel::onAgentThinkingStarted,
                                onAgentThinkingFinished = viewModel::onAgentThinkingFinished,
                                onFeedbackMessage = viewModel::showStatusMessage
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }

                        StudioProgressBar(
                            isLoading = uiState.isLoading,
                            progress = uiState.loadingProgress,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )

                        if (uiState.hasError) {
                            StudioErrorOverlay(
                                errorDescription = uiState.errorDescription,
                                onRetry = viewModel::requestReload,
                                onGoHome = { viewModel.navigateToSection(StudioSection.STUDIO) }
                            )
                        }

                        ImmersiveFloatingPill(
                            visible = uiState.preferences.isImmersiveMode,
                            onExitImmersive = { viewModel.setImmersiveMode(false) },
                            onReload = viewModel::requestReload,
                            onOpenWorkspace = { viewModel.setWorkspaceSheetOpen(true) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
            }

            if (uiState.isWorkspaceSheetOpen) {
                WorkspaceSheet(
                    currentUrl = uiState.currentUrl,
                    canGoBack = uiState.canGoBack,
                    preferences = uiState.preferences,
                    updateCheckState = uiState.updateCheckState,
                    onDismiss = { viewModel.setWorkspaceSheetOpen(false) },
                    onGoBack = {
                        viewModel.requestGoBack()
                        viewModel.setWorkspaceSheetOpen(false)
                    },
                    onReload = {
                        viewModel.requestReload()
                        viewModel.setWorkspaceSheetOpen(false)
                    },
                    onNavigateToUrl = viewModel::navigateToUrl,
                    onOpenPinDialog = {
                        viewModel.setWorkspaceSheetOpen(false)
                        viewModel.setPinDialogOpen(true)
                    },
                    onDeleteShortcut = viewModel::removePinnedShortcut,
                    onToggleDesktopMode = viewModel::setDesktopMode,
                    onToggleImmersiveMode = viewModel::setImmersiveMode,
                    onTextZoomChange = viewModel::setTextZoom,
                    onThemeChange = viewModel::setThemePreference,
                    onToggleNotifications = { enabled ->
                        viewModel.setNotificationsEnabled(enabled)
                        if (
                            enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !StudioNotificationHelper.hasNotificationPermission(context)
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onToggleNotifyOnlyBackground = viewModel::setNotifyOnlyInBackground,
                    onTestNotification = {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !StudioNotificationHelper.hasNotificationPermission(context)
                        ) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.sendTestNotification()
                        }
                    },
                    onCheckUpdates = viewModel::checkForAppUpdates,
                    onOpenUpdateRelease = { releaseUrl ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
                            )
                        }
                    },
                    onOpenCompileDialog = {
                        viewModel.setWorkspaceSheetOpen(false)
                        viewModel.setCompileDialogOpen(true)
                    },
                    onOpenVaultDialog = {
                        viewModel.setWorkspaceSheetOpen(false)
                        viewModel.setVaultDialogOpen(true)
                    },
                    onCopyCurrentLink = {
                        copyTextToClipboard(context, uiState.currentUrl)
                        viewModel.showStatusMessage(context.getString(R.string.link_copied))
                        viewModel.setWorkspaceSheetOpen(false)
                    },
                    onClearCache = {
                        viewModel.clearCache()
                        viewModel.setWorkspaceSheetOpen(false)
                    },
                    onResetGoogleSession = {
                        viewModel.resetGoogleSession()
                        viewModel.setWorkspaceSheetOpen(false)
                    }
                )
            }

            if (uiState.isCompileDialogOpen) {
                CompileApkDialog(
                    initialServerUrl = uiState.preferences.buildServerUrl,
                    selectedZip = uiState.selectedProjectZip,
                    zipError = uiState.zipSelectionError,
                    keystoreInspection = uiState.selectedKeystoreInspection,
                    savedProfiles = uiState.savedKeystoreProfiles,
                    compileState = uiState.compileState,
                    hasPendingApkSave = uiState.pendingCompiledApk != null,
                    onDismiss = { viewModel.setCompileDialogOpen(false) },
                    onPickZip = {
                        zipPickerLauncher.launch(
                            arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                        )
                    },
                    onPickKeystore = { storePass, alias, keyPass ->
                        pendingKsPass = storePass
                        pendingKeyAlias = alias
                        pendingKeyPass = keyPass
                        keystorePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onApplySavedProfile = viewModel::applySavedKeystoreProfile,
                    onInspectKeystore = { storePass, alias, keyPass ->
                        viewModel.refreshKeystoreInspection(
                            storePassword = storePass,
                            alias = alias,
                            keyPassword = keyPass
                        )
                    },
                    onCopyServerScript = {
                        copyTextToClipboard(context, ApkCompilerClient.BUILD_RUNNER_INFO)
                        viewModel.showStatusMessage(context.getString(R.string.compile_script_copied))
                    },
                    onCompile = { serverUrl, storePass, keyAlias, keyPass, rememberKeystore ->
                        viewModel.compileSignedApk(
                            serverUrl = serverUrl,
                            storePassword = storePass,
                            keyAlias = keyAlias,
                            keyPassword = keyPass,
                            rememberKeystore = rememberKeystore
                        ) { compiled ->
                            coroutineScope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    saveFileToDownloads(
                                        context = context,
                                        fileName = compiled.fileName,
                                        mimeType = "application/vnd.android.package-archive",
                                        bytes = compiled.apkBytes
                                    )
                                }
                                if (saved) {
                                    viewModel.clearPendingCompiledApk()
                                    viewModel.showStatusMessage(
                                        context.getString(R.string.compile_apk_saved, compiled.fileName)
                                    )
                                } else {
                                    apkSaveLauncher.launch(compiled.fileName)
                                }
                            }
                        }
                    },
                    onSaveApkFallback = {
                        uiState.pendingCompiledApk?.let { apkSaveLauncher.launch(it.fileName) }
                    }
                )
            }

            if (uiState.isVaultDialogOpen) {
                KeystoreVaultDialog(
                    savedProfiles = uiState.savedKeystoreProfiles,
                    draftKeystoreInspection = uiState.selectedKeystoreInspection,
                    onDismiss = { viewModel.setVaultDialogOpen(false) },
                    onPickKeystore = { storePass, alias, keyPass ->
                        pendingKsPass = storePass
                        pendingKeyAlias = alias
                        pendingKeyPass = keyPass
                        keystorePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onInspectDraftKeystore = { storePass, alias, keyPass ->
                        viewModel.refreshKeystoreInspection(
                            storePassword = storePass,
                            alias = alias,
                            keyPassword = keyPass
                        )
                    },
                    onSaveProfile = viewModel::saveKeystoreToVault,
                    onCopyField = { fieldName, value ->
                        copyTextToClipboard(context, value)
                        viewModel.showStatusMessage(
                            context.getString(R.string.vault_copied_field, fieldName)
                        )
                    },
                    onExportKeystore = { profile ->
                        viewModel.loadSavedKeystoreForExport(profile) { jksBytes ->
                            coroutineScope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    saveFileToDownloads(
                                        context = context,
                                        fileName = profile.fileName,
                                        mimeType = "application/x-pkcs12",
                                        bytes = jksBytes
                                    )
                                }
                                if (saved) {
                                    viewModel.showStatusMessage(
                                        context.getString(R.string.vault_exported_jks, profile.fileName)
                                    )
                                } else {
                                    pendingExportJks = profile.fileName to jksBytes
                                    jksExportSafLauncher.launch(profile.fileName)
                                }
                            }
                        }
                    },
                    onDeleteProfile = viewModel::deleteSavedKeystoreProfile
                )
            }

            if (uiState.isPinDialogOpen) {
                PinShortcutDialog(
                    defaultTitle = uiState.pageTitle,
                    onDismiss = { viewModel.setPinDialogOpen(false) },
                    onSave = viewModel::addPinnedShortcut
                )
            }
        }
    }
}

private fun readBoundedBytesFromUri(
    context: Context,
    uri: Uri,
    maxBytes: Long,
    defaultName: String
): Result<Pair<String, ByteArray>> = runCatching {
    val resolver = context.contentResolver
    var fileName = defaultName
    var declaredSize = -1L
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0) {
                fileName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: defaultName
            }
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                declaredSize = cursor.getLong(sizeIdx)
            }
        }
    }
    if (declaredSize > maxBytes) {
        throw IllegalArgumentException("Fichier trop volumineux (max. ${maxBytes / (1024L * 1024L)} Mo).")
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("Fichier trop volumineux (max. ${maxBytes / (1024L * 1024L)} Mo).")
            }
            out.write(buf, 0, n)
        }
        out.toByteArray()
    } ?: throw IllegalStateException("Impossible d’ouvrir le fichier sélectionné.")
    fileName to bytes
}

private fun saveFileToDownloads(
    context: Context,
    fileName: String,
    mimeType: String,
    bytes: ByteArray
): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        true
    } catch (_: Exception) {
        false
    }
}

private fun copyTextToClipboard(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Studio Portal", value))
}
