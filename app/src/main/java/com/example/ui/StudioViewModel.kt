package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.R
import com.example.compiler.ApkCompileState
import com.example.compiler.ApkCompilerClient
import com.example.compiler.CompiledApkResult
import com.example.compiler.InspectedProjectZip
import com.example.compiler.KeystoreInspection
import com.example.compiler.KeystoreVault
import com.example.compiler.SavedKeystoreProfile
import com.example.data.PinnedShortcut
import com.example.data.StudioPreferences
import com.example.data.StudioPreferencesRepository
import com.example.data.StudioSection
import com.example.data.ThemePreference
import com.example.notifications.StudioNotificationHelper
import com.example.update.AppUpdateChecker
import com.example.update.UpdateCheckState
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface WebCommand {
    data class LoadUrl(val url: String) : WebCommand
    data object Reload : WebCommand
    data object GoBack : WebCommand
    data object GoForward : WebCommand
    data object ClearCache : WebCommand
    data object ResetGoogleSession : WebCommand
}

data class StudioUiState(
    val isReady: Boolean = false,
    val initialUrl: String = StudioSection.STUDIO.url,
    val currentUrl: String = StudioSection.STUDIO.url,
    val pageTitle: String = "Google AI Studio",
    val isLoading: Boolean = true,
    val loadingProgress: Int = 10,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val hasError: Boolean = false,
    val errorDescription: String? = null,
    val activeSection: StudioSection = StudioSection.STUDIO,
    val preferences: StudioPreferences = StudioPreferences(),
    val isWorkspaceSheetOpen: Boolean = false,
    val isPinDialogOpen: Boolean = false,
    val isCompileDialogOpen: Boolean = false,
    val isVaultDialogOpen: Boolean = false,
    val savedKeystoreProfiles: List<SavedKeystoreProfile> = emptyList(),
    val selectedProjectZip: InspectedProjectZip? = null,
    val zipSelectionError: String? = null,
    val selectedKeystoreInspection: KeystoreInspection? = null,
    val compileState: ApkCompileState = ApkCompileState.Idle,
    val pendingCompiledApk: CompiledApkResult? = null,
    val isAgentThinking: Boolean = false,
    val updateCheckState: UpdateCheckState = UpdateCheckState.Idle,
    val statusBannerMessage: String? = null
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = StudioPreferencesRepository(application.applicationContext)

    private val internalState = MutableStateFlow(StudioUiState())

    private var selectedZipBytes: ByteArray? = null
    private var selectedKeystoreBytes: ByteArray? = null

    @Volatile
    private var isAppInForeground: Boolean = true

    val uiState: StateFlow<StudioUiState> = combine(
        internalState,
        repository.preferencesFlow
    ) { state, prefs ->
        state.copy(
            preferences = prefs,
            activeSection = StudioSection.matchFromUrl(state.currentUrl) ?: state.activeSection
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StudioUiState()
    )

    private val _webCommands = MutableSharedFlow<WebCommand>(extraBufferCapacity = 16)
    val webCommands: SharedFlow<WebCommand> = _webCommands.asSharedFlow()

    init {
        StudioNotificationHelper.ensureNotificationChannel(application.applicationContext)
        viewModelScope.launch {
            val initialPrefs = repository.preferencesFlow.first()
            val savedProfiles = KeystoreVault.listProfiles(application.applicationContext)
            internalState.update {
                it.copy(
                    isReady = true,
                    initialUrl = initialPrefs.lastUrl,
                    currentUrl = initialPrefs.lastUrl,
                    activeSection = StudioSection.matchFromUrl(initialPrefs.lastUrl) ?: StudioSection.STUDIO,
                    savedKeystoreProfiles = savedProfiles
                )
            }
        }
    }

    fun setAppForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
    }

    fun navigateToSection(section: StudioSection) {
        internalState.update {
            it.copy(
                activeSection = section,
                hasError = false,
                errorDescription = null,
                isWorkspaceSheetOpen = false,
                isAgentThinking = false
            )
        }
        _webCommands.tryEmit(WebCommand.LoadUrl(section.url))
    }

    fun navigateToUrl(url: String) {
        internalState.update {
            it.copy(
                hasError = false,
                errorDescription = null,
                isWorkspaceSheetOpen = false,
                isAgentThinking = false
            )
        }
        _webCommands.tryEmit(WebCommand.LoadUrl(url))
    }

    fun requestReload() {
        internalState.update {
            it.copy(hasError = false, errorDescription = null, isAgentThinking = false)
        }
        _webCommands.tryEmit(WebCommand.Reload)
    }

    fun requestGoBack() {
        _webCommands.tryEmit(WebCommand.GoBack)
    }

    fun requestGoForward() {
        _webCommands.tryEmit(WebCommand.GoForward)
    }

    fun onPageStarted(url: String) {
        internalState.update {
            it.copy(
                currentUrl = url,
                isLoading = true,
                loadingProgress = 15,
                hasError = false,
                errorDescription = null,
                isAgentThinking = false
            )
        }
    }

    fun onPageFinished(url: String, title: String?, canGoBack: Boolean, canGoForward: Boolean) {
        internalState.update {
            it.copy(
                currentUrl = url,
                pageTitle = title?.takeIf { t -> t.isNotBlank() } ?: "Google AI Studio",
                isLoading = false,
                loadingProgress = 100,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )
        }
        viewModelScope.launch {
            repository.saveLastUrl(url)
        }
    }

    fun onProgressChanged(progress: Int) {
        internalState.update {
            it.copy(
                loadingProgress = progress,
                isLoading = progress in 1..99
            )
        }
    }

    fun onNavigationStateChanged(canGoBack: Boolean, canGoForward: Boolean, url: String?) {
        internalState.update {
            it.copy(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                currentUrl = url ?: it.currentUrl
            )
        }
    }

    fun onMainFrameError(description: String?) {
        internalState.update {
            it.copy(
                isLoading = false,
                hasError = true,
                errorDescription = description,
                isAgentThinking = false
            )
        }
    }

    fun onAgentThinkingStarted() {
        internalState.update { it.copy(isAgentThinking = true) }
    }

    fun onAgentThinkingFinished(durationSeconds: Int, preview: String?) {
        internalState.update { it.copy(isAgentThinking = false) }
        val prefs = uiState.value.preferences
        if (!prefs.notificationsEnabled) return
        if (prefs.notifyOnlyInBackground && isAppInForeground) return

        StudioNotificationHelper.showAgentFinishedNotification(
            context = getApplication<Application>().applicationContext,
            durationSeconds = durationSeconds,
            preview = preview
        )
    }

    fun sendTestNotification() {
        val ctx = getApplication<Application>().applicationContext
        if (!StudioNotificationHelper.hasNotificationPermission(ctx)) {
            showStatusMessage(ctx.getString(R.string.notification_permission_denied))
            return
        }
        StudioNotificationHelper.showAgentFinishedNotification(
            context = ctx,
            durationSeconds = 12,
            preview = "Exemple : votre code et votre analyse Gemini sont prêts."
        )
        showStatusMessage(ctx.getString(R.string.notification_test_sent))
    }

    fun setWorkspaceSheetOpen(open: Boolean) {
        internalState.update { it.copy(isWorkspaceSheetOpen = open) }
    }

    fun setPinDialogOpen(open: Boolean) {
        internalState.update { it.copy(isPinDialogOpen = open) }
    }

    fun setCompileDialogOpen(open: Boolean) {
        if (!open) {
            selectedZipBytes = null
            if (!internalState.value.isVaultDialogOpen) {
                selectedKeystoreBytes = null
            }
            internalState.update {
                it.copy(
                    isCompileDialogOpen = false,
                    selectedProjectZip = null,
                    zipSelectionError = null,
                    selectedKeystoreInspection = if (it.isVaultDialogOpen) it.selectedKeystoreInspection else null,
                    compileState = ApkCompileState.Idle,
                    pendingCompiledApk = null
                )
            }
        } else {
            internalState.update { it.copy(isCompileDialogOpen = true) }
        }
    }

    fun setVaultDialogOpen(open: Boolean) {
        if (!open) {
            if (!internalState.value.isCompileDialogOpen) {
                selectedKeystoreBytes = null
            }
            internalState.update {
                it.copy(
                    isVaultDialogOpen = false,
                    selectedKeystoreInspection = if (it.isCompileDialogOpen) it.selectedKeystoreInspection else null
                )
            }
        } else {
            internalState.update {
                it.copy(isVaultDialogOpen = true, selectedKeystoreInspection = null)
            }
            selectedKeystoreBytes = null
        }
    }

    fun onProjectZipLoaded(bytes: ByteArray, fileName: String) {
        viewModelScope.launch {
            val res = withContext(Dispatchers.Default) {
                ApkCompilerClient.inspectProjectZip(bytes, fileName)
            }
            res.onSuccess { inspected ->
                selectedZipBytes = bytes
                internalState.update {
                    it.copy(
                        selectedProjectZip = inspected,
                        zipSelectionError = null,
                        compileState = ApkCompileState.Idle
                    )
                }
            }.onFailure { err ->
                selectedZipBytes = null
                internalState.update {
                    it.copy(
                        selectedProjectZip = null,
                        zipSelectionError = err.message ?: "Archive ZIP invalide."
                    )
                }
            }
        }
    }

    fun onProjectZipLoadError(message: String) {
        selectedZipBytes = null
        internalState.update {
            it.copy(selectedProjectZip = null, zipSelectionError = message)
        }
    }

    fun onKeystoreLoaded(
        bytes: ByteArray,
        fileName: String,
        storePassword: String,
        alias: String,
        keyPassword: String
    ) {
        selectedKeystoreBytes = bytes
        refreshKeystoreInspection(fileName, storePassword, alias, keyPassword)
    }

    fun refreshKeystoreInspection(
        explicitFileName: String? = null,
        storePassword: String,
        alias: String,
        keyPassword: String
    ) {
        val bytes = selectedKeystoreBytes ?: return
        val name = explicitFileName
            ?: internalState.value.selectedKeystoreInspection?.fileName
            ?: "keystore.jks"
        val inspection = ApkCompilerClient.inspectKeystore(
            bytes = bytes,
            fileName = name,
            storePassword = storePassword,
            alias = alias,
            keyPassword = keyPassword
        )
        internalState.update { it.copy(selectedKeystoreInspection = inspection) }
    }

    fun applySavedKeystoreProfile(profile: SavedKeystoreProfile) {
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val res = KeystoreVault.loadKeystoreBytes(ctx, profile.id)
            res.onSuccess { bytes ->
                onKeystoreLoaded(
                    bytes = bytes,
                    fileName = profile.fileName,
                    storePassword = profile.storePassword,
                    alias = profile.keyAlias,
                    keyPassword = profile.keyPassword
                )
            }.onFailure { err ->
                showStatusMessage(err.message ?: "Impossible de déchiffrer le keystore.")
            }
        }
    }

    fun saveKeystoreToVault(
        label: String,
        storePassword: String,
        keyAlias: String,
        keyPassword: String
    ) {
        val bytes = selectedKeystoreBytes ?: return
        val fileName = internalState.value.selectedKeystoreInspection?.fileName ?: "keystore.jks"
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val res = KeystoreVault.saveProfile(
                context = ctx,
                label = label,
                fileName = fileName,
                keystoreBytes = bytes,
                storePassword = storePassword,
                keyAlias = keyAlias,
                keyPassword = keyPassword
            )
            res.onSuccess { saved ->
                val updatedList = KeystoreVault.listProfiles(ctx)
                selectedKeystoreBytes = null
                internalState.update {
                    it.copy(
                        savedKeystoreProfiles = updatedList,
                        selectedKeystoreInspection = null
                    )
                }
                showStatusMessage(ctx.getString(R.string.vault_saved_banner, saved.label))
            }.onFailure { err ->
                showStatusMessage(err.message ?: "Impossible d’enregistrer le keystore.")
            }
        }
    }

    fun deleteSavedKeystoreProfile(profileId: String) {
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            KeystoreVault.deleteProfile(ctx, profileId)
            val updatedList = KeystoreVault.listProfiles(ctx)
            internalState.update { it.copy(savedKeystoreProfiles = updatedList) }
            showStatusMessage(ctx.getString(R.string.vault_deleted_banner))
        }
    }

    fun loadSavedKeystoreForExport(
        profile: SavedKeystoreProfile,
        onReady: (ByteArray) -> Unit
    ) {
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            KeystoreVault.loadKeystoreBytes(ctx, profile.id)
                .onSuccess(onReady)
                .onFailure { err ->
                    showStatusMessage(err.message ?: "Impossible d’exporter le keystore.")
                }
        }
    }

    fun compileSignedApk(
        serverUrl: String,
        storePassword: String,
        keyAlias: String,
        keyPassword: String,
        rememberKeystore: Boolean,
        onApkCompiled: (CompiledApkResult) -> Unit
    ) {
        val zipBytes = selectedZipBytes
        val zipMeta = internalState.value.selectedProjectZip
        val ksBytes = selectedKeystoreBytes
        val ksMeta = internalState.value.selectedKeystoreInspection
        if (zipBytes == null || zipMeta == null) {
            internalState.update {
                it.copy(compileState = ApkCompileState.Error("Veuillez d’abord importer le fichier .zip du projet."))
            }
            return
        }
        if (ksBytes == null || ksMeta == null) {
            internalState.update {
                it.copy(compileState = ApkCompileState.Error("Veuillez importer votre fichier keystore (.jks / .p12)."))
            }
            return
        }

        val cleanServerUrl = serverUrl.trim()
        internalState.update {
            it.copy(
                compileState = ApkCompileState.Compiling("Envoi du projet et compilation Gradle en cours…"),
                pendingCompiledApk = null
            )
        }

        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            repository.setBuildServerUrl(cleanServerUrl)
            if (rememberKeystore) {
                KeystoreVault.saveProfile(
                    context = ctx,
                    label = ksMeta.fileName.substringBeforeLast('.'),
                    fileName = ksMeta.fileName,
                    keystoreBytes = ksBytes,
                    storePassword = storePassword,
                    keyAlias = keyAlias,
                    keyPassword = keyPassword
                )
                val updatedList = KeystoreVault.listProfiles(ctx)
                internalState.update { it.copy(savedKeystoreProfiles = updatedList) }
            }
            val result = ApkCompilerClient.compileSignedApk(
                serverUrl = cleanServerUrl,
                zipBytes = zipBytes,
                zipFileName = zipMeta.fileName,
                keystoreBytes = ksBytes,
                keystoreFileName = ksMeta.fileName,
                storePassword = storePassword,
                keyAlias = keyAlias,
                keyPassword = keyPassword
            )
            result.onSuccess { compiled ->
                internalState.update {
                    it.copy(
                        compileState = ApkCompileState.Success(
                            fileName = compiled.fileName,
                            sizeBytes = compiled.apkBytes.size.toLong()
                        ),
                        pendingCompiledApk = compiled
                    )
                }
                onApkCompiled(compiled)
            }.onFailure { err ->
                internalState.update {
                    it.copy(
                        compileState = ApkCompileState.Error(
                            err.message ?: "Erreur pendant la compilation."
                        )
                    )
                }
            }
        }
    }

    fun clearPendingCompiledApk() {
        internalState.update { it.copy(pendingCompiledApk = null) }
    }

    fun toggleDesktopMode() {
        val next = !uiState.value.preferences.isDesktopMode
        viewModelScope.launch {
            repository.setDesktopMode(next)
        }
    }

    fun setDesktopMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setDesktopMode(enabled)
        }
    }

    fun setImmersiveMode(enabled: Boolean) {
        viewModelScope.launch {
            repository.setImmersiveMode(enabled)
        }
    }

    fun setTextZoom(percent: Int) {
        viewModelScope.launch {
            repository.setTextZoom(percent)
        }
    }

    fun setThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            repository.setThemePreference(theme)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.setNotificationsEnabled(enabled)
        }
    }

    fun setNotifyOnlyInBackground(bgOnly: Boolean) {
        viewModelScope.launch {
            repository.setNotifyOnlyInBackground(bgOnly)
        }
    }

    fun setUpdateRepoSlug(slug: String) {
        viewModelScope.launch {
            repository.setUpdateRepoSlug(slug)
        }
        internalState.update { it.copy(updateCheckState = UpdateCheckState.Idle) }
    }

    fun checkForAppUpdates(repoInput: String) {
        val trimmed = repoInput.trim()
        viewModelScope.launch {
            repository.setUpdateRepoSlug(trimmed)
            internalState.update { it.copy(updateCheckState = UpdateCheckState.Checking) }
            val result = AppUpdateChecker.checkLatestGitHubRelease(
                rawRepo = trimmed,
                currentVersion = BuildConfig.VERSION_NAME
            )
            internalState.update { it.copy(updateCheckState = result) }
        }
    }

    fun addPinnedShortcut(title: String) {
        val cleanTitle = title.trim()
        val url = uiState.value.currentUrl
        if (cleanTitle.isBlank() || !url.startsWith("https://")) return
        val shortcut = PinnedShortcut(
            id = UUID.randomUUID().toString(),
            title = cleanTitle,
            url = url,
            createdAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            repository.addPinnedShortcut(shortcut)
            internalState.update { it.copy(isPinDialogOpen = false) }
        }
    }

    fun removePinnedShortcut(id: String) {
        viewModelScope.launch {
            repository.removePinnedShortcut(id)
        }
    }

    fun clearCache() {
        _webCommands.tryEmit(WebCommand.ClearCache)
    }

    fun resetGoogleSession() {
        _webCommands.tryEmit(WebCommand.ResetGoogleSession)
    }

    fun showStatusMessage(message: String) {
        internalState.update { it.copy(statusBannerMessage = message) }
    }

    fun clearStatusMessage() {
        internalState.update { it.copy(statusBannerMessage = null) }
    }
}
