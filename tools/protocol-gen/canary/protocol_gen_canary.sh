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
#   * A WRONG ACCESS DIRECTION IN THE EMITTED RUST. Two mutations, because the
#     two halves fail in different places. Flipping read to write is caught on
#     the two-group policy; FOLDING the read-and-write grant onto one direction
#     is INVISIBLE there — that policy grants no message both ways — and is
#     caught only on the directions policy, whose clean run on that same mutant
#     is asserted so the fixture is shown to be load-bearing rather than
#     decorative. A wrong direction still COMPILES, which is asserted too: that
#     is exactly why an oracle is needed and a rustc run is not enough.
#   * AN EMITTED RUST MODULE THAT IS NOT WARNING-FREE. Dropping the attribute
#     the module carries for its verbatim variant names leaves valid Rust and
#     reintroduces a lint, which -D warnings turns into an error — so the two
#     halves of "valid, warning-free Rust" are separated rather than conflated.
#   * A WITHHELD MESSAGE THAT CAN BE NAMED ANYWAY. The module's whole claim is
#     that a message the policy withheld has no variant, so naming one cannot
#     compile — and nothing proves a compile error except a compile REQUIRED to
#     fail, asserted on the diagnostic's own error code and the missing name
#     rather than on a bare non-zero exit. Two mutations, because the claim has
#     two halves that fail in different places: LEAKING an ungranted message
#     makes the refusal disappear, and DROPPING a granted one makes the
#     neighbouring control stop compiling. So a case satisfied by any broken
#     compile, and a control that would have compiled against anything, are
#     both excluded.
#   * A NESTED PERMISSION TREE THAT IS NOT TOTAL. The mutation drops the DENIED
#     nodes, leaving a tree that lists only the grants — smaller, still valid
#     Rust, and consistent with the group's `.proto`, which never named those
#     fields either. Nothing else in the run moves, so the ORACLE catches it by
#     re-deriving each node's DIRECT CHILD COUNT from the source message.
#   * A TREE THAT DESCRIBES THE INTERIOR OF A DENIAL. The mutation drops the
#     `granted?` conjunct so expansion descends beneath a denied field, and the
#     generator's own invariant refuses. NO POLICY CAN REACH THAT REFUSAL — a
#     denied node is terminal by construction — so a mutation is the only thing
#     that can show it able to fire, which is the standing
#     `protocol-gen.numbering/assert-stamped!` already has.
#   * A CYCLIC GRANT, and a COLLIDING STATIC NAME. Both ARE policy-reachable, so
#     both are driven with a fixture and each has its clause broken alone. The
#     cycle mutant dies of a StackOverflowError rather than returning a verdict,
#     which is precisely what the clause converts into a named refusal.
#   * A TREE THAT DOES NOT FOLLOW THE POLICY. A tool mutation cannot ask that
#     question, so the FIXTURE policy is mutated instead: one field is withheld,
#     the emitted bytes must move, the tree must still DESCRIBE that field as
#     denied, and the oracle must call the result clean against the mutated
#     policy and RED against the real one.
#   * A STATE SUBSYSTEM TABLE THAT IS NOT TOTAL, and one that does not follow
#     its policy. The same pair one axis over: the first mutation emits only the
#     PERMITTED rows, which is a smaller well-formed table that no other
#     artefact contradicts; the second withholds a subsystem in the FIXTURE
#     policy and requires the bytes to move, the row to survive reading `false`,
#     and the oracle to split clean-against-the-mutant from red-against-the-real
#     one.
#   * A PROJECTION FINGERPRINT THAT STOPS BEING ONE. Three mutations, because
#     the constant carries three properties that fail in three places and NO TWO
#     OF THEM CATCH EACH OTHER — which is the whole reason they are separate
#     cases rather than one. Folding an environmental term in breaks
#     REPRODUCIBILITY while leaving two groups perfectly distinct; collapsing
#     the value to a constant breaks DISTINCTNESS while staying perfectly
#     reproducible; and each mutant is asserted CLEAN on the other's case, so
#     neither case can be read as covering the other. The third mutates the
#     FIXTURE policy instead of the tool — withholding one FIELD, which leaves
#     every granted message id, name and direction untouched — because whether
#     the value follows the POLICY is a question no tool mutation can ask, and
#     the untouched group's fingerprint must stay put or the value is following
#     the run rather than the group.
#
# THE RUST SIDE IS JUDGED THROUGH rustc, NOT THROUGH A REGEX. Each emitted
# access module is compiled twice — alone as a library under -D warnings, then
# with a harness this script writes that walks its `MESSAGES` and prints what
# `may_read()` and `may_write()` answer. The oracle judges that dump against
# the POLICY. So what is compared is what a consumer's own call would return,
# and a module rustc cannot compile is a FAULT rather than a comparison that
# silently found nothing.
#
# A THIRD rustc SHAPE JOINS THOSE TWO, and it is the one whose claim is a
# REFUSAL rather than a value: a harness that NAMES a `Message` variant and is
# required to be REJECTED, by rustc's own error code and by the missing name.
# It compiles to metadata and is never run, because the question is whether the
# name resolves at all.
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
for tool in clojure protoc rustc; do
	command -v "$tool" > /dev/null 2>&1 || {
		printf '\033[31mFAIL\033[0m — %s is not on PATH.\n' "$tool" >&2
		printf '  This suite compiles emitted protos, compiles and RUNS the emitted\n' >&2
		printf '  Rust access modules, and drives the generator — so it cannot report\n' >&2
		printf '  anything without all three. Install it, or run inside the toolchain\n' >&2
		printf '  container, whose Rust pin is in Dockerfile.base.\n' >&2
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

# schema_version <module-file> — the u32 that module's SCHEMA_VERSION declares,
# or the EMPTY STRING when it declares none.
#
# THE EMPTY CASE IS WHY EVERY CALLER GUARDS ON `-n` FIRST. Two modules carrying
# no constant at all yield two equal empty strings, so a distinctness case
# written without that guard reports a PASS for an emitter that stopped
# emitting — the nothing-ran value equalling the pass value, one line lower
# than usual.
schema_version() {
	sed -n 's/^pub const SCHEMA_VERSION: u32 = \([0-9][0-9]*\);$/\1/p' "$1"
}

# rust_access_dumps <out-dir> <work-dir> <group>… — what the emitted Rust access
# modules ANSWER, as a tab-separated dump the oracle judges.
#
# TWO rustc INVOCATIONS PER GROUP, PROVING TWO DIFFERENT THINGS. The first
# compiles the module ALONE as a library under -D warnings: that is the whole
# of the claim "the emitted text is valid Rust", and it is taken with nothing
# else in the crate that could carry the error. The second builds a HARNESS
# that walks `MESSAGES` and prints what the module's own public API answers —
# so what the oracle judges has been through the Rust compiler and through the
# calls a consumer would make, never through a regex over the text.
#
# THE HARNESS IS WRITTEN HERE, NOT BY THE GENERATOR, and it names no expected
# value: it prints `GROUP`, `source_id()`, `may_read()` and `may_write()` and
# stops. It reads the two PREDICATES rather than the `Access` variant, because
# the predicates are the consumer-facing surface — a mutation that flips,
# drops or fabricates a direction has to move one of them to matter.
#
# IT ALWAYS RETURNS 0 AND REPORTS THROUGH `RSA_RC`. This suite runs under
# `set -e`, so a helper returning non-zero outside a condition ABORTS the run —
# and an aborted run prints no FAIL line at all, which is the one outcome a
# canary must never produce. Measured: a bare `return` here ended the suite mid
# section with rc 1 and nothing named.
RSA_RC=0
RSA_OUT=""
RSA_DUMP=""
rust_access_dumps() {
	local out="$1" work="$2"
	shift 2
	mkdir -p "$work"
	RSA_DUMP="$work/access.tsv"
	: > "$RSA_DUMP"
	local group
	for group in "$@"; do
		set +e
		RSA_OUT="$(rustc --edition 2021 --crate-type lib --emit=metadata -D warnings \
			-o "$work/$group.rmeta" "$out/$group.rs" 2>&1)"
		RSA_RC=$?
		set -e
		[ "$RSA_RC" -eq 0 ] || return 0
		# `<<-` strips leading TABS only, so the tabs indent the heredoc and the
		# SPACES survive into the Rust source.
		cat > "$work/$group-harness.rs" <<-RSEOF
			#[allow(dead_code)]
			#[path = "$out/$group.rs"]
			mod m;

			fn main() {
			    for msg in m::MESSAGES.iter().copied() {
			        println!(
			            "{}\t{}\t{}\t{}",
			            m::GROUP,
			            msg.source_id(),
			            msg.access().may_read(),
			            msg.access().may_write()
			        );
			    }
			}
		RSEOF
		set +e
		RSA_OUT="$(rustc --edition 2021 -o "$work/$group-harness" \
			"$work/$group-harness.rs" 2>&1)"
		RSA_RC=$?
		set -e
		[ "$RSA_RC" -eq 0 ] || return 0
		set +e
		RSA_OUT="$("$work/$group-harness" 2>&1 >> "$RSA_DUMP")"
		RSA_RC=$?
		set -e
		[ "$RSA_RC" -eq 0 ] || return 0
	done
}

# verify_access <dump> <policy> — judge a dump against the policy that granted
# it, with the REAL tree's oracle. Bare, so its own status is read.
VA_RC=0
VA_OUT=""
verify_access() {
	set +e
	VA_OUT="$(cd "$PG" && clojure -M:verify rust-access --dump "$1" --policy "$2" 2>&1)"
	VA_RC=$?
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

# permission_tree_prelude <work-dir> <out-dir> — the harness half of the nested
# permission mirror: the two type declarations the emitted fragment assumes are
# in scope, then an `include!` of it.
#
# THE HARNESS DECLARES THE TYPES AND THE GENERATOR DOES NOT. That is the whole
# contract the emitted file has — it names no crate and assumes exactly
# `Permission` and `PermissionNode` — so the file is only judgeable against a
# module that supplies them, and this is that module. What the compile proves is
# therefore that the fragment is valid, const-evaluable Rust in the POSITION a
# consumer includes it, which is the strongest statement available here: this
# repository cannot see any consumer's real declaration.
#
# TWO CONSTRUCTORS AND NO `new`, matching the fragment: `message` takes children
# and `leaf` takes none. The ARITY is what makes the compile say something about
# the kind marker at all — a leaf built here cannot be handed a child, so a
# generator that emitted `leaf(tag, name, permission, &[])` would not compile,
# and one that emitted `message(…)` for a scalar would still compile and is
# caught by the emitted-text case in the section that drives this.
#
# `Unspecified` IS DECLARED AND NEVER CONSTRUCTED, deliberately. It is the
# enum's zero value, which a consumer needs so a default-constructed node is not
# a grant; the generator emits no node carrying it, and `label` below matches it
# so the variant name is still pinned by a compile.
permission_tree_prelude() {
	local work="$1" out="$2"
	mkdir -p "$work"
	# `<<-` strips leading TABS only, so the tabs indent the heredoc and the
	# SPACES survive into the Rust source.
	cat > "$work/prelude.rs" <<-PTPEOF
		#[allow(dead_code)]
		#[derive(Debug, Clone, Copy, PartialEq, Eq)]
		pub enum Permission {
		    Unspecified,
		    Inherit,
		    Allow,
		    Deny,
		}

		pub struct PermissionNode {
		    pub tag: u32,
		    pub name: &'static str,
		    pub permission: Permission,
		    pub children: &'static [PermissionNode],
		}

		impl PermissionNode {
		    #[must_use]
		    pub const fn message(
		        tag: u32,
		        name: &'static str,
		        permission: Permission,
		        children: &'static [PermissionNode],
		    ) -> Self {
		        Self { tag, name, permission, children }
		    }

		    #[must_use]
		    pub const fn leaf(tag: u32, name: &'static str, permission: Permission) -> Self {
		        Self { tag, name, permission, children: &[] }
		    }
		}

		include!("$out/permission_tree.rs");
	PTPEOF
}

# permission_tree_dump <out-dir> <work-dir> — what the emitted permission tree
# HOLDS, as a tab-separated dump the oracle judges.
#
# TWO rustc INVOCATIONS, PROVING TWO DIFFERENT THINGS, the same split
# `rust_access_dumps` makes. The first compiles the prelude-plus-fragment ALONE
# as a library under -D warnings: that is the whole of "the emitted text is
# valid, warning-free Rust, and every node is const-evaluable", taken with
# nothing else in the crate that could carry the error. The second builds a
# harness that walks every group through the emitted `GROUPS` table and prints
# one line per node — so what the oracle judges has been through the Rust
# compiler and through the same const data a scanner would read, never through a
# regex over the text.
#
# THE HARNESS NAMES NO EXPECTED VALUE. It prints the group, the node's path, its
# tag, its permission and its DIRECT CHILD COUNT, and stops. The child count is
# what makes totality checkable at all: a message described without a child per
# field its source declares is a smaller tree that is otherwise indistinguishable
# from a correct one.
#
# IT ALWAYS RETURNS 0 AND REPORTS THROUGH `PTD_RC`, for the reason
# `rust_access_dumps` records: this suite runs under `set -e`, so a helper
# returning non-zero outside a condition ABORTS the run with no FAIL line at all.
PTD_RC=0
PTD_OUT=""
PTD_DUMP=""
permission_tree_dump() {
	local out="$1" work="$2"
	permission_tree_prelude "$work" "$out"
	PTD_DUMP="$work/tree.tsv"
	# TRUNCATED UP FRONT, so the file EXISTS even when a compile below returns
	# early. Callers read it with `cat` under `set -e`, and a `cat` of an absent
	# path ABORTS the whole suite — which prints no FAIL line at all, the one
	# outcome a canary must never produce. Measured: with the tree emission
	# removed from the generator, the suite died mid-section instead of
	# reporting the reds it had already collected.
	: > "$PTD_DUMP"
	cp "$work/prelude.rs" "$work/lib.rs"
	set +e
	PTD_OUT="$(rustc --edition 2021 --crate-type lib --emit=metadata -D warnings \
		-o "$work/lib.rmeta" "$work/lib.rs" 2>&1)"
	PTD_RC=$?
	set -e
	[ "$PTD_RC" -eq 0 ] || return 0
	cp "$work/prelude.rs" "$work/harness.rs"
	cat >> "$work/harness.rs" <<-PTHEOF

		fn label(p: Permission) -> &'static str {
		    match p {
		        Permission::Unspecified => "Unspecified",
		        Permission::Inherit => "Inherit",
		        Permission::Allow => "Allow",
		        Permission::Deny => "Deny",
		    }
		}

		fn walk(group: &str, prefix: &str, nodes: &'static [PermissionNode]) {
		    for n in nodes {
		        let path = if prefix.is_empty() {
		            n.name.to_string()
		        } else {
		            format!("{prefix}>{}", n.name)
		        };
		        println!(
		            "{group}\t{path}\t{}\t{}\t{}",
		            n.tag,
		            label(n.permission),
		            n.children.len()
		        );
		        walk(group, &path, n.children);
		    }
		}

		fn main() {
		    for (group, nodes) in GROUPS.iter().copied() {
		        walk(group, "", nodes);
		    }
		}
	PTHEOF
	set +e
	PTD_OUT="$(rustc --edition 2021 -o "$work/harness" "$work/harness.rs" 2>&1)"
	PTD_RC=$?
	set -e
	[ "$PTD_RC" -eq 0 ] || return 0
	set +e
	PTD_OUT="$("$work/harness" 2>&1 > "$PTD_DUMP")"
	PTD_RC=$?
	set -e
}

# state_table_dump <out-dir> <work-dir> — what the emitted STATE SUBSYSTEM
# table holds, as a tab-separated dump the oracle judges.
#
# THE SAME TWO-COMPILE SPLIT the permission tree and the access module use. The
# first compiles the fragment alone as a library under -D warnings, which is the
# whole of "the emitted text is valid, warning-free Rust"; the second builds a
# harness that walks `GROUP_STATE_SUBSYSTEMS` and prints one line per entry, so
# what the oracle judges has been through rustc and through the same const data
# a read path would narrow against.
#
# THE FRAGMENT ASSUMES NOTHING IS IN SCOPE, unlike the permission tree's, so the
# lib file is the `include!` and nothing else — which is itself the assertion
# that it declares no type and names no crate.
#
# IT ALWAYS RETURNS 0 AND REPORTS THROUGH `STD_RC`, and its dump is truncated up
# front, both for the reasons `permission_tree_dump` records.
STD_RC=0
STD_OUT=""
STD_DUMP=""
state_table_dump() {
	local out="$1" work="$2"
	mkdir -p "$work"
	STD_DUMP="$work/state.tsv"
	: > "$STD_DUMP"
	# `<<-` strips leading TABS only, so the tabs indent the heredoc and the
	# SPACES survive into the Rust source.
	cat > "$work/lib.rs" <<-STLEOF
		include!("$out/state_subsystems.rs");
	STLEOF
	set +e
	STD_OUT="$(rustc --edition 2021 --crate-type lib --emit=metadata -D warnings \
		-o "$work/lib.rmeta" "$work/lib.rs" 2>&1)"
	STD_RC=$?
	set -e
	[ "$STD_RC" -eq 0 ] || return 0
	cp "$work/lib.rs" "$work/harness.rs"
	cat >> "$work/harness.rs" <<-STHEOF

		fn main() {
		    for (group, entries) in GROUP_STATE_SUBSYSTEMS.iter().copied() {
		        for (subsystem, permitted) in entries.iter().copied() {
		            println!("{group}\t{subsystem}\t{permitted}");
		        }
		    }
		}
	STHEOF
	set +e
	STD_OUT="$(rustc --edition 2021 -o "$work/harness" "$work/harness.rs" 2>&1)"
	STD_RC=$?
	set -e
	[ "$STD_RC" -eq 0 ] || return 0
	set +e
	STD_OUT="$("$work/harness" 2>&1 > "$STD_DUMP")"
	STD_RC=$?
	set -e
}

# verify_state <dump> <policy> — judge a state-table dump against the policy
# that declared it, with the REAL tree's oracle. Bare, so its own status is read.
VS_RC=0
VS_OUT=""
verify_state() {
	set +e
	VS_OUT="$(cd "$PG" && clojure -M:verify state-table --dump "$1" --policy "$2" 2>&1)"
	VS_RC=$?
	set -e
}

# verify_tree <dump> <policy> [db] — judge a permission-tree dump against the
# inputs that produced it, with the REAL tree's oracle. Bare, so its own status
# is read.
VT_RC=0
VT_OUT=""
verify_tree() {
	local database="${3:-fixtures/db.edn}"
	set +e
	VT_OUT="$(cd "$PG" && clojure -M:verify permission-tree --dump "$1" --policy "$2" \
		--db "$database" --minted fixtures/minted.edn \
		--registry fixtures/numbering-registry.edn 2>&1)"
	VT_RC=$?
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
	diff -q "$BASE/permissions.edn" "$AGAIN/permissions.edn" > /dev/null 2>&1 &&
	diff -q "$BASE/sensor-reader.rs" "$AGAIN/sensor-reader.rs" > /dev/null 2>&1 &&
	diff -q "$BASE/commander.rs" "$AGAIN/commander.rs" > /dev/null 2>&1 &&
	diff -q "$BASE/permission_tree.rs" "$AGAIN/permission_tree.rs" > /dev/null 2>&1 &&
	diff -q "$BASE/state_subsystems.rs" "$AGAIN/state_subsystems.rs" > /dev/null 2>&1; then
	ok "the schema, the mirror, both access modules, the permission tree and the state table are byte-identical across runs"
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

section "RUST ACCESS — the direction a .proto cannot carry, compiled and asked"
# The emitted Rust access module is the only artefact of this run that a
# consumer can ask a DIRECTION of at compile time. Everything in this section
# and the two mutations after it is about that fact and nothing else.
RSBASE="$WORK/rs-base"
rust_access_dumps "$BASE" "$RSBASE" sensor-reader commander
if [ "$RSA_RC" -eq 0 ]; then
	ok "each emitted module compiles warning-free as a library, and its API answers"
else
	bad "the emitted Rust did not compile or did not run (rc=$RSA_RC): $RSA_OUT"
fi
verify_access "$RSA_DUMP" "$PG/fixtures/policy.edn"
if [ "$VA_RC" -eq 0 ]; then
	ok "every direction the modules answer agrees with the policy that granted it"
else
	bad "the baseline access dump disagreed with the policy (rc=$VA_RC): $VA_OUT"
fi
# NON-VACUITY: a clean verdict over zero grants reads exactly like a clean
# verdict over the real ones.
if contains "$VA_OUT" "— 0 granted"; then
	bad "the access oracle checked ZERO grants — a green over nothing"
else
	ok "the access oracle reports a non-zero grant count"
fi

section "RUST ACCESS — every direction a grant can hold, rendered"
# fixtures/policy.edn has no `#{:read :write}` grant — its two groups are read
# and write respectively, and that asymmetry is what several cases above are
# about. fixtures/policy-directions.edn exists for the combination it has none
# of, and MUTATION 11 below is what proves the fixture is load-bearing rather
# than decorative.
DIRS="$WORK/dirs"
generate "$PG" "$DIRS" fixtures/policy-directions.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the directions policy generates"
else
	bad "the directions policy failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
RSDIRS="$WORK/rs-dirs"
rust_access_dumps "$DIRS" "$RSDIRS" operator
if [ "$RSA_RC" -eq 0 ]; then
	ok "its module compiles warning-free as a library, and its API answers"
else
	bad "the directions module did not compile or did not run (rc=$RSA_RC): $RSA_OUT"
fi
DIRS_DUMP="$(cat "$RSA_DUMP")"
if contains "$DIRS_DUMP" "$(printf 'operator\tpgfix.Reading\ttrue\tfalse')" &&
	contains "$DIRS_DUMP" "$(printf 'operator\tpgfix.Stop\tfalse\ttrue')" &&
	contains "$DIRS_DUMP" "$(printf 'operator\tpgfix.SetMode\ttrue\ttrue')"; then
	ok "read-only, write-only and BOTH each answer their own pair of predicates"
else
	bad "a direction did not reach the compiled module: $DIRS_DUMP"
fi
verify_access "$RSA_DUMP" "$PG/fixtures/policy-directions.edn"
if [ "$VA_RC" -eq 0 ]; then
	ok "and all three agree with the policy that granted them"
else
	bad "the directions dump disagreed with its policy (rc=$VA_RC): $VA_OUT"
fi
DIRS_DUMP_FILE="$RSA_DUMP"

section "MUTATION 10 — a FLIPPED direction is caught"
# The mutation is in the RENDERING, which is where the brief for this artefact
# puts it: the projection is untouched, so the `.proto` and the permission
# mirror are byte-identical to the real tree's and only the Rust moves.
M10="$WORK/m10"
copy_tool "$M10"
mutate_file "$M10/src/protocol_gen/rust_access.clj" \
	'{#{:read} "Read"' \
	'{#{:read} "Write"' \
	|| bad "mutation 10 did not land"
generate "$M10" "$WORK/m10-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
rust_access_dumps "$WORK/m10-out" "$WORK/rs-m10" sensor-reader commander
if [ "$RSA_RC" -eq 0 ]; then
	ok "MUTANT: a wrong direction still COMPILES — which is why an oracle is needed"
else
	bad "the mutant module did not compile (rc=$RSA_RC), so the red below is about rustc: $RSA_OUT"
fi
verify_access "$RSA_DUMP" "$PG/fixtures/policy.edn"
if [ "$VA_RC" -eq 1 ] &&
	contains "$VA_OUT" "policy grants read=true write=false, the emitted module answers read=false write=true"; then
	ok "a flipped direction is REFUSED, naming the grant and both answers"
else
	bad "a flipped direction was not caught (rc=$VA_RC): $VA_OUT"
fi
# CONTROL: the `.proto` and the mirror are UNAFFECTED on this same mutant, so
# the red above is attributable to the Rust rendering and to nothing else.
verify "$WORK/m10-out"
if [ "$VER_RC" -eq 0 ]; then
	ok "CONTROL: the proto-and-mirror oracle calls that mutant CLEAN"
else
	bad "the mutant moved the schema or the mirror too (rc=$VER_RC): $VER_OUT"
fi
# THE NEIGHBOUR, on that same mutant: a clause with nothing to do with
# rendering must still refuse, or the case above could be satisfied by a
# mutation that broke the pass as a whole.
generate "$M10" "$WORK/m10-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 10 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 11 — the BOTH direction folded onto one is caught"
# The half fixtures/policy.edn structurally cannot see, and the reason
# fixtures/policy-directions.edn exists. Rendering `#{:read :write}` as `Write`
# loses `may_read` for every message granted both ways — and the two-group
# policy grants none, so it stays CLEAN on that mutant. That clean run is
# asserted rather than assumed: it is what makes the directions fixture
# load-bearing rather than decorative.
M11="$WORK/m11"
copy_tool "$M11"
mutate_file "$M11/src/protocol_gen/rust_access.clj" \
	'#{:read :write} "ReadWrite"}' \
	'#{:read :write} "Write"}' \
	|| bad "mutation 11 did not land"
generate "$M11" "$WORK/m11-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
rust_access_dumps "$WORK/m11-out" "$WORK/rs-m11" sensor-reader commander
verify_access "$RSA_DUMP" "$PG/fixtures/policy.edn"
if [ "$RSA_RC" -eq 0 ] && [ "$VA_RC" -eq 0 ]; then
	ok "MUTANT: the two-group policy is CLEAN — it grants no message BOTH ways"
else
	bad "the two-group policy reddened (rc=$VA_RC), so mutation 11 no longer shows what the directions fixture buys: $VA_OUT"
fi
generate "$M11" "$WORK/m11-dirs" fixtures/policy-directions.edn fixtures/db.edn
rust_access_dumps "$WORK/m11-dirs" "$WORK/rs-m11-dirs" operator
if [ "$RSA_RC" -eq 0 ]; then
	ok "MUTANT: the directions module still compiles — the fold is not a syntax error"
else
	bad "the mutant directions module did not compile (rc=$RSA_RC): $RSA_OUT"
fi
verify_access "$RSA_DUMP" "$PG/fixtures/policy-directions.edn"
if [ "$VA_RC" -eq 1 ] &&
	contains "$VA_OUT" "policy grants read=true write=true, the emitted module answers read=false write=true"; then
	ok "a dropped half of a BOTH grant is REFUSED, naming what was lost"
else
	bad "the folded direction was not caught (rc=$VA_RC): $VA_OUT"
fi
# THE NEIGHBOUR, again on that same mutant.
generate "$M11" "$WORK/m11-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 11 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 12 — the WARNING-FREE claim, proven able to fail"
# The library compile above is a claim in its own right — "the emitted text is
# valid Rust, with no warning" — and a claim nobody has watched fail is not
# evidence. Dropping the attribute the module carries for its verbatim variant
# names leaves the text perfectly valid and reintroduces a warning, which
# -D warnings turns into an error. So this mutation separates the two halves of
# that claim rather than merely breaking the file.
M12="$WORK/m12"
copy_tool "$M12"
mutate_file "$M12/src/protocol_gen/rust_access.clj" \
	'"#[allow(non_camel_case_types)]\n"' \
	'""' \
	|| bad "mutation 12 did not land"
generate "$M12" "$WORK/m12-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
rust_access_dumps "$WORK/m12-out" "$WORK/rs-m12" commander
if [ "$RSA_RC" -ne 0 ] && contains "$RSA_OUT" "non_camel_case_types"; then
	ok "MUTANT: the library compile REFUSES, naming the lint it was denied on"
else
	bad "the -D warnings leg did not refuse a warning (rc=$RSA_RC): $RSA_OUT"
fi
# CONTROL: the text is still VALID Rust — only the warning-free half moved. A
# mutation that had produced a syntax error would satisfy the case above while
# proving nothing about -D warnings.
set +e
M12_PLAIN="$(rustc --edition 2021 --crate-type lib --emit=metadata \
	-o "$WORK/rs-m12/plain.rmeta" "$WORK/m12-out/commander.rs" 2>&1)"
M12_PLAIN_RC=$?
set -e
if [ "$M12_PLAIN_RC" -eq 0 ]; then
	ok "CONTROL: without -D warnings the same mutant module COMPILES"
else
	bad "the mutant is not valid Rust at all, so the case above is about syntax: $M12_PLAIN"
fi
# THE NEIGHBOUR, on that same mutant, as mutations 10 and 11 carry: a clause
# with nothing to do with rendering must still refuse, or the red above could
# be satisfied by a mutation that broke the pass as a whole.
generate "$M12" "$WORK/m12-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 12 broke more than its clause: $GEN_OUT"
fi

# rust_names_message <module-file> <work-dir> <label> <variant> — compile a
# throwaway harness that NAMES one `Message` variant of an emitted access
# module, in the two positions a consumer writes one: a value expression and a
# `match` arm. It asserts nothing; the caller reads the verdict.
#
# WHY BOTH POSITIONS IN ONE COMPILE. A consumer either holds a `Message` or
# dispatches on one, and the two are resolved by different halves of rustc's
# path machinery. Compiling them together costs one invocation and removes the
# reading where a case proved only that a value expression refuses.
#
# METADATA ONLY, DELIBERATELY. Nothing here is RUN — the question is whether
# the name resolves at all — so not linking removes the linker, and every
# failure it could contribute, from a case whose whole content is a failure.
# The flags are otherwise the ones `rust_access_dumps` compiles a module under.
#
# -D warnings IS DELIBERATELY ABSENT, and its absence is the point: this
# helper's negative cases assert an ERROR, and a denied lint is an error too.
# Under -D warnings a harness that merely warned would satisfy a case about a
# withheld message, which is the attribution failure the whole section is
# arranged against.
#
# IT ALWAYS RETURNS 0 AND REPORTS THROUGH `RNM_RC`, for the reason
# `rust_access_dumps` records: this suite runs under `set -e`, so a helper
# returning non-zero outside a condition ABORTS the run with no FAIL line —
# and here the non-zero status is the ORDINARY outcome, not the exceptional
# one.
RNM_RC=0
RNM_OUT=""
rust_names_message() {
	local module="$1" work="$2" label="$3" variant="$4"
	mkdir -p "$work"
	# `<<-` strips leading TABS only, so the tabs indent the heredoc and the
	# SPACES survive into the Rust source.
	cat > "$work/$label.rs" <<-RNMEOF
		#[allow(dead_code)]
		#[path = "$module"]
		mod m;

		#[allow(dead_code)]
		fn hold_it() -> m::Message {
		    m::Message::$variant
		}

		#[allow(dead_code)]
		fn dispatch_on_it(msg: m::Message) -> bool {
		    matches!(msg, m::Message::$variant)
		}
	RNMEOF
	set +e
	RNM_OUT="$(rustc --edition 2021 --crate-type lib --emit=metadata \
		-o "$work/$label.rmeta" "$work/$label.rs" 2>&1)"
	RNM_RC=$?
	set -e
}

section "RUST ACCESS COMPILE-FAIL — a message the policy withheld cannot be NAMED"
# The access module's central claim is that a withheld message is a COMPILE
# ERROR rather than a lookup returning nothing, and nothing proves a compile
# error except a compile that is REQUIRED to fail.
#
# THE PASS-VALUE HAZARD IS THE WHOLE OF THIS SECTION. A compile-fail case
# "passes" when compilation fails for ANY reason — a typo in the harness, a
# missing module, an unrelated lint — so every case below asserts the
# DIAGNOSTIC'S IDENTITY (rustc's own error code and the missing name) and never
# a bare non-zero, and three controls stand around it: one proving the harness
# shape compiles at all, one proving the identical harness compiles against the
# group the policy DID grant the message to, and one proving a harness that
# cannot find its module fails with a DIFFERENT diagnostic.
#
# THE DIAGNOSTIC TEXT WAS MEASURED, not assumed: `error[E0599]: no variant,
# associated function, or constant named `X` found for enum `Message` in the
# current scope`, under rustc 1.97 — the toolchain image's pin, whose version
# is in Dockerfile.base. A later rustc that rewords it fails these cases, and
# that red is a HARNESS-MAINTENANCE fact rather than a policy regression: the
# controls beside each case are what tell the two apart, because a reworded
# diagnostic leaves every control exactly as green as it is now.
CFWORK="$WORK/compile-fail"
# THE CONTROL COMES FIRST. A harness shape that does not compile at all makes
# every refusal below unattributable, so it is established before anything is
# asked to fail.
rust_names_message "$BASE/sensor-reader.rs" "$CFWORK" granted-reading pgfix_Reading
if [ "$RNM_RC" -eq 0 ]; then
	ok "CONTROL: the harness shape compiles when it names a GRANTED message"
else
	bad "CANNOT RUN — the harness shape does not compile even for a granted message (rc=$RNM_RC), so no refusal below is attributable: $RNM_OUT"
fi
# `pgfix.Stop` is granted to `commander` and withheld from `sensor-reader`, so
# what refuses here is the POLICY BOUNDARY and not the message's absence from
# the world: the message exists, is emitted, and has a variant one module over.
rust_names_message "$BASE/sensor-reader.rs" "$CFWORK" withheld-stop pgfix_Stop
if [ "$RNM_RC" -ne 0 ] && contains "$RNM_OUT" "error[E0599]" &&
	contains "$RNM_OUT" "pgfix_Stop" &&
	contains "$RNM_OUT" 'found for enum `Message`'; then
	ok "a message granted to a SIBLING group is E0599, naming the variant and the enum"
else
	bad "naming a withheld message did not fail with the named diagnostic (rc=$RNM_RC): $RNM_OUT"
fi
# THE ADJACENCY CONTROL, and the strongest one available: the harness TEXT is
# identical apart from which module it is compiled against, and the verdict
# flips. So the refusal above is a fact about the grant, not about the name.
rust_names_message "$BASE/commander.rs" "$CFWORK" granted-stop pgfix_Stop
if [ "$RNM_RC" -eq 0 ]; then
	ok "CONTROL: the identical harness COMPILES against the group that WAS granted it"
else
	bad "the harness naming a granted message failed against its own group (rc=$RNM_RC): $RNM_OUT"
fi
# The other withheld class: `pgfix.Secret` is granted to no group in any
# fixture policy, so no module anywhere carries a variant for it.
rust_names_message "$BASE/commander.rs" "$CFWORK" withheld-secret pgfix_Secret
if [ "$RNM_RC" -ne 0 ] && contains "$RNM_OUT" "error[E0599]" &&
	contains "$RNM_OUT" "pgfix_Secret" &&
	contains "$RNM_OUT" 'found for enum `Message`'; then
	ok "a message granted to NO group is E0599 too, naming that variant"
else
	bad "naming a message no policy grants did not fail with the named diagnostic (rc=$RNM_RC): $RNM_OUT"
fi
# THE BROKEN-HARNESS CONTROL. A compile-fail case whose assertion were merely
# "rc is non-zero" would be satisfied by a harness that cannot find its module
# at all — the exact reading this section exists to refuse. Driven, rather than
# argued: the same builder is pointed at a module that does not exist, and the
# E0599 assertion must NOT be satisfied. The wording rustc uses for it is
# deliberately not asserted; what matters is that it is not the diagnostic the
# cases above name.
rust_names_message "$WORK/no-such-module.rs" "$CFWORK" broken-harness pgfix_Stop
if [ "$RNM_RC" -ne 0 ] && ! contains "$RNM_OUT" "error[E0599]"; then
	ok "CONTROL: a harness that cannot find its module fails for a DIFFERENT reason"
else
	bad "a harness with no module at all satisfied the E0599 assertion (rc=$RNM_RC): $RNM_OUT"
fi

section "MUTATION 13 — a WITHHELD message leaking into the enum is caught"
# The regression the section above exists to catch, made real. `granted-messages`
# is read by the Rust emission and by nothing else, so the `.proto` text and the
# permission mirror are untouched and only the access module moves — the same
# framing mutations 10 to 12 carry.
#
# `pgfix.Secret` is the message to leak, and NOT `pgfix.Stop`: a group already
# granted `pgfix.Stop` would receive a SECOND variant of that name and its
# module would fail to compile as a duplicate definition, which would satisfy
# nothing here while looking like it had.
M13="$WORK/m13"
copy_tool "$M13"
mutate_file "$M13/src/protocol_gen/rust_access.clj" \
	'(vec (sort-by :id (:messages group)))' \
	'(vec (sort-by :id (conj (:messages group) {:id "pgfix.Secret" :proto-name "pgfix_Secret" :origin :descriptor :access #{:write} :fields [] :oneofs []})))' \
	|| bad "mutation 13 did not land"
generate "$M13" "$WORK/m13-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — what follows is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
# The leaked module is still VALID, WARNING-FREE Rust, which is the reason a
# compile-fail case is needed at all: nothing about the file's shape gives the
# leak away, and the library compile every other Rust case rests on stays green.
set +e
M13_LIB="$(rustc --edition 2021 --crate-type lib --emit=metadata -D warnings \
	-o "$WORK/m13-lib.rmeta" "$WORK/m13-out/commander.rs" 2>&1)"
M13_LIB_RC=$?
set -e
if [ "$M13_LIB_RC" -eq 0 ]; then
	ok "MUTANT: the leaked module is still valid, warning-free Rust"
else
	bad "the leaked module does not compile, so this mutation is about syntax: $M13_LIB"
fi
rust_names_message "$WORK/m13-out/commander.rs" "$WORK/cf-m13" leaked-secret pgfix_Secret
if [ "$RNM_RC" -eq 0 ]; then
	ok "MUTANT: the withheld message becomes NAMEABLE — the compile-fail cases can go red"
else
	bad "the leak did not make the withheld message nameable, so the compile-fail cases above have not been shown able to fail (rc=$RNM_RC): $RNM_OUT"
fi
# AND THE ORACLE CATCHES THE SAME LEAK, from the other side. Two independent
# mechanisms over one defect, which is what the fabricated-dump case further
# down asserts without a mutation behind it.
rust_access_dumps "$WORK/m13-out" "$WORK/rs-m13" commander
verify_access "$RSA_DUMP" "$PG/fixtures/policy.edn"
if [ "$RSA_RC" -eq 0 ] && [ "$VA_RC" -eq 1 ] &&
	contains "$VA_OUT" "pgfix.Secret: answered by the emitted module and granted by nothing"; then
	ok "MUTANT: the access oracle REFUSES the leak too, naming the ungranted message"
else
	bad "the leak reached the oracle unnoticed (rsa=$RSA_RC va=$VA_RC): $VA_OUT"
fi
# THE NEIGHBOUR, on that same mutant. It is owed by the ORACLE case above and
# not by the nameability case: a red is what a mutation breaking the pass as a
# whole would also produce, while a green is not.
generate "$M13" "$WORK/m13-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 13 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 14 — a GRANTED message dropped from the enum is caught"
# The other direction, and what it buys is the CONTROL rather than the case: a
# harness naming a granted message compiles, and that green is worth having
# only if it is a fact about the emitted enum rather than about the harness.
# Dropping the lowest-sorted granted message empties `sensor-reader`'s enum —
# it holds exactly one — and the control must then stop compiling, naming the
# variant it can no longer find.
M14="$WORK/m14"
copy_tool "$M14"
mutate_file "$M14/src/protocol_gen/rust_access.clj" \
	'(vec (sort-by :id (:messages group)))' \
	'(vec (rest (sort-by :id (:messages group))))' \
	|| bad "mutation 14 did not land"
generate "$M14" "$WORK/m14-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
# The emptied module is still valid, warning-free Rust — the emitter renders an
# uninhabited `Message` deliberately — so the red below is about the missing
# VARIANT and not about a file rustc could not read.
set +e
M14_LIB="$(rustc --edition 2021 --crate-type lib --emit=metadata -D warnings \
	-o "$WORK/m14-lib.rmeta" "$WORK/m14-out/sensor-reader.rs" 2>&1)"
M14_LIB_RC=$?
set -e
if [ "$M14_LIB_RC" -eq 0 ]; then
	ok "MUTANT: the emptied module is still valid, warning-free Rust"
else
	bad "the emptied module does not compile, so the red below is about syntax: $M14_LIB"
fi
rust_names_message "$WORK/m14-out/sensor-reader.rs" "$WORK/cf-m14" dropped-reading pgfix_Reading
if [ "$RNM_RC" -ne 0 ] && contains "$RNM_OUT" "error[E0599]" &&
	contains "$RNM_OUT" "pgfix_Reading"; then
	ok "MUTANT: the CONTROL stops compiling, naming the granted variant it lost"
else
	bad "a dropped grant left the control harness compiling, so its green proves nothing about the module (rc=$RNM_RC): $RNM_OUT"
fi
# AND THE ORACLE CATCHES THE SAME DROP, from the other side — the mirror of
# mutation 13's oracle case, so neither direction of the claim rests on rustc
# alone. Both groups are dumped: `sensor-reader`'s module is now EMPTY and
# contributes nothing, so a single-group dump would be vacuous rather than
# refused, and the vacuity case further down would be the thing that fired.
rust_access_dumps "$WORK/m14-out" "$WORK/rs-m14" sensor-reader commander
verify_access "$RSA_DUMP" "$PG/fixtures/policy.edn"
if [ "$RSA_RC" -eq 0 ] && [ "$VA_RC" -eq 1 ] &&
	contains "$VA_OUT" "sensor-reader/pgfix.Reading: granted, and the emitted module answers for no such message"; then
	ok "MUTANT: the access oracle REFUSES the drop too, naming the grant nothing answers"
else
	bad "the dropped grant reached the oracle unnoticed (rsa=$RSA_RC va=$VA_RC): $VA_OUT"
fi
# THE NEIGHBOUR, on that same mutant, for the reason the cases above are REDS.
generate "$M14" "$WORK/m14-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 14 broke more than its clause: $GEN_OUT"
fi

section "RUST ACCESS — a grant the policy never made is caught the other way"
# The inverse finding, taken WITHOUT a tool mutation: the oracle must refuse a
# module answering for a message nothing granted, not merely one whose
# direction moved. `pgfix.Secret` is granted to no group in any fixture policy.
FAB="$WORK/fabricated.tsv"
cp "$DIRS_DUMP_FILE" "$FAB"
printf 'operator\tpgfix.Secret\tfalse\ttrue\n' >> "$FAB"
verify_access "$FAB" "$PG/fixtures/policy-directions.edn"
if [ "$VA_RC" -eq 1 ] && contains "$VA_OUT" "pgfix.Secret: answered by the emitted module and granted by nothing"; then
	ok "a fabricated grant is REFUSED, naming the message no policy carries"
else
	bad "a fabricated grant was not caught (rc=$VA_RC): $VA_OUT"
fi

section "RUST ACCESS VACUITY — an empty or unreadable dump is a FAULT, not a pass"
: > "$WORK/empty.tsv"
verify_access "$WORK/empty.tsv" "$PG/fixtures/policy-directions.edn"
if [ "$VA_RC" -eq 2 ] && contains "$VA_OUT" "CANNOT RUN"; then
	ok "an empty dump is exit 2, not a clean verdict over nothing"
else
	bad "an empty dump did not report CANNOT RUN (rc=$VA_RC): $VA_OUT"
fi
# A SHAPE FAULT IS NOT A FINDING. A dump whose shape changed means the harness
# or the module moved, and scoring that as a disagreement about ACCESS would
# name the wrong defect — so it must be 2 rather than 1.
printf 'operator\tpgfix.Reading\ttrue\n' > "$WORK/malformed.tsv"
verify_access "$WORK/malformed.tsv" "$PG/fixtures/policy-directions.edn"
if [ "$VA_RC" -eq 2 ] && contains "$VA_OUT" "CANNOT RUN"; then
	ok "a dump line that is not four fields is exit 2, never a finding"
else
	bad "a malformed dump was scored as a verdict (rc=$VA_RC): $VA_OUT"
fi

section "PERMISSION TREE — the nested mirror a byte-level scanner walks"
# The flat permission mirror cannot be walked tag by tag, so the generator emits
# a second, NESTED one. Everything in this section and the five mutations after
# it is about that artefact and nothing else.
PTBASE="$WORK/pt-base"
permission_tree_dump "$BASE" "$PTBASE"
if [ "$PTD_RC" -eq 0 ]; then
	ok "the emitted fragment compiles warning-free where the two types are in scope, and its data reads"
else
	bad "the emitted permission tree did not compile or did not run (rc=$PTD_RC): $PTD_OUT"
fi
verify_tree "$PTD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VT_RC" -eq 0 ]; then
	ok "every node the emitted statics hold agrees with the inputs that produced them"
else
	bad "the baseline tree disagreed with its inputs (rc=$VT_RC): $VT_OUT"
fi
# NON-VACUITY: a clean verdict over zero nodes reads exactly like a clean verdict
# over the real ones. THE `clean —` HALF IS LOAD-BEARING: a bare absence probe
# for "0 node(s)" is also satisfied by a FAULT, whose output says neither, so its
# pass value would equal its nothing-ran value — measured, on a run with the tree
# emission removed, where this case passed while the case above it reddened.
if contains "$VT_OUT" "clean — " && ! contains "$VT_OUT" "clean — 0 node(s)"; then
	ok "the tree oracle reports a non-zero node count"
else
	bad "the tree oracle checked ZERO nodes, or did not report a count at all: $VT_OUT"
fi
TREE_DUMP="$(cat "$PTD_DUMP")"
# TOTALITY, read off the dump rather than argued. `pgfix.Reading` declares six
# fields and the policy grants three, so a tree that listed only the grants
# would carry three children and look perfectly consistent with the group's
# `.proto`. The child count is what makes the difference visible.
if contains "$TREE_DUMP" "$(printf 'sensor-reader\tpgfix.Reading\t0\tAllow\t6')" &&
	contains "$TREE_DUMP" "$(printf 'sensor-reader\tpgfix.Reading>value\t3\tInherit\t0')" &&
	contains "$TREE_DUMP" "$(printf 'sensor-reader\tpgfix.Reading>label\t11\tDeny\t0')"; then
	ok "a message node carries one child per field its SOURCE declares, granted and denied alike"
else
	bad "the tree is not total over the source message's fields: $TREE_DUMP"
fi
# THE DELIBERATE DISCLOSURE, asserted rather than left to the prose. The group's
# `.proto` does not name the withheld field at all; the tree names it and denies
# it, which is what a scanner needs to tell a denial from a field nobody added.
if ! command grep -q 'label' "$BASE/sensor-reader.proto" &&
	contains "$TREE_DUMP" "pgfix.Reading>label"; then
	ok "a field the group's .proto withholds is NAMED in the tree, and DENIED there"
else
	bad "the tree and the schema disagree about naming the withheld field"
fi
# RECURSION: a granted message-typed field expands into its target's fields, and
# the target is guaranteed present by the projection's own closure check.
if contains "$TREE_DUMP" "$(printf 'commander\tpgfix.Command>set_mode\t8\tInherit\t1')" &&
	contains "$TREE_DUMP" "$(printf 'commander\tpgfix.Command>set_mode>mode\t4\tInherit\t0')"; then
	ok "a granted message-typed field expands into the fields of the message it names"
else
	bad "a granted message-typed field did not expand: $TREE_DUMP"
fi
# THE KIND MARKER, and it is read off the emitted TEXT rather than off the dump.
# That is deliberate and it is the one thing in this section the harness cannot
# supply: the dump prints what the const data HOLDS, while a node's kind is a
# property of the CALL that built it. Giving the prelude's struct a kind field
# would be this repository inventing a consumer's declaration, which the
# prelude's own header says it must not do.
#
# `pgfix.Start` IS THE CASE THE MARKER EXISTS FOR. The policy grants it whole
# and it declares NO fields, so its node carries no children and — on the const
# data alone — is indistinguishable from the scalar beside it. One is a message
# whose every interior tag is undescribed and must be refused; the other is
# bytes a scanner may step over. The CONTROL below asserts that ambiguity is
# real rather than argued.
TREE_RS="$(cat "$BASE/permission_tree.rs")"
if contains "$TREE_RS" 'PermissionNode::message(2, "start", Permission::Inherit, &[]),' &&
	contains "$TREE_RS" 'PermissionNode::leaf(3, "value", Permission::Inherit),'; then
	ok "a granted message declaring NO fields emits message, and a scalar emits leaf"
else
	bad "the kind marker is not in the emitted text: $TREE_RS"
fi
if contains "$TREE_DUMP" "$(printf 'commander\tpgfix.Command>start\t2\tInherit\t0')" &&
	contains "$TREE_DUMP" "$(printf 'sensor-reader\tpgfix.Reading>value\t3\tInherit\t0')"; then
	ok "CONTROL: both of those nodes hold ZERO children, so the marker is what separates them"
else
	bad "the two nodes the marker separates are not both in the dump: $TREE_DUMP"
fi
# NO NODE IS BUILT BY THE RETIRED SINGLE CONSTRUCTOR. There is no fallback and
# no third arm, so a surviving `new` would mean some node's kind was never
# decided at all.
if ! contains "$TREE_RS" 'PermissionNode::new('; then
	ok "no node is emitted through the retired single constructor"
else
	bad "the emitted tree still calls the retired constructor: $TREE_RS"
fi

section "PERMISSION TREE — a DENIED message-typed field is TERMINAL"
# fixtures/policy.edn cannot carry this case: its withheld fields are all
# scalars, so a tree that wrongly described the interior of a denial would emit
# nothing extra there and the bytes would be identical to a correct run.
# fixtures/policy-nested.edn exists for exactly that gap, and MUTATION 16 below
# is what proves it load-bearing rather than decorative.
NESTED="$WORK/nested"
generate "$PG" "$NESTED" fixtures/policy-nested.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the nested policy generates"
else
	bad "the nested policy failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
permission_tree_dump "$NESTED" "$WORK/pt-nested"
if [ "$PTD_RC" -eq 0 ]; then
	ok "its fragment compiles warning-free, and its data reads"
else
	bad "the nested fragment did not compile or did not run (rc=$PTD_RC): $PTD_OUT"
fi
NESTED_DUMP="$(cat "$PTD_DUMP")"
if contains "$NESTED_DUMP" "$(printf 'relay\tpgfix.Command>set_mode\t8\tDeny\t0')" &&
	! contains "$NESTED_DUMP" "set_mode>mode"; then
	ok "a denied message-typed field carries NO children — its interior is not described at all"
else
	bad "a denied message-typed field described its interior: $NESTED_DUMP"
fi
# AND IT IS STILL A MESSAGE NODE. Denial is carried by `Permission::Deny`; a
# generator that expressed it by calling the field a leaf instead would be
# telling a scanner the field's bytes hold no tags, which is false of the source
# and which every check above this line would pass over — the dump prints a zero
# child count either way.
NESTED_RS="$(cat "$NESTED/permission_tree.rs")"
if contains "$NESTED_RS" 'PermissionNode::message(8, "set_mode", Permission::Deny, &[]),'; then
	ok "a DENIED message-typed field keeps its SOURCE TYPE's kind, terminal through its PERMISSION"
else
	bad "a denied message-typed field lost its source type's kind: $NESTED_RS"
fi
# THE CONTROL for that absence probe, and the strongest one available: the SAME
# message-typed field, in the group that WAS granted it, does describe its
# interior. So what suppresses the interior is the denial and not the emitter
# failing to descend anywhere.
if contains "$TREE_DUMP" "set_mode>mode"; then
	ok "CONTROL: the identical field expands in the group whose policy GRANTED it"
else
	bad "the interior probe finds nothing even where the field is granted"
fi
verify_tree "$PTD_DUMP" "$PG/fixtures/policy-nested.edn"
if [ "$VT_RC" -eq 0 ]; then
	ok "and the nested tree agrees with the policy that produced it"
else
	bad "the nested tree disagreed with its policy (rc=$VT_RC): $VT_OUT"
fi

section "PERMISSION TREE REFUSALS — a cyclic grant and a colliding static name"
# Both are policy-reachable, so both are driven with a fixture rather than with
# a mutation, and each has its clause broken alone below.
generate "$PG" "$WORK/cycle-out" fixtures/refusal-policy-cycle.edn fixtures/refusal-db-cycle.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "permission-cycle"; then
	ok "a self-referential grant is REFUSED, by name"
else
	bad "a cyclic grant was not refused (rc=$GEN_RC): $GEN_OUT"
fi
# AND THE REFUSAL LEFT NOTHING BEHIND. The trees are built before any file is
# written precisely so a refusal here cannot strand a partial output set, and a
# partial set is indistinguishable from a policy that granted less.
if [ ! -e "$WORK/cycle-out/walker.proto" ] && [ ! -e "$WORK/cycle-out/permissions.edn" ]; then
	ok "the refusal wrote no partial output set"
else
	bad "the cyclic refusal left files behind: $(ls "$WORK/cycle-out")"
fi
generate "$PG" "$WORK/gname-out" fixtures/refusal-policy-group-name.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "name-collision"; then
	ok "two group ids that flatten onto one Rust static are REFUSED, by name"
else
	bad "a colliding static name was not refused (rc=$GEN_RC): $GEN_OUT"
fi

section "MUTATION 15 — a tree that lists only the GRANTED fields is caught"
# TOTALITY is the property the consumer's undescribed-tag refusal rests on, and
# a tree that dropped its denied nodes is the one defect that leaves every other
# artefact of the run untouched: the `.proto` never named those fields anyway,
# the flat mirror has no permission axis, and the fragment still compiles.
M15="$WORK/m15"
copy_tool "$M15"
mutate_file "$M15/src/protocol_gen/permission_tree.clj" \
	'          (sort-by :number (:fields src)))))' \
	'          (sort-by :number (filterv #(contains? kept (:name %)) (:fields src))))))' \
	|| bad "mutation 15 did not land"
generate "$M15" "$WORK/m15-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
permission_tree_dump "$WORK/m15-out" "$WORK/pt-m15"
if [ "$PTD_RC" -eq 0 ]; then
	ok "MUTANT: the smaller tree is still valid, warning-free Rust — nothing about its shape gives it away"
else
	bad "the mutant fragment did not compile (rc=$PTD_RC), so the red below is about rustc: $PTD_OUT"
fi
verify_tree "$PTD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VT_RC" -eq 1 ] &&
	contains "$VT_OUT" "sensor-reader/pgfix.Reading>label: described by the policy, and the emitted tree has no such node" &&
	contains "$VT_OUT" "children=6, the emitted tree holds tag=0 permission=Allow children=3"; then
	ok "a tree missing its denied nodes is REFUSED, naming the node and the child count"
