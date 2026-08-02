(ns scratchcard.provenance-test
  "Pins the run stamp.

  The floors here matter more than the happy paths. Most of these collectors
  degrade to nil or an empty map by design, so the failure mode is not an
  exception — it is a manifest that looks complete and says nothing. A pin map
  that silently became `{}` because a regex stopped matching would pass every
  shape assertion ever written about it."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [devcards.host :as host]
   [scratchcard.digest :as digest]
   [scratchcard.provenance :as prov]
   [scratchcard.scope :as scope])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private repo-root
  (scope/discover-repo-root (System/getProperty "user.dir")))

(deftest git-stamp-identifies-the-checkout
  (let [s (prov/git-stamp repo-root)]
    (is (re-matches #"[0-9a-f]{40}" (:sha s)))
    (is (str/starts-with? (:sha s) (:short-sha s)))
    (is (not (str/blank? (:branch s))))
    (testing "dirtiness is a boolean, never nil — a reader must not have to guess"
      (is (boolean? (:dirty? s))))))

(deftest file-stamp-states-absence-positively
  (testing "a missing file is recorded, not omitted"
    ;; An omitted key forces the reader to interpret; :exists? false does not.
    (let [s (prov/file-stamp "/nonexistent/scratchcard/probe.bin")]
      (is (false? (:exists? s)))
      (is (nil? (:sha256 s)))))
  (testing "a real file carries size and a digest that agrees with digest/of-file"
    (let [tmp (Files/createTempFile "scratchcard-prov" ".bin"
                                    (into-array FileAttribute []))
          f (.toFile tmp)]
      (spit f "scratchcard")
      (let [s (prov/file-stamp f)]
        (is (true? (:exists? s)))
        (is (= (.length f) (:bytes s)))
        (is (= (digest/of-file f) (:sha256 s)))
        (is (= (digest/of-string "scratchcard") (:sha256 s)))))))

(deftest toolchain-pins-are-parsed-and-non-empty
  ;; NON-VACUITY FLOOR. `toolchain-pins` reads Dockerfile.base with a regex; a
  ;; format change would yield {} and every downstream shape check would still
  ;; pass. An empty pin map is a FAILURE, never a pass.
  (let [pins (prov/toolchain-pins repo-root)]
    (is (seq pins) "no toolchain pins parsed from Dockerfile.base")
    (testing "the pins the render path actually depends on are present"
      (is (contains? pins :GRAALVM_VERSION))
      (is (contains? pins :WASI_SDK_VERSION)))
    (testing "every value is a non-blank string"
      (is (every? #(and (string? %) (not (str/blank? %))) (vals pins))))
    (testing "the parse is generic — it finds more than the two named above,\n           so a NEW pin is tracked without editing this code"
      (is (> (count pins) 2)))))

(deftest toolchain-pins-absent-file-is-nil-not-empty
  ;; nil and {} must not be confused: nil means "could not look",
  ;; {} would mean "looked and found none". Only one of those is honest here.
  (let [empty-dir (str (Files/createTempDirectory "scratchcard-noroot"
                                                  (into-array FileAttribute [])))]
    (is (nil? (prov/toolchain-pins empty-dir)))))

(deftest protocol-stamp-reads-its-one-home
  ;; Copied literals here would be a second home for the contract the goldens
  ;; and the cross-engine mirror were minted under.
  (let [p (prov/protocol-stamp)]
    (is (= host/render-ticks (:ticks p)))
    (is (= host/tick-ms (:tick-ms p)))
    (is (= host/default-dpi (:dpi p)))
    (testing "and they are real numbers, so a nil from a renamed var is caught"
      (is (every? number? (vals p))))))

(deftest wasm-stamp-carries-both-identities
  (let [s (prov/wasm-stamp repo-root)]
    (is (str/ends-with? (:path s) "controls.wasm"))
    (testing "when the artifact is present it carries bytes AND a digest"
      (when (:exists? s)
        (is (pos? (:bytes s)))
        (is (re-matches #"[0-9a-f]{64}" (:sha256 s)))))
    (testing "build-sha answers a DIFFERENT question from sha256 and may be absent"
      (is (or (nil? (:build-sha s)) (string? (:build-sha s)))))))

(deftest collect-produces-every-top-level-block
  (let [c (prov/collect {:repo-root repo-root
                         :image-tag nil
                         :runtime {:abi 4 :fb-format 1 :engine-impl "probe"}})]
    (is (= #{:protogen :wasm :toolchain :protocol :host}
           (set (keys c))))
    (testing "runtime facts are merged onto the wasm block, not lost"
      (is (= 4 (get-in c [:wasm :abi])))
      (is (= 1 (get-in c [:wasm :fb-format]))))
    (testing "engine-impl rides the toolchain block — the archive self-describes"
      (is (= "probe" (get-in c [:toolchain :engine-impl]))))
    (testing "a nil image-tag does not invent an image-id"
      (is (nil? (get-in c [:toolchain :image-id]))))))

(deftest collect-survives-a-runtime-that-never-started
  ;; The run that most needs a stamp is the one that failed before a host
  ;; existed. Provenance must not require the facts only a live host can give.
  (let [c (prov/collect {:repo-root repo-root :image-tag nil :runtime {}})]
    (is (some? (get-in c [:protogen :sha])))
    (is (some? (get-in c [:protocol :ticks])))
    (is (nil? (get-in c [:wasm :abi])))
    (testing "engine-impl is absent rather than a misleading placeholder"
      (is (not (contains? (:toolchain c) :engine-impl))))))

(deftest digest-of-file-is-nil-for-a-directory
  ;; A directory is not a file; returning a digest for one would be a lie that
  ;; only surfaces as a mismatched manifest much later.
  (is (nil? (digest/of-file (io/file repo-root)))))
