# protocol-gen — descriptors plus a policy, projected into per-group `.proto`

A general-purpose generator. Its input is a **descriptor database** (proto
descriptors already parsed into EDN), a declarative **access policy**, a set of
**locally-minted** messages, and an assign-once **field-number registry**. Its
output is one `.proto` file per access group, one **Rust access module** per
group, a machine-readable **permission transcript**, a **nested permission
tree** in Rust, and a **subject group table** — all written from the same value
in the same run.

**TWO OF THOSE ARTEFACTS ARE EASILY CONFUSED, so they carry separate names.**
The flat EDN file `permissions.edn` is the **permission transcript**: a record
of what the generator was told. The nested Rust file `permission_tree.rs` is the
**permission tree**, and it is the only artefact this generator also calls a
**mirror** — the one a byte-level scanner walks. Two artefacts under one name
leave a reader sent to "the permission mirror" unable to tell which is meant, so
the word MIRROR is the tree's alone.

**THE IDENTIFIERS DO NOT MATCH THAT SPLIT, and a reader meets the mismatch
immediately.** `src/protocol_gen/mirror.clj` is the file that emits the
TRANSCRIPT; the namespace `protocol-gen.mirror`, its public function and every
emitted file name spell the word this prose reserves for the tree. They are not
renamed here because a consumer references them, and moving them is a
coordinated change on both sides rather than a prose edit.

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
descriptor database ─┐                                            ┌► .proto
locally-minted msgs ─┼─► numbering ─► projection ─► render ─► emit┤
field-number registry┘                    │                       └► transcript
access policy ───────────────────────────┘                        ├► .rs
                                          │                       ├► permission tree
                                          └──────────────────────►└► subject group table
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
| `src/protocol_gen/mirror.clj` | the flat permission TRANSCRIPT |
| `src/protocol_gen/permission_tree.clj` | the NESTED permission tree, in Rust |
| `src/protocol_gen/state_table.clj` | the SUBJECT GROUP table, in Rust |
| `src/protocol_gen/rust_access.clj` | the per-group Rust ACCESS module |
| `src/protocol_gen/rust_lit.clj` | quoting a name into emitted Rust |
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

The unresolvable-reference class is not hypothetical. A field whose type is an
enum declared INSIDE a message lands in it whenever the producing parser reads
enums at file level only while walking nested messages; this repository's parser
reads them at every level, and a survey of the committed database reports no
refusal of that class. Read what a survey reports from a RUN rather than from a
number here, which would rot on the next database.

**A SURVEY OF THE COMMITTED DATABASE IS NOT CLEAN, AND WHAT IT REFUSES IS MAP
FIELDS.** The producing parser records a map as `:type :map` carrying its
`:key-type` and `:value-type`, with no `:type-ref` — not as a repeated entry
MESSAGE — and `:map` is outside `db/known-types`, so every map-typed field in the
corpus is an `unknown-field-type` refusal. That is a fact about the corpus rather
than a failure: a survey is a REPORT, and such a refusal becomes a failure only
when a policy grants the field that carries it.

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
pub const SCHEMA_VERSION: u32;      // a fingerprint of this group's projection
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

**Why a type rather than the transcript, which already carries the same fact.**
The transcript is EDN: a consumer parses it at run time, and a typo in the key
it looks under is a nil rather than a compile error. Here `may this group send
message X?` is `Message::X.access().may_write()` — the compiler answers, and a
message the group was not granted has no variant to name.

**Message level and no lower.** proto3 field presence is absent from the
descriptor database by construction, so a per-field access surface would claim
a distinction its input cannot supply.

### `SCHEMA_VERSION` — the projection, as one comparable number

A group's emitted schema is a PROJECTION, so two groups from one run hold two
different decoders. `SCHEMA_VERSION` is a `u32` fingerprint of the projection
that produced this module, for a transport that wants to compare it at a
destination rather than hand one group's bytes to another group's decoder.

