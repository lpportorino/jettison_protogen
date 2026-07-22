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
            [devcards.docs :as docs]
            [devcards.fixtures :as fixtures]
            [devcards.gates :as gates]
            [devcards.golden :as golden]
            [devcards.host :as host]
            [devcards.invariants :as invariants])
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

(defn- render-one!
  "One hermetic render: fresh context, family+mode set, pb loaded. Returns
   {:fb bytes :tree parsed-json-or-nil}."
  [^bytes pb {:keys [family dark dump?]}]
  (let [h (host/start! {:wasm wasm-path
                        :assets assets-path
                        :w (get-in render-protocol [:canvas :w])
                        :h (get-in render-protocol [:canvas :h])})]
    (try (when (pos? (long family)) (host/set-theme-family! h family))
         (let [fb (host/render-card! h {:pb pb :bp 0 :dark (if dark 1 0)})
               tree (when dump? (json/read-str (host/dump-tree! h) :key-fn keyword))]
           {:fb fb :tree tree})
         (finally (host/close! h)))))

(defn- invariant-findings-for
  "Invariant-lane routing by the entry's :expect (ns docstring)."
  [id expect tree]
  (case expect
    :probe-pixel-only []
    :probe-defect (if (some (fn [node] (some #(get node %) invariants/defect-flags))
                            (tree-seq #(seq (:children %)) :children tree))
                    []
                    [{:card id
                      :invariant :probe-defect-absent
                      :detail "cell exists to EXHIBIT a defect flag; none present"}])
    ;; nil expect (kitchen sinks) and every judged expect → full lanes
    (invariants/tree-findings id tree {:vis-px? true})))

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
                 (map (fn [{:keys [id expect ^bytes bytes]}]
                        (let [dark (render-one! bytes {:family 0 :dark true :dump? true})
                              light (render-one! bytes {:family 0 :dark false})]
                          [(str id)
                           {:dark-hash (golden/sha256-hex (:fb dark))
                            :light-hash (golden/sha256-hex (:fb light))
                            :expect expect
                            :tree (:tree dark)}])))
                 built)
        fam-hashes (fn [family dark]
                     (into {}
                           (map
                            (fn [{:keys [id ^bytes bytes]}]
                              [(str id)
                               (golden/sha256-hex
                                (:fb (render-one! bytes {:family family :dark dark})))]))
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
                     (into inv))]
    {:findings findings
     :counts (assoc (:counts gate-res)
                    :renders (* (count built) 6)
                    :invariant-findings (count inv)
                    :elapsed-s (/ (- (System/nanoTime) t0) 1e9))
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
      (let [{:keys [findings counts manifests]} (run-generate spec built)]
        (golden/write-manifest! (:dark manifests) "goldens/manifest-dark.edn")
        (golden/write-manifest! (:light manifests) "goldens/manifest-light.edn")
        ;; Persist the FULL findings vector every run (console truncates at
        ;; 40) — triage reads out/findings.edn, the exit code stays the gate.
        (io/make-parents "out/findings.edn")
        (with-open [w (io/writer "out/findings.edn")] (pp/pprint (vec findings) w))
        (println "renders:" (:renders counts)
                 " elapsed:" (format "%.1fs" (double (:elapsed-s counts))))
        (println "findings:" (count findings)
                 " by lane:" (frequencies (map #(or (:gate %) (:invariant %)) findings)))
        (doseq [f (take 40 findings)] (prn f))
        (when (> (count findings) 40) (println "…" (- (count findings) 40) "more"))
        (System/exit (if (seq findings) 1 0)))
      "gallery"
      (let [t0 (System/nanoTime)
            {:keys [files sheets pages cells]}
            (docs/generate!
             {:spec spec :built built :paths {:wasm wasm-path :assets assets-path}})
            total (reduce + (map :bytes files))]
        (doseq [{:keys [path bytes]} files]
          (println (format "  %-64s %8d bytes" path bytes)))
        (println
         (format
          "gallery: %d cell renders, %d sheets, %d pages, %d files, %d bytes total, %.1fs"
          cells
          sheets
          pages
          (count files)
          total
          (/ (- (System/nanoTime) t0) 1e9)))
        (System/exit 0))
      (do (println "unknown mode" mode) (System/exit 2)))))