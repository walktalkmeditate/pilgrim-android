# pr-review

Adversarial pull-request review. Assumes bugs exist; runs a 5x blunder hunt with a different lens per pass (data / errors / integration / invariants / hostile input); posts findings as inline PR comments via `gh` with idempotency markers so re-runs update instead of duplicate.

Composes `blunder-hunt` and `lie-to-them` via `deps.skills`.

Install:
```bash
jutsu install pr-review
```

`deps` are pulled in automatically.

Trigger phrases: "review this PR", "code review", "review the diff", "look at PR #N", `/pr-review`.
