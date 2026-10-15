(ns gate.portable
  "Bounded lexical values shared by explicit codecs; this domain never admits a report contract."
  (:require [malli.core :as m]))

(def Value
  "Data representable by the bounded EDN profile; caller-specific vocabularies and schemas remain mandatory."
  [:schema {:registry {::value [:or :nil :boolean [:int {:min 0 :max 2147483647}]
                                [:string {:max 4096}] :keyword
                                [:vector {:max 1000000} [:ref ::value]]
                                [:map-of {:max 1000000} :keyword [:ref ::value]]]}}
   [:ref ::value]])
(def Input
  "Untrusted caller data at a self-validating boundary; native collections are bounded before traversal."
  [:or :nil :boolean :int :string :keyword [:fn coll?]])

(defn require-bounded!
  "Refuse unsupported objects, invalid Unicode and excessive depth before recursive schema validation.
   Only plain maps and vectors are counted, and both count in constant time; every other collection,
   a record included, is refused by shape before anything reads it, so an unbounded sequence cannot
   stall this boundary and no type is lost on the way back. Integers are the fixed-precision kind."
  [value]
  (loop [pending [[value 0]] visited 0]
    (when (seq pending)
      (let [[item depth] (peek pending) remaining (pop pending)
            oversize? #(> (count %) 1000000)]
        (when (or (> depth 64) (>= visited 16777216))
          (throw (ex-info "Portable value refused" {:code :portable-limit})))
        (cond
          (record? item) (throw (ex-info "Portable value refused" {:code :portable-shape}))
          (map? item)
          (do (when (oversize? item) (throw (ex-info "Portable value refused" {:code :portable-limit})))
              (when-not (every? keyword? (keys item))
                (throw (ex-info "Portable value refused" {:code :portable-shape})))
              (recur (into remaining (map #(vector % (inc depth)) (mapcat identity item))) (inc visited)))
          (vector? item)
          (do (when (oversize? item) (throw (ex-info "Portable value refused" {:code :portable-limit})))
              (recur (into remaining (map #(vector % (inc depth)) item)) (inc visited)))
          :else
          (do
            (when-not (or (nil? item) (boolean? item)
                          (and (int? item) (<= 0 item 2147483647))
                          (and (keyword? item) (<= (count (str item)) 256))
                          (and (string? item) (<= (count item) 4096)
                               (loop [i 0]
                                 (if (= i (count item)) true
                                     (let [u #?(:clj (int (.charAt ^String item i)) :cljs (.charCodeAt item i))]
                                       (cond
                                         (<= 56320 u 57343) false
                                         (<= 55296 u 56319)
                                         (and (< (inc i) (count item))
                                              (<= 56320 #?(:clj (int (.charAt ^String item (inc i)))
                                                           :cljs (.charCodeAt item (inc i))) 57343)
                                              (recur (+ i 2)))
                                         :else (recur (inc i))))))))
              (throw (ex-info "Portable value refused" {:code :portable-shape})))
            (recur remaining (inc visited)))))))
  value)
(m/=> require-bounded! [:=> [:cat Input] Value])
