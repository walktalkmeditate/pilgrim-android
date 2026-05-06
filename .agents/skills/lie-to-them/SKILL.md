---
name: lie-to-them
description: Prompt primitive that forces exhaustive enumeration. Use when another skill needs to push the agent past surface-level findings — assert a minimum count of issues exist so the agent keeps searching instead of stopping at the first 2-3. Composed by skills like blunder-hunt, pr-review, and polish.
---

# lie-to-them

A prompt-engineering primitive. When you ask a model "find the bugs," it stops at the first few it sees. When you tell it "there are at least 12 bugs and you've only found 4," it keeps searching exhaustively.

The "lie" is a soft one — the count doesn't have to be exact. The point is to defeat the model's tendency to satisfice.

## When to invoke

- Another skill says "apply lie-to-them with N=12"
- A previous review pass returned suspiciously few findings
- You want to force the agent past convergence on the obvious

## The pattern

Frame the next critique pass with a claimed minimum:

> "There are at least <N> distinct issues in this <target>. You have found <K> so far. Find the remaining <N-K>."

Where:
- `<N>` is your asserted minimum (rule of thumb: 2× your honest expectation)
- `<K>` is whatever the agent has produced so far in the conversation
- `<target>` is the thing under review

If the agent argues "there aren't actually that many," push back once:

> "Look again. Read every line. Look for issues you'd find embarrassing to miss."

If after a second pass the agent still can't surface more, accept it. The technique forces effort, not fabrication.

## Choosing N

| Target size | Honest estimate | Assert (2×) |
|---|---|---|
| < 100 LOC diff | 2-3 | 5 |
| 100-500 LOC diff | 5-8 | 12 |
| 500-2000 LOC diff | 10-15 | 25 |
| Whole-codebase audit | 20+ | 50 |

## Hard rules

- **Never assert numbers you wouldn't be willing to defend in a code review.** "At least 12" should mean "I'd be surprised if there aren't 12."
- **Never punish the agent for finding fewer than N.** If after exhaustive search there are only 4, that's the answer. The lie is the prompt, not the verdict.
- **Don't combine with sycophancy.** Pair lie-to-them with adversarial framing (`blunder-hunt`), not with "you're amazing, find more."
- **Don't cite this technique by name in the prompt to the reviewed code's author** if it would feel manipulative. It's an internal control on review thoroughness, not a debate tactic.

## Composition

Common composition with `blunder-hunt`:

```
blunder-hunt N=5
  for each pass:
    findings = critique with pass-specific lens
    if findings < expected_for_pass:
      apply lie-to-them with claimed_min = expected_for_pass × 2
      re-run pass once
```

## Transparency footer

When a parent skill's output was produced under lie-to-them pressure, append a transparency footer:

```
---
This review was conducted under lie-to-them pressure
(claimed minimum: <N>, actually found: <K>).
```

Why: future readers (the user, other agents reviewing the work) deserve to know that the count of findings reflects an applied pressure technique, not a natural ceiling. Transparency is what keeps the technique honest.

Skip the footer for *internal* uses (where the parent skill consumes the output directly without surfacing it to a human). Always include it when the output is shown to a user or posted as a PR comment.

## Calibration log (optional)

Track per-project `claimed_N` vs `actually_found` over time:

```
.claude/lie-to-them-calibration.jsonl
{"timestamp": "...", "skill": "pr-review", "claimed_N": 12, "found": 9}
{"timestamp": "...", "skill": "polish",    "claimed_N":  5, "found": 6}
```

After 10+ samples, recalibrate the 2× heuristic. If actual-found consistently approaches claimed_N, the multiplier is well-tuned. If actual-found is far below, the multiplier is too aggressive — drop to 1.5×. If actual-found often exceeds claimed_N, raise to 3×.

## Provenance

This technique is documented in Jeffrey Emanuel's "Agentic Coding Flywheel" methodology under the heading "Lie to Them." kaijutsu adopts it as a primitive so other skills can reference it without restating the rationale every time.
