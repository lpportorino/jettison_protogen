#!/usr/bin/env bash
# protocol_gen_canary.sh — the protocol generator's canary.
#
# IT IS NOT A DEMO. It drives the REAL generator over data this repository owns,
# in both directions, and it FAILS when the generator is broken. Every case
# asserts an exact EXIT CODE and a substring naming the finding, so a stack
# trace can never be mistaken for a clause firing.
#
# THE THREE FAILURES IT EXISTS TO CATCH, each proven by MUTATION:
#
#   1. A WRONG FIELD NUMBER. The mutation makes the renderer number fields by
#      their position in the emitted set. Every projection would still be
#      internally consistent, so nothing that looks at one file alone can see
#      it — the oracle compares against the SOURCE numbers instead.
#   2. A DROPPED GRANT EMITTING A SILENTLY-ALLOWED FIELD. The mutation makes an
#      explicit field set behave like `:all`. The permission mirror moves with
#      it, so mirror-versus-schema stays clean; the oracle re-derives what the
#      POLICY granted and catches it there.
#   3. A CONSTRUCT THE EMITTER CANNOT EXPRESS BEING APPROXIMATED. The mutation
#      neuters the unresolved-reference clause and the FINDING disappears.
#
#      READ THE PREDICATE RATHER THAN INFERRING IT FROM A RED, which is the
#      trap `.claude/rules/gate-enforcement.md` §2 names. On the GENERATION path
#      that clause is shadowed by the closure check one pass later: an input
#      whose reference resolves to nothing also has a type the policy could not
#      have granted, so with the clause dead the run STILL refuses, for a
#      different reason. That is defence in depth working, and a case asserting
#      only a colour flip there would have been unattributable.
#
#      So attribution is taken on the SURVEY path, where the clause is reached
#      with no policy in the picture and nothing else can refuse the same input;
#      the generation path is then asserted on the FINDING NAME rather than on
#      the exit code. A neighbouring clause is required to keep refusing on that
#      same mutant, or a mutation that broke the whole pass would satisfy the
#      case by breaking everything.
#
# THE ORACLE IS ALWAYS THE REAL TREE'S. A mutation is applied to a COPY of the
# generator, and the verifier that judges its output is the one under
# tools/protocol-gen — so a mutant cannot mark its own work.
#
# HERMETIC. Every mutation lands in a scratch copy; the tracked tree is never
# written, so there is no restore whose success anyone has to take on trust.
#
# Usage: bash tools/protocol-gen/canary/protocol_gen_canary.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd -- "$SCRIPT_DIR/../../.." && pwd -P)"
PG="$ROOT/tools/protocol-gen"
DESCRIPTOR_SET="$ROOT/output/json-descriptors/descriptor-set.binpb"
EMITTED_FILES="sensor-reader.proto,commander.proto"

# A MISSING TOOL IS A HARD FAILURE WITH AN INSTALL HINT, never a skip: a canary
# that passed because its toolchain was absent is the defect it exists to catch,
# wearing a green.
for tool in clojure protoc; do
	command -v "$tool" > /dev/null 2>&1 || {
		printf '\033[31mFAIL\033[0m — %s is not on PATH.\n' "$tool" >&2
		printf '  This suite compiles emitted protos and runs the generator, so it\n' >&2
		printf '  cannot report anything without both. Install it, or run inside the\n' >&2
		printf '  toolchain container.\n' >&2
		exit 3
	}
done

# protoc resolves the emitted files' validation import out of the COMMITTED
# descriptor set, so no network and no vendored copy of that schema is needed.
[ -f "$DESCRIPTOR_SET" ] || {
	printf '\033[31mFAIL\033[0m — descriptor set not found: %s\n' "$DESCRIPTOR_SET" >&2
	printf '  It is what supplies the validation import to protoc offline.\n' >&2
	exit 3
}

MUTATE_LIB="$ROOT/tools/lint/test/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	printf '  Every attribution proof here is a mutation, so without it this suite\n' >&2
	printf '  cannot break anything and its green would mean nothing.\n' >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fails=0
