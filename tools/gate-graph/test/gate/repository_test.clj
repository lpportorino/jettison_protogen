(ns gate.repository-test
  "Synthetic Git fixtures exercise working-tree identity, exclusion boundaries and finite observations."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.inputs :as inputs]
            [gate.repository :as repository]
            [gate.repository-contract :as rc]
            [gate.repository-git :as git]
            [malli.core :as m]
            [malli.generator :as mg])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

(defn- temp! [] (Files/createTempDirectory "gate-repository-" (make-array FileAttribute 0)))
(defn- file [root relative] (.resolve ^Path root ^String relative))
(defn- put! [root relative text]
  (let [path (file root relative)]
    (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
    (spit (str path) text) path))
(defn- remove-tree! [root]
  (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
    (doseq [path (reverse (sort-by #(.getNameCount ^Path %) (iterator-seq (.iterator paths))))]
      (Files/delete path))))
(defn- git! [root & args]
  (let [result (apply shell/sh (concat ["/usr/bin/git" "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid"
                                        "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null"] args
                                       [:dir (str root) :env git/environment]))]
    (when-not (zero? (:exit result)) (throw (ex-info "Fixture Git failed" result)))
    (:out result)))
(defn- init! [root]
  (git! root "init" "--template=" "-q")
  (put! root "src/main.clj" "alpha")
  (put! root ".gitignore" "ignored/\n")
  (git! root "add" ".")
  (git! root "commit" "-qm" "Synthetic initial tree"))
(defn- fixture! [f]
  (let [root (temp!) logs (temp!)]
    (try
      (init! root)
      (f root {:git "/usr/bin/git" :log-directory (str logs) :timeout-ms 10000 :output-bytes 1048576})
      (finally (remove-tree! root) (remove-tree! logs)))))
(defn- observe [root settings]
  (repository/observe! (str root) {:excluded ["reports"] :protected ["src" ".gitignore"]}
                       settings repository/default-limits))
(defn with-repository
  "Supply an owned committed synthetic checkout and an external log directory; remove only this fixture."
  [f]
  (fixture! f))
(defn- code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error
                 (is (m/validate rc/Failure (ex-data error))) (:code (ex-data error)))))
(defn- entries [observation]
  (into {} (map (juxt :path identity) (:entries (first (:repositories observation))))))

(deftest strict-git-records
  (let [sha (apply str (repeat 40 "a")) row (str "100644 " sha " 0\tx\u0000")]
    (is (= [{:path "x" :mode "100644" :object sha}] (git/index row 1)))
    (is (= ["a\tb" "z"] (git/untracked "z\u0000a\tb\u0000" 2)))
    (doseq [text [(str "100644 " sha " 1\tx\u0000") (str "100644 " sha " 0\t../x\u0000")
                  (str "100644 " sha " 0\ta/.git/config\u0000") (str row row)
                  (str "040000 " sha " 0\tx\u0000") "unterminated"]]
      (is (= :repository-protocol (code #(git/index text 10)))))
    (is (= :repository-budget (code #(git/untracked "a\u0000b\u0000" 1))))
    (is (= :repository-protocol (code #(git/untracked "x\u0000x\u0000" 2))))
    (is (= :repository-protocol (code #(git/untracked "dir/\u0000" 1))))
    (is (= "abc" (git/line "abc\n")))
    (doseq [text ["" "abc" "abc\n\n" "a\u0000b\n"]]
      (is (= :repository-protocol (code #(git/line text)))))))

(deftest exclusions-have-component-boundaries-and-protected-inputs
  (is (= {:excluded ["reports"] :protected ["reports-source" "src"]}
         (repository/policy ["reports" "reports"] ["src" "reports-source"])))
  (is (repository/beneath? "reports" "reports/run/graph.edn"))
  (is (not (repository/beneath? "reports" "reports-source/a")))
  (doseq [[excluded protected] [[["reports"] ["."]] [["src"] ["src/main.clj"]]
                                [["src/generated"] ["src"]] [["a/.git"] ["src"]]]]
    (is (= :repository-policy (code #(repository/policy excluded protected))))))

(deftest identity-observes-bytes-index-and-membership
  (fixture!
   (fn [root settings]
     (let [baseline (observe root settings) path (file root "src/main.clj")
           timestamp (Files/getLastModifiedTime path (make-array LinkOption 0))]
       (is (m/validate rc/Observation baseline))
       (is (= baseline (observe root settings)))
       (is (= baseline (admission/decode (canonical/encode baseline 1048576) :value admission/default-limits)))
       (put! root "src/main.clj" "bravo")
       (Files/setLastModifiedTime path timestamp)
       (let [dirty (observe root settings)]
         (is (not= (:content baseline) (:content dirty)))
         (is (= (get-in (entries baseline) ["src/main.clj" :index])
                (get-in (entries dirty) ["src/main.clj" :index])))
         (is (= (canonical/sha256 "bravo") (get-in (entries dirty) ["src/main.clj" :digest])))
         (git! root "add" "src/main.clj")
         (let [staged (observe root settings)]
           (is (not= (:content dirty) (:content staged)))
           (is (= (get-in (entries dirty) ["src/main.clj" :digest])
                  (get-in (entries staged) ["src/main.clj" :digest])))))
       (put! root "new.txt" "new")
       (is (nil? (get-in (entries (observe root settings)) ["new.txt" :index])))
       (is (= :file (get-in (entries (observe root settings)) ["new.txt" :kind])))
       (Files/delete path)
       (is (= :deleted (get-in (entries (observe root settings)) ["src/main.clj" :kind])))
       (git! root "rm" "--cached" "src/main.clj")
       (is (not (contains? (entries (observe root settings)) "src/main.clj")))))))

(deftest modes-links-exclusions-and-ignored-input-scope
  (fixture!
   (fn [root settings]
     (let [baseline (observe root settings)]
       (put! root "reports/run/graph.edn" "generated")
       (put! root "ignored/runtime.jar" "ignored input requires separate identity")
       (is (= baseline (observe root settings)))
       (put! root "reports-source/a" "source")
       (is (not= baseline (observe root settings)))
       (Files/setPosixFilePermissions (file root "src/main.clj") (PosixFilePermissions/fromString "rwxr-xr-x"))
       (is (true? (get-in (entries (observe root settings)) ["src/main.clj" :executable?])))
       (Files/createSymbolicLink (file root "link") (Path/of "src/main.clj" (make-array String 0))
                                 (make-array FileAttribute 0))
       (let [first-link (observe root settings)]
         (is (= :symlink (get-in (entries first-link) ["link" :kind])))
         (Files/delete (file root "link"))
         (Files/createSymbolicLink (file root "link") (Path/of "absent" (make-array String 0))
                                   (make-array FileAttribute 0))
         (is (not= (:content first-link) (:content (observe root settings)))))))))

(deftest actual-child-checkout-and-dirty-content-are-bound
  (fixture!
   (fn [root settings]
     (let [child (file root "child")]
       (Files/createDirectory child (make-array FileAttribute 0))
       (init! child)
       (git! root "add" "child")
       (git! root "commit" "-qm" "Synthetic gitlink")
       (let [baseline (observe root settings)]
         (is (= ["." "child"] (mapv :path (:repositories baseline))))
         (is (= :submodule (get-in (entries baseline) ["child" :kind])))
         (put! child "src/main.clj" "child dirty")
         (is (not= (:content baseline) (:content (observe root settings))))
         (is (not= (get-in (entries baseline) ["child" :content])
                   (get-in (entries (observe root settings)) ["child" :content])))
         (git! child "add" ".")
         (git! child "commit" "-qm" "Child ahead of gitlink")
         (let [ahead (get (entries (observe root settings)) "child")]
           (is (not= (:revision ahead) (get-in ahead [:index :object]))))
         (remove-tree! (file child ".git"))
         (is (= :repository-unsupported (code #(observe root settings)))))))))

(deftest finite-budgets-and-log-location-refuse
  (fixture!
   (fn [root settings]
     (doseq [limits [(assoc-in repository/default-limits [:inputs :files] 1)
                     (assoc-in repository/default-limits [:inputs :entries] 1)
                     (assoc-in repository/default-limits [:inputs :bytes] 1)
                     (assoc-in repository/default-limits [:inputs :depth] 1)]]
       (is (= :repository-budget
              (code #(repository/observe! (str root) {:excluded [] :protected ["src"]} settings limits)))))
     (is (= :repository-git (code #(observe root (assoc settings :output-bytes 1)))))
     (is (= :repository-policy (code #(observe root (assoc settings :log-directory (str root)))))))))

(deftest membership-change-during-observation-refuses
  (fixture!
   (fn [root settings]
     (let [original git/command! touched (atom false)]
       (with-redefs [git/command! (fn [directory configuration args & cancellation]
                                    (let [result (apply original directory configuration args cancellation)]
                                      (when (and (= ["ls-files" "--others" "--exclude-standard" "-z"] args)
                                                 (compare-and-set! touched false true))
                                        (put! root "arrived" "after first membership"))
                                      result))]
         (is (= :repository-unstable (code #(observe root settings)))))))))

(deftest checkout-boundaries-and-repository-budget
  (fixture!
   (fn [root settings]
     (let [child (file root "child")]
       (Files/createDirectory child (make-array FileAttribute 0))
       (is (= :repository-unsupported (code #(observe child settings))))
       (init! child)
       (git! root "add" "child")
       (is (= :repository-budget
              (code #(repository/observe! (str root) {:excluded [] :protected ["src"]} settings
                                          (assoc repository/default-limits :repositories 1)))))))))

(deftest malformed-output-timeouts-and-symlink-ancestors
  (fixture!
   (fn [root settings]
     (let [script (put! root "fake-git" "#!/bin/sh\nprintf '\\377\\000'\n")]
       (Files/setPosixFilePermissions script (PosixFilePermissions/fromString "rwx------"))
       (is (= :repository-protocol (code #(git/command! (str root) (assoc settings :git (str script)) ["rev-parse" "HEAD"]))))
       (is (= :repository-protocol (code #(observe root (assoc settings :git (str script))))))
       (put! root "fake-git" "#!/bin/sh\nprintf 'unexpected\\n'\nexit 7\n")
       (is (= :repository-git (code #(observe root (assoc settings :git (str script))))))
       (put! root "fake-git" "#!/bin/sh\nexec /bin/sleep 3\n")
       (is (= :repository-git (code #(observe root (assoc settings :git (str script) :timeout-ms 50))))))
     (remove-tree! (file root "src"))
     (Files/createSymbolicLink (file root "src") (Path/of "reports" (make-array String 0))
                               (make-array FileAttribute 0))
     (is (= :repository-unsupported (code #(observe root settings)))))))

(deftest malli-generated-prefix-boundaries
  (let [component [:string {:min 1 :max 16 :gen/regex "[a-z]{1,16}"}]
        result (tc/quick-check
                100
                (prop/for-all [parts (mg/generator [:vector {:min 2 :max 8} component])]
                              (let [ancestor (first parts) sibling (str ancestor "-source")
                                    child (str/join "/" parts)]
                                (and (repository/beneath? ancestor child)
                                     (not (repository/beneath? ancestor sibling))
                                     (= {:excluded [ancestor] :protected [sibling]}
                                        (repository/policy [ancestor] [sibling])))))
                :seed 20261008)]
    (is (:pass? result) (pr-str result))))

(deftest large-byte-accounting-and-precise-refusal
  (fixture!
   (fn [root settings]
     (let [budget (atom {:files 0 :entries 0 :bytes 2147483647})
           limits (assoc inputs/default-limits :bytes 3000000000)]
       (is (m/validate inputs/ReadLimits limits))
       (is (not (m/validate inputs/Limits limits)))
       (is (= (canonical/sha256 "alpha") (:digest (inputs/read-file! root "src/main.clj" limits budget))))
       (is (= 2147483652 (:bytes @budget))))
     (let [failure (try (repository/observe! (str root) {:excluded [] :protected ["src"]} settings
                                             (assoc-in repository/default-limits [:inputs :bytes] 1))
                        nil (catch clojure.lang.ExceptionInfo error (ex-data error)))]
       (is (m/validate rc/Failure failure))
       (is (= :repository-budget (:code failure)))
       (is (= :bytes (:limit failure)))
       (is (= "1" (:maximum failure)))
       (is (> (parse-long (:used failure)) 1))))))

(deftest cancellation-stops-provenance-before-launch-and-between-byte-reads
  (fixture!
   (fn [root settings]
     (let [cancelled (atom true) calls (atom 0) original git/command!]
       (with-redefs [git/command! (fn [& args] (swap! calls inc) (apply original args))]
         (is (= :repository-cancelled
                (code #(repository/observe! (str root) {:excluded [] :protected ["src"]}
                                            settings repository/default-limits cancelled))))
         (is (zero? @calls))))
     (put! root "src/large" (apply str (repeat 200000 "x")))
     (let [cancelled (atom false) calls (atom 0) original inputs/read-file!]
       (with-redefs [inputs/read-file!
                     (fn [root relative limits budget checkpoint]
                       (original root relative limits budget
                                 (fn []
                                   (when (and (= relative "src/large") (= 3 (swap! calls inc)))
                                     (reset! cancelled true))
                                   (checkpoint))))]
         (is (= :repository-cancelled
                (code #(repository/observe! (str root) {:excluded [] :protected ["src"]}
                                            settings repository/default-limits cancelled))))
         (is @cancelled))))))
