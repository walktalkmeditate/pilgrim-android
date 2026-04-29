// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.util.CustomTabs
import org.walktalkmeditate.pilgrim.ui.util.PlayStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val units by viewModel.distanceUnits.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = pilgrimType.heading,
                        color = pilgrimColors.ink,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.about_back_content_description),
                            tint = pilgrimColors.ink,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pilgrimColors.parchment,
                ),
            )
        },
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            HeroSection()
            SectionDivider()
            PillarsSection()
            SectionDivider()
            if (stats.hasWalks) {
                StatsWhisperSection(stats = stats, units = units)
                FootprintTrailSection()
                SectionDivider()
            }
            OpenSourceSection()
            SectionDivider()
            MottoSection()
            SeasonalVignetteSection()
            VersionSection()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun HeroSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PilgrimLogo(size = 80.dp, breathing = true)
        Text(
            text = stringResource(R.string.about_hero_title),
            style = pilgrimType.displayMedium.copy(fontStyle = FontStyle.Italic),
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.about_hero_body),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun PillarsSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.about_pillars_caption),
            style = pilgrimType.caption.copy(letterSpacing = 3.sp),
            color = pilgrimColors.stone,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        PillarRow(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            tint = pilgrimColors.moss,
            title = stringResource(R.string.about_pillar_walk_title),
            description = stringResource(R.string.about_pillar_walk_description),
        )
        PillarRow(
            icon = Icons.Outlined.ChatBubbleOutline,
            tint = pilgrimColors.dawn,
            title = stringResource(R.string.about_pillar_talk_title),
            description = stringResource(R.string.about_pillar_talk_description),
        )
        PillarRow(
            icon = Icons.Filled.NightsStay,
            tint = pilgrimColors.stone,
            title = stringResource(R.string.about_pillar_meditate_title),
            description = stringResource(R.string.about_pillar_meditate_description),
        )
    }
}

@Composable
private fun PillarRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    description: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = description,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun StatsWhisperSection(stats: AboutStats, units: UnitSystem) {
    var phase by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp)
            .clickable { phase = (phase + 1) % 3 },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "about-stats-value",
        ) { current ->
            Text(
                text = when (current) {
                    1 -> stats.walkCount.toString()
                    2 -> formatSinceDate(stats)
                    else -> formatDistance(stats.totalDistanceMeters, units)
                },
                style = pilgrimType.statValue,
                color = pilgrimColors.stone,
            )
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "about-stats-label",
        ) { current ->
            Text(
                text = stringResource(
                    when (current) {
                        1 -> if (stats.walkCount == 1) {
                            R.string.about_stats_walk_taken
                        } else {
                            R.string.about_stats_walks_taken
                        }
                        2 -> R.string.about_stats_walking_since
                        else -> R.string.about_stats_walked_with
                    },
                ),
                style = pilgrimType.caption.copy(fontStyle = FontStyle.Italic),
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun FootprintTrailSection() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(4) { index ->
            val alpha = 0.08f + index * 0.04f
            val flipX = if (index % 2 == 0) 1f else -1f
            val rotation = if (index % 2 == 0) -10f else 10f
            val tintColor = pilgrimColors.stone.copy(alpha = alpha)
            Canvas(
                modifier = Modifier
                    .size(width = 12.dp, height = 18.dp)
                    .scale(scaleX = flipX, scaleY = 1f)
                    .rotate(rotation),
            ) {
                drawPath(
                    path = FootprintShape.path(size.width, size.height),
                    color = tintColor,
                )
            }
        }
    }
}

@Composable
private fun OpenSourceSection() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.about_open_source_header),
            style = pilgrimType.caption.copy(letterSpacing = 2.sp),
            color = pilgrimColors.stone.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.about_open_source_body),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(8.dp))
        OpenSourceLinkRow(
            icon = Icons.Filled.Public,
            label = stringResource(R.string.about_link_website),
            external = false,
            onClick = {
                CustomTabs.launch(context, Uri.parse("https://walktalkmeditate.org"))
            },
        )
        OpenSourceLinkRow(
            icon = Icons.Filled.Code,
            label = stringResource(R.string.about_link_github),
            external = false,
            onClick = {
                CustomTabs.launch(
                    context,
                    Uri.parse("https://github.com/walktalkmeditate/pilgrim-android"),
                )
            },
        )
        OpenSourceLinkRow(
            icon = Icons.Outlined.FavoriteBorder,
            label = stringResource(R.string.about_link_rate),
            external = true,
            onClick = { PlayStore.openListing(context) },
        )
    }
}

@Composable
private fun OpenSourceLinkRow(
    icon: ImageVector,
    label: String,
    external: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = pilgrimColors.stone,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.stone,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (external) {
                Icons.AutoMirrored.Filled.OpenInNew
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = null,
            tint = pilgrimColors.stone.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun MottoSection() {
    Text(
        text = stringResource(R.string.about_motto),
        style = pilgrimType.body.copy(fontStyle = FontStyle.Italic),
        color = pilgrimColors.stone,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
    )
}

@Composable
private fun SeasonalVignetteSection() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        SeasonalTree(
            color = pilgrimColors.stone.copy(alpha = 0.5f),
            modifier = Modifier.wrapContentWidth(),
            size = 40.dp,
        )
    }
}

@Composable
private fun VersionSection() {
    Text(
        text = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
        style = pilgrimType.caption,
        color = pilgrimColors.fog.copy(alpha = 0.3f),
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp),
    )
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color.Transparent,
                        pilgrimColors.stone.copy(alpha = 0.2f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

private fun formatDistance(meters: Double, units: UnitSystem): String {
    return when (units) {
        UnitSystem.Metric -> {
            if (meters >= 1_000) {
                String.format(Locale.US, "%.1f km", meters / 1_000)
            } else {
                String.format(Locale.US, "%.0f m", meters)
            }
        }
        UnitSystem.Imperial -> {
            val miles = meters / 1_609.344
            if (miles >= 1) {
                String.format(Locale.US, "%.1f mi", miles)
            } else {
                String.format(Locale.US, "%.0f ft", meters * 3.28084)
            }
        }
    }
}

private fun formatSinceDate(stats: AboutStats): String {
    val instant = stats.firstWalkInstant ?: return ""
    val formatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}
