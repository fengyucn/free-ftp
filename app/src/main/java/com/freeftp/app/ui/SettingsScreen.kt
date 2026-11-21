package com.freeftp.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.freeftp.app.storage.DownloadLocationStore
import com.freeftp.app.storage.SettingsBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings. Where downloads go, and moving the whole configuration between devices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    downloads: DownloadLocationStore,
    backup: SettingsBackup,
    onBack: () -> Unit,
) {
    // Recomposition key: bumped whenever the stored location changes.
    var revision by remember { mutableStateOf(0) }
    val description = remember(revision) { downloads.describe() }
    val browsable = remember(revision) { downloads.isBrowsableByUser() }
    val lostAccess = remember(revision) { downloads.hasLostAccess }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    // The folder picker *is* the permission prompt: the user grants access to exactly
    // one directory. Dismissing it returns null, which leaves the previous choice alone.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            downloads.useUserFolder(uri)
            revision++
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupMessage = try {
                val count = backup.exportTo(uri)
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
                "Exported $count servers. The file contains passwords unencrypted — keep it safe."
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
        }
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupMessage = try {
                val result = backup.importFrom(uri)
                revision++
                buildString {
                    append("Imported ${result.imported} servers")
                    if (result.skipped > 0) append(", ${result.skipped} unreadable entries skipped")
                    if (result.hostKeysRestored > 0) {
                        append(", ${result.hostKeysRestored} host keys restored")
                    }
                    when {
                        !result.downloadFolderKnown -> {}
                        result.downloadFolderRestored -> append(", download folder restored")
                        else -> append(", download folder grant not transferable — pick it again")
                    }
                    append(".")
                }
            } catch (e: Exception) {
                "Import failed: ${e.message}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { pickFolder.launch(null) }
                    .padding(16.dp)
                    .testTag("setting-download-folder"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Download folder", style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("download-folder-value"),
                )
                if (lostAccess) {
                    Text(
                        "The folder you chose is no longer available, so downloads are " +
                            "going to the default again. Pick it once more to restore it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("download-folder-lost"),
                    )
                } else if (!browsable) {
                    Text(
                        "Files here are not visible in the Files app — Android hides " +
                            "app folders. Choose a folder to make downloads easy to find.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("download-folder-hidden"),
                    )
                } else {
                    Text(
                        "Tap to choose a different folder. FreeFTP asks for no storage " +
                            "permission — you grant access to the one folder you pick.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            TextButton(
                onClick = {
                    downloads.useDefault()
                    revision++
                },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("reset-download-folder"),
            ) { Text("Use the default folder") }

            HorizontalDivider()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Backup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Move saved servers (including passwords), accepted SSH host keys and " +
                        "the download folder to another device as a single JSON file. " +
                        "The export keeps secrets unencrypted so any device can read it — " +
                        "store the file somewhere you trust.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Import merges into what is already here: servers with the same id are " +
                        "replaced, others are added. Nothing is deleted.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row {
                    TextButton(
                        onClick = {
                            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                            exportBackup.launch("freeftp-backup-$stamp.json")
                        },
                        modifier = Modifier.testTag("export-settings"),
                    ) { Text("Export…") }
                    TextButton(
                        onClick = {
                            importBackup.launch(
                                arrayOf("application/json", "application/octet-stream", "text/plain")
                            )
                        },
                        modifier = Modifier.testTag("import-settings"),
                    ) { Text("Import…") }
                }
                backupMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("backup-result"),
                    )
                }
            }
        }
    }
}
