(ns t22-smoke
  "T2.2 smoke — the fixture builder's end-to-end proof, three phases:

   1. BUILD: every corpus entry (all atomic cards + all 6 kitchen sinks)
      through `devcards.fixtures`, collecting per-entry failures (no silent
      skips — every unbuilt card is enumerated in the report).
   2. VALIDATE: every built Screen parsed back through the bindings
      (structural round-trip: parse -> re-serialize must be byte-identical)
      and validated with protovalidate (the schema end of the two-ended
      goldens). Per widget class, the first card's parsed tree is
      spot-checked to actually CONTAIN that class's widget type.
   3. RENDER: a representative slice — the first baseline card of EVERY
      widget class + all 6 kitchen sinks — through devcards.host at the
      spec canvas (800x480, the DISP_LARGE tier), fresh context per card,
      asserting a non-empty framebuffer. Deliberately NOT all cards: the
      full-corpus render is T2.8's.

   Exit 0 only when every phase is clean; failures print per-entry and the
   run exits 1. Timing per phase is printed (feeds the T2.6 CI budget).

   Run (in the toolchain container, from tools/devcards/):
     clojure -M:bindings:t22-smoke     # protogen-compiled bindings"
  (:require [devcards.fixtures :as fixtures]
            [devcards.host :as host])
  (:import [build.buf.protovalidate ValidatorFactory]
           [java.security MessageDigest]
           [java.util Arrays]
           [ui UiAst$Screen UiAst$WidgetNode UiAst$WidgetType]))

(set! *warn-on-reflection* true)

(def wasm-path
  "The themed ABI-3 renderer under test (the checkout's own build —
   `make -f renderer.mk wasm` first)."
  "../../renderer/output/controls.wasm")

(def assets-path
  "The render-time asset bundle (fonts + icons via the WASI preopen)."
  "../../renderer/assets")

(def expected-atomic-count
  "Mission-level pin for the atomic corpus size. The spec header + README
   were authored at 226; the T2.5 gate sweep added the
   lv_checkbox/default/medium baseline (same-label pairing rule), making
   227 the committed truth this smoke asserts."
  227)

(def expected-sink-count "Mission-level pin for the kitchen-sink count." 6)

(defn- sha256-hex
  ^String [^bytes b]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn- opaque-count
  "Fully-opaque (A=255) pixel count — a positive floor rules out a blank
   framebuffer."
  ^long [^bytes fb]
  (loop [i 3
         n 0]
    (if (< i (alength fb)) (recur (+ i 4) (if (= -1 (aget fb i)) (inc n) n)) n)))

(defn- ms-since ^long [^long t0] (quot (- (System/nanoTime) t0) 1000000))

;; ── Phase 1: build ──────────────────────────────────────────────────────
(defn build-phase
  "Build every inventory entry, collecting failures instead of dying on the
   first — the report must enumerate every unbuilt card."
  [spec]
  (reduce
   (fn [acc entry]
     (let [r (try {:ok (fixtures/build-entry spec entry)} (catch Exception e {:error e}))]
       (if-some [^bytes bytes (:ok r)]
         (update acc
                 :built
                 conj
                 (assoc (select-keys entry [:kind :id :widget :type :expect]) :bytes bytes))
         (update acc
                 :failures
                 conj
                 {:id (:id entry)
                  :widget (:widget entry)
                  :error (ex-message (:error r))
                  :data (ex-data (:error r))}))))
   {:built [] :failures []}
   (fixtures/entries spec)))