**IT IS READ, NEVER JUDGED, BY WHATEVER CARRIES IT.** The generator's claim is
exactly one: the number is a function of the projection. What a mismatch MEANS
— refuse, log, renegotiate — is the reading side's rule and is asserted nowhere
here. It is a hash and not an ordinal: newer is not larger, and two runs differ
or they do not.

**WHAT IT IS TAKEN OVER, and why the answer is neither `pr-str` nor a short
list of names.** The input is every fact the projection DECLARES: the group id
and package, the subject groups, and per granted message its id, emitted
name, origin, access, its fields' declared facts and its oneofs', and per
granted enum its members'. `protocol-gen.db`'s field, oneof and enum-value
schemas are OPEN on purpose, so a database may carry prose and interaction
metadata this generator never reads — none of it reaches emitted text, so none
of it is in the fingerprint, and those three key sets are DERIVED from their
schemas rather than listed twice.

**FOUR KEY SETS ARE NOT DERIVED, and saying only the sentence above would imply
they were.** The group, message and enum levels, plus the two stamps the field
set adds, are written out, because each key needs its own normalisation. Every
one of them can legally gain a member and fall outside the fingerprint in
silence, so each is defended by a test that derives BOTH sides — three from
their schemas, including the anonymous closed map the group schema declares for
an enum, and the stamps from a real projection diffed against its own input,
since `db/field` is open and no closed projected-field schema exists to read.

**THE FIELD AXIS IS THE ONE A NAME-ONLY FINGERPRINT WOULD MISS**, and it is the
one that decides what a decoder can express: two groups granted the same
messages under different field filters emit different `.proto` files and
generate different Rust types, while every message id, emitted name and
direction stays identical. A canary mutation withholds exactly one field from
one group's grant and requires the fingerprint to move — and requires the OTHER
group's not to.

**THE BIAS RUNS ONE WAY, DELIBERATELY.** The group id and package are hashed in
even though `GROUP` and `PACKAGE` carry them verbatim two lines above; that is
not a second home for those facts, since nothing can read either back out of a
hash. It is because over-covering costs a REFUSAL that a lockstep rebuild
clears, and under-covering costs a silent ACCEPT — two projections that differ
in a fact the fingerprint skipped would share a value, and a number that cannot
tell two decoders apart is worse than no number.

**ORDER AND ENCODING ARE IMPOSED, not inherited.** The projection lists
messages and enums in the policy's GRANT order, and a message's fields in the
source message's DECLARATION order — both authoring facts rather than facts
about the group, since the emitter writes fields in NUMBER order — so all three
are sorted here and a fingerprint that moved when an author reordered two
grants would report a change nobody made. Rendering is a sorted,
length-prefixed encoding written here rather than `pr-str`, whose map order is
ITERATION order; the map sort is reached through a field's `:constraints`,
which is carried from the database verbatim, and the length prefix is what
keeps `["a" "b"]` and `["a,sb"]` from sharing a rendering. A real number is
encoded from its IEEE bits rather than from `Double/toString`, whose output the
JDK has already changed for some values. A value the encoder has no rendering
for stops the run instead of falling back to `str`, which would fold an
object's identity — and so the machine — into the number.

**IT FINGERPRINTS THE PROJECTION AND NOT THE GENERATOR**, which is the residual
worth knowing beside the collision odds below, because it runs in the
silent-ACCEPT direction. Two peers whose policies agree but whose GENERATOR
versions differ compute the same number while holding decoders this tool
emitted differently — a change to how a type reference is rendered, or to which
validation options are emitted, moves the `.proto` and the Rust generated from
it without touching the projection. Folding an emitter version in would close
it; this tool declares none, and inventing one is a release decision rather
than something to bury in a hash.

