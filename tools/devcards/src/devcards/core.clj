(ns devcards.core
  "The devcard pipeline CLI — spec → build → render → judge.

   `generate` renders the FULL corpus (every atomic card + kitchen sink)
   across the three theme families × dark/light, then:
   - family 0 (asgard, the shipped look): invariants over the dump trees
     with the spec's :expect routing — :probe-defect cells must EXHIBIT at
     least one defect flag, :probe-pixel-only cells are dump-blind and
     skipped, everything else runs the full lanes — plus the golden
     manifests (dark + light).
   - families 1/2 (vanilla/stock): per-card hash equality, BOTH modes (the
     vanilla arms carry dark-conditional stock colors — light must hold
     too).
   - the state-contract lanes (distinctness/inertness) over family-0 dark.
   - the AUTHORED-COMPOSITION lane (corpus/composition.edn via
     devcards.composition): the same family-0 invariants + goldens
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
  "label → committed golden manifest path. THE ONE HOME of these four paths:
   the golden-drift lane READS each of them and the mint WRITES the same file,
   and two literal lists would be free to drift into verifying a file nothing
   writes — a gate that can only ever be green."
  {:atomic-dark "goldens/manifest-dark.edn"
   :atomic-light "goldens/manifest-light.edn"
   :composition-dark "goldens/manifest-composition-dark.edn"
   :composition-light "goldens/manifest-composition-light.edn"})

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
                 (map (fn [{:keys [id expect] ^bytes pb :bytes}]
                        (let [dark (render-one! pb {:family 0 :dark true :dump? true})
                              light (render-one! pb {:family 0 :dark false})]
                          [(str id)
                           {:dark-hash (golden/sha256-hex (:fb dark))
                            :light-hash (golden/sha256-hex (:fb light))
                            :expect expect
                            :tree (:tree dark)}])))
                 built)
        fam-hashes (fn [family dark]
                     (into {}
                           (map
                            (fn [{:keys [id] ^bytes pb :bytes}]
                              [(str id)
                               (golden/sha256-hex
                                (:fb (render-one! pb {:family family :dark dark})))]))
                           built))
        f1d (fam-hashes 1 true)
        f2d (fam-hashes 2 true)
        f1l (fam-hashes 1 false)
        f2l (fam-hashes 2 false)
        inv (vec (mapcat (fn [[id {:keys [expect tree]}]]
                           (lanes/atomic-findings id expect tree))
                         f0))
        gate-res (gates/run-gates spec {0 (update-vals f0 :dark-hash) 1 f1d 2 f2d})
        vs-light (mapv #(assoc % :mode :light) (gates/vanilla-stock-findings f1l f2l))
        findings (-> (:findings gate-res)
                     (into vs-light)
                     ;; the kitchen sinks' RENDERED trees are authored here in
                     ;; Clojure (fixtures/kitchen-sink-trees), not in spec.edn —
                     ;; the secret-scan must see them, or a landmark in a sink's
                     ;; text ships unscanned
                     (into (gates/corpus-secret-findings
                            (map (fn [[id tree]] {:id id :node tree})
                                 fixtures/kitchen-sink-trees)))
                     (into inv))]
    {:findings findings
     :counts (assoc (:counts gate-res)
                    :renders (* (count built) 6)
                    :invariant-findings (count inv)
                    :elapsed-s (/ (- (System/nanoTime) t0) 1e9))
     :manifests {:dark (manifest-of (update-vals f0 :dark-hash))
                 :light (manifest-of (update-vals f0 :light-hash))}}))

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
  (let [t0 (System/nanoTime)
        f0 (into {}
                 (map (fn [{:keys [id] ^bytes pb :bytes}]
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
                                  id (:tree dark)
                                  {:dark (:emissions dark)
                                   :light (:emissions light)})}])))
                 built)
        fam-hashes (fn [family dark]
                     (into {}
                           (map (fn [{:keys [id] ^bytes pb :bytes}]
                                  [(str id)
                                   (golden/sha256-hex
                                    (:fb (render-one! pb
                                                      {:family family :dark dark})))]))
                           built))
        f1d (fam-hashes 1 true)
        f2d (fam-hashes 2 true)
        f1l (fam-hashes 1 false)
        f2l (fam-hashes 2 false)
        inv-findings (vec (mapcat (comp :inv val) (sort-by key f0)))
        vs (-> (vec (gates/vanilla-stock-findings f1d f2d))
               (into (mapv #(assoc % :mode :light)
                           (gates/vanilla-stock-findings f1l f2l))))
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
     :artifacts (vec (mapcat (comp :artifacts val) (sort-by key f0)))
     :counts {:composition-cards (count built)
              :composition-renders (* (count built) 6)
              :composition-findings (count findings)
              :elapsed-s (/ (- (System/nanoTime) t0) 1e9)}
     :manifests {:dark (manifest-of (update-vals f0 :dark-hash))
                 :light (manifest-of (update-vals f0 :light-hash))}}))

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
               :fresh (:cards (:light (:manifests comp-run)))}])
            manifest-writes [[(:atomic-dark golden-manifest-paths)
                              (:dark manifests)]
                             [(:atomic-light golden-manifest-paths)
                              (:light manifests)]
                             [(:composition-dark golden-manifest-paths)
                              (:dark (:manifests comp-run))]
                             [(:composition-light golden-manifest-paths)
                              (:light (:manifests comp-run))]]
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
