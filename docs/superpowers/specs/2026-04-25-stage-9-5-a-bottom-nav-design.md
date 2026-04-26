# Stage 9.5-A — Bottom-Nav Restructure + WalkStartScreen Design Spec

## Goal

Restructure Android navigation to mirror iOS's 3-tab model (Path / Journal / Settings) and port iOS's `WalkStartView` to the new Path tab. This is the foundation for the Phase 9.5 design-parity work — every subsequent stage assumes this navigation model.

## High-level approach

`MainActivity` drops its `Scaffold` entirely (just calls `PilgrimNavHost` at the root inside `PilgrimTheme`). `PilgrimNavHost` owns the only Scaffold in the chain — its `bottomBar` slot conditionally renders a Material3 `NavigationBar` based on whether the current route is in `TAB_ROUTES = {PATH, HOME, SETTINGS}`. Tab transitions use Compose Nav's canonical idiom: `popUpTo(graph.findStartDestination().id) { saveState = true } + restoreState = true + launchSingleTop = true`.

The new `WalkStartScreen` ports iOS WalkStartView's structure: parchment background with time-of-day tint, breathing logo, rotating quote, moon-phase glyph, 3-mode selector (text-only buttons with underline; footprint shapes deferred), and a big primary action button. WalkMode is added to the domain layer as an enum with `.wander` available + `.together`/`.seek` showing "coming soon" subtitles and disabled button (mirrors iOS `WalkMode.isAvailable`).

When the user lands on the Path tab via cold launch and a walk is already in progress (controller restored from a crash), `WalkStartScreen` redirects to `ACTIVE_WALK` exactly once via a `hasSeenIdle` latch + one-shot `LaunchedEffect(Unit)`. This matches iOS's `fullScreenCover` semantics where `WalkStartView` is unreachable during a walk. To prevent the user from re-entering Path via system Back during a walk (which would re-trigger the redirect and create a Back-loop), `ACTIVE_WALK` installs a `BackHandler { /* no-op */ }`.

## What I considered and rejected

- **Single-screen Path with no WalkMode selector** — simplest possible, but loses the iOS contemplative pre-walk hub feel. Rejected because the mode selector + quote + moon glyph IS the screen.
- **Defer WalkMode enum until Phase N differentiation** — could route all 3 buttons to the same `startWalk()` call without the enum. Rejected because the "coming soon" disabled state is critical UX (sets expectations) and the enum costs ~15 LOC.
- **Keep Settings as pushed route only** — would let the user reach Settings via a gear button anywhere. Rejected because iOS unambiguously has Settings as a tab.
- **Hide bottom bar during walkSummary** — iOS shows the summary as `.sheet` with the tab bar dimmed-but-present. Android nav-pushed walkSummary HIDES the bar (acceptable divergence — Android lacks built-in modal sheets without third-party deps). For 9.5-A, the bar is visible iff route is one of PATH/HOME/SETTINGS; everything else hides it.

---

## Spec

### 1. New domain type: `WalkMode`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkMode.kt` (new)

```kotlin
enum class WalkMode {
    Wander, Together, Seek;

    val isAvailable: Boolean get() = this == Wander
}
```

Display strings + quote pools + button labels live in `strings.xml` (next section). For 9.5-A the enum is only consumed by `WalkStartScreen`; functional differentiation is out of scope. All 3 buttons (only Wander enabled) call `walkController.startWalk(intention = null)` unchanged.

### 2. New string resources

**File:** `app/src/main/res/values/strings.xml` (modify)

**Critical Android string-resource note:** Android's resource compiler reads `\\n` (escaped backslash + n) and produces a newline. Plain `\n` in XML is an XML escape that the parser rejects/preserves literally. iOS's `\n` from Localizable.strings does NOT transfer 1:1 — every newline below uses `\\n`.

