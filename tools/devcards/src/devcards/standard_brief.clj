(ns devcards.standard-brief
  "Generates the UI-standard REVIEW BRIEFING — the one document a batched
   screenshot-review agent loads before it judges anything, written to
   `.claude/skills/ui-standard-review/STANDARD.md`.

   The agent it briefs eyeballs the committed gallery renders plus the
   `dump_tree` values behind them and reports interface defects against
   this repo's UI standard. It is the only lane in that standard whose
   judgement is not reproducible, which is why the briefing is GENERATED
   rather than authored: a hand-written brief is a SECOND copy of the
   standard, and the copy is wrong from the first threshold that moves.
   Every value here is read from the same source the gate reads —

     docs/UI-QUALITY-CONTRACTS.md     the contract text, embedded VERBATIM
     devcards.lvgl-classes            the live classification table
     each producer's :thresholds      the live defaults + their :doc
     each producer's :requires        rendered into the producer table
     the example producer's :fn       RUN, so the worked example is real
                                      output and moves when the rule does

   Treat that list as the ones worth naming, not as a closed set: the page
   is a function of this namespace and everything it reads, so the honest
   statement of its inputs is the generator itself. The gate does not
   depend on the list being complete — it regenerates and diffs, so it
   catches a drift in any input whether or not anyone wrote it down.

   A standard change that does not reach the briefing therefore shows up as
   a diff, which is exactly what the `standard-brief` target in renderer.mk
   gates on (regenerate, then `git diff --exit-code`, the shape the
   goldens/gallery freshness step uses).

   MARKDOWN, NOT PDF. The consumer is a vision-language model reading
   text; a PDF would cost a rasterisation round-trip and tokens for
   nothing, and it could not be diffed by the gate above.

   WHAT THIS NS DOES NOT DECIDE. It publishes the standard; it does not
   arm anything. `devcards.overlap` and `devcards.layers` ship OPT-IN and
   protogen's own corpus does not run them (each producer's docstring
   carries the evidence for why), so `reviewed-producers` below is the
   roster whose declared knobs the briefing PUBLISHES — never a claim
   about what is armed.

   Shapes are hand-validated (no malli dependency; the devcards tool is
   malli-free), and the page is assembled with the shared
   `devcards.docgen` primitives so the DO-NOT-EDIT banner, the GFM tables
   and the writer stay one home apiece."
  (:require [clojure.string :as str]
            [devcards.docgen :as docgen]
            [devcards.findings :as findings]
            [devcards.invariants :as invariants]
            [devcards.layers :as layers]
            [devcards.lvgl-classes :as lvgl-classes]
            [devcards.overlap :as overlap]))

(set! *warn-on-reflection* true)

(def contract-path
  "The standard's prose home (tool-relative). Embedded verbatim — see
   `contract-section-md` for why it is not summarised."
  "../../docs/UI-QUALITY-CONTRACTS.md")

(def out-path
  "The generated briefing (tool-relative). It sits beside the skill that
   loads it, so the agent reads one file and the gate diffs one file."
  "../../.claude/skills/ui-standard-review/STANDARD.md")

(def regen-cmd
  "The command that rewrites the page. Named in the banner so the reader
   who spots a stale value knows what to run rather than editing the
   output."
  "`make -f renderer.mk standard-brief` from the repo root")

(def reviewed-producers
  "The registry producers whose declared thresholds the briefing
   publishes, validated at load so a malformed roster fails GENERATION
   rather than emitting a plausible-looking table. `validate-producers!`
   also refuses an empty vector, which is the failure that matters here:
   a briefing that published no rules would read as a standard with no
   rules."
  (findings/validate-producers! [overlap/producer layers/producer]))

(def example-card-id
  "The synthetic card id the worked example carries. Deliberately not a
   real corpus id — the example demonstrates the SHAPE of a finding, and
   borrowing a real card's id would put a fabricated finding in the
   reader's head against a card they can go and look at."
  "example/two-controls")

(def example-tree
  "A synthetic two-control dump tree: a button and a slider, siblings,
   sharing pixels. Small enough to read, and it exercises the one thing
   the worked example must prove — that the finding below was PRODUCED by
   the live rule against the live classification table, not typed out."
  {:type "lv_obj" :uid 1 :coords [0 0 99 49]
   :children [{:type "lv_button" :uid 2 :coords [0 0 49 29]}
              {:type "lv_slider" :uid 3 :coords [40 10 89 39]}]})