else
	bad "a non-total tree was not caught (rc=$VT_RC): $VT_OUT"
fi
# CONTROL: the `.proto` and the flat mirror are UNAFFECTED on this same mutant,
# so the red above is attributable to the tree emission and to nothing else.
verify "$WORK/m15-out"
if [ "$VER_RC" -eq 0 ]; then
	ok "CONTROL: the proto-and-mirror oracle calls that mutant CLEAN"
else
	bad "the mutant moved the schema or the mirror too (rc=$VER_RC): $VER_OUT"
fi
# THE NEIGHBOUR, on that same mutant.
generate "$M15" "$WORK/m15-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 15 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 16 — describing the interior of a DENIAL is caught"
# The generator's own invariant, and the one refusal in this tool that NO policy
# can reach: `expand` makes a denied node terminal by construction, so the check
# judges an empty population on every legal input. That is exactly why its
# ability to fire can only be shown by breaking the construction — the same
# shape `protocol-gen.numbering/assert-stamped!` carries.
M16="$WORK/m16"
copy_tool "$M16"
mutate_file "$M16/src/protocol_gen/permission_tree.clj" \
	'                  descend? (and granted? (= :message node-kind))]' \
	'                  descend? (= :message node-kind)]' \
	|| bad "mutation 16 did not land"
