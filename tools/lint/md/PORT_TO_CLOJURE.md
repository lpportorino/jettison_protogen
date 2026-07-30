# The markdown gate, ported from python3 to Clojure

The gate is now `lint-gate.md` in `tools/lint/src/lint_gate/md.clj`; the python
script and its JSON waiver store are deleted. This is the account of what moved,
what could not move byte-for-byte, and what the port exposed.

## 1. Verdicts, before and after

Both implementations were run BACK-TO-BACK over the same working tree, because
the tree is shared with another live worker and it moved three times during this
session (the code-span count climbed 3589 -> 3602 -> 3618 -> 3641 as other
markdown was committed). Any before/after taken minutes apart would have measured
that drift rather than the port, and the first baseline I took did exactly that —
I read a line number as a port defect before noticing HEAD had advanced.

| | python3 | Clojure |
|---|---|---|
| exit code | 0 | 0 |
| hand-authored .md | 49 | 49 |
| tracked .md (total) | 400 | 400 |
| generated/vendored excluded | 351 | 351 |
| rules / skills / agents / commands | 13 / 7 / 1 / 11 | 13 / 7 / 1 / 11 |
| frontmatter blocks parsed | 25 | 25 |
| code spans | 3641 | 3641 |
| path citations + links | 296 | 296 |
| gitignored-only citations | 6, same 6 paths, same 6 line numbers | same |
| live exemptions | 1 | 1 |

`diff` over both runs' stdout and stderr differed in exactly ONE place: two lines
of the NOT-COVERED block, where the python text says `see EXCLUDE_* above` and the
Clojure text names the `exclude-*` tables it actually has. Nothing else — not a
count, not a coordinate, not a verdict.

**Canary suite: 66 assertions green against the python gate, 68 green against the
Clojure gate.** The two added assertions are a new case (section 5); no existing
case was weakened, renamed away, or made less specific. The brief's "38-case" count
is wrong — the suite emits 66 PASS/FAIL labels across 33 labelled cases, and it did
so before I touched it.

**A second, stronger comparison, because a clean tree exercises almost nothing.**
The live run above proves the two agree on a corpus with ZERO findings, which
leaves every finding formatter, every line-number computation and the whole sort
order unmeasured. So I built one fixture tree that trips every clause at once —
unterminated and unparsable frontmatter, scalar/dead/match-all/blank `paths:`,
missing and copied sentinels, prose scope, a skill with `model:` and one with no
description, a version-pinned agent, a non-kebab name, an unmatched backtick run,
two mid-identifier folds, dead spans, a dead dirglob, a dead link, a doc-relative
and a suffix citation that must RESOLVE, a fenced block that must be skipped, a
double-delimiter span that must stay balanced, and an undecodable file — and ran
both gates over it:

- **23 findings each side.**
- Clause ids and `file:line` coordinates: **identical, in identical sort order.**
- Detail text: **identical for all 23 once the repr quote character is
  normalised**, except `unreadable-file` (see 2.4) and the FAIL trailer naming the
  waiver store (`exemptions.json` -> `exemptions.edn`, `retires_when` ->
  `:retires-when`), which is the intended conversion.

## 2. Where the port could NOT be behaviour-identical

Each of these was MEASURED, not reasoned about. Where a divergence exists, the
direction is stated.

### 2.1 Regex dialect — `(?U)` is load-bearing IN BOTH DIRECTIONS

Python's `re` is unicode-aware for `\s` and `\d`; Java's is ASCII-only unless
`UNICODE_CHARACTER_CLASS` is on. Measured directly:

| probe | python | Java default | Java `(?U)` |
|---|---|---|---|
| `[^\s]` against U+00A0 (NO-BREAK SPACE) | no match | **match** | no match |
| `\d` against U+0967 (DEVANAGARI ONE) | **match** | no match | match |

So a transliterated port would have been wrong twice, in opposite directions:

- `code-span-folded-at-joiner` would **over-report** — a code span folded next to a
  NO-BREAK SPACE would read as a joiner fold and manufacture a finding python never
  emitted.
- `model-pinned-version` would **under-report** — a model string carrying a
  non-ASCII digit would slip past a gate whose whole job is to catch a version
  where an alias belongs.

