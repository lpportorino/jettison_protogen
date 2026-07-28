(ns devcards.border
  "BORDER-CONTOUR CONTINUITY — an exact RAW-framebuffer producer.

   THE QUANTITY. A contour is an ordered, one-pixel-spaced sequence of
   samples. Each sample declares the contour pixel and one pixel on either
   side. The local edge signal is the largest RGB L1 distance among those
   three RAW framebuffer pixels. A signal reaches the contour when it meets
   the caller-declared `:min-delta`; continuity is the longest consecutive
   run that does not. The verdict is exact integer arithmetic:

     longest missing run <= :max-gap-px  => :pass
     longest missing run >  :max-gap-px  => :fail

   There is no seed, stochastic classifier, noise floor, or adjudicator.
   `:unjudgeable` is not an uncertainty band: it means the supplied contour
   cannot be sampled (canvas clipping or non-opaque framebuffer pixels), and
   `findings` turns that into an explicit `:untested` finding.

   COVERAGE IS NOT CONTINUITY, and that difference is the whole rule. Two
   renders that lose the same NUMBER of edge pixels are not equally legible:
   four scattered one-pixel holes still read as a border, one four-pixel
   break reads as a gap. A rule that counted missing samples would score
   those identically — `equal-edge-counts-do-not-imply-equal-continuity` in
   the test namespace is exactly that pair, and it is the clause a mutation
   of this producer must go red on.

   ── THE TWO INPUTS, AND WHY EACH ARRIVES THE WAY IT DOES ──────────────

   `:framebuffer` is an OBSERVATION — the card's RAW rendered pixels,
   {:bytes byte[] :width n :height n}, validated once at the registry seam
   (`findings/framebuffer-problem`). This rule reads pixels, so it declares
   the key, and a caller that cannot supply one is refused by
   `check-requires!` rather than handed nil. That refusal is the whole
   reason this namespace can carry a producer at all: judging zero contours
   returns [], which is byte-identical to a corpus whose every border is
   continuous.

   `:declaration` is INTENT — the consumer's `:borders` vector of contour
   targets, read the way `devcards.layers` reads `(:layers declaration)`.
   It is a declaration and not a derivation because a contour CANNOT be
   recovered from the dump: `dump_obj` reports a rectangle, while a border
   may be rounded, one-sided, part-specific or transformed, and a rule that
   guessed the square perimeter would measure a boundary the renderer never
   drew and report on it with full confidence.

   ── IT MEASURES A BOUNDARY, NEVER A BORDER — READ THIS BEFORE ARMING ──

   The signal is the LARGEST L1 among {point, inner, outer}, so any step
   across the contour reaches the floor, whichever pair produced it. On a
   widget whose FILL differs from its backdrop that step is present at every
   sample REGARDLESS OF THE BORDER, and the two questions come apart
   completely. Measured, not argued —
   `a-fill-against-its-backdrop-supplies-the-signal-with-NO-border-drawn` in
   the test namespace paints a flat filled rect with no stroke of any kind
   and this rule returns :pass at full coverage, then paints a real border,
   deletes four of its pixels, and returns :pass again on the break that
   `equal-edge-counts-do-not-imply-equal-continuity` fails.

   That is the correct quantity for LEGIBILITY — a border whose colour
   equals its fill is invisible against the fill and legible against the
   backdrop, and a rule demanding the stroke be distinct from BOTH sides
   would condemn it — but it is NOT the quantity the words `border` and
   `:border-contour-discontinuous` suggest, and a reader will take a green
   here as `the border is drawn`. It does not say that and cannot. Where the
   two answers must be separated, the separating measurement is a different
   one (the point distinct from both neighbours rather than from either),
   and it belongs in its own rule under its own name — not as a quiet
   re-reading of this one.

   ── WHAT IT CANNOT SEE ────────────────────────────────────────────────
   - It cannot derive rounded, partial-side, part-specific or transformed
     contours from dump-tree rectangles. The producer needs the renderer or
     consumer to declare the actual contour.
   - `:min-delta` is an exact digital floor, not a claim about human
     legibility, panel conditions, colour perception, or hardware.
   - Occluding foreground content can create or erase local signal. This
     instrument has no paint provenance.
   - Non-opaque samples are refused because the visible RGB depends on a
     backdrop the raw framebuffer does not declare.

   ── ARMING, AND THE ONE CYCLE TO AVOID ────────────────────────────────
   `producer` is opt-in: it is in no shipped producer vector, and a consumer
   arms it by appending it to its own. This namespace REQUIRES
   `devcards.findings` — for the one framebuffer shape rule, so that the
   seam's contract and this rule's standalone entry points cannot drift
   apart — so `devcards.findings` must never require this one back. Arm it
   from `devcards.lanes` or from a consumer's own vector, never by adding it
   to `builtin-producers`."
  (:require [devcards.findings :as registry]))

