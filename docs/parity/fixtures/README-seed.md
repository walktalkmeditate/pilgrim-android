# Parity Seed Fixture (U2)

One curated `parity-seed.pilgrim` that, imported by **both** iOS and Android via the existing `PilgrimPackageImporter`, yields the same data so every screenshot diff reflects implementation drift, not data drift (plan R2, origin AE2).

> **Status:** spec only. The binary `parity-seed.pilgrim` is built from a running app's exporter against a seeded DB (see "Producing the binary"), which requires an installed build + device/sim — deferred to the capture-execution session, not hand-authored here.

## States the seed MUST encode

Drawn from the ledger's `seed req` column (`docs/parity/2026-05-15-parity-ledger.md`):

| Need | Why (ledger rows) |
|---|---|
| ≥108 finished walks | `goshuin.milestone`, sacred-number milestone celebration; `journal.inkscroll.*` density |
| ≥1 archived walk (`manifest.archived[]`) | `journal.dot.archived`, `journal.expandcard.archived`, `goshuin.archived-ghost`, `goshuin.statsheader` (archived counts in total, excluded from grid) |
| Tended marker (`manifest.modifications[]` non-empty) | overwrite-by-UUID import path; `settings.data-detail` import summary "added/replaced/archived" |
| ≥1 shared walk (collective share cache non-expired) | `journal.dot.shared` stone ring |
| Walks with photos pinned | `summary.reliquary.*`, `summary.map` photo pins |
| Walks with voice recordings incl. one long transcript (>280 chars / >7 lines) | `summary.voicerow.read` 7-line clamp + show-more + pencil; `settings.recordings` |
| A long-duration walk (route w/ many samples + altitude) | `summary.elevation`, `summary.timeline`, `summary.map` route |
| Walks across varied weather + celestial dates | `summary.lightreading`, `walk.active.greeting`, vignette rows |
| A walk on a turning date (solstice/equinox) | `journal.turningbanner`, `walk.turning.card` |
| Milestone-triggering walk (5th/10th/100th/108th) | `journal.inkscroll.milestone`, `sealreveal.milestone` |

## States the seed CANNOT encode → manual capture recipe

`.pilgrim` carries finished walks + settings + archive/mod markers. It cannot carry transient/runtime states. Capture these per-row with the documented recipe instead of faking them into the seed:

| Ledger rows | Manual recipe |
|---|---|
| `walk.active.*`, `walk.options.inwalk`, `walk.waypoint/whisper/stone`, `walk.meditation.*`, `walk.active.sparkline` | Start a real walk on each platform from an identical start location (mock-location both to the same lat/lon); reach the state live. Sparkline needs >10 pace points → walk ≥2 min. |
| `setup.*` (welcome/permissions/breath) | Fresh data state: Android `run-as <pkg> rm files/datastore/pilgrim_prefs.preferences_pb` (note: `pm clear` is blocked on the OnePlus OEM build — use run-as); iOS erase-and-reinstall or reset onboarding key. |
| `overlay.constellation*`, any `appearance: C` capture | Settings → Appearance → Constellation on both before capturing the row. `overlay.constellation.reduce` additionally enable system "remove animations". |
| `*.reduce-motion` rows | Enable OS reduce-motion (Android: Settings→Accessibility→Remove animations; iOS: Accessibility→Motion→Reduce Motion) before capture. |
| `path.wander.recovery-banner` | Start a walk, swipe app from recents (FGS killed), relaunch → recovery banner. |
| `overlay.proximity` | Place a whisper, walk back into its radius (mock-location). |

## Round-trip equivalence check (U2 done-gate)

Before any capture begins, prove the seed imports to *equivalent* state on both:

1. Android: `PilgrimPackageImporter.import(seed)` → record `ImportSummary{added, replaced, archived}`.
2. iOS: import same file → record its import result.
3. Counts MUST match (added/replaced/archived).
4. Spot-check ≥3 walks (one normal, one archived, one with photos+recording): surface stats (distance, duration, steps), archived→hollow-ring on both, photo count equal.
5. Mismatch ⇒ seed or an importer is non-equivalent — fix before capture, else every diff is noise (plan top risk).

## Producing the binary `parity-seed.pilgrim`

Deferred to the capture-execution session:

1. Build a debug app, drive a debug/dev path (or scripted inserts) to create the walk set above on ONE platform.
2. Export via Settings → Data → Export (`PilgrimPackageBuilder.build(includePhotos = true)`) → `parity-seed.pilgrim`.
3. Commit it to `docs/parity/fixtures/parity-seed.pilgrim`.
4. Run the round-trip equivalence check on the *other* platform; iterate the source data until counts + spot-checks match.

Until the binary exists, `docs/parity/fixtures/` holds this spec only; capture-execution units (U5/U6) are blocked on it + on an available iOS capture environment.
