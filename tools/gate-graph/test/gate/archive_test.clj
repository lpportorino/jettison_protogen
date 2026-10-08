(ns gate.archive-test
  "Independent malformed-tree, archive-binding and hostile HTML fixtures for portable run archives."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.fixtures :as fixtures]
            [gate.report :as report]
            [gate.repository-identity :as repository]
            [malli.core :as m]
            [malli.generator :as mg]))

(def revision (apply str (repeat 40 "a")))
(def child-revision (apply str (repeat 40 "b")))
(def scope {:id "ci/compiler" :label "Compiler checks" :kind :battery :omissions ["Deployment" "Native checks"]})
(def policy {:excluded ["reports"] :protected ["child/src" "src"]})
(defn sealed [value]
  (let [material (dissoc value :content)]
    (assoc material :content (canonical/sha256 (canonical/encode material 1048576)))))
(defn file-entry [path]
  {:kind :file :path path :index nil :digest (canonical/sha256 "fixture") :executable? false})
(defn observation []
  (let [child (sealed {:path "child" :revision child-revision :entries [(file-entry "src/x")]})
        root (sealed {:path "." :revision revision
                      :entries [{:path "child" :kind :submodule :index {:mode "160000" :object child-revision}
                                 :revision child-revision :content (:content child)}
                                (file-entry "src/main")]})]
    (sealed {:schema/version 1 :profile :git-visible-posix-v1 :policy policy :repositories [root child]})))
(defn acquired [] {:state :observed :snapshot (repository/summarize (observation))})
(def unavailable {:state :unavailable :failure {:code :repository-unreadable}})
(defn document [] (archive/create scope (fixtures/example) (acquired) (acquired)))
(defn failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))
(defn reseal-root [value]
  (sealed (update-in value [:repositories 0] sealed)))

(deftest summaries-bind-the-full-observed-tree
  (let [value (observation) summary (repository/summarize value)]
    (is (= value (repository/require-observation! value)))
    (is (= summary (repository/require-summary! summary)))
    (is (= (:content value) (:content summary)))
    (is (= [2 1] (mapv :entries (:repositories summary))))
    (is (= ["." "child"] (mapv :path (:repositories summary))))
    (is (= [revision child-revision] (mapv :revision (:repositories summary))))
    (is (= value (admission/decode (canonical/encode value 1048576) :repository-observation admission/default-limits)))
    (is (< (count (canonical/encode summary 1048576)) (count (canonical/encode value 1048576))))))