**COLLISIONS, stated rather than hidden.** Thirty-two bits is not
collision-free and is not claimed to be. Over the handful of groups a policy
declares, the chance that any two collide is about n(n-1)/2 in 2^32 — for four
groups, six chances in four billion — and the cost of one is that the mismatch
between exactly those two groups goes undetected, not that anything decodes
wrongly. That is the trade a header-sized field buys; a policy at a scale where
it stopped being negligible wants a wider field, not a cleverer hash. **Nothing
here detects one**: the emitter sees one group at a time, and the run that
emits every group — where a collision would be visible — has no pass that looks
for it.

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

Each emitted module compiles as a library under
`rustc --edition 2021 --crate-type lib -D warnings`, warning-free, and the
canary asserts it. It also asserts the CLOSED half of that enum's claim, which
a green compile structurally cannot carry: a harness naming a message the
policy withheld must be REJECTED by rustc, by error code and by the missing
name, with a granted-message control beside it that compiles clean.

**IT IS NOT `rustfmt` CANONICAL, and that is deliberate rather than a gap.** An
earlier version of this paragraph claimed it was, on the strength of ONE module
that happened to pass — a false generalisation, and the measurement that
retires it is worth keeping: rustfmt collapses `pub const MESSAGES: […]` onto
one line whenever the collapsed form fits the line width, and it collapses
`pub enum Message {}` when the enum is empty. So whether a module is canonical
depends on how long its message NAMES are, not on anything the emitter decides.
Emitting the canonical form would mean reimplementing rustfmt's width
heuristics here — a copy of another tool's layout rule, which rots the next
time that tool changes its mind.

A consumer running `cargo fmt --check` over a tree containing a generated
module should exclude it, as it would for any generated code. Nothing is lost:
the property that matters is byte-identical re-emission, which the canary does
assert, across two runs and over every artefact the run writes.

## What the transcript carries that a `.proto` cannot

Two facts, and both matter to a consumer:

- **direction** — whether a group may READ a message or SEND it. Proto describes
  a shape, not who may send it, so the emitted schema is identical either way.
  The Rust access module above carries this same fact as a TYPE; the two are
  rendered from one projection in one run, so they are one fact seen twice
  rather than two claims that could disagree;
- **provenance** — the text says a field is number 7 and cannot say whether 7
  came from a descriptor or from the registry.

Both artefacts are derived from one projection in one run, so there is nothing
for the transcript to be wrong about that the schema could be right about. The
transcript is a record of what the generator was told, NOT an enforcement
mechanism: nothing at run time reads it and refuses a message.

**AND THE SCHEMA IS NOT THE MECHANISM EITHER.** The reading to refuse is *a
group cannot send what its schema cannot express, and THAT is the mechanism* —
it is the obvious one to reach for, and it is false. A schema constrains what an
HONEST client CONSTRUCTS. Bytes on the wire carry no trace of the generated code
that produced them, so a peer that ignores its own schema, or was never built
from one, is unconstrained by it — and a receiver that trusted the schema to
have filtered its input would be enforcing nothing at all.

Enforcement is a RECEIVE-side property, and what this generator contributes to
it is the NESTED TREE below, not this file: a receiver walks encoded bytes tag
by tag against that tree and refuses any tag the tree does not describe. That is
why the tree is TOTAL over the fields a source message declares, and why an
undescribed tag is a refusal there rather than a gap. The transcript is what
lets a reviewer check that what the run emitted is what the policy said.

## The NESTED permission tree — what a byte-level scanner walks

Beside the transcript the generator writes ONE file for the whole run,
`permission_tree.rs`: a `pub static` per group, plus the table that selects one.

```rust
pub static SENSOR_READER: &[PermissionNode] = &[
    PermissionNode::message(0, "pgfix.Reading", Permission::Allow, &[
        PermissionNode::leaf(3, "value", Permission::Inherit),
        PermissionNode::leaf(9, "sample_count", Permission::Deny),
        // … one child per field the SOURCE message declares …
    ]),
];

pub static GROUPS: &[(&str, &[PermissionNode])] = &[
    ("commander", COMMANDER),
    ("sensor-reader", SENSOR_READER),
];
```

