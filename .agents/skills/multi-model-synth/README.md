# multi-model-synth

Primitive that fans out the same task to multiple models (Claude / Codex / Gemini), compares responses, then synthesizes a "best of all worlds" answer. Used by skills that produce opinion artifacts: `decide`, `journal`, `pr-review`, plan-stage skills.

Composes `dispatch-parallel` for the fan-out half.

Install:
```bash
jutsu install multi-model-synth
```

Trigger phrases: "multi-model", "best of all worlds", "synthesize", `/multi-model-synth`.
