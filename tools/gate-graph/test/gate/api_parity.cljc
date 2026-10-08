(ns gate.api-parity
  "Portable synthetic API identity/adoption corpus; compare complete canonical outputs across runtimes."
  (:require [gate.admission :as admission]
            [gate.api :as api]
            [gate.api-contract :as ac]
            [gate.canonical :as canonical]
            [malli.core :as m]))

(def a (apply str (repeat 64 "a")))
(def b (apply str (repeat 64 "b")))
(def material
  {:schema/version 1 :profile :source-closure-v1 :api-version 1 :source a
   :entries [{:id "example.query/page!" :kind :operation :role :tool :invocation :function
              :platforms [:cljs :clj] :capabilities ["query"] :definition a :arguments a :result a}]})
(def document (api/manifest material))
(def record
  {:schema/version 1 :consumer-api-version 1 :acceptance-version 1 :manifest (:artifact document)
   :surface a :exports ["example.toolbox/drill"]
   :decisions [{:id "example.query/page!" :action :adopt :wrappers ["example.toolbox/drill"]
                :reason "Unicode review: λ 🌍\nA real newline and a quote: \"."}]})
(def request {:manifest document :acceptance record :previous nil :surface a :exports (:exports record)})
(defn capture
  "Retain either the canonical result or exact structured refusal for differential comparison."
  [f]
  (try (let [value (f)] (if (nil? value) "nil" (canonical/encode value 65536)))
       (catch #?(:clj Exception :cljs :default) error
         (canonical/encode (ex-data error) 65536))))
(m/=> capture [:=> [:cat ifn?] :string])
(defn oversized
  "Construct a schema-valid inventory exceeding the canonical manifest byte budget."
  []
  (let [caps (mapv #(str (apply str (repeat 156 "a")) %) (range 32))]
    (assoc material :entries
           (mapv (fn [i] {:id (str "fixture.values/v" i) :kind :value :platforms [:clj]
                          :definition a :capabilities caps}) (range 4096)))))
(m/=> oversized [:=> [:cat] ac/Material])
(defn results
  "Exercise identity, acceptance, Unicode and refusal boundaries with public synthetic values."
  []
  (mapv capture
        [#(api/manifest material)
         #(api/verify! document)
         #(api/check! request)
         #(admission/decode (canonical/encode record 65536) :value admission/default-limits)
         #(api/check! (assoc request :surface b))
         #(api/check! (assoc request :exports []))
         #(api/check! (assoc-in request [:acceptance :manifest] b))
         #(api/check! (assoc-in request [:acceptance :decisions] []))
         #(api/check! (assoc-in request [:acceptance :extra] true))
         #(api/check! (-> request (assoc :previous record)
                          (assoc-in [:acceptance :decisions 0 :reason] "New reason.")))
         #(api/check! (-> request (assoc :previous record)
                          (assoc-in [:acceptance :decisions 0 :reason] "New reason.")
                          (assoc-in [:acceptance :acceptance-version] 2)))
         #(api/verify-upgrade! document (api/manifest (assoc-in material [:entries 0 :arguments] b)))
         #(api/verify-upgrade! document (api/manifest (assoc material :source b)))
         #(api/manifest (update material :entries conj (first (:entries material))))
         #(api/manifest (oversized))]))
(m/=> results [:=> [:cat] [:vector {:min 15 :max 15} :string]])
(defn -main
  "Print exactly one deterministic line containing all fifteen results and refusals."
  [& _]
  (let [values (results)]
    (assert (= 15 (count values)))
    (println (str "PARITY " (pr-str values)))))
(m/=> -main [:=> [:cat [:* :string]] :nil])

#?(:cljs (set! *main-cli-fn* -main))
