#!/usr/bin/env bash
# protocol_gen_canary.sh — the protocol generator's canary.
#
# IT IS NOT A DEMO. It drives the REAL generator over data this repository owns,
# in both directions, and it FAILS when the generator is broken. Every case
# asserts an exact EXIT CODE and a substring naming the finding, so a stack
# trace can never be mistaken for a clause firing.
#
# THE FAILURES IT EXISTS TO CATCH, each proven by MUTATION. Read the list from
# the sections below rather than from a tally here, which rots the next time one
# lands:
#
#   * A WRONG FIELD NUMBER. The mutation makes the renderer number fields by
#     their position in the emitted set. Every projection would still be
#     internally consistent, so nothing that looks at one file alone can see
#     it — the oracle compares against the SOURCE numbers instead.
#   * A DROPPED GRANT EMITTING A SILENTLY-ALLOWED FIELD. The mutation makes an
#     explicit field set behave like `:all`. The permission mirror moves with
#     it, so mirror-versus-schema stays clean; the oracle re-derives what the
#     POLICY granted and catches it there.
#   * A CONSTRUCT THE EMITTER CANNOT EXPRESS BEING APPROXIMATED. The mutation
#     neuters the unresolved-reference clause and the FINDING disappears.
#
#     READ THE PREDICATE RATHER THAN INFERRING IT FROM A RED, which is the
#     trap `.claude/rules/gate-enforcement.md` §2 names. On the GENERATION path
#     that clause is shadowed by the closure check one pass later: an input
#     whose reference resolves to nothing also has a type the policy could not
#     have granted, so with the clause dead the run STILL refuses, for a
#     different reason. That is defence in depth working, and a case asserting
#     only a colour flip there would have been unattributable.
#
#     So attribution is taken on the SURVEY path, where the clause is reached
#     with no policy in the picture and nothing else can refuse the same input;
#     the generation path is then asserted on the FINDING NAME rather than on
#     the exit code. A neighbouring clause is required to keep refusing on that
#     same mutant, or a mutation that broke the whole pass would satisfy the
#     case by breaking everything.
#
#   * A MINTED ONEOF SILENTLY FLATTENED INTO FREE FIELDS. The mutation restores
#     the empty oneof vector the stamping pass used to write unconditionally.
#     Its fields still emit, with the right numbers, so the ORACLE STAYS CLEAN
#     — asserted here rather than assumed, because that blindness is the whole
#     reason this case reads the emitted TEXT.
#   * A MINTED ENUM THAT DOES NOT RESOLVE. The mutation points enum resolution
#     back at the database, where a locally-minted enum has never been, and the
#     run refuses `enum-not-in-database`.
#   * A MINTED ONEOF NAMING A MEMBER ITS MESSAGE DOES NOT CARRY, and one
#     naming a field TWO oneofs claim. Each is proven by neutering its own
#     clause, and each is the OTHER's neighbouring control on that same mutant
#     — so neither red can be a mutation that broke the pass as a whole.
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

# generate <tool-dir> <out-dir> <policy-file> <db-file> [registry-file] [mint-file]
# Runs the generator BARE and reads its own status on the next line; a pipe here
# would report the filter's status and a red would record as green.
GEN_RC=0
GEN_OUT=""
generate() {
	local tool="$1" out="$2" policy="$3" database="$4"
	local registry="${5:-fixtures/numbering-registry.edn}"
	local mints="${6:-fixtures/minted.edn}"
	set +e
	GEN_OUT="$(cd "$tool" && clojure -M:run generate \
		--db "$database" --minted "$mints" \
		--registry "$registry" --policy "$policy" --out "$out" 2>&1)"
	GEN_RC=$?
	set -e
}

