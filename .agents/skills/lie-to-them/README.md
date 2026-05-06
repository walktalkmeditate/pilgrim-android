# lie-to-them

Prompt-engineering primitive. Asserts a minimum issue count to defeat the model's tendency to satisfice on the first 2-3 findings and stop. Used by adversarial-review skills.

Install:
```bash
jutsu install lie-to-them
```

Composed via `deps.skills` by `blunder-hunt`, `pr-review`, and any skill that needs exhaustive enumeration. Trigger phrases: "lie to them", "force exhaustive search".
