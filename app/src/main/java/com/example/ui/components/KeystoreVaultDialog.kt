package com.example.ui.components

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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.compiler.KeystoreInspection
import com.example.compiler.SavedKeystoreProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun KeystoreVaultDialog(
    savedProfiles: List<SavedKeystoreProfile>,
    draftKeystoreInspection: KeystoreInspection?,
    onDismiss: () -> Unit,
    onPickKeystore: (storePassword: String, alias: String, keyPassword: String) -> Unit,
    onInspectDraftKeystore: (storePassword: String, alias: String, keyPassword: String) -> Unit,
    onSaveProfile: (label: String, storePassword: String, alias: String, keyPassword: String) -> Unit,
    onCopyField: (fieldName: String, value: String) -> Unit,
    onExportKeystore: (SavedKeystoreProfile) -> Unit,
    onDeleteProfile: (String) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var storePassword by remember { mutableStateOf("") }
    var keyAlias by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var showDraftStorePass by remember { mutableStateOf(false) }
    var showDraftKeyPass by remember { mutableStateOf(false) }

    LaunchedEffect(draftKeystoreInspection?.fileName) {
        val name = draftKeystoreInspection?.fileName.orEmpty()
        if (label.isBlank() && name.isNotBlank()) {
            label = name.substringBeforeLast('.')
        }
    }
    LaunchedEffect(draftKeystoreInspection?.aliases) {
        val aliases = draftKeystoreInspection?.aliases.orEmpty()
        if (keyAlias.isBlank() && aliases.size == 1) {
            keyAlias = aliases.first()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
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
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.vault_dialog_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("close_vault_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_close)
                        )
                    }
                }

                if (savedProfiles.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Text(
                            text = stringResource(R.string.vault_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        savedProfiles.forEach { profile ->
                            SavedKeystoreCard(
                                profile = profile,
                                onCopyField = onCopyField,
                                onExportKeystore = { onExportKeystore(profile) },
                                onDelete = { onDeleteProfile(profile.id) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                            text = stringResource(R.string.vault_add_section),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )

                        FilledTonalButton(
                            onClick = { onPickKeystore(storePassword, keyAlias, keyPassword) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("vault_pick_keystore_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.compile_pick_keystore))
                        }

                        if (draftKeystoreInspection != null) {
                            Text(
                                text = "${draftKeystoreInspection.fileName} — ${draftKeystoreInspection.statusMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedTextField(
                            value = label,
                            onValueChange = { label = it },
                            label = { Text(stringResource(R.string.vault_label_input)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_label_input")
                        )

                        OutlinedTextField(
                            value = storePassword,
                            onValueChange = {
                                storePassword = it
                                if (draftKeystoreInspection != null) {
                                    onInspectDraftKeystore(it, keyAlias, keyPassword)
                                }
                            },
                            label = { Text(stringResource(R.string.compile_store_password)) },
                            singleLine = true,
                            visualTransformation = if (showDraftStorePass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showDraftStorePass = !showDraftStorePass }) {
                                    Icon(
                                        imageVector = if (showDraftStorePass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_store_password_input")
                        )

                        OutlinedTextField(
                            value = keyAlias,
                            onValueChange = {
                                keyAlias = it
                                if (draftKeystoreInspection != null) {
                                    onInspectDraftKeystore(storePassword, it, keyPassword)
                                }
                            },
                            label = { Text(stringResource(R.string.compile_key_alias)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_key_alias_input")
                        )

                        OutlinedTextField(
                            value = keyPassword,
                            onValueChange = {
                                keyPassword = it
                                if (draftKeystoreInspection != null) {
                                    onInspectDraftKeystore(storePassword, keyAlias, it)
                                }
                            },
                            label = { Text(stringResource(R.string.compile_key_password)) },
                            singleLine = true,
                            visualTransformation = if (showDraftKeyPass) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { showDraftKeyPass = !showDraftKeyPass }) {
                                    Icon(
                                        imageVector = if (showDraftKeyPass) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_key_password_input")
                        )

                        Button(
                            onClick = {
                                onSaveProfile(label, storePassword, keyAlias, keyPassword)
                                label = ""
                                storePassword = ""
                                keyAlias = ""
                                keyPassword = ""
                            },
                            enabled = draftKeystoreInspection != null &&
                                storePassword.isNotEmpty() &&
                                keyAlias.isNotBlank() &&
                                keyPassword.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("vault_save_profile_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.vault_save_button))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedKeystoreCard(
    profile: SavedKeystoreProfile,
    onCopyField: (String, String) -> Unit,
    onExportKeystore: () -> Unit,
    onDelete: () -> Unit
) {
    var revealed by remember(profile.id) { mutableStateOf(false) }
    val dateLabel = remember(profile.savedAt) {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(profile.savedAt))
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${profile.fileName} • Alias : ${profile.keyAlias} • $dateLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.testTag("vault_toggle_reveal_${profile.id}")
                ) {
                    Icon(
                        imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.vault_hide_codes else R.string.vault_show_codes
                        )
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("vault_delete_${profile.id}")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.vault_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            CredentialCopyRow(
                label = "Mot de passe Keystore",
                displayValue = if (revealed) profile.storePassword else "••••••••••••",
                onCopy = { onCopyField("Mot de passe Keystore", profile.storePassword) }
            )
            CredentialCopyRow(
                label = "Alias",
                displayValue = profile.keyAlias,
                onCopy = { onCopyField("Alias", profile.keyAlias) }
            )
            CredentialCopyRow(
                label = "Mot de passe Clé",
                displayValue = if (revealed) profile.keyPassword else "••••••••••••",
                onCopy = { onCopyField("Mot de passe Clé", profile.keyPassword) }
            )

            OutlinedButton(
                onClick = onExportKeystore,
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("vault_export_${profile.id}")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.vault_export_jks))
            }
        }
    }
}

@Composable
private fun CredentialCopyRow(
    label: String,
    displayValue: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copier $label",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