generate "$M16" "$WORK/m16-out" fixtures/policy-nested.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "grant-under-denial"; then
	ok "MUTANT: a node beneath a denial REFUSES the run, by name"
else
	bad "describing a denial's interior was not refused (rc=$GEN_RC): $GEN_OUT"
fi
# AND THE TWO-GROUP POLICY IS CLEAN ON THAT SAME MUTANT, which is what makes
# fixtures/policy-nested.edn load-bearing: every field policy.edn withholds is a
# SCALAR, so descending beneath its denials reaches nothing and emits nothing.
generate "$M16" "$WORK/m16-clean" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "MUTANT: the two-group policy stays CLEAN — its denied fields have no interior to describe"
else
	bad "the two-group policy reddened (rc=$GEN_RC), so mutation 16 no longer shows what the nested fixture buys: $GEN_OUT"
fi
# THE NEIGHBOUR, on that same mutant.
generate "$M16" "$WORK/m16-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 16 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 17 — the cycle clause, broken alone"
M17="$WORK/m17"
copy_tool "$M17"
mutate_file "$M17/src/protocol_gen/permission_tree.clj" \
	'  (when (contains? seen msg-id)' \
	'  (when (contains? #{} msg-id)' \
	|| bad "mutation 17 did not land"
generate "$M17" "$WORK/m17-out" fixtures/refusal-policy-cycle.edn fixtures/refusal-db-cycle.edn
# THE MUTANT DIES RATHER THAN VERDICTS, and that is the point of the clause
# rather than a defect in the case: a static tree over a cycle is infinite, so
# without the check the expansion runs until the stack is gone. What the clause
# buys is a NAMED refusal in place of a stack trace, so the assertion is that
# the name is gone AND the crash is what replaced it.
if [ "$GEN_RC" -ne 0 ] && ! contains "$GEN_OUT" "permission-cycle" &&
	contains "$GEN_OUT" "StackOverflowError"; then
	ok "MUTANT: the FINDING is gone and the expansion runs out of stack instead"
