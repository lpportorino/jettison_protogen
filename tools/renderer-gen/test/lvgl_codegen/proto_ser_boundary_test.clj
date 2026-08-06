(ns lvgl-codegen.proto-ser-boundary-test
  "The rest of the protobuf serialization boundary — the surface
   `lvgl-codegen.proto-ser-presence-test` does not reach.

   WHY BOTH EXIST. The presence suite exists for one contract and would be
   diluted by unrelated cases. This one exists so the WHOLE of
   `lvgl-codegen.proto-ser` and `lvgl-codegen.normalize` is driven by
   `clojure -M:test`, which is the precondition
   `lvgl-codegen.spec-coverage/enrolled` sets for enrolling a namespace: the
   honest way to grow that list is to write the test, never to widen the list.
   Until these landed, BOTH namespaces were at zero — nothing in the test tree
   required either, so their `m/=>` contracts were registered and never
   evaluated.

   Every test here asserts a CONTRACT, not merely a call. A test written to tick
   a coverage box is a green that proves the function is loadable, which is the
   defect this repo's spec-coverage docstring already refuses.

   Hermetic: in-memory except `write-bytes!`, whose whole contract is a
   filesystem one; that test uses a per-run temp directory and removes it."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.normalize :as normalize]
            [lvgl-codegen.proto-ser :as proto-ser])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]
           [ui UiAst$StateUpdate]))

(set! *warn-on-reflection* true)

;; ── StateUpdate: the host→WASM controller-binding inbound payload ────────────

(deftest state-update-bytes-carry-both-oneof-arms
  (testing "`state-update->bytes` emits a StateUpdate whose SubjectValue oneof
            keeps the int and the string arms apart — the arm is the contract,
            and a collapsed one would silently retype a subject"
    (let [^bytes bs (proto-ser/state-update->bytes
                     {:values [{:name "volume" :int_value 70}
                               {:name "mode" :string_value "manual"}]})
          parsed (UiAst$StateUpdate/parseFrom bs)
          vs (.getValuesList parsed)]
      (is (= 2 (.size vs)) "both subject values survive")
      (is (= "volume" (.getName (.get vs 0))))
      (is (= 70 (.getIntValue (.get vs 0))) "the int arm carries its value")
      (is (= "mode" (.getName (.get vs 1))))
      (is (= "manual" (.getStringValue (.get vs 1))) "the string arm carries its value"))))

;; ── ScreenPatch: the controls_apply_patch inbound payload ────────────────────

(def ^:private a-patch-ir
  ;; Both hashes are FNV-1a-32 values; pronto carries a proto uint32 in a Java
  ;; int, so a test value must sit inside the signed range rather than the
  ;; unsigned one.
  {:base_hash 305419896
   :target_hash 1412567295
   :ops [{:kind :PATCH_OP_REMOVE_NODE :target_uid 42}
         {:kind :PATCH_OP_MOVE_NODE :target_uid 7 :parent_uid 3 :index 2}]})

(deftest patch-bytes-round-trip-preserves-hashes-and-op-order
  (testing "`patch->bytes` / `bytes->patch` are inverses over a patch IR. The
            hashes are the reconciler's fail-fast guard against an out-of-order
            push, and op ORDER is load-bearing — a patch is applied in sequence"
    (let [round-tripped (proto-ser/bytes->patch (proto-ser/patch->bytes a-patch-ir))]
      (is (= (:base_hash a-patch-ir) (:base_hash round-tripped)) "base_hash survives")
      (is (= (:target_hash a-patch-ir) (:target_hash round-tripped)) "target_hash survives")
      (is (= [:PATCH_OP_REMOVE_NODE :PATCH_OP_MOVE_NODE] (mapv :kind (:ops round-tripped)))
          "op kinds survive in order")
      (is (= [42 7] (mapv :target_uid (:ops round-tripped))) "op targets survive in order")
      (is (= 3 (:parent_uid (second (:ops round-tripped)))) "the MOVE destination survives"))))

;; ── validate-ir!: the proto-IR backstop ─────────────────────────────────────