Every regex carrying `\s`, `\S` or `\d` therefore compiles with a leading `(?U)`:
`load-test-re`, `scope-prose-re`, `fold-joiner-re`, `cite-span-re`, `cite-link-re`,
and the model-digit probe. `kebab-re` and `cite-dirglob-re` use explicit ranges and
need none. Verified end-to-end as well as at the regex level: an agent declaring
`model: opus` + U+0967 now fires `model-pinned-version` on both gates, with
byte-identical detail text.

Two more dialect notes, both checked and both harmless here: `re-matches` requires
a whole-string match, which is what the python `^...$` patterns meant (python's `$`
additionally tolerates one trailing newline, and no input here has one — tracked
paths and single lines); and literal characters in the glob translator go through
`Pattern/quote` rather than a hand-written escape table, so a `.` or `+` in a path
cannot become a metacharacter.

### 2.2 Line splitting — the hazard the brief named was real, and it is avoided

The gate reports line numbers, so a splitting difference is a wrong finding
LOCATION. Three candidates and what each does:

- `clojure.string/split-lines` — **wrong twice over.** It splits on `\r` as well as
  `\n`, and it drops trailing empty fields.
- `(str/split s #"\n")` — **wrong once.** Default limit drops trailing empties, so a
  file ending in a blank line loses it and any later index shifts.
- `(str/split s #"\n" -1)` — **correct.** This is `split-lines*`, and it matches
  python's `str.split("\n")`, which is what the python gate used everywhere. It
  never used `str.splitlines()`, so the widest hazard was never in play.

Measured with a probe fixture: a CRLF file with a dead citation on line 5, and a
file containing U+2028 LINE SEPARATOR and U+0085 NEL mid-paragraph with a dead
citation on line 5. Both gates report line 5 for both. `splitlines()` would have
split on all three of `\r`, U+2028 and U+0085 and reported different numbers.

### 2.3 Date arithmetic — Java is STRICTER, and the contract already said so

`today` is a PARAMETER of `validate-exemptions!`, never `LocalDate/now` read
inside, so the expiry and horizon clauses can be pinned and made to go red on
purpose. `MD_GATE_TODAY` is the only way to move it, and **it announces itself on
stderr** — a hidden seam that can silently relax an expiry deadline is the bypass
this gate refuses everywhere else. The python version read the real clock, which is
why its three expiry fixtures were computed as offsets from `date.today()` and
would drift with it; the ported fixtures are literals against a pinned 2026-06-15.

The parsers differ, measured:

| input | python `date.fromisoformat` | Java `LocalDate/parse` |
|---|---|---|
| `2026-10-27` | accepts | accepts |
| `20261027` | **accepts** -> 2026-10-27 | rejects |
| `2026-W01-1` | **accepts** -> 2025-12-29 | rejects |
| `2026-1-1` | rejects | rejects |
| `2026-10-27T00:00:00` | rejects | rejects |

The port TIGHTENS this, in the direction the contract already declared: both the
waiver store's own prose and the gate's error message say `:expires` is ISO-8601
`YYYY-MM-DD`. Python accepted two forms that text forbade, and the second is worse
than merely lax — `2026-W01-1` resolved to a date in the PREVIOUS year, so an entry
written that way would have been refused as EXPIRED for a reason its author could
not have predicted from the message. No live entry uses either form, so nothing
changes today.

### 2.4 Two detail strings that differ on purpose

- **Quote character.** Python's `%r` quotes with `'...'`; Clojure's `pr-str` uses
  `"..."`. Nothing matches on it — an exemption's `:match` is a plain substring test
  against the whole detail, and the live entry's needle is a bare archive-internal
  path carrying no quote. (Quoting that needle here in a backticked span would make
  THIS file a second dead-path-citation, which the entry's own `:file` glob does not
  cover — the gate caught exactly that on the first run of this report, and it is a
  fair demonstration that the clause reaches new prose.) Verified: normalising the
  quote character makes all 23 union fixture details byte-identical.
