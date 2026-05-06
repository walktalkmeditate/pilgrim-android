---
name: dispatch-parallel
description: Fan out N independent tasks to parallel subagents, collect their results, optionally synthesize. Use when another skill needs work that decomposes into independent pieces — review passes with different lenses, multi-file searches, multi-source data gathering. Composed by skills like blunder-hunt, pr-review, polish, journal.
---

# dispatch-parallel

Sequential subtask execution wastes wall-clock time when subtasks are independent. This primitive captures the pattern of "decompose, dispatch, collect, synthesize" so other skills don't have to restate it.

## When to invoke

- A parent skill needs to run K independent passes / lenses / searches
- The subtasks are read-only or write to disjoint state
- Per-subtask cost dominates over coordination cost
- The outputs can be merged or deduplicated post-hoc

Do **not** invoke when:
- Subtasks share mutable state
- Order matters (use sequential composition instead)
- The work is small enough that one agent finishes faster than spawning N

## The pattern

### 1. Decompose

Split the parent task into K self-contained subtasks. Each subtask must:
- Have a clear input (what to look at)
- Have a clear lens (what to look for)
- Produce a structured output (list of findings, list of facts, etc.)
- Be runnable without seeing the other subtasks' outputs

If you can't write a one-paragraph brief for a subtask, it's not decomposed enough.

### 2. Dispatch (per agent platform)

| Agent | Subagent primitive |
| --- | --- |
| Claude Code | `Task` tool with `subagent_type: general-purpose` (or specialized) — multiple Task calls in a single response run in parallel |
| OpenAI Codex CLI | `codex agents spawn` (where supported) or sequential with explicit context resets |
| Google Gemini CLI | `activate_skill` + delegated subtasks where supported; otherwise sequential |

When the platform supports parallel calls in a single turn, use them. Otherwise, fall back to sequential — still apply the decompose/collect pattern, just lose the wall-clock win.

### 3. Collect

Each subagent returns a structured output. Aggregate into a single list. Tag every item with which subagent produced it (`subagent_id`, `lens`, etc.) so dedup and synthesis can preserve provenance.

### 4. Synthesize

Pick the synthesis pattern based on what the parent skill needs:

- **Merge** — take the union of all outputs. Use when each subagent searches disjoint territory.
- **Dedupe** — group equivalent items, keep the highest-confidence variant. Use for findings where the same issue might be spotted by multiple lenses.
- **Vote** — items present in N+ subagent outputs are high-confidence. Use for adversarial review where agreement = signal.
- **Pick best** — when each subagent produced a competing answer (e.g., draft prose), score them and pick. Often paired with `multi-model-synth`.

## Output shape

```
## dispatch-parallel: <task-name>

| Subagent | Lens / Subtask | Items | Wall time |
|---|---|---|---|
| 1 | data correctness | 4 | 8s |
| 2 | error handling | 3 | 11s |
| 3 | integration | 5 | 9s |

Synthesized (<merge | dedupe | vote | pick-best>):
- ...
```

## JSON sidecar

When invoked under a `--json` flag (or when the parent skill requests structured output), emit alongside the markdown:

```json
{
  "skill": "dispatch-parallel",
  "version": "0.1.0",
  "task": "<task-name>",
  "synthesis": "dedupe",
  "subagents": [
    {"id": 1, "lens": "data correctness", "items": [...], "wall_ms": 8000},
    ...
  ],
  "synthesized": [...]
}
```

## Hard rules

- **Never share mutable state** between subagents. Each receives its own context, its own files-to-read, its own scratch space. Mutating shared files concurrently produces undefined results and is forbidden.
- **Read-only of in-flux data is also racy.** If subagent A reads `file.go` while subagent B is writing it (e.g., parallel review of a workspace where some other process is editing), A may see an inconsistent snapshot. Either freeze the inputs (snapshot the directory) before dispatching, or restrict subagents to immutable inputs (specific git refs, frozen text passed via prompt).
- **Always tag the source.** Every item in the synthesized output carries a `subagent_id` (and `lens` if the subagents had different lenses). Provenance enables debugging when synthesis goes wrong.
- **Time-bound subagents.** A subagent that hangs blocks the whole synthesis. Set an explicit timeout (suggested default 5 min). On timeout, flag and continue with the others.
- **Dispatch is non-recursive by default.** A subagent should not spawn more parallel subagents unless the parent skill explicitly authorizes depth > 1.
- **Idempotent subtasks only.** If a subtask retries, its repeated execution must not change disk state or external systems.