(deftest validate-ir-throws-with-the-malli-explain-path-not-a-pronto-error
  (testing "`validate-ir!` exists so pronto is unreachable as a validation layer
            — a pronto failure names a proto field and nothing else. The
            contract is that a bad IR raises ex-info carrying the humanized
            Malli path, and that a good one is silent"
    (is (nil? (proto-ser/validate-ir! {:root {:type :WIDGET_OBJ}} "ok.pb"))
        "a conforming IR validates silently")
    (let [thrown (try (proto-ser/validate-ir! {:root {:type :NOT_A_WIDGET}} "bad.pb")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "a non-conforming IR throws")
      (is (= "bad.pb" (:output (ex-data thrown))) "the failure names the output path")
      (is (some? (get-in (ex-data thrown) [:explain :root :type]))
          "the failure carries the Malli explain path to the offending field"))))

;; ── write-bytes!: the ATOMIC artifact write ─────────────────────────────────

(deftest write-bytes-is-atomic-and-leaves-no-tmp-sibling
  (testing "`write-bytes!` writes a sibling .tmp and renames it onto the target,
            so a concurrent reader can never see a torn artifact. The observable
            contract is the returned count, the file's content, and — the part a
            non-atomic implementation would fail — no surviving .tmp"
    (let [dir (Files/createTempDirectory "proto-ser-boundary" (make-array FileAttribute 0))
          target (str (.resolve dir "screen.pb"))
          payload (byte-array [1 2 3 4 5])]
      (try
        (is (= 5 (proto-ser/write-bytes! payload target)) "returns the byte count")
        (is (= [1 2 3 4 5] (vec (Files/readAllBytes (.resolve dir "screen.pb"))))
            "the target holds exactly the bytes written")
        (is (not (Files/exists (.resolve dir "screen.pb.tmp") (make-array java.nio.file.LinkOption 0)))
            "the .tmp sibling was renamed away, not left behind")
        (is (= 3 (proto-ser/write-bytes! (byte-array [9 9 9]) target))
            "a second write replaces the target")
        (is (= [9 9 9] (vec (Files/readAllBytes (.resolve dir "screen.pb"))))
            "the replacement is complete, not appended")
        (finally
          (doseq [^Path p (reverse (vec (iterator-seq (.iterator (Files/walk dir (make-array java.nio.file.FileVisitOption 0))))))]
            (Files/deleteIfExists p)))))))

;; ── normalize: the three roundtrip-equality strategies ──────────────────────

(deftest strip-defaults-removes-proto3-defaults-and-keeps-real-values
  (testing "`strip-defaults` (and the `proto3-default?` predicate it consults at
            every depth) removes exactly what proto3 declines to serialize —
            zero, empty string, false, empty collections and enum-zero — while
            keeping every value the wire would actually carry"
    (is (= {:a 1 :nested {:keep "x"}}
           (normalize/strip-defaults
            {:a 1 :zero 0 :empty "" :flag false :none [] :blank {}
             :enum-zero :WIDGET_OBJ :nested {:keep "x" :drop 0}}))
        "defaults are stripped at every depth, real values survive")
    (is (nil? (normalize/strip-defaults {:only 0}))
        "a map that is entirely defaults collapses to nil, as proto3 omits it")
    (is (= [{:a 1}] (normalize/strip-defaults [{:a 1 :b 0}]))
        "vectors of maps are walked")))

(deftest normalize-for-comparison-canonicalizes-both-sides
  (testing "`normalize-for-comparison` puts a source IR and a deserialized IR
            through the SAME canonical roundtrip, so an equality check between
            them cannot fail merely because proto3 dropped a default on one
            side. A sparse source and its own roundtrip must land equal"
    (let [source {:root {:type :WIDGET_OBJ :text "hi"}}
          deser (normalize/canonicalize source)
          [a b] (normalize/normalize-for-comparison source deser)]
      (is (= a b) "both sides canonicalize to the same map")
      (is (= "hi" (get-in a [:root :text])) "the real value survives canonicalization"))))