cases=0
ok() {
	cases=$((cases + 1))
	printf '  \033[32mok\033[0m   %s\n' "$1"
}
bad() {
	cases=$((cases + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$1" >&2
	fails=$((fails + 1))
}
section() { printf '\n\033[1m== %s\033[0m\n' "$1"; }

# The primitives prove they can REFUSE before anything depends on them.
mutate_selftest "$WORK/mutate-selftest" || fails=$((fails + 1))
contains_selftest "$WORK/contains-selftest" || fails=$((fails + 1))

# copy_tool <dest> — a scratch copy of the generator, mutable without touching
# the tracked tree.
copy_tool() {
	mkdir -p "$1"
	cp -a "$PG/deps.edn" "$PG/src" "$PG/fixtures" "$PG/verify" "$1/"
}

# generate <tool-dir> <out-dir> <policy-file> <db-file> [registry-file]
# Runs the generator BARE and reads its own status on the next line; a pipe here
# would report the filter's status and a red would record as green.
GEN_RC=0
GEN_OUT=""
generate() {
	local tool="$1" out="$2" policy="$3" database="$4"
	local registry="${5:-fixtures/numbering-registry.edn}"
	set +e
	GEN_OUT="$(cd "$tool" && clojure -M:run generate \
		--db "$database" --minted fixtures/minted.edn \
		--registry "$registry" --policy "$policy" --out "$out" 2>&1)"
	GEN_RC=$?
	set -e
}

# verify <out-dir> [file-list] — compile the emitted protos and judge them with
# the REAL tree's oracle.
VER_RC=0
VER_OUT=""
verify() {
	local out="$1" files="${2:-$EMITTED_FILES}"
	set +e
	protoc "--descriptor_set_in=$DESCRIPTOR_SET" -I "$out" \
		"--descriptor_set_out=$out/emitted.binpb" \
		"$out/sensor-reader.proto" "$out/commander.proto" > "$out/protoc.log" 2>&1
	local protoc_rc=$?
	set -e
	if [ "$protoc_rc" -ne 0 ]; then
		VER_RC=90
		VER_OUT="$(cat "$out/protoc.log")"
		return
	fi
	set +e
	VER_OUT="$(cd "$PG" && clojure -M:verify emitted \
		--descriptor "$out/emitted.binpb" --files "$files" \
		--policy fixtures/policy.edn --db fixtures/db.edn \
		--minted fixtures/minted.edn --registry fixtures/numbering-registry.edn \
		--mirror "$out/permissions.edn" 2>&1)"
	VER_RC=$?
	set -e
}

section "STRUCTURE — the oracle shares no code with the generator"
# An oracle that imported the thing it judges could only ever agree with it.
if command grep -qE '\[protocol-gen\.' "$PG/verify/protocol_gen/verify.clj"; then
	bad "the verifier requires a generator namespace — it is no longer independent"
else
	ok "the verifier requires no generator namespace"
fi
# THE CONTROL. The probe above returns nothing on a clean file, which is also
# what a broken probe returns, so it is run against a file that DOES require one.
if command grep -qE '\[protocol-gen\.' "$PG/src/protocol_gen/core.clj"; then
	ok "CONTROL: the same probe finds a generator require where one exists"
else
	bad "the independence probe matches nothing at all — it is not a probe"
fi

section "FIXTURE HONESTY — the hand-written database matches its own protos"
# Every later case is a statement about the generator over this input. If the
# input has drifted from the proto it claims to mirror, all of them are
# statements about nothing.
FIXWORK="$WORK/fixture-db"
mkdir -p "$FIXWORK"
set +e
protoc "--descriptor_set_in=$DESCRIPTOR_SET" -I "$PG/fixtures/proto" \
	"--descriptor_set_out=$FIXWORK/fixtures.binpb" \
	"$PG/fixtures/proto/fixture_core.proto" > "$FIXWORK/protoc.log" 2>&1
fix_protoc_rc=$?
set -e
if [ "$fix_protoc_rc" -eq 0 ]; then
	ok "the fixture protos compile"
else
	bad "the fixture protos do not compile: $(cat "$FIXWORK/protoc.log")"
fi
set +e
FIX_OUT="$(cd "$PG" && clojure -M:verify fixture-db \
	--descriptor "$FIXWORK/fixtures.binpb" --files fixture_core.proto \
	--db fixtures/db.edn 2>&1)"
FIX_RC=$?
set -e
if [ "$FIX_RC" -eq 0 ]; then
	ok "the fixture database agrees with protoc, field by field"
else
	bad "the fixture database disagrees with its own protos (rc=$FIX_RC): $FIX_OUT"
fi
# AND IT CAN GO RED. A comparison that passed on its first run and was never
# seen to fail is not evidence: it may be comparing nothing.
MFIX="$WORK/mfix"
copy_tool "$MFIX"
mutate_file "$MFIX/fixtures/db.edn" \
	'{:number 3 :name "value" :type :double :constraints {:gte -1 :lte 1}}' \
	'{:number 4 :name "value" :type :double :constraints {:gte -1 :lte 1}}' \
	|| bad "the fixture-database mutation did not land"
set +e
MFIX_OUT="$(cd "$MFIX" && clojure -M:verify fixture-db \
	--descriptor "$FIXWORK/fixtures.binpb" --files fixture_core.proto \
	--db fixtures/db.edn 2>&1)"
MFIX_RC=$?
set -e
if [ "$MFIX_RC" -eq 1 ] && contains "$MFIX_OUT" "verify fixture-db: FAIL" &&
	contains "$MFIX_OUT" "descriptor has"; then
	ok "MUTANT: a database number that disagrees with the proto is REFUSED"
else
	bad "a drifted fixture database was not caught (rc=$MFIX_RC): $MFIX_OUT"
fi

section "BASELINE — the real tree emits, compiles, and agrees with its inputs"
BASE="$WORK/base"
generate "$PG" "$BASE" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the generator runs clean over the fixtures"
else
	bad "the generator failed on the real tree (rc=$GEN_RC): $GEN_OUT"
fi
verify "$BASE"
if [ "$VER_RC" -eq 0 ]; then
	ok "protoc compiles both emitted files and the oracle agrees"
else
	bad "baseline verification failed (rc=$VER_RC): $VER_OUT"
fi
# NON-VACUITY: a clean verdict over zero fields reads exactly like a clean
# verdict over the real ones.
if contains "$VER_OUT" "0 field(s)"; then
	bad "the oracle checked ZERO fields — a green over nothing"
else
	ok "the oracle reports a non-zero field count"
fi

section "PROJECTION — a message granted to nobody is in no emitted file"
if command grep -q 'Secret' "$BASE/sensor-reader.proto" "$BASE/commander.proto"; then
	bad "an ungranted message reached an emitted schema"
else
	ok "the ungranted message appears in neither file"
fi
if command grep -q 'pgfix_Reading' "$BASE/sensor-reader.proto"; then
	ok "CONTROL: a GRANTED message does appear, so the probe can see one"
else
	bad "the granted message is missing — the absence probe proves nothing"
fi

section "DETERMINISM — two runs over one input write identical bytes"
AGAIN="$WORK/again"
generate "$PG" "$AGAIN" fixtures/policy.edn fixtures/db.edn
if diff -r -q "$BASE/sensor-reader.proto" "$AGAIN/sensor-reader.proto" > /dev/null 2>&1 &&
	diff -q "$BASE/permissions.edn" "$AGAIN/permissions.edn" > /dev/null 2>&1; then
	ok "the schema and the mirror are byte-identical across runs"
else
	bad "the generator is not deterministic"
fi

section "MUTATION 1 — numbering by POSITION is caught"
M1="$WORK/m1"
copy_tool "$M1"
mutate_file "$M1/src/protocol_gen/render.clj" \
	'(let [fields (mapv #(render-field names options-for (:id msg) %) (:fields msg))' \
	'(let [fields (vec (map-indexed (fn [i f] (assoc (render-field names options-for (:id msg) f) :number (inc i))) (:fields msg)))' \
	|| bad "mutation 1 did not land"
generate "$M1" "$WORK/m1-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
verify "$WORK/m1-out"
if [ "$VER_RC" -eq 1 ] && contains "$VER_OUT" "descriptor has"; then
	ok "positional numbering is REFUSED, naming the disagreement"
else
	bad "positional numbering was not caught (rc=$VER_RC): $VER_OUT"
fi

section "MUTATION 2 — a withheld field emitted anyway is caught"
M2="$WORK/m2"
copy_tool "$M2"
mutate_file "$M2/src/protocol_gen/projection.clj" \
	'    (if (= :all wanted)
      (:fields msg)' \
	'    (if true
      (:fields msg)' \
	|| bad "mutation 2 did not land"
generate "$M2" "$WORK/m2-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
verify "$WORK/m2-out"
if [ "$VER_RC" -eq 1 ] && contains "$VER_OUT" "granted by nothing"; then
	ok "a silently-allowed field is REFUSED, naming the grant it lacks"
else
	bad "a silently-allowed field was not caught (rc=$VER_RC): $VER_OUT"
fi
# The mirror moved WITH the projection, so this case is not caught by comparing
# the mirror against the schema. Recorded because it is the reason the oracle
# re-derives the expectation from the policy rather than trusting the mirror.
if contains "$VER_OUT" "mirror "; then
	bad "the mirror disagreed too — this case no longer proves the policy re-derivation"
else
	ok "the mirror agreed with the schema, so the catch came from the POLICY"
fi

section "MUTATION 3 — an inexpressible construct approximated instead of refused"
REFUSAL_OUT="$WORK/refusal-out"
generate "$PG" "$REFUSAL_OUT" fixtures/refusal-policy.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "unresolved-type-ref"; then
	ok "the real tree REFUSES an unresolvable reference, by name"
else
	bad "the unresolvable reference was not refused (rc=$GEN_RC): $GEN_OUT"
fi
M3="$WORK/m3"
copy_tool "$M3"
mutate_file "$M3/src/protocol_gen/constructs.clj" \
	'      (and (contains? db/referring-types t)
           (not (or (db/message-ref? database type-ref) (db/enum-ref? database type-ref))))
      {:reason :unresolved-type-ref' \
	'      (and false (contains? db/referring-types t)
           (not (or (db/message-ref? database type-ref) (db/enum-ref? database type-ref))))
      {:reason :unresolved-type-ref' \
	|| bad "mutation 3 did not land"
generate "$M3" "$WORK/m3-out" fixtures/refusal-policy.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && ! contains "$GEN_OUT" "unresolved-type-ref"; then
	ok "MUTANT: the FINDING is gone; the closure check refuses instead (defence in depth)"
else
	bad "the mutant still named the clause (rc=$GEN_RC), so the red is not attributable: $GEN_OUT"
fi
# ATTRIBUTION, taken where the clause is reached ALONE. The survey applies no
# policy, so no closure check can shadow it.
survey() {
	local tool="$1" database="$2"
	set +e
	GEN_OUT="$(cd "$tool" && clojure -M:run survey --db "$database" 2>&1)"
	GEN_RC=$?
	set -e
}
survey "$PG" fixtures/refusal-db.edn
if [ "$GEN_RC" -eq 0 ] && contains "$GEN_OUT" ":unresolved-type-ref 1"; then
	ok "the survey reaches the clause alone and counts the refusal"
else
	bad "the survey did not report the refusal (rc=$GEN_RC): $GEN_OUT"
fi
survey "$M3" fixtures/refusal-db.edn
if [ "$GEN_RC" -eq 0 ] && contains "$GEN_OUT" ":refusals 0"; then
	ok "MUTANT: the same survey counts NOTHING — the finding WAS that clause"
else
	bad "the mutant still counted a refusal, so the clause is not attributed: $GEN_OUT"
fi
# THE NEIGHBOUR, on that same mutant. Without it, a mutation that broke the
# whole pass would satisfy the case above by breaking everything.
survey "$M3" fixtures/refusal-db-reserved.edn
if [ "$GEN_RC" -eq 0 ] && contains "$GEN_OUT" ":field-number-reserved 1"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 3 broke more than its clause"
fi
# And the typo policy proves the generation path is still alive on the mutant.
generate "$M3" "$WORK/m3-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: the generation path still refuses a mistyped grant on that mutant"
else
	bad "the generation path stopped refusing on the mutant: $GEN_OUT"
fi

section "NUMBERING — an unpinned minted field stops the run"
M4="$WORK/m4"
copy_tool "$M4"
mutate_file "$M4/fixtures/numbering-registry.edn" \
	'{"pgfix.Heartbeat" {"seq" 1, "sent_at_ns" 2}}' \
	'{"pgfix.Heartbeat" {"seq" 1}}' \
	|| bad "the registry mutation did not land"
generate "$M4" "$WORK/m4-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "Field not in the field-number registry"; then
	ok "an unpinned field REFUSES the run rather than being numbered"
else
	bad "an unpinned field did not stop the run (rc=$GEN_RC): $GEN_OUT"
fi

section "VACUITY — the oracle refuses to judge an empty population"
verify "$BASE" "no-such-file.proto"
if [ "$VER_RC" -eq 2 ] && contains "$VER_OUT" "CANNOT RUN"; then
	ok "an empty descriptor side is exit 2, not a pass"
else
	bad "an empty population did not report CANNOT RUN (rc=$VER_RC): $VER_OUT"
fi

section "summary"
printf '  cases:  %s\n' "$cases"
printf '  failed: %s\n' "$fails"
if [ "$fails" -ne 0 ]; then
	printf '\033[31m[protocol-gen-canary] %s FAILURE(S)\033[0m\n' "$fails" >&2
	exit 1
fi
printf '\033[32m[protocol-gen-canary] ALL GREEN (%s cases)\033[0m\n' "$cases"