else
	bad "the mutant still named the clause, or died some other way (rc=$GEN_RC): $GEN_OUT"
fi
# THE NEIGHBOUR is the OTHER clause this namespace raises, on this same mutant.
generate "$M17" "$WORK/m17-neighbour" fixtures/refusal-policy-group-name.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "name-collision"; then
	ok "CONTROL: the static-name clause still refuses on that same mutant"
else
	bad "the neighbouring clause stopped refusing too: $GEN_OUT"
fi

section "MUTATION 18 — the colliding-static clause, broken alone"
M18="$WORK/m18"
copy_tool "$M18"
mutate_file "$M18/src/protocol_gen/permission_tree.clj" \
	'            :when (> (count gs) 1)]' \
	'            :when (> (count gs) 99)]' \
	|| bad "mutation 18 did not land"
generate "$M18" "$WORK/m18-out" fixtures/refusal-policy-group-name.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ] && ! contains "$GEN_OUT" "name-collision"; then
	ok "MUTANT: the FINDING is gone and the run emits instead of refusing"
else
	bad "the mutant still named the clause (rc=$GEN_RC): $GEN_OUT"
fi
# AND THE APPROXIMATION IS SHOWN, not merely inferred: the emitted fragment now
# defines one static twice, and rustc says so. This is what the refusal buys —
# without it a consumer's first symptom names Rust rather than the policy.
permission_tree_dump "$WORK/m18-out" "$WORK/pt-m18"
if [ "$PTD_RC" -ne 0 ] && contains "$PTD_OUT" "E0428" && contains "$PTD_OUT" "RELAY_A"; then
	ok "MUTANT: the emitted fragment defines RELAY_A twice and rustc REFUSES it, by error code"
