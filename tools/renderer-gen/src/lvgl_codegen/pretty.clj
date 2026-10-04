(ns lvgl-codegen.pretty
  "Deterministic printer for scalar EDN, vectors, sequences and nested maps
   with keyword/string keys. Sorts map keys without comparing printed key text.
   Sets, tagged literals and other map-key types are outside this domain."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def printable-value
  "Recursive supported input/output domain, checked by test instrumentation.
   Map keys are keywords or strings; leaves are the listed EDN scalar types.
   This annotation is not a runtime input guard outside the instrumented suite."
  [:schema
   {:registry
    {::value [:or :nil :boolean number? :string :keyword :symbol char? uuid? inst?
              [:vector [:ref ::value]]
              [:sequential [:ref ::value]]
              [:map-of [:or :keyword :string] [:ref ::value]]]}}
   [:ref ::value]])

(defn- key-order
  "An injective rank over keyword/string keys: keywords first, then strings.
   Namespace presence, namespace and name stay separate, including nil versus
   empty namespaces. Equal printed representations never merge distinct keys."
  [k]
  (if (keyword? k)
    [0 (if (some? (namespace k)) 1 0) (or (namespace k) "") (name k)]
    [1 0 "" k]))

(defn- sort-map-keys
  "Recursively sort map keys for deterministic pretty-print output.
   Keywords sort by namespace presence, namespace and name; strings follow in
   lexical order. The typed comparator preserves every supported map entry."
  [data]
  (cond (map? data) (into (sorted-map-by #(compare (key-order %1) (key-order %2)))
                          (map (fn [[k v]] [k (sort-map-keys v)]))
                          data)
        (vector? data) (mapv sort-map-keys data)
        (sequential? data) (map sort-map-keys data)
        :else data))

(defn pp-str
  "Pretty-print a supported value with deterministic keyword/string map order.
   Accepts the recursive printable-value domain. Collection kinds and scalar
   representations are preserved; this does not canonicalize all EDN values."
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
(m/=> key-order [:=> [:cat [:or :keyword :string]] [:tuple :int :int :string :string]])

(m/=> sort-map-keys [:=> [:cat printable-value] printable-value])

(m/=> pp-str [:=> [:cat printable-value] string?])

(m/=> diff-strs [:=> [:cat string? string?] [:map [:equal? boolean?] [:diff string?]]])
