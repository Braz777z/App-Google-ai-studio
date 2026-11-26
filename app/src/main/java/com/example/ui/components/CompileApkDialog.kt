package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.compiler.ApkCompileState
import com.example.compiler.InspectedProjectZip
import com.example.compiler.KeystoreInspection
import com.example.compiler.SavedKeystoreProfile

@Composable
fun CompileApkDialog(
    initialServerUrl: String,
    selectedZip: InspectedProjectZip?,
    zipError: String?,
    keystoreInspection: KeystoreInspection?,
    savedProfiles: List<SavedKeystoreProfile>,
    compileState: ApkCompileState,
    hasPendingApkSave: Boolean,
    onDismiss: () -> Unit,
    onPickZip: () -> Unit,
    onPickKeystore: (storePassword: String, alias: String, keyPassword: String) -> Unit,
    onApplySavedProfile: (SavedKeystoreProfile) -> Unit,
    onInspectKeystore: (storePassword: String, alias: String, keyPassword: String) -> Unit,
    onCopyServerScript: () -> Unit,
    onCompile: (
        serverUrl: String,
        storePassword: String,
        keyAlias: String,
        keyPassword: String,
        rememberKeystore: Boolean
    ) -> Unit,
    onSaveApkFallback: () -> Unit
) {
    var storePassword by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var serverUrl by remember(initialServerUrl) { mutableStateOf(initialServerUrl) }
    var rememberKeystore by remember { mutableStateOf(true) }

    var showStorePass by remember { mutableStateOf(false) }
    var showKeyPass by remember { mutableStateOf(false) }
    var showRunnerHelp by remember { mutableStateOf(false) }

    LaunchedEffect(keystoreInspection?.aliases) {
        val aliases = keystoreInspection?.aliases.orEmpty()
        if (keyAlias.isBlank() && aliases.size == 1) {
            keyAlias = aliases.first()
        }
    }

    val isCompiling = compileState is ApkCompileState.Compiling
    val canSubmit = selectedZip != null &&
        keystoreInspection != null &&
        storePassword.isNotEmpty() &&
        keyAlias.isNotBlank() &&
        keyPassword.isNotEmpty() &&
        serverUrl.isNotBlank() &&
        !isCompiling

    Dialog(
        onDismissRequest = { if (!isCompiling) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.compile_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isCompiling,
                        modifier = Modifier.testTag("close_compile_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }

                // 1. Project ZIP
                SectionCard(title = stringResource(R.string.compile_step_zip)) {
                    FilledTonalButton(
                        onClick = onPickZip,
                        enabled = !isCompiling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("compile_pick_zip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.compile_pick_zip))
                    }

                    if (selectedZip != null) {
                        Text(
                            text = stringResource(
                                R.string.compile_zip_summary,
                                selectedZip.fileName,
                                selectedZip.entryCount
                            ),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val metaLine = buildString {
                            append(formatReadableSize(selectedZip.sizeBytes))
                            if (!selectedZip.detectedAppId.isNullOrBlank()) {
                                append(" • ")
                                append(selectedZip.detectedAppId)
                            }
                        }
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (selectedZip.isValidAndroidProject) {
                                stringResource(R.string.compile_zip_valid)
                            } else {
                                stringResource(R.string.compile_zip_invalid)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedZip.isValidAndroidProject) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    } else if (!zipError.isNullOrBlank()) {
                        Text(
                            text = zipError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.compile_zip_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. Android Studio-style Keystore signing fields
                SectionCard(title = stringResource(R.string.compile_step_signing)) {
                    if (savedProfiles.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.compile_saved_keystores_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            savedProfiles.forEach { profile ->
                                AssistChip(
                                    onClick = {
                                        storePassword = profile.storePassword
                                        keyAlias = profile.keyAlias
                                        keyPassword = profile.keyPassword
                                        rememberKeystore = false
                                        onApplySavedProfile(profile)
                                    },
                                    label = { Text("${profile.label} (${profile.keyAlias})") },
                                    modifier = Modifier.testTag("saved_keystore_chip_${profile.id}")
                                )
                            }
                        }
                    }

                    FilledTonalButton(
                        onClick = { onPickKeystore(storePassword, keyAlias, keyPassword) },
                        enabled = !isCompiling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("compile_pick_keystore_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.compile_pick_keystore))
                    }

                    if (keystoreInspection != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (keystoreInspection.isLocallyVerified) {
                                Icon(
                                    imageVector = Icons.Outlined.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "${keystoreInspection.fileName} (${formatReadableSize(keystoreInspection.sizeBytes)})",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = keystoreInspection.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.compile_keystore_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = storePassword,
                        onValueChange = {
                            storePassword = it
                            if (keystoreInspection != null) {
                                onInspectKeystore(it, keyAlias, keyPassword)
                            }
                        },
                        label = { Text(stringResource(R.string.compile_store_password)) },
                        singleLine = true,
                        enabled = !isCompiling,
                        visualTransformation = if (showStorePass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showStorePass = !showStorePass }) {
                                Icon(
                                    imageVector = if (showStorePass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compile_store_password_input")
                    )

                    OutlinedTextField(
                        value = keyAlias,
                        onValueChange = {
                            keyAlias = it
                            if (keystoreInspection != null) {
                                onInspectKeystore(storePassword, it, keyPassword)
                            }
                        },
                        label = { Text(stringResource(R.string.compile_key_alias)) },
                        singleLine = true,
                        enabled = !isCompiling,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compile_key_alias_input")
                    )

                    val detectedAliases = keystoreInspection?.aliases.orEmpty()
                    if (detectedAliases.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            detectedAliases.forEach { aliasItem ->
                                AssistChip(
                                    onClick = {
                                        keyAlias = aliasItem
                                        onInspectKeystore(storePassword, aliasItem, keyPassword)
                                    },
                                    label = { Text(aliasItem) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = keyPassword,
                        onValueChange = {
                            keyPassword = it
                            if (keystoreInspection != null) {
                                onInspectKeystore(storePassword, keyAlias, it)
                            }
                        },
                        label = { Text(stringResource(R.string.compile_key_password)) },
                        singleLine = true,
                        enabled = !isCompiling,
                        visualTransformation = if (showKeyPass) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKeyPass = !showKeyPass }) {
                                Icon(
                                    imageVector = if (showKeyPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compile_key_password_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberKeystore,
                            onCheckedChange = { rememberKeystore = it },
                            enabled = !isCompiling,
                            modifier = Modifier.testTag("compile_remember_keystore_checkbox")
                        )
                        Text(
                            text = stringResource(R.string.compile_remember_keystore),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // 3. Remote Gradle compiler endpoint
                SectionCard(title = stringResource(R.string.compile_step_server)) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text(stringResource(R.string.compile_server_url_label)) },
                        placeholder = { Text(stringResource(R.string.compile_server_url_placeholder)) },
                        singleLine = true,
                        enabled = !isCompiling,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compile_server_url_input")
                    )

                    Text(
                        text = stringResource(R.string.compile_server_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = { showRunnerHelp = !showRunnerHelp },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.compile_toggle_script),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showRunnerHelp) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null
                        )
                    }

                    AnimatedVisibility(visible = showRunnerHelp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            OutlinedButton(
                                onClick = onCopyServerScript,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("compile_copy_script_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = stringResource(R.string.compile_copy_script))
                            }
                        }
                    }
                }

                Button(
                    onClick = {
                        onCompile(serverUrl, storePassword, keyAlias, keyPassword, rememberKeystore)
                    },
                    enabled = canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("compile_apk_submit_button")
                ) {
                    if (isCompiling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.compile_in_progress))
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Build,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.compile_launch_button))
                    }
                }

                when (compileState) {
                    is ApkCompileState.Compiling -> {
                        Text(
                            text = compileState.stageMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is ApkCompileState.Error -> {
                        Text(
                            text = compileState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    is ApkCompileState.Success -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "${compileState.fileName} (${formatReadableSize(compileState.sizeBytes)})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (hasPendingApkSave) {
                                    FilledTonalButton(
                                        onClick = onSaveApkFallback,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(
                                                R.string.compile_save_apk_fallback,
                                                compileState.fileName
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ApkCompileState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

private fun formatReadableSize(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes o"
        bytes < 1024L * 1024L -> "${bytes / 1024L} Ko"
        else -> String.format("%.2f Mo", bytes / (1024.0 * 1024.0))
    }
}
