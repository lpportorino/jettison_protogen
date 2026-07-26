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
   `verify` re-renders family-0 against the committed manifests.

   `gallery` is the T2.7 doc build (recorded call: a core mode, not a
   dev/ script — the pipeline has ONE CLI and the gallery is a pipeline
   product, not a probe): renders the corpus once per committed family set
   (vanilla-dark, asgard-dark, asgard-light) and writes the per-widget
   contact sheets + generated doc pages under docs/widgets/
   (devcards.gallery + devcards.docs).

   Exit code is the verdict: non-zero on ANY finding; counts always print —
   silence is never success."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [devcards.composition :as composition]
            [devcards.docs :as docs]
            [devcards.expect :as expect]
            [devcards.findings :as findings]
            [devcards.fixtures :as fixtures]
            [devcards.gates :as gates]
            [devcards.golden :as golden]
            [devcards.host :as host]
            [devcards.interaction :as interaction])
  (:gen-class))

(set! *warn-on-reflection* true)

(def render-protocol
  "The pinned per-card render protocol (mirrors the conventions manifest)."
  {:ticks 3 :tick-ms 16 :dpi 160 :canvas {:w 800 :h 480}})

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

(defn- invariant-findings-for
  "Invariant findings for one card, THROUGH the registry. `:expect` is
   supplied even when nil (the kitchen-sink case) because the registry
   treats a nil value as absent, and the lane must judge those cards
   rather than refuse them."
  [id expect tree]
  (:live (findings/card-findings {:card-id (str id)
                                  :tree tree
                                  :caps {:vis-px? true}
                                  :expect (or expect :judged)
                                  :producers [expect/tree-producer]})))

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
                           (invariant-findings-for id expect tree))
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
  "Write `b` at `path` (parents created)."
  [^String path ^bytes b]
  (io/make-parents path)
  (with-open [o (io/output-stream path)] (.write o b))
  nil)

(defn- comp-slug
  "Composition card id -> flat artifact slug (ids contain '/')."
  ^String [id]
  (str/replace (str id) "/" "_"))

(def composition-out-dir
  "Where the composition lane persists each card's .pb + raw
   framebuffers — the SAME bytes the wasmtime interaction suite
   re-renders and byte-compares
   (renderer/wasm_harness/tests/composition_interaction.rs reads this
   tree repo-relatively)."
  "out/composition")

(defn run-composition
  "The authored-composition lane over built composition entries
   (devcards.composition/build-all output): family-0 renders (dark +
   light) under the full invariant + render-time-emission lanes, the
   vanilla≡stock family pin (both modes), the :inert-prop
   pixel-inertness pin, the golden manifests, and the GraalWasm
   interaction lane. Returns {:findings :counts :manifests
   {:dark m :light m}}."
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
                          (persist-bytes! (str composition-out-dir "/cards/"
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
                                          (:fb light))
                          [(str id)
                           {:dark-hash (golden/sha256-hex (:fb dark))
                            :light-hash (golden/sha256-hex (:fb light))
                            :inv (:live (findings/card-findings
                                         {:card-id (str id)
                                          :tree (:tree dark)
                                          :caps {:vis-px? true}
                                          :host-proxy? false
                                          :emissions-by-mode
                                          {:dark (:emissions dark)
                                           :light (:emissions light)}
                                          :producers
                                          [(findings/builtin-producer :tree)
                                           findings/emission-by-mode-producer]}))}])))
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
     :counts {:composition-cards (count built)
              :composition-renders (* (count built) 6)
              :composition-findings (count findings)
              :elapsed-s (/ (- (System/nanoTime) t0) 1e9)}
     :manifests {:dark (manifest-of (update-vals f0 :dark-hash))
                 :light (manifest-of (update-vals f0 :light-hash))}}))

(defn -main
  "CLI: `generate` renders + judges + writes goldens/manifest-{dark,light}
   .edn; non-zero exit on any finding."
  [& [mode]]
  (let [spec (fixtures/load-spec)
        built (fixtures/build-all spec)]
    (case (or mode "generate")
      "generate"
      (let [{:keys [findings counts manifests]} (run-generate spec built)
            inventory (composition/load-inventory)
            comp-built (composition/build-all inventory)
            comp-run (run-composition inventory comp-built)
            all-findings (into (vec findings) (:findings comp-run))]
        (golden/write-manifest! (:dark manifests) "goldens/manifest-dark.edn")
        (golden/write-manifest! (:light manifests) "goldens/manifest-light.edn")
        (golden/write-manifest! (:dark (:manifests comp-run))
                                "goldens/manifest-composition-dark.edn")
        (golden/write-manifest! (:light (:manifests comp-run))
                                "goldens/manifest-composition-light.edn")
        ;; Persist the FULL findings vector every run (console truncates at
        ;; 40) — triage reads out/findings.edn, the exit code stays the gate.
        (io/make-parents "out/findings.edn")
        (with-open [w (io/writer "out/findings.edn")] (pp/pprint all-findings w))
        (println "renders:" (:renders counts)
                 " elapsed:" (format "%.1fs" (double (:elapsed-s counts))))
        (println "composition renders:" (:composition-renders (:counts comp-run))
                 " cards:" (:composition-cards (:counts comp-run))
                 " elapsed:" (format "%.1fs" (double (:elapsed-s (:counts comp-run)))))
        (println "findings:" (count all-findings)
                 " by lane:"
                 (frequencies (map #(or (:gate %) (:invariant %)) all-findings)))
        (doseq [f (take 40 all-findings)] (prn f))
        (when (> (count all-findings) 40)
          (println "…" (- (count all-findings) 40) "more"))
        (System/exit (if (seq all-findings) 1 0)))
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
            total (reduce + (map :bytes files))]
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
        (System/exit 0))
      (do (println "unknown mode" mode) (System/exit 2)))))