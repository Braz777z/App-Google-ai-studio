package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.StudioSection

@Composable
fun StudioProgressBar(
    isLoading: Boolean,
    progress: Int,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = (progress.coerceIn(0, 100)) / 100f,
        animationSpec = spring(),
        label = "webview_progress"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.5.dp)
    ) {
        AnimatedVisibility(
            visible = isLoading && progress < 100,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress.coerceAtLeast(0.08f))
                    .height(2.5.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.onSurfaceVariant,
                                MaterialTheme.colorScheme.primary
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun ImmersiveFloatingPill(
    visible: Boolean,
    onExitImmersive: () -> Unit,
    onReload: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 8.dp, end = 12.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onReload,
                    modifier = Modifier.testTag("immersive_reload_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.action_reload),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onOpenWorkspace,
                    modifier = Modifier.testTag("immersive_workspace_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.nav_workspace),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onExitImmersive,
                    modifier = Modifier.testTag("immersive_exit_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.action_exit_fullscreen),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StudioBottomBar(
    activeSection: StudioSection,
    isWorkspaceOpen: Boolean,
    onSelectSection: (StudioSection) -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        windowInsets = WindowInsets.navigationBars,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        StudioSection.entries.forEach { section ->
            val selected = !isWorkspaceOpen && activeSection == section
            NavigationBarItem(
                selected = selected,
                onClick = { onSelectSection(section) },
                icon = {
                    Icon(
                        imageVector = sectionIcon(section, selected),
                        contentDescription = stringResource(section.labelRes)
                    )
                },
                label = {
                    Text(
                        text = stringResource(section.labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag("nav_${section.id}")
            )
        }
        NavigationBarItem(
            selected = isWorkspaceOpen,
            onClick = onOpenWorkspace,
            icon = {
                Icon(
                    imageVector = if (isWorkspaceOpen) Icons.Filled.Tune else Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.nav_workspace)
                )
            },
            label = {
                Text(
                    text = stringResource(R.string.nav_workspace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = Modifier.testTag("nav_workspace")
        )
    }
}

@Composable
fun StudioNavigationRail(
    activeSection: StudioSection,
    isWorkspaceOpen: Boolean,
    onSelectSection: (StudioSection) -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        StudioSection.entries.forEach { section ->
            val selected = !isWorkspaceOpen && activeSection == section
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectSection(section) },
                icon = {
                    Icon(
                        imageVector = sectionIcon(section, selected),
                        contentDescription = stringResource(section.labelRes)
                    )
                },
                label = { Text(text = stringResource(section.labelRes)) },
                modifier = Modifier.testTag("rail_${section.id}")
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        NavigationRailItem(
            selected = isWorkspaceOpen,
            onClick = onOpenWorkspace,
            icon = {
                Icon(
                    imageVector = if (isWorkspaceOpen) Icons.Filled.Tune else Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.nav_workspace)
                )
            },
            label = { Text(text = stringResource(R.string.nav_workspace)) },
            modifier = Modifier.testTag("rail_workspace")
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
fun StudioErrorOverlay(
    errorDescription: String?,
    onRetry: () -> Unit,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.widthIn(max = 460.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.status_offline_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.status_offline_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (!errorDescription.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = errorDescription,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onGoHome,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("error_home_button")
                    ) {
                        Text(text = stringResource(R.string.nav_studio))
                    }
                    Button(
                        onClick = onRetry,
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("error_retry_button")
                    ) {
                        Text(text = stringResource(R.string.action_retry))
                    }
                }
            }
        }
    }
}

private fun sectionIcon(section: StudioSection, selected: Boolean): ImageVector {
    return when (section) {
        StudioSection.STUDIO -> if (selected) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome
        StudioSection.APPS -> if (selected) Icons.Filled.Code else Icons.Outlined.Code
        StudioSection.API_KEYS -> if (selected) Icons.Filled.Key else Icons.Outlined.Key
        StudioSection.LIBRARY -> if (selected) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
    }
}