- **`unreadable-file`.** Python reported `'utf-8' codec can't decode byte 0xff in
  position 51: invalid start byte`; Java reports
  `java.nio.charset.MalformedInputException: Input length = 1`. **Java's message is
  worse** — it names neither the byte nor the offset. It is not a silent
  difference: the clause, path and line are identical, and the decode itself is
  equally strict (a `CharsetDecoder` with `CodingErrorAction/REPORT`; the obvious
  `(String. bytes "UTF-8")` would have substituted U+FFFD and reported a mojibake
  file as CLEAN, which is the one thing this clause exists to prevent).

### 2.5 The repo root is resolved differently, and the new way is the safer one

Python derived `REPO` from `dirname` of its own file, four levels up. Clojure uses
the process working directory, which is the assumption `lint-gate.core` already
makes for its own config file and what `lint-md.mk` provides. This removes the
`.claude/rules/fork-isolation.md` trap outright: a tool that computes its root from
its own location silently retargets when copied, and the canary suite copies this
source in order to mutate it. `MD_GATE_ROOT` still overrides, and the suite still
passes it explicitly.

### 2.6 Truncation is by UTF-16 code unit, not code point

`clip` replaces python's slice. Python slices strings by code point; `subs` slices
by UTF-16 code unit, so a detail truncated at 50/60/70 characters can differ around
an astral character, and in principle can split a surrogate pair. Probed with an
emoji inside a cited path: both gates emit NOTHING, because the citation regex's
character class excludes it — so this is latent rather than observed. It affects
only the truncated tail of a diagnostic string, never a clause, coordinate or
verdict.

### 2.7 One shared limitation, neither fixed nor introduced

Both gates read `git ls-files` output raw, and git QUOTES non-ASCII paths by default
(`core.quotePath`). A tracked path outside ASCII would arrive as `"\303\251..."` and
match no glob. Every path in this repo is ASCII, so it is unobserved in both. I did
not fix it: it is a pre-existing behaviour and changing it silently would be a
behaviour change hiding inside a port.

### 2.8 Cost: the canary suite went from 2.0s to 58s

Measured on this machine with other workers' builds running:

| | python suite | Clojure suite |
|---|---|---|
| wall | 2.02s | 58.2s |
| user CPU | 1.45s | 67.0s |
| assertions | 66 | 68 |

The suite makes ~66 gate invocations, and each is a JVM start plus a compile of a
981-line namespace. The classpath is resolved ONCE (`clojure -Spath`, then
`java -cp`), and `-XX:TieredStopAtLevel=1` is passed — measured 6.33s -> 2.30s of
user CPU over three runs, because a process that compiles a namespace once and
exits never repays C2. That is the whole of the mitigation I applied. **What I did
NOT do, and it is the obvious next step: AOT-compile the namespace once per source
tree** (once for the real source, once per mutant) so the 66 runs load classes
instead of compiling forms. I judged that out of scope for a behaviour-preserving
port, and it is a real regression for anyone running `lint` on every push.

Reducing the invocation count was the alternative and I rejected it: every one of
them is a positive, a mutation or a control, and dropping any weakens the
attribution the suite exists for.

## 3. What was deleted, and the proof nothing references it

Deleted: the python gate `md_gate.py` and the JSON waiver store `exemptions.json`,
both from `tools/lint/md/`, plus that directory's `__pycache__`. `tools/lint/md/`
now holds `tools/lint/md/exemptions.edn`, the suite under
`tools/lint/md/test/md_gate_test.sh`, the previous worker's `tools/lint/md/PORT_REPORT.md`,
and this file.

The single exemption entry survived the JSON -> EDN conversion: every field value
compared byte-for-byte (`jq` against `clojure.edn`), with the one intended key
rename `retires_when` -> `:retires-when`. `:expires` is unchanged at 2026-10-27,
which sits exactly ON the 90-day horizon from the day it was written.

A tree-wide grep for both deleted filenames finds **no code, no makefile, no
workflow, no hook and no shell script** — only prose in two markdown reports. Which
brings the one thing this change owes:

### THE DELETION CREATES 5 DEAD CITATIONS, and I did not fix them

`git ls-files --cached` still lists a deleted-but-unstaged file, so the gate is
green right now for a reason that will not survive the commit. I proved what
happens by feeding the gate a tracked universe with the two paths removed —
read-only, no git mutating command — and diffing the findings. Exactly five appear:

