# protocol-gen — descriptors plus a policy, projected into per-group `.proto`

A general-purpose generator. Its input is a **descriptor database** (proto
descriptors already parsed into EDN), a declarative **access policy**, a set of
**locally-minted** messages, and an assign-once **field-number registry**. Its
output is one `.proto` file per access group, one **Rust access module** per
group, and a machine-readable **permission mirror** — all three written from
the same value in the same run.

The per-group projection is one PROPERTY of that generator, not its purpose. A
group's emitted schema simply does not NAME what the policy did not grant, so a
decoder generated from it cannot express a forbidden message. That falls out of
projecting; it is not a separate feature, and it survives a careless consumer in
a way an annotation a consumer must honour would not.

## The one invariant everything else is arranged around

**Every emitted field carries the wire number from the descriptor, or from the
assign-once registry. No number is ever derived from position.**

If numbers came from position, dropping one grant would renumber every field
after it and a policy edit would silently become a wire break across every
group. Nothing would catch it: each group's schema stays internally consistent,
so it compiles, it round-trips against itself, and every check that does not
compare two groups passes.

It is made unrepresentable rather than discouraged, in four places:

- the emitter takes no index and creates none — `map` and `sort-by`, never
  `map-indexed` — and emits fields in NUMBER order, so layout is a function of
  the wire contract rather than of the order anything was built in;
- a minted message's declaration schema is CLOSED and has no `:number` key, so
  a number cannot be written into one even deliberately;
- the registry's stamping pass THROWS on an unpinned field rather than
  inventing one, and growing the registry is a separate command;
- every field carries `:number-source`, and `assert-stamped!` refuses anything
  that is not `:descriptor` or `:registry` before emission.

The canary drives the mutation that breaks it — numbering by index — and the
oracle reds.

## The pipeline

```text
descriptor database ─┐
locally-minted msgs ─┼─► numbering ─► projection ─► render ─► emit ─► .proto
field-number registry┘                    │                        ├► mirror
access policy ───────────────────────────┘                        └► .rs
```

| namespace | what it owns |
|---|---|
| `src/protocol_gen/db.clj` | reading and validating a descriptor database |
| `src/protocol_gen/constructs.clj` | the REFUSAL pass and the closed reason set |
| `src/protocol_gen/numbering.clj` | the assign-once field-number registry |
| `src/protocol_gen/policy.clj` | the access policy's shape |
| `src/protocol_gen/projection.clj` | the JOIN, where the policy is applied |
| `src/protocol_gen/constraints.clj` | validation constraints back out as options |
| `src/protocol_gen/render.clj` | the one place a type reference becomes a name |
| `src/protocol_gen/emit.clj` | resolved projection to `.proto` text |
| `src/protocol_gen/mirror.clj` | the permission mirror |
| `src/protocol_gen/rust_access.clj` | the per-group Rust ACCESS module |
| `src/protocol_gen/core.clj` | the command line |
| `verify/protocol_gen/verify.clj` | the INDEPENDENT oracle (its own source root) |

## Commands

```sh
make protocol-gen-survey     # what a database holds, and what cannot be emitted
make protocol-gen-generate   # emit the fixture groups (OUT=<dir> to choose where)
make protocol-gen-test       # the unit suite, with the malli specs armed
make protocol-gen-canary     # drive the generator and prove it can FAIL
make protocol-gen-check      # both of the above
```

There is no lint target here. This tree's Clojure is formatted and linted by the
repository-wide lanes — `make -f lint.mk fmt-clj` and `make -f lint.mk lint-clj`
— which reach it through `LINT_CLJ_PATHS`.

`make protocol-gen-survey DB=<path>` points the survey at another database; it
defaults to this repository's committed one.

The CLI itself takes closed flags and defaults no path. A generator that
invented a destination would write somewhere nobody checks, and one that
invented an input would read something nobody chose.

## What a locally-minted declaration can express

A mint file is `{id declaration}`, and a declaration is a MESSAGE or an ENUM,
tagged `:kind` so the two are told apart by a value rather than by which key a
reader happens to test for first. An untagged or unknown-kind declaration is
refused at LOAD.

- **A message** declares `:name`, `:fields`, and optionally `:oneofs`. A field
  declares no number and the schema HAS NO KEY FOR ONE, so the assign-once
  registry is the only thing that can supply one.