else
	bad "the duplicate static did not reach rustc as a duplicate (rc=$PTD_RC): $PTD_OUT"
fi
# THE NEIGHBOUR, again the other clause this namespace raises.
generate "$M18" "$WORK/m18-neighbour" fixtures/refusal-policy-cycle.edn fixtures/refusal-db-cycle.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "permission-cycle"; then
	ok "CONTROL: the cycle clause still refuses on that same mutant"
else
	bad "the neighbouring clause stopped refusing too: $GEN_OUT"
fi

section "MUTATION 19 — a field withheld from a group's POLICY moves that group's tree"
# The other direction, and it mutates the FIXTURE rather than the tool: the
# question here is whether the emitted tree actually FOLLOWS the policy, which a
# tool mutation cannot ask. `value` is dropped from the sensor-reader grant and
# nothing else changes.
M19="$WORK/m19"
copy_tool "$M19"
mutate_file "$M19/fixtures/policy.edn" \
	'             :fields #{"value" "mode" "history"}}]' \
	'             :fields #{"mode" "history"}}]' \
	|| bad "mutation 19 did not land"
generate "$M19" "$WORK/m19-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — what follows is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
# BOTH FILES MUST EXIST FIRST. `diff` of two absent paths is non-zero too, so a
# bare `! diff` here would report "the bytes moved" for a run that emitted
# nothing at all — the pass value equalling the nothing-ran value.
if [ -s "$BASE/permission_tree.rs" ] && [ -s "$WORK/m19-out/permission_tree.rs" ] &&
	! diff -q "$BASE/permission_tree.rs" "$WORK/m19-out/permission_tree.rs" > /dev/null 2>&1; then
	ok "MUTANT: withholding one field CHANGES the emitted tree's bytes"
