(ns lvgl-codegen.pretty-test
  "Controls for the printer's supported value domain and lossless key ordering.
   REVERT-TO-BREAK: replace the typed comparator with sorted-map, reverse its
   key type ranks, or omit a namespace/name rank field. CONTROL: scalar printing
   and diff-strs stay green. Boundary tests exercise Malli instrumentation,
   not a runtime input guard."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [lvgl-codegen.pretty :as pretty]
            [lvgl-codegen.spec-coverage :as coverage]
            [malli.core :as m]))

(defn- outcome
  "Capture a printer failure so a changed behavior produces an assertion FAIL."
  [f]
  (try {:value (f)}
       (catch Exception e {:exception (class e)
                           :type (:type (ex-data e))
                           :error-data (:data (ex-data e))})))

(defn- registered-schema
  "Name the registered function boundary that must refuse the test input."
  [fn-name]
  (some-> (get-in (m/function-schemas) ['lvgl-codegen.pretty fn-name :schema]) m/form))

(deftest scalar-and-diff-control
  (doseq [value [nil true false 42 1.5 "text" :value 'symbol \x
                 #uuid "00000000-0000-0000-0000-000000000001"
                 #inst "2026-10-03T00:00:00.000-00:00"]]
    (is (= {:value value}
           (outcome #(edn/read-string (pretty/pp-str value))))))
  (is (= {:equal? true :diff ""} (pretty/diff-strs "a\n" "a\n")))
  (is (= {:equal? false :diff "line 1:\n  - a\n  + b"}
         (pretty/diff-strs "a\n" "b\n"))))

(deftest mixed-key-printing-is-lossless-and-ordered
  (let [value {"a" 1 :a 2 ":a" 3 :z 4 :a/x 5 :b/x 6
               :nested [{"leaf" nil :leaf false} '(1 :two "three")]
               :seq (list {"leaf" nil :leaf true})}
        printed (outcome #(pretty/pp-str value))]
    (is (contains? printed :value))
    (is (= {:value value}
           (outcome #(edn/read-string (pretty/pp-str value)))))
    (is (= {:value [:a :nested :seq :z :a/x :b/x ":a" "a"]}
           (outcome #(vec (keys (#'pretty/sort-map-keys value))))))
    (is (= {:value [:leaf "leaf"]}
           (outcome #(vec (keys (first (:nested (#'pretty/sort-map-keys value))))))))
    (is (= {:value [:leaf "leaf"]}
           (outcome #(vec (keys (first (:seq (#'pretty/sort-map-keys value))))))))))

(deftest rank-fields-preserve-distinct-keys
  ;; These actual keywords have equal pr-str representations in each pair.
  ;; Comparing their printed strings would silently merge map entries.
  (let [map-keys [(keyword "a/b" "c") (keyword "a" "b/c")
                  (keyword nil "x") (keyword "" "x") ":x" "x"]
        value (zipmap map-keys (range))
        sorted (outcome #(#'pretty/sort-map-keys value))]
    (is (= 6 (count value)))
    (is (= {:value value} sorted))
    (is (= {:value 6} (outcome #(count (#'pretty/sort-map-keys value)))))
    (is (= {:value [(keyword nil "x") (keyword "" "x")
                    (keyword "a" "b/c") (keyword "a/b" "c") ":x" "x"]}
           (outcome #(vec (keys (#'pretty/sort-map-keys value))))))))

(deftest large-map-order-does-not-depend-on-insertion
  (let [entries (vec (mapcat (fn [i] [[(keyword (format "k%02d" i)) i]
                                      [(format "k%02d" i) (- i)]])
                             (range 40)))
        forward (into {} entries)
        backward (into {} (rseq entries))]
    (is (= 80 (count forward)))
    (is (= {:value forward}
           (outcome #(edn/read-string (pretty/pp-str forward)))))
    (is (= (outcome #(pretty/pp-str forward))
           (outcome #(pretty/pp-str backward))))
    (is (= {:value 80} (outcome #(count (#'pretty/sort-map-keys forward)))))))

(deftest unsupported-domain-is-rejected-by-instrumentation
  ;; REVERT-TO-BREAK: widen pp-str's argument back to :any.
  ;; CONTROL: scalar-and-diff-control remains green.
  (doseq [value [{1 "number key"} #{:set} {:nested {1 false}}]]
    (let [result (outcome #(pretty/pp-str value))]
      (is (= :malli.core/invalid-input (:type result)))
      (is (= [value] (get-in result [:error-data :args])))
      (is (= (registered-schema 'pp-str)
             (some-> (get-in result [:error-data :schema]) m/form)))))
  (let [result (outcome #(#'pretty/sort-map-keys #{:set}))]
    (is (= :malli.core/invalid-input (:type result)))
    (is (= (registered-schema 'sort-map-keys)
           (some-> (get-in result [:error-data :schema]) m/form)))))

(deftest pretty-contract-coverage-is-enrolled
  ;; REVERT-TO-BREAK: remove pretty from the maintained spec coverage roster.
  ;; CONTROL: scalar-and-diff-control remains green.
  (is (contains? coverage/enrolled "lvgl-codegen.pretty")))