- **A oneof** declares `:name`, `:required`, and its members BY FIELD NAME. A
  database oneof names its members by NUMBER — the wire identity — and a mint
  has no numbers, so a name is the only identifier available. The stamping pass
  turns each name into the number the registry supplied for that field, against
  the message's OWN declared fields rather than against the registry entry: a
  retired pin keeps its number for ever, so resolving against the entry would
  hand a dead name a live number.
- **An enum** declares `:name` and `:values`, each value carrying its own
  number. The registry has no part in this and could not: its value schema
  excludes 0, and proto3 requires an enum member numbered 0. The schema instead
  supplies the floor protoc would have — a member numbered 0, distinct numbers,
  distinct names — because a database comes from protoc and cannot carry an enum
  proto3 rejects, while a mint has no such upstream.

Both kinds are merged into the UNIVERSE, so a minted field referring to a minted
enum resolves, and a minted enum is granted through the policy's `:enums` list
exactly the way a descriptor enum is. Nothing in the policy says which kind of
enum it is naming.

Two ways a mint can be wrong that would otherwise be approximated silently, and
both are refused by name: a oneof member naming no field of its message
(`oneof-member-absent`), and a field claimed by more than one oneof
(`oneof-member-shared` — a proto field belongs to at most one, so the losing
block would emit a member short, or vanish, and still compile).

## What the emitter can and cannot express

Three classes, and only the first two can ever be caught here.

**1. Detectable in the database or the mint, refused by name.** A type the
generator has no emission for (including the producing parser's own `:unknown`
fallback); a `:type-ref` that resolves to nothing; a field number outside
1..2^29-1 or inside protoc's reserved 19000..19999; two fields sharing a number;
a oneof naming a member the message does not carry; and — reachable only from a
MINT, because protoc's own descriptors give each field one oneof index — a field
claimed by two oneofs at once.

The unresolvable-reference class is not hypothetical, and this repository's own
committed database used to demonstrate it: every refusal `make
protocol-gen-survey` reported was a field whose type is an enum declared INSIDE
a message, because the producing parser walked nested messages while reading
enums at file level only. It reads them at every level now, and that survey is
clean. Read the count from a run rather than from a number here, which would rot
on the next database.

A clean survey there is a fact about THIS corpus, not a property of the check. A
map field still lands in this class whenever one reaches the database, because
the entry message is a nested type the parser deliberately drops; no map-typed
field is inside the parser's current file filter, which is why the survey does
not show one.

**2. Detectable only against the policy.** A granted field whose type is a
message or enum the policy did not also grant; a field name a grant asks for
that the message does not carry; two source ids that flatten onto one emitted
name; a duplicated group id or grant.

These two classes OVERLAP on the generation path, and the canary says so rather
than pretending otherwise: a reference that resolves to nothing also names a
type no policy could have granted, so class 1 wins only by running first.

**3. Not detectable at all, because the database never carries the fact.** These
are the ones worth knowing, because no check here can ever find them: explicit
field presence, proto2 defaults, `reserved` ranges and names, extension ranges,
`allow_alias`, services, `json_name` and every file-level option are absent from
the database entirely.

Two entries used to HEAD that list and have moved out of it, which is worth
recording because the retired claim is the plausible one to carry forward. It
said that `sint32`/`sint64` and the `fixed`/`sfixed` family were folded onto
`int32`/`int64`/`uint32`/`uint64` by the producing parser, and that proto2
groups arrived as `:message` — so a schema re-emitted from the database decoded
the same bytes differently with nothing recording it. The parser now records one
keyword per descriptor type: the six distinctly-encoded integers are emittable
scalars here and are emitted as themselves, and `:group` is deliberately NOT
emittable, so it lands in class 1 as a named refusal rather than as a silent
substitution of a length-delimited field for a tag-delimited one.

So the honest scope of a green run is "nothing in class 1 or 2", never "the
emitted schema is wire-identical to the source". A generator cannot refuse what
it cannot see; what it can do is refuse to pretend otherwise.

## The Rust access module — the same fact, as a type

Beside each group's `<group>.proto` the generator writes `<group>.rs`: the
DIRECTION half of that group's grants, as a closed Rust surface.

```rust
pub const GROUP: &str;              // the group's policy id
pub const PACKAGE: &str;            // the package its .proto declares
pub enum Access { Read, Write, ReadWrite }
impl Access { pub const fn may_read(self) -> bool; pub const fn may_write(self) -> bool; }
pub enum Message { /* one variant per granted message, and nothing else */ }
impl Message {
    pub const fn source_id(self) -> &'static str;   // the id the policy grants it by
    pub const fn proto_name(self) -> &'static str;  // its name in this group's .proto
    pub const fn access(self) -> Access;
}
pub const MESSAGES: [Message; N];   // every granted message, in source-id order
```

