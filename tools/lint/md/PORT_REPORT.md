# Markdown quality gates — what landed, what I refused, and where the brief was wrong

Owner: `gate-port` markdown subagent. Owns `tools/lint/md/**` and `lint-md.mk`.

Read §2.1 and §6 first if you read only two things: §2.1 is the gate's first live
catch and the one action this hands back, and §6 is where the brief turned out to
be wrong.

## 0. WHAT LANDED

| file | what it is |
|---|---|
| the python `md_gate` script (since ported to Clojure) | the gate — 17 clauses over hand-authored markdown |
| its JSON waiver store (since converted to EDN) | the waiver store, one live entry |
| `tools/lint/md/test/md_gate_test.sh` | the canary suite — 66 assertions |
| `lint-md.mk` | `lint-md`, `lint-md-test`, `lint-md-all`, `help` |

State on this tree, measured by running it: **clean over every hand-authored file
except two dead pointers a co-tenant's in-flight rename created minutes ago — see
§2.1, which is the gate's first live catch.** One exemption live, zero stale.

The corpus counts move with every commit in this session, so read them from the
gate rather than from here: running the gate prints the file,
rule, skill, agent, command, frontmatter, code-span and citation counts on every
run. At the moment of writing it judged 49 hand-authored files (351
generated/vendored excluded by declared provenance), 3544 code spans and 295
citations.

**Language: python3, stdlib only.** The reasoning is at the top of the gate.
Short version: `tools/wire_contract_check.py` is the existing precedent and
`lint.mk` records why it works (stdlib python3 runs on a plain runner, in the
uber container, and in the pre-push hook alike); a `.clj` would need a `deps.edn`
alias I do not own and would land outside `LINT_CLJ_PATHS`, showing up in
`make -f lint.mk audit-clj-paths` as ungated debt; bash+awk cannot carry a YAML
subset parser plus CommonMark delimiter matching legibly.

**The residual the brief asked me to state out loud:** nothing in this repo lints
python. Two mitigations, neither a linter. `python3 -m py_compile` rides
`lint-md-test`, matching the precedent the `lint-clj-gate-test` recipe set in
this same session. And the canary suite is a `.sh`, so `lint-sh` discovers it
automatically (`git ls-files --cached --others --exclude-standard '*.sh'`) and
parse-checks it — verified: `lint-sh` reports 49 scripts and exits 0 with it
present.

## 1. THE CLAUSES — known-bad input, exact message, mutation, control

Every row below was produced by RUNNING the suite. The mutation column is the
production expression broken to attribute the red; in each case the same fixture
then goes clean (proving the red came from that clause) while a neighbouring
clause's fixture still fires on the same mutant (proving the mutation was
surgical, not a blanket disable).

### 1.1 Tier and frontmatter shape — `.claude/{rules,skills,agents,commands}`

| clause | known-bad input | message (verbatim, trimmed) |
|---|---|---|
| `fm-unterminated` | rule opening `---` with no closing `---` | "frontmatter opens with `---` and never closes; the harness reads the whole file as YAML and every other clause here is blind to it" |
| `fm-unparsed` | frontmatter with a folded continuation line carrying no key | "frontmatter line outside the supported subset … — UNJUDGED, not accepted" |
| `paths-not-list` | `paths: src/**` as a bare scalar | "`paths:` must be a YAML list of globs; got the bare scalar 'src/\*\*'" |
| `paths-glob-dead` | a `paths:` list naming a tree that does not exist | "glob 'no/such/tree/\*\*' matches ZERO tracked files — the rule can never load" |
| `paths-match-all` | `paths:` listing `**/*` | "matches everything — that IS an unscoped rule; omit `paths:` instead" |
| `load-test-missing` | path-scoped rule with no sentinel | "path-scoped rule must embed `<!-- LOAD-TEST: no-sentinel -->` immediately after the frontmatter … found 'Body cites …'" |
| `load-test-name-mismatch` | sentinel naming `some-other-rule` | "sentinel names 'some-other-rule' but this file's rule name is 'wrong-sentinel' — a copied sentinel answers the smoke test for the wrong rule" |
| `scope-prose-with-paths` | `paths:` plus a `**Scope:**` block | "a prose `**Scope:**` block AND `paths:` frontmatter — redundant" |
| `skill-model-key` | `SKILL.md` carrying `model: sonnet` | "`model:` belongs in agent and command frontmatter, never in a skill, which has no such key" |
| `skill-missing-description` | `SKILL.md` with only `name:` | "a skill's `description:` is the only part that always loads; without it the skill is invisible in the listing" |
| `model-pinned-version` | agent `model: claude-sonnet-4-5-20250929` | "looks like a pinned version; use the stable ALIAS (sonnet, opus, …)" |
| `non-kebab-name` | a rule file named with an underscore and capitals | "rule name 'Bad_Name' is not kebab-case `<concept>`" |

