#!/usr/bin/env bash
# lint_gate_checks_test.sh — canaries for the docstrings, spec-shape and fn-size
# checks (lint-gate.docstrings / lint-gate.specs / lint-gate.fnsize), and for the
# exemption contract in lint-gate.util that the first two share.
#
# WHY A SECOND SUITE. `lint_gate_test.sh` canaries the ns-size check and the
# dispatch. These two checks share almost nothing with it — a different population
# (vars and spec forms rather than namespaces), a different substrate (spec-shape
# reads SOURCE FORMS, not clj-kondo's analysis), and an exemption contract ns-size
# deliberately has none of. Folding them into one file would produce a suite whose
# fixture builder served three unrelated shapes.
#
# WHAT A CANARY HERE HAS TO PROVE:
#
#   ATTRIBUTION. Between them these checks have a dozen clauses that refuse, and
#   most exit 3. So every case asserts the exact code AND a substring naming the
#   clause; the blocking ones are proven by MUTATION — break that clause alone, the
#   fixture changes verdict, and a neighbour still refuses.
#
#   FAIL IS NOT ERROR. 1 is a verdict about the tree, 3 is a precondition failure,
#   and the JVM's own 1-on-exception is re-labelled by the gate's top-level trap.
#   A suite asserting merely "non-zero" would accept a stack trace as proof.
#
#   THE EXEMPTION CONTRACT IS A GATE OF ITS OWN. Four mandatory fields, a closed
#   key set, an expiry, a horizon, and staleness. Each of those five is a way the
#   waiver machinery can silently stop constraining anything, so each gets a case.
#
# THE FIXTURE IS A SYNTHETIC CORPUS. Each case builds a throw-away tree with its own
# gates.edn and exemptions.edn and runs a COPY of the whole gate against it, so no
# expectation moves when this repo does and no tracked file is perturbed.
#
# Usage: bash tools/lint/test/lint_gate_checks_test.sh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SRC="$SCRIPT_DIR/../src/lint_gate"

[ -d "$SRC" ] || {
	printf '\033[31mFAIL\033[0m — gate sources not found at %s\n' "$SRC" >&2
	exit 3
}
command -v clojure > /dev/null 2>&1 || {
	printf '\033[31mFAIL\033[0m — clojure is not on PATH; this suite cannot run.\n' >&2
	exit 3
}
command -v clj-kondo > /dev/null 2>&1 || {
	printf '\033[31mFAIL\033[0m — clj-kondo is not on PATH; the docstrings check needs it.\n' >&2
	exit 3
}

MUTATE_LIB="$SCRIPT_DIR/lib_mutate.sh"
[ -f "$MUTATE_LIB" ] || {
	printf '\033[31mFAIL\033[0m — missing mutation primitive at %s\n' "$MUTATE_LIB" >&2
	exit 3
}
# shellcheck source=tools/lint/test/lib_mutate.sh
. "$MUTATE_LIB"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lint-gate-checks.XXXXXX")"
trap 'rm -rf -- "$WORK"' EXIT

PASS=0
FAILED=0
ok() {
	PASS=$((PASS + 1))
	printf '  \033[32mok\033[0m   %s\n' "$*"
}
bad() {
	FAILED=$((FAILED + 1))
	printf '  \033[31mFAIL\033[0m %s\n' "$*" >&2
}
banner() { printf '\n== %s\n' "$*"; }

# A date far enough out that fixture :expires values stay inside the horizon while
# this suite is not itself a clock-dependent test. The gate takes `now` from the
# system clock, so fixtures use a RELATIVE date computed here — a hardcoded one
# would start failing on a fixed day, which is how an expiry canary stops being
# able to go red.
SOON="$(date -d '+30 days' +%Y-%m-%d)"
# LOCAL zone, and TWO days rather than one. The gate compares against
# `LocalDate/now`, which uses the system DEFAULT zone; computing this in UTC made
# the canary fail on any machine whose local date is behind UTC, because
# "yesterday in UTC" is then "today locally" and an entry expiring TODAY is
# correctly not yet expired. Two days is beyond any zone offset, so this stays
# strictly in the past regardless of where the machine is.
PAST="$(date -d '-2 days' +%Y-%m-%d)"
FAR="$(date -d '+400 days' +%Y-%m-%d)"