| file | line | dead citation |
|---|---|---|
| FINAL_REPORT.md | 33 | the python gate |
| FINAL_REPORT.md | 248 | the python gate |
| tools/lint/md/PORT_REPORT.md | 13 | the python gate |
| tools/lint/md/PORT_REPORT.md | 14 | the JSON waiver store |
| tools/lint/md/PORT_REPORT.md | 215 | the JSON waiver store |

Re-derive rather than trust those coordinates: the second `FINAL_REPORT.md` line
moved from 210 to 248 while this report was being written, because another worker
edited that file. The probe is a `git ls-files` capture with the two paths filtered
out, fed to the gate through `MD_GATE_TRACKED_FROM`, diffed against the unfiltered
capture — read-only, and it needs no git mutating command.

(A sixth mention, at PORT_REPORT.md:23, is inside a span that starts with `python3 `
rather than the path, so the citation regex does not see it. It is stale prose all
the same.)

I left both files alone. `FINAL_REPORT.md` is forbidden to me; `PORT_REPORT.md` is
the previous worker's account and the brief says not to overwrite it. More to the
point, editing another worker's report to turn my own gate green is the shape of
move this repo's rules refuse, and half-fixing it (mine, not theirs) would hide the
pattern behind an inconsistent tree. **The disposition is a decision for the
committing session, and the right one is to REPAIR the five citations, not to exempt
them** — an exemption is a waiver for a citation that is correct but unresolvable
(the live entry names a member of a distributed tarball), whereas these are simply
stale pointers to a file that no longer exists, which is precisely what
`dead-path-citation` is for.

### AND THE CI JOB THAT RUNS THIS LANE INSTALLS NO CLOJURE

`.github/workflows/hygiene.yml` runs `lint-md-test` and `lint-md` in a job that
installs nothing and says so in its own comment ("needs nothing but the bash and
git already on the runner"), while `.github/workflows/lint.yml` installs
`actions/setup-java` (temurin 25) and `DeLaGuardo/setup-clojure` (cli 1.12.5.1654)
before it may run cljfmt at all. Those two readings are from the files; that the
runner image therefore lacks the Clojure CLI is an INFERENCE I cannot execute from
a checkout. The hygiene job owes the same two setup steps, and `hygiene.yml` is
forbidden to me. `lint-md.mk` fails loud with an install hint rather than skipping,
which is the correct half of it.

## 4. What the python was doing WRONG, that the port exposed

### 4.1 An internal crash exited 1 — the FINDINGS code

The python gate caught its own `HarnessError` and returned 2, but nothing trapped
an UNEXPECTED exception. A `raise` planted in `check_text` printed a traceback and
**exited 1**, measured on the deleted script. 1 is the code reserved for "this gate
reached a verdict about your markdown", so a broken gate and a caught defect were
indistinguishable from outside — the exact ERROR-wearing-a-FAIL's-colour confusion
the gate's own docstring forbids in the paragraph above where the bug lived.

The Clojure version traps at both entry points and exits 2, with the stack trace
printed in full. The brief required that trap, so this is a mandated behaviour
CHANGE rather than a preserved behaviour, and it is the one place the port
deliberately does not match. It also earned the suite's two new assertions:
`crash-trap` throws from a formatter that runs only while emitting a finding,
requires exit 2 and the message naming the trap, and then requires a fixture whose
own clause never reaches that formatter to still report exit 1 — so the red is
attributed to the crash rather than to a mutant broken everywhere.

That case is new because its absence is why the defect lived. A canary suite with
66 green assertions never asked the question.

### 4.2 Two ISO-8601 forms accepted against the store's own stated contract

See 2.3. Not a live defect; a latitude the contract text did not grant.

### 4.3 An observation, not a defect: the `#` in the link-skip list is dead

`LINK_SKIP` includes `"#"`, but `cite-link-re`'s capture group excludes `#`, so a
target can never begin with one — a pure-fragment link `[x](#y)` does not match the
pattern at all. Carried over verbatim rather than tidied, because removing it is a
behaviour question (what SHOULD a fragment-only link do?) and not a port question.

## 5. What I verified BY RUNNING versus BY READING

**By running**, all in this checkout:

- The python suite green (66) BEFORE any port work, so the starting point was known
  good and I was measuring my own port.
- The Clojure suite green (68), including all 48 clause positive/mutation/control
  assertions, the 5 non-vacuity floors, the 7 exemption-contract cases and the 4
  harness self-refusals.
- The suite green with `python3` and `python` MASKED on PATH by stubs that exit
  127 — the deliverable's independence from python is executed, not assumed.
- Both gates over the live tree, back-to-back, byte-diffed (section 1).
- Both gates over the all-clauses union fixture, 23 findings each, coordinates and
  sort order diffed (section 1).
- Both gates over the CRLF / U+2028 / U+0085 / NBSP / astral edge fixture
  (section 2.2).
- The regex and date-parser divergences, probed directly in both dialects
  (2.1, 2.3).
- The python crash exit code, by planting a `raise` in the restored script (4.1).
- `git` ABSENT: run with a PATH carrying only `java`, giving exit 2 and the install
  hint. `git` FAILING: root pointed outside any repository, giving exit 2 with
  git's own `fatal: not a git repository` text reproduced verbatim — stderr is
  captured to a temp file rather than a second pipe, so a git that writes more than
  a pipe buffer's worth of warnings cannot deadlock the gate.
- Usage refusals: no `--check` -> exit 2; `--check nosuch` -> exit 2 naming the
  known set.
- `clojure -M:lint-gate -m lint-gate.md` reaching `lint-gate.core/-main` with
  `["-m" "lint-gate.md"]` — which is why the makefile uses `-X`.
- Self-gating: `clj-kondo --cache false --fail-level warning` clean, `cljfmt check`
  clean, and the namespace-size ceiling passed at **981 code-LOC / 2 publics**
  against ceilings of 1219/66. It sits on the non-blocking DEGRADED watchlist, as
  a 981-line namespace should.
- The repo lanes that can see these files: `make -f lint.mk lint-sh` (51 scripts,
  14 payload blocks), `fmt-clj`, `lint-clj`, `make -f lint-md.mk lint-md`,
  `lint-md-test`, `lint-md-all`, `help`.
- Every backticked path this report cites, and every one in the new source and
  makefile: the gate itself is the check, and it reports clean over 49 files with
  this file in the corpus.

**By reading only** — and these are the claims a reviewer should not take on my
word:

- Anything about GitHub Actions. I cannot run a workflow. Section 3's CI paragraph
  is read off two workflow files plus one inference.
- That clj-kondo's finding classes I avoided (`:unused-binding`, `:shadowed-var`,
  `:unused-private-var`) are the complete set that could fire here — I ran the
  linter, so its verdict is executed, but I did not enumerate its rule set against
  my code by hand.
