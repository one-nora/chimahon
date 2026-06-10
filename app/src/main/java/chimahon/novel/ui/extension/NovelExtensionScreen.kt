package chimahon.novel.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.extension.NovelExtension
import chimahon.novel.extension.NovelExtensionManager
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Novel extension management screen.
 */
class NovelExtensionScreen : Screen {
    @Composable
    override fun Content() {
        NovelExtensionContent()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelExtensionContent(
    extensionManager: NovelExtensionManager = Injekt.get(),
) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val availableExtensions by extensionManager.availableNovelExtensions.collectAsState()
    val installedExtensions by extensionManager.installedNovelExtensions.collectAsState()
    val untrustedExtensions by extensionManager.untrustedExtensions.collectAsState()
    val isLoading by extensionManager.isLoading.collectAsState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Novel Extensions") },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Text("X")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            extensionManager.loadInstalledExtensions()
                            scope.launch { extensionManager.refreshAvailableExtensions() }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Available") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Installed") },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Untrusted") },
                )
            }

            when (selectedTab) {
                0 -> AvailableExtensionsList(
                    extensions = availableExtensions,
                    installedPkgNames = installedExtensions.map { it.pkgName }.toSet(),
                    onInstall = { extension -> extensionManager.installExtension(extension) },
                )
                1 -> InstalledExtensionsList(
                    extensions = installedExtensions,
                    onUninstall = { extension -> extensionManager.uninstallExtension(extension.pkgName) },
                )
                2 -> UntrustedExtensionsList(
                    extensions = untrustedExtensions,
                    onTrust = { extension -> extensionManager.trustExtension(extension) },
                )
            }
        }
    }
}

@Composable
private fun AvailableExtensionsList(
    extensions: List<NovelExtension.Available>,
    installedPkgNames: Set<String>,
    onInstall: (NovelExtension.Available) -> Unit,
) {
    if (extensions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Extension,
            title = "No extensions available",
            message = "Check the configured extension repositories.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = extensions,
            key = { it.pkgName },
        ) { extension ->
            AvailableExtensionCard(
                extension = extension,
                isInstalled = extension.pkgName in installedPkgNames,
                onInstall = { onInstall(extension) },
            )
        }
    }
}

@Composable
private fun AvailableExtensionCard(
    extension: NovelExtension.Available,
    isInstalled: Boolean,
    onInstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(extension.lang)
                        append(" - v")
                        append(extension.versionName)
                        if (extension.repoName.isNotBlank()) {
                            append(" - ")
                            append(extension.repoName)
                        }
                        if (extension.isNsfw) {
                            append(" - NSFW")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onInstall,
                enabled = !isInstalled,
            ) {
                Text(if (isInstalled) "Installed" else "Install")
            }
        }
    }
}

@Composable
private fun InstalledExtensionsList(
    extensions: List<NovelExtension.Installed>,
    onUninstall: (NovelExtension.Installed) -> Unit,
) {
    if (extensions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Extension,
            title = "No extensions installed",
            message = "Install an IReader or LNReader extension to add novel sources.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = extensions,
            key = { it.pkgName },
        ) { extension ->
            InstalledExtensionCard(
                extension = extension,
                onUninstall = { onUninstall(extension) },
            )
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    extension: NovelExtension.Installed,
    onUninstall: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(extension.pkgName)
                        append(" - v")
                        append(extension.versionName)
                        append(" - ")
                        append(extension.sources.size)
                        append(" source")
                        if (extension.sources.size != 1) {
                            append("s")
                        }
                        if (extension.hasUpdate) {
                            append(" - Update available")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onUninstall) {
                Icon(Icons.Default.Delete, contentDescription = "Uninstall")
            }
        }
    }
}

@Composable
private fun UntrustedExtensionsList(
    extensions: List<NovelExtension.Untrusted>,
    onTrust: (NovelExtension.Untrusted) -> Unit,
) {
    if (extensions.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Security,
            title = "No untrusted extensions",
            message = "Extensions with unknown signatures will appear here.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = extensions,
            key = { it.pkgName },
        ) { extension ->
            UntrustedExtensionCard(
                extension = extension,
                onTrust = { onTrust(extension) },
            )
        }
    }
}

@Composable
private fun UntrustedExtensionCard(
    extension: NovelExtension.Untrusted,
    onTrust: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${extension.pkgName} - v${extension.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onTrust) {
                Text("Trust")
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .width(64.dp)
                .height(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
