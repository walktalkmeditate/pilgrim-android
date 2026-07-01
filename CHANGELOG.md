# Changelog

All notable changes to Pilgrim are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions follow [SemVer](https://semver.org/).

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