```xml
<!-- Stage 9.5-A: Bottom-nav tab labels -->
<string name="tab_path">Path</string>
<string name="tab_journal">Journal</string>
<string name="tab_settings">Settings</string>

<!-- Stage 9.5-A: WalkStartScreen (Path tab) -->
<string name="path_mode_wander">WANDER</string>
<string name="path_mode_together">TOGETHER</string>
<string name="path_mode_seek">SEEK</string>
<string name="path_mode_wander_subtitle">walk · talk · meditate</string>
<string name="path_mode_together_subtitle">walk with others nearby</string>
<string name="path_mode_seek_subtitle">follow the unknown</string>
<string name="path_mode_unavailable_subtitle">coming soon</string>
<string name="path_button_wander">Wander</string>
<string name="path_button_together">Walk Together</string>
<string name="path_button_seek">Seek</string>
<string name="path_start_a11y">Begin your journey</string>

<!-- Stage 9.5-A: Quote corpora as string-arrays (indexable by ordinal at runtime) -->
<string-array name="path_quotes_wander">
    <item>Every journey begins\nwith a single step</item>
    <item>The path is made\nby walking</item>
    <item>Not all who wander\nare lost</item>
    <item>Solvitur ambulando —\nit is solved by walking</item>
    <item>Walk as if you are kissing\nthe earth with your feet</item>
    <item>The journey of a thousand miles\nbegins beneath your feet</item>
</string-array>
<string-array name="path_quotes_together">
    <item>We walk together,\nthough our paths differ</item>
    <item>In good company,\nthe road seems shorter</item>
    <item>Side by side,\nstep by step</item>
</string-array>
<string-array name="path_quotes_seek">
    <item>What you seek\nis seeking you</item>
    <item>Wander where\nthe wi-fi is weak</item>
    <item>The real voyage of discovery\nis seeing with new eyes</item>
</string-array>
```

### 3. New routes

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (modify)

Add to the `Routes` object:

```kotlin
const val PATH = "path"
```

`HOME` (the journal scroll) and `SETTINGS` are existing routes — they become tab destinations alongside the new `PATH` route.

### 4. New `WalkStartScreen` Composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreen.kt` (new)

#### 4.1 Layout (top to bottom)

iOS structure (per `WalkStartView.swift:147`): `VStack { ScrollView { … VStack with logo/quote/moon … } ; modeSelector ; Button }`. Android equivalent uses a non-scrolling outer Column with `Modifier.weight(1f)` Spacers around the centered content; the bottom selector + button live in the same Column at fixed height. Only the centered content (logo/quote/moon) is in a `verticalScroll` so it can overflow on small screens.

```
Box(Modifier.fillMaxSize()):
  ├ Background: Box(Modifier.fillMaxSize().background(parchment))
  └ Column(Modifier.fillMaxSize().padding(PilgrimSpacing.big)):
       ├ Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())):
       │   Column(Modifier.fillMaxSize(), horizontalAlignment = CenterHorizontally,
       │          verticalArrangement = Arrangement.Center):
       │      ├ BreathingLogo (size = 100dp; or 60dp when LocalDensity.fontScale > 1.5f)
       │      ├ Spacer(PilgrimSpacing.big)
       │      ├ Text(currentQuote, style = displayMedium, color = fog,
       │      │      textAlign = TextAlign.Center, maxLines = 4)
       │      ├ Spacer(PilgrimSpacing.big)
       │      └ MoonPhaseGlyph(phase = lunarPhase, size = 44dp)
       ├ ModeSelector(selectedMode, onSelect)
       ├ Spacer(PilgrimSpacing.normal)
       └ Button(
            onClick = onStartTapped,
            enabled = selectedMode.isAvailable && !starting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = pilgrimColors.stone,
                                                  contentColor = pilgrimColors.parchment),
         ) { Text(label) }
```

#### 4.2 State management

```kotlin
@Composable
fun WalkStartScreen(
    walkViewModel: WalkViewModel = hiltViewModel(),
    onEnterActiveWalk: () -> Unit,
) {
    val context = LocalContext.current
    var selectedMode by rememberSaveable { mutableStateOf(WalkMode.Wander) }
    var currentQuote by rememberSaveable(selectedMode) {
        mutableStateOf(pickRandomQuote(context, selectedMode))
    }
    val lunarPhase = remember { MoonCalc.moonPhase(Instant.now()) }
    var starting by remember { mutableStateOf(false) }
    val walkState by walkViewModel.uiState.collectAsStateWithLifecycle()

    // Auto-redirect on cold-launch with already-in-progress walk.
    // hasSeenIdle latch + one-shot LaunchedEffect(Unit) per Stage 5-A
    // memory: a state-class-keyed observer would re-fire the navigate
    // on every Active → Paused → Meditating sub-state transition,
    // re-entering ACTIVE_WALK from Path's saved-back-stack composition.
    val didCheck = rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didCheck.value) return@LaunchedEffect
        didCheck.value = true
        if (walkState.walkState.isInProgress) {
            onEnterActiveWalk()
            return@LaunchedEffect
        }
        val restored = walkViewModel.restoreActiveWalk()
        if (restored != null) onEnterActiveWalk()
    }

    Box(...) { ... }
}
```

