# Manual reactive WASM mutation campaign

Run this tool explicitly at an epic boundary or during planned idle time when
changes affect reactive comparison/string bindings or wire flag ordering. It is
not a push gate or an automatic background job. The selected fault catalog has
15 entries; upstream LVGL, unrelated tests and unrelated mutation operators are
outside this campaign's scope.

```sh
python3 renderer/tools/lvgl-reactive-mutations.py \
  --out /tmp/lvgl-reactive-epic --jobs 1
```

The evidence directory must not exist. An optional `--seed-target` names an
existing Cargo dependency cache. The tool copies it into a private target and
cleans the renderer package before testing, so the compiled harness embeds its
own isolated worker path. It does not run against the seed cache or change the
invoking checkout. Source snapshots include tracked and unignored source files;
ignored build caches and the harness target are excluded. Run in the official
container with a populated Cargo registry; Cargo uses `--locked --offline`.

Each selected fault must match one exact source anchor before execution. The
worker changes only that source site, rebuilds actual WASM under the ordinary
strict Make flags, and runs all three named `reactive_api_contracts` Rust cases.
A kill requires successful compilation, Cargo exit 101, exactly the intended
Rust assertion failure, and both other named cases passing. Compile errors,
load/initialization panics, missing cases, ignored tests, malformed output,
crashes and timeouts receive no kill credit. Any survivor or invalid outcome
fails the campaign.

The runner imports the native API probe's bounded process-group supervisor.
Its self-canaries reject empty/error/crash/timeout processes, altered observed
case/summary populations, and an actual strict WASM compilation failure. It
restores source in `finally`, rebuilds the baseline and checks every frozen input
again. Positive baselines must pass all three cases and reproduce the original
WASM digest. Reports preserve the selected replacements, source/tool hashes,
compile/test commands and logs, each mutant's WASM digest, elapsed times, runner
canaries and final restoration. A missing final positive receipt is unfinished
work, regardless of any earlier kills.
