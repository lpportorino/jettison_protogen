#!/usr/bin/env bash
# aot.sh — compile protodoc's doc-generation entry point to bytecode ONCE, and
# only when its inputs actually changed. Driven by `make docs-aot`, which every
# protodoc-backed docs target depends on.
#
# WHY. Each doc leg (`docs-generate`, `docs-render`, `docs-manifests`) is a
# fresh JVM, and nearly all of its wall clock is Clojure loading — i.e.
# compiling — the same namespace tree again: protodoc's own nine namespaces
# plus malli, selmer and telemere. Compiling that once into target/classes and
# putting it on the classpath (the `:aot` alias) turns a per-run compile into a
# per-run class load.
#
# FRESHNESS IS CONTENT-HASHED, NEVER mtime'd. An mtime records when a file
# OBJECT was last written, which a checkout, a `cp -p` or a tar extract
# re-stamps independently of content — so a timestamp comparison can call
# stale bytecode fresh. The hash below is over the BYTES of every input the
# compile reads. A miss recompiles from scratch (the class dir is removed
# first), so a DELETED namespace cannot leave an orphan class behind, which is
# the one thing a timestamp could never notice.
#
# THIS HASH IS THE ONLY REAL GUARD — RT/load's fallback is NOT a second one.
# clojure.lang.RT/load prefers a `.class` over the `.clj` it shadows only when
# the class is NEWER, and the sources are deliberately NOT copied into
# target/classes, so an ORDINARY edit — which stamps mtime=now — does fall back
# to loading source. But that preference is ITSELF an mtime comparison: the
# exact mechanism the paragraph above calls unsound. Give an edited source an
# OLD mtime — the `cp -p`, `rsync -a`, tar-extract or checkout shape — and the
# stale bytecode loads SILENTLY. Reproduce it in three commands: append a
# `(println "MARKER")` to a source file, `touch -t 202001010000` it, then
# `clojure -M:aot -e "(require 'protodoc.core)"` prints no marker; run this
# script first and it does, because the hash saw the bytes and the mtime was
# never consulted.
#
# SO: EVERY `-M:aot:run` LEG MUST TAKE `docs-aot` AS A PREREQUISITE. There is no
# belt behind these braces; the source fallback is a convenience for the
# ordinary-edit case, not a guarantee.
#
# WHAT THE HASH DOES NOT COVER: the JDK and the toolchain image. deps.edn pins
# Clojure itself, so the residual is a JDK swap with deps.edn untouched — and
# that fails LOUD on load (UnsupportedClassVersionError), never as silent
# staleness, which is why it is left out rather than guarded.
#
# NO LOCKING. Two CONCURRENT `make` invocations could have one delete the class
# dir under the other's mid-load JVM. A single `make` run is safe however many
# doc targets it names — `docs-aot` is a phony prerequisite that runs once.
#
# WHAT IS *NOT* COMPILED, on purpose: the test leg. A gate's job is to judge the
# SOURCE that ships, and AOT classes for src namespaces underneath
# source-loaded tests is a mixed load mode whose equivalence nobody has proven.
set -euo pipefail

cd "$(dirname "$0")"

CLASS_DIR="target/classes"
STAMP="target/.aot-inputs.sha256"
# `compile` emits <ns>__init.class for every namespace it touches; this one is
# the entry point the docs legs run. Its absence means the compile produced
# nothing usable, whatever exit code it reported.
ENTRY_CLASS="$CLASS_DIR/protodoc/core__init.class"

# Every byte the compile reads: the sources, the resources that ship on the
# classpath beside them, the dependency set, and this script itself (it decides
# WHAT gets compiled, so it is an input to the result). Overhash rather than
# underhash — a needless recompile costs seconds, a missed one runs stale
# bytecode.
inputs_hash() {
  local n
  n="$(find src resources -type f | wc -l)"
  if [ "$n" -eq 0 ]; then
    echo "aot.sh: FATAL: no files under src/ or resources/ — refusing to hash an empty input set" >&2
    exit 1
  fi
  {
    find src resources -type f -print0 | xargs -0 sha256sum
    sha256sum deps.edn aot.sh
  } | sort | sha256sum | cut -d' ' -f1
}

want="$(inputs_hash)"

if [ -f "$STAMP" ] && [ -f "$ENTRY_CLASS" ] && [ "$(cat "$STAMP")" = "$want" ]; then
  echo "protodoc AOT: up to date ($want)"
  exit 0
fi

echo "protodoc AOT: compiling protodoc.core -> $CLASS_DIR"
rm -rf "$CLASS_DIR"
mkdir -p "$CLASS_DIR"
clojure -M:aot-compile

if [ ! -f "$ENTRY_CLASS" ]; then
  echo "aot.sh: FATAL: compile left no $ENTRY_CLASS" >&2
  exit 1
fi

printf '%s\n' "$want" >"$STAMP"
echo "protodoc AOT: compiled ($want)"
