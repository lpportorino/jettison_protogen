(ns lvgl-codegen.gesture-thresholds
  "The one home for the native gesture recognizer's tuning constants.

   `edn/gesture-thresholds.edn` owns the values; this namespace loads them,
   carries the declared key↔C-macro mapping, validates the map,
   and emits the generated C header (`generated/gesture_thresholds.h`) the way
   `construct/emit` emits the LVGL LUTs — values become `#define` macros, one
   per threshold, indexed by name.

   The C projection must agree with the EDN home, and the `manifests`
   freshness lane (renderer.mk) is the drift canary — every battery run
   re-emits the header from the home and FATALs if the committed copy
   differs:
     • generated/gesture_thresholds.h — C #defines (emitted here, the lvgl
       host recognizer includes it)

   This is the renderer_caps pattern (`lvgl-codegen.renderer-caps`) applied to
   a native C value table."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def edn-home
  "Tool-relative path to the EDN single source of truth (the -main
   `--tokens` default; the manifests lane runs from the tool root)."
  "edn/gesture-thresholds.edn")

(def thresholds-schema
  "The gesture-threshold map shape: the six tuning constants, closed. px/ms
   values are non-negative ints; pinch-scale is a positive ratio step."
  [:map {:closed true} [:move-px [:int {:min 0}]] [:long-press-ms [:int {:min 0}]]
   [:pinch-scale [:double {:min 0.0}]] [:swipe-px [:int {:min 0}]]
   [:double-tap-ms [:int {:min 0}]] [:double-tap-px [:int {:min 0}]]])

(def fields
  "The declared key↔projection mapping, in canonical emit order. Each entry:
   `:edn-key`    — the kebab key in edn/gesture-thresholds.edn + this ns;
   `:c-macro`    — the `#define` name in generated/gesture_thresholds.h;
   `:int-literal?`   — true for a px/ms count (emit as an int literal), false for
                   the unitless pinch-scale ratio (emit as a float literal);
   `:doc`        — the trailing C comment (mirrors the EDN annotation)."
  [{:edn-key :move-px
    :c-macro "GESTURE_MOVE_PX"
    :int-literal? true
    :doc "px: drag-commit / tap-vs-move boundary"}
   {:edn-key :long-press-ms
    :c-macro "GESTURE_LONG_PRESS_MS"
    :int-literal? true
    :doc "ms: reserved tap-vs-hold guard"}
   {:edn-key :pinch-scale
    :c-macro "GESTURE_PINCH_SCALE"
    :int-literal? false
    :doc "unitless: per-step spread-ratio delta"}
   {:edn-key :swipe-px
    :c-macro "GESTURE_SWIPE_PX"
    :int-literal? true
    :doc "px: reserved long-swipe nav boundary"}
   {:edn-key :double-tap-ms
    :c-macro "GESTURE_DOUBLE_TAP_MS"
    :int-literal? true
    :doc "ms: double-tap -> track time window"}
   {:edn-key :double-tap-px
    :c-macro "GESTURE_DOUBLE_TAP_PX"
    :int-literal? true
    :doc "px: double-tap proximity window"}])

(def field-schema
  "One `fields` entry — the declared projection mapping for a single
   threshold."
  [:map {:closed true} [:edn-key :keyword] [:c-macro [:string {:min 1}]]
   [:int-literal? :boolean] [:doc [:string {:min 1}]]])

(defn load-thresholds
  "Read + validate the gesture-threshold map from the EDN home at `path`.
   Pure clojure.edn (data, never code); throws `ex-info` with the Malli
   explanation when the file drifts from `thresholds-schema` — the home is the
   contract, so a malformed home fails loud here."
  [^String path]
  (let [m (-> path
              slurp
              edn/read-string
              :gesture-thresholds)]
    (when-not (m/validate thresholds-schema m)
      (throw (ex-info "Invalid gesture-thresholds EDN home"
                      {:path path :explain (m/explain thresholds-schema m)})))
    m))
(m/=> load-thresholds [:=> [:cat [:string {:min 1}]] thresholds-schema])

(defn- c-literal
  "Format one threshold value as a C literal: an int for px/ms counts, a
   DOUBLE (decimal point, NO `f` suffix) for the unitless pinch-scale. The FSM
   widens it to `double` and the EDN home's value is f64, so an f32 literal
   (`0.01f`) would DIVERGE at pinch-step boundaries — (double)0.01f ==
   0.009999999776… vs the f64 0.01000000000000000021, ~2.2e-10 apart — making
   the C ratchet cross a step the tuned value does not for a spread-ratio in
   that gap band."
  [value int-literal?]
  (if int-literal?
    (str (long value))
    (let [s (str (double value))] (if (str/includes? s ".") s (str s ".0")))))
(m/=> c-literal [:=> [:cat number? :boolean] [:string {:min 1}]])

(defn thresholds->header
  "Emit the generated C header for `thresholds`: one `#define <C_MACRO>
   <literal>` per declared field, in canonical order, wrapped in an include
   guard with the DO-NOT-EDIT preamble (mirrors `construct/emit/luts->header`).
   `thresholds-schema` is the closed contract on the input — every declared
   field is present by construction (a renamed key fails `load-thresholds` at
   the home boundary, never silently emits a partial header), so the body
   trusts the validated map."
  [thresholds]
  (str "/* GENERATED by lvgl-codegen.gesture-thresholds — DO NOT EDIT.\n"
       " * Regenerate: `make -f renderer.mk manifests`. Home: edn/gesture-thresholds.edn.\n"
       " * Native gesture-recognizer tuning constants.\n"
       " * px -> NDC by the shell (1 NDC = 100px). */\n"
       "#ifndef GESTURE_THRESHOLDS_H\n#define GESTURE_THRESHOLDS_H\n\n"
       (str/join "\n"
                 (for [{:keys [edn-key c-macro int-literal? doc]} fields]
                   (str "#define "
                        c-macro
                        " "
                        (c-literal (get thresholds edn-key) int-literal?)
                        " /* "
                        doc
                        " */")))
       "\n\n#endif /* GESTURE_THRESHOLDS_H */\n"))
(m/=> thresholds->header [:=> [:cat thresholds-schema] [:string {:min 1}]])

(defn generate-header!
  "Write the generated C header (renderer/generated/gesture_thresholds.h)
   from the EDN home. Run via the `manifests` freshness lane (renderer.mk),
   which re-emits the header on every battery run and FATALs on drift."
  [home-path out-path]
  (io/make-parents out-path)
  (spit out-path (thresholds->header (load-thresholds home-path)))
  nil)
(m/=> generate-header! [:=> [:cat [:string {:min 1}] [:string {:min 1}]] :nil])

(defn- parse-args
  "Closed --tokens/--output flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--tokens" (assoc acc :tokens value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag :expected #{"--tokens" "--output"}}))))
          {:tokens edn-home}
          (partition 2 args)))
(m/=> parse-args
      [:=> [:cat [:sequential [:string {:min 1}]]]
       [:map [:tokens [:string {:min 1}]]]])

(defn -main
  "Read the threshold home, emit the gesture_thresholds.h header text to
   `--output`, print the destination. `--output` is mandatory — this tool
   never guesses a destination."
  [& args]
  (let [{:keys [tokens output]} (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (generate-header! tokens output)
    (println (str "gesture_thresholds.h: emitted from " tokens " -> " output))))
(m/=> -main [:=> [:cat [:* [:string {:min 1}]]] :nil])