else
	bad "withholding a field left the emitted tree byte-identical, or one of the two was never written"
fi
permission_tree_dump "$WORK/m19-out" "$WORK/pt-m19"
M19_DUMP="$(cat "$PTD_DUMP")"
if [ "$PTD_RC" -eq 0 ] &&
	contains "$M19_DUMP" "$(printf 'sensor-reader\tpgfix.Reading>value\t3\tDeny\t0')"; then
	ok "MUTANT: the withheld field is still DESCRIBED, and now DENIED — totality is what makes that visible"
else
	bad "the withheld field did not turn into a denial (rc=$PTD_RC): $M19_DUMP"
fi
# THE FIRST DIRECTION: judged against the policy that produced it, the mutant is
# CLEAN. So the change is the policy taking effect, not the emitter corrupting
# its output.
verify_tree "$PTD_DUMP" "$M19/fixtures/policy.edn"
if [ "$VT_RC" -eq 0 ]; then
	ok "MUTANT: judged against its OWN policy the tree is clean — the move is a policy effect"
else
	bad "the mutant tree disagreed with the policy that produced it (rc=$VT_RC): $VT_OUT"
fi
# THE SECOND DIRECTION: judged against the REAL tree's policy it REDS, naming
# the node and both permissions.
verify_tree "$PTD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VT_RC" -eq 1 ] &&
	contains "$VT_OUT" "sensor-reader/pgfix.Reading>value: the policy describes tag=3 permission=Inherit children=0, the emitted tree holds tag=3 permission=Deny children=0"; then
	ok "and against the UNMUTATED policy it is REFUSED, naming the node and both permissions"
