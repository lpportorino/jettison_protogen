(ns gate.archive-run-test
  "Real Git observations around runner callbacks, including changed inputs and cancellation."
  (:require [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.archive-io :as publication]
            [gate.archive-run :as run]
            [gate.archive-test :as fixture]
            [gate.fixtures :as graph]
            [gate.report-io :as reader]
            [gate.report-publish-test :as publisher]
            [gate.repository :as repository]
            [gate.repository-test :as git-fixture]
            [malli.core :as m])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(deftest late-cancellation-refuses-success-without-rewriting-completed-evidence
  (doseq [phase [:observation-write :publication-enter :publication-return]
          outcome [:passed :failed]]
    (git-fixture/with-repository
      (fn [root settings]
        (let [options {:repository (str root) :policy {:excluded ["reports"] :protected ["src"]}
                       :git settings :limits repository/default-limits :scope fixture/scope
                       :output (str (.resolve ^Path root "reports/run"))}
              token (atom false) original-publish publication/publish!
              original-write publication/write-observation! answer (atom nil)
              failure (with-redefs [publication/write-observation!
                                    (fn [& args]
                                      (let [result (apply original-write args)]
                                        (when (= :observation-write phase) (reset! token true))
                                        result))
                                    publication/publish!
                                    (fn [& args]
                                      (when (= :publication-enter phase) (reset! token true))
                                      (let [result (apply original-publish args)]
                                        (when (= :publication-return phase) (reset! token true))
                                        result))]
                        (fixture/failure
                         #(reset! answer
                                  (run/run! options publisher/viewer token
                                            (fn [_]
                                              (Files/createDirectories (Path/of (:output options) (make-array String 0))
                                                                       (make-array FileAttribute 0))
                                              (assoc-in (graph/example) [:nodes 0 :outcome] outcome))))))
              retained (reader/read-archive! (str (:output options) "/run.edn") admission/default-limits)]
          (is @token)
          (is (= outcome (:status retained)))
          (is (= :unchanged (get-in retained [:provenance :stability])))
          (if (= :passed outcome)
            (do (is (nil? @answer))
                (is (m/validate run/Failure failure))
                (is (= {:code :archive-cancelled :status :passed :artifact (:artifact retained)} failure)))
            (do (is (nil? failure))
                (is (= retained @answer)))))))))

(defn configuration [root settings]
  {:repository (str root) :policy {:excluded ["reports"] :protected ["src"]}
   :git settings :limits repository/default-limits :scope fixture/scope
   :output (str (.resolve ^Path root "reports/run"))})
(defn prepare! [options]
  (Files/createDirectories (Path/of (:output options) (make-array String 0)) (make-array FileAttribute 0)))

(deftest actual-run-is-bracketed-and-all-archives-roundtrip
  (git-fixture/with-repository
    (fn [root settings]
      (let [options (configuration root settings) token (atom false) calls (atom 0)
            result (run/run! options publisher/viewer token
                             (fn [cancellation]
                               (is (identical? token cancellation))
                               (swap! calls inc) (prepare! options) (graph/example)))]
        (is (= 1 @calls))
        (is (= :passed (:status result)))
        (is (nil? (run/require-passed! result)))
        (is (= result (reader/read-archive! (str (:output options) "/run.edn") admission/default-limits)))
        (doseq [phase [:before :after]]
          (let [full (reader/read-repository! (str (:output options) "/repository-" (name phase) ".edn") admission/default-limits)]
            (is (= (:content full) (get-in result [:provenance phase :snapshot :content])))))))))

(deftest changed-inputs-retain-the-run-and-prevent-success
  (git-fixture/with-repository
    (fn [root settings]
      (let [options (configuration root settings)
            result (run/run! options publisher/viewer (atom false)
                             (fn [_] (prepare! options) (spit (str (.resolve ^Path root "src/main.clj")) "changed") (graph/example)))]
        (is (= :incomplete (:status result)))
        (is (= :changed (get-in result [:provenance :stability])))
        (is (= :archive-not-passed (:code (fixture/failure #(run/require-passed! result)))))
        (is (= :passed (get-in result [:graph :nodes 0 :outcome])))
        (is (= result (reader/read-archive! (str (:output options) "/run.edn") admission/default-limits)))))))

(deftest cancellation-retains-known-before-evidence-without-a-fabricated-after
  (git-fixture/with-repository
    (fn [root settings]
      (let [options (configuration root settings) cancellation (atom false)
            result (run/run! options publisher/viewer cancellation
                             (fn [token] (prepare! options) (reset! token true)
                               (assoc-in (graph/example) [:nodes 0 :outcome] :cancelled)))]
        (is (= :cancelled (:status result)))
        (is (= :observed (get-in result [:provenance :before :state])))
        (is (= {:state :unavailable :failure {:code :repository-cancelled}}
               (get-in result [:provenance :after])))
        (is (= result (reader/read-archive! (str (:output options) "/run.edn") admission/default-limits)))))))

(deftest unavailable-provenance-is-explicit-and-callback-exceptions-propagate
  (git-fixture/with-repository
    (fn [root settings]
      (let [options (assoc-in (configuration root settings) [:limits :inputs :bytes] 1)
            result (run/run! options publisher/viewer (atom false)
                             (fn [_] (prepare! options) (graph/example)))]
        (is (= :incomplete (:status result)))
        (is (= :unavailable (get-in result [:provenance :stability])))
        (is (= :bytes (get-in result [:provenance :before :failure :limit])))
        (is (= :archive-not-passed (:code (fixture/failure #(run/require-passed! result))))))))
  (git-fixture/with-repository
    (fn [root settings]
      (is (= {:fixture :failed}
             (fixture/failure #(run/run! (configuration root settings) publisher/viewer (atom false)
                                         (fn [_] (throw (ex-info "Fixture runner failed" {:fixture :failed}))))))))))