- The merge seam in section 6, which is read off `lint-gate.core`'s current
  worktree state — a file another worker is actively rewriting.

**One red exists in this shared tree and it is not mine**, which I mention so nobody
attributes it to this change: `make -f lint.mk lint-ns-size` fails on
`tools/renderer-gen/src/lvgl_codegen/palette_ladder.clj`, which is 1219 code-LOC at
HEAD (exactly its seeded ceiling) and 1258 in the working tree — another worker's
uncommitted growth, in a file I never opened for writing. It was two reds an hour
ago: `lint-clj-gate-test` was failing 5 cases against an in-flight rework of
`tools/lint/src/lint_gate/core.clj`, and that worker has since committed and it now
reports 37 green. Re-check both before reading anything into them; this tree does
not hold still.

## 6. Where this brief was WRONG, overstated, or impossible

**"invoke `clojure -M:lint-gate ...`" is impossible as written**, and this was the
one instruction I could not follow. The `:lint-gate` alias pins
`:main-opts ["-m" "lint-gate.core"]`, and the Clojure CLI CONCATENATES
command-line main-opts onto an alias's rather than overriding them — so
`clojure -M:lint-gate -m lint-gate.md` hands `["-m" "lint-gate.md"]` to
`lint-gate.core/-main` as ARGV and is refused as a usage error (measured; exit 3).
The brief's own escape hatch, "extend or mirror" `core.clj`'s dispatch, is closed
in the extend direction by the brief itself: `core.clj` is forbidden to me and owned
by another worker. So I mirrored — `lint-gate.md/-main` carries the same
`--check <name>` shape — and the makefile drives the namespace with
`clojure -X:lint-gate lint-gate.md/gate`, which ignores `:main-opts` and keeps ONE
home for the source path (the alias's `:extra-paths`, which is what puts this file
inside `LINT_CLJ_PATHS` in the first place). The alternative was adding a
`:lint-gate-md` alias, which the deps.edn restriction does not licence, or naming
the path a second time in the makefile via `-Sdeps`, which is a second source that
can drift. **`deps.edn` is untouched.**