(deftest independent-tree-hash-and-hierarchy-refusals
  (let [value (observation)
        changed-entry (sealed (assoc-in value [:repositories 0 :entries 1 :digest] (canonical/sha256 "changed")))
        wrong-link (reseal-root (assoc-in value [:repositories 0 :entries 0 :content] (canonical/sha256 "wrong-child")))
        orphan (sealed (update value :repositories conj (sealed {:path "orphan" :revision revision :entries []})))
        omitted (sealed (update value :repositories pop))
        untyped (reseal-root (assoc-in value [:repositories 0 :entries 1 :index] {:mode "160000" :object revision}))
        duplicate (reseal-root (update-in value [:repositories 0 :entries] conj (file-entry "src/main")))
        excluded (reseal-root (update-in value [:repositories 0 :entries]
                                         #(vec (sort-by :path (conj % (file-entry "reports/run.edn"))))))]
    (doseq [invalid [changed-entry wrong-link orphan omitted untyped duplicate excluded
                     (assoc value :content (canonical/sha256 "wrong-root"))]]
      (is (= {:code :archive-repository} (failure #(repository/require-observation! invalid)))))))

(deftest path-ancestry-is-not-lexical-adjacency
  (let [value (reseal-root (assoc-in (observation) [:repositories 0 :entries]
                                     (vec (sort-by :path (concat (get-in (observation) [:repositories 0 :entries])
                                                                 (map file-entry ["a" "a!" "a/b"]))))))]
    (is (= {:code :archive-repository} (failure #(repository/require-observation! value))))))

(deftest malformed-policy-and-summary-refusals
  (doseq [bad-policy [(assoc policy :excluded ["src"]) (assoc policy :excluded ["reports" "reports"])
                      (assoc policy :protected ["src" "child/src"]) (assoc policy :excluded ["a/.git"])]]
    (is (= {:code :archive-policy}
           (failure #(repository/require-observation! (sealed (assoc (observation) :policy bad-policy)))))))
  (doseq [bad-summary [(update-in (acquired) [:snapshot :repositories] #(vec (reverse %)))
                       (assoc-in (acquired) [:snapshot :repositories 1 :path] "reports/child")
                       (assoc-in (acquired) [:snapshot :repositories 1 :path] "x/.git")]]
    (is (= {:code :archive-repository} (failure #(repository/require-summary! (:snapshot bad-summary)))))))

(deftest archive-verdict-preserves-missing-and-changed-evidence
  (let [before (acquired)
        changed (assoc-in before [:snapshot :content] (canonical/sha256 "changed"))
        changed-policy (assoc-in changed [:snapshot :policy :excluded] ["reports-v2"])]
    (doseq [[after stability] [[before :unchanged] [changed :changed] [changed-policy :policy-changed] [unavailable :unavailable]]]
      (let [result (archive/create scope (fixtures/example) before after)]
        (is (= stability (get-in result [:provenance :stability])))
        (is (= (if (= stability :unchanged) :passed :incomplete) (:status result)))
        (is (= result (archive/require-valid! result)))))
    (is (= :unavailable (archive/stability unavailable before)))
    (is (= {:code :archive-provenance}
           (failure #(archive/stability before (assoc-in before [:snapshot :repositories 0 :entries] 99)))))
    (doseq [[outcome expected] [[:failed :failed] [:error :failed] [:cancelled :cancelled]]]
      (is (= expected (:status (archive/create scope (assoc-in (fixtures/example) [:nodes 0 :outcome] outcome)
                                               before unavailable)))))))

(deftest digest-status-provenance-and-scope-cannot-be-relabelled
  (let [value (document)]
    (doseq [[changed code] [[(assoc value :status :failed) :archive-status]
                            [(assoc-in value [:provenance :stability] :changed) :archive-provenance]
                            [(assoc value :artifact (canonical/sha256 "different")) :archive-identity]
                            [(assoc-in value [:scope :label] "Different run") :archive-identity]
                            [(assoc-in value [:scope :kind] :full-ci) :archive-scope]]]
      (is (= {:code code} (failure #(archive/require-valid! changed)))))
    (is (= value (admission/decode (canonical/encode value 1048576) :run-archive admission/default-limits)))
    (is (= {:code :archive-status}
           (failure #(admission/decode (canonical/encode (assoc value :status :failed) 1048576)
                                       :run-archive admission/default-limits))))))

(deftest html-binds-archive-and-escapes-hostile-labels
  (let [value (archive/create (assoc scope :label "</script><script>alert('bad')</script>")
                              (fixtures/example) (acquired) unavailable)
        metadata (dissoc value :graph)
        html (report/render (:graph value) "window.fixture = true;" metadata)
        embedded (second (re-find #"(?s)<script id=\"archive-data\" type=\"application/edn\">(.*?)</script>" html))]
    (is (str/includes? html "Archive: incomplete"))
    (is (str/includes? html "Scope: battery"))
    (is (str/includes? html "Repository evidence: unavailable"))
    (is (str/includes? html "Outside this run: Deployment; Native checks"))
    (is (not (str/includes? html "<script>alert('bad')</script>")))
    (is (= metadata (admission/decode embedded :archive-metadata admission/default-limits)))
    (is (= {:code :archive-identity}
           (failure #(report/render (assoc-in (:graph value) [:nodes 0 :label] "other")
                                    "window.fixture = true;" metadata))))))

(deftest malli-generated-acquisition-states-have-independent-verdicts
  (let [result (tc/quick-check
                100
                (prop/for-all [before? (mg/generator :boolean) after? (mg/generator :boolean)]
                              (let [result (archive/create scope (fixtures/example)
                                                           (if before? (acquired) unavailable) (if after? (acquired) unavailable))]
                                (and (m/validate ac/Document result)
                                     (= (if (and before? after?) :passed :incomplete) (:status result)))))
                :seed 20261008)]
    (is (:pass? result) (pr-str result))))
