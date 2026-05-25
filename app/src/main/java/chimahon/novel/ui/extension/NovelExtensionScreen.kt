package chimahon.novel.ui.extension

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chimahon.novel.extension.CatalogRemote
import chimahon.novel.extension.InstalledExtension
import chimahon.novel.extension.NovelExtensionManager
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionScreen : cafe.adriel.voyager.core.screen.Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val extensionManager = remember { Injekt.get<NovelExtensionManager>() }
        NovelExtensionContent(
            extensionManager = extensionManager,
            onNavigateBack = { navigator.pop() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelExtensionContent(
    extensionManager: NovelExtensionManager,
    onNavigateBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val availableExtensions by extensionManager.availableExtensions.collectAsState()
    val installedExtensions by extensionManager.installedExtensions.collectAsState()
    val isLoading by extensionManager.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Novel Extensions") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { extensionManager.loadInstalledExtensions() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Available (${availableExtensions.size})") },
                    icon = { Icon(Icons.Default.List, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Installed (${installedExtensions.size})") },
                    icon = { Icon(Icons.Default.CheckCircle, null) }
                )
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (selectedTab) {
                0 -> AvailableExtensionsList(
                    extensions = availableExtensions,
                    installedIds = installedExtensions.map { it.id }.toSet(),
                    onInstall = { extensionManager.installExtension(it) }
                )
                1 -> InstalledExtensionsList(
                    extensions = installedExtensions,
                    onUninstall = { extensionManager.uninstallExtension(it) },
                    onToggle = { id, enabled -> extensionManager.toggleExtension(id, enabled) }
                )
            }
        }
    }
}

@Composable
private fun AvailableExtensionsList(
    extensions: List<CatalogRemote>,
    installedIds: Set<String>,
    onInstall: (CatalogRemote) -> Unit
) {
    if (extensions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Loading extensions...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(extensions) { extension ->
            ExtensionCard(
                extension = extension,
                isInstalled = installedIds.contains(extension.pkgName),
                onInstall = { onInstall(extension) }
            )
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: CatalogRemote,
    isInstalled: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append(extension.lang)
                        append(" • v${extension.versionName}")
                        if (extension.nsfw) append(" • 18+")
                        append(" • ${extension.repositoryType}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                extension.description.takeIf { it.isNotBlank() }?.let { desc ->
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            if (isInstalled) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Installed",
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(onClick = onInstall) {
                    Text("Install")
                }
            }
        }
    }
}

@Composable
private fun InstalledExtensionsList(
    extensions: List<InstalledExtension>,
    onUninstall: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    if (extensions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Extension,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No extensions installed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Browse available extensions to install",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(extensions) { extension ->
            InstalledExtensionCard(
                extension = extension,
                onUninstall = { onUninstall(extension.id) },
                onToggle = { enabled -> onToggle(extension.id, enabled) }
            )
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    extension: InstalledExtension,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    var showUninstallDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${extension.lang} • v${extension.version} • ${extension.repositoryType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Switch(
                checked = extension.isEnabled,
                onCheckedChange = onToggle
            )

            IconButton(onClick = { showUninstallDialog = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Uninstall",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            title = { Text("Uninstall Extension") },
            text = { Text("Are you sure you want to uninstall ${extension.name}?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUninstall()
                        showUninstallDialog = false
                    }
                ) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
