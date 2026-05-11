// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val TAG_RELIQUARY_TOGGLE_OFF = "reliquary-toggle-off"
internal const val TAG_RELIQUARY_PERMISSION_PROMPT = "reliquary-permission-prompt"
internal const val TAG_RELIQUARY_SETTINGS_BUTTON = "reliquary-settings-button"
internal const val TAG_RELIQUARY_SKELETON = "reliquary-skeleton"
internal const val TAG_RELIQUARY_CAROUSEL = "reliquary-carousel"

private const val SKELETON_DEFER_MS = 300L
private val SKELETON_HEIGHT = 88.dp

internal fun isPhotosPermissionGranted(context: android.content.Context): Boolean {
    val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * Stage 7-A / Task 2.3: Walk Summary's photo reliquary section.
 * Dispatches on [ReliquaryState] per the D6 precedence spec:
 *   ToggleOff > PermissionDenied > Loading > Populated(candidates)
 *
 * A [DisposableEffect] wired to the host Lifecycle forwards `ON_START`
 * to [onForegrounded] with the live permission result so the VM can
 * keep [ReliquaryState] in sync across permission-settings round-trips.
 *
 * The existing grid logic is preserved inside [ReliquaryPopulated];
 * Stage 3 will replace it with the PhotoCarousel.
 */
@Composable
fun PhotoReliquarySection(
    state: ReliquaryState,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    onForegrounded: (permissionGranted: Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                onForegrounded(isPhotosPermissionGranted(context))
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    when (state) {
        is ReliquaryState.ToggleOff -> {
            Box(modifier = modifier.testTag(TAG_RELIQUARY_TOGGLE_OFF))
        }
        is ReliquaryState.PermissionDenied -> {
            ReliquaryPermissionPrompt(
                onSettingsClick = onSettingsClick,
                modifier = modifier,
            )
        }
        is ReliquaryState.Loading -> {
            ReliquaryDeferredSkeleton(modifier = modifier)
        }
        is ReliquaryState.Populated -> {
            if (state.candidates.isEmpty()) {
                Box(modifier = modifier)
            } else {
                ReliquaryPopulated(
                    photos = state.candidates,
                    onPinPhotos = onPinPhotos,
                    onUnpinPhoto = onUnpinPhoto,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ReliquaryPermissionPrompt(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_RELIQUARY_PERMISSION_PROMPT),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.reliquary_permission_denied_title),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        Text(
            text = stringResource(R.string.reliquary_permission_denied_body),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Button(
            onClick = onSettingsClick,
            modifier = Modifier.testTag(TAG_RELIQUARY_SETTINGS_BUTTON),
        ) {
            Text(stringResource(R.string.reliquary_permission_denied_action_settings))
        }
    }
}

@Composable
private fun ReliquaryDeferredSkeleton(modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SKELETON_DEFER_MS)
        show = true
    }
    if (show) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(SKELETON_HEIGHT)
                .clip(RoundedCornerShape(PilgrimCornerRadius.small))
                .background(pilgrimColors.parchmentSecondary)
                .testTag(TAG_RELIQUARY_SKELETON),
        )
    }
}

@Composable
private fun ReliquaryPopulated(
    photos: List<WalkPhoto>,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val slots = (MAX_PINS_PER_WALK - photos.size).coerceAtLeast(0)
    var photoToRemove by remember { mutableStateOf<WalkPhoto?>(null) }

    // Stable contract references across recompositions.
    // `rememberLauncherForActivityResult` keys its `DisposableEffect` on
    // the contract identity; constructing a fresh contract inline on
    // every recompose would unregister / re-register the launcher on
    // every tick, racing with in-flight picker intents. Wrap in
    // `remember { }` so the contract instances survive unrelated
    // recompositions. The picker's `maxItems` stays at MAX; the VM's
    // pre-clip against pinnedPhotos.value and the repo's transactional
    // count + insert inside `withTransaction` are the real defenses
    // against exceeding the cap.
    val multiContract = remember {
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_PINS_PER_WALK)
    }
    val multiLauncher = rememberLauncherForActivityResult(multiContract) { uris ->
        if (uris.isNotEmpty()) onPinPhotos(uris)
    }
    // Single-pick fallback kept for the `slots == 1` case —
    // PickMultipleVisualMedia requires maxItems > 1, so we cannot clamp
    // the multi contract to 1 even though the VM would clip anyway.
    val singleContract = remember { ActivityResultContracts.PickVisualMedia() }
    val singleLauncher = rememberLauncherForActivityResult(singleContract) { uri ->
        if (uri != null) onPinPhotos(listOf(uri))
    }

    val haptics = LocalHapticFeedback.current
    val soundsEnabled = LocalSoundsEnabled.current

    Column(modifier = modifier.fillMaxWidth().testTag(TAG_RELIQUARY_CAROUSEL)) {
        ReliquaryHeader(
            slotsAvailable = slots,
            onAddClick = {
                val request = PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                )
                when (slots) {
                    0 -> Unit
                    1 -> singleLauncher.launch(request)
                    else -> multiLauncher.launch(request)
                }
            },
        )

        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(PilgrimSpacing.small))
            PhotoGrid(
                photos = photos,
                onLongPressTile = { photo ->
                    if (soundsEnabled) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    photoToRemove = photo
                },
            )
        }
    }

    val target = photoToRemove
    if (target != null) {
        UnpinConfirmationDialog(
            onConfirm = {
                onUnpinPhoto(target)
                photoToRemove = null
            },
            onDismiss = { photoToRemove = null },
        )
    }
}

