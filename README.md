> *The road is made by walking.*
> — Antonio Machado

# Pilgrim

A pilgrimage app for Android. Track your walks, capture voice reflections, sit in meditation. No accounts. No servers. No leaderboards. Everything stays on your device.

[pilgrimapp.org](https://pilgrimapp.org)

---

## What Pilgrim Is

Walking is thinking. It always has been. Aristotle walked while he taught. Wordsworth composed poems on foot. Matsuo Bashō walked the narrow road to the deep north and came back with haiku.

Pilgrim treats a walk as a creative practice — a moving meditation, a thinking space, a way of being in the world. The app holds your walk lightly: GPS route, pace, steps, elevation. It records your voice so you can speak thoughts without stopping. It offers a breathing circle when you want to pause and be still. When you return, it offers back what you gave it: a map of where you went, a transcript of what you said, writing prompts drawn from your own words.

That's the whole thing. No more, no less.

### What Pilgrim Is Not

- Not a fitness app. There are no calorie counters, no personal bests, no badges for streaks.
- Not a social platform. There is no feed, no following, no comparison.
- Not a data business. No analytics, no advertising, no behavioral profiling.
- Not a subscription. No paywall mid-walk, no features gated behind recurring payments.
- Not a cloud service. Your walks live on your phone. When you delete the app, they're gone with it — unless you exported them first.

---

## Features

**The walk itself**

GPS tracking with live pace sparkline, step counting, altitude gain, and waypoint marking. Three-way time breakdown shows how each walk split between walking, talking, and meditating — because those are genuinely different states of attention. A foreground service keeps tracking alive with the screen off for the length of a long walk, flushing data to disk on every significant sample so nothing is lost if the app is interrupted. Live weather via Open-Meteo is logged with each walk.

**Voice**

Tap to record a voice note at any moment on the walk. Each recording is timestamped and pinned to a location. After the walk, whisper.cpp transcribes everything on-device — no audio is ever sent to a server. Auto-transcription runs after each walk when enabled, and skips gracefully when battery is low. Edit transcriptions inline to fix what the model got wrong. The transcriptions become the raw material for writing prompts.

**Meditation**

A dedicated meditation mode with an animated breathing circle. Set the rhythm (inhale, hold, exhale, rest). Meditation time is tracked separately and shown alongside walk time in the summary.

**Voice guides and soundscapes**

Downloadable meditation guide packs with spoken prompts during walks and meditation. Ambient soundscapes — forest, rain, ocean, stream, birds, fire, crickets — play seamlessly in the background with crossfade looping. Customizable bells mark the start and end of walks and meditation sessions.

**The Walk Reliquary**

Photos you happened to take along a walk appear in a quiet carousel on your walk summary — gathered passively from your device's media library by time and GPS. Nothing is copied, nothing is uploaded, nothing is stored; the app holds only a reference back to MediaStore. Long-press a photo and tap the pin to commit it as a relic: it becomes a circular thumbnail anchored to exactly where you stood when you took it. Scroll the carousel and the corresponding map pin glows. Tap a map pin and the carousel scrolls to meet it. Opt-in, default off. Photos without GPS are excluded. Screenshots are filtered out.

**AI writing prompts**

Six prompt styles — contemplative, reflective, creative, gratitude, philosophical, journaling — generated from your transcriptions, walk context, and pinned photos. The app reads your photos on-device via ML Kit — detecting landscapes, text, people, colors — and weaves what it finds into the prompts. All analysis is local. Copy them into your favorite AI and turn a walk into writing.

**Goshuin seals**

In Japan, pilgrims collect *goshuin* — vermilion ink stamps given at temples along a route. Pilgrim generates a digital seal for each walk, derived from its unique data: distance, duration, weather, elevation. The collection grows with your practice.

**Celestial awareness**

Moon phase, zodiac sign, and planetary hour appear in the walk context. A contemplative koan drawn from the celestial, weather, or seasonal context appears before each walk — a seed for reflection.

**Sharing**

Share a walk as a goshuin seal image, a hand-painted etegami postcard, or an ephemeral HTML walk page (no login required). Shared pages render on Mapbox's outdoors style with terrain contours and trail markings. Optionally include waypoints and pinned photos — both off by default, per-share opt-in. The walk is yours to keep or share as you see fit.

**Walk with me**

Turn on *Interactive* when sharing and the page becomes a living walk: the camera glides your route across a real map under your walk's true sun and weather, your voice recordings play at the places you spoke them, photographs appear where you took them, and meditations become breathing pools with your own soundscape playing low underneath. Viewers can take the guided walk or replay it minute for minute — *as it happened* — with the walker's own clock. Whoever reaches the end may leave one anonymous stone on the walk's cairn, which grows through the same tiers as the cairns on the trail. Recordings and full-size photos upload only when you choose Interactive, transcripts never leave the device, and pages expire — taking everything with them.

→ **[Walk one yourself](https://walk.pilgrimapp.org/9mYhRL7GWx)** — a real walk, shared from the app.

**Walk with the collective**

Opt-in anonymous counter that tracks total walks, distance, and meditation time across all pilgrims. Your Settings screen shows the collective progress mapped to real pilgrimage routes — from the Kumano Kodo to the Camino de Santiago. Sacred number milestones ring a temple bell. A streak flame tracks consecutive days someone, somewhere, has walked. The logo gently pulses when another pilgrim walked in the last hour.

**Your data**

See all your walks rendered on [view.pilgrimapp.org](https://view.pilgrimapp.org) — right from the app, nothing uploaded. Export as `.pilgrim` packages (full data, importable). Import on a new device anytime. A home-screen widget (Jetpack Glance) surfaces your latest walk at a glance. Colors shift with the seasons, calibrated to your hemisphere.

---

## Privacy

Every feature that could require a network call has been built to work without one.

- Transcription: on-device via whisper.cpp (JNI)
- Writing prompts: generated on-device from walk context, copy into your own AI
- Image understanding: on-device via ML Kit
- Maps: Mapbox with no user-identifying requests
- Weather: Open-Meteo (no personal account, approximate location only)
- Walk data: stored in a local Room database on the device
- Collective counter: opt-in, sends only anonymous totals (walk count, distance, meditation time)

There is no backend that knows who you are. There is no account to create. The Play Store data safety form declares every data type the app touches and why.

---

## Building

### Requirements

- JDK 17 (Temurin recommended)
- Android SDK with platform 34/35/36 installed
- Android NDK (for the whisper.cpp JNI build)
- A physical device or arm64 emulator

### Setup

```bash
git clone https://github.com/walktalkmeditate/pilgrim-android.git
cd pilgrim-android
```

Copy the local-properties template and add your Mapbox tokens:

```bash
cp local.properties.example local.properties
# Edit local.properties:
#   MAPBOX_ACCESS_TOKEN=pk.xxx       (public token, baked into the build)
#   MAPBOX_DOWNLOADS_TOKEN=sk.xxx    (secret token, for the Mapbox Maven repo)
```

Build and run:

```bash
./gradlew assembleDebug
```

The app functions without a Mapbox token (maps will not render), but all other features work.

### Running Tests

```bash
./gradlew testDebugUnitTest        # unit tests (JUnit4 + Turbine + Robolectric)
./gradlew lintDebug                # Android lint
```

### Releasing

Releases run through GitHub Actions, mirroring the iOS two-track shape. Both workflows are manual (`workflow_dispatch`) and sign with the upload keystore via repository secrets.

```bash
# Publish to Play Internal Testing (TestFlight equivalent)
gh workflow run internal.yml --field version=1.0.1

# Promote to Play Production at 20% staged rollout
gh workflow run production.yml --field version=1.0.1
```

`production.yml` also commits the version bump back to `main`, tags `vX.Y.Z`, and creates a GitHub Release with the AAB + APK attached. Release notes are generated from conventional-commit history by `scripts/release-notes.sh`:

```bash
scripts/release-notes.sh            # buckets feat/fix/style since the last tag into
                                    #   build/changelog.md  (GitHub Release body)
                                    #   app/src/main/play/release-notes/en-US/default.txt
                                    #     (Play "What's new", <=500 chars)
```

`versionCode` is computed per-commit as `git rev-list --count HEAD`. A committed `whatsnew.txt` next to `default.txt` overrides the auto-generated Play note for a given release.

---

## Architecture

### Technology

- **Jetpack Compose + StateFlow** — UI and reactive state throughout (Material 3, heavily themed)
- **MVVM** — `ViewModel` + `StateFlow`, the direct analogue of the iOS `@Published` model
- **Hilt** — dependency injection
- **Room** — a single baseline schema (no migration chain); local persistence
- **DataStore (Preferences)** — settings
- **whisper.cpp via JNI** — on-device speech recognition
- **Mapbox Android SDK** — maps
- **ML Kit** — on-device image labeling, text, and face detection for prompt context
- **Media3 / ExoPlayer + AudioRecord** — soundscapes, voice guides, recording
- **Jetpack Glance** — home-screen widget
- **Foreground service** (`foregroundServiceType="location"`) — survives long, screen-off walks

### Structure

```
app/src/main/java/org/walktalkmeditate/pilgrim/
├── ui/
│   ├── home/         — journal scroll, walk list, ink/calligraphy path renderer
│   ├── walk/         — active walk, waypoints, intention, live stats
│   ├── meditation/   — breathing circle, rhythm picker
│   ├── goshuin/      — seal collection + generative renderer
│   ├── settings/     — preferences, data export, voice guides, soundscapes
│   ├── path/         — Path tab (start screen, celestial greeting)
│   ├── recordings/   — voice recording list + playback
│   ├── etegami/      — postcard share rendering
│   ├── onboarding/   — first-run permissions + welcome ritual
│   ├── navigation/   — Navigation Compose graph (single Activity)
│   ├── theme/        — seasonal color engine, typography
│   └── design/       — shared components, design system
├── walk/             — WalkController + recording orchestration
├── data/             — Room entities/DAOs, .pilgrim package import/export
├── domain/           — use cases, models
├── audio/            — capture + playback primitives
├── location/         — GPS source, foreground tracking
├── sensor/           — step counter
├── service/          — foreground walk service
├── permissions/      — runtime permission flows
├── widget/           — Glance widget
├── core/             — celestial calculator, koan corpus, utilities
└── di/               — Hilt modules
```

### Navigation

Single-Activity, Navigation Compose. First-run permission flow in `ui/onboarding`. MVVM with `ViewModel` + `StateFlow` / `SharedFlow` throughout (no LiveData, no RxJava).

### Design System

Typography uses Cormorant Garamond (display, headings, body) and Lato (timer, stats, captions) — variable-font TTFs with explicit weight axes. Never use Material defaults for type.

Colors: stone (accent), ink, parchment, moss, rust, fog, dawn. Seasonal vignettes shift the palette across spring, summer, autumn, winter, calibrated to hemisphere.

### Long-session reliability

The hardest part of this app is surviving a 45–90 minute walk with the screen off, battery saver on, and the device in a backpack. The tracking pipeline is built with explicit teardown: a `START_STICKY` foreground service with an ongoing notification, a battery-optimization exemption flow, structured concurrency scoped to the service lifecycle, writes flushed to Room on every significant sample, and exhaustive audio-session cleanup.

---

## Contributing

Pilgrim is open source under GPLv3. Contributions are welcome.

The app is built for long walks — sessions that last 30, 60, 90 minutes without interruption. The highest obligation when contributing is to not break that. A memory leak that manifests after 45 minutes, an audio player that doesn't clean up after itself, a coroutine that outlives its scope — these are not minor bugs. They are the app failing at the moment it matters most.

Before contributing:

- Read the resource safety guidelines in `CLAUDE.md`
- Study 2–3 existing screens before writing a new one — patterns exist for a reason
- Coroutines, audio players, `Flow` collectors, and location updates all require explicit cleanup paths
- Any PR that constructs platform builder objects (`WorkRequest`, `AudioFocusRequest`, `NotificationChannel`, `MediaItem`) must include a Robolectric test that calls `.build()` on the production class — runtime-validated builders fail on-device, not in faked unit tests
- Code should be self-documenting; comments that explain *what* the code does signal a refactor, not a note

Open an issue before starting significant work. Not for permission — for conversation.

---

## License

GNU General Public License v3. See [LICENSE](LICENSE).

    Pilgrim for Android
    Copyright (C) 2026 Walk Talk Meditate contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

---

[pilgrimapp.org](https://pilgrimapp.org)
