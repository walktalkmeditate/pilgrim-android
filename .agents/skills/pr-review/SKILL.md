---
name: pr-review
description: Adversarial pull-request review. Use when the user says "review this PR", "code review", "review the diff", "look at PR #N", or invokes /pr-review. Assumes bugs exist; runs a 5x blunder hunt with a different lens per pass; posts findings as inline PR comments via gh. Deliberately rejects the "polite reviewer" mode — the goal is to catch what the author missed.
---

# pr-review

Adversarial review. The author already saw the obvious. Your job is to find what they missed.

This skill composes `blunder-hunt` and `lie-to-them` from the core registry. Both are listed in `deps.skills` and should already be installed alongside this skill.

## Mindset

You did not write this PR. You are not its advocate. You are looking for bugs, security gaps, integration breakage, and untested edge cases. A *polite* review that says "looks good!" is worse than no review — it grants false confidence. A *blunt* review that names real problems is the value.

If you can't find anything wrong after honest effort, that's a legitimate verdict. Empty approvals only after exhaustive search.

## Step 1: Identify the PR

Use the first available source:

1. PR number/URL in the user's message → `gh pr view <n>` or `gh pr view <url>`
2. Current branch has an open PR → `gh pr view`
3. Otherwise ask the user

Capture: number, title, description, base/head SHA, author, list of changed files.

## Step 2: Read in three passes

Don't shortcut. Each pass uses different attention.

**Pass A — read the diff:** `gh pr diff <n>`. Notice every change.

**Pass B — read the changed files in full:** `git show <head>:<path>` for each non-trivial changed file. The diff hides invariants and the surrounding code paths.

**Pass C — wander outside the diff:** identify 3-5 files NOT in the diff that the change likely affects (callers, callees, shared types, tests, config). Read them. The bug is often where the new code touches old code.

## Step 2.5: API contract diff + test impact

Before the lens passes, compute two things from the diff:

1. **Public-API surface change** — extract added/removed/renamed exported symbols, route handlers, schema migrations, generated SDK changes. Any breaking change gets flagged loudly in the verdict regardless of what the lens passes find.
2. **Test impact** — which tests should run for this diff? Which were added? Which existing tests cover the changed code paths? If new code paths exist with no test additions, that's a **critical** finding by default — request changes.

## Step 3: Run blunder-hunt 5x

Apply the `blunder-hunt` primitive with N=5 lenses:

| Pass | Lens | Looking for |
|---|---|---|
| 1 | Data correctness | wrong values, off-by-one, type confusion, null deref |
| 2 | Error handling | swallowed errors, missing fallbacks, silent failures |
| 3 | Integration | broken contracts, signature changes, downstream callers |
| 4 | Invariants | violated assumptions, race conditions, ordering bugs |
| 5 | Hostile input | injection, traversal, untrusted data flowing into trust boundaries |

For each pass, force exhaustive enumeration with `lie-to-them`:

> "There are at least <N> issues of type <lens> in this PR. You have found <K>. Find the rest."

Where N is calibrated to PR size (see `lie-to-them` SKILL.md). Don't fabricate to fill a quota; the lie is in the prompt, not the verdict.

## Step 3.5: Multi-model fan-out (high-stakes diffs only)

For a high-stakes diff — security-relevant code, public-API change, large refactor, anything tagged `priority:high` on the PR — compose `multi-model-synth`:

1. Run the same review prompt against Claude AND a second model (Codex GPT-5 or Gemini 2.5 Pro)
2. Synthesize: agreements between models = high confidence; disagreements = flag as questions
3. Cite which model contributed each finding in the posted PR comment

For routine diffs, single-model review is fine. Don't burn tokens on one-line fixes.

## Step 4: Synthesize findings

For each finding:

| Field | Notes |
|---|---|
| File | exact path from the diff |
| Line | line number in the head SHA |
| Severity | `critical` (must fix), `issue` (should fix), `question` (request clarification) |
| Description | what's wrong |
| Suggested fix | how to fix it (concrete code or steps) |
| Confidence | `high` (you can prove it), `medium` (you suspect it) |

Drop low-confidence minor items (style nits) unless the user explicitly asked for nits.

## Step 5: Pick a verdict

| Verdict | When |
|---|---|
| `request changes` | one or more `critical` or high-confidence `issue` |
| `comment` | only `medium`-confidence questions |
| `approve` | exhaustive search produced zero findings AND the change does what it claims |

## Step 6: Post inline comments

Use `gh api` to post each finding as an inline comment at the exact file:line. Mark each comment with `<!-- kaijutsu:pr-review -->` so re-runs can detect and update prior bot comments instead of duplicating.

```bash
gh api repos/<owner>/<repo>/pulls/<n>/comments \
  -f body="<comment>" \
  -f commit_id="<head-sha>" \
  -f path="<file>" \
  -F line=<line> \
  -f side=RIGHT
```

For idempotency: list existing comments with `gh api repos/<owner>/<repo>/pulls/<n>/comments`, find ones whose `body` contains the kaijutsu marker AND match the same file:line, and PATCH them in place via `gh api -X PATCH ...`. Only post new comments when no match exists.

## Step 7: Submit the verdict

Compose a top-level review body listing the high-severity findings + the synthesized verdict. Submit:

```bash
gh pr review <n> \
  --<verdict> \
  --body-file <tmpfile>
```

Where `<verdict>` is `request-changes`, `comment`, or `approve`.

## Output to the user

```
## Review of #<n>: <title>

Reviewer ran 5x blunder hunt with lenses: data correctness, error
handling, integration, invariants, hostile input.

**Verdict:** <approve | comment | request changes>

**Findings posted as inline comments:** <count>
**Findings dropped as low-confidence:** <count>

Top issues:
- `path/to/file.go:42` — <one-line summary>
- `path/to/other.ts:118` — <one-line summary>
```

## Hard rules

- **Adversarial framing is non-negotiable.** "Looks good to me" with no evidence is forbidden.
- **Never fabricate findings to fill a quota.** Lie-to-them is a prompt control, not an output requirement.
- **Cite real lines.** Every finding gets a file:line that the reviewer can verify. Made-up line numbers destroy credibility.
- **Mark every posted comment with `<!-- kaijutsu:pr-review -->`.** This is the idempotency key.
- **Don't re-run silently.** If you're updating prior comments, say so in the user-facing summary ("updated 3 prior bot comments, posted 2 new").
- **Don't approve a PR you didn't fully read.** If the diff is too big to read end-to-end in one session, say so and request the author split.