**IT IS NOT A MESSAGE EMITTER, and building one is the mistake this file
exists to head off.** The shapes already reach Rust through the group's
`.proto` and a consumer's prost pipeline, where a message the policy withheld
is simply not in the generated module — so a forbidden message is ALREADY a
compile error there. A second emitter for those shapes would give one wire
contract two sources. What a `.proto` structurally cannot carry is direction,
and that is the whole of what this module adds.

**Why a type rather than the mirror, which already carries the same fact.** The
mirror is EDN: a consumer parses it at run time, and a typo in the key it looks
under is a nil rather than a compile error. Here `may this group send message
X?` is `Message::X.access().may_write()` — the compiler answers, and a message
the group was not granted has no variant to name.

**Message level and no lower.** proto3 field presence is absent from the
descriptor database by construction, so a per-field access surface would claim
a distinction its input cannot supply.

**Each variant is the message's `.proto` name VERBATIM** — `pgfix_Command`, not
`PgfixCommand`. A camel-cased variant would be a SECOND name for a message the
`.proto` emission has already named, and camel-casing is not injective:
`pgfix_command` and `pgfix_Command` are two distinct `.proto` names — distinct
enough that the projection's own collision check passes them — that collide on
one variant. The module carries `#[allow(non_camel_case_types)]` for exactly
that reason. The one input class
this cannot express — a message whose emitted name is a word Rust reserves — is
named in the namespace docstring and is caught by rustc rather than by a
hand-kept copy of Rust's keyword list, which would rot silently.

Measured at the pinned toolchain's Rust: each emitted module compiles as a
library under `rustc --edition 2021 -D warnings`, and is already
`rustfmt --check` canonical. The compile is asserted by the canary; the
formatting is not, because `rustfmt` is outside the image's minimal rustup
profile and a gate that skipped when its tool was absent would be worse than no
gate.

## What the mirror carries that a `.proto` cannot

Two facts, and both matter to a consumer:

- **direction** — whether a group may READ a message or SEND it. Proto describes
  a shape, not who may send it, so the emitted schema is identical either way.
  The Rust access module above carries this same fact as a TYPE; the two are
  rendered from one projection in one run, so they are one fact seen twice
  rather than two claims that could disagree;
- **provenance** — the text says a field is number 7 and cannot say whether 7
  came from a descriptor or from the registry.

Both artefacts are derived from one projection in one run, so there is nothing
for the mirror to be wrong about that the schema could be right about. The
mirror is a record of what the generator was told, NOT an enforcement mechanism:
a group cannot send what its schema cannot express, and that is the mechanism.

## The fixtures and the canary

`fixtures/` holds data this repository owns — a fixture proto, the descriptor
database it describes, a mint, its registry, a two-group policy, a single-group
policy carrying every access DIRECTION, and the inputs the refusal cases need. They live inside this tool's own directory: generation
walks the proto source tree at the repository root, so nothing here is an input
to a shipped generation leg and nothing here reaches a published binding
repository.

The fixture field numbers are deliberately non-contiguous and do not start at 1.
A fixture numbered 1..n in order could not tell a correct generator from one
numbering by position; this one can. The minted `pgfix.Notice` carries that
further: its registry pins disagree with its declaration ORDER as well as with
1..n, and its oneof's two members are pinned so that number order and
declaration order are opposites — so an emitter listing members as declared is
caught by the same fixture that catches one numbering by position.

One emitted file therefore carries all four combinations that matter: a
descriptor message with a descriptor oneof, a minted message with a minted
oneof, a descriptor enum, and a minted one.

`fixtures/policy-directions.edn` covers the axis the two-group policy
structurally cannot. Its groups are read-only and write-only respectively, and
the asymmetry BETWEEN them is what several canary cases are about — so the
combination it has no instance of is a grant that is BOTH. A single group with
one grant of each direction supplies it, and a canary mutation folding
read-and-write onto one direction shows what that buys: the two-group policy
stays CLEAN on that mutant, and only the directions policy reds.

