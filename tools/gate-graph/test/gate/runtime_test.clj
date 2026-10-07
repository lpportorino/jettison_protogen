(ns gate.runtime-test
  "Actual runtime-byte identities, ordered classpaths and conservative unavailable-evidence behavior."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gate.cache :as cache]
            [gate.contained :as contained]
            [gate.contained-test :as completion]
            [gate.container :as container]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.run-test :as fixture]
            [gate.runtime :as runtime]
            [gate.trace-test :refer [with-directory failure]])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(deftest linked-jdk-config-bytes-enter-runtime-identity
  (with-directory
    (fn [root]
      (doseq [path ["jdk/bin/java" "jdk/release" "jdk/lib/core.so" "config/security/policy"]]
        (files/put! root path "a"))
      (Files/createSymbolicLink (.resolve root "jdk/conf") (.resolve root "config") (make-array FileAttribute 0))
      (let [paths (#'runtime/jdk-files (str (.resolve root "jdk")))
            before (runtime/content-digest paths inputs/default-limits)]
        (is (= 4 (count paths)))
        (is (some #{(str (.resolve root "config/security/policy"))} paths))
        (files/put! root "config/security/policy" "b")
        (is (not= before (runtime/content-digest (#'runtime/jdk-files (str (.resolve root "jdk"))) inputs/default-limits)))))))

(deftest jdk-directory-traversal-cannot-silently-omit-nested-content
  (with-directory
    (fn [root]
      (files/put! root "jdk/lib/core.so" "a")
      (files/put! root "jdk/conf/policy" "a")
      (let [link (.resolve root "jdk/conf/loop")]
        (Files/createSymbolicLink link (.resolve root "jdk/conf") (make-array FileAttribute 0))
        (is (= :runtime-controller (:code (failure #(#'runtime/jdk-files (str (.resolve root "jdk")))))))
        (Files/delete link))
      (files/put! root (str "jdk/conf/" (str/join "/" (repeat 32 "deep")) "/policy") "a")
      (is (= :runtime-controller (:code (failure #(#'runtime/jdk-files (str (.resolve root "jdk"))))))))))

(deftest runtime-content-is-not-a-size-or-mtime-memo
  (with-directory
    (fn [root]
      (let [path (files/put! root "library.jar" "a")
            stamp (Files/getLastModifiedTime path (make-array LinkOption 0))
            before (runtime/content-digest [(str path)] inputs/default-limits)]
        (spit (str path) "b")
        (Files/setLastModifiedTime path stamp)
        (is (not= before (runtime/content-digest [(str path)] inputs/default-limits)))
        (spit (str path) "a")
        (Files/setLastModifiedTime path stamp)
        (is (= before (runtime/content-digest [(str path)] inputs/default-limits)))
        (Files/setPosixFilePermissions path (PosixFilePermissions/fromString "rwxr-xr-x"))
        (is (not= before (runtime/content-digest [(str path)] inputs/default-limits)))))))

(deftest controller-identity-includes-libraries-loaded-by-its-own-first-probe
  (let [program (str (System/getProperty "java.home") "/bin/java")
        before (runtime/controller-digest program)
        after (runtime/controller-digest program)]
    (is (= before after))))

(deftest classpath-order-membership-and-total-byte-budget-are-effective
  (with-directory
    (fn [root]
      (let [a (str (files/put! root "a.jar" "a")) b (str (files/put! root "b.jar" "b"))]
        (is (not= (runtime/content-digest [a b] inputs/default-limits) (runtime/content-digest [b a] inputs/default-limits)))
        (is (= :runtime-controller (:code (failure #(runtime/content-digest [a b] (assoc inputs/default-limits :bytes 1))))))
        (let [before (runtime/content-digest [(str root)] inputs/default-limits)]
          (files/put! root "new.edn" "{}")
          (is (not= before (runtime/content-digest [(str root)] inputs/default-limits))))))))

(deftest existing-empty-classpath-directories-have-content-identity
  (with-directory
    (fn [root]
      (let [directory (Files/createDirectory (.resolve root "classes") (make-array FileAttribute 0))
            paths [(str directory)]
            before (try (runtime/content-digest paths inputs/default-limits)
                        (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))
            _ (is (string? before) "An existing empty classpath directory is valid membership")
            file (files/put! root "classes/loaded.clj" "(ns loaded)")]
        (is (not= before (runtime/content-digest paths inputs/default-limits)))
        (Files/delete file)
        (is (= before (try (runtime/content-digest paths inputs/default-limits)
                           (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))
        (Files/delete directory)
        (is (thrown? java.nio.file.NoSuchFileException
                     (runtime/content-digest paths inputs/default-limits)))))))

(deftest empty-classpath-removal-during-observation-cannot-acquire-identity
  (with-directory
    (fn [root]
      (let [directory (Files/createDirectory (.resolve root "classes") (make-array FileAttribute 0))
            observe inputs/observe!]
        (with-redefs [inputs/observe! (fn [& args]
                                        (let [snapshot (apply observe args)]
                                          (Files/delete directory)
                                          snapshot))]
          (is (thrown? java.nio.file.NoSuchFileException
                       (runtime/content-digest [(str directory)] inputs/default-limits))))))))

(deftest unavailable-runtime-evidence-executes-without-a-reuse-attestation
  (with-directory
    (fn [root]
      (completion/setup root)
      (with-redefs [runtime/probe! (fn [_ _] {:directory (str root) :observation {:status :unavailable :identity nil :elapsed-ns "1" :reason :runtime-controller}})
                    container/run! (fn [_ _] (completion/result root))]
        (let [result (contained/run! (completion/request root)
                                     {:cache-directory (str root "/cache") :run "fixture" :attempt "fixture" :dependencies [] :force? false}
                                     (fn [_ _] fixture/coverage) (constantly []) (atom false))]
          (is (= :passed (get-in result [:observation :attempt :work :outcome])))
          (is (= :unproven-isolation (get-in result [:observation :attempt :admission :reason])))
          (is (= :not-attempted (get-in result [:observation :attempt :publication]))))))))

(deftest runtime-change-during-coverage-refuses-artifacts-and-receipts
  (with-directory
    (fn [root]
      (completion/setup root)
      (let [req (completion/request root)
            runtime-id {:schema/version 1 :profile (container/profile (:image req) (:limits req) (:output-roots req))
                        :input-limits (:input-limits req) :process-limits (:process-limits req)
                        :controller fixture/digest-a :daemon fixture/digest-a :machine fixture/digest-a}
            changed? (atom false)]
        (with-redefs [runtime/probe! (fn [_ _]
                                       {:directory (str root) :observation {:status :verified :reason nil :elapsed-ns "1"
                                                                            :identity (cond-> runtime-id @changed? (assoc :controller fixture/digest-b))}})
                      container/run! (fn [request _]
                                       (assoc-in (completion/result root) [:observation :snapshot]
                                                 (inputs/observe! (:directory request) (:gate request) (:evidence request) (:input-limits request))))]
          (let [result (contained/run! req {:cache-directory (str root "/cache") :run "fixture" :attempt "fixture" :dependencies [] :force? false}
                                       (fn [_ _] (reset! changed? true) fixture/coverage) (constantly []) (atom false))]
            (is (= :runtime-unstable (get-in result [:observation :attempt :work :reason])))
            (is (= :not-attempted (get-in result [:observation :output-publication :status])))
            (is (= :not-attempted (get-in result [:observation :attempt :publication])))
            (is (= "old" (slurp (str root "/out/result.edn"))))))))))

(deftest runtime-policy-identity-is-part-of-cache-material
  (with-directory
    (fn [root]
      (completion/setup root)
      (let [request (completion/request root)
            runtime-id {:schema/version 1 :profile (container/profile (:image request) (:limits request) (:output-roots request))
                        :input-limits (:input-limits request) :process-limits (:process-limits request)
                        :controller fixture/digest-a :daemon fixture/digest-a :machine fixture/digest-a}
            keyed (ns-resolve 'gate.contained 'keyed-request)
            input-key (fn [runtime-id]
                        (let [effective (keyed request {:status :verified :reason nil :elapsed-ns "1" :identity runtime-id})]
                          (cache/input-key (:gate effective)
                                           (inputs/observe! (str root) (:gate effective) (:evidence effective) (:input-limits effective)) [])))
            original (input-key runtime-id)]
        (doseq [changed [(assoc runtime-id :controller fixture/digest-b)
                         (assoc runtime-id :daemon fixture/digest-b)
                         (assoc runtime-id :machine fixture/digest-b)
                         (update-in runtime-id [:input-limits :bytes] inc)
                         (update-in runtime-id [:process-limits :timeout-ms] inc)]]
          (is (not= original (input-key changed))))))))
