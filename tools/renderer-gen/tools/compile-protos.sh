#!/usr/bin/env bash
# compile-protos.sh — compile the protogen checkout's FULL generated Java
# surface (output/java/**: ser + cmd + ui + jon) PLUS pronto's own Java
# helpers into tools/renderer-gen/target/proto-classes — the classpath the
# relocated fixture/codegen namespaces (asgard.proto.{cmd,state}, pronto
# mappers, lvgl-codegen.proto-ser) load at require time.
#
# Mirrors the private source repo's compile-proto-java build step in the
# batch-tool posture (javac -source/-target 17, -proc:none; pronto java dir
# discovered from the resolved classpath).
# Freshness model: clean rebuild (rm -rf) — this output dir has NO live-JVM
# consumer (the live-JVM delete-then-javac ClassNotFoundException hazard does
# not apply to a batch tool's private target/), and a clean rebuild is what
# guarantees no orphan .class survives a proto rename.
#
# Run from the assembled home, inside the toolchain container:
#   cd tools/renderer-gen && bash tools/compile-protos.sh
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
[[ -d "$PROTOGEN_JAVA/ui" && -d "$PROTOGEN_JAVA/cmd" && -d "$PROTOGEN_JAVA/ser" ]] || {
  echo "FATAL: $PROTOGEN_JAVA/{ui,cmd,ser} missing — not an assembled protogen checkout?" >&2
  exit 1
}

CP="$(clojure -Spath)"

# pronto ships Java sources in its git repo (java/ beside src/); tools.deps
# puts only src/ on the classpath, so the helpers must be compiled here —
# same discovery walk the private source repo's build tooling uses.
PRONTO_SRC="$(tr ':' '\n' <<<"$CP" | grep -m1 '/pronto/')" || {
  echo "FATAL: pronto not on the resolved classpath — deps.edn drift?" >&2
  exit 1
}
PRONTO_JAVA=""
d="$(dirname "$PRONTO_SRC")"
while [[ -n "$d" && "$d" != "/" ]]; do
  if [[ -d "$d/java" ]]; then
    PRONTO_JAVA="$d/java"
    break
  fi
  d="$(dirname "$d")"
done
[[ -n "$PRONTO_JAVA" ]] || {
  echo "FATAL: pronto java/ dir not found above $PRONTO_SRC" >&2
  exit 1
}

mapfile -t JAVA_FILES < <(find "$PROTOGEN_JAVA" "$PRONTO_JAVA" -name '*.java' | sort)
[[ "${#JAVA_FILES[@]}" -gt 0 ]] || {
  echo "FATAL: zero .java sources found — an empty input set is a green tick over nothing" >&2
  exit 1
}

rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -source 17 -target 17 -proc:none \
  -cp "$CP" \
  -d "$OUT" \
  "${JAVA_FILES[@]}"

for probe in ui/UiAst.class cmd/JonSharedCmd\$Root.class ser/JonSharedData\$JonGUIState.class pronto/ProtoMap.class; do
  [[ -f "$OUT/$probe" ]] || {
    echo "FATAL: $OUT/$probe missing — compile produced the wrong tree" >&2
    exit 1
  }
done
N=$(find "$OUT" -name '*.class' | wc -l)
echo "compiled $N classes -> $OUT (sources: $PROTOGEN_JAVA + $PRONTO_JAVA)"