(set! *warn-on-reflection* true)

(def required-context
  "Context keys this producer declares in `:requires`, in addition to the
   registry-supplied `:card-id`. Neither is defaultable: without
   `:framebuffer` there are no bytes to judge, and without `:declaration`
   there is no contour to judge them against."
  #{:framebuffer :declaration})

(def outcomes
  "The ACT outcomes this producer may emit. `:untested` records a target
   whose declared contour cannot be sampled; it is not a third score, and it
   is `:untested` rather than `:cantTell` because in both of its cases the
   clause DECLINED TO SAMPLE — it never ran on that target — where
   `:cantTell` means it ran and could not decide."
  #{:failed :untested})

(def reasons
  "Closed reason vocabulary for the `:untested` outcomes above."
  {:canvas-clipped
   "At least one contour/inside/outside sample lies outside the framebuffer."
   :nonopaque-sample
   (str "At least one sampled RAW pixel has alpha below 255, so its displayed "
        "RGB depends on an undeclared compositing backdrop.")})

(def ^:private sample-keys #{:point :inner :outer})
(def ^:private target-keys
  #{:id :contour :closed? :min-delta :max-gap-px})

(defn- problem
  [message data]
  (throw (ex-info (str "malformed border-continuity input: " message) data)))

(defn- require-keys!
  [kind m required]
  (when-not (map? m)
    (problem (str kind " must be a map") {:kind kind :value m}))
  (when-let [missing (seq (remove #(contains? m %) required))]
    (problem (str kind " omits required keys " (vec (sort missing)))
             {:kind kind :missing (vec (sort missing))}))
  m)

(defn- exact-keys!
  [kind m allowed]
  (when-let [extra (seq (remove allowed (keys m)))]
    (problem (str kind " names unknown keys " (vec (sort extra)))
             {:kind kind :extra (vec (sort extra))}))
  m)

(defn- coordinate?
  [p]
  (and (vector? p)
       (= 2 (count p))
       (every? integer? p)))

(defn- chebyshev-distance
  [[ax ay] [bx by]]
  (max (abs (- (long ax) (long bx)))
       (abs (- (long ay) (long by)))))

(defn- validate-sample!
  [sample]
  (require-keys! "contour sample" sample sample-keys)
  (exact-keys! "contour sample" sample sample-keys)
  (doseq [k sample-keys]
    (when-not (coordinate? (get sample k))
      (problem (str "contour sample " k " must be an integer [x y]")
               {:sample sample :key k})))
  (when-not (= 3 (count (set (map sample sample-keys))))
    (problem "a sample's :point, :inner and :outer must be distinct pixels"
             {:sample sample}))
  sample)

(defn- validate-contour!
  [contour closed?]
  (when-not (and (vector? contour) (<= 2 (count contour)))
    (problem ":contour must be a vector of at least two samples"
             {:contour contour}))
  (doseq [sample contour] (validate-sample! sample))
  (let [points (mapv :point contour)
        pairs (partition 2 1 points)
        pairs (cond-> (vec pairs) closed? (conj [(peek points) (first points)]))]
    (doseq [[a b] pairs]
      (when-not (= 1 (chebyshev-distance a b))
        (problem (str "contour points must be ordered at one-pixel "
                      "Chebyshev spacing")
                 {:from a :to b :closed? closed?}))))
  contour)

(defn- validate-framebuffer!
  "Refuse an unusable framebuffer, by the REGISTRY's rule rather than a
   second copy of it. `measure-contour` is a public entry point that a
   consumer may call outside the registry, so it owes the check; sourcing it
   from `registry/framebuffer-problem` is what keeps the two from drifting
   into two different definitions of a well-formed framebuffer."
  [fb]
  (when-let [bad (registry/framebuffer-problem fb)]
    (problem (str "framebuffer " bad) {:framebuffer-problem bad}))
  fb)

(defn- validate-target!
  [target]
  (require-keys! "border target" target target-keys)
  (exact-keys! "border target" target target-keys)
  (when-not (or (string? (:id target)) (keyword? (:id target)))
    (problem "target :id must be a string or keyword" {:target target}))
  (when-not (boolean? (:closed? target))
    (problem "target :closed? must be boolean" {:target (:id target)}))
  (when-not (and (pos-int? (:min-delta target))
                 (<= (:min-delta target) 765))
    (problem "target :min-delta must be an integer from 1 through 765"
             {:target (:id target) :min-delta (:min-delta target)}))
  (when-not (nat-int? (:max-gap-px target))
    (problem "target :max-gap-px must be a non-negative integer"
             {:target (:id target) :max-gap-px (:max-gap-px target)}))
  (validate-contour! (:contour target) (:closed? target))
  target)

(defn- in-bounds?
  [^long width ^long height [x y]]
  (and (<= 0 (long x)) (< (long x) width)
       (<= 0 (long y)) (< (long y) height)))

(defn- rgba-at
  [^bytes framebuffer ^long width [x y]]
  (let [i (* (+ (* (long y) width) (long x)) 4)]
    [(bit-and (aget framebuffer i) 0xff)
     (bit-and (aget framebuffer (+ i 1)) 0xff)
     (bit-and (aget framebuffer (+ i 2)) 0xff)
     (bit-and (aget framebuffer (+ i 3)) 0xff)]))

(defn- rgb-l1
  [a b]
  (+ (abs (- (long (nth a 0)) (long (nth b 0))))
     (abs (- (long (nth a 1)) (long (nth b 1))))
     (abs (- (long (nth a 2)) (long (nth b 2))))))

(defn- sample-delta
  [^bytes framebuffer ^long width sample]
  (let [point (rgba-at framebuffer width (:point sample))
        inner (rgba-at framebuffer width (:inner sample))
        outer (rgba-at framebuffer width (:outer sample))]
    {:delta (max (rgb-l1 point inner)
                 (rgb-l1 point outer)
                 (rgb-l1 inner outer))
     :opaque? (every? #(= 255 (nth % 3)) [point inner outer])}))

(defn- longest-linear-gap
  "The longest run of consecutive FALSE entries. The `[0 best]` branch is
   the continuity clause itself: a visible sample RESETS the run, which is
   what separates one four-pixel break from four scattered holes. Keep the
   accumulator there and this function counts missing samples instead, and
   the rule silently becomes a coverage rule."
  [visible]
  (second
   (reduce (fn [[run best] v]
             (if v [0 best] (let [run' (inc run)] [run' (max best run')])))
           [0 0]
           visible)))

(defn- longest-gap
  [visible closed?]
  (let [n (count visible)]
    (cond
      (every? false? visible) n
      (not closed?) (longest-linear-gap visible)
      :else
      (let [first-visible (.indexOf ^java.util.List visible true)
            rotated (into []
                          (take n)
                          (drop (inc first-visible) (cycle visible)))]
        (longest-linear-gap rotated)))))

(defn- visible-runs
  [visible closed?]
  (let [n (count visible)]
    (cond
      (zero? n) 0
      (every? false? visible) 0
      (and closed? (every? true? visible)) 1
      closed?
      (count (filter true?
                     (map (fn [a b] (and (not a) b))
                          visible
                          (concat (rest visible) [(first visible)]))))
      :else
      (count (filter true?
                     (map (fn [a b] (and (not a) b))
                          (cons false visible)
                          visible))))))

(defn measure-contour
  "Measure one declared contour against RAW RGBA bytes.

   Required framebuffer map — the `:framebuffer` context value:
     {:bytes byte[] :width int :height int}

   Required target map:
     {:id string-or-keyword
      :contour [{:point [x y] :inner [x y] :outer [x y]} ...]
      :closed? boolean
      :min-delta 1..765
      :max-gap-px non-negative-int}

   Samples must be ordered at one-pixel Chebyshev spacing. Returns an exact
   `:pass`/`:fail` measurement, or `:unjudgeable` with a closed reason. No
   input is defaulted."
  [fb target]
  (validate-framebuffer! fb)
  (validate-target! target)
  (let [^bytes px (:bytes fb)
        width (:width fb)
        height (:height fb)
        coordinates (mapcat (juxt :point :inner :outer) (:contour target))
        clipped (first (remove #(in-bounds? width height %) coordinates))]
    (if clipped
      {:verdict :unjudgeable
       :reason :canvas-clipped
       :target (:id target)
       :min-delta (:min-delta target)
       :max-gap-px (:max-gap-px target)
       :coordinate clipped}
      (let [signals (mapv #(sample-delta px width %) (:contour target))
            nonopaque (first (keep-indexed
                              (fn [i signal] (when-not (:opaque? signal) i))
                              signals))]
        (if (some? nonopaque)
          {:verdict :unjudgeable
           :reason :nonopaque-sample
           :target (:id target)
           :min-delta (:min-delta target)
           :max-gap-px (:max-gap-px target)
           :sample-index nonopaque}
          (let [deltas (mapv :delta signals)
                visible (mapv #(>= (long %) (long (:min-delta target))) deltas)
                n (count visible)
                visible-count (count (filter true? visible))
                gap (longest-gap visible (:closed? target))]
            {:verdict (if (<= gap (:max-gap-px target)) :pass :fail)
             :target (:id target)
             :sample-count n
             :visible-count visible-count
             :coverage-permille (quot (* 1000 visible-count) n)
             :longest-gap-px gap
             :visible-runs (visible-runs visible (:closed? target))
             :weakest-delta (reduce min deltas)
             :strongest-delta (reduce max deltas)
             :min-delta (:min-delta target)
             :max-gap-px (:max-gap-px target)}))))))

(defn rect-contour
  "Build a square-corner, closed contour for inclusive `rect`.

   The contour pixel is on the rect perimeter; :inner is one pixel inward and
   :outer one outward. This helper is intentionally inapplicable to rounded,
   transformed, partial-side, or part-specific borders; those need an explicit
   renderer/consumer declaration. The returned coordinates may extend outside
   the framebuffer, in which case `measure-contour` reports :canvas-clipped."
  [[x1 y1 x2 y2 :as rect]]
  (when-not (and (every? integer? rect)
                 (< x1 x2)
                 (< y1 y2)
                 (<= 3 (inc (- x2 x1)))
                 (<= 3 (inc (- y2 y1))))
    (problem "rect-contour needs an inclusive integer rect at least 3x3"
             {:rect rect}))
  (let [sample (fn [point inner outer]
                 {:point point :inner inner :outer outer})]
    (into []
          (concat
           (for [x (range x1 (inc x2))]
             (sample [x y1] [x (inc y1)] [x (dec y1)]))
           (for [y (range (inc y1) (inc y2))]
             (sample [x2 y] [(dec x2) y] [(inc x2) y]))
           (for [x (range (dec x2) (dec x1) -1)]
             (sample [x y2] [x (dec y2)] [x (inc y2)]))
           (for [y (range (dec y2) y1 -1)]
             (sample [x1 y] [(inc x1) y] [(dec x1) y]))))))

(defn declared-targets
  "The `:borders` entry of the consumer's `:declaration`, refused when it is
   not a vector — which includes being absent.

   ABSENT IS AN OVERSIGHT, EMPTY IS A CLAIM: `check-requires!`'s rule, one
   level down, where the registry cannot apply it. The closed context set
   admits `:declaration` as a whole, so a caller that supplies {:layers …}
   and forgets :borders satisfies the requirement and hands this rule
   nothing to judge — and judging zero contours returns [], which is
   byte-identical to a card whose every border is continuous.

   This is the OPPOSITE of `devcards.layers`' answer to the same gap, and
   the asymmetry is deliberate rather than an inconsistency: an absent
   `:layers` leaves every node in the root layer at z 0, which is the
   STRICTEST reading available, so absence there costs nothing. An absent
   `:borders` judges nothing, so absence there is the whole hazard."
  [declaration]
  (let [targets (:borders declaration)]
    (when-not (vector? targets)
      (problem (str ":declaration must carry :borders, a VECTOR of contour "
                    "targets — `[]` is the claim 'this card declares no "
                    "borders', an absent key is an oversight, and judging "
                    "zero contours passes the card in silence")
               {:borders targets}))
    (when-not (= (count targets) (count (distinct (map :id targets))))
      (problem ":borders must have unique :id values"
               {:ids (mapv :id targets)}))
    targets))

(defn- finding-detail
  [measurement]
  (str "target " (pr-str (:target measurement)) " "
       (case (:verdict measurement)
         :fail
         (str "has a " (:longest-gap-px measurement)
              "px contour-signal gap; allowed "
              (:max-gap-px measurement) "px at RGB L1 floor "
              (:min-delta measurement))
         :unjudgeable
         (str "was not judged: "
              (get reasons (:reason measurement)
                   (name (:reason measurement)))))))

(defn findings
  "The producer body. Context is `required-context` plus the registry-
   supplied `:card-id`.

   A pass emits nothing, a fail emits `:border-contour-discontinuous`, and an
   unjudgeable target emits `:border-contour-untested` with `:act/outcome
   :untested` and its declared reason."
  [{:keys [card-id framebuffer declaration] :as ctx}]
  (require-keys! "border findings context" ctx
                 (conj required-context :card-id))
  (when-not (and (some? card-id) (or (string? card-id) (keyword? card-id)))
    (problem ":card-id must be a string or keyword" {:card-id card-id}))
  (into []
        (keep
         (fn [target]
           (let [measurement (measure-contour framebuffer target)
                 label (str "contour " (pr-str (:target measurement)))]
             (case (:verdict measurement)
               :pass nil
               :fail {:card card-id
                      :invariant :border-contour-discontinuous
                      :node label
                      :target (:target measurement)
                      :measurement measurement
                      :detail (finding-detail measurement)}
               :unjudgeable
               {:card card-id
                :invariant :border-contour-untested
                :node label
                :target (:target measurement)
                :measurement measurement
                :act/outcome :untested
                :act/reason (:reason measurement)
                :detail (finding-detail measurement)})))
         (declared-targets declaration))))

(def producer
  "The registry entry. OPT-IN: it appears in no shipped producer vector, and
   a consumer arms it by appending this map to its own — see the ns
   docstring for why it must not be added to
   `devcards.findings/builtin-producers`.

   `:min-delta` and `:max-gap-px` are per-TARGET declarations rather than
   registry `:thresholds`, and that is a choice with a cost: a consumer
   cannot retune the whole lane with one supplied key. It buys the property
   this rule needs more — a threshold has a DEFAULT, and a default digital
   floor is a number every card would be judged against without anyone
   having claimed it is the right one for that border. Every floor here is
   named by the declaration that asked for the measurement."
  {:id :border
   :fn findings
   :requires required-context
   :outcomes outcomes
   :reasons reasons})
