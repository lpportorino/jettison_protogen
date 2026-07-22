(ns renderer-gen.renderer-caps-json
  "Emit `renderer-caps.json` — the renderer static-contract manifest
   (a ratified home-move decision): the pool caps (renderer.c's MAX_*
   #defines), the deliberately-uncounted caps with their proof rationales,
   and the compiled-in font ladder. Consumers: the authoring consumer's
   headroom COUNTING half (which reads caps from this pinned manifest
   instead of a source mirror) and any binding consumer that needs the
   renderer's capacity facts.

   Generation IS the mirror check (the same three assertions
   lvgl-codegen.renderer-caps-test makes): renderer.c is parsed as the home,
   and the manifest is emitted from lvgl-codegen.renderer-caps' declared
   tables only after proving
     - every counted cap equals its renderer.c #define value,
     - every renderer.c MAX_* is either counted or rationale-allowlisted
       (and never both, and no allowlisted name is absent from the source),
     - the compiled-in font set equals resolve_font's strcmp ladder.
   Any drift throws — a manifest is never emitted from a lying mirror.

   Shape (CLOSED):
     {\"source\": \"renderer/src/renderer.c\",
      \"caps\": {<concern> {\"define\": MAX_*, \"cap\": int}},
      \"non-headroom-caps\": {MAX_* <rationale string>},
      \"compiled-in-fonts\": [<font symbol> …]}

   Run (from tools/renderer-gen/):
     clojure -M -m renderer-gen.renderer-caps-json \\
       --renderer ../../renderer/src/renderer.c \\
       --output ../../output/manifests/renderer-caps.json"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [lvgl-codegen.renderer-caps :as renderer-caps])
  (:gen-class))

(set! *warn-on-reflection* true)

(def manifest-source
  "The renderer source the caps are parsed from, as protogen consumers see
   it."
  "renderer/src/renderer.c")

(defn parse-max-defines
  "Every `#define MAX_* <int>` in the renderer source: {define-name value}.
   Value-shaped defines only — the name-only scan below catches the rest."
  [source]
  (into {}
        (map (fn [[_ define value]] [define (Long/parseLong value)]))
        (re-seq #"#define\s+(MAX_[A-Z_]+)\s+(\d+)" source)))

(defn parse-max-names
  "Every `#define MAX_*` NAME in the renderer source, independent of value
   shape — a paren/macro-valued cap must still be caught by the
   completeness check."
  [source]
  (into #{} (map second) (re-seq #"#define\s+(MAX_[A-Z_]+)\b" source)))

(defn parse-font-ladder
  "The compiled-in font symbols of resolve_font's strcmp ladder
   (layout-tolerant, the mirror test's own pattern)."
  [source]
  (into #{} (map second) (re-seq #"(?s)strcmp\(\s*name,\s*\"([^\"]+)\"\s*\)" source)))

(defn check-mirror!
  "The three mirror assertions (ns docstring); throws with the exact drift on
   any failure, else returns nil."
  [source]
  (let [values (parse-max-defines source)
        defined (parse-max-names source)
        counted (into #{} (map (comp :define val)) renderer-caps/caps)
        allowlisted (set (keys renderer-caps/non-headroom-caps))]
    (when (empty? defined)
      (throw (ex-info "no MAX_* defines found — wrong renderer source?"
                      {:source manifest-source})))
    (doseq [[k {:keys [define cap]}] renderer-caps/caps]
      (let [actual (get values define)]
        (when-not (= cap actual)
          (throw (ex-info (str "cap mirror drift on " (name k)
                               ": mirror says " cap
                               " but " define
                               " in renderer.c says " actual)
                          {:concern k :define define :mirror cap :source actual})))))
    (let [unaccounted (set/difference defined (set/union counted allowlisted))
          orphaned (set/difference allowlisted defined)
          doubled (set/intersection counted allowlisted)]
      (when (seq unaccounted)
        (throw (ex-info (str "renderer.c MAX_* neither counted nor allowlisted: "
                             unaccounted)
                        {:unaccounted unaccounted})))
      (when (seq orphaned)
        (throw (ex-info (str "non-headroom-caps names a #define absent from "
                             "renderer.c: "
                             orphaned)
                        {:orphaned orphaned})))
      (when (seq doubled)
        (throw (ex-info (str "a define is BOTH counted and allowlisted: " doubled)
                        {:doubled doubled}))))
    (let [ladder (parse-font-ladder source)]
      (when-not (= ladder renderer-caps/compiled-in-fonts)
        (throw (ex-info
                "compiled-in font mirror drift vs resolve_font's ladder"
                {:ladder-only (set/difference ladder renderer-caps/compiled-in-fonts)
                 :mirror-only (set/difference renderer-caps/compiled-in-fonts ladder)}))))
    nil))

(defn- one-line
  "Collapse a multi-line rationale into single-spaced prose."
  [s]
  (str/join " " (str/split s #"\s+")))

(defn manifest
  "The full manifest map (JSON-ready, deterministically ordered), emitted
   only after `check-mirror!` proves source and mirror agree."
  [source]
  (check-mirror! source)
  {"source" manifest-source
   "caps" (into (sorted-map)
                (map (fn [[k {:keys [define cap]}]] [(name k) {"define" define "cap" cap}]))
                renderer-caps/caps)
   "non-headroom-caps" (into (sorted-map)
                             (map (fn [[define reason]] [define (one-line reason)]))
                             renderer-caps/non-headroom-caps)
   "compiled-in-fonts" (vec (sort renderer-caps/compiled-in-fonts))})

(defn- parse-args
  "Closed --renderer/--output flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--renderer" (assoc acc :renderer value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag :expected #{"--renderer" "--output"}}))))
          {:renderer "src/renderer.c"}
          (partition 2 args)))

(defn -main
  "Parse renderer.c, prove the mirror, emit renderer-caps.json, print the
   cap counts. `--output` is mandatory — this tool never guesses a
   destination."
  [& args]
  (let [{:keys [renderer output]} (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (let [m (manifest (slurp renderer))]
      (io/make-parents output)
      (with-open [w (io/writer output)]
        (json/write m w :indent true :escape-slash false)
        (.write w "\n"))
      (println (str "renderer-caps.json: " (count (get m "caps"))
                    " counted caps, " (count (get m "non-headroom-caps"))
                    " non-headroom caps, " (count (get m "compiled-in-fonts"))
                    " compiled-in fonts -> " output)))))