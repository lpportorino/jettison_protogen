(ns gate.viewer-asset-test
  "Packaged-byte, source-drift and parser refusals at the consumer boundary."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [gate.canonical :as canonical]
            [gate.viewer-asset :as asset]
            [gate.viewer-build :as build]
            [gate.viewer-contract :as vc]
            [malli.core :as m])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn failure
  "Retain only closed ex-data; unexpected exceptions fail the test normally."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest build-file-acquisition-bounds-bytes-and-maps-io-errors
  (with-redefs [io/input-stream (fn [_] (java.io.ByteArrayInputStream. (byte-array [1 2 3 4])))]
    (is (= [1 2 3 4] (vec (#'build/read-bytes! "deps.edn" 4))))
    (is (= {:code :viewer-build-input} (failure #(#'build/read-bytes! "deps.edn" 3)))))
  (with-redefs [io/input-stream (fn [_] (throw (java.io.FileNotFoundException. "synthetic")))]
    (is (= {:code :viewer-build-input} (failure #(#'build/read-bytes! "deps.edn" 4))))))

(deftest source-discovery-refuses-links-empty-membership-and-overflow
  (let [root (Files/createTempDirectory "viewer-membership-" (make-array FileAttribute 0))]
    (try
      (is (= {:code :viewer-build-input} (failure #(#'build/source-paths! root))))
      (doseq [i (range 256)]
        (Files/createFile (.resolve root (str "v" i ".cljs")) (make-array FileAttribute 0)))
      (is (= 256 (count (#'build/source-paths! root))))
      (let [extra (.resolve root "extra.cljs")]
        (Files/createFile extra (make-array FileAttribute 0))
        (is (= {:code :viewer-build-input} (failure #(#'build/source-paths! root))))
        (Files/delete extra)
        (Files/createSymbolicLink extra (.resolve root "v0.cljs") (make-array FileAttribute 0))
        (is (= {:code :viewer-build-input} (failure #(#'build/source-paths! root)))))
      (finally (doseq [entry (reverse (file-seq (.toFile root)))] (io/delete-file entry))))))

(deftest declaration-hashes-preserve-distinct-byte-inputs
  (let [hash-byte (fn [value]
                    (with-redefs [io/input-stream
                                  (fn [_] (java.io.ByteArrayInputStream. (byte-array [(unchecked-byte value)])))]
                      (#'build/input-hash "deps.edn")))]
    (is (not= (hash-byte 254) (hash-byte 255)))))

(deftest source-membership-includes-nested-browser-files
  (let [root (.toFile (Files/createTempDirectory "viewer-nested-" (make-array FileAttribute 0)))
        file io/file]
    (try
      (io/make-parents (file root "nested/child.cljs"))
      (spit (file root "direct.cljs") "(ns gate.direct)")
      (spit (file root "nested/child.cljs") "(ns gate.nested.child)")
      (let [snapshot (with-redefs [io/file (fn [& paths] (if (= ["src/gate"] (vec paths)) root (apply file paths)))
                                   asset/resource-text! (fn [path _] path)]
                       (build/snapshot))]
        (is (= ["gate/direct.cljs" "gate/nested/child.cljs"] (mapv :path (:inputs snapshot)))))
      (finally (doseq [entry (reverse (file-seq root))] (io/delete-file entry))))))

(deftest packaged-viewer-is-fresh-and-contract-valid
  (let [viewer (asset/load!) manifest (build/check!)]
    (is (m/validate asset/Asset viewer))
    (is (m/validate vc/Manifest manifest))
    (is (= (:bundle manifest) (:digest viewer)))
    (is (> (count (:javascript viewer)) 10000))
    (is (some #(= "gate/viewer.cljs" (:path %)) (:inputs manifest)))))

(deftest mismatched-bundle-and-sources-refuse
  (let [read-resource asset/resource-text!]
    (with-redefs [asset/resource-text!
                  (fn [path limit]
                    (str (read-resource path limit) (when (= path "gate/viewer/main.js") "\n/* drift */")))]
      (is (= {:code :viewer-bundle-mismatch} (failure asset/load!))))
    (with-redefs [asset/resource-text!
                  (fn [path limit]
                    (str (read-resource path limit) (when (= path "gate/viewer.cljs") "\n; drift")))]
      (is (= {:code :viewer-source-mismatch} (failure asset/load!))))))

(deftest missing-duplicate-or-unsorted-viewer-inputs-refuse
  (let [manifest (asset/manifest!) inputs (:inputs manifest)]
    (doseq [changed [(vec (remove #(= "gate/viewer.cljs" (:path %)) inputs))
                     (conj inputs (first inputs)) (vec (reverse inputs))]]
      (with-redefs [asset/manifest! (fn [] (assoc manifest :inputs changed))]
        (is (= {:code :viewer-input-roster} (failure asset/load!)))))))

(deftest manifest-input-paths-name-exact-public-resources
  (let [manifest (asset/manifest!)]
    (doseq [path ["../gate/viewer.cljs" "other/gate/viewer.cljs" "gate/viewer.cljs/extra"
                  "gate/viewer.cljs\n" "gate/viewer.cljs\r\n" "/gate/viewer.cljs"]]
      (with-redefs [asset/resource-text! (fn [_ _] (pr-str (assoc-in manifest [:inputs 0 :path] path)))]
        (is (= :invalid-input-shape (:code (failure asset/manifest!))) path)))))

(deftest build-declaration-drift-refuses-even-with-valid-bundle
  (let [snapshot (build/snapshot)]
    (with-redefs [build/snapshot (fn [] (assoc snapshot :dependencies (apply str (repeat 64 "0"))))]
      (is (= {:code :viewer-build-stale} (failure build/check!))))))

(deftest actual-byte-bound-and-strict-utf8-on-resources
  (let [file (Files/createTempFile "viewer-input-" ".txt" (make-array FileAttribute 0))]
    (try
      (with-redefs [io/resource (fn [_] (.toURL (.toUri file)))]
        (spit (str file) "λ")
        (is (= "λ" (asset/resource-text! "fixture" 2)))
        (is (= {:code :viewer-byte-limit} (failure #(asset/resource-text! "fixture" 1))))
        (with-open [stream (io/output-stream (str file))]
          (.write stream (byte-array [(unchecked-byte 195) (unchecked-byte 40)])))
        (is (= {:code :viewer-utf8} (failure #(asset/resource-text! "fixture" 8)))))
      (finally (Files/deleteIfExists file))))
  (with-redefs [io/resource (constantly nil)]
    (is (= {:code :viewer-unavailable} (failure #(asset/resource-text! "fixture" 8))))))

(deftest manifest-admission-rejects-extra-fields-and-trailing-input
  (let [read-resource asset/resource-text! text (canonical/encode (asset/manifest!) 65536)]
    (doseq [[changed code] [[(str text " {}") :trailing-input]
                            [(str (subs text 0 (dec (count text))) " :label \"extra\"}") :invalid-input-shape]]]
      (with-redefs [asset/resource-text! (fn [path limit]
                                           (if (= path "gate/viewer/manifest.edn") changed
                                               (read-resource path limit)))]
        (is (= code (:code (failure asset/load!))))))))
