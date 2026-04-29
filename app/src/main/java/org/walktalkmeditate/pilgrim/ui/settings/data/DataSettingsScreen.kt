// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onAction: (SettingsAction) -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel(),
) {
    val recordingCount by viewModel.recordingCount.collectAsStateWithLifecycle()
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val pilgrimExportState by viewModel.pilgrimExportState.collectAsStateWithLifecycle()
    val pilgrimImportState by viewModel.pilgrimImportState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val authority = remember(context) { "${context.packageName}.fileprovider" }

    val emptyMsg = stringResource(R.string.data_export_empty)
    val failedMsg = stringResource(R.string.data_export_failed)
    val pilgrimExportNoWalksMsg = stringResource(R.string.data_pilgrim_export_no_walks)
    val pilgrimExportFailedMsg = stringResource(R.string.data_pilgrim_export_failed)
    val pilgrimExportPartialMsg = stringResource(R.string.data_pilgrim_export_partial)
    val pilgrimImportImportedOne = stringResource(R.string.data_pilgrim_import_imported_one)
    val pilgrimImportImportedManyTpl = stringResource(R.string.data_pilgrim_import_imported_many)
    val pilgrimImportUnsupportedMsg = stringResource(R.string.data_pilgrim_import_failed_unsupported_version)
    val pilgrimImportGenericFailedMsg = stringResource(R.string.data_pilgrim_import_failed_generic)

    // Stage 10-EFG fix #1: hoist the contract instance via remember so
    // DisposableEffect's identity-keyed register/unregister cycle stays
    // stable across recompositions. A fresh `OpenDocument()` instance
    // every recompose strands in-flight pickers.
    val openDocumentContract = remember { ActivityResultContracts.OpenDocument() }
    val importPickerLauncher = rememberLauncherForActivityResult(openDocumentContract) { uri ->
        if (uri != null) {
            viewModel.importPilgrim(uri)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.exportEvents.collect { event ->
            when (event) {
                is RecordingsExportResult.Success -> {
                    val uri = FileProvider.getUriForFile(context, authority, event.file)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // ClipData + flag are MANDATORY on all APIs — without them,
                        // modern receiving apps (Drive, Gmail, Slack) can fail to
                        // resolve the URI grant through Intent.createChooser. Same
                        // pattern as EtegamiShareIntentFactory (Stage 7-D).
                        clipData = ClipData.newRawUri(event.file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(sendIntent, null).apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(chooser)
                }
                RecordingsExportResult.Empty -> {
                    scope.launch { snackbarHostState.showSnackbar(emptyMsg) }
                }
                is RecordingsExportResult.Failed -> {
                    scope.launch { snackbarHostState.showSnackbar(failedMsg) }
                }
            }
        }
    }

    // Stage 10-I: collect .pilgrim share events. Same intent shape as
    // the recordings ZIP path: ACTION_SEND + ClipData + chooser-side
    // FLAG_GRANT_READ_URI_PERMISSION (Stage 10-EFG fix #2) so receiving
    // apps reliably resolve the FileProvider URI.
    LaunchedEffect(Unit) {
        viewModel.pilgrimShareEvents.collect { file ->
            val uri = FileProvider.getUriForFile(context, authority, file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, null).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        }
    }

    LaunchedEffect(pilgrimExportState) {
        when (val state = pilgrimExportState) {
            is PilgrimExportState.Failed -> {
                val message = when (state.message) {
                    "No walks found to export." -> pilgrimExportNoWalksMsg
                    else -> pilgrimExportFailedMsg
                }
                scope.launch { snackbarHostState.showSnackbar(message) }
                viewModel.cancelPilgrimExport()
            }
            is PilgrimExportState.Done -> {
                if (state.skippedPhotoCount > 0) {
                    scope.launch { snackbarHostState.showSnackbar(pilgrimExportPartialMsg) }
                }
                viewModel.cancelPilgrimExport()
            }
            else -> Unit
        }
    }

    LaunchedEffect(pilgrimImportState) {
        when (val state = pilgrimImportState) {
            is PilgrimImportState.Imported -> {
                val message = when (state.walkCount) {
                    1 -> pilgrimImportImportedOne
                    else -> String.format(java.util.Locale.US, pilgrimImportImportedManyTpl, state.walkCount)
                }
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
            is PilgrimImportState.Failed -> {
                val message = if (state.message.startsWith("Unsupported version")) {
                    pilgrimImportUnsupportedMsg
                } else {
                    pilgrimImportGenericFailedMsg
                }
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
            else -> Unit
        }
    }

    val confirming = pilgrimExportState as? PilgrimExportState.Confirming
    if (confirming != null) {
        ExportConfirmationSheet(
            walkCount = confirming.walkCount,
            dateRangeText = confirming.dateRangeText,
            pinnedPhotoCount = confirming.pinnedPhotoCount,
            estimatedPhotoSizeBytes = confirming.estimatedPhotoBytes,
            onDismissRequest = { viewModel.cancelPilgrimExport() },
            onCancel = { viewModel.cancelPilgrimExport() },
            onExport = { includePhotos -> viewModel.confirmPilgrimExport(includePhotos) },
        )
    }

    val isPilgrimExportBuilding = pilgrimExportState is PilgrimExportState.Building
    val isPilgrimImporting = pilgrimImportState is PilgrimImportState.Importing

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.data_screen_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.data_back_content_description),
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.data_section_walks_header)) }
            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    if (isPilgrimExportBuilding) {
                        ExportingRow(
                            label = stringResource(R.string.data_action_export),
                        )
                    } else {
                        SettingNavRow(
                            label = stringResource(R.string.data_action_export),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.requestPilgrimExport() },
                        )
                    }
                    if (isPilgrimImporting) {
                        ExportingRow(
                            label = stringResource(R.string.data_action_import),
                        )
                    } else {
                        SettingNavRow(
                            label = stringResource(R.string.data_action_import),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                importPickerLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*"),
                                )
                            },
                        )
                    }
                }
            }
            item { SectionFooter(stringResource(R.string.data_walks_footer)) }

            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    SettingNavRow(
                        label = stringResource(R.string.data_action_journey),
                        modifier = Modifier.fillMaxWidth(),
                        external = true,
                        onClick = { onAction(SettingsAction.OpenJourneyViewer) },
                    )
                }
            }
            item { SectionFooter(stringResource(R.string.data_journey_footer)) }

            if (recordingCount > 0) {
                item { SectionHeader(stringResource(R.string.data_section_audio_header)) }
                item {
                    Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                        if (isExporting) {
                            // Show a row that mimics the SettingNavRow layout
                            // but with a spinner in place of the trailing
                            // chevron + count. The compareAndSet guard in
                            // the VM means re-taps are silent no-ops; the
                            // spinner is what tells the user the work is
                            // happening (a 50MB+ recordings tree can take
                            // several seconds).
                            ExportingRow(
                                label = stringResource(R.string.data_action_export_recordings),
                            )
                        } else {
                            SettingNavRow(
                                label = stringResource(R.string.data_action_export_recordings),
                                detail = recordingCount.toString(),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { viewModel.exportRecordings() },
                            )
                        }
                    }
                }
                item { SectionFooter(stringResource(R.string.data_audio_footer)) }
            }
        }
    }
}

@Composable
private fun ExportingRow(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.ink.copy(alpha = 0.6f),
        )
        Spacer(Modifier.weight(1f))
        CircularProgressIndicator(
            color = pilgrimColors.stone,
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = pilgrimType.caption,
        color = pilgrimColors.fog,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text = text,
        style = pilgrimType.caption,
        color = pilgrimColors.fog,
        modifier = Modifier.padding(horizontal = 32.dp),
    )
}