;; ── Phase 2: validate (schema end + structural round-trip) ──────────────
(defn- tree-contains-type?
  "Walk a parsed WidgetNode tree looking for `wt`."
  [^UiAst$WidgetNode node ^UiAst$WidgetType wt]
  (or (= wt (.getType node))
      (some #(tree-contains-type? ^UiAst$WidgetNode % wt) (.getChildrenList node))))

(defn validate-phase
  "Parse + protovalidate + byte-round-trip every built screen; spot-check
   one parsed tree per widget class for its class's widget type."
  [spec built]
  (let [validator (.build (ValidatorFactory/newBuilder))
        failures (into
                  []
                  (keep
                   (fn [{:keys [id ^bytes bytes]}]
                     (try (let [screen (UiAst$Screen/parseFrom bytes)
                                result (.validate validator screen)]
                            (cond (not (.isSuccess result))
                                  {:id id
                                   :error "protovalidate violations"
                                   :data {:violations (mapv str (.getViolations result))}}
                                  (not (Arrays/equals bytes (.toByteArray screen)))
                                  {:id id :error "parse -> re-serialize not byte-identical"}
                                  :else nil))
                          (catch Exception e
                            {:id id :error (ex-message e) :data (ex-data e)}))))
                  built)
        by-id (into {} (map (juxt :id identity)) built)
        spot-failures
        (into []
              (keep
               (fn [{:keys [tag type cards]}]
                 (let [first-id (:id (first cards))
                       entry (get by-id first-id)]
                   (cond (nil? entry)
                         {:id first-id :widget tag :error "spot-check card was never built"}
                         :else (let [screen (UiAst$Screen/parseFrom ^bytes (:bytes entry))
                                     wt (UiAst$WidgetType/valueOf (name type))]
                                 (when-not (tree-contains-type? (.getRoot screen) wt)
                                   {:id first-id
                                    :widget tag
                                    :error (str "decoded tree does not contain "
                                                (name type))}))))))
              (:widgets spec))]
    {:failures failures :spot-failures spot-failures}))

;; ── Phase 3: render the representative slice ────────────────────────────
(defn render-slice
  "The first :baseline card of every widget class (falling back to the
   class's first card — every class DOES have a baseline, but a fallback
   keeps the slice total even if a spec edit reorders) + every kitchen
   sink."
  [spec built]
  (let [by-id (into {} (map (juxt :id identity)) built)
        atomic-picks (for [w (:widgets spec)]
                       (:id (or (first (filter #(= :baseline (:expect %)) (:cards w)))
                                (first (:cards w)))))
        sink-picks (map :id (:kitchen-sinks spec))]
    (into [] (keep by-id) (concat atomic-picks sink-picks))))

(defn render-phase
  "Render each slice entry under the pinned protocol (fresh context per
   card — the hermetic builder law), asserting a non-empty framebuffer."
  [spec slice]
  (let [{:keys [w h]} (get-in spec [:render :canvas])
        dpi (get-in spec [:render :dpi])]
    (reduce
     (fn [acc {:keys [id ^bytes bytes]}]
       (let [t0 (System/nanoTime)
             r (try (let [h* (host/start! {:wasm wasm-path :assets assets-path :w w :h h})]
                      (try
                        (let [fb (host/render-card! h* {:pb bytes :bp 0 :dark 1 :dpi dpi})
                              opaque (opaque-count fb)]
                          (if (pos? opaque)
                            {:ok {:id id
                                  :opaque opaque
                                  :sha (subs (sha256-hex fb) 0 12)
                                  :ms (ms-since t0)}}
                            {:error {:id id :error "blank framebuffer (zero opaque px)"}}))
                        (finally (host/close! h*))))
                    (catch Exception e
                      {:error {:id id :error (ex-message e) :data (ex-data e)}}))]
         (if-some [ok (:ok r)]
           (update acc :rendered conj ok)
           (update acc :failures conj (:error r)))))
     {:rendered [] :failures []}
     slice)))

;; ── Report + main ───────────────────────────────────────────────────────
(defn- print-failures!
  [phase failures]
  (doseq [{:keys [id widget error data]} failures]
    (println (format "  FAIL [%s] %s%s: %s%s"
                     (name phase)
                     id
                     (if widget (str " (" widget ")") "")
                     error
                     (if data (str " " (pr-str data)) "")))))

(defn -main
  [& _]
  (let [spec (fixtures/load-spec)
        ;; The render-protocol mirror check: the spec pins what host.clj
        ;; hardcodes; drift between them would silently render a different
        ;; contract than the corpus declares.
        _ (when-not (and (= host/render-ticks (get-in spec [:render :render-ticks]))
                         (= host/tick-ms (get-in spec [:render :tick-ms]))
                         (= host/default-dpi (get-in spec [:render :dpi])))
            (throw (ex-info "spec :render protocol disagrees with devcards.host pins"
                            {:spec (:render spec)
                             :host {:render-ticks host/render-ticks
                                    :tick-ms host/tick-ms
                                    :dpi host/default-dpi}})))
        t-build (System/nanoTime)
        {:keys [built failures]} (build-phase spec)
        build-ms (ms-since t-build)
        atomic (count (filter #(= :atomic (:kind %)) built))
        sinks (count (filter #(= :sink (:kind %)) built))
        count-ok? (and (= expected-atomic-count atomic)
                       (= expected-sink-count sinks)
                       (empty? failures))
        t-val (System/nanoTime)
        {val-failures :failures spot-failures :spot-failures} (validate-phase spec built)
        val-ms (ms-since t-val)
        slice (render-slice spec built)
        t-render (System/nanoTime)
        {:keys [rendered] render-failures :failures} (render-phase spec slice)
        render-ms (ms-since t-render)]
    (println (format
              "BUILD    %d entries (%d atomic + %d sinks) in %d ms — expected %d + %d"
              (count built)
              atomic
              sinks
              build-ms
              expected-atomic-count
              expected-sink-count))
    (print-failures! :build failures)
    (when-not count-ok?
      (println "  FAIL [build] entry counts do not match the mission pin"))
    (println
     (format
      "VALIDATE %d screens (protovalidate + byte round-trip) + %d spot-decodes in %d ms"
      (count built)
      (count (:widgets spec))
      val-ms))
    (print-failures! :validate val-failures)
    (print-failures! :spot-decode spot-failures)
    (println (format
              "RENDER   %d/%d slice cards in %d ms (canvas %dx%d, fresh context per card)"
              (count rendered)
              (count slice)
              render-ms
              (get-in spec [:render :canvas :w])
              (get-in spec [:render :canvas :h])))
    (doseq [{:keys [id opaque sha ms]} rendered]
      (println (format "  %-42s opaque=%-7d sha=%s %5d ms" id opaque sha ms)))
    (print-failures! :render render-failures)
    (let [ok? (and count-ok?
                   (empty? val-failures)
                   (empty? spot-failures)
                   (empty? render-failures)
                   (= (count rendered) (count slice)))]
      (println (if ok? "T2.2 SMOKE OK" "T2.2 SMOKE FAILED"))
      (System/exit (if ok? 0 1)))))