# fixture <name> — a tree with the gate, an empty exemptions file, and config
# enrolling `src`. Echoes the path.
fixture() {
	local name="$1"
	local dir="$WORK/$name"
	rm -rf -- "$dir"
	mkdir -p "$dir/tools/lint" "$dir/gate/lint_gate" "$dir/src/fixture"
	cp "$SRC"/*.clj "$dir/gate/lint_gate/"
	cat > "$dir/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 9999 :nesting-block 9999 :decisions-block 9999
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}
 :spec-presence {:enrolled ["fixture.s"]}}
EDN
	printf '{:docstrings []\n :spec-shape []\n :spec-presence []}\n' > "$dir/tools/lint/exemptions.edn"
	printf '%s' "$dir"
}

run_gate() {
	local dir="$1"
	shift
	# -J-XX:TieredStopAtLevel=1 caps JIT at the fast baseline compiler. Each case is a
	# fresh JVM running for well under a second, so tier-4 compilation never pays for
	# itself — the run ends before the optimised code would. MEASURED interleaved on one
	# gate invocation: 1031 ms -> 729 ms mean (~29%), with tighter variance too
	# (684-763 vs 834-1208). It changes no VERDICT: the same bytecode executes.
	(cd "$dir" && clojure -J-XX:TieredStopAtLevel=1 -Sdeps '{:paths ["gate"]}' -M -m lint-gate.core "$@" 2>&1) || return $?
}

# expect <dir> <code> <needle> <label> [args...]
expect() {
	local dir="$1" want="$2" needle="$3" label="$4"
	shift 4
	local out code
	out="$(run_gate "$dir" "$@")" && code=0 || code=$?
	if [ "$code" != "$want" ]; then
		bad "$label — expected exit $want, got $code"
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	if [ -n "$needle" ] && ! printf '%s' "$out" | grep -qF -- "$needle"; then
		bad "$label — exit $code was right but the diagnosis never named the clause"
		printf '       | wanted: %s\n' "$needle" >&2
		printf '%s\n' "$out" | sed 's/^/       | /' >&2
		return
	fi
	ok "$label"
}

mutate() {
	local dir="$1" file="$2" old="$3" new="$4"
	local err
	if ! err="$(mutate_file "$dir/gate/lint_gate/$file" "$old" "$new" 2>&1)"; then
		bad "$err"
		return 1
	fi
	return 0
}

# ---------------------------------------------------------------------------
banner 'THE MUTATION PRIMITIVE ITSELF — it must be able to REFUSE'
if mutate_selftest "$WORK/_selftest"; then
	PASS=$((PASS + 7))
else
	bad 'the mutation primitive failed its own self-test — every proof below is void'
fi

# ===========================================================================
banner 'DOCSTRINGS — a documented corpus passes'
D="$(fixture doc_clean)"
cat > "$D/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn documented "Says what it is for." [x] x)
(defn- also-private "Private vars are in scope too." [x] x)
CLJ
expect "$D" 0 'documented across' 'a fully documented corpus is clean' --check docstrings src

banner 'DOCSTRINGS — a missing docstring is a FINDING'
D="$(fixture doc_missing)"
cat > "$D/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn documented "Says what it is for." [x] x)
(defn bare [x] x)
CLJ
expect "$D" 1 'bare' 'an undocumented defn is a FINDING (exit 1)' --check docstrings src

# ATTRIBUTION: silence only the blank-docstring predicate. Everything else — the
# form filter, the enrolment test, the generated exclusion — is untouched.
D2="$(fixture doc_missing_mut)"
cp "$D/src/fixture/a.clj" "$D2/src/fixture/a.clj"
if mutate "$D2" docstrings.clj ':when (str/blank? (str (:doc v)))' ':when false'; then
	expect "$D2" 0 'documented across' \
		'MUTANT: blank-docstring clause silenced -> clean (the red was that clause)' \
		--check docstrings src
fi

banner 'DOCSTRINGS — an EMPTY docstring counts as missing'
# The gate is for orientation, not box-ticking, and "" is what box-ticking produces.
D="$(fixture doc_empty)"
cat > "$D/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn documented "Real." [x] x)
(defn hollow "" [x] x)
CLJ
expect "$D" 1 'hollow' 'an empty-string docstring is still a FINDING' --check docstrings src

banner 'DOCSTRINGS — `def` is NOT in scope'
# A Malli registry entry is a `def`; requiring a docstring on one would fire on
# every schema in the tree.
D="$(fixture doc_def)"
cat > "$D/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(def registry {:a 1})
(defn documented "Real." [x] x)
CLJ
expect "$D" 0 'documented across' 'a bare `def` owes no docstring' --check docstrings src

banner 'DOCSTRINGS — an UNENROLLED root is out of scope'
D="$(fixture doc_unenrolled)"
mkdir -p "$D/other"
cat > "$D/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn documented "Real." [x] x)
CLJ
cat > "$D/other/b.clj" <<'CLJ'
(ns other.b)
(defn undocumented-but-unenrolled [x] x)
CLJ
expect "$D" 0 'documented across' 'a file outside :enrolled is not judged' \
	--check docstrings src other

banner 'DOCSTRINGS — NON-VACUITY: an empty population must not read as clean'
# The vacuous value here is a PASSING one: no population means no misses, so the
# check would print full coverage and exit 0 — a perfect score for measuring nothing.
D="$(fixture doc_vacuous)"
printf '(ns fixture.a)\n(def only-a-def 1)\n' > "$D/src/fixture/a.clj"
expect "$D" 3 'ZERO defn-family vars' 'a population of zero is CANNOT RUN, never a green' \
	--check docstrings src

D2="$(fixture doc_vacuous_mut)"
printf '(ns fixture.a)\n(def only-a-def 1)\n' > "$D2/src/fixture/a.clj"
if mutate "$D2" docstrings.clj '(when (empty? population)' '(when false'; then
	expect "$D2" 0 'documented across' \
		'MUTANT: vacuity floor removed -> the vacuous GREEN it exists to refuse' \
		--check docstrings src
fi

banner 'DOCSTRINGS — empty :enrolled is CANNOT RUN'
D="$(fixture doc_noenrol)"
printf '(ns fixture.a)\n(defn d "x" [] 1)\n' > "$D/src/fixture/a.clj"
printf '{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}\n :docstrings {:enrolled []}\n :spec-shape {:enrolled ["src"]}}\n' > "$D/tools/lint/gates.edn"
expect "$D" 3 'no enrolled roots' 'an empty declared scope refuses rather than judging nothing' \
	--check docstrings src

# ===========================================================================
banner 'SPEC-SHAPE — a tight spec passes'
D="$(fixture spec_clean)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat [:map [:k :int]]] [:vector :int]])
CLJ
expect "$D" 0 'm/=> form(s)' 'a spec with no naked position is clean' --check spec-shape src

banner 'SPEC-SHAPE — a naked position is a FINDING'
for nk in any map string; do
	D="$(fixture "spec_naked_$nk")"
	cat > "$D/src/fixture/s.clj" <<CLJ
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :$nk] :int])
CLJ
	expect "$D" 1 ":$nk" "a bare :$nk argument is a FINDING (exit 1)" --check spec-shape src
done

banner 'SPEC-SHAPE — the RETURN position is judged too'
D="$(fixture spec_ret)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat [:map [:k :int]]] :any])
CLJ
expect "$D" 1 ':any' 'a naked RETURN schema is a FINDING' --check spec-shape src

banner 'SPEC-SHAPE — :keyword and :int are DELIBERATELY not naked'
# For a function whose argument genuinely is an arbitrary keyword, `:keyword` is the
# tight answer; flagging it would push authors toward a narrower type that is FALSE.
D="$(fixture spec_tight_scalars)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x y] [x y])
(m/=> f [:=> [:cat :keyword :int] :boolean])
CLJ
expect "$D" 0 'm/=> form(s)' ':keyword and :int are tight, not naked' --check spec-shape src

# ATTRIBUTION: silence only the naked-set membership test.
D2="$(fixture spec_naked_mut)"
cat > "$D2/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
if mutate "$D2" specs.clj '(filter naked (spec-positions spec))' '(filter #{} (spec-positions spec))'; then
	expect "$D2" 0 'm/=> form(s)' \
		'MUTANT: naked-set test silenced -> clean (the red was that clause)' \
		--check spec-shape src
fi

banner 'SPEC-SHAPE — an UNPARSEABLE file is a FINDING, never a skip'
# "an unjudged element is a FINDING" one level up. Skipping the one file somebody
# broke is how a gate reports clean over exactly the thing that needs looking at.
D="$(fixture spec_unparseable)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat [:map [:k :int]]] :int])
CLJ
printf '(ns fixture.broken)\n(defn oops [\n' > "$D/src/fixture/broken.clj"
expect "$D" 1 'unparseable' 'a file the reader cannot parse is reported, not skipped' \
	--check spec-shape src

banner 'SPEC-SHAPE — NON-VACUITY: zero specs must not read as clean'
D="$(fixture spec_vacuous)"
printf '(ns fixture.s)\n(defn f "Doc." [x] x)\n' > "$D/src/fixture/s.clj"
expect "$D" 3 'ZERO m/=> forms' 'a population of zero specs is CANNOT RUN, never a green' \
	--check spec-shape src

D2="$(fixture spec_vacuous_mut)"
printf '(ns fixture.s)\n(defn f "Doc." [x] x)\n' > "$D2/src/fixture/s.clj"
if mutate "$D2" specs.clj '(when (empty? specs)' '(when false'; then
	expect "$D2" 0 'm/=> form(s)' \
		'MUTANT: vacuity floor removed -> the vacuous GREEN it exists to refuse' \
		--check spec-shape src
fi

# ===========================================================================
# SPEC-PRESENCE. The population is FUNCTIONS (not specs, as spec-shape's is), the
# scope is a set of NAMESPACES (not roots, as docstrings' is), and both of those
# differences are places the check can silently stop judging — an enrolment that
# names a namespace discovery cannot see scores perfectly over nothing, and a
# population predicate that stops matching prints exactly what a clean tree does.
banner 'SPEC-PRESENCE — a fully specced enrolled namespace passes'
D="$(fixture pres_clean)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
(defn- g "Doc." [x] x)
(m/=> g [:=> [:cat :int] :int])
CLJ
expect "$D" 0 'function(s) judged across' 'every enrolled defn carries a spec' \
	--check spec-presence src

banner 'SPEC-PRESENCE — THE KNOWN-BAD INPUT: a defn with no m/=> is a FINDING'
D="$(fixture pres_missing)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn specced "Doc." [x] x)
(m/=> specced [:=> [:cat :int] :int])
(defn unspecced "Doc." [x] x)
CLJ
expect "$D" 1 'unspecced' 'a defn with no m/=> is a FINDING (exit 1)' \
	--check spec-presence src
expect "$D" 1 'with no m/=> in an enrolled namespace' \
	'and the failure names the clause that refused' --check spec-presence src

# ATTRIBUTION. Silence ONLY the presence predicate — the enrolment test, the two
# vacuity floors, the reader and the unreadable-file clause are all untouched.
D2="$(fixture pres_missing_mut)"
cp "$D/src/fixture/s.clj" "$D2/src/fixture/s.clj"
if mutate "$D2" presence.clj '(not (contains? specced (:name d)))' 'false'; then
	expect "$D2" 0 'function(s) judged across' \
		'MUTANT: presence predicate silenced -> clean (the red WAS that clause)' \
		--check spec-presence src

	# CONTROL 1, on that SAME mutant: a file the reader cannot handle must STILL
	# be refused. A red here proves the mutation disabled one clause rather than
	# the check's ability to reach a verdict at all.
	printf '(ns fixture.broken)\n(defn oops [\n' > "$D2/src/fixture/broken.clj"
	expect "$D2" 1 'UNREADABLE' \
		'CONTROL: on the presence-dead mutant, the unreadable-file clause still refuses' \
		--check spec-presence src
	rm -f "$D2/src/fixture/broken.clj"

	# CONTROL 2, same mutant, a DIFFERENT neighbour and a different exit class:
	# delete the enrolled namespace's only file and the per-namespace floor must
	# still refuse — and at 3, not 1, so a precondition failure stays
	# distinguishable from a verdict even on a mutant.
	rm -f "$D2/src/fixture/s.clj"
	printf '(ns fixture.other)\n(defn h "Doc." [x] x)\n' > "$D2/src/fixture/other.clj"
	expect "$D2" 3 'not found' \
		'CONTROL: on the same mutant, the per-namespace floor still refuses (exit 3)' \
		--check spec-presence src
fi

banner 'SPEC-PRESENCE — an UNENROLLED namespace is out of scope'
# The scope is a DECLARATION, so a namespace outside it is not judged — that is
# the property that makes this adoptable, and it has to be pinned or the check
# could quietly be judging everything.
D="$(fixture pres_unenrolled)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
CLJ
cat > "$D/src/fixture/t.clj" <<'CLJ'
(ns fixture.t)
(defn bare-but-unenrolled "Doc." [x] x)
CLJ
expect "$D" 0 'function(s) judged across' 'a namespace outside :enrolled is not judged' \
	--check spec-presence src
expect "$D" 0 'UNJUDGED: 1 of 2' \
	'and the unjudged remainder is REPORTED, so a green cannot over-claim' \
	--check spec-presence src

banner 'SPEC-PRESENCE — defn- IS in the population'
# The widest of the available choices, matching lint-docstrings: a private helper
# is exactly where a reader lands with no context.
D="$(fixture pres_private)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
(defn- hidden "Doc." [x] x)
CLJ
expect "$D" 1 'hidden' 'an unspecced defn- is a FINDING too' --check spec-presence src

banner 'SPEC-PRESENCE — defmacro is DELIBERATELY out of the population'
# `m/=>` registers a FUNCTION schema over argument VALUES; a macro receives
# unevaluated forms, so demanding one would manufacture a schema false by
# construction. The mutation proves the exclusion is LIVE rather than accidental.
D="$(fixture pres_macro)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
(defmacro mac "Doc." [x] `(inc ~x))
CLJ
expect "$D" 0 'function(s) judged across' 'a bare defmacro owes no m/=>' \
	--check spec-presence src
if mutate "$D" presence.clj '#{"defn" "defn-"})' '#{"defn" "defn-" "defmacro"})'; then
	expect "$D" 1 'mac' \
		'MUTANT: defmacro added to the population -> it goes red (exclusion is live)' \
		--check spec-presence src
fi

banner 'SPEC-PRESENCE — NON-VACUITY: an enrolled namespace discovery cannot see'
# The sharpest failure this check has: a renamed or moved namespace leaves the
# enrolment naming nothing, and the check then scores a perfect run over an empty
# population. Floored PER NAMESPACE, because any populated sibling satisfies a
# union floor while this one sits dark.
D="$(fixture pres_absent)"
cat > "$D/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 9999 :nesting-block 9999 :decisions-block 9999
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}
 :spec-presence {:enrolled ["fixture.s" "fixture.renamed-away"]}}
EDN
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
CLJ
expect "$D" 3 'fixture.renamed-away' \
	'an enrolled namespace matching no file is CANNOT RUN, and is NAMED' \
	--check spec-presence src

D2="$(fixture pres_absent_mut)"
cp "$D/tools/lint/gates.edn" "$D2/tools/lint/gates.edn"
cp "$D/src/fixture/s.clj" "$D2/src/fixture/s.clj"
if mutate "$D2" presence.clj '(let [absent (remove #(contains? by-ns %) enrolled)]' '(let [absent []]'; then
	expect "$D2" 0 'function(s) judged across' \
		'MUTANT: per-namespace floor removed -> the vacuous GREEN it exists to refuse' \
		--check spec-presence src
fi

banner 'SPEC-PRESENCE — NON-VACUITY: an enrolled namespace with ZERO functions'
# A SEPARATE failure from the one above, with a different cause and a different
# fix: the file is there and the population collapsed. Collapsing the two would
# report whichever was met first and hide the other — and the two clauses are
# only separable because the second is restricted to namespaces that were FOUND.
# THIS SUITE PROVED THAT: before that restriction, the `no functions` clause also
# refused an ABSENT namespace, so the `not found` mutation above could not go
# green and its clause was unattributable.
D="$(fixture pres_nofns)"
printf '(ns fixture.s)\n(def only-a-def 1)\n' > "$D/src/fixture/s.clj"
expect "$D" 3 'no functions' 'an enrolled namespace defining nothing is CANNOT RUN' \
	--check spec-presence src

D2="$(fixture pres_nofns_mut)"
printf '(ns fixture.s)\n(def only-a-def 1)\n' > "$D2/src/fixture/s.clj"
if mutate "$D2" presence.clj '(empty? (mapcat :defns (get by-ns %)))' 'false'; then
	expect "$D2" 0 'function(s) judged across' \
		'MUTANT: zero-function floor removed -> the vacuous GREEN it exists to refuse' \
		--check spec-presence src
fi

banner 'SPEC-PRESENCE — an EMPTY :enrolled is CANNOT RUN'
D="$(fixture pres_noenrol)"
cat > "$D/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 9999 :nesting-block 9999 :decisions-block 9999
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}
 :spec-presence {:enrolled []}}
EDN
printf '(ns fixture.s)\n(defn f "Doc." [x] x)\n' > "$D/src/fixture/s.clj"
expect "$D" 3 'no enrolled namespaces' \
	'an empty declared scope refuses rather than judging nothing' --check spec-presence src

banner 'SPEC-PRESENCE — an UNREADABLE file is a FINDING, never a skip'
# It cannot be attributed to a namespace precisely BECAUSE it will not read, so
# it can neither be ruled into the enrolled scope nor out of it. Reporting it is
# the only honest answer; skipping it reports clean over the one broken file.
D="$(fixture pres_unreadable)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
CLJ
printf '(ns fixture.broken)\n(defn oops [\n' > "$D/src/fixture/broken.clj"
expect "$D" 1 'UNREADABLE' 'a file the reader cannot handle is reported, not passed over' \
	--check spec-presence src

banner 'SPEC-PRESENCE — an auto-resolved ::alias/key does NOT read as broken'
# MEASURED ON THE LIVE TREE: tools/devcards/dev/deadzone_census.clj carries
# `::deadzone/reach`, which clojure.core/read cannot resolve from outside the
# file. Without the shared normalize-source step this check would report a real
# source file as UNREADABLE for a reason that has nothing to do with specs — a
# false red, and the kind that gets a gate disabled.
D="$(fixture pres_autoresolve)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
CLJ
cat > "$D/src/fixture/t.clj" <<'CLJ'
(ns fixture.t (:require [fixture.s :as-alias fs]))
(def k ::fs/reach)
CLJ
expect "$D" 0 'function(s) judged across' 'an ::alias/key file reads cleanly' \
	--check spec-presence src

banner 'SPEC-PRESENCE — the exemption contract is WIRED to this check too'
# A check that forgot to call validate-exemptions! would silently accept a waiver
# with no proof at all, so both directions are pinned in THIS lane rather than
# inferred from spec-shape's.
D="$(fixture pres_waiver)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn specced "Doc." [x] x)
(m/=> specced [:=> [:cat :int] :int])
(defn unspecced "Doc." [x] x)
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape []
 :spec-presence [{:file "src/fixture/s.clj" :name "unspecced"
                  :rationale "A macro-generated arity the reader cannot see."
                  :retires-when "the generator emits the spec alongside the defn"
                  :owner "gate-port"
                  :expires "$SOON"}]}
EDN
expect "$D" 0 '1 exemption(s) live' 'a four-field waiver suppresses its finding' \
	--check spec-presence src

D="$(fixture pres_waiver_bad)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn specced "Doc." [x] x)
(m/=> specced [:=> [:cat :int] :int])
(defn unspecced "Doc." [x] x)
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape []
 :spec-presence [{:file "src/fixture/s.clj" :name "unspecced"
                  :rationale "r" :retires-when "w" :expires "$SOON"}]}
EDN
expect "$D" 3 'owner' 'a waiver missing :owner is refused in THIS lane' \
	--check spec-presence src

banner 'SPEC-PRESENCE — the SHIPPED enrolment is a ratchet that only GROWS'
# THE ONE ABUSE PATH A SYNTHETIC FIXTURE CANNOT CLOSE. Every case above proves
# the check refuses what it should; none of them can stop an author meeting a red
# by DELETING the namespace from `:enrolled`, which is a coverage regression
# wearing a config edit. So this reads the SHIPPED config and floors its size,
# exactly as `lvgl-codegen.spec-coverage` pins its own list.
#
# READ-ONLY AND CONFIG-ONLY, which is what keeps it inside the
# prefer-a-synthetic-fixture preference rather than against it: it perturbs
# nothing, so it runs on a dirty checkout, and its expectation drifts only when
# somebody edits the enrolment — which is the event it exists to catch. Raising
# the floor with the enrolment is part of enrolling; lowering it is the bypass.
PRESENCE_FLOOR=31
SHIPPED_GATES="$SCRIPT_DIR/../gates.edn"
if [ ! -f "$SHIPPED_GATES" ]; then
	bad "enrolment ratchet: no shipped gates.edn at $SHIPPED_GATES"
else
	enrolled_n="$(cd "$SCRIPT_DIR" && clojure -J-XX:TieredStopAtLevel=1 -Sdeps '{}' -M -e \
		'(require (quote clojure.edn)) (count (get-in (clojure.edn/read-string (slurp "../gates.edn")) [:spec-presence :enrolled]))' \
		2> /dev/null | tail -1)"
	case "$enrolled_n" in
	'' | *[!0-9]*)
		bad "enrolment ratchet: could not read :spec-presence :enrolled (got '$enrolled_n')"
		;;
	*)
		if [ "$enrolled_n" -ge "$PRESENCE_FLOOR" ]; then
			ok "the shipped enrolment holds $enrolled_n namespace(s), floor $PRESENCE_FLOOR"
		else
			bad "enrolment ratchet: $enrolled_n enrolled, below the floor of $PRESENCE_FLOOR — a namespace was DE-ENROLLED. Write the specs, do not shrink the scope."
		fi
		;;
	esac
fi

banner 'SPEC-PRESENCE — a STALE waiver is itself a finding'
D="$(fixture pres_waiver_stale)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn specced "Doc." [x] x)
(m/=> specced [:=> [:cat :int] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape []
 :spec-presence [{:file "src/fixture/s.clj" :name "unspecced"
                  :rationale "r" :retires-when "w" :owner "o" :expires "$SOON"}]}
EDN
expect "$D" 3 'matched NO live finding' \
	'a waiver whose finding is fixed must be DELETED' --check spec-presence src

# ===========================================================================
banner 'EXEMPTIONS — a well-formed waiver suppresses its own finding'
D="$(fixture ex_ok)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f"
               :rationale "A tree walker over arbitrary EDN; any narrower schema would be false."
               :retires-when "the walker is split into per-node-type arms"
               :owner "gate-port"
               :expires "$SOON"}]}
EDN
expect "$D" 0 '1 exemption(s) live' 'a four-field waiver suppresses its finding and is reported' \
	--check spec-shape src

banner 'EXEMPTIONS — each mandatory field is mandatory'
for field in rationale retires-when owner expires; do
	D="$(fixture "ex_missing_$field")"
	cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
	{
		printf '{:docstrings []\n :spec-shape [{:file "src/fixture/s.clj" :name "f"\n'
		[ "$field" = rationale ] || printf '               :rationale "r"\n'
		[ "$field" = retires-when ] || printf '               :retires-when "w"\n'
		[ "$field" = owner ] || printf '               :owner "o"\n'
		[ "$field" = expires ] || printf '               :expires "%s"\n' "$SOON"
		printf '               }]}\n'
	} > "$D/tools/lint/exemptions.edn"
	expect "$D" 3 "$field" "a waiver missing :$field is refused" --check spec-shape src
done

banner 'EXEMPTIONS — the key set is CLOSED (a typo cannot widen an entry)'
D="$(fixture ex_typo)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f" :rationale "r"
               :retires-when "w" :owner "o" :expires "$SOON"
               :rationalle "typo that must not be ignored"}]}
EDN
expect "$D" 3 'unknown key' 'an unknown key is refused rather than silently ignored' \
	--check spec-shape src

banner 'EXEMPTIONS — an EXPIRED waiver is refused'
D="$(fixture ex_expired)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f" :rationale "r"
               :retires-when "w" :owner "o" :expires "$PAST"}]}
EDN
expect "$D" 3 'EXPIRED' 'a waiver past its :expires is refused' --check spec-shape src

banner 'EXEMPTIONS — an over-horizon :expires is a SEPARATE failure'
# Separate from expiry so neither masks the other: an entry can be both, and
# collapsing them would report one and hide the other.
D="$(fixture ex_horizon)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f" :rationale "r"
               :retires-when "w" :owner "o" :expires "$FAR"}]}
EDN
expect "$D" 3 'horizon' 'a waiver beyond the 90-day horizon is refused' --check spec-shape src

banner 'EXEMPTIONS — a STALE waiver is itself a finding (the down-only ratchet)'
D="$(fixture ex_stale)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat [:map [:k :int]]] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f" :rationale "r"
               :retires-when "w" :owner "o" :expires "$SOON"}]}
EDN
expect "$D" 3 'matched NO live finding' 'a waiver whose finding is fixed must be DELETED' \
	--check spec-shape src

D2="$(fixture ex_stale_mut)"
cp "$D/src/fixture/s.clj" "$D2/src/fixture/s.clj"
cp "$D/tools/lint/exemptions.edn" "$D2/tools/lint/exemptions.edn"
if mutate "$D2" util.clj '(let [stale (remove used entries)]' '(let [stale []]'; then
	expect "$D2" 0 'm/=> form(s)' \
		'MUTANT: staleness clause silenced -> clean (the red was that clause alone)' \
		--check spec-shape src
fi

banner 'EXEMPTIONS — matching needs :file AND :name together'
# Either alone would turn one entry into a whole-file skip, or into a licence for a
# same-named var anywhere in the tree.
D="$(fixture ex_scope)"
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(defn g "Doc." [x] x)
(m/=> f [:=> [:cat :any] :int])
(m/=> g [:=> [:cat :any] :int])
CLJ
cat > "$D/tools/lint/exemptions.edn" <<EDN
{:docstrings []
 :spec-shape [{:file "src/fixture/s.clj" :name "f" :rationale "r"
               :retires-when "w" :owner "o" :expires "$SOON"}]}
EDN
out="$(run_gate "$D" --check spec-shape src)" && code=0 || code=$?
if [ "$code" = 1 ] && printf '%s' "$out" | grep -q ' g ' && ! printf '%s' "$out" | grep -qE '^\s+\S+  +f '; then
	ok 'the waiver excuses f alone; g is still a finding'
else
	bad "per-name scoping: expected g reported and f excused, got exit $code"
	printf '%s\n' "$out" | sed 's/^/       | /' >&2
fi

# ===========================================================================
# FN-SIZE. Three axes in one check, so ATTRIBUTION is the whole difficulty: any
# one of them will refuse a fixture that breaches all three, and a red therefore
# says nothing about which axis is alive. Every case below breaches ONE axis while
# leaving the other two far under their ceilings, and each mutation disables that
# axis ALONE and requires a neighbour to keep refusing.
banner 'SPEC-SHAPE — a DARK enrolled root is named, not masked by a populated one'
# The floor over specs is a UNION, so one populated root satisfies it while a
# sibling contributes nothing and says nothing. Measured on the live tree: of three
# enrolled roots, ONE carries all 325 specs and TWO carry zero. This case pins that
# the dark root is REPORTED — reported and not refused, because a root with no
# `m/=>` is not a tree defect, and failing on it would invite de-enrolling the root,
# which is the coverage loss the reporting exists to avoid.
D="$(fixture spec_dark_root)"
mkdir -p "$D/other/fixture"
cat > "$D/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 9999 :nesting-block 9999 :decisions-block 9999
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src" "other"]}}
EDN
cat > "$D/src/fixture/s.clj" <<'CLJ'
(ns fixture.s (:require [malli.core :as m]))
(defn f "Doc." [x] x)
(m/=> f [:=> [:cat :int] :int])
CLJ
# a real Clojure file with NO arrow spec at all — the dark-root shape
cat > "$D/other/fixture/t.clj" <<'CLJ'
(ns fixture.t)
(defn g "Doc." [x] x)
CLJ
expect "$D" 0 'UNMEASURED: 1 enrolled root' \
	'a populated root passes AND the dark sibling is named' --check spec-shape src
expect "$D" 0 'other=0' 'the per-root count names the dark root by path' --check spec-shape src
# ATTRIBUTION: silence the dark-root report alone. The gate must still pass and
# still judge the populated root — proving the report is what went missing, not the
# check. This is the mutation that distinguishes "reported" from "refused".
if mutate "$D" specs.clj '(when (seq dark)' '(when false'; then
	expect "$D" 0 'per root:' \
		'MUTANT: dark-root report silenced -> still green, so the report was the only loss' \
		--check spec-shape src
	out="$(run_gate "$D" --check spec-shape src 2>&1)" || true
	if printf '%s' "$out" | grep -qF 'UNMEASURED'; then
		bad 'MUTANT still printed UNMEASURED — the mutation did not reach the report'
	else
		ok 'MUTANT: UNMEASURED is gone (the clause under test is the reporting one)'
	fi
fi

banner 'FN-SIZE — a small corpus passes'
F="$(fixture fn_clean)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 10 :nesting-block 3 :decisions-block 4
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn tiny "Small." [x] (inc x))
CLJ
expect "$F" 0 'under the ceiling' 'a small corpus is clean' --check fn-size src

banner 'FN-SIZE — LOC over ceiling is a FINDING, and names the loc axis'
F="$(fixture fn_loc)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 5 :nesting-block 99 :decisions-block 99
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
# 6 code lines (rows 2..8, less a 1-line docstring), nesting 0, decisions 0 —
# so ONLY the loc axis can possibly refuse this fixture.
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn long-flat "Long but flat." [x]
  (let [a (+ x 1)
        b (+ a 2)
        c (+ b 3)
        d (+ c 4)
        e (+ d 5)]
    (+ a b c d e)))
CLJ
expect "$F" 1 '[loc over]' 'a too-long function is a FINDING naming loc' --check fn-size src

# ATTRIBUTION: kill the loc axis alone by pointing it at a config key that does
# not exist, so its bound is nil and the axis is skipped. nesting and decisions
# are untouched — and the CONTROL below proves they were still able to refuse.
F2="$(fixture fn_loc_mut)"
cp "$F/tools/lint/gates.edn" "$F2/tools/lint/gates.edn"
cp "$F/src/fixture/a.clj" "$F2/src/fixture/a.clj"
if mutate "$F2" fnsize.clj '[[:loc :loc-block :loc-warn "loc"]' '[[:loc :loc-absent :loc-warn "loc"]'; then
	expect "$F2" 0 'under the ceiling' \
		'MUTANT: loc axis disabled -> clean (the red WAS the loc axis)' \
		--check fn-size src
fi
# CONTROL on that same mutant: with loc dead, a nesting breach must still refuse,
# proving the mutation disabled one axis rather than the whole check.
printf '%s\n' '(defn deep "d" [x] (if x (if x (if x (if x 1 2) 2) 2) 2))' >> "$F2/src/fixture/a.clj"
sed -i 's/:nesting-block 99/:nesting-block 2/' "$F2/tools/lint/gates.edn"
expect "$F2" 1 '[nesting over]' \
	'CONTROL: on the loc-dead mutant, nesting still refuses' --check fn-size src

banner 'FN-SIZE — NESTING over ceiling is a FINDING, and names the nesting axis'
F="$(fixture fn_nest)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 99 :nesting-block 2 :decisions-block 99
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn nested "Nesting 3." [x] (if x (if x (if x 1 2) 2) 2))
CLJ
expect "$F" 1 '[nesting over]' 'a too-deep function is a FINDING naming nesting' --check fn-size src

# ATTRIBUTION: `let` must NOT count as nesting — that is the documented divergence
# from the donor's head set. ADD let to the head set and a let-chain that is
# currently clean must go red; that proves the exclusion is live rather than
# accidental.
F2="$(fixture fn_nest_let)"
cp "$F/tools/lint/gates.edn" "$F2/tools/lint/gates.edn"
cat > "$F2/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn lets "Three lets, no branch." [x]
  (let [a x] (let [b a] (let [c b] c))))
CLJ
expect "$F2" 0 'under the ceiling' 'a let-chain is NOT nesting (donor divergence)' --check fn-size src
if mutate "$F2" fnsize.clj '"dotimes" "while" "fn" "fn*"}' '"dotimes" "while" "fn" "fn*" "let"}'; then
	expect "$F2" 1 '[nesting over]' \
		'MUTANT: let added to nesting-heads -> the let-chain goes red (exclusion is live)' \
		--check fn-size src
fi

banner 'FN-SIZE — DECISIONS counts ARMS, not heads'
F="$(fixture fn_dec)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 99 :nesting-block 99 :decisions-block 3
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
# ONE `case` head with 5 arms. A head-counting metric scores this 1 and passes;
# an arm-counting metric scores 5 and refuses. That difference IS the metric.
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn dispatch "Five arms, one head." [x]
  (case x :a 1 :b 2 :c 3 :d 4 5))
CLJ
expect "$F" 1 '[decisions over]' 'a 5-arm case is a FINDING (arms, not heads)' --check fn-size src

# ATTRIBUTION: make `case` score like a HEAD (1) instead of its arms. The fixture
# then passes — which is precisely the blindness the arm count exists to remove.
F2="$(fixture fn_dec_mut)"
cp "$F/tools/lint/gates.edn" "$F2/tools/lint/gates.edn"
cp "$F/src/fixture/a.clj" "$F2/src/fixture/a.clj"
if mutate "$F2" fnsize.clj '"case" (leading-plus-pairs 1 args)' '"case" 1'; then
	expect "$F2" 0 'under the ceiling' \
		'MUTANT: case scored as a HEAD -> clean (arm counting WAS the red)' \
		--check fn-size src
fi

banner 'FN-SIZE — condp takes TWO leading args, case takes ONE'
# THIS CASE DISCRIMINATES, which a bare "a big condp is refused" case does not.
# The shipped bug stripped ONE leading arg from a `condp`, leaving its expression
# in the pair stream, so a NO-DEFAULT condp came out exactly one too high. The
# ceiling here is set to the CORRECT count, so the fixture is clean now and would
# have FAILED under the old arithmetic. A canary over the DEFAULTED form would have
# gone green either way — that form was coincidentally right — which is why the
# no-default shape is the one pinned.
F="$(fixture fn_condp)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 99 :nesting-block 99 :decisions-block 4
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
# 3 pairs, NO default. Correct count 3 (+1 for the condp head's own iteration? no —
# condp is not a nesting/iteration head). Ceiling 4, so clean; the old arithmetic
# computed 4 for this and the strictly-greater-than test then also passed, so the
# ceiling is deliberately set to 3 in the mutant check below instead.
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn dispatch "Three arms, no default." [x]
  (condp = x :a 1 :b 2 :c 3))
CLJ
expect "$F" 0 'under the ceiling' 'a 3-arm no-default condp counts 3, not 4' --check fn-size src

# The discriminator: ceiling EXACTLY 3. Correct arithmetic -> clean. The old
# one-leading-arg arithmetic -> 4 -> FAIL. So this fixture's verdict is the
# difference between the two implementations, not merely a red.
sed -i 's/:decisions-block 4/:decisions-block 3/' "$F/tools/lint/gates.edn"
expect "$F" 0 'under the ceiling' \
	'AT the corrected count it is clean (the old arithmetic would have failed here)' \
	--check fn-size src
if mutate "$F" fnsize.clj '"condp" (leading-plus-pairs 2 args)' '"condp" (leading-plus-pairs 1 args)'; then
	expect "$F" 1 '[decisions over]' \
		'MUTANT: condp stripping ONE leading arg -> overcounts and goes red' \
		--check fn-size src
fi

banner 'FN-SIZE — and case still takes exactly one, on that same mutant'
# CONTROL for the case above: the condp mutation must not change `case`. A shared
# clause was the original defect, so the canary has to prove they are now separate.
F2="$(fixture fn_case_control)"
cat > "$F2/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 99 :nesting-block 99 :decisions-block 3
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
cat > "$F2/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn dispatch "Three arms, no default." [x]
  (case x :a 1 :b 2 :c 3))
CLJ
expect "$F2" 0 'under the ceiling' 'a 3-arm no-default case counts 3' --check fn-size src
if mutate "$F2" fnsize.clj '"condp" (leading-plus-pairs 2 args)' '"condp" (leading-plus-pairs 1 args)'; then
	expect "$F2" 0 'under the ceiling' \
		'CONTROL: breaking condp leaves case UNCHANGED (the clauses are separate)' \
		--check fn-size src
fi

banner 'FN-SIZE — the SEEDED-RATCHET property: BLOCK is strictly-greater-than'
# A ceiling seeded at the measured maximum must leave that function AT the ceiling
# rather than over it. This is the property that makes a seeded ratchet green on
# arrival with ZERO exemptions, so it owes a canary of its own.
F="$(fixture fn_at_ceiling)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 99 :nesting-block 3 :decisions-block 99
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn at-ceiling "Nesting exactly 3." [x] (if x (if x (if x 1 2) 2) 2))
CLJ
expect "$F" 0 'under the ceiling' 'a function AT the ceiling is clean' --check fn-size src
if mutate "$F" fnsize.clj '(if (= tier-key :block) > >=)' '(if (= tier-key :block) >= >=)'; then
	expect "$F" 1 '[nesting over]' \
		'MUTANT: BLOCK relaxed to >= -> the at-ceiling function goes red' \
		--check fn-size src
fi

banner 'FN-SIZE — the DOCSTRING SUBTRACTION, which keeps two gates out of conflict'
# lint-docstrings REQUIRES prose; if this metric charged for it, satisfying one
# gate could break the other. It did once, at namespace grain. Prove the
# subtraction is live at function grain too.
F="$(fixture fn_docstring)"
cat > "$F/tools/lint/gates.edn" <<'EDN'
{:ns-size {:loc-block 9999 :publics-block 9999 :loc-warn 9998 :publics-warn 9998}
 :fn-size {:loc-block 4 :nesting-block 99 :decisions-block 99
           :loc-warn 9998 :nesting-warn 9998 :decisions-warn 9998}
 :docstrings {:enrolled ["src"]}
 :spec-shape {:enrolled ["src"]}}
EDN
cat > "$F/src/fixture/a.clj" <<'CLJ'
(ns fixture.a)
(defn prosey
  "Line one.
  Line two.
  Line three.
  Line four.
  Line five."
  [x]
  (inc x))
CLJ
expect "$F" 0 'under the ceiling' 'a long docstring is FREE' --check fn-size src
if mutate "$F" fnsize.clj '(if doc (count (str/split-lines doc)) 0)' '0'; then
	expect "$F" 1 '[loc over]' \
		'MUTANT: docstring no longer subtracted -> prose is charged and it goes red' \
		--check fn-size src
fi

banner 'FN-SIZE — vacuity: a DARK ROOT is exit 3, not a pass'
F="$(fixture fn_dark)"
expect "$F" 3 'dark root' 'a root matching no Clojure file CANNOT RUN (exit 3)' \
	--check fn-size src/nonexistent

banner 'FN-SIZE — vacuity: files but ZERO functions is exit 3'
F="$(fixture fn_nofns)"
printf '(ns fixture.a)\n(def x 1)\n' > "$F/src/fixture/a.clj"
expect "$F" 3 'no functions found' 'a corpus with no defn CANNOT RUN (exit 3)' \
	--check fn-size src

banner 'FN-SIZE — an UNPARSEABLE file is a FINDING, never a skip'
# TWO FILES ON PURPOSE. A corpus whose ONLY file is unparseable yields zero
# functions, so the vacuity floor fires first and the run is exit 3 — which is a
# different clause, and a fixture built that way would prove nothing about
# totality. The property under test is that a bad file among GOOD ones is
# reported rather than silently dropped, so the fixture needs a good one.
F="$(fixture fn_unparseable)"
printf '(ns fixture.a)\n(defn ok "Fine." [x] x)\n' > "$F/src/fixture/a.clj"
printf '(ns fixture.b)\n(defn broken [x] (((\n' > "$F/src/fixture/b.clj"
expect "$F" 1 'UNREADABLE' 'a bad file among good ones is reported, not passed over' \
	--check fn-size src

banner 'FN-SIZE — an ALL-UNPARSEABLE corpus is exit 3, and that is deliberate'
# Recorded as a decision rather than left ambiguous: when nothing parses there are
# no functions, and the vacuity floor is the correct clause — the gate genuinely
# could not run. It is exit 3 and not 1 so a canary can tell "the tree is bad"
# from "I could not look at the tree".
F="$(fixture fn_all_bad)"
printf '(ns fixture.a)\n(defn broken [x] (((\n' > "$F/src/fixture/a.clj"
expect "$F" 3 'no functions found' 'an all-unparseable corpus CANNOT RUN (exit 3)' \
	--check fn-size src

# ---------------------------------------------------------------------------
printf '\n== summary\n'
printf '  passed: %d\n' "$PASS"
printf '  failed: %d\n' "$FAILED"
if [ "$FAILED" -gt 0 ]; then
	printf '\033[31m[lint-gate-checks-test] RED\033[0m\n' >&2
	exit 1
fi
printf '\033[32m[lint-gate-checks-test]\033[0m ALL GREEN (%d cases)\n' "$PASS"
