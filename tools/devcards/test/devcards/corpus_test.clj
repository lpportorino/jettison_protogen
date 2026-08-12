(ns devcards.corpus-test
  "Unit tests for the generic themed-corpus driver (`devcards.corpus`) — the
   render-loop/error-partitioning/golden-diff mechanics every bring-your-own-
   corpus consumer plugs into, exercised over a fake render fn (no wasm, no
   host)."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.corpus :as corpus]
            [devcards.probe :as probe]))

(def ^:private screens
  [{:id "b-screen" :payload :b} {:id "a-screen" :payload :a}])

(def ^:private variants
  [{:key :dark :dark 1} {:key :light :dark 0}])

(defn- fake-result
  "Render stand-in: deterministic sha per (id, variant); throws for the one
   poisoned pair; carries a finding for a-screen dark."
  [{:keys [id]} {vk :key}]
  (when (and (= id "b-screen") (= vk :light))
    (throw (ex-info "renderer rejected screen" {:id id})))
  {:sha256 (str (name vk) "-" id)
   :w 10
   :h 20
   :findings (if (and (= id "a-screen") (= vk :dark))
               [{:invariant :zero-area :node "lv_label#1"}]
               [])})

(deftest render-corpus-partitions-and-tags
  (let [{:keys [by-variant findings errors]}
        (corpus/render-corpus screens variants fake-result)]
    (testing "errored pair lands in :errors, tagged, and OUT of the golden maps"
      (is (= [{:variant :light :id "b-screen" :error "renderer rejected screen"}]
             errors))
      (is (= #{"a-screen" "b-screen"} (set (keys (get by-variant :dark)))))
      (is (= #{"a-screen"} (set (keys (get by-variant :light))))))
    (testing "golden entries carry exactly sha/w/h"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20}
             (get-in by-variant [:dark "a-screen"]))))
    (testing "golden maps are sorted by id (stable manifest diffs)"
      (is (sorted? (get by-variant :dark)))
      (is (= ["a-screen" "b-screen"] (vec (keys (get by-variant :dark))))))
    (testing "findings are tagged with their variant + id"
      (is (= [{:invariant :zero-area :node "lv_label#1" :variant :dark :id "a-screen"}]
             findings)))))

(deftest render-corpus-refuses-empty
  (testing "a zero-screen or zero-variant corpus proves nothing — throw, never
            return a vacuous green"
    (is (thrown? Exception (corpus/render-corpus [] variants fake-result)))
    (is (thrown? Exception (corpus/render-corpus screens [] fake-result)))))

;; ── the card KEY SET the driver carries ──────────────────────────────────
;;
;; The driver is the seam a consumer builds its own corpus on, so what it does
;; with the keys a render fn returns is a contract, not an implementation
;; detail. Three properties, each asserted against a render fn shaped for it:
;; extra keys RIDE ALONG, the documented set is carried BYTE-IDENTICALLY, and a
;; driver-owned key is REFUSED rather than honoured or ignored.

(def ^:private baseline-prstr
  "The EXACT printed form the driver produced for `fake-result` before card-key
   preservation landed, captured from a run against the pre-change function.
   The structural assertions elsewhere in this ns say the VALUES still match;
   this says the BYTES do — key order included — which is what a consumer
   minting an EDN manifest through this seam actually diffs. It is a literal on
   purpose: a re-derivation would compare the new driver against itself.

   THE `:trees []` TERM WAS ADDED DELIBERATELY when the `:tree` channel landed,
   and this is the one edit that may be made to this literal: the RETURN grew a
   channel, so the printed form legitimately changed. What it must never absorb
   is a change under :by-variant — that would mean a card's bytes moved, which
   is the drift this baseline exists to catch."
  (str "{:by-variant {:dark {\"a-screen\" {:sha256 \"dark-a-screen\", :w 10, :h 20},"
       " \"b-screen\" {:sha256 \"dark-b-screen\", :w 10, :h 20}},"
       " :light {\"a-screen\" {:sha256 \"light-a-screen\", :w 10, :h 20}}},"
       " :findings [{:invariant :zero-area, :node \"lv_label#1\","
       " :variant :dark, :id \"a-screen\"}],"
       " :trees [],"
       " :errors [{:variant :light, :id \"b-screen\","
       " :error \"renderer rejected screen\"}]}"))

(deftest render-corpus-output-unchanged-for-documented-key-set
  (testing "a render fn returning exactly the documented {:sha256 :w :h
            :findings} yields output byte-identical to the pre-preservation
            driver"
    (is (= baseline-prstr (pr-str (corpus/render-corpus screens variants fake-result))))))

(defn- thin-result
  "Render stand-in that returns ONLY :sha256 — no :w, no :h, no :findings.
   Destructuring used to fill the two missing dims with an explicit nil; a
   merge-based driver must keep filling them, or a consumer whose render fn is
   this shape gets a manifest that differs from the one it minted yesterday."
  [{:keys [id]} {vk :key}]
  {:sha256 (str (name vk) "-" id)})

(def ^:private baseline-thin-prstr
  "The pre-change printed form for `thin-result`, captured the same way."
  (str "{:by-variant {:dark {\"a-screen\" {:sha256 \"dark-a-screen\", :w nil, :h nil},"
       " \"b-screen\" {:sha256 \"dark-b-screen\", :w nil, :h nil}},"
       " :light {\"a-screen\" {:sha256 \"light-a-screen\", :w nil, :h nil},"
       " \"b-screen\" {:sha256 \"light-b-screen\", :w nil, :h nil}}},"
       " :findings [], :trees [], :errors []}"))

(deftest render-corpus-keeps-the-nil-filled-card-skeleton
  (testing "a render fn that omits :w/:h still gets them, explicitly nil,
            exactly as destructuring produced them"
    (is (= baseline-thin-prstr (pr-str (corpus/render-corpus screens variants thin-result))))))

(defn- bbox-result
  "Render stand-in that returns one key BEYOND the documented set — the shape
   of a consumer recording a per-card geometry rect so a later run can tell
   which rendered images actually differ."
  [{:keys [id]} {vk :key}]
  (assoc (fake-result {:id id} {:key vk}) :bbox [0 0 (count id) 7]))

(deftest render-corpus-preserves-extra-card-keys
  (let [{:keys [by-variant findings]} (corpus/render-corpus screens variants bbox-result)
        card (get-in by-variant [:dark "a-screen"])]
    (testing "the extra key reaches the card — this seam is what a consumer
              threads per-card data through, and dropping it silently is what
              leaves a manifest READING as migrated"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20 :bbox [0 0 8 7]} card)))
    (testing "preservation does not leak the driver's own bookkeeping into the
              card: :findings is CONSUMED (it would push a finding vector into
              every golden entry), and the tag keys are the map's own keys"
      (is (= #{:sha256 :w :h :bbox} (set (keys card)))))
    (testing "the consumed :findings still reaches the findings channel"
      (is (= [{:invariant :zero-area :node "lv_label#1" :variant :dark :id "a-screen"}]
             findings)))))

(defn- hijack
  "Render stand-in that returns a DRIVER-OWNED key. Measured against the
   pre-change driver: `:id` collapsed a four-card corpus into one entry per
   variant, and `:error` partitioned every SUCCESSFUL render into :errors and
   left the golden maps empty. Both silently."
  [k v]
  (fn [{:keys [id]} {vk :key}]
    {:sha256 (str (name vk) "-" id) :w 10 :h 20 :findings [] k v}))

(deftest render-corpus-refuses-driver-owned-keys
  (testing "a render fn returning :id / :variant / :error is REFUSED — honouring
            it corrupts the golden map's keys or its success partition, and
            ignoring it is just as silent"
    (doseq [[k v] [[:id "HIJACKED"] [:variant :nope] [:error "not really"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"driver-owned"
                            (corpus/render-corpus screens variants (hijack k v)))
          (str "expected a refusal for " k))))
  (testing "the refusal names EVERY offending pair, not whichever parallel task
            lost the race, and names the reserved set it enforced"
    (let [data (try (corpus/render-corpus screens variants (hijack :id "HIJACKED"))
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= [:variant :id :error] (:driver-owned data)))
      (is (= [{:variant :dark :id "b-screen" :keys [:id]}
              {:variant :dark :id "a-screen" :keys [:id]}
              {:variant :light :id "b-screen" :keys [:id]}
              {:variant :light :id "a-screen" :keys [:id]}]
             (:clashes data))))))

(deftest diff-cards-three-ways
  (let [expected {"a" {:sha256 "x"} "b" {:sha256 "y"} "gone" {:sha256 "z"}}
        actual {"a" {:sha256 "x"} "b" {:sha256 "DRIFT"} "new" {:sha256 "n"}}
        {:keys [mismatched missing unexpected]} (corpus/diff-cards expected actual)]
    (is (= [{:id "b" :expected "y" :actual "DRIFT"}] mismatched))
    (is (= ["gone"] missing))
    (is (= ["new"] unexpected))))

(deftest diff-cards-clean
  (let [cards {"a" {:sha256 "x"}}]
    (is (= {:mismatched [] :missing [] :unexpected []}
           (corpus/diff-cards cards cards)))))

(deftest probe-find-uid
  (testing "hit returns the exact node (depth-first, descendants included);
            miss returns nil"
    (let [tree {:type "lv_obj" :coords [0 0 9 9]
                :children [{:type "lv_obj" :coords [1 1 4 4] :uid 7
                            :children [{:type "lv_label" :coords [2 2 3 3]
                                        :uid 42 :children []}]}]}]
      (is (= {:type "lv_label" :coords [2 2 3 3] :uid 42 :children []}
             (probe/find-uid tree 42)))
      (is (nil? (probe/find-uid tree 999))))))

(defn- tree-result
  "Render stand-in that returns a dump TREE beside the documented set — the
   shape of a consumer that already holds each tree because it judged the card
   with it, and wants it back without a second render pass."
  [{:keys [id]} {vk :key}]
  (assoc (fake-result {:id id} {:key vk})
         :tree {:type "lv_obj" :id (str (name vk) "-" id)}))

(deftest tree-is-routed-to-its-channel-and-never-into-the-card
  (let [{:keys [by-variant trees]} (corpus/render-corpus screens variants tree-result)]
    (testing "the tree does NOT ride into the golden entry — that is the whole
              reason it is consumed rather than left to ride along, since a
              manifest carrying a dump tree per card is unreviewable and would
              churn on every renderer change"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20}
             (get-in by-variant [:dark "a-screen"]))
          "a :tree key reached card-of — it must be stripped by consumed-card-keys"))
    (testing "and it arrives on the channel, tagged like a finding"
      (is (= 3 (count trees)) "one per SUCCESSFUL pair; the errored pair contributes none")
      (is (= #{{:variant :dark :id "a-screen" :tree {:type "lv_obj" :id "dark-a-screen"}}
               {:variant :dark :id "b-screen" :tree {:type "lv_obj" :id "dark-b-screen"}}
               {:variant :light :id "a-screen" :tree {:type "lv_obj" :id "light-a-screen"}}}
             (set trees))))))

(deftest a-render-fn-with-no-tree-contributes-nothing
  ;; `keep`, not `map`: the absence must be EMPTY rather than a vector of
  ;; {:tree nil}, so a consumer can tell "this driver carried no trees" from
  ;; "every card had a nil one". The two read identically at a call site that
  ;; only checks `seq`, and the second is a defect wearing the first's clothes.
  (testing "a documented-set render fn yields an empty channel, not nil entries"
    (is (= [] (:trees (corpus/render-corpus screens variants fake-result))))))
