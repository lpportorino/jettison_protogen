(ns devcards.border-test
  "Tests for the exact contour-continuity instrument and for the registry
   seam that now admits it.

   The two halves are different populations and are labelled as such. The
   MEASUREMENT tests build a framebuffer by hand and call `measure-contour`
   directly; they pin the arithmetic and nothing else. The REGISTRY tests go
   through `findings/card-findings` — the path a consumer's gate actually
   takes — and pin that the producer's honest declaration registers, that a
   caller who cannot supply pixels is refused rather than handed nil, and
   that the closed context set is still closed."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.border :as border]
            [devcards.findings :as findings]))

(defn- blank-frame
  "An opaque, all-black framebuffer in the `:framebuffer` context shape."
  [w h]
  (let [px (byte-array (* w h 4))]
    (dotimes [i (* w h)]
      (aset-byte px (+ (* i 4) 3) (unchecked-byte 255)))
    {:bytes px :width w :height h}))

(defn- set-pixel!
  [fb [x y] [r g b]]
  (let [^bytes px (:bytes fb)
        i (* (+ (* (long y) (long (:width fb))) (long x)) 4)]
    (aset-byte px i (unchecked-byte r))
    (aset-byte px (+ i 1) (unchecked-byte g))
    (aset-byte px (+ i 2) (unchecked-byte b))
    fb))

(defn- paint-contour!
  [fb contour rgb]
  (doseq [sample contour]
    (set-pixel! fb (:point sample) rgb))
  fb)