Mutations used, one per clause and each proven to land on the exact bytes:
`if status == "unterminated":` to `…"unterminated-DISABLED":`;
`for lineno, raw in unparsed:` to `… in []:`;
`if isinstance(value, str) and value:` to `… and False:`;
`elif not glob_alive(pattern):` to `elif False and not …`;
`if pattern in MATCH_ALL_GLOBS:` to `if pattern in ():`;
`if kind == "rule" and scoped:` to `if False and …`;
`match.group(1) != expected` to `match.group(1) != match.group(1)`;
`if SCOPE_PROSE.match(line):` to `if None:`;
`if "model" in data:` to `if "model-DISABLED" in data:`;
the description emptiness test to `if False:`;
`re.search(r"\d", model)` to `re.search(r"\dDISABLED", model)`;
`if not KEBAB.match(name):` to `if False:`.

### 1.2 Text integrity — all hand-authored markdown

| clause | known-bad input | message (verbatim, trimmed) |
|---|---|---|
| `backtick-unmatched` | a paragraph with one stray backtick | "run of 1 backtick(s) with no matching close in this block — renders as a literal backtick, and any span it was meant to open is lost" |
| `code-span-folded-at-joiner` | the measured defect itself: a span whose content is `devcards.` then a newline then `overlap` | "code span folded across a line break next to a '.'/'o' joiner: markdown turns the newline into a SPACE, so this renders a broken symbol and loses the grep" |

`backtick-unmatched` does CommonMark delimiter matching, not a parity count. That
distinction is load-bearing here rather than pedantic: `review-discipline.md`
uses double-backtick spans to quote literal backticks, and a naive count reports
them unbalanced. Measured — a parity check flagged that paragraph; the delimiter
matcher reports zero.

Mutations: `for pos, width in unmatched:` to `… in []:`; the joiner test
`if match.group(1) in "._/" or match.group(2) in "._/":` to `if False:`.

### 1.3 Citations

| clause | known-bad input | message (verbatim, trimmed) |
|---|---|---|
| `dead-path-citation` | a doc citing a source file that was renamed away | "resolves nowhere: not tracked at the repo root, not relative to this document, no tracked path ends with it, and this repo does not declare it a build output" |
| `dead-markdown-link` | a link to a page that does not exist | same shape |

Mutations: `CITE_SPAN.finditer(line)` to `CITE_SPAN.finditer("")`;
`CITE_LINK.finditer(line)` to `CITE_LINK.finditer("")`.

**The canary suite found a real confound here and it is worth reading.**
Disabling span-extraction to attribute `dead-path-citation` also emptied the
citation extractor, whose own non-vacuity FLOOR then fired at exit 2 — so the
mutation could not be observed at all. That is a neighbour refusing the same
input, with the neighbour being a floor rather than a sibling clause. The fixture
now also carries a link that RESOLVES, which keeps the extractor count non-zero
under the mutation without adding a finding. This is exactly the case that would
have been recorded as "the clause fires" by a suite checking only for a colour
change.

### 1.4 The third answer

