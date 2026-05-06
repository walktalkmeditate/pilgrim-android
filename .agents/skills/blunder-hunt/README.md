# blunder-hunt

Adversarial multi-pass critique primitive. Runs the same skeptic review N times with deliberately different lenses (data correctness → error handling → integration → invariants → hostile input), then synthesizes the deduplicated findings.

Designed to be composed by other skills (`pr-review`, `polish`, `unstuck`) via `deps.skills`. Also directly invocable.

Install:
```bash
jutsu install blunder-hunt
```

Trigger phrases: "blunder hunt", "5x critique", "find what's wrong", `/blunder-hunt`.