else
	bad "the moved permission was not caught (rc=$VT_RC): $VT_OUT"
fi

section "PERMISSION TREE VACUITY — an empty or unreadable dump is a FAULT, not a pass"
: > "$WORK/empty-tree.tsv"
verify_tree "$WORK/empty-tree.tsv" "$PG/fixtures/policy.edn"
if [ "$VT_RC" -eq 2 ] && contains "$VT_OUT" "CANNOT RUN"; then
	ok "an empty tree dump is exit 2, not a clean verdict over nothing"
else
	bad "an empty tree dump did not report CANNOT RUN (rc=$VT_RC): $VT_OUT"
fi
# A SHAPE FAULT IS NOT A FINDING, the same discipline the access dump carries: a
# dump whose shape changed means the harness or the emission moved, and scoring
# that as a disagreement about PERMISSION would name the wrong defect.
printf 'sensor-reader\tpgfix.Reading\t0\tAllow\n' > "$WORK/malformed-tree.tsv"
verify_tree "$WORK/malformed-tree.tsv" "$PG/fixtures/policy.edn"
if [ "$VT_RC" -eq 2 ] && contains "$VT_OUT" "CANNOT RUN"; then
	ok "a tree dump line that is not five fields is exit 2, never a finding"
else
	bad "a malformed tree dump was scored as a verdict (rc=$VT_RC): $VT_OUT"
fi

section "STATE SUBSYSTEM TABLE — the axis no descriptor database carries"
# A state subsystem names a SOURCE of state, which nothing in the database, the
# mints or the registry can resolve — so the policy's own closed declaration is
# the only thing that says which exist, and this table is what a read path later
# narrows against.
STBASE="$WORK/st-base"
state_table_dump "$BASE" "$STBASE"
if [ "$STD_RC" -eq 0 ]; then
	ok "the emitted table compiles warning-free with NOTHING in scope, and its data reads"
else
	bad "the emitted state table did not compile or did not run (rc=$STD_RC): $STD_OUT"
fi
verify_state "$STD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VS_RC" -eq 0 ]; then
	ok "every row the emitted table holds agrees with the policy that declared it"
else
	bad "the baseline state table disagreed with its policy (rc=$VS_RC): $VS_OUT"
fi
if contains "$VS_OUT" "clean — " && ! contains "$VS_OUT" "clean — 0 row(s)"; then
	ok "the state oracle reports a non-zero row count"
else
	bad "the state oracle checked ZERO rows, or did not report a count at all: $VS_OUT"
fi
STATE_DUMP="$(cat "$STD_DUMP")"
# TOTALITY, on this axis. The policy declares three subsystems; one group names
# two of them and the other names none at all. A table carrying only the
# PERMITTED entries would hold two rows and look perfectly well-formed — and a
# group that receives nothing would then have no rows, which is exactly what a
# table nobody filled in looks like.
if contains "$STATE_DUMP" "$(printf 'sensor-reader\ttelemetry\ttrue')" &&
	contains "$STATE_DUMP" "$(printf 'sensor-reader\tdiagnostics\tfalse')" &&
	contains "$STATE_DUMP" "$(printf 'commander\tthermal\tfalse')"; then
	ok "the table is the CROSS PRODUCT — every group against every declared subsystem"
else
	bad "the state table is not total over the declared set: $STATE_DUMP"
fi
# THE GROUP THAT RECEIVES NOTHING IS PRESENT, not missing. `commander` names no
# subsystem at all, and its rows are what say so.
if [ "$(printf '%s\n' "$STATE_DUMP" | command grep -c '^commander	')" -eq 3 ] &&
	! contains "$STATE_DUMP" "$(printf 'commander\tdiagnostics\ttrue')"; then
	ok "a group that receives NO state carries a row per subsystem, each false"
else
	bad "the group that receives no state is not represented row by row: $STATE_DUMP"
fi
# THE UNIVERSE IS EMITTED TOO, which is what lets a consumer notice a row set
# that silently narrowed — a narrower table is still a well-formed one.
if command grep -q 'pub static STATE_SUBSYSTEMS: &\[&str\]' "$BASE/state_subsystems.rs" &&
	command grep -q '"diagnostics",' "$BASE/state_subsystems.rs"; then
	ok "the declared universe is emitted beside the rows"
else
	bad "the emitted table does not carry the universe its rows are total over"
fi

section "STATE SUBSYSTEM TABLE — a policy with no state axis, and an undeclared name"
# A policy need not use the axis, and the honest rendering is an EMPTY universe
# rather than a missing file. The oracle must then refuse to judge rather than
# report clean, which is what stops a vacuous table passing for a narrow one.
state_table_dump "$NESTED" "$WORK/st-nested"
if [ "$STD_RC" -eq 0 ]; then
	ok "a policy declaring no state axis still emits a compiling table"
else
	bad "the axis-free table did not compile (rc=$STD_RC): $STD_OUT"
fi
verify_state "$STD_DUMP" "$PG/fixtures/policy-nested.edn"
if [ "$VS_RC" -eq 2 ] && contains "$VS_OUT" "CANNOT RUN"; then
	ok "and the oracle REFUSES to judge it — an empty table is a fault, not a clean verdict"
else
	bad "an empty state table was reported as a verdict (rc=$VS_RC): $VS_OUT"
fi
generate "$PG" "$WORK/state-out" fixtures/refusal-policy-state.edn fixtures/db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "state-subsystem-not-declared"; then
	ok "a group naming a subsystem the policy does not declare is REFUSED, by name"
else
	bad "an undeclared subsystem was not refused (rc=$GEN_RC): $GEN_OUT"
fi

section "MUTATION 20 — a table of only the PERMITTED rows is caught"
# The totality half, and the reason it needs a case: dropping the denied rows
# leaves a smaller table that is still valid Rust, still consistent with every
# other artefact of the run, and indistinguishable from a policy that declared
# fewer subsystems — unless something re-derives the CROSS PRODUCT.
M20="$WORK/m20"
copy_tool "$M20"
mutate_file "$M20/src/protocol_gen/state_table.clj" \
	'             :entries (mapv (fn [s] {:subsystem s :permitted (contains? permitted s)})
                            declared)}))' \
	'             :entries (mapv (fn [s] {:subsystem s :permitted true})
                            (filterv permitted declared))}))' \
	|| bad "mutation 20 did not land"
generate "$M20" "$WORK/m20-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
state_table_dump "$WORK/m20-out" "$WORK/st-m20"
if [ "$STD_RC" -eq 0 ]; then
	ok "MUTANT: the narrower table is still valid, warning-free Rust"
else
	bad "the mutant table did not compile (rc=$STD_RC), so the red below is about rustc: $STD_OUT"
fi
verify_state "$STD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VS_RC" -eq 1 ] &&
	contains "$VS_OUT" "commander/diagnostics: the policy describes permitted=false, and the emitted table carries no row for it"; then
	ok "a table missing its denied rows is REFUSED, naming the row and what it should say"
else
	bad "a non-total state table was not caught (rc=$VS_RC): $VS_OUT"
fi
# CONTROL: every other artefact is UNAFFECTED on this same mutant, so the red
# above is attributable to the state emission and to nothing else.
verify "$WORK/m20-out"
if [ "$VER_RC" -eq 0 ]; then
	ok "CONTROL: the proto-and-mirror oracle calls that mutant CLEAN"
else
	bad "the mutant moved the schema or the mirror too (rc=$VER_RC): $VER_OUT"
fi
# THE NEIGHBOUR, on that same mutant.
generate "$M20" "$WORK/m20-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 20 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 21 — a subsystem withheld from a group's POLICY moves that group's table"
# The direction a tool mutation cannot ask, the same shape MUTATION 19 takes on
# the permission axis: `telemetry` is dropped from the sensor-reader group and
# nothing else changes.
M21="$WORK/m21"
copy_tool "$M21"
mutate_file "$M21/fixtures/policy.edn" \
	'   :state-subsystems ["telemetry" "thermal"]}' \
	'   :state-subsystems ["thermal"]}' \
	|| bad "mutation 21 did not land"
generate "$M21" "$WORK/m21-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — what follows is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
if [ -s "$BASE/state_subsystems.rs" ] && [ -s "$WORK/m21-out/state_subsystems.rs" ] &&
	! diff -q "$BASE/state_subsystems.rs" "$WORK/m21-out/state_subsystems.rs" > /dev/null 2>&1; then
	ok "MUTANT: withholding one subsystem CHANGES the emitted table's bytes"
else
	bad "withholding a subsystem left the emitted table byte-identical, or one of the two was never written"
fi
state_table_dump "$WORK/m21-out" "$WORK/st-m21"
M21_DUMP="$(cat "$STD_DUMP")"
if [ "$STD_RC" -eq 0 ] &&
	contains "$M21_DUMP" "$(printf 'sensor-reader\ttelemetry\tfalse')"; then
	ok "MUTANT: the withheld subsystem still has a ROW, and it now reads false"
else
	bad "the withheld subsystem did not turn into a false row (rc=$STD_RC): $M21_DUMP"
fi
verify_state "$STD_DUMP" "$M21/fixtures/policy.edn"
if [ "$VS_RC" -eq 0 ]; then
	ok "MUTANT: judged against its OWN policy the table is clean — the move is a policy effect"
else
	bad "the mutant table disagreed with the policy that produced it (rc=$VS_RC): $VS_OUT"
fi
verify_state "$STD_DUMP" "$PG/fixtures/policy.edn"
if [ "$VS_RC" -eq 1 ] &&
	contains "$VS_OUT" "sensor-reader/telemetry: the policy describes permitted=true, the emitted table holds permitted=false"; then
	ok "and against the UNMUTATED policy it is REFUSED, naming the row and both answers"
else
	bad "the moved subsystem was not caught (rc=$VS_RC): $VS_OUT"
fi

section "STATE TABLE VACUITY — an unreadable dump is a FAULT, not a pass"
printf 'sensor-reader\ttelemetry\n' > "$WORK/malformed-state.tsv"
verify_state "$WORK/malformed-state.tsv" "$PG/fixtures/policy.edn"
if [ "$VS_RC" -eq 2 ] && contains "$VS_OUT" "CANNOT RUN"; then
	ok "a state dump line that is not three fields is exit 2, never a finding"
else
	bad "a malformed state dump was scored as a verdict (rc=$VS_RC): $VS_OUT"
