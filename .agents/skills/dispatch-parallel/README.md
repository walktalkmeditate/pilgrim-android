# dispatch-parallel

Primitive for fanning out independent subtasks to parallel subagents, then synthesizing the results (merge / dedupe / vote / pick-best).

Composed via `deps.skills` by every skill that benefits from parallelism: `blunder-hunt`, `pr-review`, `polish`, `journal`, and others.

Install:
```bash
jutsu install dispatch-parallel
```

Trigger phrases: "dispatch parallel", "fan out", "run in parallel", `/dispatch-parallel`.
