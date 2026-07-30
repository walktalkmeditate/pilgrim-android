# Changelog

All notable changes to Pilgrim are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).

## [1.3.0] — 2026-07-27

iOS v1.9.0 parity (anchor `9a418e4`): route rotation, whisper base, map glyphs.

### Collective route rotation
- The collective line in Settings now walks a different pilgrimage every day —
  the same route the web and iOS pilgrims see, chosen by shared daily math and
  a curated open-pilgrimages catalog (bundled for offline, refreshed from the
  CDN when a path is available).
- A contributed walk's summary places your distance on the day's route, among
  the people who really walk it. The route dataset is credited in About
  (ODbL, open-pilgrimages).

### Voice notes hear more clearly
- Transcription steps up from the tiny model to multilingual base — the same
  tier iOS runs. The model now downloads once (~148 MB, Wi-Fi by default with
  a mobile-data option) instead of shipping inside the app, so the install
  shrinks by ~78 MB.
- Upgraders keep transcribing throughout: the old model serves until the new
  one is verified, then hands over atomically. Recording rows explain
  download states, and Settings shows progress.
- Generated prompts carry the walk's character — pauses, climbs, light
  crossings, and the wander/seek vocabulary — closing with a steadier
  response contract.

### The presences deserve presence
- Map whispers and cairns render hand-drawn vector art: one wisp in your
  mood's color, seven cairn tiers that age visibly from faint to eternal.
- The add-a-stone sheet shows what your stone makes; the cairn detail carries
  the art at full scale; whisper mood rows glow in their guide colors.
- Seek clearings grow a tree: arrivals on the summary map stand in the hour's
  light atop a gentler halo, and a live arrival leaves a quiet stone tree
  where the fog parted.

## [1.2.0] — 2026-07-16

Seek Mode and journal scenery — parity with iOS v1.8.0/1.8.1 (anchor `c1745e8`).

### Seek Mode
- A new walk mode: name an intention, choose how long you have, and follow a
  quiet sonar through fog toward unknown clearings. The fog dissolves as you
  arrive; a crescent on the map keeps the bearing honest.
- Sonar pings tighten as a clearing nears, with haptic breaths at the gateway;
  sonar volume and toggle live in the in-walk options sheet AND Settings →
  Sounds (shared preference, mirrored live).
- Arrival leaves a sun-haze waypoint; the walk summary gains a seek section
  with provenance and clearing halos; goshuin seals mark seeking milestones.
- The foreground notification carries a seek glance line (distance ladder +
  direction), refreshed through screen-off pocket walks.
- Survives process death mid-seek: staged sessions restore across the
  two-process boundary; pre-departure GPS lock matches iOS's gateway boot.

### Journal scenery
- Walks can now stand at torii gates (practice + seeking thresholds decided
  from real history), raise cairns for found places, and draw the season's
  breath (drift: petals, fireflies, dragonflies, snow) — deterministic per
  walk, backward-compatible with every previously rolled scenery item.
- Moon scenery renders the real lunar phase of that night with a breathing
  halo and swaying light shafts; lanterns light only after dusk.
- Scenery gains depth-of-field parallax on scroll, ages with its walk
  (seeking gates refuse the fade), and haptic dots mark gates and cairns.

### Fixes
- Journal walk dots no longer read flat black: the drop shadow is pinned to
  true black (the adaptive ink inverted to a light halo in dark mode — the
  same bug iOS fixed in 1.7.0) and the outer aura no longer age-fades.
- Archived-walk rings match iOS (0.6× size, constant opacity, 44 dp target);
  smallest dots get the full 44 dp tap target.
- Scenery restored to iOS-verbatim 32–56 dp (the earlier 20–36 dp shrink
  read as glyphs side-by-side against an iPhone).
- Importing an archive with duplicate walk entries now resolves the winner
  deterministically on every platform.
- In-app feedback is tagged with its platform so reports route to the right
  repo.

## [1.1.0] — 2026-07-01

The iOS v1.7.0 (PR #45) parity sweep plus the PR #47 recording follow-up.

### Accessibility
- Full TalkBack pass: actions, roles, labels, and 48 dp tap targets across
  gesture-only controls — meditation options, walk dots, mode selector,
  journey-header stat cycling, seal reveal, waveform slider (AF47/49/50/53/57/58/62/63/66).
- Reduce-motion honored on the Meditation and Welcome screens (AF48/AF59).

### Audio & recording
- A voice talk now survives transient interruptions — a notification, assistant,
  nav prompt, or unanswered ring no longer cuts a recording short; only a real
  takeover (an answered call / another capture app) ends it (iOS PR #47).
- Interrupted recordings are finalized and surfaced instead of silently
  truncating while the UI still shows them live (AF11).
- Ending a meditation no longer force-resumes a manually-paused voice guide (AF24).

### Onboarding
- "Wander" logo zoom on Begin, and a permission-grant ritual — a soft bell and
  a checkmark spring-pulse on each grant, once per permission, reduce-motion-aware (#43).

### Performance
- Incremental live-route mapping — long walks no longer re-map/re-upload the whole
  growing route every GPS fix (AF9/AF46).
- The 20 Hz mic-metering tick no longer recomposes the entire active-walk screen
  and map (AF10).
- The whisper transcription model is unloaded after each batch (~75 MB reclaimed) (AF33).

### Visual & parity
- Secondary text (fog) now meets WCAG contrast in light and dark (AF68).
- The home-widget mantra uses the brand serif (AF74).
- Hemisphere-correct solstice/equinox turnings and seasonal route coloring
  (#167/#169/#170); soft journal dot shadow (#176).
- Soundscape mid-session swap + gapless loop; journey editor/viewer inset fixes.

### Correctness & data integrity
- Transcription and import report real success/failure instead of always-success
  (AF32/AF28).
- Per-walk import transactions so one undecodable walk can't lose its siblings.

## [1.0.0] — 2026-05-31

Initial release.