fi
printf 'sensor-reader\ttelemetry\tyes\n' > "$WORK/nonbool-state.tsv"
verify_state "$WORK/nonbool-state.tsv" "$PG/fixtures/policy.edn"
if [ "$VS_RC" -eq 2 ] && contains "$VS_OUT" "CANNOT RUN"; then
	ok "a state dump flag that is not a Rust bool is exit 2 too"
else
	bad "a non-bool state flag was scored as a verdict (rc=$VS_RC): $VS_OUT"
fi

section "SCHEMA VERSION — a fingerprint of the projection, in every emitted module"
# The constant a routing header can carry beside a message, so a destination can
# tell one group's projection from another's rather than decoding whatever lines
# up. Three properties, and every case here is a COMPARISON: an assertion that
# merely FOUND a number would be satisfied by a constant, which is exactly the
# emitter mutation 23 below produces.
SV_SENSOR="$(schema_version "$BASE/sensor-reader.rs")"
SV_COMMANDER="$(schema_version "$BASE/commander.rs")"
if [ -n "$SV_SENSOR" ] && [ -n "$SV_COMMANDER" ]; then
	ok "every emitted module declares a SCHEMA_VERSION"
else
	bad "an emitted module carries no SCHEMA_VERSION (sensor='$SV_SENSOR' commander='$SV_COMMANDER')"
fi
# THE VALUE, not the syntax: rustc already accepted both modules above under
# -D warnings, so a literal too wide for the `u32` it is typed as would have
# reddened there. What is left to check here is that the TRUNCATION holds.
if [ -n "$SV_SENSOR" ] && [ "$SV_SENSOR" -le 4294967295 ] &&
	[ -n "$SV_COMMANDER" ] && [ "$SV_COMMANDER" -le 4294967295 ]; then
	ok "both values sit inside the u32 range the constant is typed as"
else
	bad "a SCHEMA_VERSION is outside u32 (sensor='$SV_SENSOR' commander='$SV_COMMANDER')"
fi
# DISTINCTNESS, over two projections this fixture policy makes deliberately
# asymmetric — different messages, different fields, different directions.
if [ -n "$SV_SENSOR" ] && [ "$SV_SENSOR" != "$SV_COMMANDER" ]; then
	ok "two groups whose projections differ fingerprint DIFFERENTLY"
else
	bad "the two groups share a fingerprint ('$SV_SENSOR'), so a misroute between them is undetectable"
fi
# REPRODUCIBILITY ACROSS PROCESSES — the claim the unit suite structurally
# cannot make. Calling a pure function twice on one value in one JVM has no
# non-determinism to catch; $AGAIN was written by a SECOND generator run in its
# own JVM over the same inputs, which is where an environmental term shows.
#
# IT IS NOT THE ONLY CASE THAT WOULD CATCH ONE, and reading it as such is the
# error MUTATION 22 below spells out: the DETERMINISM section already diffs both
# emitted modules WHOLE across those same two runs. This narrows that verdict to
# the constant, so a red here names the line rather than the file.
AGAIN_SENSOR="$(schema_version "$AGAIN/sensor-reader.rs")"
AGAIN_COMMANDER="$(schema_version "$AGAIN/commander.rs")"
if [ -n "$SV_SENSOR" ] && [ -n "$AGAIN_SENSOR" ] &&
	[ -n "$SV_COMMANDER" ] && [ -n "$AGAIN_COMMANDER" ] &&
	[ "$SV_SENSOR" = "$AGAIN_SENSOR" ] &&
	[ "$SV_COMMANDER" = "$AGAIN_COMMANDER" ]; then
	ok "a second run in a second process fingerprints both groups identically"
else
	bad "the fingerprint moved between two runs over one input"
fi

section "MUTATION 22 — a fingerprint that reads anything ENVIRONMENTAL is caught"
# The failure this constant cannot survive. A consumer freshness-gates the
# emitted file, so a value that moves between two runs over one input reddens a
# gate with no change behind it. The mutation folds in the one term that is
# always environmental.
#
# WHAT THE CASE ABOVE ADDS IS ATTRIBUTION, NOT DETECTION — said precisely,
# because the obvious stronger claim is false. The DETERMINISM section already
# byte-compares both emitted modules across two generator processes, so it is
# strictly broader and would red on this same mutant. What it cannot do is NAME
# the constant: a whole-file diff says the bytes moved, and the reader is left
# to find which line. So this mutation is expected to red BOTH, and the case
# above earns its place by reporting the two values it compared.
M22="$WORK/m22"
copy_tool "$M22"
mutate_file "$M22/src/protocol_gen/rust_access.clj" \
	'(truncated-sha256 (canonical (fingerprint-input group)))' \
	'(truncated-sha256 (str (canonical (fingerprint-input group)) (System/nanoTime)))' \
	|| bad "mutation 22 did not land"
generate "$M22" "$WORK/m22-a" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
generate "$M22" "$WORK/m22-b" fixtures/policy.edn fixtures/db.edn
M22_A="$(schema_version "$WORK/m22-a/sensor-reader.rs")"
M22_B="$(schema_version "$WORK/m22-b/sensor-reader.rs")"
if [ -n "$M22_A" ] && [ -n "$M22_B" ] && [ "$M22_A" != "$M22_B" ]; then
	ok "MUTANT: two runs over one input now disagree — which is what the case above forbids"
else
	bad "the environmental mutation did not move the fingerprint, so the reproducibility case proves nothing ('$M22_A' vs '$M22_B')"
fi
# CONTROL: DISTINCTNESS survives this same mutant, so the two properties are two
# cases rather than one asserted twice — and mutation 23 below is the other half
# of that demonstration.
M22_A_COMMANDER="$(schema_version "$WORK/m22-a/commander.rs")"
if [ -n "$M22_A" ] && [ -n "$M22_A_COMMANDER" ] && [ "$M22_A" != "$M22_A_COMMANDER" ]; then
	ok "CONTROL: the mutant's two groups still differ — reproducibility is its own property"
else
	bad "the mutant lost distinctness too, so the red above is not attributable"
fi
# THE NEIGHBOUR, on that same mutant: a clause with nothing to do with the
# fingerprint must still refuse, or the case above could be satisfied by a
# mutation that broke the pass as a whole.
generate "$M22" "$WORK/m22-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 22 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 23 — a CONSTANT fingerprint is caught, and reproducibility cannot see it"
# The failure a reproducibility case is structurally BLIND to, which is why
# distinctness is a case of its own: a constant is perfectly reproducible, and a
# constant is exactly what this emitter degrades to the moment it stops reading
# the projection.
M23="$WORK/m23"
copy_tool "$M23"
mutate_file "$M23/src/protocol_gen/rust_access.clj" \
	'(truncated-sha256 (canonical (fingerprint-input group)))' \
	'(truncated-sha256 "one value for every group")' \
	|| bad "mutation 23 did not land"
generate "$M23" "$WORK/m23-a" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — the red below is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
M23_SENSOR="$(schema_version "$WORK/m23-a/sensor-reader.rs")"
M23_COMMANDER="$(schema_version "$WORK/m23-a/commander.rs")"
if [ -n "$M23_SENSOR" ] && [ "$M23_SENSOR" = "$M23_COMMANDER" ]; then
	ok "MUTANT: two different projections now share a fingerprint — which the case above forbids"
else
	bad "the constant mutation left the two groups distinct, so the distinctness case proves nothing ('$M23_SENSOR' vs '$M23_COMMANDER')"
fi
generate "$M23" "$WORK/m23-b" fixtures/policy.edn fixtures/db.edn
M23_B_SENSOR="$(schema_version "$WORK/m23-b/sensor-reader.rs")"
if [ -n "$M23_SENSOR" ] && [ -n "$M23_B_SENSOR" ] && [ "$M23_SENSOR" = "$M23_B_SENSOR" ]; then
	ok "CONTROL: the mutant is still perfectly REPRODUCIBLE — which is why that case cannot stand in for this one"
else
	bad "the constant mutant was not reproducible either, so it does not demonstrate what it is here for"
fi
generate "$M23" "$WORK/m23-neighbour" fixtures/refusal-policy-typo.edn fixtures/refusal-db.edn
if [ "$GEN_RC" -ne 0 ] && contains "$GEN_OUT" "field-not-in-message"; then
	ok "CONTROL: on that same mutant a NEIGHBOURING clause still refuses"
else
	bad "the neighbour stopped refusing too — mutation 23 broke more than its clause: $GEN_OUT"
fi

section "MUTATION 24 — a field withheld from a group's POLICY moves that group's fingerprint"
# The question a tool mutation cannot ask: does the fingerprint follow the
# POLICY? `value` is dropped from the sensor-reader grant and nothing else
# changes — so every granted MESSAGE id, emitted name and direction is exactly
# what it was, and a fingerprint taken over those alone could not see it. The
# group's `.proto` and its generated decoder both move; the constant must too,
# or a stale client passes the comparison it exists to fail.
M24="$WORK/m24"
copy_tool "$M24"
mutate_file "$M24/fixtures/policy.edn" \
	'             :fields #{"value" "mode" "history"}}]' \
	'             :fields #{"mode" "history"}}]' \
	|| bad "mutation 24 did not land"
generate "$M24" "$WORK/m24-out" fixtures/policy.edn fixtures/db.edn
if [ "$GEN_RC" -eq 0 ]; then
	ok "the mutant still GENERATES — what follows is a verdict, not a crash"
else
	bad "the mutant failed to generate (rc=$GEN_RC): $GEN_OUT"
fi
M24_SENSOR="$(schema_version "$WORK/m24-out/sensor-reader.rs")"
M24_COMMANDER="$(schema_version "$WORK/m24-out/commander.rs")"
if [ -n "$M24_SENSOR" ] && [ -n "$SV_SENSOR" ] && [ "$M24_SENSOR" != "$SV_SENSOR" ]; then
	ok "MUTANT: withholding one FIELD moves that group's fingerprint, though its granted messages did not change"
else
	bad "a withheld field left the fingerprint where it was ('$M24_SENSOR' vs '$SV_SENSOR') — a stale client would not be caught"
fi
# ATTRIBUTION: the other group's projection did not change, so its fingerprint
# must not have either — otherwise the value is following the RUN rather than
# the group, and every group would move whenever any policy line did.
if [ -n "$M24_COMMANDER" ] && [ -n "$SV_COMMANDER" ] && [ "$M24_COMMANDER" = "$SV_COMMANDER" ]; then
	ok "and the untouched group's fingerprint is unchanged — the value follows the GROUP, not the run"
else
	bad "the untouched group's fingerprint moved too ('$M24_COMMANDER' vs '$SV_COMMANDER')"
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
