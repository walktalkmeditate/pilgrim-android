// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.reliquary

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

internal const val TAG_RELIQUARY_TOGGLE_OFF = "reliquary-toggle-off"
internal const val TAG_RELIQUARY_PERMISSION_PROMPT = "reliquary-permission-prompt"
internal const val TAG_RELIQUARY_GRANT_BUTTON = "reliquary-grant-button"
internal const val TAG_RELIQUARY_SETTINGS_BUTTON = "reliquary-settings-button"
internal const val TAG_RELIQUARY_SKELETON = "reliquary-skeleton"
internal const val TAG_RELIQUARY_CAROUSEL = "reliquary-carousel"

private const val SKELETON_DEFER_MS = 300L
private val SKELETON_HEIGHT = 88.dp

internal fun isPhotosPermissionGranted(context: android.content.Context): Boolean {
    val granted = android.content.pm.PackageManager.PERMISSION_GRANTED
    return when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            // Android 14+: accept either full (READ_MEDIA_IMAGES) or partial
            // (READ_MEDIA_VISUAL_USER_SELECTED) photo access. Per spec non-goal,
            // partial-grant is treated the same as full-grant for this bundle;
            // the "Manage" affordance for partial access is future UX (Phase N).
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES,
            ) == granted || androidx.core.content.ContextCompat.checkSelfPermission(
                context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ) == granted
        }
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_MEDIA_IMAGES,
            ) == granted
        }
        else -> {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == granted
        }
    }
}

/**
 * SDK-appropriate photo-read permissions to request when the user
 * enables the reliquary (iOS-parity: enabling asks for photo access).
 * Mirrors the read split in [isPhotosPermissionGranted]:
 *  - API 34+ : READ_MEDIA_IMAGES + the partial-access pseudo-permission
 *  - API 33  : READ_MEDIA_IMAGES
 *  - pre-33  : READ_EXTERNAL_STORAGE
 */
internal fun photoPermissionsToRequest(): Array<String> = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            // Q+ runtime grant needed for unredacted EXIF GPS via
            // MediaStore.setRequireOriginal — the auto-discovery
            // scanner (PhotoLibraryScanner) filters candidates by
            // proximity to the route, which fails closed without it.
            "android.permission.ACCESS_MEDIA_LOCATION",
        )
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU ->
        arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            "android.permission.ACCESS_MEDIA_LOCATION",
        )
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ->
        arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            "android.permission.ACCESS_MEDIA_LOCATION",
        )
    else ->
        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * Walk Summary's photo reliquary section.
 * Dispatches on [ReliquaryState] per the D6 precedence spec:
 *   ToggleOff > PermissionDenied > Loading > Populated(candidates)
 *
 * A [DisposableEffect] wired to the host Lifecycle forwards `ON_START`
 * to [onForegrounded] with the live permission result so the VM can
 * keep [ReliquaryState] in sync across permission-settings round-trips.
 */
@Composable
fun PhotoReliquarySection(
    state: ReliquaryState,
    onPinPhotos: (List<Uri>) -> Unit,
    onUnpinPhoto: (WalkPhoto) -> Unit,
    onForegrounded: (permissionGranted: Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPinningInFlight: Boolean = false,
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

    // Stable contract identity across recompositions (Stage 7-A
    // launcher-race precedent). On result, push the live permission
    // snapshot through the same seam ON_START uses so the VM leaves
    // PermissionDenied the instant access is granted — no app round-trip.
    val photoPermContract = remember {
        ActivityResultContracts.RequestMultiplePermissions()
    }
    val photoPermLauncher = rememberLauncherForActivityResult(photoPermContract) {
        onForegrounded(isPhotosPermissionGranted(context))
    }

    when (state) {
        is ReliquaryState.ToggleOff -> {
            Box(modifier = modifier.testTag(TAG_RELIQUARY_TOGGLE_OFF))
        }
        is ReliquaryState.PermissionDenied -> {
            ReliquaryPermissionPrompt(
                onGrantClick = { photoPermLauncher.launch(photoPermissionsToRequest()) },
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
                    isPinningInFlight = isPinningInFlight,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun ReliquaryPermissionPrompt(
    onGrantClick: () -> Unit,
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
            onClick = onGrantClick,
            modifier = Modifier.testTag(TAG_RELIQUARY_GRANT_BUTTON),
        ) {
            Text(stringResource(R.string.reliquary_permission_denied_action_grant))
        }
        // Fallback only — reaches the system app-settings page for the
        // permanently-denied case (system stops showing the in-app
        // dialog after the user denies with "don't ask again").
        OutlinedButton(
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
    isPinningInFlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val slots = (MAX_PINS_PER_WALK - photos.size).coerceAtLeast(0)
    var previewPhoto by remember { mutableStateOf<WalkPhoto?>(null) }

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
            PhotoCarousel(
                photos = photos,
                pinnedIds = photos.mapTo(mutableSetOf()) { it.id },
                onThumbnailCommit = { photo -> previewPhoto = photo },
            )
        }
    }

    val previewState = previewPhoto
    if (previewState != null) {
        val context = LocalContext.current
        val pinnedIds = photos.map { it.id }.toSet()
        PhotoPreviewSheet(
            photo = previewState,
            isPinned = previewState.id in pinnedIds,
            isPinningInFlight = isPinningInFlight,
            onPin = { onPinPhotos(listOf(previewState.photoUri.toUri())) },
            onOpenInGallery = {
                val intent = buildOpenInGalleryIntent(previewState.photoUri)
                try {
                    context.startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    android.util.Log.w("PhotoReliquary", "no activity to handle gallery intent")
                }
            },
            onDismiss = { previewPhoto = null },
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
            Text(
                stringResource(
                    if (slotsAvailable > 0) {
                        R.string.reliquary_action_add
                    } else {
                        R.string.reliquary_action_full
                    },
                ),
            )
        }
    }
}