(defn- refusal
  "How a call ENDED, as a comparable value: `[:refused msg]`, `[:threw class
   msg]`, or `[:returned-without-refusing]`.

   `thrown-with-msg?` is deliberately not used for the canaries below.
   Removing a refusal rarely makes the call succeed — it usually makes it
   fail somewhere ELSE, an NPE or an array bound deep inside a rule that was
   handed the input the deleted clause existed to reject — and clojure.test
   reports a non-matching exception class as an ERROR, which reds the whole
   var while saying nothing about the clause under test. Reducing the call
   to a value makes every one of those mutations a FAIL whose printed
   `actual` names what happened instead."
  [f]
  (try (let [_ (f)] [:returned-without-refusing])
       (catch clojure.lang.ExceptionInfo e [:refused (ex-message e)])
       (catch Throwable t [:threw (.getName (class t)) (ex-message t)])))

(defn- refusal-free
  "`f`'s value, or `[:refused msg]` when it was refused — the mirror of
   `refusal`, for a canary asserting that a call SUCCEEDS. Same reason: the
   mutation must print the refusal as an `actual`, not vanish into an
   ERROR."
  [f]
  (try (f) (catch clojure.lang.ExceptionInfo e [:refused (ex-message e)])))

(defn- target
  [contour overrides]
  (merge {:id :control
          :contour contour
          :closed? true
          :min-delta 1
          :max-gap-px 0}
         overrides))

;; ── the measurement ──────────────────────────────────────────────────────

(deftest low-contrast-continuous-beats-high-contrast-broken
  (let [contour (border/rect-contour [2 2 7 7])
        low (paint-contour! (blank-frame 10 10) contour [1 0 0])
        high-broken (paint-contour! (blank-frame 10 10) contour [255 255 255])]
    ;; Plant a real four-pixel break and assert the mutation landed before
    ;; trusting the verdict.
    (doseq [sample (subvec contour 5 9)]
      (set-pixel! high-broken (:point sample) [0 0 0]))
    (is (= 20 (:visible-count (border/measure-contour low (target contour {}))))
        "control: every low-delta contour sample is present")
    (let [broken (border/measure-contour high-broken (target contour {}))]
      (is (= 16 (:visible-count broken))
          "mutation landed: four high-contrast contour pixels are absent")
      (is (= :pass (:verdict (border/measure-contour low (target contour {}))))
          "continuity is exact at the declared digital floor, not a preference for high contrast")
      (is (= :fail (:verdict broken)))
      (is (= 4 (:longest-gap-px broken))))))

(deftest equal-edge-counts-do-not-imply-equal-continuity
  ;; THE CLAUSE THIS PRODUCER EXISTS FOR. Both mutations remove exactly four
  ;; edge pixels, so every coverage-shaped statistic is identical; only the
  ;; RUN-RESET in `longest-linear-gap` tells them apart.
  ;; REVERT-TO-BREAK: in `devcards.border/longest-linear-gap`, change the
  ;; visible branch from `[0 best]` to `[run best]` — the run then never
  ;; resets, `:longest-gap-px` becomes the count of missing samples, and this
  ;; test alone goes red while `no-edge-signal-is-one-whole-contour-gap` and
  ;; `low-contrast-continuous-beats-high-contrast-broken` stay green.
  (let [contour (border/rect-contour [2 2 7 7])
        distributed (paint-contour! (blank-frame 10 10) contour [255 255 255])
        contiguous (paint-contour! (blank-frame 10 10) contour [255 255 255])]
    (doseq [i [1 6 11 16]]
      (set-pixel! distributed (:point (nth contour i)) [0 0 0]))
    (doseq [i (range 6 10)]
      (set-pixel! contiguous (:point (nth contour i)) [0 0 0]))
    (let [allow-one (target contour {:max-gap-px 1})
          a (border/measure-contour distributed allow-one)
          b (border/measure-contour contiguous allow-one)]
      (is (= 16 (:visible-count a) (:visible-count b))
          "the naive on-contour edge count is identical")
      (is (= 1 (:longest-gap-px a)))
      (is (= 4 (:longest-gap-px b)))
      (is (= :pass (:verdict a)))
      (is (= :fail (:verdict b))))))

(deftest a-closed-contour-joins-the-end-back-to-the-start
  (let [contour (border/rect-contour [2 2 7 7])
        fb (paint-contour! (blank-frame 10 10) contour [255 255 255])]
    (doseq [i [18 19 0 1 2]]
      (set-pixel! fb (:point (nth contour i)) [0 0 0]))
    (is (= 5
           (:longest-gap-px
            (border/measure-contour fb (target contour {:max-gap-px 4}))))
        "the seam is one five-pixel break, not two short linear breaks")
    (is (= :fail (:verdict
                  (border/measure-contour fb (target contour {:max-gap-px 4})))))))

(deftest no-edge-signal-is-one-whole-contour-gap
  (let [contour (border/rect-contour [2 2 7 7])
        result (border/measure-contour (blank-frame 10 10) (target contour {}))]
    (is (= 20 (:sample-count result)))
    (is (= 20 (:longest-gap-px result)))
    (is (= 0 (:visible-runs result)))
    (is (= :fail (:verdict result)))))

(deftest the-digital-floor-is-declared-and-exact
  (let [contour (border/rect-contour [2 2 7 7])
        fb (paint-contour! (blank-frame 10 10) contour [2 1 0])]
    (is (= :pass (:verdict
                  (border/measure-contour fb (target contour {:min-delta 3})))))
    (is (= :fail (:verdict
                  (border/measure-contour fb (target contour {:min-delta 4})))))
    (is (= 3 (:weakest-delta
              (border/measure-contour fb (target contour {:min-delta 3})))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"1 through 765"
         (border/measure-contour fb (target contour {:min-delta 0}))))))

(deftest unjudgeable-targets-are-findings-not-skips
  (let [fb (blank-frame 8 8)
        clipped (target (border/rect-contour [0 0 4 4]) {})
        clipped-result (border/measure-contour fb clipped)]
    (is (= :unjudgeable (:verdict clipped-result)))
    (is (= :canvas-clipped (:reason clipped-result)))
    (let [fs (border/findings {:card-id "c"
                               :framebuffer fb
                               :declaration {:borders [clipped]}})
          f (first fs)]
      (is (= 1 (count fs)))
      (is (= :border-contour-untested (:invariant f)))
      (is (= :untested (:act/outcome f)))
      (is (= :canvas-clipped (:act/reason f))))))

(deftest a-nonopaque-sample-is-unjudgeable
  (let [contour (border/rect-contour [2 2 7 7])
        fb (paint-contour! (blank-frame 10 10) contour [255 255 255])
        [x y] (:point (nth contour 3))
        alpha-index (+ (* (+ (* (long y) 10) (long x)) 4) 3)]
    (aset-byte ^bytes (:bytes fb) alpha-index (unchecked-byte 254))
    (let [result (border/measure-contour fb (target contour {}))]
      (is (= :unjudgeable (:verdict result)))
      (is (= :nonopaque-sample (:reason result)))
      (is (= 3 (:sample-index result))))))

(deftest the-producer-body-reports-only-nonpasses
  (let [contour (border/rect-contour [2 2 7 7])
        clean (paint-contour! (blank-frame 10 10) contour [5 5 5])
        broken (blank-frame 10 10)]
    (is (= []
           (border/findings {:card-id "clean"
                             :framebuffer clean
                             :declaration {:borders [(target contour {})]}})))
    (let [fs (border/findings {:card-id "broken"
                               :framebuffer broken
                               :declaration {:borders [(target contour {})]}})
          f (first fs)]
      (is (= 1 (count fs)))
      (is (= :border-contour-discontinuous (:invariant f)))
      (is (= :fail (get-in f [:measurement :verdict])))
      (is (and (string? (:detail f))
               (re-find #"20px contour-signal gap" (:detail f)))))))

(deftest every-input-is-required-and-none-is-defaulted
  (let [contour (border/rect-contour [2 2 7 7])
        ctx {:card-id "c"
             :framebuffer (blank-frame 10 10)
             :declaration {:borders [(target contour {})]}}]
    (doseq [k (conj border/required-context :card-id)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"omits required keys"
           (border/findings (dissoc ctx k)))
          (str k " must not acquire a default")))))

(deftest malformed-contours-fail-loud
  (let [fb (blank-frame 10 10)
        contour (border/rect-contour [2 2 7 7])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"one-pixel Chebyshev spacing"
         (border/measure-contour fb (target (assoc-in contour [4 :point] [9 9]) {}))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"unknown keys"
         (border/measure-contour fb (assoc (target contour {})
                                           :silent-default true))))
    (is (re-find #"describe different images"
                 (str (refusal #(border/measure-contour
                                 {:bytes (byte-array 4) :width 10 :height 10}
                                 (target contour {})))))
        "a buffer that disagrees with its own dimensions is refused before
         a single pixel is sampled")))

;; ── the registry seam ────────────────────────────────────────────────────

(deftest the-registry-ADMITS-the-declaration-this-rule-has-to-make
  (testing "the producer declares EVERY input it reads and registers on that
            declaration — the whole blocker this namespace was stuck behind.
            REVERT-TO-BREAK: remove :framebuffer from `findings/context-keys`;
            this comparison then FAILS carrying the registry's own refusal."
    (is (= [border/producer]
           (refusal-free #(findings/validate-producers! [border/producer])))))
  (testing "CONTROL: the set is still CLOSED, so what landed is ONE key and
            not an open door. A near-miss of the key that was added is still
            refused at registration, and it stays refused under the mutation
            above — which is what attributes that red to the widening."
    (is (re-find #"unknown context keys \[:framebuffer-width\]"
                 (str (refusal #(findings/validate-producers!
                                 [(assoc border/producer
                                         :requires #{:framebuffer-width})])))))))

(deftest the-rule-judges-through-the-real-registry
  (let [contour (border/rect-contour [2 2 7 7])
        broken (blank-frame 10 10)
        live (:live (findings/card-findings
                     {:card-id "c"
                      :framebuffer broken
                      :declaration {:borders [(target contour {})]}
                      :producers [border/producer]}))]
    (is (= 1 (count live)))
    (is (= :border-contour-discontinuous (:invariant (first live))))
    (testing "and the finding is attributed — the registry's own stamp, which
              `outcome/axis-problem` requires before any ACT axis is honoured"
      (is (= :border (:producer (first live))))))
  (testing "an unjudgeable target survives the ACT axis check: the declared
            :untested outcome and its declared reason reach the verdict"
    (let [live (:live (findings/card-findings
                       {:card-id "c"
                        :framebuffer (blank-frame 8 8)
                        :declaration
                        {:borders [(target (border/rect-contour [0 0 4 4]) {})]}
                        :producers [border/producer]}))]
      (is (= [:untested :canvas-clipped]
             [(:act/outcome (first live)) (:act/reason (first live))])))))

(deftest a-caller-that-cannot-supply-pixels-is-REFUSED
  (testing "the registry refuses the call rather than handing the rule nil —
            a border rule with no framebuffer returns [], which is
            byte-identical to a card whose every border is continuous.
            REVERT-TO-BREAK: drop :framebuffer from `border/required-context`."
    (is (re-find #"requires context \[:framebuffer\]"
                 (str (refusal #(findings/card-findings
                                 {:card-id "c"
                                  :declaration {:borders []}
                                  :producers [border/producer]}))))))
  (testing "and a nil framebuffer counts as ABSENT, not as a supplied claim"
    (is (re-find #"requires context \[:framebuffer\]"
                 (str (refusal #(findings/card-findings
                                 {:card-id "c"
                                  :framebuffer nil
                                  :declaration {:borders []}
                                  :producers [border/producer]}))))))
  (testing "CONTROL: the same call WITH pixels runs and returns a clean
            judgement, so the throws above key on the missing input and not
            on something else about the call"
    (is (= [] (:live (findings/card-findings
                      {:card-id "c"
                       :framebuffer (blank-frame 10 10)
                       :declaration {:borders []}
                       :producers [border/producer]}))))))

(deftest a-HALF-supplied-framebuffer-is-refused-at-the-seam
  (testing "{:width :height} with no :bytes is `some?`, so `check-requires!`
            is satisfied and the nil arrives inside the value. The seam check
            is the only thing standing there.
            REVERT-TO-BREAK: delete the `(not (bytes? px))` clause from
            `findings/framebuffer-problem`."
    (is (re-find #":bytes must be a byte array"
                 (str (refusal #(findings/card-findings
                                 {:card-id "c"
                                  :framebuffer {:width 10 :height 10}
                                  :declaration {:borders []}
                                  :producers [border/producer]}))))))
  (testing "dimensions that disagree with the buffer are refused too — a rule
            sampling [x y] through the wrong width reads the wrong pixels and
            still returns a well-formed verdict.
            REVERT-TO-BREAK: delete the byte-length clause from
            `findings/framebuffer-problem`."
    (is (re-find #"describe different images"
                 (str (refusal #(findings/card-findings
                                 {:card-id "c"
                                  :framebuffer {:bytes (byte-array (* 10 10 4))
                                                :width 20
                                                :height 10}
                                  :declaration {:borders []}
                                  :producers [border/producer]}))))))
  (testing "CONTROL: the well-formed value passes the same seam, so both
            throws key on the defect and not on the check firing always"
    (is (nil? (findings/framebuffer-problem (blank-frame 10 10))))))

(deftest an-absent-borders-DECLARATION-is-an-oversight
  (testing "the closed context set admits :declaration whole, so a caller
            supplying {:layers …} and forgetting :borders satisfies
            `check-requires!` and hands this rule nothing to judge.
            REVERT-TO-BREAK: delete the `(vector? targets)` refusal from
            `border/declared-targets`."
    (is (re-find #"must carry :borders"
                 (str (refusal #(findings/card-findings
                                 {:card-id "c"
                                  :framebuffer (blank-frame 10 10)
                                  :declaration {:layers {}}
                                  :producers [border/producer]}))))))
  (testing "CONTROL: an EMPTY :borders vector is a CLAIM and judges cleanly —
            the refusal keys on absence, not on having nothing to do"
    (is (= [] (:live (findings/card-findings
                      {:card-id "c"
                       :framebuffer (blank-frame 10 10)
                       :declaration {:borders []}
                       :producers [border/producer]})))))
  (testing "and duplicate target ids are refused, so a finding always names
            exactly one declared contour"
    (let [contour (border/rect-contour [2 2 7 7])]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"unique :id"
           (border/findings {:card-id "c"
                             :framebuffer (blank-frame 10 10)
                             :declaration {:borders [(target contour {})
                                                     (target contour {})]}}))))))
