(ns gate.admission-test
  "Admission limits and grammar tested independently of the canonical writer."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as check]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.diagnostic :as diagnostic]
            [gate.fixtures :as f]
            [malli.core :as m]))

(defn load-value [text]
  (admission/decode text :value admission/default-limits))

(defn failure [text overrides]
  (try
    (admission/decode text :value (merge admission/default-limits overrides))
    nil
    (catch clojure.lang.ExceptionInfo error (ex-data error))))

(defn reader-validation-work [size]
  (let [value {:op :sum :measurements (mapv #(str "measurement-" %) (range size))
               :budget {:pairs 32640 :bytes 65536}}
        text (pr-str value) work (atom 0) original reduce
        counted (fn [f] (fn ([] (f)) ([a b] (swap! work inc) (f a b))))]
    (with-redefs [clojure.core/reduce (fn ([f values] (original (counted f) values))
                                        ([f initial values] (original (counted f) initial values)))]
      (is (= value (admission/decode text :aggregate-request admission/default-limits))))
    @work))

(deftest instrumented-reader-does-not-rescan-growing-collections
  ;; Count schema/reader reduction work instead of timing a contested machine.
  (diagnostic/install!)
  (let [small (reader-validation-work 64) large (reader-validation-work 128)]
    (is (pos? small) "The counter must observe real validation work")
    (is (< large (* 5/2 small)) (pr-str {:small small :large large}))))

(deftest canonical-and-human-edn-have-the-same-meaning
  (doseq [value [(f/example) (f/decision) (f/measurement)
                 {:op :nodes} {:op :children :anchor nil}
                 {:op :sum :measurements ["m"] :budget {:pairs 0 :bytes 1024}}]]
    (is (= value (load-value (canonical/encode value 65536))))
    (is (= value (load-value (pr-str value)))))
  (is (= {:op :nodes} (load-value " ; prefix\n { :op, ; key\r\n :nodes } ; suffix"))))

(deftest each-request-target-checks-its-own-closed-contract
  (doseq [[target value]
          [[:query-request {:select {:op :nodes} :budget {:visits 1 :rows 1 :bytes 1024}}]
           [:aggregate-request {:op :sum :measurements ["m"] :budget {:pairs 0 :bytes 1024}}]
           [:diff-request {:select {:op :tasks :changed-only? false}
                           :budget {:visits 1 :rows 1 :bytes 1024}}]]]
    (is (= value (admission/decode (pr-str value) target admission/default-limits)))
    (is (= :invalid-input-shape
           (try (admission/decode "{:op :nodes}" target admission/default-limits)
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))

(deftest graph-admission-enforces-references-beyond-map-shape
  (is (= (f/example) (admission/decode (pr-str (f/example)) :graph admission/default-limits)))
  (let [invalid (assoc-in (f/example) [:nodes 1 :parent] "absent")
        result (try (admission/decode (pr-str invalid) :graph admission/default-limits)
                    (catch clojure.lang.ExceptionInfo error (ex-data error)))]
    (is (= :invalid-graph (:code result)))
    (is (m/validate c/Failure result))
    (is (some #(= :missing-parent (:code %)) (:findings result)))))

(deftest grammar-refusals-never-evaluate-reader-forms
  (doseq [text ["#=(throw (Exception.))" "#inst \"2026-01-01\"" "#foo {}"
                "#{:op :nodes}" "#_{} {:op :nodes}" "(identity {:op :nodes})"
                "'{}" "@x" "^{} {}" "\\a" "1.0" "1/2" "1N" "+1" "-1" "01"
                "" " ; only a comment" "[" "{" "{\"op\" :nodes}" "{:op}" "{:op :nodes]"
                "{:op :nodes :where [}" "[1}" "\"\\q\"" "\"\\u12xz\"" "\"\\u12\""
                "\"unterminated" "\"trailing\\"]]
    (testing text
      (let [result (failure text {})]
        (is (= :invalid-edn (:code result)))
        (is (m/validate c/AdmissionFailure result))))))

(deftest duplicate-keys-are-rejected-before-replacement-values
  (doseq [text ["{:op :nodes :op :nodes}" "{:op :nodes :op #=(anything)}"
                "{:op :nodes :where {:kind :gate :kind :test}}"]]
    (is (= :duplicate-map-key (:code (failure text {}))))))

(deftest unknown-keywords-and-trailing-input-have-named-refusals
  (is (= :unknown-keyword (:code (failure "{:not-in-the-registry :nodes}" {}))))
  (is (= :unknown-keyword (:code (failure "{:op ::nodes}" {}))))
  (doseq [text ["{:op :nodes} {}" "{:op :nodes} #_nil" "{:op :nodes} ]"]]
    (is (= :trailing-input (:code (failure text {}))))))

(deftest lexical-admission-does-not-authorize-open-normalized-maps
  (is (= [] (admission/decode "[]" :value admission/default-limits)))
  (doseq [target [:graph :query-request :view-request]]
    (is (= :invalid-input-shape
           (try (admission/decode "[]" target admission/default-limits)
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))))
  (doseq [text ["{}" "[true]" "nil" "true" "{:op :nodes :id \"extra\"}"
                "{:op :window :interval {:start-ns \"00\" :end-ns \"1\"}}"]]
    (is (= :invalid-input-shape (:code (failure text {}))))))

(deftest byte-limit-counts-source-utf8-including-comments
  (let [text "; λ🌳\n{:op :nodes}" byte-count (alength (.getBytes text "UTF-8"))]
    (is (= {:op :nodes} (admission/decode text :value (assoc admission/default-limits :bytes byte-count))))
    (is (= :input-byte-limit (:code (failure text {:bytes (dec byte-count)}))))
    (is (= :input-byte-limit (:code (failure text {:bytes (count text)})))))
  (is (= :input-byte-limit (:code (failure (str (apply str (repeat 1000 " ")) "{}") {:bytes 16})))))

(deftest source-surrogates-refuse-without-replacement-character-conversion
  (doseq [invalid [(str (char 55296)) (str (char 56320)) (str (char 55296) "a")]]
    (is (= :invalid-input-unicode (:code (failure (str ";" invalid) {}))))))

(deftest escaped-utf16-and-edn-string-escapes-round-trip-exactly
  (doseq [label ["λ🌳\n\r\t\b\f\"\\" (str (char 55296)) (str (char 56320))]]
    (let [value (assoc (f/measurement) :id "m")
          graph (assoc-in (assoc (f/example) :measurements [value]) [:nodes 0 :label] label)
          text (canonical/encode graph 65536)]
      (is (= graph (edn/read-string text)))
      (is (= graph (admission/decode text :graph admission/default-limits)))))
  (is (= "a\n\r\t\b\f\"\\"
         (:label (load-value (str "{:id \"a\" :key \"a\" :label \"a\\n\\r\\t\\b\\f\\\"\\\\\" "
                                  ":parent nil :source \"s\" :kind :gate :record :execution "
                                  ":attempt 1 :outcome :passed :interval {:start-ns \"0\" :end-ns \"1\"}}"))))))

(deftest total-values-counts-map-keys-as-well-as-values
  (is (= {:op :nodes} (admission/decode "{:op :nodes}" :value (assoc admission/default-limits :values 3))))
  (is (= :input-value-limit (:code (failure "{:op :nodes}" {:values 2})))))

(deftest collection-and-depth-bounds-admit-the-exact-boundary
  (let [text "{:op :nodes :where {:kind :gate}}"]
    (is (= {:op :nodes :where {:kind :gate}}
           (admission/decode text :value (assoc admission/default-limits :depth 3 :collection 2))))
    (is (= :input-depth-limit (:code (failure text {:depth 2})))))
  (is (= :input-collection-limit (:code (failure "{:op :nodes :where {}}" {:collection 1})))))

(deftest nested-vectors-spend-limits-before-growing
  (is (= :input-depth-limit (:code (failure (str (apply str (repeat 70 "[")) "0") {}))))
  (is (= :invalid-input-shape
         (:code (failure (str (apply str (repeat 64 "[")) (apply str (repeat 64 "]"))) {}))))
  (is (= :input-collection-limit (:code (failure "[1 2 3]" {:collection 2})))))

(deftest strings-and-tokens-are-bounded-before-retention
  (let [text "{:op :children :anchor \"abc\"}"]
    (is (= {:op :children :anchor "abc"}
           (admission/decode text :value (assoc admission/default-limits :string-units 3 :token-units 9))))
    (is (= :input-string-limit (:code (failure text {:string-units 2})))))
  (is (= :input-token-limit (:code (failure "{:op :children :anchor nil}" {:token-units 8}))))
  (is (= :input-string-limit (:code (failure "\"\\u0061\\u0062\"" {:string-units 1})))))

(deftest portable-integers-do-not-overflow-or-round
  (is (= :invalid-input-shape (:code (failure "2147483647" {}))))
  (doseq [text ["2147483648" "9999999999999999999999999999999999999999"]]
    (is (= :input-integer-limit (:code (failure text {})))))
  (is (= :input-token-limit (:code (failure (apply str (repeat 257 "9")) {})))))

(deftest refusal-offsets-are-utf16-and-do-not-echo-private-input
  (let [result (failure ";🌳\n{:op :nodes} secret-tail" {})]
    (is (= {:code :trailing-input :offset 17 :offset-unit :utf16 :expected-kind :value} result))
    (is (m/validate c/Failure result))
    (is (not (.contains (pr-str result) "secret-tail")))))

(deftest generated-labels-agree-with-an-independent-edn-reader
  (let [labels (gen/fmap #(apply str %) (gen/vector (gen/elements ["a" "λ" "🌳" "\n" "\"" "\\" "\u0000"]) 1 70))
        result (check/quick-check
                200 (prop/for-all [label labels]
                                  (let [graph (assoc-in (f/example) [:nodes 0 :label] label)
                                        encoded (canonical/encode graph 65536)]
                                    (= graph (edn/read-string encoded)
                                       (admission/decode encoded :graph admission/default-limits))))
                :seed 713049)]
    (is (:pass? result) (pr-str result))))