**IT IS A FRAGMENT, NOT A MODULE.** It names no crate and declares no type: it
assumes `Permission` and `PermissionNode` are already in scope and is meant to
be `include!`d into a module that supplies them. It builds every node through
one of TWO constructors — `PermissionNode::message(tag, name, permission,
children)` and `PermissionNode::leaf(tag, name, permission)` — called in
`static` position, so both must be `const fn` and every tree is plain const
data, with no runtime construction and no allocation. There is deliberately no
third constructor and no general one taking a kind: a consumer that could build
a node whose kind and children disagree is exactly the state the marker exists
to make unrepresentable, and the arity does half that work at the type level —
a leaf has nowhere to put a child.
`Permission`'s variants are `Unspecified`, `Inherit`, `Allow` and `Deny`;
`Unspecified` is the zero value a consumer needs so a default-constructed node
is not silently a grant, and this generator never emits one.

**WHY IT IS NOT THE TRANSCRIPT UNDER ANOTHER NAME**, which is the first thing
to check before reading further. The transcript is a map of group → message →
field → number and provenance. A scanner holding a position in a message and a
tag it has just read needs the node for THAT tag under the node it is standing
on, and a flat map has no *under*. The two also carry disjoint facts: the
transcript carries NUMBER PROVENANCE and DIRECTION, which a scanner never
consults, and carries no permission axis at all; the tree carries a permission
per node and no provenance. Neither replaces the other, and the flat one is
unchanged.

**THE PERMISSION AXIS IS MESSAGE-GRAINED, because the policy is.** A grant
names a message, a direction and a FIELD FILTER; no field carries a grant of
its own. So a message root is `Allow`, a field the filter kept is `Inherit`,
and a field the filter dropped is `Deny`. A root carries tag `0` — not a legal
proto field number, so it cannot be confused with a field — and is selected by
NAME; everything below a root is selected by TAG.

**TOTALITY, and the exact form this shape can carry.** Every message node lists
one child per field its SOURCE message declares, granted or not, so a scanner
meeting a tag with no node knows the generator never described it rather than
wondering whether a field was dropped.

**EVERY NODE NAMES ITS KIND, and that is what makes totality ACTIONABLE.** An
empty `message` node declares no fields, so every tag inside it is undescribed
and a scanner must REFUSE it; only a `leaf` names bytes with no tags in them and
may be stepped over. The children alone cannot state that difference — the
fixture's own `pgfix.Start` is a granted message with no fields, and on child
count it is identical to the scalar beside it, so a scanner reading the leaf
rule for it would grant every tag a hostile peer smuggled inside, unread. The
kind is derived from the database's own field type: a message-typed field is
`message`, and a scalar, an enum, a string, bytes or any of those repeated is
`leaf`. A proto MAP has NO kind and is REFUSED — the refusal list below carries
why. A DENIED node keeps the kind its source type has; denial is terminal
through `Permission::Deny` and never through calling a message a leaf.

**A FIELD TYPE THIS GENERATOR CANNOT NAME IS REFUSED HERE TOO**, and it is the
one behaviour change a policy author can meet. `protocol-gen.projection` already
refuses an unnameable type on a GRANTED field; this tree names every field the
source declares, so a DENIED field carrying `:map`, `:group` or the producer's
`:unknown` fallback reaches the tree having been judged by nothing, and it has no
honest kind. It refuses `unknown-field-type`, naming the message and the field.

**A DENIED NODE IS TERMINAL, and that bounds the one disclosure this artefact
makes.** The tree names fields the group's `.proto` deliberately withholds —
that is what makes totality checkable — but nothing below a denial is emitted,
so it never names a field of a message the group holds no grant for, and never
names a message id the group's `.proto` does not already carry.

**FOUR REFUSALS, and only three of them are reachable from a policy** — one of
those three reachable from a DATABASE rather than from a policy at all.

