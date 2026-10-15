(ns gate.extension-schema
  "Finite portable Malli dialect compiled against explicit immutable local definitions."
  (:require [clojure.string :as str]
            [gate.contract :as c]
            [gate.extension-contract :as ec]
            [gate.portable :as p]
            [malli.core :as m]))

(def Counter [:fn #?(:clj #(instance? clojure.lang.Volatile %) :cljs volatile?)])
(def Definitions [:map-of {:max (:definitions ec/limits)} ec/ExtensionId p/Value])
(def Stack [:set {:max (:definitions ec/limits)} ec/ExtensionId])

(defn- properties!
  "Require exactly the declared properties, with no Malli hooks or ambient registry overrides."
  [properties allowed required]
  (when-not (and (map? properties) (every? allowed (keys properties))
                 (every? #(contains? properties %) required)) (ec/refuse! :extension-schema)))
(m/=> properties! [:=> [:cat p/Input [:set :keyword] [:set :keyword]] :nil])

(defn- literal!
  "Accept only bounded lexical scalar literals; container alternatives need tagged schemas."
  [value]
  (when (coll? value) (ec/refuse! :extension-schema))
  (p/require-bounded! value)
  nil)
(m/=> literal! [:=> [:cat p/Input] :nil])

(defn check!
  "Check the entire expanded schema before Malli compiles it; refs cannot hide cycles or fan-out."
  [form definitions stack depth counter]
  (when (or (> depth (:schema-depth ec/limits)) (> (vswap! counter inc) (:schema-nodes ec/limits))) (ec/refuse! :extension-limit))
  (cond
    (contains? #{:nil :boolean} form) nil
    (= form :gate.extension/natural) nil
    (not (vector? form)) (ec/refuse! :extension-schema)
    :else
    (let [[tag properties & children] form
          descend #(check! % definitions stack (inc depth) counter)]
      (case tag
        (:int :string)
        (do (when-not (= 2 (count form)) (ec/refuse! :extension-schema))
            (properties! properties #{:min :max} #{:min :max})
            (when-not (and (integer? (:min properties)) (integer? (:max properties))
                           (<= 0 (:min properties) (:max properties)
                               (if (= tag :int) 2147483647 4096))) (ec/refuse! :extension-schema)))
        := (do (when-not (= 2 (count form)) (ec/refuse! :extension-schema)) (literal! properties))
        :enum (do (when-not (<= 2 (count form) 257) (ec/refuse! :extension-schema))
                  (when-not (= (count (rest form)) (count (set (rest form)))) (ec/refuse! :extension-schema))
                  (doseq [value (rest form)] (literal! value)))
        :maybe (do (when-not (= 2 (count form)) (ec/refuse! :extension-schema)) (descend properties))
        :tuple (do (when-not (<= 2 (count form) 129) (ec/refuse! :extension-schema)) (doseq [child (rest form)] (descend child)))
        :vector (do (when-not (= 3 (count form)) (ec/refuse! :extension-schema))
                    (properties! properties #{:min :max} #{:min :max})
                    (when-not (and (integer? (:min properties)) (integer? (:max properties))
                                   (<= 0 (:min properties) (:max properties) 1000000)) (ec/refuse! :extension-schema))
                    (descend (first children)))
        :ref (do (when-not (and (= 2 (count form)) (or (= :gate.extension/natural properties) (contains? definitions properties))) (ec/refuse! :extension-schema))
                 (when (contains? stack properties) (ec/refuse! :extension-schema))
                 (when-not (= :gate.extension/natural properties)
                   (check! (get definitions properties) definitions (conj stack properties) (inc depth) counter)))
        :map
        (do (when-not (<= 2 (count form) 130) (ec/refuse! :extension-schema))
            (properties! properties #{:closed} #{:closed})
            (when-not (true? (:closed properties)) (ec/refuse! :extension-schema))
            (when-not (and (every? vector? children)
                           (= (count children) (count (set (map first children))))) (ec/refuse! :extension-schema))
            (doseq [entry children]
              (when-not (and (vector? entry) (contains? #{2 3} (count entry)) (keyword? (first entry)))
                (ec/refuse! :extension-schema))
              (when (= 3 (count entry))
                (properties! (second entry) #{:optional} #{:optional})
                (when-not (boolean? (:optional (second entry))) (ec/refuse! :extension-schema)))
              (descend (peek entry))))
        :multi
        (do (properties! properties #{:dispatch} #{:dispatch})
            (when-not (and (keyword? (:dispatch properties)) (<= 1 (count children) (:multi-arms ec/limits))
                           (every? #(and (vector? %) (= 2 (count %))) children)
                           (= (count children) (count (set (map first children))))) (ec/refuse! :extension-schema))
            (doseq [[tag schema :as arm] children]
              (when-not (and (vector? arm) (= 2 (count arm)) (keyword? tag)
                             (not= :malli.core/default tag) (vector? schema) (= :map (first schema))
                             (some #(= [(:dispatch properties) [:= tag]] %) (drop 2 schema)))
                (ec/refuse! :extension-schema))
              (descend schema)))
        (ec/refuse! :extension-schema))))
  nil)
(m/=> check! [:=> [:cat p/Input Definitions Stack [:int {:min 0 :max 64}] Counter] :nil])

(defn contract!
  "Validate and normalize one trusted data-only contract; definitions are local and order-independent."
  [contract counter]
  (p/require-bounded! contract)
  (when-not (m/validate ec/Contract contract) (ec/refuse! :extension-registration))
  (when (or (= "gate" (namespace (:extension/id contract)))
            (str/starts-with? (namespace (:extension/id contract)) "gate.")) (ec/refuse! :extension-registration))
  (let [entries (:payload/definitions contract)
        definitions (into {} (map (juxt :schema/id :schema/form)) entries)]
    (when-not (= (count entries) (count definitions)) (ec/refuse! :extension-duplicate))
    (when (contains? definitions :gate.extension/natural) (ec/refuse! :extension-schema))
    (doseq [[id form] definitions] (check! form definitions #{id} 0 counter))
    (check! (:payload/schema contract) definitions #{} 0 counter)
    (assoc contract :payload/definitions (vec (sort-by (comp str :schema/id) entries)))))
(m/=> contract! [:=> [:cat p/Input Counter] ec/Contract])

(defn compile-schema
  "Compile only the checked dialect using the pinned builtin registry and local immutable definitions."
  [contract]
  (m/schema (:payload/schema contract)
            {:registry (merge (m/default-schemas) {:gate.extension/natural c/Natural}
                              (into {} (map (juxt :schema/id :schema/form)) (:payload/definitions contract)))}))
(m/=> compile-schema [:=> [:cat ec/Contract] [:fn m/schema?]])
