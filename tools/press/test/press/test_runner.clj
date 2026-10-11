(ns press.test-runner
  "The suite's entry point: `clojure -M:test` from `tools/press`.

   Every declared `m/=>` is instrumented before a test runs, so a call or a
   return that breaks a function's schema throws `Malli <kind>: <function>`
   at the boundary (the tests name that refusal `:malli/boundary`). The run is refused as
   vacuous when a source function carries no schema or no docstring, and when
   no test or no assertion ran; it exits non-zero on any failure or error."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as test]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn discover-namespaces
  "Read namespace declarations in a directory; refuse empty or duplicate discovery."
  [directory]
  (let [names (->> (file-seq (io/file directory))
                   (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".clj")))
                   (map (fn [file]
                          (with-open [reader (java.io.PushbackReader. (io/reader file))]
                            (let [form (read {:eof nil :read-cond :allow :features #{:clj}} reader)]
                              (when-not (and (seq? form) (= 'ns (first form)) (symbol? (second form)))
                                (throw (ex-info "missing namespace declaration" {:file (str file)})))
                              (second form)))))
                   sort vec)]
    (when (or (empty? names) (not= (count names) (count (distinct names))))
      (throw (ex-info "empty or duplicate namespace discovery" {:directory directory :names names})))
    names))

(def source-namespaces
  "Every source namespace: each function in them owes a schema and a docstring."
  (discover-namespaces "src"))

(def test-namespaces
  "Every discovered namespace whose name ends in -test, in sorted order."
  (filterv #(str/ends-with? (str %) "-test") (discover-namespaces "test")))

(defn- functions-without
  "The source functions (macros excluded) for which `(has? ns-sym name var)` is false."
  [has?]
  (vec (for [nsym source-namespaces
             [nm v] (sort-by key (ns-interns nsym))
             :when (and (bound? v) (fn? @v) (not (:macro (meta v))) (not (has? nsym nm v)))]
         (symbol (str nsym) (str nm)))))

(defn prepare!
  "Refuse a source function without a schema or a docstring, then instrument
   every declared schema. Returns the number of functions instrumented."
  []
  (let [schemas (m/function-schemas)
        unspecced (functions-without (fn [nsym nm _] (get-in schemas [nsym nm])))
        undocumented (functions-without (fn [_ _ v] (not (str/blank? (:doc (meta v))))))]
    (when (seq unspecced)
      (throw (ex-info "source functions without an m/=> schema" {:missing unspecced})))
    (when (seq undocumented)
      (throw (ex-info "source functions without a docstring" {:missing undocumented})))
    (count (mi/instrument! {:report (fn [kind data]
                                      (throw (ex-info (str "Malli " kind ": " (:fn-name data))
                                                      (select-keys data [:input :args :output :value]))))}))))

(defn -main
  "Instrument, run every test namespace, and exit 0 only when at least one
   test and one assertion ran and none failed or errored."
  [& _]
  (doseq [nsym (concat source-namespaces test-namespaces)] (require nsym))
  (when (or (empty? test-namespaces)
            (some (fn [nsym] (not-any? #(-> % val meta :test) (ns-interns nsym))) test-namespaces))
    (throw (ex-info "empty test namespace discovery" {:names test-namespaces})))
  (let [n (prepare!)
        _ (println "instrumented" n "functions in" (count source-namespaces) "namespaces")
        {:keys [pass fail error] tests :test} (apply test/run-tests test-namespaces)
        ok? (and (pos? tests) (pos? pass) (zero? fail) (zero? error))]
    (when-not (and (pos? tests) (pos? pass))
      (println "VACUOUS: no test or no assertion ran"))
    (shutdown-agents)
    (System/exit (if ok? 0 1))))