- `permission-cycle` — a granted message-typed field whose expansion reaches a
  message already on its path. A static tree is finite and a cycle is not, so
  describing it to some arbitrary depth would be a tree that silently stops
  covering what it claims to cover. Without the clause the expansion runs out
  of stack; the canary drives exactly that.
- `unknown-field-type` — a field, GRANTED OR DENIED, whose type this generator
  cannot name and which therefore has no kind. It is the one refusal here a
  policy cannot cause on its own: the input that reaches it is a DATABASE
  carrying `:map`, `:group` or the producer's `:unknown` fallback on a field of
  a message some group was granted. `protocol-gen.constructs` owns this reason
  and raises it over granted fields one pass earlier; the tree raises the same
  reason because it names denied fields too, and a guess about whether such a
  field holds tagged bytes is exactly the claim the emitted data cannot support.

  **A MAP IS THE MEMBER OF THAT SET A READER IS LIKELIEST TO EXPECT SOMEWHERE
  ELSE.** The reading to refuse is that a descriptor models a map as a repeated
  entry MESSAGE, so one takes `message` and its entry's key and value are the
  children. A database records `:type :map` with its key and value types and no
  `:type-ref`, so nothing names an entry message to descend into. A map's
  payload does hold tagged fields, so `leaf` would tell a scanner to step over
  bytes it must walk; `message` would claim children this generator has nothing
  to build. Describing an entry's key and value TAGS is work this generator has
  not done, and the refusal names that where a kind would bury it.
- `name-collision` — two group ids that flatten onto one Rust static name
  (`:relay-a` and `:relay_a` both give `RELAY_A`). Emitting them would define
  one static twice, so a consumer's first symptom would name Rust rather than
  the policy.
- `grant-under-denial` — anything at all beneath a denied node. **NO POLICY CAN
  REACH IT**, because the expansion makes a denial terminal by construction, so
  it judges an empty population on every legal input. It is a defensive
  invariant over the generator's own output, in the shape
  `protocol-gen.numbering/assert-stamped!` already uses, and its ability to
  fire is proven by mutation and by nothing else.

**The predicate is `beneath a denial`, not `granted beneath a denial`**, and
deliberately the stronger of the two: a denial is terminal, so a grant below it
is unreachable AND a denial below it is an interior this generator undertook
not to disclose. The narrow reading would pass the second, which is exactly
what a defect in the expansion produces.

**IT IS NOT `rustfmt` CANONICAL**, for the reason the access module's section
below records. Two runs over one projection write identical bytes, which is the
property that matters where a consumer freshness-gates the file.

## The SUBJECT GROUP TABLE — the axis no descriptor database carries

The run also writes `subject_groups.rs`: which subject groups each group may
receive, as Rust data and nothing else.

```rust
pub static SUBJECT_GROUPS: &[&str] = &["diagnostics", "telemetry", "thermal"];

pub static GROUP_SUBJECT_GROUPS: &[(&str, &[(&str, bool)])] = &[
    ("commander",     &[("diagnostics", false), ("telemetry", false), ("thermal", false)]),
    ("sensor-reader", &[("diagnostics", false), ("telemetry", true),  ("thermal", true)]),
];
```

**WHAT A SUBJECT GROUP IS.** A named set of state SUBJECTS a consumer's own
producer emits — a screen, a service, a projection it publishes. It is NOT a
partition of the device state the descriptor database describes: that state is
not narrowed per group, and no row here thins it. The axis exists for the state
no descriptor carries at all.

**THE NAME IS NEW AND THE OLD ONE IS RETIRED.** This axis was `:state-subsystems`,
emitted as `state_subsystems.rs` carrying `STATE_SUBSYSTEMS` and
`GROUP_STATE_SUBSYSTEMS`, refused as `state-subsystem-not-declared`. Every one
of those names said "device subsystem", so a reader who met the key inferred a
per-subsystem thinning of device state that does not exist. The old spelling is
recorded here so it can be recognised, and it is current nowhere.

