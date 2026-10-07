(ns gate.canonical
  "Deterministic EDN encoding and content identities for closed graph/query records."
  (:require #?(:cljs [goog.crypt :as crypt])
            #?(:cljs [goog.crypt.Sha256])
            [gate.contract :as c]
            [gate.run-contract :as r]
            [gate.schema :as schema]
            [malli.core :as m])
  #?(:clj (:import [java.nio.charset StandardCharsets]
                   [java.security MessageDigest])))

(def ByteCount [:int {:min 0 :max 536870912}])
(def ByteLimit [:int {:min 1 :max 134217728}])
(def Text [:string {:max 134217728}])
(def Encodable [:or schema/Encodable r/Encodable])
(def ^:private encodable-schema (m/schema Encodable))

(defn utf8-size
  "Count encoded bytes, including multibyte labels, rather than JVM/JS string units."
  [text]
  #?(:clj (alength (.getBytes ^String text StandardCharsets/UTF_8))
     :cljs (count (crypt/stringToUtf8ByteArray text))))
(m/=> utf8-size [:=> [:cat Text] ByteCount])

(defn sha256
  "Hash UTF-8 text identically across the JVM and Closure crypto implementations."
  [text]
  #?(:clj (let [digest-bytes (.digest (MessageDigest/getInstance "SHA-256")
                                      (.getBytes ^String text StandardCharsets/UTF_8))]
            (apply str (map #(format "%02x" (bit-and 255 %)) digest-bytes)))
     :cljs (let [digest (goog.crypt.Sha256.)]
             (.update digest (crypt/stringToUtf8ByteArray text))
             (crypt/byteArrayToHex (.digest digest)))))
(m/=> sha256 [:=> [:cat Text] c/Digest])

(defn- quote-string
  "Encode UTF-16 code units as portable ASCII EDN, independent of printer bindings."
  [text]
  (str "\""
       (apply str
              (for [i (range (count text))
                    :let [code #?(:clj (int (.charAt ^String text i))
                                  :cljs (.charCodeAt text i))]]
                (cond
                  (= code 34) "\\\""
                  (= code 92) "\\\\"
                  (or (< code 32) (>= code 127))
                  (str "\\u" #?(:clj (format "%04x" code)
                                :cljs (.padStart (.toString code 16) 4 "0")))
                  :else #?(:clj (str (char code)) :cljs (js/String.fromCharCode code)))))
       "\""))
(m/=> quote-string [:=> [:cat [:string {:max 4096}]] [:string {:max 24578}]])

(defn encode
  "Encode a closed value; count each token before retaining it beyond the byte budget."
  [value max-bytes]
  (when-not (m/validate encodable-schema value)
    (throw (ex-info "Value is outside the canonical contract" {:code :invalid-encoding-input})))
  (let [remaining (volatile! max-bytes) parts (volatile! [])]
    (letfn [(emit [token]
              (let [size (utf8-size token)]
                (when (> size @remaining)
                  (throw (ex-info "Canonical encoding byte limit" {:code :encoded-byte-limit})))
                (vswap! remaining - size)
                (vswap! parts conj token)))
            (write-value [item]
              (cond
                (map? item)
                (do (emit "{")
                    (doseq [[index [field child]] (map-indexed vector (sort-by (comp str key) item))]
                      (when (pos? index) (emit " "))
                      (emit (str field)) (emit " ") (write-value child))
                    (emit "}"))
                (vector? item)
                (do (emit "[")
                    (doseq [[index child] (map-indexed vector item)]
                      (when (pos? index) (emit " "))
                      (write-value child))
                    (emit "]"))
                (string? item) (emit (quote-string item))
                (nil? item) (emit "nil")
                :else (emit (str item))))]
      (write-value value)
      (apply str @parts))))
(m/=> encode [:=> [:cat Encodable ByteLimit] Text])

(defn normalize-graph
  "Canonicalize identity-indexed collection order; preserve all original observations."
  [graph]
  (let [graph (cond-> graph
                (contains? graph :partitions)
                (update :partitions #(mapv (fn [assertion] (update assertion :members
                                                                   (fn [ids] (vec (sort ids)))))
                                           (sort-by :id %))))]
    (reduce (fn [result field] (update result field #(vec (sort-by :id %))))
            (-> graph
                (update-in [:run :expected-keys] #(vec (sort %)))
                (update :nodes #(vec (sort-by (juxt :key (fn [node] (or (:attempt node) 0)) :id) %))))
            [:sources :resources :edges :measurements])))
(m/=> normalize-graph [:=> [:cat c/Graph] c/Graph])