**Why `hiltViewModel()`** — WalkController underneath is `@Singleton`, so Path's WalkViewModel and Home's WalkViewModel observe the same controller state. The wrapper VM instances differ per NavBackStackEntry, but that's fine — neither holds state that needs cross-tab synchronization (`WalkController` is the source of truth). This matches the existing HomeScreen pattern (HomeScreen.kt:85 uses `hiltViewModel()`).

**Why `rememberSaveable(selectedMode)` for `currentQuote`** — survives both rotation AND tab-switch saved-state restore. With plain `remember(selectedMode)`, switching to Settings tab and back would re-pick a fresh quote on every visit (defeats verification step 9).

#### 4.3 `pickRandomQuote` API

**File:** Same file (`WalkStartScreen.kt`), package-private.

```kotlin
internal fun pickRandomQuote(
    context: Context,
    mode: WalkMode,
    random: Random = Random.Default,
): String {
    val arrayId = when (mode) {
        WalkMode.Wander -> R.array.path_quotes_wander
        WalkMode.Together -> R.array.path_quotes_together
        WalkMode.Seek -> R.array.path_quotes_seek
    }
    val quotes = context.resources.getStringArray(arrayId)
    return quotes[random.nextInt(quotes.size)]
}
```

The `random` param is injectable for test determinism.

**No timer-based rotation.** Quotes re-roll only on mode change (matches iOS — iOS uses `onChange(of: selectedMode)`, not a timer). Resolves the spec contradiction the reviewer flagged.

#### 4.4 Mode selector

```kotlin
@Composable
private fun ModeSelector(
    selectedMode: WalkMode,
    onSelect: (WalkMode) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            WalkMode.values().forEach { mode ->
                ModeButton(
                    mode = mode,
                    selected = mode == selectedMode,
                    onClick = {
                        if (mode != selectedMode) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(mode)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        AnimatedContent(targetState = selectedMode, label = "mode-subtitle") { mode ->
            val subtitleId = if (mode.isAvailable) {
                when (mode) {
                    WalkMode.Wander -> R.string.path_mode_wander_subtitle
                    WalkMode.Together -> R.string.path_mode_together_subtitle
                    WalkMode.Seek -> R.string.path_mode_seek_subtitle
                }
            } else R.string.path_mode_unavailable_subtitle
            Text(
                stringResource(subtitleId),
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.5f),
            )
        }
    }
}
```

`ModeButton` renders the uppercase label + a 2dp underline that's filled when selected, transparent otherwise. No footprint shapes (deferred to future polish).

#### 4.5 Start-button onClick

```kotlin
val onStartTapped: () -> Unit = remember {
    {
        if (!starting) {
            starting = true
            walkViewModel.startWalk()
            // Auto-redirect to ACTIVE_WALK is handled by walkState
            // observer on next emission. The `starting` flag prevents
            // double-tap fan-out during the dispatch-to-emit window.
            // It does NOT reset (this VM instance is one-shot for the
            // current Path composition; if user comes back to Path,
            // a fresh `starting` is allocated by `remember`).
        }
    }
}
```

When state flips to Active, the user is on ACTIVE_WALK. When they return to Path later (after finishing), this VM may be a fresh instance (if Compose Nav cleared it) or restored — `starting` is `remember` (NOT saveable), so it always starts false on a fresh composition.

### 4.6 Reduced-motion accessibility

`BreathingLogo` and `MoonPhaseGlyph`'s glow pulse should respect the OS reduced-motion setting. Add a top-level helper:

```kotlin
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        // API 33+ has isAudioDescriptionRequested + transition animation scale checks.
        // Simplest reliable check: Settings.Global.TRANSITION_ANIMATION_SCALE == 0.
        try {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
                1.0f,
            ) == 0.0f
        } catch (_: Throwable) {
            false
        }
    }
}
```

Both `BreathingLogo` and `MoonPhaseGlyph` accept an optional `reducedMotion: Boolean = rememberReducedMotion()` parameter; when true they render statically.