**WHY IT IS AN AXIS OF ITS OWN.** A grant names a MESSAGE and the projection
resolves it against the descriptor database. A subject group names an emitter of
state, which no descriptor database carries and nothing here can resolve — so
the policy itself is the only thing that can say which subject groups exist.
Neither the group's `.proto` nor its permission tree has anywhere to put that.

**THE POLICY GRAMMAR WAS EXTENDED FOR IT, minimally.** `:subject-groups` at the
policy's top level is the CLOSED SET, optional and non-empty when present;
`:subject-groups` on a group is a subset of it, optional, where an omitted key
means the empty set — the reading `:enums` already has. Both are checked for
distinct entries at LOAD. A group naming an id the policy does not declare is
refused `subject-group-not-declared`, at POLICY grain, because the declared set
is a top-level fact no single group can see.

**THE TOP-LEVEL DECLARATION IS WHAT MAKES TOTALITY POSSIBLE.** Without a set to
be total OVER, *this group receives none* and *this policy has no subject-group
axis* emit the same nothing, and a subject group dropped from a group is
indistinguishable from one nobody has declared yet. With it, the table is the
CROSS PRODUCT — one row per group per declared subject group, each carrying a
bool — so an absent row is not representable and **a group that receives nothing
has a row per subject group reading `false`** rather than no rows at all.

**IT IS DATA AND NOTHING ELSE.** A read path narrows against this table; the
narrowing is the consumer's, and a `const fn` doing it here would be a second
home for that rule. So the emission is two `pub static`s of primitives, no
items besides. The fragment names no crate, declares no type and assumes
nothing is in scope, which is why it is a SEPARATE file from the permission
tree — folding it in would add a third name that fragment's includer must
supply, or force this axis into a permission vocabulary it does not have
(`Inherit` means nothing about a subject group).

**A POLICY THAT DECLARES NO AXIS EMITS AN EMPTY UNIVERSE**, and therefore an
empty row set under every group — the group tuples are still all there. That is
the honest rendering, not a skipped file, and it is why the universe is emitted
beside the rows: a consumer checks its length rather than reading a short table
as a narrow one. The oracle refuses to JUDGE such a table (exit 2) rather than
reporting it clean, so a vacuous one cannot pass for a narrow one here either.

**THE EMITTING NAMESPACE KEEPS ITS NAME.** `src/protocol_gen/state_table.clj`
is named for the ARTEFACT it writes rather than for the file it writes, which is
this generator's convention: of the five namespaces that emit, only
`permission_tree.clj` shares a name with its output. Renaming it would have
created a convention the tree does not have.

**WHAT THIS FORK DID NOT DECIDE.** The subject-group NAMES in
`fixtures/policy.edn` are generic engineering words chosen to exercise the
generator. Real policy content is authored elsewhere; nothing here claims to
know what a deployment's subject groups are called.

## The fixtures and the canary

`fixtures/` holds data this repository owns — a fixture proto, the descriptor
database it describes, a mint, its registry, a two-group policy, a single-group
policy carrying every access DIRECTION, and the inputs the refusal cases need.
They live inside this tool's own directory: generation
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

`fixtures/policy-nested.edn` covers the axis BOTH of those structurally cannot:
a DENIED field whose type is a message with an interior. Every field the
two-group policy withholds is a scalar, so a defect that described the interior
of a denial would emit nothing extra there and the bytes would be identical to
a correct run. Its one group grants `pgfix.Command` a single field, which
denies two message-typed siblings — and a canary mutation shows the two-group
policy staying CLEAN on the same mutant, so the fixture is load-bearing rather
than decorative.

