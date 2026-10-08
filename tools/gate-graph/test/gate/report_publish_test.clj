(ns gate.report-publish-test
  "Actual-file publication, preserved evidence, and generated hostile graph labels."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.fixtures :as fixtures]
            [gate.report-publish :as publish]
            [malli.generator :as mg])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(def viewer {:javascript "window.fixture = true;" :digest (canonical/sha256 "window.fixture = true;")})

(defn with-directory
  "Give each case a private real directory and remove only its own children."
  [f]
  (let [directory (Files/createTempDirectory "html-publication-" (make-array FileAttribute 0))]
    (try (f directory)
         (finally
           (with-open [children (Files/list directory)]
             (doseq [child (iterator-seq (.iterator children))] (Files/deleteIfExists ^Path child)))
           (Files/deleteIfExists directory)))))

(defn failure
  "Keep named refusal evidence; native exceptions remain test errors."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest invalid-native-path-has-a-closed-policy-failure
  (let [result (try (publish/publish! "\u0000" (fixtures/example) viewer) nil
                    (catch clojure.lang.ExceptionInfo error (ex-data error))
                    (catch java.nio.file.InvalidPathException _ {:native :invalid-path}))]
    (is (= {:code :html-policy} result))))

(deftest published-html-binds-the-exact-normalized-graph-and-viewer
  (with-directory
    (fn [directory]
      (let [graph (update (fixtures/example) :nodes #(vec (reverse %)))
            result (publish/publish! (str directory) graph viewer)
            html (slurp (str (.resolve directory "index.html")))
            embedded (second (re-find #"(?s)<script id=\"gate-data\" type=\"application/edn\">(.*?)</script>" html))]
        (is (= {:file "index.html" :artifact (canonical/sha256 (canonical/encode (canonical/normalize-graph graph) 65536))
                :viewer (:digest viewer) :bytes (alength (.getBytes html StandardCharsets/UTF_8))} result))
        (is (= (canonical/normalize-graph graph) (admission/decode embedded :graph admission/default-limits)))
        (is (str/includes? html (:javascript viewer)))
        (is (= #{"index.html"} (set (map #(.getName %) (.listFiles (.toFile directory))))))))))

(deftest existing-evidence-including-symlinks-is-never-replaced
  (with-directory
    (fn [directory]
      (let [target (.resolve directory "index.html") other (.resolve directory "other")]
        (spit (str target) "original evidence")
        (is (= {:code :html-exists} (failure #(publish/publish! (str directory) (fixtures/example) viewer))))
        (is (= "original evidence" (slurp (str target))))
        (Files/delete target)
        (spit (str other) "private target")
        (Files/createSymbolicLink target other (make-array FileAttribute 0))
        (is (= {:code :html-exists} (failure #(publish/publish! (str directory) (fixtures/example) viewer))))
        (is (= "private target" (slurp (str other))))
        (Files/delete other)
        (is (= {:code :html-exists} (failure #(publish/publish! (str directory) (fixtures/example) viewer))))
        (is (Files/isSymbolicLink target))))))

(deftest simultaneous-publishers-have-one-complete-winner
  (with-directory
    (fn [directory]
      (let [start (promise)
            jobs (mapv (fn [_] (future @start (or (failure #(publish/publish! (str directory) (fixtures/example) viewer)) :published))) (range 4))]
        (deliver start true)
        (let [results (mapv deref jobs)]
          (is (= 1 (count (filter #{:published} results))))
          (is (= 3 (count (filter #{{:code :html-exists}} results)))))
        (is (str/ends-with? (slurp (str (.resolve directory "index.html"))) "</html>"))))))

(deftest invalid-graph-or-viewer-cannot-create-html
  (with-directory
    (fn [directory]
      (is (= :invalid-graph (:code (failure #(publish/publish! (str directory) (assoc-in (fixtures/example) [:nodes 1 :parent] "missing") viewer)))))
      (is (= {:code :html-viewer-mismatch}
             (failure #(publish/publish! (str directory) (fixtures/example) (assoc viewer :javascript "other")))))
      (is (empty? (.listFiles (.toFile directory)))))))

(deftest generated-labels-roundtrip-without-changing-script-boundaries
  (doseq [label (mg/sample c/Label {:seed 7319 :size 24})]
    (with-directory
      (fn [directory]
        (let [label (str "</script>λ" (subs label 0 (min 120 (count label))))
              graph (assoc-in (fixtures/example) [:nodes 0 :label] label)
              _ (publish/publish! (str directory) graph viewer)
              html (slurp (str (.resolve directory "index.html")))
              embedded (second (re-find #"(?s)<script id=\"gate-data\" type=\"application/edn\">(.*?)</script>" html))]
          (is (= 2 (count (re-seq #"</script>" html))))
          (is (= (canonical/normalize-graph graph) (admission/decode embedded :graph admission/default-limits))))))))
