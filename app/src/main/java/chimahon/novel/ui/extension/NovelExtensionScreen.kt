package chimahon.novel.ui.extension

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.extension.NovelExtension
import chimahon.novel.extension.NovelExtensionManager
import chimahon.novel.extension.install.ExtensionEvent
import chimahon.novel.extension.install.InstallStep
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
    val snackbarHostState = remember { SnackbarHostState() }

    val availableExtensions by extensionManager.availableNovelExtensions.collectAsState()
    val installedExtensions by extensionManager.installedNovelExtensions.collectAsState()
    val untrustedExtensions by extensionManager.untrustedExtensions.collectAsState()
    val isLoading by extensionManager.isLoading.collectAsState()
    val extensionState by extensionManager.extensionState.collectAsState()
    var showRepoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        extensionManager.extensionEvents.collect { event ->
            when (event) {
                is ExtensionEvent.InstallCompleted ->
                    snackbarHostState.showSnackbar("Installed ${event.pkgName}")
                is ExtensionEvent.InstallFailed ->
                    snackbarHostState.showSnackbar("Install failed: ${event.reason}")
                is ExtensionEvent.UninstallCompleted ->
                    snackbarHostState.showSnackbar("Uninstalled ${event.pkgName}")
                is ExtensionEvent.UninstallFailed ->
                    snackbarHostState.showSnackbar("Uninstall failed: ${event.reason}")
            }
        }
    }

    if (showRepoDialog) {
        RepoManagementDialog(
            repos = extensionManager.getExtensionRepos(),
            onAdd = { url ->
                extensionManager.addExtensionRepo(url)
                scope.launch { extensionManager.refreshAvailableExtensions() }
            },
            onRemove = { url ->
                extensionManager.removeExtensionRepo(url)
                scope.launch { extensionManager.refreshAvailableExtensions() }
            },
            onDismiss = { showRepoDialog = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Novel Extensions") },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Text("X")
                    }
                },
                actions = {
                    IconButton(onClick = { showRepoDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Manage repos")
                    }
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
                    installSteps = extensionState.installSteps,
                    onInstall = { extension ->
                        val remote = extensionManager.availableExtensions.value.find { it.pkgName == extension.pkgName }
                        if (remote != null) {
                            scope.launch { extensionManager.installExtension(remote).collect {} }
                        }
                    },
                )
                1 -> InstalledExtensionsList(
                    extensions = installedExtensions,
                    onUninstall = { extensionManager.uninstallExtension(it) },
                )
                2 -> UntrustedExtensionsList(
                    extensions = untrustedExtensions,
                    onTrust = { extensionManager.trustExtension(it) },
                )
            }
        }
    }
}

@Composable
private fun AvailableExtensionsList(
    extensions: List<NovelExtension.Available>,
    installedPkgNames: Set<String>,
    installSteps: Map<String, InstallStep>,
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
                installStep = installSteps[extension.pkgName],
                onInstall = { onInstall(extension) },
            )
        }
    }
}

@Composable
private fun AvailableExtensionCard(
    extension: NovelExtension.Available,
    isInstalled: Boolean,
    installStep: InstallStep?,
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
            when (installStep) {
                is InstallStep.Downloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                is InstallStep.Success -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(24.dp),
                    )
                }
                is InstallStep.Error -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                }
                else -> {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
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
                        if (installStep is InstallStep.Error) {
                            append(" - ")
                            append(installStep.error)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onInstall,
                enabled = !isInstalled && installStep !is InstallStep.Downloading,
            ) {
                Text(
                    when {
                        installStep is InstallStep.Downloading -> "Downloading"
                        isInstalled -> "Installed"
                        else -> "Install"
                    },
                )
            }}
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
private fun RepoManagementDialog(
    repos: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newRepoUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Extension Repositories") },
        text = {
            Column {
                Text(
                    "Add or remove extension repository URLs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = newRepoUrl,
                        onValueChange = { newRepoUrl = it },
                        placeholder = { Text("https://.../index.min.json") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (newRepoUrl.isNotBlank()) {
                                onAdd(newRepoUrl.trim())
                                newRepoUrl = ""
                            }
                        },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                repos.forEachIndexed { index, repo ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = repo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { onRemove(repo) },
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (index < repos.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
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