`unreadable-file` fires on a file that will not decode as UTF-8: "UNJUDGED by
every check below, which is a finding and not a skip". Its mutation is the weaker
PROVENANCE form — it is an exception handler with no predicate to break, so the
mutation renames the emitted clause id and the fixture must stop reporting it.
The suite says so in its own text rather than presenting it as the strong form.

`fm-unparsed` is the same principle inside the parser: the YAML reader is
deliberately a documented SUBSET, and a line outside it is reported rather than
accepted.

### 1.5 Non-vacuity and the exemption contract

All proven by running, all asserted at **exit 2 (ERROR), never 1 (FAIL)**:

- zero rule files discovered; zero hand-authored markdown; an empty tracked
  universe; an extractor that found zero code spans; and an exclusion rule that
  matches nothing (a dead exclusion reads as scope and narrows nothing).
- Floors are **per subject class**, not over the union — rules, skills, agents
  and commands are floored separately, because any populated class satisfies a
  combined floor and one class going dark would be invisible.
- An absent `git` is exit 2 with an install hint, not a traceback. This was a
  real bug I introduced and then found by auditing against
  `.claude/rules/gate-enforcement.md` §4: `FileNotFoundError` propagating from
  `subprocess.run` makes python exit **1** — the code reserved for a FINDING — so
  a checkout without git would have reported a markdown defect it never looked
  for. Verified by running the gate with a PATH containing only python3 and bash.
- Exemptions: a missing proof key, a BLANK proof key, an expired entry, an entry
  beyond the 90-day horizon, and a STALE entry each fail at exit 2. Expiry and
  horizon are separate clauses so neither masks the other. A complete entry
  excuses its finding, and a narrow `match` excuses only its own citation — the
  suite asserts a second dead citation in the same file still fires.

### 1.6 The harness's own canaries

Four assertions exist because everything above rests on two helpers:
`expect_only` must refuse an unattributed two-clause red and must refuse an exit
2 offered as a FAIL; `mutate` must refuse an absent target and must refuse a
no-op that yields a byte-identical mutant. Each runs the real helper against an
input it must reject. Without these, "all green" above would be decoration.

Writing them found a live bug: `MUTANT=$(mutate …)` ran the helper in a SUBSHELL,
so a failed mutation's diagnosis was captured into the string instead of printed
and its `FAIL` increment was discarded on subshell exit — a mutation harness that
could not report its own failure. It also found a shell scoping bug: the helpers
assigned `label`, `root` and `clause` without `local`, so calls clobbered each
other's variables. Both fixed; `local` throughout is the class fix.

## 2. FINDINGS MEASURED ON THIS TREE, AND THE DISPOSITION OF EACH

| check | findings | disposition |
|---|---|---|
| all 12 tier/frontmatter clauses | **0** | always-on, zero exemptions |
| `backtick-unmatched` | **0** | always-on |
| `code-span-folded-at-joiner` | **0** of 23 folded spans | always-on |
| `unreadable-file`, `fm-unparsed` | **0** | always-on |
| `dead-markdown-link` | **0** of 11 links | always-on |
| `dead-path-citation` | **3** of 284 path citations | 1 EXEMPT, **2 REAL — §2.1** |
| accretion / historical-narrative scan | **25 raw hits** | **CHECK REFUSED — see §3** |

Nothing is ratcheted and nothing is baselined. `gate-enforcement.md` §1 forbids
parking findings, and there was no need: the tree is clean.

### 2.1 THE GATE'S FIRST LIVE CATCH — two dead pointers, and the fix is one string

`FINAL_REPORT.md` lines 16 and 200 cite the co-tenant's Clojure-lint gate at its
former path under `tools/lint`, as a bare `clj_gate.py`. That file was MOVED during
this session to `tools/lint/src/lint_gate/core.clj`; the citations were not
updated, so both are dead pointers in a report that ships. The gate is red on
exactly these two and nothing else.

(This paragraph cannot spell the old path in a backticked span either — doing so
mints a third dead citation, and the gate caught me doing it. Third time in this
file; the lesson generalises and is in §6.5.)

