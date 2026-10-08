# clj-kondo map-return correction

The latest stable upstream release, [2026.08.04](https://github.com/clj-kondo/clj-kondo/releases/tag/v2026.08.04),
misinterprets some Malli return types. Nullable maps become always truthy, open
maps lose their unknown keys, and optional-key lookup can lose its declared
value type. The synthetic regression uses Malli 0.20.3 to emit the signatures;
it keeps genuine missing-key, wrong-value and constant-condition diagnostics.

`map-return-types.patch` repairs those conversions in two upstream source files.
The source-only JAR retains every other upstream entry, including its POM and
EPL-1.0 license. It changes no runtime Malli contract or diagnostic level. The
patch is distributed under the same license, included in `LICENSE`.

`artifact.json` binds the upstream URL and bytes, patch bytes, and rebuilt JAR.
The repository wrapper checks the patch and JAR before every invocation, then
runs the linter from the caller's directory so relative `--lint` paths resolve
where the caller meant them (`test-wrapper-cwd.sh`, run by `kondo-regression`). The
lint aggregate additionally reconstructs the JAR and runs its regression suite.
No shared Maven cache is modified. The linter runs as JVM source, never as a
native executable that could not carry the patch.

Rebuild and verify inside the pinned toolchain:

```sh
python3 tools/lint/kondo/rebuild.py --write
python3 tools/lint/kondo/rebuild.py
make -f lint.mk kondo-regression
```

`--upstream PATH` supplies the pristine JAR for offline reconstruction. Otherwise
the script reads the ordinary Maven cache or downloads the checksum-pinned
release. ZIP timestamps, permissions, ordering and compression settings are
fixed; verify byte identity again when changing Python or its compression library.

To reproduce the upstream failure, run `clojure -M:stock:test` from this directory.
The upstream suite must fail assertions, while the patched run must pass with
its negative controls intact. Remove the patch when a new unmodified stable
release passes the same suite. A release update is never grounds to suppress
findings or weaken those cases.