# oneof_block <file> <name> — the emitted text of one `oneof` block, so a case
# can ask what is INSIDE it. A whole-file grep cannot: every member of a oneof
# is also a plain field line, so "the file mentions raw" is true whether raw is
# in the block, in a different block, or free.
oneof_block() {
	sed -n "/^  oneof $2 {/,/^  }/p" "$1"
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
# AND INSIDE THE MINTED ONEOF, which is where the oracle cannot follow it. The
# same mutation renumbers a oneof's members, and because the emitter orders a
# block by NUMBER the two swap into declaration order — so this is the failing
# direction of the two assertions the MINTED ONEOF section makes on the real
# tree, taken on a mutant rather than asserted about one.
M1_DETAIL="$(oneof_block "$WORK/m1-out/commander.proto" detail)"
if ! contains "$M1_DETAIL" "uint32 code = 4;" &&
	[ "$(printf '%s\n' "$M1_DETAIL" |
		awk '/uint32 code/{c=NR} /string text/{t=NR} END{print (c && t && c < t) ? "yes" : "no"}')" = no ]; then
	ok "MUTANT: the minted oneof's members lose the registry's numbers AND their order"
else
	bad "positional numbering left the minted oneof intact, so those assertions prove nothing: $M1_DETAIL"
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

section "MINTED ONEOF — declared by NAME, emitted with the registry's numbers"
# The mint names its members "text" and "code"; the registry pins them 7 and 4.
# Everything asserted here is a fact about that translation.
DETAIL_BLOCK="$(oneof_block "$BASE/commander.proto" detail)"
if contains "$DETAIL_BLOCK" "uint32 code = 4;" &&
	contains "$DETAIL_BLOCK" "string text = 7"; then
	ok "the minted oneof's members carry the REGISTRY's numbers"
else
	bad "the minted oneof did not emit its members with the registry's numbers: $DETAIL_BLOCK"
fi
# Declaration order is text-then-code and the emitted order must be the
# NUMBERS' — 4 before 7. A block that merely contains both cannot tell the two
# orderings apart, which is exactly the defect a positional emitter produces.
if [ "$(printf '%s\n' "$DETAIL_BLOCK" |
	awk '/uint32 code = 4;/{c=NR} /string text = 7/{t=NR} END{print (c && t && c < t) ? "yes" : "no"}')" = yes ]; then
	ok "its members are emitted in NUMBER order, which is not their declaration order"
else
	bad "the minted oneof's members are not in number order: $DETAIL_BLOCK"
fi
if contains "$DETAIL_BLOCK" "option (buf.validate.oneof).required = true;"; then
	ok "the mint's :required reaches the emitted block"
else
	bad "the minted oneof lost its required option: $DETAIL_BLOCK"
fi
# The free field beside it keeps its own registry number and stays OUT of the
# block — a mint declares membership, so a field it did not name must not join.
if command grep -q '^  pgfix_Level level = 15' "$BASE/commander.proto" &&
	! contains "$DETAIL_BLOCK" "level"; then
	ok "the free field the mint left out of the oneof is not inside the block"
else
	bad "a field the mint did not name joined the oneof: $DETAIL_BLOCK"
fi

section "MUTATION 4 — a minted oneof flattened into free fields is caught"
MONEOF="$WORK/m-oneof"
copy_tool "$MONEOF"
mutate_file "$MONEOF/src/protocol_gen/numbering.clj" \
	'     :oneofs (mapv #(stamp-oneof msg-id by-name %) oneofs)}))' \
	'     :oneofs []}))' \
	|| bad "mutation 4 did not land"
generate "$MONEOF" "$WORK/m-oneof-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
if command grep -q '^  oneof detail {' "$WORK/m-oneof-out/commander.proto"; then
	bad "the mutant still emitted the oneof, so this case attributes nothing"
else
	ok "MUTANT: the oneof block is GONE and its fields emit free"
fi
# THE POINT OF THIS CASE. Every field is still there, under the right number, so
# the ORACLE — which reads the descriptor's fields and never their oneof index —
# reports the mutant CLEAN. That blindness is why the assertions above read the
# emitted text, and asserting it here stops a later reader assuming otherwise.
verify "$WORK/m-oneof-out"
if [ "$VER_RC" -eq 0 ]; then
	ok "CONTROL: the oracle calls that mutant CLEAN — it cannot see oneof membership"
else
	bad "the oracle reddened on the flattened mutant (rc=$VER_RC), so this case is now about something else: $VER_OUT"
fi

section "MUTATION 5 — a minted oneof's :required dropped is caught"
# The failing direction of the MINTED ONEOF section's assertion about that
# option, and it needs its OWN mutant: `buf.validate.oneof` also appears on the
# DESCRIPTOR oneof of pgfix_Command, which no mutation here touches, so a
# whole-file probe for that string is true on every mutant and proves nothing.
MREQ="$WORK/m-required"
copy_tool "$MREQ"
mutate_file "$MREQ/src/protocol_gen/numbering.clj" \
	'   :required required' \
	'   :required false' \
	|| bad "mutation 5 did not land"
generate "$MREQ" "$WORK/m-required-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
MREQ_DETAIL="$(oneof_block "$WORK/m-required-out/commander.proto" detail)"
if contains "$MREQ_DETAIL" "uint32 code = 4;" &&
	! contains "$MREQ_DETAIL" "option (buf.validate.oneof).required = true;"; then
	ok "MUTANT: the block still emits and its required option is GONE"
else
	bad "dropping the mint's :required did not change the emitted block: $MREQ_DETAIL"
fi
# CONTROL: the DESCRIPTOR oneof beside it is unaffected, so the assertion above
# is about the minted path and not about the emitter forgetting the option.
if contains "$(oneof_block "$WORK/m-required-out/commander.proto" action)" \
	"option (buf.validate.oneof).required = true;"; then
	ok "CONTROL: the descriptor oneof in the same file keeps its option"
else
	bad "the descriptor oneof lost its option too — mutation 5 broke more than the minted path"
fi

section "MINTED ENUM — declared in the mint, resolved out of the universe"
if command grep -q '^enum pgfix_Level {' "$BASE/commander.proto" &&
	command grep -q '  LEVEL_ALERT = 12;' "$BASE/commander.proto"; then
	ok "the minted enum is emitted with the numbers it declared"
else
	bad "the minted enum did not reach the emitted schema"
fi
# The projection property, for an enum no database carries: a group that did not
# list it does not name it. The presence check above is this probe's control.
if command grep -q 'pgfix_Level' "$BASE/sensor-reader.proto"; then
	bad "a minted enum reached a group that did not list it"
else
	ok "the group that did not list it does not name it"
fi

section "MUTATION 6 — a minted enum resolved against the DATABASE is caught"
MENUM="$WORK/m-enum"
copy_tool "$MENUM"
mutate_file "$MENUM/src/protocol_gen/projection.clj" \
	'        enums (project-enums all g)]' \
	'        enums (project-enums database g)]' \
	|| bad "mutation 6 did not land"
generate "$MENUM" "$WORK/m-enum-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "enum-not-in-database"; then
	ok "MUTANT: a minted enum stops resolving, and the run REFUSES rather than emitting"
else
	bad "asking the database alone did not refuse the minted enum (rc=$GEN_RC): $GEN_OUT"
fi
# THE NEIGHBOUR, on that same mutant: a clause that does not depend on enum
# resolution must still refuse, or the case above is satisfied by a mutation
# that broke the pass as a whole.
generate "$MENUM" "$WORK/m-enum-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 6 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 7 — a minted enum's members renumbered by POSITION is caught"
# The enum half of MUTATION 1, and it needs its own case because the numbers
# reach the file by a different route: an enum's members are declared in the
# mint and pass through untouched, where a field's come from the registry.
MENUMPOS="$WORK/m-enum-pos"
copy_tool "$MENUMPOS"
mutate_file "$MENUMPOS/src/protocol_gen/numbering.clj" \
	'  {:id enum-id :name enum-name :values values})' \
	'  {:id enum-id :name enum-name :values (vec (map-indexed (fn [i v] (assoc v :number i)) values))})' \
	|| bad "mutation 7 did not land"
generate "$MENUMPOS" "$WORK/m-enum-pos-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
if command grep -q '  LEVEL_ALERT = 12;' "$WORK/m-enum-pos-out/commander.proto"; then
	bad "the mutant kept the declared number, so the assertion above proves nothing"
else
	ok "MUTANT: the minted enum's declared numbers are replaced by positions"
fi
# THE SAME BLINDNESS AS MUTATION 4, on a different construct. The oracle checks
# that a granted enum is PRESENT and never reads its members, so it calls this
# mutant clean — which is why the assertion above reads the emitted text.
verify "$WORK/m-enum-pos-out"
if [ "$VER_RC" -eq 0 ]; then
	ok "CONTROL: the oracle calls that mutant CLEAN — it does not read enum members"
else
	bad "the oracle reddened on the renumbered enum (rc=$VER_RC), so this case is now about something else: $VER_OUT"
fi

section "MINTED ONEOF REFUSALS — an absent member, and one two oneofs claim"
ONEOF_REG=fixtures/refusal-registry-oneof.edn
generate "$PG" "$WORK/absent-out" fixtures/refusal-policy-oneof-absent.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-absent.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "oneof-member-absent"; then
	ok "the real tree REFUSES a member the message does not carry, by name"
else
	bad "a minted oneof naming an absent member was not refused (rc=$GEN_RC): $GEN_OUT"
fi
generate "$PG" "$WORK/shared-out" fixtures/refusal-policy-oneof-shared.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-shared.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "oneof-member-shared"; then
	ok "the real tree REFUSES a field claimed by two oneofs, by name"
else
	bad "a field in two oneofs was not refused (rc=$GEN_RC): $GEN_OUT"
fi

section "MUTATION 8 — the absent-member clause, broken alone"
MABSENT="$WORK/m-absent"
copy_tool "$MABSENT"
mutate_file "$MABSENT/src/protocol_gen/numbering.clj" \
	'                   (or (get by-name member)' \
	'                   (or (get by-name member) 0' \
	|| bad "mutation 8 did not land"
generate "$MABSENT" "$WORK/m-absent-out" fixtures/refusal-policy-oneof-absent.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-absent.edn
if [ "$GEN_RC" -eq 0 ] && ! contains "$GEN_OUT" "oneof-member-absent"; then
	ok "MUTANT: the FINDING is gone and the run emits the approximation instead"
else
	bad "the mutant still named the clause (rc=$GEN_RC), so the red is not attributable: $GEN_OUT"
fi
# THE NEIGHBOUR is the OTHER new clause, on this same mutant.
generate "$MABSENT" "$WORK/m-absent-neighbour" fixtures/refusal-policy-oneof-shared.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-shared.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "oneof-member-shared"; then
	ok "CONTROL: the shared-member clause still refuses on that same mutant"
else
	bad "the neighbouring clause stopped refusing too: $GEN_OUT"
fi

section "MUTATION 9 — the shared-member clause, broken alone"
MSHARED="$WORK/m-shared"
copy_tool "$MSHARED"
mutate_file "$MSHARED/src/protocol_gen/numbering.clj" \
	'          :when (> (count claims) 1)]' \
	'          :when (> (count claims) 99)]' \
	|| bad "mutation 9 did not land"
generate "$MSHARED" "$WORK/m-shared-out" fixtures/refusal-policy-oneof-shared.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-shared.edn
if [ "$GEN_RC" -eq 0 ] && ! contains "$GEN_OUT" "oneof-member-shared"; then
	ok "MUTANT: the FINDING is gone and the run emits instead of refusing"
else
	bad "the mutant still named the clause (rc=$GEN_RC): $GEN_OUT"
fi
# AND THE APPROXIMATION IS SHOWN, not merely inferred. `raw` was declared in
# both oneofs; the emitted file puts it in the first and `secondary` loses it
# without a word. This is what the refusal buys, and it compiles either way.
if contains "$(oneof_block "$WORK/m-shared-out/bad.proto" primary)" "raw" &&
	! contains "$(oneof_block "$WORK/m-shared-out/bad.proto" secondary)" "raw"; then
	ok "MUTANT: the losing oneof silently lost its member — a compiling, wrong schema"
else
	bad "the mutant did not produce the approximation this clause exists to prevent"
fi
# THE NEIGHBOUR, again the other new clause.
generate "$MSHARED" "$WORK/m-shared-neighbour" fixtures/refusal-policy-oneof-absent.edn fixtures/db.edn \
	"$ONEOF_REG" fixtures/refusal-mint-oneof-absent.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "oneof-member-absent"; then
	ok "CONTROL: the absent-member clause still refuses on that same mutant"
else
	bad "the neighbouring clause stopped refusing too: $GEN_OUT"
fi

section "NUMBERING — an unpinned minted field stops the run"
M4="$WORK/m4"
copy_tool "$M4"
mutate_file "$M4/fixtures/numbering-registry.edn" \
	'{"pgfix.Heartbeat" {"seq" 1, "sent_at_ns" 2}' \
	'{"pgfix.Heartbeat" {"seq" 1}' \
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
