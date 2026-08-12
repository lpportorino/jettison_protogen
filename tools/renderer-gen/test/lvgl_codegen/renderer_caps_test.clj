(ns lvgl-codegen.renderer-caps-test
  "Guard tests for `lvgl-codegen.renderer-caps/check-headroom!`'s COUNTED-SET
   assertion — the both-directions check that the renderer-caps manifest and
   the codegen agree about which pools are headroom-counted.

   The manifest is this project's own emission (`renderer-gen.renderer-caps-json`
   parses the reference interpreter's MAX_* defines and partitions every one into
   `caps` — the codegen counts it — or `non-headroom-caps`, a written rationale
   for why it needs no count). So the partition and its consumer live in one
   repository, and a promotion out of the allowlist must land a `counts` entry in
   the same change or the pool is silently ungated.

   Both directions matter and only one of them is self-reporting:

   - a COUNTED concern with no pinned cap would un-gate that count, and the
     over-cap scan cannot see it either;
   - a pinned cap the codegen never MEASURES is skipped by the nil-guarded
     over-cap scan, so the build reads green over an ungated pool. That is the
     silent direction, and the reason the assertion is worth its lines.

   The pass value of a completeness assertion EQUALS its nothing-ran value, so a
   green run proves nothing on its own; the two canary cases below drive the REAL
   `check-headroom!` over a planted pinned set and watch it refuse BY NAME.

   Hermetic: no wasm, no proto classes, no fixtures beyond the committed
   manifest this project emits — the screens are built in memory."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.renderer-caps :as renderer-caps]))

(set! *warn-on-reflection* true)

(def ^:private innocent-ir
  "An IR comfortably inside every real cap — so any refusal over it is
   attributable to a planted pinned entry, never to the screen itself."
  {:subjects [] :root {:type :WIDGET_OBJ}})

;; ── The live both-directions assertion, against the emitted manifest ────────
(deftest test-pinned-manifest-covers-every-counted-concern
  (testing
   "each concern `counts` measures has a pinned cap (a manifest that
            dropped one would silently un-gate that count)"
    (let [measured (renderer-caps/counts innocent-ir)]
      (is (= #{} (set (remove (renderer-caps/caps) (keys measured))))))))

(deftest test-codegen-measures-every-counted-cap
  (testing
   "the inverse direction — every cap the manifest declares COUNTED is
            actually measured by `counts` (an unmeasured one is skipped by the
            nil-guarded over-cap scan, so it would be ungated in silence)"
    (let [measured (renderer-caps/counts innocent-ir)]
      (is (= #{} (set (remove (set (keys measured)) (keys (renderer-caps/caps)))))))))

;; ── Fail canary: the assertion must be watched refusing ─────────────────────
(deftest test-unmeasured-cap-canary-refuses-by-name
  (testing "a manifest cap the codegen does not measure fails loud, named"
    ;; The plant is the exact promotion this guard exists for: a define moving
    ;; out of `non-headroom-caps` into `caps` without a matching `counts` entry.
    ;; Without the assertion, `counts` measuring no such concern leaves the pool
    ;; ungated and the build green.
    (let [planted (assoc (renderer-caps/caps)
                         :pending-visibility
                         {:define "MAX_PENDING_VISIBILITY" :cap 32})]
      (with-redefs [renderer-caps/caps (constantly planted)]
        (is
         (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"declares counted cap\(s\) the codegen does not measure: \[:pending-visibility\]"
          (renderer-caps/check-headroom! innocent-ir "out/x.pb"))))
      (testing "and the SAME ir is green once the plant is removed"
        (with-redefs [renderer-caps/caps (constantly (dissoc planted :pending-visibility))]
          (is (nil? (renderer-caps/check-headroom! innocent-ir "out/x.pb"))))))))

(deftest test-unmeasured-cap-canary-does-not-blame-a-neighbour
  (testing "the refusal names only the planted concern — measured neighbours stay green"
    (let [planted (assoc (renderer-caps/caps)
                         :pending-visibility
                         {:define "MAX_PENDING_VISIBILITY" :cap 32})]
      (with-redefs [renderer-caps/caps (constantly planted)]
        ;; Defaulted rather than nil-punned: under a disabled guard the call
        ;; returns normally, and a nil message would turn every assertion
        ;; below into an NPE error instead of a readable failure.
        (let [caught (try (renderer-caps/check-headroom! innocent-ir "out/x.pb")
                          nil
                          (catch clojure.lang.ExceptionInfo e
                            {:msg (ex-message e) :data (ex-data e)}))
              {:keys [msg data]} (or caught {:msg "" :data {}})]
          (is (some? caught) "the planted cap must refuse")
          (is (= [:pending-visibility] (:unmeasured data))
              "exactly the planted concern is reported")
          ;; A measured neighbour must not be dragged in by the same input.
          (is (not (re-find #"subjects" msg)) "a measured neighbour is not named")
          (is (not (re-find #"exceeded" msg))
              "this is the completeness finding, not an over-cap one"))))))
