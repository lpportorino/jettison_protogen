(ns gate.snapshot-test
  "Actual copied bytes, layout and metadata controls; no mutable-worktree mount is called a snapshot."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gate.container :as container]
            [gate.inputs :as inputs]
            [gate.inputs-test :as files]
            [gate.run-test :as fixture]
            [gate.snapshot :as snapshot]
            [gate.trace-test :refer [with-directory failure]])
  (:import [java.nio.file Files Path LinkOption]
           [java.nio.file.attribute PosixFilePermissions]))

(defn copy! [root]
  (snapshot/materialize! (str root) (str root "/owned-copy") fixture/gate files/evidence inputs/default-limits ["out"]))

(deftest engine-source-identity-hashes-bytes-before-decoding
  (with-directory
    (fn [root]
      (let [path (.resolve ^Path root "resource")
            observe #(with-redefs [io/resource (constantly (.toURL (.toUri path)))] (container/engine-digest))]
        (Files/write path (byte-array [(unchecked-byte 255)]) (make-array java.nio.file.OpenOption 0))
        (let [before (observe)]
          (Files/write path (byte-array [(unchecked-byte 254)]) (make-array java.nio.file.OpenOption 0))
          (is (not= before (observe))))))))

(deftest every-shipped-jvm-source-changes-the-runtime-profile-key
  (with-directory
    (fn [root]
      (let [resource io/resource
            source-root (.getParentFile (io/file (resource "gate/container.clj")))
            names (set (for [file (file-seq source-root)
                             :when (and (.isFile ^java.io.File file) (re-find #"\.cljc?$" (.getName ^java.io.File file)))]
                         (str "gate/" (.relativize (.toPath source-root) (.toPath ^java.io.File file)))))
            reads (atom [])
            image (apply str (repeat 64 "a"))
            profile #(container/profile image container/default-limits ["out"])
            key-of #(get-in (container/runtime-evidence fixture/gate % (:environment files/evidence) {}) [:toolchains "container-profile"])
            before (with-redefs [io/resource (fn [resource-name] (swap! reads conj resource-name) (resource resource-name))] (profile))
            replacement (.resolve ^Path root "changed-source")]
        (is (seq names))
        (is (= (zipmap names (repeat 1)) (frequencies @reads)))
        (doseq [resource-name (sort names)]
          (Files/write replacement
                       (.getBytes (str (slurp (resource resource-name)) "\n;; source byte-change probe\n") java.nio.charset.StandardCharsets/UTF_8)
                       (make-array java.nio.file.OpenOption 0))
          (let [after (with-redefs [io/resource (fn [requested] (if (= resource-name requested) (.toURL (.toUri replacement)) (resource requested)))]
                        (profile))]
            (is (not= (:engine before) (:engine after)) resource-name)
            (is (not= (key-of before) (key-of after)) resource-name)))
        (let [after (with-redefs [io/resource (fn [resource-name]
                                                (if (str/ends-with? resource-name ".cljs")
                                                  (.toURL (.toUri replacement)) (resource resource-name)))]
                      (profile))]
          (is (= before after) "Browser source does not alter the JVM execution profile"))))))

(deftest engine-source-absence-and-byte-budget-fail-closed
  (with-directory
    (fn [root]
      (let [resource io/resource
            path (.resolve ^Path root "resource")
            observe #(with-redefs [io/resource (fn [resource-name] (if (= resource-name "gate/clock.clj") (.toURL (.toUri path)) (resource resource-name)))]
                       (container/engine-digest))]
        (is (= :container-policy
               (:code (failure #(with-redefs [io/resource (fn [resource-name] (when-not (= resource-name "gate/clock.clj") (resource resource-name)))]
                                  (container/engine-digest))))))
        (Files/write path (byte-array 1048576) (make-array java.nio.file.OpenOption 0))
        (is (string? (observe)))
        (Files/write path (byte-array 1048577) (make-array java.nio.file.OpenOption 0))
        (is (= :container-policy (:code (failure observe))))))))

(deftest copied-bytes-are-stable-after-workspace-changes
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [result (copy! root)]
        (files/put! root "src/a.clj" "changed")
        (is (= fixture/snapshot (:snapshot result)))
        (is (= "a" (slurp (str root "/owned-copy/src/a.clj"))))
        (is (= 0 (.toMillis (Files/getLastModifiedTime (.resolve ^Path root "owned-copy/src/a.clj") (make-array LinkOption 0)))))
        (is (= (PosixFilePermissions/fromString "r--r--r--")
               (Files/getPosixFilePermissions (.resolve ^Path root "owned-copy/src/a.clj") (make-array LinkOption 0))))))))

(deftest missing-and-overbudget-snapshots-never-produce-usable-evidence
  (with-directory
    (fn [root]
      (is (= :snapshot-incomplete (:code (failure #(copy! root)))))
      (files/put! root "src/a.clj" "abc")
      (is (= :snapshot-incomplete
             (:code (failure #(snapshot/materialize! (str root) (str root "/owned-copy") fixture/gate files/evidence
                                                     (assoc inputs/default-limits :bytes 2) ["out"]))))))))

(deftest copy-rejects-bytes-changed-after-real-observation
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (let [observe inputs/observe!]
        (is (= :snapshot-changed
               (:code (failure #(with-redefs [inputs/observe! (fn [& args]
                                                                (let [result (apply observe args)]
                                                                  (files/put! root "src/a.clj" "b") result))]
                                  (copy! root))))))))))

(deftest output-mounts-cannot-hide-inputs-or-overlap
  (doseq [roots [["src"] ["out" "out/nested"] [] ["out" "unused"]]]
    (is (= :snapshot-layout (:code (failure #(snapshot/validate-layout! fixture/gate fixture/snapshot roots))))))
  (is (nil? (snapshot/validate-layout! fixture/gate fixture/snapshot ["out"]))))

(deftest existing-destination-is-not-overwritten
  (with-directory
    (fn [root]
      (files/put! root "src/a.clj" "a")
      (files/put! root "owned-copy/preserve" "original")
      (is (= :snapshot-io (:code (failure #(copy! root)))))
      (is (= "original" (slurp (str root "/owned-copy/preserve")))))))