### 5. New `BreathingLogo` Composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/BreathingLogo.kt` (new)

```kotlin
@Composable
fun BreathingLogo(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val scale = if (reducedMotion) {
        1.0f
    } else {
        val infinite = rememberInfiniteTransition(label = "logo-breath")
        infinite.animateFloat(
            initialValue = 1.0f, targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scale",
        ).value
    }
    Image(
        painter = painterResource(R.drawable.ic_pilgrim_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.18f))
            .graphicsLayer { scaleX = scale; scaleY = scale },
    )
}
```

**Logo asset note:** Spec creates a NEW vector drawable `R.drawable.ic_pilgrim_logo` derived from the iOS asset. The existing `R.drawable.ic_launcher_foreground` has the Android adaptive-icon 33% safe-zone padding (foreground content inset by 18%), which would render the logo visually small inside its rounded rectangle. The new `ic_pilgrim_logo.xml` is edge-to-edge (matches iOS's `Image("pilgrimLogo")`).

For 9.5-A, if creating a custom edge-to-edge vector from scratch is too much scope, accept the launcher-foreground as a known-imperfect placeholder and ship a TODO comment + GitHub issue. Spec recommends the placeholder approach for 9.5-A speed; ship the proper asset in a follow-up polish PR.

`graphicsLayer { scaleX = scale; scaleY = scale }` (lambda form) per Stage 5-A memory — `Modifier.scale(value)` would cause composition-phase reads on every animation frame.

### 6. New `MoonPhaseGlyph` Composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/MoonPhaseGlyph.kt` (new)

#### 6.1 `isWaxing` derivation

Add to `MoonPhase.kt` (NOT to `MoonPhaseGlyph.kt`) as a computed property — single source of truth, available to any future consumer:

```kotlin
@Immutable
data class MoonPhase(
    val name: String,
    val illumination: Double,
    val ageInDays: Double,
) {
    /**
     * True if the moon is in its waxing half (new → full). False if
     * waning (full → new). Synodic month is split symmetrically at
     * its midpoint; ageInDays at exactly the midpoint is treated as
     * already-waning (boundary inclusive on the waning side, matching
     * astronomical convention that "first quarter" + "last quarter"
     * are exact instants, not half-day windows).
     */
    val isWaxing: Boolean
        get() = ageInDays < MoonCalc.SYNODIC_DAYS / 2.0
}
```

This adds a computed property; data-class equality is unaffected (only constructor params count). `MoonCalc.SYNODIC_DAYS` is the authoritative constant (29.530588770576).

#### 6.2 Glyph rendering

Compose Canvas port of iOS `MoonPhaseShape`:

```kotlin
@Composable
fun MoonPhaseGlyph(
    phase: MoonPhase,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val pulse = if (reducedMotion) 1.0f else animatedPulse()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Canvas(modifier = Modifier.size(size * 3)) {
            drawMoonPhaseGlow(pulse, color = silverColor())
            drawMoonPhase(
                illumination = phase.illumination,
                isWaxing = phase.isWaxing,
                radiusPx = size.toPx() / 2,
                color = silverColor(),
            )
        }
        Text(phase.name, style = pilgrimType.annotation, color = pilgrimColors.fog)
    }
}

internal fun DrawScope.drawMoonPhase(
    illumination: Double,
    isWaxing: Boolean,
    radiusPx: Float,
    color: Color,
) {
    // Port of iOS MoonPhaseShape.path(in:):
    //   - illumination > 0.95 → full circle
    //   - illumination < 0.05 → empty path
    //   - else: half-circle arc + bezier curve forming the terminator
    // ...
}
```

`drawMoonPhase` is `internal fun DrawScope.drawMoonPhase(...)` so a Robolectric test can extract its `Path` via a stub `DrawScope` or test the path directly via a non-Composable variant returning `Path`. Per Stage 3-C lesson: Robolectric Canvas is a stub; test path construction directly, not draw output.

For 9.5-A: light-mode rendering only (silver gradient + soft glow). Dark-mode variant (ink-color gradient) deferred.

### 7. Restructured `PilgrimNavHost` + new tab Scaffold

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (modify)

#### 7.1 Inset handling — `MainActivity` drops Scaffold

Current MainActivity.kt:39:
```kotlin
Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
    PilgrimNavHost(
        modifier = Modifier.padding(innerPadding),
        ...
    )
}
```

Becomes:
```kotlin
PilgrimNavHost(
    pendingDeepLink = deepLink,
    onDeepLinkConsumed = { ... },
)
```

`PilgrimNavHost` becomes the sole owner of the Scaffold + window-insets. `MainActivity` only owns `enableEdgeToEdge()` + the `setContent { PilgrimTheme { ... } }` wrap. This eliminates the double-Scaffold inset cascade the reviewer flagged: insets are consumed exactly once.

#### 7.2 New Scaffold + bottom-bar gating

```kotlin
@Composable
fun PilgrimNavHost(
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
    pendingDeepLink: DeepLinkTarget? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
            ) {
                PilgrimBottomBar(
                    currentRoute = currentRoute,
                    onSelectTab = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PERMISSIONS,
            modifier = Modifier.padding(innerPadding),
        ) {
            // ... existing 14 composable() entries ...
            composable(Routes.PATH) {
                WalkStartScreen(
                    onEnterActiveWalk = {
                        navController.navigate(Routes.ACTIVE_WALK)
                    },
                )
            }
        }
    }
}

private val TAB_ROUTES = setOf(Routes.PATH, Routes.HOME, Routes.SETTINGS)

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
```

`AnimatedVisibility` resolves the visibility-flicker concern (C6) — bar fades in/out over 150ms instead of snapping. Content reflow during the fade is acceptable.

#### 7.3 `PERMISSIONS` auto-nav target — PATH not HOME

```kotlin
LaunchedEffect(onboardingComplete, currentEntry?.destination?.route) {
    if (
        onboardingComplete &&
        PermissionChecks.isMinimumGranted(context) &&
        currentEntry?.destination?.route == Routes.PERMISSIONS
    ) {
        navController.navigate(Routes.PATH) {  // was: Routes.HOME
            popUpTo(Routes.PERMISSIONS) { inclusive = true }
        }
    }
}
```

Update the comment block above to say "Auto-nav to Path is in flight; wait for it to land before consuming the deep link." (was: "Auto-nav to HOME is in flight").

#### 7.4 Deep-link target updates

The Stage 9-A/9-B deep-link `LaunchedEffect` references `Routes.HOME` in three places:
- Line 273-277: PERMISSIONS-route gate's wait — comment update only (auto-nav target changed PATH); still waits for ANY route change away from PERMISSIONS.
- Line 287-289: `DeepLinkTarget.WalkSummary` branch's `popUpTo(Routes.HOME)` — KEEP. WalkSummary deep-link should land in journal context (Back lands on Home/Journal), not Path.
- Line 290-296: `DeepLinkTarget.Home` branch — KEEP. The widget Home target navigates to Home tab, not Path.
- Line 298-302: `DeepLinkTarget.ActiveWalk` branch's `popUpTo(Routes.HOME)` — CHANGE to `popUpTo(Routes.PATH)`. ActiveWalk deep-link should land with Path in the back stack (Back from ACTIVE_WALK pops to Path; combined with §4.2's hasSeenIdle latch, this won't loop because the latch already fired on the previous composition).

#### 7.5 `composable(Routes.SETTINGS)` call site

Currently passes `onBack = { navController.popBackStack() }`. Change to pass `onBack = null` (per §11) — Settings is now a tab destination with no back arrow.

#### 7.6 `composable(Routes.HOME)` call site — drop `onEnterSettings`

HomeScreen no longer takes an `onEnterSettings` parameter (settings reached via tab bar). Drop the lambda from the call site.

Likewise drop the `onEnterActiveWalk` start-walk binding (HomeScreen no longer has the start-walk button); HomeScreen still calls `onEnterActiveWalk` from its existing resume-check `LaunchedEffect`, so keep that lambda.

### 8. New `PilgrimBottomBar` Composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimBottomBar.kt` (new)

```kotlin
@Composable
fun PilgrimBottomBar(
    currentRoute: String?,
    onSelectTab: (String) -> Unit,
) {
    NavigationBar(
        containerColor = pilgrimColors.parchmentSecondary,  // was: parchment (no separation)
        contentColor = pilgrimColors.stone,
        tonalElevation = 0.dp,
    ) {
        TabItem(
            route = Routes.PATH, currentRoute = currentRoute,
            label = R.string.tab_path,
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            onSelect = onSelectTab,
        )
        TabItem(
            route = Routes.HOME, currentRoute = currentRoute,
            label = R.string.tab_journal,
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            onSelect = onSelectTab,
        )
        TabItem(
            route = Routes.SETTINGS, currentRoute = currentRoute,
            label = R.string.tab_settings,
            icon = Icons.Outlined.Settings,
            onSelect = onSelectTab,
        )
    }
}

@Composable
private fun RowScope.TabItem(
    route: String,
    currentRoute: String?,
    @StringRes label: Int,
    icon: ImageVector,
    onSelect: (String) -> Unit,
) {
    NavigationBarItem(
        selected = currentRoute == route,
        onClick = { onSelect(route) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = pilgrimColors.stone,
            unselectedIconColor = pilgrimColors.fog,
            selectedTextColor = pilgrimColors.stone,
            unselectedTextColor = pilgrimColors.fog,
            indicatorColor = pilgrimColors.parchment,
        ),
    )
}
```

`parchmentSecondary` provides visual separation from the screen content above (which is on `parchment`). Verify `parchmentSecondary` is defined in `Color.kt` — if not, add it during implementation.

### 9. ACTIVE_WALK back-button handling

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` (modify)

Add a `BackHandler { /* no-op */ }` at the top of the ActiveWalkScreen composable to disable system Back during a walk. This matches iOS's `fullScreenCover` UX (no Back from a walk) and prevents the C5 Back-loop (Path's auto-redirect would otherwise immediately bounce the user back into ACTIVE_WALK).

```kotlin
@Composable
fun ActiveWalkScreen(...) {
    BackHandler {
        // Walks don't dismiss via system Back. The user must explicitly
        // tap Finish (or finish via the notification action). Without
        // this no-op, system Back pops to Path, where Path's auto-redirect
        // immediately re-navigates to ACTIVE_WALK — visible flash.
    }
    ...
}
```

Same treatment on `MeditationScreen.kt` (already-existing `BackHandler` per Stage 5-A — verify it's still there; if not, add).

### 10. HomeScreen cleanup

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` (modify)

Changes (line numbers reference current file):
- **Line 117-134** (TopAppBar Row with title + settings IconButton): Drop the `IconButton(onClick = onEnterSettings)` block; keep the title `Text`.
- **Line 145-153** (`Button(onClick = startWalk + onEnterActiveWalk)`): Remove entirely. Wander button now lives on Path tab.
- **Line 157-162** (`OutlinedButton(onClick = onEnterGoshuin)`): Remove from the in-Column flow; replace with a `FloatingActionButton` (per §10.1 below).
- **Line 79-86** (function signature): Drop `onEnterSettings: () -> Unit` parameter (no callers anymore). Keep `onEnterGoshuin` (FAB needs it).
- **KEEP** the resume-check `LaunchedEffect(Unit)` — see §10.2.

#### 10.1 Goshuin FAB positioning

Wrap HomeScreen's existing Column inside a `Box(Modifier.fillMaxSize())`. Place the FAB as a sibling aligned to BottomEnd:

```kotlin
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterGoshuin: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    // ... resume-check LaunchedEffect ...

    Box(modifier = Modifier.fillMaxSize()) {
        // Existing Column content (without start-walk + view-goshuin buttons).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.big),
        ) {
            // ... title + JournalThread + BatteryExemptionCard ...
        }

        // Goshuin FAB. Inner-padding from Scaffold already accounts
        // for the bottom bar; add PilgrimSpacing.big for breathing room.
        FloatingActionButton(
            onClick = onEnterGoshuin,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PilgrimSpacing.big),
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.stone,
        ) {
            Icon(
                imageVector = Icons.Outlined.Explore,  // compass-like
                contentDescription = stringResource(R.string.home_action_view_goshuin),
            )
        }
    }
}
```

The `Scaffold`'s `innerPadding` (passed to `NavHost`'s `Modifier.padding(innerPadding)`) already insets HomeScreen's content above the bottom bar. The FAB sits inside that inset zone, so no extra `navigationBarsPadding()` needed.

