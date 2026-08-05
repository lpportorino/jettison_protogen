#!/usr/bin/env bash
# compile-bindings.sh — T2.2: compile protogen's OWN generated Java UiAst
# bindings into target/proto-classes, so the devcards fixture builder runs
# against the protogen checkout instead of borrowing a consumer's compiled
# classes. deps.edn wires the output as the `:bindings` alias (the real
# path); no borrow alias exists — the checkout's own bindings are the ONE
# source.
#
# Sources: protogen/output/java/ui/*.java, with -sourcepath pointing at
# output/java so javac pulls exactly the transitive imports it needs
# (UiAst.java references cmd.Root) — never a hand-maintained file list.
#
# Classpath: resolved from the tool's deps.edn via `clojure -Spath` so the
# protobuf-java + protovalidate pins have ONE home (protovalidate is a
# class-init dependency: the generated descriptor references
# build.buf.validate.ValidateProto).
#
# Freshness model: clean rebuild (rm -rf). This output dir has NO live-JVM
# consumer — the live-JVM delete-then-javac ClassNotFoundException hazard
# (its proto-classes sit on a running app's classpath) does not apply to a
# batch tool's private target/ — and a clean rebuild is what guarantees no
# orphan .class survives a proto rename.
#
# Run from the ASSEMBLED home (tools/devcards beside the protogen root's
# output/java), inside the toolchain container:
#   docker exec -w <assembled-root>/tools/devcards <container> bash tools/compile-bindings.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PROTOGEN_JAVA="../../output/java"
OUT="target/proto-classes"

command -v javac >/dev/null 2>&1 || {
  echo "FATAL: javac not found — run inside the toolchain container" >&2
  exit 1
}
command -v clojure >/dev/null 2>&1 || {
  echo "FATAL: clojure not found — run inside the toolchain container" >&2
  exit 1
}
[[ -d "$PROTOGEN_JAVA/ui" ]] || {
  echo "FATAL: $PROTOGEN_JAVA/ui missing — protogen submodule not checked out?" >&2
  exit 1
}

CP="$(clojure -Spath)"

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 \
  -cp "$CP" \
  -sourcepath "$PROTOGEN_JAVA" \
  -d "$OUT" \
  "$PROTOGEN_JAVA"/ui/*.java

[[ -f "$OUT/ui/UiAst.class" ]] || {
  echo "FATAL: ui/UiAst.class missing from $OUT — compile produced the wrong tree" >&2
  exit 1
}
N=$(find "$OUT" -name '*.class' | wc -l)
[[ "$N" -gt 0 ]] || {
  echo "FATAL: zero classes compiled" >&2
  exit 1
}
echo "compiled $N classes -> $OUT (sources: $PROTOGEN_JAVA/ui + sourcepath imports)"