@Composable
private fun ReliquaryHeader(
    slotsAvailable: Int,
    onAddClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Reliquary",
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        OutlinedButton(
            enabled = slotsAvailable > 0,
            onClick = onAddClick,
        ) {
            Icon(
                imageVector = Icons.Default.AddAPhoto,
                contentDescription = null,
            )
            Spacer(Modifier.width(PilgrimSpacing.xs))
            Text(if (slotsAvailable > 0) "Add" else "Full")
        }
    }
}

@Composable
private fun PhotoGrid(
    photos: List<WalkPhoto>,
    onLongPressTile: (WalkPhoto) -> Unit,
) {
    // Non-lazy chunked grid: Walk Summary already hosts a verticalScroll
    // so a LazyVerticalGrid here would crash at measure time. With a
    // 20-photo cap the cost is negligible — 7 rows max.
    val cols = 3
    val rows = remember(photos) { photos.chunked(cols) }
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
                row.forEach { photo ->
                    PhotoTile(
                        photo = photo,
                        onLongPress = { onLongPressTile(photo) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad trailing cells so partial rows keep column widths.
                repeat(cols - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoTile(
    photo: WalkPhoto,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stage 7-B: track Coil's load state so we can swap the
    // contentDescription to an actionable tombstone hint when the
    // image fails to load. Keyed by photo.id so a new tile sliding
    // into this slot resets the flag — otherwise a fresh live photo
    // would inherit the previous photo's broken state for a frame.
    var loadFailed by remember(photo.id) { mutableStateOf(false) }
    val contentDesc = if (loadFailed) {
        "Photo unavailable — long press to remove"
    } else {
        "Photo from this walk"
    }

    // `contentDescription` lives on this outer Box rather than on
    // SubcomposeAsyncImage because Coil only stamps the description
    // into the semantics tree once the image loads — during the
    // loading / error sub-compositions the description is absent,
    // which breaks both accessibility and the UI tests that rely on
    // it. Owning the description here keeps the semantics stable
    // across image load states.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(PilgrimCornerRadius.small))
            .background(pilgrimColors.parchmentSecondary)
            .pointerInput(photo.id) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .semantics {
                contentDescription = contentDesc
                customActions = listOf(
                    CustomAccessibilityAction(label = "Remove from walk") {
                        onLongPress()
                        true
                    },
                )
            },
    ) {
        // Pass the URI string directly — Coil 3 resolves String
        // content://… models via AndroidContentUriFetcher, avoiding the
        // Uri.parse allocation that would run on every recomposition.
        SubcomposeAsyncImage(
            model = photo.photoUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = { loadFailed = true },
            onSuccess = { loadFailed = false },
            error = { ReliquaryTombstone() },
            loading = { /* transparent; parchment background shows through */ },
        )
    }
}

@Composable
private fun ReliquaryTombstone() {
    // Stage 7-B: a legible fallback when Coil can't load the pinned
    // photo (user deleted it from the library, SD card unmounted, SAF
    // grant expired on API 28-29). The icon alone is ambiguous — "is
    // this loading, or broken?" — so we add a caption that names the
    // state. The tile's long-press gesture is unchanged; tapping an
    // accessibility tile announces the "Photo unavailable — long
    // press to remove" hint set on the outer Box.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(PilgrimSpacing.xs),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.BrokenImage,
            contentDescription = null,
            tint = pilgrimColors.fog,
        )
        Spacer(Modifier.height(PilgrimSpacing.xs))
        Text(
            text = "Photo unavailable",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun UnpinConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove from walk?") },
        text = {
            Text("This photo will be unpinned. Your photo library isn't changed.")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        },
    )
}
