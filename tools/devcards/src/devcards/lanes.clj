(ns devcards.lanes
  "The LANES protogen's own gate runs: which producers judge an atomic card,
   which judge a composition card, and how `corpus/spec.edn`'s `:expect`
   vocabulary routes the DOM lane.

   Separate from `devcards.core` — which owns the CLI, the render loop and
   the golden manifests — for one reason, and it is a testability one: core
   has to load the generated protobuf bindings, so NOTHING that requires it
   can be unit-tested without first compiling `target/proto-classes`, which
   the `:test` alias deliberately does not carry.

   That is not a detail. While the producer vectors were assembled inside
   `core.clj`, the only canaries that could be written pinned things that
   RESEMBLED the call sites — a registry helper, a producer in isolation —
   and a review found both of them green while the production expression
   they stood for had been reverted to its defective form. A lane that
   cannot be named in a test cannot be pinned by one. So the call sites live
   here, and `core.clj` calls them.

   Separate from `devcards.findings` for the opposite reason: `:expect` is
   THIS corpus' vocabulary, not a registry concern. A consumer's corpus
   declares what its own cards are for, through its own producer."
  (:require [devcards.deadzone :as deadzone]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.opa :as opa]
            [devcards.outcome :as outcome]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(def verdict-policy
  "This gate's exit policy: the shipped default, UNNARROWED — the same
   dogfood argument `overlap-classes` makes. A private relaxation here would
   gate this repo more loosely than every consumer that inherits the
   default."
  outcome/default-policy)

(def tree-producer
  "The DOM lane, routed by what the card is FOR.

   It is a producer rather than a bare function so protogen's own gate runs
   through the same registry every consumer is told to extend: a rule added
   to `devcards.findings` that the gate here could not express would make
   that instruction hollow.

   `:probe-defect` is the INVERTED arm and the reason this cannot be the
   plain tree lane: such a cell exists to EXHIBIT a defect flag, so its
   ABSENCE is the finding.

   TRUNCATION IS CHECKED BEFORE THE ROUTER, and that ordering is the whole
   point rather than a detail. Two arms short-circuit without ever reaching
   `invariants/tree-findings`, which is the only other home of the
   :dump-truncated clause: `:probe-pixel-only` returns [] outright, and
   `:probe-defect` reads defect flags off a tree that truncation has already
   replaced with a canonical empty root — so it would report
   :probe-defect-absent, a finding naming a clause that never ran. Routing
   first therefore turns 'the dump overflowed and I judged nothing' into a
   clean card on every :probe-pixel-only card and into a wrong diagnosis on the
   :probe-defect ones. What the card is FOR is only a meaningful question once
   the instrument answered at all.

`:probe-pixel-only` IS A SKIP, AND THIS LANE EMITS NO THIRD ANSWER FOR IT.
   Say it here because the empty vector cannot: five corpus cards declare it,
   the DOM lane returns `[]` for each, and that `[]` is byte-identical to the
   one a fully-judged clean card returns. Nothing in the run's output — not
   findings.edn, not the by-lane tally — distinguishes \"judged and clean\"
   from \"not judged\" on those cards. The RECORD that makes this a declared
   skip rather than the silence CLAUDE.md forbids lives in the CORPUS, not
   here: `corpus/spec.edn`'s `:expect-legend` defines the class, and each of
   the five carries `:notes` naming the failure mode dump_tree is structurally
   blind to. That is a real record and a reader can check it; it is simply not
   in the same place as the answer.

   WHAT DOES JUDGE THOSE FIVE, since 'pixel-only' is a claim about which
   ORACLE owns them and not a claim that nothing does: `gates/golden-drift-
   findings` compares every card's raw-framebuffer sha256 against the
   committed `goldens/manifest-{dark,light}.edn`, and all five are committed
   in both — VERIFIED, not assumed. Before that lane was armed the only
   comparison anywhere was CI's `git diff`, and the card `:notes` still say
   things like \"the PNG pixels are the assertion\" from that era; the local
   battery can now fail on them. Do not read this arm as a hole in the pixel
   coverage — it is a hole in the DOM lane's REPORTING, which is the smaller
   and more precise complaint.

   THE TWO PARAGRAPHS ABOVE MEET AT ONE POINT: the skip is what this arm does
   when the instrument ANSWERED. A truncated dump is judged before the router
   reaches the arm at all, so the `[]` described here is the reply to a tree
   that was read and declined, never to one that was never read."
  {:id :tree-by-expect
   :requires #{:tree :caps :expect :declaration :family}
   :fn (fn [{:keys [card-id tree nodes caps expect declaration family]}]
         ;; :family is stamped on EVERY arm's output, at the one seam all three
         ;; leave through. Stamping inside the judged arm alone was the first
         ;; cut and it was wrong in a way no test caught: the truncation
         ;; short-circuit and the :probe-defect-absent arm both return before
         ;; it, so a card that overflowed the dump buffer under stock reported a
         ;; finding that named no render. A per-render verdict that cannot say
         ;; WHICH render is unactionable, and two of the three arms were emitting
         ;; exactly that.
         (mapv
          #(assoc % :family family)
          (or (seq (invariants/truncation-findings card-id tree))
              (case expect
                :probe-pixel-only []
                :probe-defect (if (some (fn [node]
                                          (some #(get node %) invariants/defect-flags))
                                        (tree-seq #(seq (:children %)) :children tree))
                                []
                                [{:card card-id
                                  :invariant :probe-defect-absent
                                  :detail (str "cell exists to EXHIBIT a defect flag; "
                                               "none present")}])
               ;; nil expect (kitchen sinks) and every judged expect → full lane.
               ;; The designed-flag declaration is applied HERE rather than
               ;; inside `tree-findings`, so the flag producer stays a pure
               ;; "what did the dump say" function and the "what did the corpus
               ;; declare" question is one composable step a consumer can read.
               ;; Every finding carries :family — a per-render verdict that
               ;; cannot name its render is not actionable.
                (:live (invariants/apply-designed-flags
                        card-id
                        (:designed-flags declaration)
                        (invariants/tree-findings card-id tree caps nodes)
                        family))))))})

(def overlap-classes
  "The classification table the overlap lane judges this corpus with: the
   shipped starter table, unextended. protogen is the table's author, so
   taking it as-is is the honest dogfood — a private override here would
   gate this repo on rules no consumer inherits."
  (lvgl-classes/merge-consumer {}))

(def overlap-thresholds
  "Strict overlap: a SHARED pixel fires, touching does not. Deliberately not
   1 — RE-MEASURED over all 246 cards on this tree, gap-px 1 reports 80
   findings where gap-px 0 reports none, 66 of them on the five lv_tabview
   cards plus the tabview kitchen sink (UI-QUALITY-CONTRACTS §2.3). The lane
   is a pointer-hazard gate, and two elements that merely touch take no press
   from each other.

   THEY ARE NOT STACKED PAGES, which is what this docstring used to say. Every
   one of the 66 is an ABUTMENT at 0px: the tab bar against the content area
   below it, one tab button against the next, a tab button against the content
   area. A snapped-away page never reaches the pairing at ANY threshold — it
   sits outside its content box, so the descent-gate clip records it
   `:unreachable`, a determination rather than a gap. The other 14 are the
   scrubber legos' vertically stacked rows, abutting the same way. Raising the
   threshold floods the lane with LAYOUT, not with hazards.

   THE COUNT IS A PROPERTY OF THIS CORPUS, THIS TABLE AND THIS RENDERER, so
   re-measure rather than trust it — the number here was 97, from a tree state
   nothing reproduces, which is the failure `overlap/producer`'s closing
   paragraph warns about for the census it lost. Reproduce it with
   `GAP_PX=1 clojure -M:bindings:class-census`, and `CLASSES=shipped` to judge
   with the armed table instead of the probe's: the two agree on 0/80/66, which
   is a result rather than a tautology, since the shipped table marks several
   classes non-interactive and the two therefore judge different node sets.

   The 97 did not originate here and was never measured here: it came from
   `docs/UI-QUALITY-CONTRACTS.md` §2.3, recorded before the arming work, and was
   copied into this docstring BY that arming commit without being re-derived —
   at which point gap-px 0 already reported 0, not the 17 the doc's table still
   showed."
  {:overlap/gap-px 0})

(def deadzone-thresholds
  "Strict ordered overlap, matching `overlap-thresholds`: the disabled
   winner and enabled node beneath it must share a pixel."
  {:deadzone/gap-px 0})

(def producer-thresholds
  "The namespaced thresholds for every adjustable producer in either lane.

   SUPPLYING A KEY AND ARMING ITS PRODUCER ARE ONE STEP, NOT TWO — and the
   half-step is a HARD BREAK, not a no-op. `findings/resolve-thresholds` builds
   its index from the producer vector the LANE passes and throws
   `unknown threshold …` on any supplied key no producer in that vector
   declares. Measured: merging `:palette/colors-by-mode` here while
   `devcards.palette`'s producer stays out of both lane vectors throws on the
   first card of BOTH lanes, so the gate judges nothing at all. That direction
   is pinned by `lanes-test`'s
   `every-supplied-threshold-is-declared-by-an-ARMED-producer`. It does NOT
   turn a green suite red — the resolver's throw already reds it, and already
   names the key. What it changes is the ATTRIBUTION: without it the mistake
   arrives as ERRORs inside canaries about `:expect` routing, producer selection
   and exemption vocabularies, which abort those bodies and say nothing about
   which half was intended (arm the producer, or drop the key).

   WHY `:palette/colors-by-mode` IS NOT HERE, and it is a MEASUREMENT rather
   than an oversight. `devcards.palette`'s producer is written, registers
   cleanly, and is reachable — `findings/card-findings` passes every context key
   the caller supplies straight through, so `:draw-palette` needs no entry in
   the registry's closed `context-keys` set. What stops it is the population.
   Over the shipped corpus, 246 cards x 2 modes, a large minority of colour
   emissions match no semantic token value and about a third of those carry the
   observer's declared-theme-recolor tag. THE FIGURES ARE NOT WRITTEN HERE ON
   PURPOSE: they move with the theme — the tabview/buttonmatrix/switch
   stock-fallthrough repair cut them measurably in one commit — and a pinned
   tally in prose is a second source that goes stale silently. Re-derive with
   the probe named at the end of this docstring.

   That tag is the separation an arming plan naturally reaches for,
   `palette-findings` ALREADY applies it, and it is not what is in the way: a
   few hundred blocking findings survive it, across most renders and roughly a
   score of distinct hexes.

   NINE OF THOSE HEXES WERE TRACED to a source; the rest were not, and this
   says nine rather than all of them for that reason. Two classes account for all
   nine, and neither is a defect:
     - AUTHORED BY THE CARD, as structured RGB rather than hex — which is why a
       hex grep over the corpus finds nothing. #3B82F6 is the `lv_chart` series
       colour and #00C800/#E63232 the `lv_scale` section colours, all literals
       in `corpus/spec.edn`; #30363D and #8A939B are the scrubber lego's, in
       `devcards.legos`.
     - STOCK LVGL's OWN values, reached by fall-through. #FAFAFA and #212121
       are `lv_theme_default`'s `color_text` — DARK_COLOR_TEXT and
       LIGHT_COLOR_TEXT, i.e. `lv_palette_lighten(GREY,5)` and
       `lv_palette_darken(GREY,4)` — and #F5F5F5 and #E0E0E0 are two more
       entries of that same grey row in `lv_palette.c`.
   A tenth is worth naming for what it is NOT: #6A32CC is authored NOWHERE in
   this tree — not the corpus, not `theme.c`, not the token manifest — and lands
   on pressed-button cards; §6.2 records the same value as the fill a disabled
   themed button rendered. Its derivation was not traced, and that is the point:
   it cannot be repaired by declaring a token, because nothing declares it.

   A semantic colour catalogue does not declare a chart's series colour and does
   not own stock's fall-through values. Blocking on these buys per-card
   exemptions — the ratchet CLAUDE.md refuses — or a token-table change whose
   obvious form §6.8 measures as a net CONTRAST LOSS. Arming is therefore a
   design decision this repo has not made, not a wiring step somebody forgot.

   RE-MEASURE RATHER THAN TRUST THAT — it is a property of this corpus, this
   theme and this renderer. The probe is `tools/devcards/dev/palette_census.clj`.
   It carries no deps.edn alias AND defines no -main (`-m` would load it, print
   everything, then die on the missing var), so from `tools/devcards` in the
   toolchain container it is:
     clojure -Sdeps '{:aliases {:devpath {:extra-paths [\"dev\"]}}}' \\
       -M:bindings:devpath -e \"(require 'palette-census)\"
   `dev/brief_claims.clj` is the same shape and splits the non-token population
   by the recolor tag."
  (merge overlap-thresholds deadzone-thresholds))

(def atomic-producers
  "The producers that judge an ATOMIC card. A named vector rather than a
   literal inside the call below because `armed-producers` has to be the set
   that actually runs — a hand-kept second list would be free to drift, and
   the NOT-EXERCISED line computed from it would then lie in the one direction
   nothing else can catch."
  [tree-producer overlap/producer deadzone/producer opa/producer])

(def composition-producers
  "The producers that judge a COMPOSITION card.

   THE DOM PRODUCER IS `tree-producer`, THE SAME ONE THE ATOMIC LANE USES, and
   sharing it is load-bearing rather than tidiness. This vector held the plain
   `(findings/builtin-producer :tree)` while `composition-findings` threaded
   `:family` and `:declaration` into the context — and the plain builtin reads
   NEITHER. The registry refuses an ABSENT required key, never a SUPPLIED
   unread one, so nothing failed: composition findings simply carried no
   `:family`, and a `:designed-flags` entry naming a composition card would
   have been silently inert while its flag stayed live. Two lanes, two DOM
   rules, and no gate able to notice them diverging.

   The `:expect` router costs nothing here: a composition's `:expect` is
   `:composition` or `:inert-prop`, neither of which the router names, so both
   take the default arm — the full lane — exactly as the plain builtin did."
  [tree-producer
   findings/emission-by-mode-producer
   overlap/producer
   deadzone/producer
   opa/producer])

(def armed-producers
  "Every producer this gate arms, across both lanes. Derived from the two
   vectors the lanes actually pass, so it cannot name a lane that is not
   running or miss one that is.

   Read by `run-verdict` for exactly one purpose: to decide which blocking
   outcomes are IN SCOPE for the NOT-EXERCISED line. `opa/producer` declares
   `#{:failed :cantTell}` and every other entry declares nothing (so
   `:failed` alone) — which means the armed set can emit :cantTell, that line
   NAMES it, and on this corpus it names it as NOT EXERCISED. That is a
   measurement now, where before the whole line was permanently out of scope:
   a run in which the opa clause could not decide a faded node would drop
   :cantTell from the not-exercised list and BLOCK, because the shipped
   policy fails on it. :untested stays out of scope — nothing armed here
   declares it.

   DEDUPED, because the two lanes legitimately share `overlap/producer` and a
   plain concat therefore carried `:overlap` twice — enough that
   `(findings/validate-producers! armed-producers)` REFUSED the very vector
   named 'every producer this gate arms'. Nothing the verdict computes from it
   is order- or multiplicity-sensitive (it unions the declared outcomes), so
   this changes no line; it makes the def safe for the next caller, who will
   reasonably expect to be able to validate it. `distinct` compares whole
   producer maps, so two DIFFERENT producers colliding on one id still reach
   `validate-producers!` and still throw — which is the case that is a real
   defect."
  (into [] (distinct) (concat atomic-producers composition-producers)))

(def gate-exemptions
  "This gate's GLOBAL exemption list, and it is EMPTY.

   The emptiness is a CLAIM, not an omission — the registry's own
   supplied-but-empty/absent distinction applied to the gate itself. The overlap
   lane reached zero by the interpreter DECLARING its own proxy composition and
   by clearing CLICKABLE on two decorative widgets, never by a waiver (see
   `overlap/producer`). Passing it EXPLICITLY below rather than letting
   `card-findings` default it is what makes that a statement a reader can check.

   IT IS NOT A CLAIM THAT NOTHING IS CONCEDED ANYWHERE, and it stopped being one
   the day `corpus/designed-flags.edn` landed. THIS list is empty; that file
   holds per-card, per-family declarations, and its `:kind :false-positive`
   entries run through `outcome/check-proof!` — the waiver validator — so they
   are waivers in everything but which vector they live in. The two are separate
   because an exemption cannot match on FAMILY (`exemption-match-keys` has no
   such axis), not because one kind of concession is absent.

   It is GLOBAL — one list for both lanes — which is the fact that makes the
   reason vocabulary the ARMED set's rather than any one lane's; see
   `findings/validate-exemption-reasons!`."
  [])

(defn atomic-findings
  "The live findings for ONE atomic card — the exact call the gate makes.

   `expect` is the card's declared `:expect`, or nil for the kitchen sinks.
   It is defaulted to `:judged` HERE rather than passed through, because the
   registry treats a nil value as ABSENT and would refuse the call: a nil
   would not fall through to a lenient lane, it would throw at
   `check-requires!`. The kitchen sinks must be JUDGED, so the default has
   to be a real value.

   `family` is the theme family the tree was dumped from, and `designed` the
   card's `:designed-flags` declaration (nil when it declares none). Both are
   REQUIRED ARGUMENTS rather than defaulted: a family defaulted to 0 would
   silently judge every render as though it were the shipped theme, which is
   the exact blindness this arity exists to remove."
  [id expect tree family designed]
  (:live (findings/card-findings {:card-id (str id)
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect (or expect :judged)
                                  :family family
                                  :declaration {:designed-flags designed}
                                  :classes overlap-classes
                                  :thresholds producer-thresholds
                                  :producers atomic-producers
                                  :armed-producers armed-producers
                                  :exemptions gate-exemptions})))

(defn composition-findings
  "The live findings for ONE composition card — the exact call the gate
   makes. A composition is rendered in both modes, so the emission lane is
   the by-mode producer rather than the `:emission` builtin.

   `expect`, `family` and `designed` carry the same contract as
   `atomic-findings`' — and they are CONSUMED here for the same reason, because
   both lanes now run the same DOM producer. `expect` is defaulted to `:judged`
   on the same argument that arity makes: the registry treats nil as ABSENT and
   would refuse the call, so a nil would throw rather than fall through."
  [id expect tree emissions-by-mode family designed]
  (:live (findings/card-findings {:card-id (str id)
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect (or expect :judged)
                                  :family family
                                  :declaration {:designed-flags designed}
                                  :host-proxy? false
                                  :emissions-by-mode emissions-by-mode
                                  :classes overlap-classes
                                  :thresholds producer-thresholds
                                  :producers composition-producers
                                  :armed-producers armed-producers
                                  :exemptions gate-exemptions})))

(defn run-verdict
  "THE GATE'S PROCESS VERDICT — the lines `devcards.core` prints and the code
   it exits with, in one call.

   It lives HERE, and that placement is the whole point rather than tidiness.
   `core.clj` cannot load under the `:test` alias (it drags
   `devcards.fixtures` and the generated bindings), so for as long as the
   verdict->lines->exit computation sat in core's `-main` it was the one
   expression in this system that NO test could name. Canaries existed that
   pinned `outcome/exit-code` — a fn with zero production callers — and a
   mutation forcing `:exit 0` in what core actually ran left all of them
   green. That is the identical failure this namespace was created to fix for
   the LANES, arriving one function to the left. After this move, neither
   core arm holds a decision: each has only a `doseq println` and a
   `System/exit`, both of whose inputs are computed and source-pinned.

   The one-argument arity passes `armed-producers`, the set the generate mode
   actually runs, so its NOT-EXERCISED line is scoped to what that gate can
   emit. The two-argument arity is for a batch source outside
   `findings/card-findings`; gallery passes [] because its disk audit is not a
   registered per-card producer. The findings still block under the shipped
   defaults, but the report does not claim gallery exercised the atomic or
   composition producers.

   Returns `outcome/verdict`'s map with `:lines` extended by the console
   detail — the by-lane tally and the findings themselves, blocking first,
   truncated at 40 with the remainder counted. Truncation is console-only:
   `core.clj` persists the FULL vector to out/findings.edn before calling
   this."
  ([findings] (run-verdict findings armed-producers))
  ([findings producers]
   (let [{:keys [blocking lines] :as v}
         (outcome/verdict findings verdict-policy {:producers producers})
         blocking? (set blocking)
         ;; sort-by is STABLE, so with no ACT axis anywhere this is the
         ;; identity ordering and the console output is unchanged.
         ordered (sort-by #(if (blocking? %) 0 1) findings)
         shown (into (conj lines
                           (str "by lane: "
                                (pr-str (frequencies
                                         (map #(or (:gate %) (:invariant %))
                                              findings)))))
                     (map pr-str)
                     (take 40 ordered))]
     (assoc v :lines (cond-> shown
                       (> (count findings) 40)
                       (conj (str "… " (- (count findings) 40) " more")))))))