`fixtures/refusal-db-cycle.edn` plus `fixtures/refusal-policy-cycle.edn` reach
the cycle refusal, `fixtures/refusal-policy-group-name.edn` reaches the
colliding-static one, and `fixtures/refusal-policy-subject-group.edn` reaches
the undeclared-subject-group one. Both are ordinary, legal policies — the first emits a
perfectly good `.proto` — so each is driven with a fixture rather than a
mutation, and the mutation is what breaks its clause alone.

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
folded onto one direction, an emitted Rust module that is no longer
warning-free, a withheld message LEAKED into the access enum, a granted one
DROPPED from it, a nested permission tree that lists only its GRANTS instead of
every field the source declares, a tree that describes the interior of a
DENIAL, each of the tree's two policy-reachable refusals broken alone with the
other as its neighbouring control, a fixture policy withholding a field that
the emitted tree must then follow, a subject group table carrying only its
PERMITTED rows, a fixture policy withholding a subject group that the emitted
table must then follow, a projection fingerprint that folds in an ENVIRONMENTAL
term, one that collapses to a CONSTANT — each asserted CLEAN on the other's
case, so neither reads as covering it — and a fixture policy withholding a
FIELD that the fingerprint must then follow while the untouched group's stays
put. Read the list from the script's sections rather than
from a count here. Every case asserts an exact exit code and a
substring naming the finding; every absence probe carries a control that makes
it produce a hit; and each mutant is asserted still to RUN, so a red is a
verdict rather than a crash.

**The compile-fail cases prove a REFUSAL, and a refusal is the one claim a
green compile cannot carry.** The access module's central promise is that a
withheld message has no variant, so naming one cannot compile — and nothing
establishes a compile error except a compile REQUIRED to fail. The hazard there
is that such a case "passes" when compilation fails for ANY reason: a typo in
the harness, a module it cannot find, an unrelated denied lint. So the
assertion is the DIAGNOSTIC'S IDENTITY — rustc's own error code and the missing
name in its output — never a bare non-zero exit, and three controls stand
around it. The same harness shape naming a GRANTED message compiles. The
IDENTICAL harness text compiles against the group the policy DID grant that
message to, so what refuses is the grant and not the name. And a harness
pointed at a module that does not exist fails with a DIFFERENT diagnostic, so a
broken harness cannot read as a proven refusal. Two mutations drive the claim
both ways: leaking an ungranted message into the enum makes the refusal
disappear, and dropping a granted one makes the control stop compiling.

The diagnostic text is MEASURED under the toolchain image's `rustc` pin and
recorded in the script beside the cases. A later `rustc` that rewords it reds
those cases while every control stays green — which is what tells a
harness-maintenance fact from a policy regression.

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
sets. It is in the toolchain image; the pin is in `Dockerfile.base`.

`rustfmt` is deliberately NOT used, for TWO reasons and not one. It is outside
the image's minimal rustup profile, so a case asserting it would have to skip
when its tool was absent — and a skipping gate is the defect this suite exists
to catch, wearing a green. And the emitted text is not rustfmt-canonical
anyway; the section above carries the measurement and why chasing it would be
wrong.

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
some of its functions carry no docstring — `die`, `parse-args` and `read-edn`
are the ones — and it practises no arrow specs, so it is enrolled in each gate
the day it is clean for that gate.

A COUNT USED TO STAND HERE — "three of its sixteen" — and it rotted the first
time this file grew a mode, which is exactly the drift the namespace tally
above was already told not to repeat. The three NAMES survive the file growing
and the number does not, so the names are what is written; the gate cannot
report this root at all, since passing it an unenrolled path reports CANNOT RUN
over the four roots it does judge.

## Reading order

`src/protocol_gen/constructs.clj` first — the refusal pass carries the three
construct classes and the reason set every other pass reports through. Then
`src/protocol_gen/projection.clj`, which is where the policy is actually
applied, and `src/protocol_gen/numbering.clj`, which is why a number cannot
move. `verify/protocol_gen/verify.clj` is the oracle and deliberately shares no
code with any of them.