#### 10.2 Resume-check on Home

Keep the existing resume-check LaunchedEffect. Path's WalkStartScreen has its own (per §4.2) for the new default-landing flow. Home's continues to handle the legacy/edge case where the user navigates to Journal tab while a walk is silently in progress in another scope. Both checks use `restoreActiveWalk()` which is dispatchMutex-locked + idempotent (no-op if state already non-Idle), so duplicate firing across tab switches is harmless.

(The reviewer flagged this as potentially-dead duplication. It's belt-and-braces: minimal cost, eliminates a class of "missed restore" bugs at tab boundaries.)

### 11. SettingsScreen back-arrow logic

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt` (modify)

Change `onBack: () -> Unit` parameter to `onBack: (() -> Unit)? = null`:
- `onBack != null` → render the `ArrowBack` IconButton in the TopAppBar's `navigationIcon` slot
- `onBack == null` → no `navigationIcon` (tab-mode default for 9.5-A)

In PilgrimNavHost's `composable(Routes.SETTINGS)`, pass `onBack = null`.

### 12. Bottom-bar visibility set

**Definition:** `private val TAB_ROUTES = setOf(Routes.PATH, Routes.HOME, Routes.SETTINGS)`

**Routes that HIDE the bar:** ACTIVE_WALK, MEDITATION, walkSummary, walkShare, GOSHUIN, voice-guide picker, voice-guide detail, soundscape picker.

This matches the immersive-walk semantic (ACTIVE_WALK / MEDITATION) and treats other pushed surfaces (walkSummary, GOSHUIN, etc.) as full-screen takeovers without a tab bar. Acceptable divergence from iOS, which keeps the tab bar visible during `.sheet` modals.

### 13. Test coverage

**New test files:**

1. **`WalkModeTest.kt`** — `isAvailable` returns true ONLY for Wander.
2. **`WalkStartScreenTest.kt`** — Robolectric Compose tests:
   - mode selector displays all 3 modes
   - tapping Together/Seek selects them but button stays disabled
   - tapping Wander selects + button enabled + onStartTapped fires
   - mode change re-rolls the quote (inject seeded `Random` to assert deterministic re-roll)
   - cold-launch with `walkState.isInProgress = true` fires `onEnterActiveWalk` exactly once on first composition
   - sub-state transitions (Active → Paused → Meditating) do NOT re-fire `onEnterActiveWalk` (regression guard for C2)
3. **`MoonPhaseGlyphTest.kt`** — extract `drawMoonPhase` as `internal fun` returning `Path` (NOT a `DrawScope` extension); test path bounds + arc count for illumination=0.0/0.25/0.5/0.75/1.0; test `isWaxing = true` vs `false` produces mirrored paths.
4. **`MoonPhaseTest.kt`** — `isWaxing` true at ageInDays=0, true at 14.0, false at 14.8 (just past midpoint), false at 28.0.
5. **`PilgrimNavHostTest.kt`** — Robolectric Compose test:
   - bottom bar visible on PATH/HOME/SETTINGS
   - bottom bar hidden on ACTIVE_WALK/MEDITATION/walkSummary
   - `navigateToTab` preserves saveState (push then pop, state restored)
   - PERMISSIONS auto-nav lands on PATH (not HOME)
6. **`PilgrimBottomBarTest.kt`** — Robolectric test that calls `NavigationBar` builder paths + `.build()` on `Notification`-style platform objects (per CLAUDE.md "Platform-object builder tests" rule applied to NavigationBar's color-token validation).
7. **`SettingsScreenTest.kt`** — update existing test (or add new test method): `onBack == null` → no back arrow rendered; `onBack != null` → back arrow present + invokes the lambda.

**Modified test files:**

8. **`HomeScreenTest.kt`** (if exists) — drop assertions on the start-walk button + settings IconButton (no longer present). Add assertion that the goshuin FAB is rendered + clickable.

### 14. Open questions resolved

1. **Goshuin FAB positioning** — RESOLVED: bottom-end aligned in a Box overlay, see §10.1.
2. **Path tab default selection survives process death?** — `rememberSaveable` for selectedMode handles config + tab-switch; for true process death restoration we'd need DataStore. NOT a 9.5-A concern (iOS doesn't persist either).
3. **Quote rotation cadence** — RESOLVED: NO timer rotation; quotes re-roll only on mode change (matches iOS). See §4.2/§4.3.

---

## Verification (manual)

1. Cold launch: PERMISSIONS → auto-nav to Path tab. Bottom bar shows with Path selected.
2. Tap Journal tab → Home screen. Bottom bar shows with Journal selected. Goshuin FAB visible bottom-right (above bar).
3. Tap Settings tab → SettingsScreen, no back arrow, centered "Settings" title.
4. Tap Path tab from Settings → Path screen with breathing logo, rotating quote (different from initial), moon glyph, mode selector (Wander selected), big Wander button.
5. Tap Together button → mode flips, subtitle becomes "coming soon", Wander button label changes to "Walk Together" but is disabled. Tap haptic fires.
6. Tap Wander → button briefly disabled (`starting=true`), state goes Active, ACTIVE_WALK route opens, bottom bar fades out (150ms).
7. Tap system Back during ACTIVE_WALK → no-op (BackHandler eats the event).
8. Tap in-app Finish in ACTIVE_WALK → finish flow fires, walkSummary opens, bottom bar still hidden.
9. Tap Done in walkSummary → navigates to HOME (Journal tab); bottom bar fades in.
10. Tab back-stack preservation: navigate Path → Journal → Settings → Path; Path's WalkStartScreen preserves `selectedMode` AND `currentQuote` (both rememberSaveable).
11. Cold launch with widget deep-link to walkSummary → PERMISSIONS skipped, summary opens with bar HIDDEN, Done lands on HOME tab with bar visible.
12. Cold launch with notification body deep-link to ACTIVE_WALK while a walk is in progress → ACTIVE_WALK opens with bar HIDDEN; system Back is no-op.
13. Cold launch with crash-restored walk: PERMISSIONS auto-nav → PATH → resume-check fires → restoreActiveWalk returns non-null → onEnterActiveWalk navigates to ACTIVE_WALK. Stack: [PATH, ACTIVE_WALK]. System Back is no-op.
14. Reduced-motion enabled (Settings > Accessibility > Animation scale OFF): BreathingLogo renders static; MoonPhaseGlyph glow doesn't pulse.

---

## Non-goals (deferred)

- WalkMode functional differentiation (.together / .seek do something different) — Phase N
- Footprint shape Composables (wanderFootprints / togetherFootprints / seekFootprints) — future polish
- Ambient radial gradient drift on background — future polish
- Collective pulse on logo when walks happened in last hour — future polish
- Entrance staggered animations (showLogo / showQuote / showMoon) — future polish
- Time-of-day tinted radial gradient on background — future polish (start with flat parchment for 9.5-A)
- Dark-mode MoonPhaseGlyph variant (ink color gradient) — future polish (silver-only for 9.5-A)
- Edge-to-edge `ic_pilgrim_logo` vector asset — future polish (use `ic_launcher_foreground` placeholder for 9.5-A; ship a TODO + GitHub issue)
- Practice summary header on Path tab (iOS shows season + walk count + miles) — Stage 9.5-E
- Turning-day banner on journal — Stage 9.5-D

---

## Files

**New:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkMode.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreen.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/BreathingLogo.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/MoonPhaseGlyph.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimBottomBar.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkModeTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/MoonPhaseTest.kt` (new — `isWaxing` derivation)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreenTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/MoonPhaseGlyphTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHostTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimBottomBarTest.kt`

**Modified:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/MainActivity.kt` — drop Scaffold; just call PilgrimNavHost inside PilgrimTheme.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` — own Scaffold + bottomBar with AnimatedVisibility, TAB_ROUTES, navigateToTab helper, PATH composable, PERMISSIONS auto-nav target → PATH, deep-link comment + ActiveWalk popUpTo target updates, HOME `composable` call site drops `onEnterSettings`, SETTINGS `composable` passes `onBack = null`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` — drop settings IconButton + start-walk Button + view-goshuin OutlinedButton; wrap content in Box; add Goshuin FAB at BottomEnd; drop `onEnterSettings` parameter.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt` — `onBack: (() -> Unit)?` nullable; conditional `navigationIcon` rendering.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` — add `BackHandler { /* no-op */ }`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/MoonPhase.kt` — add `isWaxing` computed property using `MoonCalc.SYNODIC_DAYS / 2.0`.
- `app/src/main/res/values/strings.xml` — tab labels, mode strings, quote string-arrays (with `\\n` not `\n`).
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelTest.kt` — if existing tests assert on the start-button or settings-icon presence, update.
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreenTest.kt` (if exists) — update for nullable `onBack`.
