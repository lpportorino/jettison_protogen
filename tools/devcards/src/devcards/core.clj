(ns devcards.core
  "The devcard pipeline CLI — spec → build → render → judge.

   `generate` renders the FULL corpus (every atomic card + kitchen sink)
   across the three theme families × dark/light, then:
   - EVERY family the run renders is DOM-judged, from its DARK tree, with the
     spec's :expect routing — :probe-defect cells must EXHIBIT at least one
     defect flag, :probe-pixel-only cells are dump-blind and skipped,
     everything else runs the full lanes — and every finding carries :family.
     Family 0 (asgard, the shipped look) additionally carries the golden
     manifests (dark + light).
     THE MODE AXIS IS STILL ONE PER FAMILY: light renders are hashed, never
     dumped, so a light-only DOM defect is invisible here. That is a narrower
     boundary than the family-0-only one it replaces, but it is still one.
   - families 1/2 (vanilla/stock): per-card hash equality, BOTH modes (the
     vanilla arms carry dark-conditional stock colors — light must hold
     too), PLUS golden manifests for family 2 (manifest-stock-*). Equality
     alone is RELATIVE: it cannot see a change that moves both reference
     families by the same delta, which is every change to the substrate they
     share. The stock manifests pin family 2 absolutely and family 1 follows
     transitively — see `golden-manifest-paths` for why no vanilla manifest
     is committed. They remain differential reference controls for PIXELS —
     stock LVGL is vendored upstream and vanilla is required to reproduce it —
     but their DOM is judged like any other family, which is how the
     composition legos' theme-dependence was found: chrome sized in px with its
     padding, border and gap inherited from whichever theme was loaded.
     The case for leaving them unjudged was that upstream behaviour would
     become a permanent exemption ratchet. Measured, it split three ways: some
     findings were artifacts of the FLAGS (fixed in the interpreter, so no
     longer emitted), most were protogen's own legos (fixed), and the residue is
     DECLARED per family in `corpus/designed-flags.edn`, where a :subject entry
     owes no expiry and a :false-positive entry owes a waiver's full proof.
   - the state-contract lanes (distinctness/inertness) over family-0 dark.
   - the AUTHORED-COMPOSITION lane (corpus/composition.edn via
     devcards.composition): the same per-family invariants + goldens
     (manifest-composition-{dark,light}.edn) + vanilla≡stock over the
     public-lego cards, plus the :inert-prop pixel-inertness pin and the
     GraalWasm interaction lane (devcards.interaction); every card's
     bytes persist under out/composition/ for the wasmtime mirror suite.
   The CLI has exactly these two modes; anything else exits 2. `generate`
   BOTH verifies and re-mints: every card's fresh raw-FB hash is diffed
   against the COMMITTED manifest (`gates/golden-drift-findings`, read before
   the mint overwrites it) and any drift is a blocking finding, then the
   manifests are rewritten so a red run leaves the corrected copies in the
   tree to review and commit — the regenerate-then-diff shape `renderer.mk`'s
   `manifests` and `generated-projection` lanes already use.

   THAT CONTENT comparison is the goldens only. The JPEG gallery and generated
   doc pages under docs/ are produced by `gallery`, so CI's `git diff
   --exit-code tools/devcards/goldens tools/devcards/docs` remains the gate for
   a changed CONTACT SHEET. The two-way disk audit in each mode covers the
   different blind spot: a retired generated file that nothing rewrites and
   `git diff` therefore cannot see.

   THE AUDIT NOW PRUNES AS WELL AS REPORTS, which changes what the freshness
   diff can see. A retired artifact used to be a blocking finding the author
   cleared by hand, and the gate stayed red until they did; the prune deletes it,
   so it reaches `git diff` as a ` D` entry rather than as an invisible file.
   Deletion is gated on TOTALITY, not on the render: unless every page the
   registry declares was written, nothing is deleted and every orphan comes back
   `:run-not-total`. A run that produced less than it should must never be read
   as a run whose missing units were retired.

   `gallery` is the T2.7 doc build (recorded call: a core mode, not a
   dev/ script — the pipeline has ONE CLI and the gallery is a pipeline
   product, not a probe): renders the corpus once per committed family set
   (vanilla-dark, asgard-dark, asgard-light) and writes the per-widget
   contact sheets + generated doc pages under docs/widgets/
   (devcards.gallery + devcards.docs).

   Exit code is the verdict: non-zero on any BLOCKING finding, where blocking
   is `devcards.lanes/verdict-policy` applied to each finding's ACT/EARL axes.
   Per-card findings get producer metadata through `findings/card-findings`;
   the batch-level disk audit cannot use that per-card contract and carries no
   ACT axes, so the shipped defaults make it blocking. It is deliberately not
   exemptible. Both modes print counts and persist their full findings vector
   to out/findings.edn before the verdict: silence is never success, and a
   non-blocking finding is never silent.

   'Counts always print' is a claim about ORDER, and it is load-bearing:
   `outcome/verdict` is TOTAL and is computed as one step, so a malformed
   axis becomes a printed blocking finding instead of an exception thrown
   before the report exists. Zero counts print as zeroes and unexercised
   blocking outcomes get a named NOT-EXERCISED line, because a `frequencies`
   map that omits what it never saw cannot tell 'none observed' from 'not a
   value this run could produce'.

   THE VERDICT IS NOT COMPUTED IN THIS NAMESPACE. Each mode calls
   `lanes/run-verdict`, which returns the lines and exit code together;
   `-main` only prints and exits with those values. The two source-text pins in
   `lanes_test.clj` are necessary because this ns cannot be loaded by a test —
   the generated bindings it requires are not on the :test alias's path."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.designed :as designed]
            [devcards.docgen :as docgen]
            [devcards.docs :as docs]
            [devcards.fixtures :as fixtures]
            [devcards.gates :as gates]
            [devcards.golden :as golden]
            [devcards.host :as host]
            [devcards.interaction :as interaction]
            [devcards.lanes :as lanes])
  (:gen-class))

(set! *warn-on-reflection* true)

(def render-protocol
  "The pinned per-card render protocol (mirrors the conventions manifest)."
  {:ticks 3 :tick-ms 16 :dpi 160 :canvas {:w 800 :h 480}})

(def golden-manifest-paths
  "label → committed golden manifest path. THE ONE HOME of these paths:
   the golden-drift lane READS each of them and the mint WRITES the same file,
   and two literal lists would be free to drift into verifying a file nothing
   writes — a gate that can only ever be green.

   THE :stock-* HALF PINS FAMILY 2 ABSOLUTELY. The family-1-vs-2 equality lane
   (`gates/vanilla-stock-findings`) is RELATIVE and therefore cannot see a
   change that moves both reference families by the same delta — a vendored
   LVGL bump, a font, the canvas, the render protocol. Those four manifests
   are what turn \"vanilla and stock must not move\" from a cited control into
   an armed one.

   THERE IS DELIBERATELY NO :vanilla-* HALF. Family 1 is pinned transitively —
   stock == committed here, vanilla == stock in the equality lane — so a
   vanilla manifest set would double the committed surface (and the re-mint
   diff of every substrate change) while detecting nothing new. That argument
   is only sound while the equality lane stays TOTAL over these ids, which is
   what its one-sided arms and their canaries exist to hold."
  {:atomic-dark "goldens/manifest-dark.edn"
   :atomic-light "goldens/manifest-light.edn"
   :composition-dark "goldens/manifest-composition-dark.edn"
   :composition-light "goldens/manifest-composition-light.edn"
   :stock-atomic-dark "goldens/manifest-stock-dark.edn"
   :stock-atomic-light "goldens/manifest-stock-light.edn"
   :stock-composition-dark "goldens/manifest-stock-composition-dark.edn"
   :stock-composition-light "goldens/manifest-stock-composition-light.edn"})

(def ^:private audit-details
  "Root-specific recovery text for the batch-level disk audit. Tracked
   generated trees and regenerated scratch have different remedies."
  {:goldens
   {:missing (str "the golden writer returned this manifest path but no file "
                  "exists there — inspect the write before accepting a re-mint")
    :orphaned (str "this TRACKED golden manifest is no longer claimed by any "
                   "live lane — delete it in the same corpus change that "
                   "retired the lane")
    :refused "the committed goldens tree could not be reconciled"}
   :composition
   {:missing (str "the fresh composition writer returned this scratch path but "
                  "no file exists there — the write did not survive this run")
    :orphaned (str "the composition scratch tree was deleted immediately "
                   "before this run, so this unclaimed file was created by the "
                   "current generator — fix the writer/claim disagreement and "
                   "rerun; there is no tracked file to delete")
    :refused "the freshly rebuilt composition scratch tree could not be reconciled"}
   :docs
   {:missing (str "the gallery writer returned this doc path but no file exists "
                  "there — inspect the write before accepting the gallery")
    :orphaned (str "this TRACKED doc artifact is no longer claimed by the live "
                   "gallery — delete it in the same corpus change that retired "
                   "its unit")
    :refused "the committed docs tree could not be reconciled"}})

(defn- persist-findings!
  "Persist one CLI arm's FULL findings vector before its total verdict."
  [findings]
  (io/make-parents "out/findings.edn")
  (with-open [w (io/writer "out/findings.edn")] (pp/pprint findings w)))

;; Assembled-home paths (protogen root layout: tools/devcards/ beside
;; renderer/): the pinned wasm + assets are the relocated renderer's build
;; output. Run from tools/devcards inside the assembled tree — `renderer.mk
;; fixtures` builds the wasm first, so a missing file here is a battery
;; sequencing bug, not a provisioning fallback.
(defn- existing-path
  "The path must exist — a missing renderer artifact is a battery
   sequencing bug (`renderer.mk fixtures` builds the wasm first), never a
   provisioning fallback."
  ^String [path]
  (if (.exists (java.io.File. ^String path))
    path
    (throw (ex-info "no renderer artifact found" {:tried [path]}))))

(def ^:private wasm-path
  (existing-path "../../renderer/output/controls.wasm"))

(def ^:private assets-path
  (existing-path "../../renderer/assets"))

(defn- boot-host!
  "A fresh host over the pinned wasm/assets at the protocol canvas."
  []
  (host/start! {:wasm wasm-path
                :assets assets-path
                :w (get-in render-protocol [:canvas :w])
                :h (get-in render-protocol [:canvas :h])}))

(defn- render-one!
  "One hermetic render: fresh context, family+mode set, pb loaded. Returns
   {:fb bytes :tree parsed-json-or-nil :emissions captured-lanes}."
  [^bytes pb {:keys [family dark dump?]}]
  (let [h (boot-host!)]
    (try (when (pos? (long family)) (host/set-theme-family! h family))
         (let [fb (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
               tree (when dump? (json/read-str (host/dump-tree! h) :key-fn keyword))]
           {:fb fb :tree tree :emissions @(:captured h)})
         (finally (host/close! h)))))

(defn- manifest-of
  "Literal golden manifest from precomputed hashes ({id → sha})."
  [hashes]
  {:protocol render-protocol
   :cards (into (sorted-map)
                (map (fn [[id sha]] [id
                                     {:sha256 sha
                                      :w (get-in render-protocol [:canvas :w])
                                      :h (get-in render-protocol [:canvas :h])}]))
                hashes)})

(defn run-generate
  "The full pipeline over built entries (vector of {:id :expect :bytes}).
   Returns {:findings :counts :manifests {:dark m :light m}}."
  [spec built]
  (when (empty? built) (throw (ex-info "empty corpus — refusing a vacuous run" {})))
  (let [t0 (System/nanoTime)
        f0 (into {}
                 (map (fn [{:keys [id expect designed-flags] ^bytes pb :bytes}]
                        (let [dark (render-one! pb {:family 0 :dark true :dump? true})
                              light (render-one! pb {:family 0 :dark false})]
                          [(str id)
                           {:hash (golden/sha256-hex (:fb dark))
                            :light-hash (golden/sha256-hex (:fb light))
                            :expect expect
                            :designed-flags designed-flags
                            :tree (:tree dark)}])))
                 built)
        ;; DARK renders DUMP, light renders do not — the per-family DOM is
        ;; judged from the dark tree, matching the choice family 0 already
        ;; made. Light-mode DOM stays unjudged, and that is a boundary this
        ;; change does not move: it widened the FAMILY axis from one to three
        ;; and left the MODE axis at one.
        fam-render (fn [family dark]
                     (into {}
                           (map
                            (fn [{:keys [id expect designed-flags] ^bytes pb :bytes}]
                              (let [r (render-one! pb {:family family
                                                       :dark dark
                                                       :dump? dark})]
                                [(str id)
                                 {:hash (golden/sha256-hex (:fb r))
                                  :expect expect
                                  :designed-flags designed-flags
                                  :tree (:tree r)}])))
                           built))
        f1d (fam-render 1 true)
        f2d (fam-render 2 true)
        f1l (fam-render 1 false)
        f2l (fam-render 2 false)
        hashes (fn [m] (update-vals m :hash))
        ;; EVERY family the run renders is judged, not family 0 alone. Families
        ;; 1 and 2 were hashed for pixels and never dumped, so a layout defect
        ;; living only under another theme's padding reported byte-identically
        ;; to a clean card — measured at 148 findings across 27 cards on the run
        ;; that first looked, none of them visible to any lane before it.
        judge (fn [family by-id]
                (mapcat (fn [[id {:keys [expect designed-flags tree]}]]
                          (lanes/atomic-findings id expect tree family designed-flags))
                        by-id))
        inv (vec (concat (judge 0 f0) (judge 1 f1d) (judge 2 f2d)))
        gate-res (gates/run-gates spec {0 (hashes f0) 1 (hashes f1d) 2 (hashes f2d)})
        ;; A declaration filed under a MISSPELLED card id is invisible to the
        ;; stale clause, which is only ever asked about cards the run judged. So
        ;; the key set is checked against the ids that exist — over BOTH lanes,
        ;; because one file serves both and an id valid in the other lane must
        ;; not read as a typo here.
        ;; The composition ids come from the INVENTORY, never from build-all:
        ;; that arity materialises every lego and serialises it to Screen bytes,
        ;; which is a second full build of the composition corpus inside the
        ;; ATOMIC run just to read ten strings — and it would make this run's
        ;; success depend on the composition corpus being buildable, which it
        ;; otherwise does not.
        orphan-declarations
        (for [id (designed/unknown-card-ids
                  (designed/load-declarations)
                  (concat (map :id built)
                          (map :id (:cards (composition/load-inventory)))))]
          {:card id
           :invariant :unknown-declared-card
           :node "(declaration)"
           :detail (str "corpus/designed-flags.edn declares this card id and no"
                        " card has it — a typo'd id is dead AND invisible to the"
                        " stale clause, which only asks about cards that ran")})
        vs-light (mapv #(assoc % :mode :light)
                       (gates/vanilla-stock-findings (hashes f1l) (hashes f2l)))
        findings (-> (:findings gate-res)
                     (into vs-light)
                     ;; the kitchen sinks' RENDERED trees are authored here in
                     ;; Clojure (fixtures/kitchen-sink-trees), not in spec.edn —
                     ;; the secret-scan must see them, or a landmark in a sink's
                     ;; text ships unscanned
                     (into (gates/corpus-secret-findings
                            (map (fn [[id tree]] {:id id :node tree})
                                 fixtures/kitchen-sink-trees)))
                     (into orphan-declarations)
                     (into inv))]
    {:findings findings
     :counts (assoc (:counts gate-res)
                    :renders (* (count built) 6)
                    :invariant-findings (count inv)
                    ;; Reported so GROWTH in the declaration list is visible: the
                    ;; stale clause bounds removal only, and a green run would
                    ;; otherwise read identically whether nothing or everything
                    ;; was absorbed.
                    :designed-flag-entries
                    (reduce + 0 (map count (vals (designed/load-declarations))))
                    :elapsed-s (/ (- (System/nanoTime) t0) 1e9))
     ;; :stock-* are the SAME family-2 hashes the equality lane above judged,
     ;; minted as manifests so family 2 is pinned absolutely and not only
     ;; relative to family 1. No extra render is paid: f2d/f2l already exist
     ;; because the equality lane needs them.
     :manifests {:dark (manifest-of (hashes f0))
                 :light (manifest-of (update-vals f0 :light-hash))
                 :stock-dark (manifest-of (hashes f2d))
                 :stock-light (manifest-of (hashes f2l))}}))

(defn- persist-bytes!
  "Write `b` at `path` (parents created). Returns the path — the composition
   lane collects them as its CLAIMED set for `docgen/audit`, so what is
   reconciled is what this fn actually wrote rather than a second listing of
   the naming scheme."
  ^String [^String path ^bytes b]
  (io/make-parents path)
  (with-open [o (io/output-stream path)] (.write o b))
  path)

(defn- comp-slug
  "Composition card id -> flat artifact slug (ids contain '/')."
  ^String [id]
  (str/replace (str id) "/" "_"))

(def composition-out-dir
  "Where the composition lane persists each card's .pb + raw
   framebuffers — the SAME bytes the wasmtime interaction suite
   re-renders and byte-compares
   (renderer/wasm_harness/tests/composition_interaction.rs reads this
   tree repo-relatively).

   AUDITED after `renderer.mk` deletes and rebuilds it for every fixtures run.
   The harness DISCOVERS its card roster by `read_dir` over this tree
   rather than hand-listing it — deliberately, so it cannot go narrower
   than the corpus. Cleaning before generation prevents an ignored slug from
   making two developers on one commit get different verdicts; reconciliation
   then catches any path the CURRENT run writes without claiming."
  "out/composition")

(def composition-geometry-file
  "The pointer-contract DECLARATION the wasmtime mirror suite reads
   (renderer/wasm_harness/tests/composition_interaction.rs), written
   beside the `.pb` cards it already consumes."
  (str composition-out-dir "/interaction-geometry.json"))

(def cross-engine-atomic-ids
  "ATOMIC card ids whose `.pb` + raw framebuffers are persisted into the
   COMPOSITION out-dir so the wasmtime harness's cross-engine byte-identity
   loop picks them up — it DISCOVERS its roster by `read_dir` over that tree
   (renderer/wasm_harness/tests/composition_interaction.rs `card_slugs`), so a
   card written there is compared with no Rust change. That loop is the ONLY
   byte-identity assertion between GraalWasm and wasmtime; every other atomic
   card is judged by GraalWasm alone.

   VECTOR FIRST, and that is the whole reason this set exists rather than a
   general widening. SVG rasterization is floating-point path filling, so two
   wasm engines agreeing bit-for-bit on integer blits says nothing about their
   agreeing on a curve — vector is the likeliest divergence and was the one
   class the gate could not see.

   A NAMED SET rather than the whole atomic corpus, deliberately: persisting
   all of it would write two full-canvas raw framebuffers PER CARD, hundreds
   per run against the composition lane's 22. Grow this on purpose."
  #{"lv_image/default/large" "lv_image/disabled/large"})

(defn- persist-cross-engine-atomic!
  "Re-render each `cross-engine-atomic-ids` card and persist its `.pb` and both
   raw framebuffers into the composition out-dir, returning the written paths
   so the caller folds them into the audit's CLAIMED set — the reconciliation
   compares that tree against what a generator claimed, so an unclaimed write
   is itself a finding.

   REFUSES a set that matches fewer cards than it names. A renamed or deleted
   card would otherwise persist nothing, and the cross-engine loop would go
   GREEN over one fewer card with no signal at all — the vacuous pass
   gate-enforcement.md refuses, and the one this set exists to prevent."
  [built]
  (let [wanted (filterv #(contains? cross-engine-atomic-ids (str (:id %))) built)]
    (when-not (= (count wanted) (count cross-engine-atomic-ids))
      (throw (ex-info "cross-engine atomic set matched fewer cards than it names"
                      {:named cross-engine-atomic-ids
                       :matched (mapv (comp str :id) wanted)})))
    (into []
          (mapcat (fn [{:keys [id] ^bytes pb :bytes}]
                    (let [dark (render-one! pb {:family 0 :dark true})
                          light (render-one! pb {:family 0 :dark false})
                          slug (comp-slug id)]
                      [(persist-bytes! (str composition-out-dir "/cards/" slug ".pb") pb)
                       (persist-bytes! (str composition-out-dir "/fb/" slug "_dark1.raw")
                                       (:fb dark))
                       (persist-bytes! (str composition-out-dir "/fb/" slug "_dark0.raw")
                                       (:fb light))])))
          wanted)))

(defn- persist-geometry-declaration!
  "Emit `interaction/geometry-declaration` as JSON.

   JSON because the mirror suite already parses JSON (dump_tree,
   host_event envelopes) and carries no EDN reader. Each entry gains the
   artifact SLUG so the mirror resolves the card file it must drive
   without re-implementing `comp-slug` — the same class of copy this
   file exists to retire.

   RETURNS ITS PATH, like every other writer here, because the disk audit
   reconciles what this lane CLAIMS against what is on disk. Returning nil
   made the file an unclaimed artifact of the generator's own run — a
   blocking `:orphaned-artifact` naming a file nothing had gone stale about."
  [inventory]
  (let [decl (update-vals (interaction/geometry-declaration inventory)
                          #(assoc % :slug (comp-slug (:card %))))]
    (io/make-parents composition-geometry-file)
    (spit composition-geometry-file
          (with-out-str (json/pprint decl :key-fn name)))
    composition-geometry-file))

(defn run-composition
  "The authored-composition lane over built composition entries
   (devcards.composition/build-all output): family-0 renders (dark +
   light) under the full invariant + render-time-emission lanes, the
   vanilla≡stock family pin (both modes), the :inert-prop
   pixel-inertness pin, the golden manifests, and the GraalWasm
   interaction lane. Returns {:findings :artifacts :counts :manifests
   {:dark m :light m}}; :artifacts is derived from the writer returns."
  [inventory built]
  (when (empty? built)
    (throw (ex-info "empty composition corpus — refusing a vacuous run" {})))
  (when-not (= (:canvas inventory) (:canvas render-protocol))
    (throw (ex-info "composition canvas != the pinned render protocol's"
                    {:inventory (:canvas inventory)
                     :protocol (:canvas render-protocol)})))
  ;; Emitted BEFORE the renders, alongside the cards below: a red run must
  ;; still leave the mirror suite a declaration matching the cards it wrote,
  ;; or the mirror's own red would name a stale file instead of the defect.
  (let [geometry-artifact (persist-geometry-declaration! inventory)
        t0 (System/nanoTime)
        f0 (into {}
                 (map (fn [{:keys [id expect designed-flags] ^bytes pb :bytes}]
                        (let [dark (render-one! pb {:family 0 :dark true :dump? true})
                              light (render-one! pb {:family 0 :dark false})]
                          [(str id)
                           {:dark-hash (golden/sha256-hex (:fb dark))
                            :light-hash (golden/sha256-hex (:fb light))
                            :artifacts
                            [(persist-bytes! (str composition-out-dir "/cards/"
                                                  (comp-slug id)
                                                  ".pb")
                                             pb)
                             (persist-bytes! (str composition-out-dir "/fb/"
                                                  (comp-slug id)
                                                  "_dark1.raw")
                                             (:fb dark))
                             (persist-bytes! (str composition-out-dir "/fb/"
                                                  (comp-slug id)
                                                  "_dark0.raw")
                                             (:fb light))]
                            :inv (lanes/composition-findings
                                  id expect (:tree dark)
                                  {:dark (:emissions dark)
                                   :light (:emissions light)}
                                  0
                                  designed-flags)}])))
                 built)
        ;; Same widening as `run-generate`: the dark render of every family
        ;; DUMPS and is judged. A lego authors its own geometry and promises
        ;; "proven geometry at dpi 160" — a promise that was only ever checked
        ;; under the shipped theme, where the dock's chrome constants happened
        ;; to match the padding they had been measured against.
        fam-render (fn [family dark]
                     (into {}
                           (map (fn [{:keys [id expect designed-flags] ^bytes pb :bytes}]
                                  (let [r (render-one! pb {:family family
                                                           :dark dark
                                                           :dump? dark})]
                                    [(str id)
                                     {:hash (golden/sha256-hex (:fb r))
                                      :expect expect
                                      :designed-flags designed-flags
                                      :emissions (:emissions r)
                                      :tree (:tree r)}])))
                           built))
        f1d (fam-render 1 true)
        f2d (fam-render 2 true)
        f1l (fam-render 1 false)
        f2l (fam-render 2 false)
        hashes (fn [m] (update-vals m :hash))
        judge (fn [family by-id]
                (mapcat (fn [[id {:keys [expect designed-flags emissions tree]}]]
                          (lanes/composition-findings id expect tree
                                                      {:dark emissions}
                                                      family designed-flags))
                        by-id))
        inv-findings (vec (concat (mapcat (comp :inv val) (sort-by key f0))
                                  (judge 1 f1d)
                                  (judge 2 f2d)))
        vs (-> (vec (gates/vanilla-stock-findings (hashes f1d) (hashes f2d)))
               (into (mapv #(assoc % :mode :light)
                           (gates/vanilla-stock-findings (hashes f1l) (hashes f2l)))))
        inert (-> (vec (gates/inert-prop-findings built (update-vals f0 :dark-hash)))
                  (into (mapv #(assoc % :mode :light)
                              (gates/inert-prop-findings built
                                                         (update-vals f0
                                                                      :light-hash)))))
        interaction-findings (vec (interaction/run-lane boot-host! inventory built))
        findings (-> inv-findings
                     (into vs)
                     (into inert)
                     ;; the composition cards are a card population too — the
                     ;; secret-scan must see them, not just the atomic corpus
                     (into (gates/corpus-secret-findings (:cards inventory)))
                     (into interaction-findings))]
    {:findings findings
     :artifacts (into [geometry-artifact]
                      (mapcat (comp :artifacts val) (sort-by key f0)))
     :counts {:composition-cards (count built)
              :composition-renders (* (count built) 6)
              :composition-findings (count findings)
              :elapsed-s (/ (- (System/nanoTime) t0) 1e9)}
     ;; :stock-* — see run-generate: the family-2 hashes the equality lane
     ;; already computed, minted so the composition lane pins family 2
     ;; absolutely too. The legos sit on the same substrate as the atomic
     ;; cards, so leaving them relative-only would leave a hole this size.
     :manifests {:dark (manifest-of (update-vals f0 :dark-hash))
                 :light (manifest-of (update-vals f0 :light-hash))
                 :stock-dark (manifest-of (hashes f2d))
                 :stock-light (manifest-of (hashes f2l))}}))

(defn -main
  "CLI: `generate` renders, judges, writes and audits goldens/composition;
   `gallery` renders, writes and audits docs. Both exit non-zero on any
   BLOCKING finding — see this ns's docstring for what blocking means. Under
   today's shipped `lanes/verdict-policy` that is every finding, but the two
   are not the same claim and a consumer is free to narrow the policy without
   touching this fn."
  [& [mode]]
  (let [spec (fixtures/load-spec)
        built (fixtures/build-all spec)]
    (case (or mode "generate")
      "generate"
      ;; THE COMMITTED MANIFESTS ARE READ FIRST, before a single render and
      ;; long before the mint overwrites them. Ordering is load-bearing twice:
      ;; the writes below destroy exactly what the golden lane compares
      ;; against, and `golden/read-manifest` throws on a truncated or empty
      ;; manifest — so a damaged one kills the run in a second rather than
      ;; after a ninety-second corpus render.
      (let [committed (update-vals golden-manifest-paths golden/read-manifest)
            {:keys [findings counts manifests]} (run-generate spec built)
            inventory (composition/load-inventory)
            comp-built (composition/build-all inventory)
            comp-run (run-composition inventory comp-built)
            comp-run (update comp-run :artifacts into
                             (persist-cross-engine-atomic! built))
            ;; The label→(committed, fresh) pairing. It lives here because the
            ;; fresh maps do, and this ns cannot be loaded by a test — which is
            ;; why `gates/golden-drift-findings` refuses an empty side rather
            ;; than trusting it: a mis-paired label lands on nil and throws
            ;; naming the label, instead of comparing nothing and passing.
            golden-findings
            (gates/golden-drift-findings
             [{:label :atomic-dark
               :committed (:cards (:atomic-dark committed))
               :fresh (:cards (:dark manifests))}
              {:label :atomic-light
               :committed (:cards (:atomic-light committed))
               :fresh (:cards (:light manifests))}
              {:label :composition-dark
               :committed (:cards (:composition-dark committed))
               :fresh (:cards (:dark (:manifests comp-run)))}
              {:label :composition-light
               :committed (:cards (:composition-light committed))
               :fresh (:cards (:light (:manifests comp-run)))}
              {:label :stock-atomic-dark
               :committed (:cards (:stock-atomic-dark committed))
               :fresh (:cards (:stock-dark manifests))}
              {:label :stock-atomic-light
               :committed (:cards (:stock-atomic-light committed))
               :fresh (:cards (:stock-light manifests))}
              {:label :stock-composition-dark
               :committed (:cards (:stock-composition-dark committed))
               :fresh (:cards (:stock-dark (:manifests comp-run)))}
              {:label :stock-composition-light
               :committed (:cards (:stock-composition-light committed))
               :fresh (:cards (:stock-light (:manifests comp-run)))}])
            manifest-writes [[(:atomic-dark golden-manifest-paths)
                              (:dark manifests)]
                             [(:atomic-light golden-manifest-paths)
                              (:light manifests)]
                             [(:composition-dark golden-manifest-paths)
                              (:dark (:manifests comp-run))]
                             [(:composition-light golden-manifest-paths)
                              (:light (:manifests comp-run))]
                             [(:stock-atomic-dark golden-manifest-paths)
                              (:stock-dark manifests)]
                             [(:stock-atomic-light golden-manifest-paths)
                              (:stock-light manifests)]
                             [(:stock-composition-dark golden-manifest-paths)
                              (:stock-dark (:manifests comp-run))]
                             [(:stock-composition-light golden-manifest-paths)
                              (:stock-light (:manifests comp-run))]]
            _ (doseq [[path m] manifest-writes] (golden/write-manifest! m path))
            goldens-audit
            (docgen/run-audit "goldens"
                              (map first manifest-writes)
                              (:goldens audit-details))
            composition-audit
            (docgen/run-audit composition-out-dir
                              (:artifacts comp-run)
                              (:composition audit-details))
            all-findings (-> (vec findings)
                             (into (:findings comp-run))
                             (into golden-findings)
                             (into (:findings goldens-audit))
                             (into (:findings composition-audit)))]
        (persist-findings! all-findings)
        (println (:line goldens-audit))
        (println (:line composition-audit))
        (println "renders:" (:renders counts)
                 " elapsed:" (format "%.1fs" (double (:elapsed-s counts))))
        ;; PRINTED so growth in the declaration list is visible. The stale clause
        ;; bounds REMOVAL only — an entry that stops matching reds — and nothing
        ;; bounds addition, so without this line a green run reads identically
        ;; whether the corpus absorbed nothing or absorbed everything.
        (println "designed-flag declarations in force:"
                 (:designed-flag-entries counts))
        (println "composition renders:" (:composition-renders (:counts comp-run))
                 " cards:" (:composition-cards (:counts comp-run))
                 " elapsed:" (format "%.1fs" (double (:elapsed-s (:counts comp-run)))))
        ;; NO DECISION LIVES HERE. The source-text canary in lanes_test pins
        ;; this entire call/print/exit form, and run-verdict is TOTAL.
        (let [{:keys [lines exit]} (lanes/run-verdict all-findings)]
          (doseq [l lines] (println l))
          (System/exit exit)))
      "gallery"
      (let [t0 (System/nanoTime)
            inventory (composition/load-inventory)
            comp-built (composition/build-all inventory)
            {:keys [files images pages cells]}
            (docs/generate! {:spec spec
                             :built built
                             :composition {:cards (:cards inventory)
                                           :built comp-built}
                             :paths {:wasm wasm-path :assets assets-path}})
            total (reduce + (map :bytes files))
            docs-audit (docgen/run-audit docs/audit-root
                                         (map :path files)
                                         (:docs audit-details))
            audit-findings (:findings docs-audit)]
        (persist-findings! audit-findings)
        (doseq [{:keys [path] n :bytes} files]
          (println (format "  %-64s %8d bytes" path n)))
        (println
         (format
          "gallery: %d cell renders, %d images, %d pages, %d files, %d bytes total, %.1fs"
          cells
          images
          pages
          (count files)
          total
          (/ (- (System/nanoTime) t0) 1e9)))
        (println (:line docs-audit))
        ;; The gallery arm uses the SAME testable, total verdict as generate;
        ;; no direct `(if (seq audit-findings) ...)` decision lives here.
        (let [{:keys [lines exit]} (lanes/run-verdict audit-findings [])]
          (doseq [l lines] (println l))
          (System/exit exit)))
      (do (println "unknown mode" mode) (System/exit 2)))))