**"38-case canary suite" is wrong.** It emits 66 PASS/FAIL labels across 33
labelled cases, before and independent of anything I did. I mention it only because
"every case must pass" needs a number a reviewer can check.

**"the same exemption count" is the wrong invariant to state as a port check.** The
count is 1 both sides, but that number is not evidence: with an empty finding set,
one STALE exemption is a hard failure and one MATCHING exemption is impossible, so
the live-tree comparison could only ever have confirmed the store parses. What
actually exercises the exemption machinery is the 7 fixture cases (complete entry
excuses; missing key; blank key; expired; beyond horizon; stale; narrow match), and
those are the numbers worth quoting.

**"clean, over the same file count" cannot hold after the deletion**, and the brief
does not anticipate that. The gate is clean now only because the deleted file is
still in the index; committing turns it into 5 findings. Section 3 records both
numbers rather than picking whichever one flatters the port.

**Two brief items were right in a way worth confirming**, because I would have got
them wrong unprompted: the warning that `grep -F` is line-oriented and vacuous for
a multi-line anchor (my mutation targets are all single-line, but the presence
checks use `case ... in *"$pat"*` which spans newlines correctly and has no line
semantics at all), and the warning about `clojure.core` shadowing — clj-kondo caught
`dec` shadowed by a local in `read-text` on the FIRST run, which is precisely the
finding no python lane would ever have reported and the entire argument for this
port in one line.

**One brief instruction I found under-specified and tightened.** "Break one clause
alone, its fixture goes clean, a neighbour's fixture still fires" does not say that
a mutation's REPLACEMENT may not contain the text it replaces. The natural way to
disable a Clojure predicate is to wrap it — `(not (alive? p))` ->
`(and false (not (alive? p)))` — and the harness's old-text-absent check refuses
that, correctly, because with the original still present a later occurrence count
reads 2 and no reader can tell which one the mutation meant. Two of my sixteen
mutations were first written as wrappers and both were caught by the helper rather
than by me. The constraint is now written down at `mutate`, and the fix is to break
the predicate by SUBSTITUTION.

## 7. The fact I needed and could not get

**Whether `.github/workflows/hygiene.yml`'s runner has the Clojure CLI, and
therefore whether this change reds CI on arrival.** A fork cannot run GitHub
Actions, and the answer is not in the repository: it is a property of the
`ubuntu-latest` image. Everything I could substitute for it is circumstantial —
`lint.yml` installs the CLI explicitly before using it, and the hygiene job's own
comment enumerates what it relies on and does not name Clojure. The consequence is
asymmetric and that is why it matters: if I am right, the job fails at the first
`clojure` invocation and the fix is two setup steps; if I am wrong, the change is
inert. Either way the committing session should add the steps, because a lane that
depends on an undeclared tool is one runner-image change away from silently
becoming the thing this repo calls a gate that passes because its tool is absent.

The datum that would settle it is one push, and it will be lacking next time too
unless the hygiene job declares its toolchain the way `lint.yml` does.

## 8. Files

Created:

- `tools/lint/src/lint_gate/md.clj` — the gate, namespace `lint-gate.md`.
- `tools/lint/md/exemptions.edn` — the waiver store, one live entry, prose in
  `;;` comments rather than a `_contract` pseudo-key.
- this file.

Modified:

- `lint-md.mk` — `clojure -X:lint-gate lint-gate.md/gate`; the `command -v` guard
  now names `clojure`; the syntax floor is gone because `lint-clj` and `fmt-clj`
  reach this source and the suite compiles it 66 times.
- `tools/lint/md/test/md_gate_test.sh` — `java -cp` invocation off a
  once-resolved classpath, pure-bash mutation with a two-sided landed-proof, EDN
  fixtures, a pinned clock, and the crash-trap case.

Deleted: the python gate and the JSON waiver store, both under `tools/lint/md/`.