`canary/protocol_gen_canary.sh` is not a demo. It drives the real generator in
both directions and FAILS when the generator is broken, proving every failure it
covers by MUTATION — a wrong field number, a dropped grant emitting a
silently-allowed field, a construct that cannot be expressed being approximated
instead of refused, and, on the minted side, a oneof flattened into free fields,
a oneof losing its `required`, an enum that stops resolving, an enum renumbered
by position, each of the two minted-oneof refusals broken alone with the other
as its neighbouring control, a flipped access direction, a read-and-write grant
folded onto one direction, and an emitted Rust module that is no longer
warning-free. Read the list from the script's sections
rather than from a count here. Every case asserts an exact exit code and a
substring naming the finding; every absence probe carries a control that makes
it produce a hit; and each mutant is asserted still to RUN, so a red is a
verdict rather than a crash.

**Two of those cases assert what the ORACLE CANNOT SEE, and they are the reason
the minted-construct cases read the emitted TEXT rather than the descriptor.**
The oracle compares fields — number, type, repeated, constrained — and an enum's
NAME. It never reads a field's oneof index nor an enum's members, so a mutant
that flattens a oneof into free fields, or renumbers an enum's members, is CLEAN
by its verdict. Both are asserted clean on their mutants, so a later reader
cannot mistake that blindness for coverage.

It needs `clojure`, `protoc` and `rustc`, and HARD-FAILS when any is missing
rather than skipping. protoc resolves the emitted files' validation import out
of the committed descriptor set, so the suite needs no network and no
container.

**`rustc` is a REQUIREMENT and not a preference**, because the claims that
depend on it — that each emitted access module is valid, warning-free Rust, and
that the direction its API ANSWERS is the direction the policy granted — are
not true without it, which is the test `.claude/rules/gate-enforcement.md` §4
sets. It is in the toolchain image; the pin is in `Dockerfile.base`. `rustfmt`
is deliberately NOT used: the emitted text is `rustfmt --check` canonical
today, but rustfmt is outside the image's minimal rustup profile, so a case
asserting it would have to skip when its tool was absent — and a skipping gate
is the defect this suite exists to catch, wearing a green.

## How this tree is gated

**Its Clojure rides the aggregate the pre-push hook runs.**
`tools/protocol-gen/src`, `tools/protocol-gen/test` and
`tools/protocol-gen/verify` are in `LINT_CLJ_PATHS`, so every Clojure lane —
cljfmt, clj-kondo at the zero-warning floor, and the structural `ns-size` and
`fn-size` ceilings — reaches this tree the way it reaches every other one, and
`make -f lint.mk audit-clj-paths` no longer reports it. The two lanes that need
this tool's OWN `deps.edn` aliases, `protocol-gen-test` and
`protocol-gen-canary`, are prerequisites of `lint.mk`'s `lint-lanes`.

**Two of the three declared-scope gates cover the source root; the third takes
namespaces and covers one.** `docstrings` and `spec-shape` read enrolled ROOTS
from `tools/lint/gates.edn` and `tools/protocol-gen/src` is enrolled in both.
`spec-presence` enrols NAMESPACES, not roots, and its list only ever holds
namespaces measured at 100% presence — `protocol-gen.emit` is the only one of
this tree's that is, so it is the only one enrolled. The rest are named in
`tools/lint/gates.edn` with their measured fractions; the way to enrol one is to
write its missing specs.

A COUNT OF THEM USED TO STAND HERE — "of this tree's ten" — and it rotted the
first time this tree grew a namespace, which is exactly the drift
`.claude/rules/claude-md-policy.md` bans. Derive it instead; the gate takes
paths, so this tree reports its own:

```sh
clojure -M:lint-gate --check spec-presence tools/protocol-gen/src
```

`protocol-gen.rust-access` is the newest of the unenrolled, and is deliberately
the same shape `protocol-gen.mirror` is: its public entry point carries an
`m/=>` and its private renderers do not. Enrolling it means writing those, not
widening the list.

**`tools/protocol-gen/verify` is enrolled nowhere.** It is not a scope decision:
three of its sixteen functions carry no docstring and it practises no arrow
specs, so it is enrolled in each gate the day it is clean for that gate.

## Reading order

`src/protocol_gen/constructs.clj` first — the refusal pass carries the three
construct classes and the reason set every other pass reports through. Then
`src/protocol_gen/projection.clj`, which is where the policy is actually
applied, and `src/protocol_gen/numbering.clj`, which is why a number cannot
move. `verify/protocol_gen/verify.clj` is the oracle and deliberately shares no
code with any of them.