;; ── The generated sections ──────────────────────────────────────────────
(defn classification-md
  "The live classification table as GFM, one row per declared type sorted
   by class name (sorted so the page is byte-stable and the gate's diff
   means a real change).

   An empty `:types` throws: a briefing that published an empty
   classification would tell the agent every widget is unclassified,
   which is the same wrong answer a broken table gives."
  ^String [table]
  (let [types (:types table)]
    (when (empty? types)
      (throw (ex-info "classification table declares no :types — the briefing
                       would publish an empty standard"
                      {:table (keys table)})))
    (str (docgen/md-table
          ["class" ":role — INTENT" ":interactive? — MECHANISM"]
          (for [c (sort (keys types))
                :let [{:keys [role interactive?]} (get types c)]]
            [(str "`" c "`") (str "`" role "`") (str "`" interactive? "`")]))
         "\n\n"
         (if (contains? table :default)
           (str "The table declares a `:default` (`" (pr-str (:default table))
                "`), so a class absent from the rows above is judged as that.")
           (str "The table declares NO `:default`, so totality is enforced: a "
                "class absent from the rows above is an `:unclassified-type` "
                "finding, never a skip (§3)."))
         " Report a widget whose class you cannot find above — do not guess "
         "its row.")))

(defn producers-md
  "The rule roster: what context each rule reads and which knobs it
   declares. `:requires` is rendered because it says what a rule can and
   cannot see — a rule that never receives the proxy rects cannot judge a
   compositor stack, and the reader needs that to know which findings are
   a rule's to make and which are the review's."
  ^String [producers]
  (docgen/md-table
   ["rule" "context it requires" "thresholds it declares"]
   (for [p producers]
     [(str "`" (:id p) "`")
      (str/join ", " (map #(str "`" % "`") (sort (:requires p))))
      (if (seq (:thresholds p))
        (str/join ", " (map #(str "`" % "`") (sort (keys (:thresholds p)))))
        "—")])))

(defn thresholds-md
  "Every declared threshold, with the CONSUMER key the registry builds for
   it (`findings/threshold-key` — not a string built here, so the page
   cannot disagree with the key a consumer must actually supply), its
   default, and the producer's own `:doc`.

   The default is the value that matters most: it is what runs when a
   consumer supplies nothing, so it is the number the reviewed renders
   were judged against."
  ^String [producers]
  (let [rows (for [p producers
                   [k spec] (sort-by key (:thresholds p))]
               [(str "`" (:id p) "`")
                (str "`" (findings/threshold-key (:id p) k) "`")
                (str "`" (pr-str (:default spec)) "`")
                (str (:doc spec))])]
    (if (empty? rows)
      "No rule in the roster above declares a threshold."
      (docgen/md-table ["rule" "consumer key" "default" "what it means"] rows))))

(defn- kw-list
  "`#{:a :b}` -> \"`:a`, `:b`\", sorted so the rendered page is stable."
  ^String [ks]
  (str/join ", " (map #(str "`" % "`") (sort ks))))

(defn exemption-contract-md
  "The exemption contract sentence, DERIVED from `invariants/exemption-keys`
   rather than restated.

   This paragraph is why: it used to be a hardcoded string reading 'per card
   and per invariant', and it stayed that way through the commit that added
   the outcome, mode and reason conjuncts to `exempt?`. Nothing went red —
   `make -f renderer.mk standard-brief` regenerates and diffs the page, so it
   compares the generator's output against itself and a stale literal is
   INVISIBLE to it. That is exactly the failure this ns's own docstring
   exists to prevent: a hand-written brief is a second copy of the standard,
   and a hardcoded sentence inside a generator is still a second copy.

   Now adding a match axis changes this page, and the diff is the review."
  ^String []
  (str "Every finding is dispositioned before the push: FIXED, or EXEMPTED "
       "with the same proof-carrying entry any other exemption owes — "
       (kw-list invariants/exemption-proof-keys)
       ", both non-blank. An entry MATCHES on "
       (kw-list invariants/exemption-match-keys)
       ". `:card` and `:invariant` are mandatory. `:node` matches ANY node "
       "when absent. `:act/outcome` and `:act/test-mode` are optional too "
       "but narrow the OTHER WAY — absent reads the DEFAULT (`:failed`, "
       "`:automatic`) on both sides, so omitting one does NOT widen the "
       "entry, it pins that axis to its default: an entry naming no mode "
       "matches the automatic finding and NOT the manual one. That is what "
       "lets an entry written before these axes existed keep matching "
       "exactly what it always matched. `:act/reason` is not a free choice "
       "at all — it is REQUIRED when `:act/outcome` names a reasoned "
       "outcome and REFUSED otherwise, so there is no state in which you "
       "pick it. Exemptions ratchet DOWN: an exemption "
       "matching no finding is itself a finding (`:stale-exemption`), so the "
       "list can only shrink (`devcards.invariants` is the mechanism and the "
       "authority). An undispositioned finding is not a pass.\n\n"))

(defn example-finding
  "Run `producer` over `example-tree` with `classes` and the producer's own
   resolved defaults, and return its FIRST finding (nil when it finds
   nothing). This is what makes the worked example in the briefing a live
   artifact: change the table so the two controls stop being interactive,
   or change the rule, and the example changes or disappears with it."
  [producer classes]
  (first ((:fn producer)
          {:card-id example-card-id
           :nodes (invariants/annotate-tree example-tree)
           :classes classes
           :thresholds (get (findings/resolve-thresholds [producer] {})
                            (:id producer))})))

(defn- example-md
  "The worked example, rendered from a REAL producer run. A nil finding
   throws rather than emitting an empty block: an example that shows no
   finding teaches the shape wrong, and it would do so silently.

   Printed through `sorted-map` so the key order is fixed by the sort
   rather than by map-construction order — the page must be byte-stable
   for the drift gate to mean anything."
  ^String [producer classes]
  (let [f (or (example-finding producer classes)
              (throw (ex-info "the worked example produced NO finding — a
                               briefing that shows an empty example teaches
                               the finding shape wrong"
                              {:producer (:id producer)})))]
    (str "```clojure\n" (pr-str (into (sorted-map) f)) "\n```")))

(defn contract-section-md
  "The contract, embedded verbatim under a lead-in that names its home.

   VERBATIM, not summarised, and the reason is the whole point of this
   generator: a summary is a second copy of the standard that no gate
   compares against the first, so it drifts the moment a clause moves.
   The reader is a model with a context window; the honest way to spend it
   is on the actual words the rules were written against."
  ^String [^String contract-text]
  (str "The text below is `docs/UI-QUALITY-CONTRACTS.md`, embedded "
       "verbatim by the generator — the same words the deterministic "
       "rules were written against, section numbers included, so a "
       "citation like §1.4 resolves here and in the source alike.\n\n"
       "---\n\n"
       contract-text))

;; ── The page ────────────────────────────────────────────────────────────
(defn brief-md
  "Assemble the whole briefing. `opts`:

     :contract-text     docs/UI-QUALITY-CONTRACTS.md, read from disk
     :classes           the live classification table
     :producers         the rule roster whose thresholds are published
     :example-producer  the rule the worked example is run through

   Every one is an INPUT rather than a lookup, so a test can vary one at a
   time and prove the page moved with it — the property the drift gate
   rests on."
  ^String [{:keys [contract-text classes producers example-producer]}]
  (str
   (docgen/do-not-edit-header
    regen-cmd
    (str "docs/UI-QUALITY-CONTRACTS.md (embedded verbatim) +\n"
         "tools/devcards/src/devcards/lvgl_classes.clj (the live "
         "classification table) +\n"
         "each producer's declared :thresholds, read from code by "
         "tools/devcards/src/devcards/standard_brief.clj"))
   "# UI standard — review briefing\n\n"
   "You are reviewing rendered interface elements against this repo's UI "
   "standard. Your inputs are the committed gallery renders and the "
   "`dump_tree` values behind them; your output is a list of findings in "
   "the shape below.\n\n"
   "WHAT IS AND IS NOT AUTHORITATIVE HERE. The contract text, the "
   "classification table and the threshold values below are reproduced "
   "or read FROM SOURCE at generation time, and a drift gate fails the "
   "battery if they fall out of step — trust those verbatim. The "
   "connective framing around them is this generator's own prose and is "
   "NOT covered by that gate; where it matters, the cited source wins "
   "over anything said about it here.\n\n"

   "## How this review runs\n\n"

   "### One agent, one batch — not one agent per check\n\n"
   "This briefing is the expensive part of the context and it is "
   "identical for every element, so ONE agent loads it once and works "
   "through a large batch. The fixed cost amortises instead of being "
   "re-paid per element.\n\n"
   "Batching also buys signal a fan-out cannot. An agent holding many "
   "elements at once can see that a whole widget family drifted the same "
   "way, that two theme families disagree about the same control, and "
   "that one card is the odd one out — comparisons that do not exist "
   "inside a single-element context. It also holds ONE calibration across "
   "the batch; N independent agents calibrate independently, and findings "
   "from different calibrations cannot be compared to each other. The "
   "skill beside this file carries the batch-selection protocol; this "
   "page carries the standard the batch is judged against.\n\n"

   "### Mandatory before push\n\n"
   "Running this review is required before any push that modifies what a "
   "**ui_ast surface** renders — here, and wherever such a surface is "
   "authored (`CLAUDE.md` §\"Consuming the UI standard\"). It is owed for "
   "the SURFACE, not for the repository: a repo that also ships a DOM or "
   "native front-end owes this for its ui_ast overlay and nothing for the "
   "rest, because the only inputs this review has are the gallery renders "
   "and the `dump_tree` behind them. §5 is why it cannot be one repo's "
   "habit: a quality rule that lives only in a consumer is a defect live "
   "in every other consumer that has not yet tripped over it.\n\n"

   "### Disposition before push\n\n"
   (exemption-contract-md)

   "### Your verdict is NOT a gate verdict\n\n"
   "Every other lane in this standard is reproducible — exact integer "
   "arithmetic over rects, or a hash of raw framebuffer bytes. You are "
   "not: the same renders can yield a different reading. §0 forbids a "
   "pass message that implies more than the gate can see, and a "
   "non-reproducible judgement reported as a gate result is exactly that. "
   "So your findings are input to a human disposition, never an exit "
   "code, and they must stay distinguishable from arithmetic ones — which "
   "is what the namespaced `:invariant` below is for.\n\n"
   "The corollary about legibility: §0 records that NO readability "
   "producer ships here, so there is no lane verdict for you to agree or "
   "disagree with — and the shape §0 reserves for a noise-banded "
   "measurement admits an adjudicator only once it has been validated as "
   "a classifier on a held-out labelled set, which your eye has not been. "
   "Report a legibility doubt as a finding for a human; never report it "
   "as a readability pass or fail.\n\n"

   "## What you can see, and what you cannot\n\n"
   "You hold both halves of the same card — the rendered pixels AND the "
   "`dump_tree` values that produced them. That is the review's home "
   "ground, because no other lane has both: the goldens hash pixels "
   "without reading a value, and the DOM invariants read values without "
   "seeing a pixel. Disagreement between the two halves — a label whose "
   "DOM text is not the glyphs on screen, a value the DOM reports and the "
   "render does not show, a state the DOM claims and the pixels do not — "
   "is yours to catch and nothing else's.\n\n"
   "What you cannot see, stated plainly so a clean review is not "
   "over-read:\n\n"
   "- **Pointer reachability.** The framebuffer is identical whether an "
   "occluded control was reachable or dead (§2.1). A stack that looks "
   "fine can still eat every press.\n"
   "- **Where the hazard boundary actually is.** The rules judge the "
   "REACHABLE box — the click area, grown by `ext_click_pad`, clipped to "
   "every ancestor's descent gate (§2.4). It can be larger than anything "
   "drawn, and it can be smaller than the click area itself. Neither "
   "boundary is visible in a render.\n"
   "- **`layer_top` / `layer_sys`.** Out of scope for the dump entirely "
   "(§1.6).\n"
   "- **Transforms, opacity, partial transparency.** The contract does "
   "not model them (§1.4), and neither should your findings.\n"
   "- **Any hardware condition.** Legibility under sunlight, darkness or "
   "a specific panel revision is a bench obligation scoped to a hardware "
   "revision, never a gate result (§0, PDL-HW). You are looking at a "
   "gallery render, not a panel.\n\n"

   "## Emitting a finding\n\n"
   "Findings ride the existing verdict and exemption machinery, so they "
   "carry the SAME shape every registry producer returns — "
   "`{:card :invariant :node :detail}`. `devcards.findings` rejects a "
   "finding without `:card` and `:invariant`, because those are the keys "
   "the exemption, verdict and report lanes all read.\n\n"
   (docgen/md-table
    ["key" "what you put in it"]
    [["`:card`"
      (str "the card id exactly as the gallery and the DOM name it — an "
           "invented id yields a finding nothing can attribute or exempt")]
     ["`:invariant`"
      (str "a NAMESPACED keyword from the skill's closed set beside this "
           "file — namespaced so a finding of yours can never collide with "
           "a deterministic lane's keyword, since a collision would let one "
           "exemption silence the other lane, and so a non-reproducible "
           "judgement can never be read as an arithmetic one (§0)")]
     ["`:node`"
      (str "the element, in the `type#uid` form the geometry rules label it "
           "with (`devcards.overlap`); name both when the finding is about a "
           "pair")]
     ["`:detail`"
      (str "what you saw, where, and which clause it violates — written so "
           "a reader who cannot see the image can act on it")]])
   "\n\n"
   "A worked example, produced by actually running the `"
   (:id example-producer)
   "` rule over a synthetic two-control tree with the classification "
   "table below — the generator emits whatever that rule returns, so this "
   "is the live finding shape rather than an illustration of it (keys "
   "sorted for a byte-stable page):\n\n"
   (example-md example-producer classes)
   "\n\n"
   "READ THAT EXAMPLE FOR ITS SHAPE, NOT ITS KEYWORD. It was produced by a "
   "DETERMINISTIC rule, so it carries that rule's `:invariant` — which is "
   "exactly the kind of finding you must NOT emit, because a machine already "
   "owns it and reports it reproducibly. Yours carry a `:vlm/`-namespaced "
   "keyword from the closed set in the SKILL — this page does not list them, "
   "and does not get to add one. The namespace is what keeps one exemption "
   "from silencing both a model's finding and a machine's.\n\n"

   "## The live classification table\n\n"
   "Read from `devcards.lvgl-classes/starter-table` at generation time. "
   "The two columns are deliberately different axes: `:interactive?` is "
   "MECHANISM (does LVGL put this class in the pointer path), `:role` is "
   "INTENT (what a human reads or aims at). A read-only indicator that is "
   "clickable by mechanism is `:interactive? true` here on purpose — "
   "`devcards.lvgl-classes` is the authority for why, and §3 is the "
   "contract.\n\n"
   (classification-md classes)
   "\n\n"

   "## The rules, and the thresholds they were judged at\n\n"
   "Read from each producer's declared `:thresholds` at generation time. "
   "Membership here is NOT the armed state — it is the roster whose knobs "
   "this page publishes; each rule's own docstring states whether it runs "
   "against this repo's corpus.\n\n"
   (producers-md producers)
   "\n\n"
   (thresholds-md producers)
   "\n\n"
   "Thresholds are DATA: the registry namespaces each key by its rule, "
   "throws on an unknown key and throws when two rules collide on one, so "
   "neither a typo nor a naming clash can quietly relax the gate it names "
   "(§4).\n\n"

   "## The contract\n\n"
   (contract-section-md contract-text)))

;; ── Generation ──────────────────────────────────────────────────────────
(defn generate!
  "Read the contract, assemble the page from the LIVE table and roster, and
   write it. Returns docgen's {:path :bytes}. Every input is defaulted from
   this repo's own sources; the opts exist so a caller can point the
   generator at a different checkout, not so it can publish a different
   standard."
  ([] (generate! {}))
  ([{:keys [contract classes producers example-producer out]
     :or {contract contract-path
          classes lvgl-classes/starter-table
          producers reviewed-producers
          example-producer overlap/producer
          out out-path}}]
   (docgen/write-text! out
                       (brief-md {:contract-text (slurp contract)
                                  :classes classes
                                  :producers producers
                                  :example-producer example-producer}))))

(defn -main
  "CLI entry: `clojure -M -m devcards.standard-brief` from the devcards tool
   root. Prints the written path + byte count; the freshness comparison is
   the caller's (renderer.mk's `standard-brief` target diffs the result)."
  ;; `written` rather than destructuring :bytes into a local named `bytes` —
  ;; that shadows clojure.core/bytes. The KEY stays :bytes (it is docgen's
  ;; return shape, and renaming data to please a linter is the wrong half of
  ;; the fix).
  [& _args]
  (let [{:keys [path] written :bytes} (generate!)]
    (println (str "standard-brief: wrote " path " (" written " bytes)"))))
