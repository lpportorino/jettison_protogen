(ns lvgl-codegen.pretty
  "Deterministic EDN pretty-printer for diff-friendly comparison.
   Sorts map keys for canonical output so structural equivalence
   implies string equivalence."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(defn- sort-map-keys
  "Recursively sort map keys for deterministic pretty-print output.
   Keywords sort before strings, both alphabetically."
  [data]
  (cond (map? data) (into (sorted-map) (map (fn [[k v]] [k (sort-map-keys v)])) data)
        (vector? data) (mapv sort-map-keys data)
        (sequential? data) (map sort-map-keys data)
        :else data))

(defn pp-str
  "Pretty-print any EDN value to a canonical string.
   Map keys are sorted for deterministic output."
  [data]
  (let [sorted (sort-map-keys data)] (with-out-str (pp/pprint sorted))))

(defn diff-strs
  "Line-by-line diff between two pretty-printed EDN strings.
   Returns {:equal? bool :diff String}."
  [^String expected ^String actual]
  (let [exp-lines (str/split-lines expected)
        act-lines (str/split-lines actual)
        max-lines (max (count exp-lines) (count act-lines))
        diffs (for [idx (range max-lines)
                    :let [exp-line (get (vec exp-lines) idx)
                          act-line (get (vec act-lines) idx)]
                    :when (not= exp-line act-line)]
                (str "line "
                     (inc idx)
                     ":\n"
                     "  - "
                     (or exp-line "<missing>")
                     "\n"
                     "  + "
                     (or act-line "<missing>")))]
    {:equal? (empty? diffs) :diff (str/join "\n" diffs)}))

;; -- Function schema registrations --
(m/=> sort-map-keys [:=> [:cat :any] :any])

(m/=> pp-str [:=> [:cat :any] string?])

(m/=> diff-strs [:=> [:cat string? string?] [:map [:equal? boolean?] [:diff string?]]])