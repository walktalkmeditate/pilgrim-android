# Phase 1 — Walk-tracking MVP

Covers the plan's Phase 1 deliverable: start a walk, track GPS in a
foreground service, save to Room, view a summary. Decomposed into
stages so each commit lands green.

Master plan: `/Users/rubberduck/.claude/plans/so-we-want-to-merry-scroll.md`.

## Stage 1-A: Data layer

**Goal**: Room database + entities + repository + Hilt module, locally
green on `./gradlew assembleDebug testDebugUnitTest`.

**Deliverables**:
- 6 `@Entity` classes (Walk, RouteDataSample, AltitudeSample, WalkEvent,
  ActivityInterval, Waypoint) with foreign keys to Walk and indices.
- Type converters for `WalkEventType` and `ActivityType` enums.
- One DAO per entity with Flow-based observe + suspend mutations.
- `PilgrimDatabase` with `exportSchema = true`; schema JSON committed.
- `DatabaseModule` Hilt `@Module` providing DB + DAOs.
- `WalkRepository` aggregating the DAOs behind a clean API.
- Robolectric + Room in-memory test proving insert/retrieve round-trip.

**Success criteria**: `./gradlew assembleDebug lintDebug testDebugUnitTest` green; schema JSON generated; Robolectric test passes.

**Status**: In Progress

## Stage 1-B: WalkBuilder state machine

**Goal**: Pure-Kotlin state machine driving walk lifecycle.

**Deliverables**:
- Sealed `WalkState` (Idle, Active, Paused, Meditating, Finished).
- `WalkEvent` (the domain event, not the DB entity) sealed class for
  transitions (Start, Pause, Resume, MeditateStart, MeditateEnd, Finish,
  LocationSampled, AltitudeSampled, StepTick).
- `WalkReducer` pure function `(WalkState, WalkEvent) -> WalkState`.
- Live-stats derivations (duration, distance, pace, elevation).
- Unit tests: all transitions, idempotent re-entries, invalid transitions.

**Status**: Not Started

## Stage 1-C: Location tracking + foreground service

**Goal**: `WalkTrackingService` that drives the state machine with real
GPS samples from `FusedLocationProviderClient`, persists to Room, and
surfaces live stats in its notification.

**Deliverables**:
- Permissions declared in manifest: `ACCESS_FINE_LOCATION`,
  `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`,
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`.
- `WalkTrackingService` (`@AndroidEntryPoint`, foreground, `foregroundServiceType="location"`).
- `LocationSource` wrapping FusedLocationProviderClient with a Flow API.
- Notification channel + live-updating notification (duration, distance).
- Service binding interface for UI state.
- Unit test: service lifecycle mocked.

**Status**: Not Started

## Stage 1-D: Permissions + battery-exemption UX

**Goal**: First-run permission flow that reliably obtains background
location + notification permission + battery optimization exemption on
the device-under-test.

**Deliverables**:
- `PermissionsScreen` (Compose) with rationale copy.
- Sequential permission request pattern (fine → background per API 30+ dance).
- Battery optimization exemption prompt gated on a user-facing "why".
- First-run state persisted in DataStore.

**Status**: Not Started

## Stage 1-E: Active Walk + Walk Summary screens (no map yet)

**Goal**: Compose UI for starting, running, and reviewing a walk, with a
placeholder where the map goes.

**Deliverables**:
- `ActiveWalkScreen` with stats + pause/resume/stop/waypoint controls.
- `WalkSummaryScreen` with stats breakdown.
- `WalkViewModel` backed by `WalkRepository` + `WalkTrackingService` binding.
- Navigation Compose graph.
- Compose UI tests for start/pause/finish flow.

**Status**: Not Started

## Stage 1-F: Mapbox SDK integration

**Goal**: Render live route + static summary map. Gated on Mapbox tokens
(access + downloads), which need to be set up in `local.properties`.

**Deliverables**:
- Mapbox Maven repo registered in `settings.gradle.kts` (conditional on
  `MAPBOX_DOWNLOADS_TOKEN`).
- Mapbox Android SDK dependency.
- `PilgrimMap` Compose wrapper with custom style URL.
- Route polyline update on live location.
- Walk Summary static map snapshot.

**Status**: Blocked on MAPBOX_DOWNLOADS_TOKEN in local.properties.

## Stage 1-G: Long-walk device test

**Goal**: Record a 30+ minute walk on a real device with the screen off;
all samples preserved; app survives backgrounding.

**Status**: Not Started — requires Stage 1-A through 1-F complete.
