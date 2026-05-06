---
name: multi-model-synth
description: Fan out the same task to multiple models, compare their responses, synthesize a "best of all worlds" answer. Use when one model's output is a good first draft but you want to surface blind spots and combine strengths — planning, design proposals, code reviews, retrospectives. Composes dispatch-parallel.
---

# multi-model-synth

Different frontier models reason differently. Given the same prompt, Claude, Codex, and Gemini produce surprisingly different outputs — different blind spots, different framings, different strengths. Synthesizing the best of each yields work no single model would have produced alone.

This skill captures the pattern. It composes `dispatch-parallel` for the fan-out and adds the synthesis discipline.

## When to invoke

- A high-stakes opinion artifact: design plan, architecture decision, code review verdict, project retrospective
- A creative draft where blind spots matter: README, blog post, plan
- A judgment call where multi-perspective reduces the chance of a confident-but-wrong answer

Do **not** invoke when:
- The task is mechanical (tests, formatting, type fixes) — one model is fine
- Time is critical (synthesis adds a sequential round)
- Cost is a concern (3-model fan-out triples the spend)

## The pattern

### 1. Prepare the prompt

Write the same prompt for every model. Resist the temptation to tailor per model — homogeneity of input is what makes the comparison meaningful.

Include:
- The goal
- The relevant context
- The output format (so synthesis can compare apples-to-apples)
- An explicit "be specific, cite sources where applicable" instruction

### 2. Dispatch via dispatch-parallel

Hand off to `dispatch-parallel` with K subagents, each running the same prompt against a different model. Pass the model identity as part of each subagent's context so it produces output appropriate to its capabilities.

**Reality check on platform support** (as of v0.2):

- **Claude Code** has the `Task` tool which spawns subagents within Claude — single-model. Real multi-model fan-out requires API keys for the other models and shelling out to their SDKs.
- **OpenAI Codex CLI** runs Codex models exclusively.
- **Google Gemini CLI** runs Gemini models exclusively.

Honest fallback for v0.2: run the same prompt **sequentially** in different agent sessions (Claude Code, then Codex, then Gemini) and paste each output into a final synthesis prompt. Lossier than true parallel fan-out, but achievable today without custom orchestration.

Native cross-model dispatch is on the v0.3 roadmap once the kaijutsu CLI grows API-key plumbing.

### 3. Compare

Read all K outputs side-by-side. Identify:
- **Agreements** — points all (or most) models make. High-confidence material.
- **Disagreements** — points where models diverge. These are the interesting ones; they reveal blind spots or genuine tradeoffs.
- **Unique strengths** — points only one model raised. Some of these are gold (a model spotted what others missed); some are noise (one model went off track).

### 4. Synthesize "best of all worlds"

This is the explicit synthesis prompt to use for the final pass:

> "I'm showing you outputs from <K> models on the same prompt. Identify the strongest ideas in each. Then produce a single revised answer that artfully and skillfully blends the best of all worlds — preserving every superior idea, correcting blind spots flagged by other models, and dropping points that are weak or contradicted by stronger reasoning elsewhere. Cite which model contributed each major point."

The synthesizer can be the same model as one of the K, or a different "arbiter" model. Jeffrey Emanuel's flywheel uses GPT Pro as the arbiter; pick whichever model you trust most for synthesis on the given task.

### 5. Cite provenance

In the final output, attribute major decisions to the model that contributed them. Future readers can track which model is consistently strong on which kinds of tasks.

## Output shape

```
## multi-model-synth: <task>

### Inputs
- Model A (Claude Sonnet 4.6): <one-line summary of its output>
- Model B (Codex GPT-5): <one-line summary>
- Model C (Gemini 2.5 Pro): <one-line summary>

### Agreements (high-confidence)
- ...

### Disagreements
- <topic>: A says X, B says Y. Resolution: <which won and why>.

### Synthesized output
<the actual deliverable, with provenance markers like (per Claude) where attribution matters>
```

## JSON sidecar

```json
{
  "skill": "multi-model-synth",
  "version": "0.1.0",
  "task": "<name>",
  "models": ["claude-sonnet-4-6", "codex-gpt-5", "gemini-2.5-pro"],
  "agreements": [...],
  "disagreements": [{"topic": "...", "positions": {"A": "...", "B": "..."}, "resolution": "..."}],
  "unique_contributions": [{"model": "claude", "points": [...]}, ...],
  "synthesized": "<final text>"
}
```

## Hard rules

- **Same prompt for every model.** No per-model tailoring.
- **No anchoring.** Don't show one model's output to another before they've all responded; that defeats the diversity point.
- **Attribute major points** in the final output. Provenance is the record.
- **Don't synthesize disagreements by averaging.** Pick the stronger argument; explain why.
- **Don't run multi-model-synth on mechanical tasks.** It's expensive — reserve for opinions, not facts.

## Provenance

Pattern from Jeffrey Emanuel's "Agentic Coding Flywheel" — multi-model competition + GPT-Pro synthesis + iterative refinement. kaijutsu adopts the synthesis half as a primitive so other skills (decide, journal, pr-review) can compose it.