**This is the gate working, not a gate that needs a waiver.** Green-on-arrival
means a gate must not need exemptions for a tree's legitimate content; it does not
mean a gate must stay green over a real defect somebody introduced ten minutes
ago. The fix belongs to whoever owns that report and is a one-string edit in two
places. I did not make it: `FINAL_REPORT.md` is not mine.

It is also the exact class the brief asked for. A rename moved the code and
nothing pointed out that the prose no longer resolved — no other lane here can
see that, and this is precisely the reason the check's input set cannot be
filtered to `*.md` (§4).

**A second live episode, in the opposite direction, inside ten minutes.** A
co-tenant's new `.claude/rules/malli-schemas.md` cited a path inside the malli
JAR. I judged it a true false positive of the archive-internal class and wrote a
proof-carrying waiver. They then reworded the citation to name only the resource,
so the waiver matched nothing and the gate FAILED it as STALE, naming the exact
entry to delete. I deleted it. That is the ratchet clause doing its job on a
timescale of minutes, and it is the strongest evidence I have that it works.

**The one exemption** that remains, in this gate's waiver store: `dead-path-citation`
on `docs/INTERFACE-CONTRACTS.md`, matching the tar-internal `zoom_controls`
screen path. It is a TRUE
false positive — the surrounding prose describes the layout INSIDE `controls.tar`
("one or more UI-AST screen protobufs"), so the citation names a member of a
distributed tarball, not a repo file. It correctly resolves nowhere, and no
resolution rule can tell an archive member from a repo path without being told.
Carries all four proof fields; `owner: gate-port`; `expires: 2026-10-27`, the
90-day horizon.

**That expiry is a deliberate time bomb and you should know the date.** It
matches the `waiver-horizon-days` contract in
`tools/devcards/src/devcards/invariants.clj`, which exists so a decision cannot
outlive itself. On 2026-10-28 this gate goes to exit 2 until the entry is
re-taken or the citation is reshaped. That is the house contract working, not a
bug, but it is a date somebody has to meet.

### How citations resolve, and the blindness that buys

Four ways, in order: tracked at the repo root; relative to the citing document; a
suffix of some tracked path — the shape a doc uses when it cites relative to a tool
root it has already named, such as `corpus/spec.edn` inside a rule scoped to
`tools/devcards`; and finally **git-ignored**, where the repo itself declares the
path a build output. At the moment of writing the split was 266 / 10 / 7 / 9 with
3 dead; re-derive it from the gate rather than trusting those numbers.

That last arm is not a suppression I invented — `.gitignore` is the answer — but
it buys a real blindness: a typo under an ignored PREFIX resolves too. So the
gate prints the ignored-accepted citations **on every run**, with file and line,
so the set stays auditable rather than merely counted.

Suffix resolution is deliberately weak and does not blunt the detection: after a
rename, no tracked path ends with the old suffix, which is the failure the check
exists to catch.

## 3. THE ACCRETION SCAN — REFUSED, with the measurement

The brief listed this as candidate 2. **I am not landing it, and the refusal is
the most defensible thing in this report.** `gate-enforcement.md` §1 names
declining a check with the reasoning recorded as a permitted outcome; this is
that.

**Measured, 46 hand-authored files, 25 raw hits.**

The high-value half is not mechanically decidable. `previously` (3), `used to`
(7), `supersede*` (4), `renamed`/`renaming` (4) — 18 hits, and they do not
separate:

- **Genuine violations**: `.claude/rules/devcards.md:241` ("This bullet used to
  read …"), `.claude/rules/renderer.md:48` ("the figure this rule used to
  quote"), `docs/UI-QUALITY-CONTRACTS.md:912` ("It used to sit here at 5.36:1"),
  `docs/UI-QUALITY-CONTRACTS.md:984` ("It used to belong on the …").
- **Clear false positives**: `.claude/rules/widget-consumer-duty.md:41` and `:51`
  use "superseded" for an LVGL accessor's API state, not repo history;
  `.claude/rules/lint-gates.md:118` and `.claude/rules/review-discipline.md:160`
  use "renaming" as a present-tense activity.
- **Undecidable without judgement**: `.claude/skills/ui-standard-review/SKILL.md:58`
  ("each of them used to fail silently") reads as a failure MECHANISM, which
  `claude-md-policy.md` says to KEEP.

The policy's own **deletion test** is explicitly a judgement — "remove it. If the
doc still commands the same behaviour with the same precision, it was chronicle —
cut it. If precision drops, it was law — keep it." A regex cannot apply that. A
gate whose findings each need adjudication is a report-only lane, and
`gate-enforcement.md` §1 says there is no advisory tier here. So: refused.

The mechanically decidable half does not earn its keep either, and the reason is
the one the brief warned about. `as of DATE` and `verified YYYY`: **0** hits.
Phase/`T2.5`/`F2-POC`/`W6` markers: **4** hits, every one of them
`claude-md-policy.md:107` quoting its own banned examples. Historical section
names: **2** hits, both the same self-reference at `:105`. So the decidable
clauses are clean only because of one self-referencing file, and **every
narrowing that made them clean — scoping to headings, excluding the policy file —
I found by looking at what this tree contains.** That is the suppression
`gate-enforcement.md` §1 and the brief both forbid, arrived at from the
respectable direction.

Two real findings I am reporting rather than gating, since I may not edit either
file:

1. `docs/UI-QUALITY-CONTRACTS.md:410` — `**PROTOGEN SELF-AUDIT, 2026-07-28.**` is
   a dated audit heading, the shape `claude-md-policy.md` bans as a dated
   incident log. It is arguably a measurement carrying its provenance, which the
   same policy says to KEEP. A genuine tie for the operator, not a defect I
   should resolve.
2. `DONATION_OWNER.md:5` — `claimed-at: 2026-07-29T20:32:03Z`, machine-written by
   the fork lifecycle tooling. Legitimate, and the reason a bare-ISO-date clause
   would need an exemption on day one.

## 4. THE WIRING — literal text, ready to paste

I cannot edit `lint.mk`. Add these two targets anywhere after its own first
target:

```make
.PHONY: lint-md lint-md-test
lint-md:
	@$(MAKE) --no-print-directory -f lint-md.mk lint-md
lint-md-test:
	@$(MAKE) --no-print-directory -f lint-md.mk lint-md-test
```

and put `lint-md-test lint-md` on the `lint:` prerequisite line. The line
currently reads:

```make
lint: lint-sh lint-ci lint-no-host-paths-test lint-no-host-paths lint-clj-gate-test lint-ns-size brief-check-test forks-release-test uber-chown-test fork-hazards fmt-clj lint-clj fmt-c
```

Insert after `lint-ci`, giving `… lint-ci lint-md-test lint-md
lint-no-host-paths-test …`.

Three things to know about that wiring:

- **CANARIES FIRST**, for the reason the `lint-no-host-paths` block already
  states for its own ordering: this gate's clauses can each refuse the same
  input, so a red says nothing about WHICH clause fired until the suite has
  settled it by mutation.
- **A sub-make, not `include`.** Make's default goal is the first target of the
  first file READ, so an `include lint-md.mk` placed above `lint.mk`'s own first
  target would silently retarget a bare `make -f lint.mk`.
- **`lint` is the right aggregate.** `lint.mk`'s header defines it as "formatting
  and lint over hand-authored code", and markdown here is hand-authored source;
  this is much closer to `lint-sh` and `lint-ci` than to `wire-contract`, which
  is held out because it is a generated artifact contradicting a hand-written
  contract.

**HOST-SAFE, not container-only.** Needs `python3` (stdlib) and `bash`, the same
footprint as `wire-contract`. Nothing here rewrites a committed artifact, so
`.claude/rules/uber-container.md` does not reach it. It runs under
`tools/uber.sh` too.

### The CI half, and it is NOT a `.md` path filter

**This gate cannot sit behind a path filter on `*.md`, and I proved it rather
than arguing it.** Its verdict depends on non-markdown files: `paths-glob-dead`
and `dead-path-citation` both resolve against the whole tracked universe.
Executed: removing the three `proto/ui/` files from the universe — zero markdown
touched — makes the gate report

```
paths-glob-dead  .claude/rules/widget-consumer-duty.md:1  glob 'proto/ui/**' matches ZERO tracked files
```

with the control run against the full universe reporting zero such findings. So a
`.md`-filtered job would never fire on the commit that broke it, which is the
"armed in name only" failure `gate-enforcement.md` §2 names.

**Its CI home is therefore `.github/workflows/hygiene.yml`**, the whole-tree
workflow that deliberately carries no `paths:` filter. Two steps, mirroring the
leak-ban pair already there:

```yaml
    - name: markdown-gate canaries (mutation-proven clause attribution)
      run: make -f lint.mk lint-md-test

    - name: markdown quality gate (frontmatter, code spans, path citations)
      run: make -f lint.mk lint-md
```

It needs only bash, git and python3, all present on the runner image. I could not
edit `.github/**` and cannot run GitHub Actions, so **this half is ARGUED, not
executed** — see §5.

The pre-push half is `.githooks/pre-push`, which I also could not edit. Adding
`lint-md` to `lint` is sufficient if the hook calls `lint`; if the hook enumerates
lanes, it needs the two targets naming explicitly.

## 5. VERIFIED BY RUNNING vs VERIFIED BY READING

**By running, in this checkout:**

- the gate on this tree: exit 0, clean over 46 files, counts as quoted.
- the canary suite: **66 assertions, all green**, `bash tools/lint/md/test/md_gate_test.sh`.
- every clause's positive message, quoted verbatim in §1 from actual output.
- every mutation and every control, including the two-sided byte-level proof that
  each mutation landed.
- all five non-vacuity floors and all seven exemption-contract cases, at the exact
  exit codes.
- the missing-`python3`, missing-gate-file and missing-suite paths of `lint-md.mk`
  (a PATH shim without python3; variable overrides naming absent files). All three
  fail loud with a diagnosis and an install hint.
- the missing-`git` path of the gate: exit 2 with an install hint.
- `make -f lint.mk lint-sh` — green, 49 scripts, with my `.sh` in its input set.
- `bash tools/lint/no_host_paths.sh` — green.
- **shellcheck over my test script: ZERO findings**, via
  `koalaman/shellcheck:stable` in docker. See §6.3 — it started at 17.
- the non-markdown-change claim in §4, by injecting a reduced tracked universe.

**By reading only:**

- everything about GitHub Actions. I cannot run a workflow. The `hygiene.yml`
  steps in §4 are argued from that file's own stated design plus the executed
  path-filter proof; they have never run.
- that `.githooks/pre-push` calls `lint` in a way that would pick up a new
  prerequisite. I read `lint.mk`'s comment saying the hook is `lint`'s sole
  caller; I did not read the hook (it is on my forbidden list) and did not run it.
- the toolchain container. `Dockerfile.base` is not built here and building it
  costs about fifteen minutes. This gate needs nothing from it, which is why I
  did not need it — but "it runs under `tools/uber.sh`" is a reading of the
  dependency footprint, not an executed run.

## 6. WHERE THE BRIEF WAS WRONG, OVERSTATED, OR OVERTAKEN

### 6.1 Candidate 3's "relative-link resolution" was the trap the brief half-warned about — and worse than stated

The brief said over-broad detection would "drown you in false positives" from
prose. Measured, it is sharper than that: my first detector reported **158 dead
citations out of 366**, and essentially all of them were bare filenames used in
prose (`core.clj`, `wasm.mk`, `renderer.md`) — not citations at all. Requiring at
least one `/` cut it to 20. Correct `./`-normalisation and the four-way resolver
cut it to **1**. The lesson is not "be careful", it is that **a citation checker
is mostly a resolver, and the detector is the easy half.** A worker who stops at
the first number lands a gate with 158 findings and concludes the docs are rotten.

### 6.2 Candidate 4's "odd backtick count" is the wrong shape, and its stated defect is a DIFFERENT class

The brief said "an odd backtick count in a paragraph is the detectable shape."
Two problems, both measured.

First, a parity count is wrong for this repo: `review-discipline.md` uses
double-backtick spans, and parity flagged that paragraph as unbalanced. CommonMark
delimiter matching — a run of N opens, the next run of exactly N closes — reports
zero. Parity is not the invariant.

Second, and more important: **the measured defect the brief cites is not an odd
count at all.** The `` `devcards.` `` / `` overlap` `` example renders as two
BALANCED spans; the original defect was one span containing a newline, folded
mid-identifier. Those are different classes needing different detectors, and I
built both. A worker following the brief literally would have shipped a parity
check that cannot see the very defect the brief points at.

The fold detector's honest limit: 23 folded spans exist on this tree and all 23
are legitimate (folded where the intended content has a space). Only a fold
ADJACENT to a `.`, `/` or `_` joiner is reported, because markdown turning the
newline into a space is correct whenever the content wanted a space there. A fold
between two bare word characters is undecidable from the text alone and is stated
as a residual in the gate's `NOT_COVERED`, printed every run.

### 6.3 The brief's own "self-containment" framing was overtaken, and my work reddened a co-tenant's lane

The brief told me to build a `machine-local-home-path` clause (candidate 1's
"absolute machine-local path" item). **I built it, then deleted it.** While I
worked, the main session landed `lint-no-host-paths`
(`tools/lint/no_host_paths.sh`) — a WHOLE-TREE leak ban with its own canary suite
and its own workflow. Mine was a strictly worse duplicate scoped to markdown, and
two implementations of one rule are two sources free to disagree about the same
file. Deleted, with a comment in the gate pointing at the owner instead.

It got worse before it got better: **my `/home/<user>/…` test fixture
reddened their whole-tree gate.** They excluded their own canaries from their
scan; they could not know about mine. Deleting my clause deleted the fixture and
their gate is green again — verified by running it. Recorded because it is the
generic hazard: a whole-tree scan and a co-tenant writing deliberate known-bad
fixtures are in conflict by construction, and the next such fixture will red it
again.

Their disposition of the donation record also differs from the one I had drafted
— a scope exclusion (`DONATION_[A-Z]+\.md`) where I had written a proof-carrying
waiver. Theirs is in force; mine is gone. Noting it so nobody re-derives mine.

**Separately, their report flagged a debt I owned and they correctly refused to
touch: 18 of 85 shellcheck findings were in my in-flight test file.** shellcheck
is not on this host, so I measured with `koalaman/shellcheck:stable` in docker:
**17 findings, now 0.** All three fixed at the root, none suppressed:

- **SC2329 ×15**, "this function is never invoked" — the fixture functions were
  passed by NAME and called as `"$fixture" "$root"`, invisible to static
  analysis. The only repairs on offer were 15 inline suppressions or a repo-wide
  rule disable, both forbidden by `.claude/rules/lint-gates.md`. I removed the
  indirection: fixtures are now built by direct calls into named roots and
  `clause_case` takes roots. Each tree is built once instead of twice, and the
  design is clearer.
- **SC2034 ×1** — a genuinely dead `landed` local, left over from rewriting
  `mutate`. Deleted.
- **SC2016 ×1** — backticks inside a single-quoted `printf` FORMAT string.
  Restructured to a quoted heredoc plus a byte-only `printf`.

That unblocks the shellcheck adoption for this file. The other ~68 findings are
not mine.

### 6.4 What was impossible as written

- **`paths:` glob ANCHORING cannot be checked.** The brief listed "globs anchored
  to the repo root". `claude-md-policy.md`'s own `paths:` includes `**/*.md`,
  which fails any literal anchoring test — the policy's example violates the
  policy's sentence. A gate here would red the policy file on day one for
  something that is correct. Declined and recorded in `NOT_COVERED`.
- **"one rule, one scope theme"** is pure judgement. Not attempted.
- **`paths:` on a skill or agent is NOT a violation**, though the sibling
  reference implementation flags it "defensively".
  `claude-md-policy.md` explicitly describes what `paths:` on a skill DOES and
  merely prefers a path-scoped rule. Flagging it would fork the policy — the
  thing this whole exercise exists to prevent. Not built.
- **The reference implementations are babashka.** The brief said so and it is
  right: `bb` is not installed and a `.bb` script is not portable here.
  Everything is reimplemented. I took the frontmatter gate's SHAPE from the
  `sych` sibling checkout (its glob-liveness idea and its
  `assert-nonempty-glob!` / `validate-allowlist-meta!` / `assert-sanctions-in-scope!`
  primitives, reproduced as conditions inside this gate) and rejected its
  `paths:`-on-non-rule clause per the point above. Its accretion and dead-path
  gates I read and declined to port — §3 for the first, and the second's design
  (a registry of dead ROOT prefixes) answers a different question than this
  repo's, which is whether a cited path resolves at all. The other two sibling
  checkouts carry no markdown gates to compare.

### 6.5 THIS REPORT REDDENED ITS OWN GATE THREE TIMES, and every one was real

Worth recording because both are generic.

**The gate could not see an uncommitted file.** It used plain `git ls-files`, so
its discovery was the INDEX. Writing this report and running the gate produced
exit 0 while the report carried four dead citations — a green that never read the
file. `lint.mk` already names this exact defect at its own shell discovery
("the index alone gives a worker who has written a new script and not staged it a
GREEN THAT NEVER READ IT") and fixes it with `--cached --others
--exclude-standard`; I had not copied the fix. Now corrected, and the corrected
run immediately picked up two more files a co-tenant had written and not yet
committed — a new rule and a new skill — and judged them clean.

**And a report about a dead-path gate cannot quote dead paths in backticks.** The
four findings were this file naming its own fixture paths and the tar-internal
archive member; a fifth arrived when §2.1 quoted the very moved path it was
reporting. Each time the disposition was to REWORD, not to exempt: a waiver for a
report's own prose would be the check widened to accommodate the document
describing it, and the exemption in force is scoped to one file for one reason. A
gate whose own documentation needs a waiver is a gate one edit away from waiving
everything.

**Third: a literal home path in the sentence describing a literal home path.** See
§6.6 — a co-tenant fixed that one before I did.

### 6.6 A CO-TENANT EDITED THIS REPORT, and I kept the edit

My §6.3 fixture sentence originally spelled a literal path under a named home
directory, which reddened the whole-tree leak ban a second time — in the very
paragraph describing the first time. Someone else changed it to the `/home/<user>/…`
placeholder form while I worked. I did not make that edit and cannot prove its
provenance; I kept it, because it is correct and reverting it would re-red their
gate. Recorded per `.claude/rules/fork-isolation.md`: a file you did not write is
preserved and reported, not adjudicated. It also means this file has had two
writers, so derive its claims from the tree.

### 6.7 One thing the brief got exactly right

"Prefer FEWER, genuinely robust gates over a broad shallow sweep." Three of the
four candidates landed clean with one exemption; the fourth is refused with
numbers. The measurement, not the ambition, decided which.

## 7. THE FACT I NEEDED AND COULD NOT GET

**Whether `docs/UI-QUALITY-CONTRACTS.md:410`'s dated self-audit heading is law or
chronicle.** It is the one genuine finding of the refused accretion scan, and the
policy answers both ways: `claude-md-policy.md` bans "dated incident logs" and
`as of YYYY-MM-DD` pins, and in the same file says a "measurement carrying the
condition that reproduces it" is KEPT. A dated census with its numbers is
plausibly either. Nothing runnable settles it — no grep, no canary, no container
run — because the question is what the author intended the sentence to DO. It is
a TIE for the operator by the test in `.claude/rules/work-prioritisation.md`, and
it is the fact that decides whether a dated-pin clause can ever be armed here.

A second, smaller one: **whether `.githooks/pre-push` picks up a new `lint`
prerequisite or enumerates lanes.** One `grep` would answer it. The file is on my
forbidden list and I did not read it, so the pre-push half of §4 is stated
conditionally rather than resolved. Whoever wires this should look; it costs one
command.
