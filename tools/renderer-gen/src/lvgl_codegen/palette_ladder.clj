(ns lvgl-codegen.palette-ladder
  "Derive a palette by solving each role's LIGHTNESS against the roles it is
   actually measured with — and refuse, loudly and with an attribution, wherever
   that cannot be done.

   IT EMITS A PROPOSAL AND NOTHING ELSE. `propose` returns data; `-main` prints
   it. Nothing here writes `edn/tokens.edn`, `generated/theme_tokens.h`, or any
   other tracked artifact, and nothing here is on the path of any generator. A
   palette change moves every pixel and re-mints every golden and gallery sheet
   (`docs/UI-QUALITY-CONTRACTS.md` §6.8 spells out the order that has to happen
   in), so the derivation lands first, as a thing you can read and disagree with.

   ── WHICH L. SAY IT EVERY TIME ──────────────────────────────────────────────

   Two different quantities are called \"lightness\" in this problem and they are
   NOT interchangeable:

     OKLCH L            perceptual lightness, the SEARCH variable. `:oklch-l`.
     WCAG relative      the linear-light weighted sum contrast ratios are
     luminance Y        computed from. `:wcag-y`.

   Every key this namespace emits carries its space in its NAME —
   `:oklch-l-requested`, `:oklch-l-actual`, `:oklch-c`, `:oklch-h`, `:wcag-y`,
   `:wcag-ratio`. A bare `:l` / `:lightness` / `:luminance` / `:contrast` is
   BANNED from the output, and `scan-ambiguous-keys` below enforces that
   mechanically over the emitted proposal rather than leaving it to review.
   The two are monotonically related at a FIXED hue and chroma, which is what
   makes an OKLCH-L search work at all — but they are not the same number and a
   threshold that does not name its space is a defect, not a shorthand.

   ── THE PREMISE IS MOSTLY TRUE, AND THE REST IS THE DESIGN ──────────────────

   \"Contrast is a function of lightness, so hue is free\" holds for most
   (role, mode) cells. It does not hold for all of them, and the exceptions are
   not an error path:

     * some cells are reachable only by SURRENDERING CHROMA — the requested
       OKLCH chroma is outside sRGB at the lightness the floor demands, so
       gamut mapping reduces it. This is the COMMON PATH, not an exception
       handler: `realize` performs the reduction on every call and reports
       `:oklch-c-surrendered` whether or not it was needed;
     * some cells are impossible at ANY hue or chroma. Those get one of three
       DISTINCT statuses, because they need three different fixes.

   ── THE AXIS A FLAT TABLE LACKS ─────────────────────────────────────────────

   A flat `role -> (target, reference, hue, chroma)` table cannot record that a
   role's failure is caused by a DIFFERENT role's value, and so cannot detect
   it — it searches, finds nothing, and emits a silent best-effort miss.

   `accent-text` on `accent-bg` is that case in this repo, and it is provable
   rather than empirical. `max-hostable-ratio` is a closed form: the best
   contrast ANY sRGB colour can reach against a background of relative
   luminance Y is `max((1+0.05)/(Y+0.05), (Y+0.05)/0.05)`, attained by pure
   white or pure black, both of which are emittable — so the bound is TIGHT. It
   has a minimum near Y = 0.179, and for a 6:1 floor it carves a DEAD BAND of
   background luminances that no foreground whatsoever can clear. A fill that
   lands in it dooms its own label. `docs/UI-QUALITY-CONTRACTS.md` §6.8 reached
   the same conclusion from the other end (\"white already MAXIMISES luminance
   against those fills, so no choice of text tone reaches 6:1 — the FILL's
   lightness is what has to move\"); this namespace makes it a computed
   pre-check that runs BEFORE any search on the foreground.

   So roles form a DEPENDENCY GRAPH, solved in topological order (cycles throw),
   and the failure vocabulary is split by WHERE THE FIX GOES:

     :reference-infeasible  the reference's luminance admits no foreground at
                            this floor. Fix the REFERENCE. Nothing about this
                            role's hue, chroma or lightness can help.
     :reference-conflict    every reference is individually satisfiable but no
                            single value satisfies them together. Fix the ROLE
                            VOCABULARY — split it, or move one reference. The
                            result names a maximal satisfiable subset so the
                            split is not guesswork.
     :unreachable-in-gamut  the references are all hostable and mutually
                            compatible, but not at THIS role's declared hue
                            while retaining its declared minimum chroma. Fix
                            this role's HUE or CHROMA budget.
     :blocked-upstream      a reference did not resolve, so this cell was never
                            judged. An unjudged cell is a finding, never a skip.

   And the graph is checked STATICALLY as well: if role A is measured against
   role B at floor F and B is itself SOLVED, then B must declare a
   `:hosts-foreground` obligation at floor >= F. Otherwise B's own solve is free
   to pick a value that dooms A, and the failure surfaces one role away from its
   cause. `validate-spec` reports that as a spec defect
   (`:missing-host-obligation`) before anything is solved. A PINNED role cannot
   carry an obligation — its value is a given — so for pinned references the
   same condition is checked at solve time and lands as
   `:reference-infeasible`.

   ── ONE FUNCTION, CALLED BY SEARCH AND BY REPORT ────────────────────────────

   A solver that accepts a value under one computation and reports it under
   another is broken even when it agrees, because the agreement is luck. The
   specific way that goes wrong here is RESOLUTION: an OKLCH lightness search
   runs on a continuum, and the palette this repo can actually emit is 8-bit
   `#RRGGBB`. Quantising afterwards can drop a ratio back under its floor.
   (`quantisation-hazards` in the test namespace exhibits emittable instances
   in both directions; the gap is not theoretical.)

   The fix is structural, not a tighter tolerance: `realize` QUANTISES FIRST and
   every number downstream of it — `:wcag-y`, every ratio, `:oklch-l-actual` —
   is computed from the quantised 8-bit value. `candidates` then enumerates the
   DISTINCT REALIZABLE COLOURS at a hue and chroma, so the search space IS the
   emittable space and there is no second resolution for a report to disagree
   with. `evaluate-constraint` is the single constraint evaluator; the search
   filters with it and the report prints from it. `solve-cell` re-verifies its
   own chosen candidate through it and THROWS on disagreement — a tautology
   today, which is the point: it is the tripwire for the day someone adds a
   second path.

   The sRGB transfer function is deliberately asymmetric here: decode uses
   WCAG 2.x's 0.03928 threshold (matching `tools/devcards/dev/palette-audit.py`
   and `dev/proven_pairs.clj` digit for digit) while encode uses the sRGB
   spec's exact 0.0031308. Those are not inverses, so an OKLCH round trip does
   not close. Quantise-first makes that harmless: nothing is ever measured on
   the unquantised side.

   ── WHERE THE REFERENCE EDGES COME FROM ─────────────────────────────────────

   Not from a foreground x background cross product. `docs/UI-QUALITY-CONTRACTS.md`
   §6.9 shows that construction fails in both directions at once — it scores
   pairs nothing authors, and silently omits pairs that render. The text edges
   here are the co-declared pairs `docs/PROVEN-PAIRS.md` derives from
   `renderer/src/theme.c`, `edn/components.edn`, `renderer/edn/screens/*.edn`
   and `lvgl-codegen.fixtures`. Every constraint carries `:provenance` naming
   which tier it came from, because the non-text edges are weaker: they are read
   off comments in `edn/tokens.edn`, and a finding resting on one deserves to be
   read as such. This is protogen's own spec; a consumer supplies its own and
   `propose` takes it as an argument."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; ═══════════════════════════════════════════════════════════════════════════
;; sRGB <-> linear light <-> WCAG relative luminance
;; ═══════════════════════════════════════════════════════════════════════════

(defn- clamp01
  "Floor/ceiling a linear-light value into [0.0, 1.0] so a value that drifted
   fractionally outside range from floating-point error never reaches an sRGB
   encode or a luminance sum."
  ^double [^double x]
  (cond (< x 0.0) 0.0 (> x 1.0) 1.0 :else x))

(defn srgb8->linear
  "One 8-bit sRGB component -> linear light. WCAG 2.x's 0.03928 threshold, NOT
   the sRGB spec's 0.04045 — the same constant `dev/palette-audit.py` and
   `dev/proven_pairs.clj` use, so a ratio computed here is comparable to one
   computed there digit for digit."
  ^double [^long c8]
  (let [c (/ (double c8) 255.0)]
    (if (<= c 0.03928)
      (/ c 12.92)
      (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn linear->srgb8
  "Linear light -> the nearest 8-bit sRGB component. The sRGB spec's exact
   0.0031308 encode threshold, which is NOT the inverse of the decode constant
   above; see the ns docstring on why that asymmetry is harmless here."
  ^long [^double lin]
  (let [v (clamp01 lin)
        s (if (<= v 0.0031308)
            (* v 12.92)
            (- (* 1.055 (Math/pow v (/ 1.0 2.4))) 0.055))]
    (Math/round (* 255.0 s))))

(defn wcag-y
  "WCAG 2.x relative luminance of an 8-bit [r g b]. This is the ONLY quantity
   any ratio in this namespace is computed from; it is not OKLCH L."
  ^double [[r g b]]
  (+ (* 0.2126 (srgb8->linear r))
     (* 0.7152 (srgb8->linear g))
     (* 0.0722 (srgb8->linear b))))

(defn wcag-ratio
  "WCAG 2.x contrast ratio between two relative luminances."
  ^double [^double y-a ^double y-b]
  (let [hi (max y-a y-b)
        lo (min y-a y-b)]
    (/ (+ hi 0.05) (+ lo 0.05))))

(defn max-hostable-ratio
  "The best WCAG contrast ratio ANY sRGB colour can reach against a background
   of relative luminance `y`. Closed form, and TIGHT: the extremes are pure
   black and pure white, both emittable. Below the floor this returns, the
   background is a dead end for every possible foreground — which is what makes
   `:reference-infeasible` provable rather than a failed search."
  ^double [^double y]
  (max (wcag-ratio 1.0 y) (wcag-ratio 0.0 y)))

(defn hostable-band
  "The relative-luminance band a background must avoid to host SOME foreground
   at `floor-ratio`: everything strictly between the two bounds is dead. Emitted
   with `:reference-infeasible` so the repair is a number, not an instruction to
   go and search."
  [^double floor-ratio]
  {:wcag-y-max-for-light-foreground (- (/ 1.05 floor-ratio) 0.05)
   :wcag-y-min-for-dark-foreground (- (* 0.05 floor-ratio) 0.05)})

;; ═══════════════════════════════════════════════════════════════════════════
;; hex <-> rgb8
;; ═══════════════════════════════════════════════════════════════════════════

(def ^:private hex-pattern #"^#[0-9A-Fa-f]{6}$")

(defn hex->rgb8
  "\"#12121F\" -> [18 18 31]. Throws on anything that is not a canonical
   six-digit hex — a palette derivation must never guess at its input."
  [hex]
  (when-not (and (string? hex) (re-matches hex-pattern hex))
    (throw (ex-info "not a canonical #RRGGBB hex" {:value hex})))
  (mapv #(Long/parseLong (subs hex % (+ % 2)) 16) [1 3 5]))
(m/=> hex->rgb8 [:=> [:cat [:re hex-pattern]] [:vector :int]])

(defn rgb8->hex
  "[18 18 31] -> \"#12121F\", upper case, which is the canonical form every
   token table and every dump comparison in this repo uses."
  [rgb]
  (str "#" (str/upper-case (str/join (map #(format "%02x" (long %)) rgb)))))
(m/=> rgb8->hex [:=> [:cat [:sequential :int]] [:re hex-pattern]])

;; ═══════════════════════════════════════════════════════════════════════════
;; OKLab / OKLCH  (Bjorn Ottosson's matrices, matching dev/palette-audit.py)
;; ═══════════════════════════════════════════════════════════════════════════

(defn- linear-rgb->oklab
  "Linear sRGB -> OKLab, Bjorn Ottosson's published LMS matrices and cube-root
   nonlinearity — the forward half of the OKLCH round trip every hue, chroma
   and lightness in this namespace is expressed through."
  [[^double r ^double g ^double b]]
  (let [l (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b))
        m (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b))
        s (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b))
        l' (Math/cbrt l)
        m' (Math/cbrt m)
        s' (Math/cbrt s)]
    [(+ (* 0.2104542553 l') (* 0.7936177850 m') (* -0.0040720468 s'))
     (+ (* 1.9779984951 l') (* -2.4285922050 m') (* 0.4505937099 s'))
     (+ (* 0.0259040371 l') (* 0.7827717662 m') (* -0.8086757660 s'))]))

(defn- oklab->linear-rgb
  "OKLab -> linear sRGB, the inverse of `linear-rgb->oklab`. Components may land
   outside [0,1]; `in-gamut?` / `gamut-map-chroma` are what test for that, not
   this function."
  [[^double lightness ^double a ^double b]]
  (let [l' (+ lightness (* 0.3963377774 a) (* 0.2158037573 b))
        m' (- lightness (* 0.1055613458 a) (* 0.0638541728 b))
        s' (- lightness (* 0.0894841775 a) (* 1.2914855480 b))
        l (* l' l' l')
        m (* m' m' m')
        s (* s' s' s')]
    [(+ (* 4.0767416621 l) (* -3.3077115913 m) (* 0.2309699292 s))
     (+ (* -1.2684380046 l) (* 2.6097574011 m) (* -0.3413193965 s))
     (+ (* -0.0041960863 l) (* -0.7034186147 m) (* 1.7076147010 s))]))

(defn rgb8->oklch
  "8-bit [r g b] -> {:oklch-l :oklch-c :oklch-h}. Hue in degrees [0,360)."
  [rgb]
  (let [[lightness a b] (linear-rgb->oklab (mapv #(srgb8->linear (long %)) rgb))]
    {:oklch-l lightness
     :oklch-c (Math/hypot a b)
     :oklch-h (mod (Math/toDegrees (Math/atan2 b a)) 360.0)}))
(m/=> rgb8->oklch [:=> [:cat [:sequential :int]] [:map-of :keyword :double]])

(defn hex->oklch
  "Canonical hex -> {:oklch-l :oklch-c :oklch-h}. How the shipped tokens hand
   this derivation their hue and chroma, so no coordinate is ever a number
   copied into source."
  [hex]
  (rgb8->oklch (hex->rgb8 hex)))
(m/=> hex->oklch [:=> [:cat [:re hex-pattern]] [:map-of :keyword :double]])

(defn oklch->linear-rgb
  "The UNQUANTISED linear-light triple for an OKLCH coordinate. Components may
   fall outside [0,1] — that is what `gamut-map-chroma` tests for.

   Nothing in this namespace MEASURES this value; `realize` immediately
   quantises it. It is public only so a canary can exhibit the number
   quantise-first refuses to trust (see `linear-rgb->wcag-y`)."
  [^double lightness ^double chroma ^double hue-deg]
  (let [rad (Math/toRadians hue-deg)]
    (oklab->linear-rgb [lightness (* chroma (Math/cos rad)) (* chroma (Math/sin rad))])))
(m/=> oklch->linear-rgb [:=> [:cat :double :double :double] [:sequential :double]])

(defn linear-rgb->wcag-y
  "Relative luminance of an UNQUANTISED linear-light triple — the quantity a
   solver that searched the continuum and quantised afterwards would have
   accepted on. NO SOLVER PATH CALLS THIS. It exists so a canary can show that
   it disagrees with `wcag-y` of the emitted hex, which is the whole reason
   `realize` quantises first."
  ^double [[^double r ^double g ^double b]]
  (+ (* 0.2126 (clamp01 r)) (* 0.7152 (clamp01 g)) (* 0.0722 (clamp01 b))))
(m/=> linear-rgb->wcag-y [:=> [:cat [:sequential :double]] :double])

(def ^:private gamut-epsilon
  "Tolerance on the in-gamut test, in linear light. Small enough that a value
   admitted here rounds into range rather than being clamped meaningfully."
  1.0e-9)

(defn- in-gamut?
  "True when a linear-light RGB triple is representable in sRGB, to within
   `gamut-epsilon` — the predicate `gamut-map-chroma`'s bisection narrows
   against."
  [[^double r ^double g ^double b]]
  (let [ok? (fn [^double v] (and (>= v (- gamut-epsilon)) (<= v (+ 1.0 gamut-epsilon))))]
    (and (ok? r) (ok? g) (ok? b))))

(def ^:private chroma-bisection-steps
  "Iterations of the in-gamut chroma bisection. 40 halvings take any chroma in
   this problem below 1e-11, far under the 8-bit quantisation `realize` applies
   immediately afterwards — so the bisection's own resolution can never be the
   resolution a result is reported at."
  40)

(defn- gamut-map-chroma
  "Largest chroma <= `chroma` that is in sRGB gamut at this OKLCH lightness and
   hue. Chroma reduction, NOT clipping: clipping moves lightness and hue as
   well, and this derivation's whole claim is that it controls lightness."
  ^double [^double lightness ^double chroma ^double hue-deg]
  (if (in-gamut? (oklch->linear-rgb lightness chroma hue-deg))
    chroma
    (loop [lo 0.0 hi chroma n chroma-bisection-steps]
      (if (zero? n)
        lo
        (let [mid (* 0.5 (+ lo hi))]
          (if (in-gamut? (oklch->linear-rgb lightness mid hue-deg))
            (recur mid hi (dec n))
            (recur lo mid (dec n))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; THE ONE FUNCTION
;; ═══════════════════════════════════════════════════════════════════════════

(defn realize
  "(OKLCH lightness, chroma, hue) -> the 8-bit sRGB colour this repo can
   actually emit for it, plus every quantity derived FROM that quantised value.

   THIS IS THE ONE FUNCTION. The search calls it; the report calls it;
   `solve-cell` re-verifies through it. Nothing measures an unquantised colour,
   which is what makes a search resolution and a report resolution impossible to
   disagree.

   Gamut mapping is unconditional and reported either way: `:oklch-c-requested`
   is what was asked for, `:oklch-c-used` is what survived the reduction, and
   `:oklch-c-surrendered` is the difference — 0.0 on the common path, and never
   a silent substitution.

   `:oklch-l-actual` / `:oklch-c-actual` / `:oklch-h-actual` are re-derived FROM
   the emitted hex and are the honest coordinates of the colour; the requested
   ones are the search's intent. They differ by the quantisation, and both are
   reported because collapsing them is how a derivation starts lying about what
   it produced."
  [{:keys [oklch-l oklch-c oklch-h]}]
  (let [lightness (double oklch-l)
        hue (double oklch-h)
        requested (double oklch-c)
        used (gamut-map-chroma lightness requested hue)
        lin (oklch->linear-rgb lightness used hue)
        rgb (mapv linear->srgb8 lin)
        hex (rgb8->hex rgb)
        actual (rgb8->oklch rgb)]
    {:hex hex
     :rgb8 rgb
     :wcag-y (wcag-y rgb)
     :oklch-l-requested lightness
     :oklch-h-requested hue
     :oklch-c-requested requested
     :oklch-c-used used
     :oklch-c-surrendered (- requested used)
     :gamut-mapped? (> (- requested used) 1.0e-12)
     :oklch-l-actual (:oklch-l actual)
     :oklch-c-actual (:oklch-c actual)
     :oklch-h-actual (:oklch-h actual)}))
(m/=> realize [:=> [:cat [:map-of :keyword :any]] [:map-of :keyword :any]])

(defn realize-hex
  "A pinned hex lifted into the same shape `realize` produces, so a pinned role
   and a solved role are measured by identical code. A pinned value surrenders
   no chroma by construction — it IS its own request."
  [hex]
  (let [rgb (hex->rgb8 hex)
        actual (rgb8->oklch rgb)]
    {:hex (rgb8->hex rgb)
     :rgb8 rgb
     :wcag-y (wcag-y rgb)
     :oklch-l-requested (:oklch-l actual)
     :oklch-h-requested (:oklch-h actual)
     :oklch-c-requested (:oklch-c actual)
     :oklch-c-used (:oklch-c actual)
     :oklch-c-surrendered 0.0
     :gamut-mapped? false
     :oklch-l-actual (:oklch-l actual)
     :oklch-c-actual (:oklch-c actual)
     :oklch-h-actual (:oklch-h actual)}))
(m/=> realize-hex [:=> [:cat [:re hex-pattern]] [:map-of :keyword :any]])

(def default-lightness-seed
  "Uniform OKLCH-lightness samples `candidates` STARTS from before refining
   adaptively between neighbours that realized to different colours.

   A UNIFORM GRID ALONE IS NOT ENOUGH, and this was measured rather than
   assumed: at 2048 uniform samples the amber hue's candidate set GREW when the
   grid was doubled, so the grid was silently acting as a search resolution
   after all — the exact defect the design claims not to have. Adaptive
   refinement removes the dependence, and `test-candidate-set-is-resolution-independent`
   is what holds it removed."
  256)

(def ^:private refinement-epsilon
  "Stop bisecting between two differing neighbours once their OKLCH-lightness
   gap is this small. An emittable colour occupying a narrower lightness
   interval than this could be missed.

   Sized against the tightest real interval rather than picked: adjacent 8-bit
   greys are furthest apart in OKLCH L near black (the first step off #000000
   spans about 0.067, since OKLab L is a cube root of luminance) and closest
   near white, where the last step spans about 0.003. This is three orders of
   magnitude under that."
  1.0e-6)

(defn candidates
  "Every DISTINCT emittable colour at this hue and requested chroma, ascending
   in OKLCH lightness.

   THE SEARCH SPACE IS THE EMITTABLE SPACE. The solver never holds a candidate
   it cannot name, so there is no later quantisation to knock a solution back
   under its floor — which is the whole of the one-function discipline this
   namespace is built around.

   Uniform seed, then bisection between any two neighbours that realized
   DIFFERENTLY, which is what makes the seed size stop mattering. The map from
   lightness to emitted colour is piecewise constant and monotone along a hue
   line, so a boundary always lies between a differing pair and the recursion
   costs O(colours x depth) rather than exploding."
  ([spec-cell] (candidates spec-cell default-lightness-seed))
  ([{:keys [oklch-c oklch-h]} seed]
   (let [rz (fn [l] (realize {:oklch-l l :oklch-c oklch-c :oklch-h oklch-h}))
         n (long seed)
         seeds (mapv (fn [i] (let [l (/ (double i) (double n))] [l (rz l)])) (range (inc n)))
         refine (fn refine [found [lo-l lo-r] [hi-l hi-r]]
                  (if (or (= (:hex lo-r) (:hex hi-r))
                          (< (- (double hi-l) (double lo-l)) refinement-epsilon))
                    found
                    (let [mid-l (* 0.5 (+ (double lo-l) (double hi-l)))
                          mid (rz mid-l)]
                      (-> (assoc found (:hex mid) mid)
                          (refine [lo-l lo-r] [mid-l mid])
                          (refine [mid-l mid] [hi-l hi-r])))))
         found (reduce (fn [acc [a b]] (refine acc a b))
                       (into {} (map (fn [[_ r]] [(:hex r) r])) seeds)
                       (partition 2 1 seeds))]
     (vec (sort-by :oklch-l-actual (vals found))))))
(m/=> candidates [:function
                  [:=> [:cat [:map-of :keyword :any]] [:sequential [:map-of :keyword :any]]]
                  [:=> [:cat [:map-of :keyword :any] :int] [:sequential [:map-of :keyword :any]]]])

;; ═══════════════════════════════════════════════════════════════════════════
;; Floors — each names WHICH quantity it bounds and who says so
;; ═══════════════════════════════════════════════════════════════════════════

(def floors
  "The declared floors. `:quantity` is on every one of them because the whole
   class of bug this namespace exists to avoid starts with a threshold that did
   not say what it bounded.

   MIL-STD-1472H governs where it states a threshold; WCAG 2.2 fills gaps only
   where 1472H is silent, and where both state one for the same quantity the
   stricter binds (`docs/UI-QUALITY-CONTRACTS.md` §0, which carries the
   precedence rule; §4 is per-role thresholds and states no precedence). So text is 6:1, not 4.5:1
   — `:wcag-aa-text` is carried ONLY to quantify what a WCAG-built gate would
   have blessed, and `solve-cell` will refuse it as a floor."
  {:text-shall {:ratio 6.0
                :quantity :wcag-contrast-ratio
                :source "MIL-STD-1472H 5.2.2.7 (shall)"
                :usable-as-floor? true}
   :text-should {:ratio 10.0
                 :quantity :wcag-contrast-ratio
                 :source "MIL-STD-1472H 5.2.2.7 (should)"
                 :usable-as-floor? true}
   :non-text {:ratio 3.0
              :quantity :wcag-contrast-ratio
              :source "WCAG 2.2 1.4.11 - gap-fill; 1472H states nothing for generic component boundaries"
              :usable-as-floor? true}
   :wcag-aa-text {:ratio 4.5
                  :quantity :wcag-contrast-ratio
                  :source "WCAG 2.2 1.4.3 AA - REJECTED as a floor here, kept to size the gap"
                  :usable-as-floor? false}})

(defn floor-ratio
  "Resolve a floor key to its ratio, refusing an unknown key and refusing one
   this repo does not accept as a floor. A typo must never quietly relax the
   gate it names."
  ^double [floor-key]
  (let [f (get floors floor-key)]
    (when-not f
      (throw (ex-info "unknown floor" {:floor floor-key :known (set (keys floors))})))
    (when-not (:usable-as-floor? f)
      (throw (ex-info "floor is declared unusable as a floor"
                      {:floor floor-key :source (:source f)})))
    (double (:ratio f))))
(m/=> floor-ratio [:=> [:cat :keyword] :double])

;; ═══════════════════════════════════════════════════════════════════════════
;; Constraints — ONE evaluator, used by the search and by the report
;; ═══════════════════════════════════════════════════════════════════════════

(def constraint-schema
  "Three kinds, and the split is by WHERE A FAILURE'S FIX GOES.

   `:contrast-min`     subject vs the `:against` role, >= floor. A dependency
                       edge.
   `:hosts-foreground` the subject must ADMIT some sRGB foreground at >= floor.
                       No edge - it is the DOWNSTREAM requirement pushed
                       upstream, which is the whole point of the axis. It is
                       NECESSARY, not sufficient: satisfying it does not
                       guarantee the dependent solves at its own declared hue
                       and chroma, and conflating the two is exactly the merge
                       of `:reference-infeasible` with `:unreachable-in-gamut`
                       this design refuses.
   `:dimmer-than`      THE LADDER RUNG. The subject's contrast against
                       `:against` must be strictly LESS than the `:than` role's
                       contrast against the same reference. Two dependency
                       edges, and no invented number: the bound is another
                       role's achieved ratio, so a rung cannot be declared
                       without declaring what it sits under.

                       IT ORDERS; IT DOES NOT SEPARATE. `:min-oklch-l-step`
                       (default 0.0) is where a perceptual step size would go,
                       and this repo does not specify one - readability numbers
                       are panel-and-operator properties governed upstream
                       (docs/UI-QUALITY-CONTRACTS.md). So a satisfied
                       `:dimmer-than` proves the rungs are in ORDER and proves
                       nothing about whether an operator can tell them apart."
  [:multi {:dispatch :kind}
   [:contrast-min [:map {:closed true}
                   [:kind [:= :contrast-min]]
                   [:against :keyword]
                   [:floor :keyword]
                   [:modes {:optional true} [:set :keyword]]
                   [:provenance :keyword]]]
   [:hosts-foreground [:map {:closed true}
                       [:kind [:= :hosts-foreground]]
                       [:floor :keyword]
                       [:for-roles [:set :keyword]]
                       [:provenance :keyword]]]
   [:dimmer-than [:map {:closed true}
                  [:kind [:= :dimmer-than]]
                  [:against :keyword]
                  [:than :keyword]
                  [:min-oklch-l-step {:optional true} [:double {:min 0.0 :max 1.0}]]
                  [:modes {:optional true} [:set :keyword]]
                  [:provenance :keyword]]]])

(defn evaluate-constraint
  "THE constraint evaluator. `subject` and every value in `resolved` are
   `realize` outputs, so every ratio here is between two QUANTISED colours.

   The search filters candidates with this; the report prints from this;
   `solve-cell` re-verifies its own answer with this. There is no second
   arithmetic anywhere for them to disagree about.

   `:bound-kind` says which DIRECTION `:bound-wcag-ratio` binds in - a bound
   that does not say whether it is a floor or a ceiling is the same class of
   defect as a lightness that does not say which space it is in."
  [constraint subject resolved mode]
  (case (:kind constraint)
    :contrast-min
    (let [bound (floor-ratio (:floor constraint))
          reference (get resolved (:against constraint))
          ratio (wcag-ratio (:wcag-y subject) (:wcag-y reference))]
      {:constraint constraint
       :mode mode
       :quantity :wcag-contrast-ratio
       :bound-kind :min
       :bound-wcag-ratio bound
       :wcag-ratio ratio
       :reference-hex (:hex reference)
       :reference-wcag-y (:wcag-y reference)
       :satisfied? (>= ratio bound)})

    :hosts-foreground
    (let [bound (floor-ratio (:floor constraint))
          ratio (max-hostable-ratio (:wcag-y subject))]
      {:constraint constraint
       :mode mode
       :quantity :wcag-contrast-ratio
       :bound-kind :min
       :bound-wcag-ratio bound
       :wcag-ratio ratio
       :reference-hex nil
       :reference-wcag-y (:wcag-y subject)
       :satisfied? (>= ratio bound)})

    :dimmer-than
    (let [reference (get resolved (:against constraint))
          brighter (get resolved (:than constraint))
          step (double (get constraint :min-oklch-l-step 0.0))
          ratio (wcag-ratio (:wcag-y subject) (:wcag-y reference))
          bound (wcag-ratio (:wcag-y brighter) (:wcag-y reference))
          gap (Math/abs (- (double (:oklch-l-actual subject))
                           (double (:oklch-l-actual brighter))))]
      {:constraint constraint
       :mode mode
       :quantity :wcag-contrast-ratio
       :bound-kind :max
       :bound-wcag-ratio bound
       :wcag-ratio ratio
       :oklch-l-step gap
       :min-oklch-l-step step
       :reference-hex (:hex reference)
       :reference-wcag-y (:wcag-y reference)
       :satisfied? (and (< ratio bound) (>= gap step))})))
(m/=> evaluate-constraint
      [:=> [:cat [:map-of :keyword :any] [:map-of :keyword :any]
            [:map-of :keyword :any] :keyword]
       [:map-of :keyword :any]])

(defn- constraint-applies?
  "A `:contrast-min` may be declared for one mode only (a pair that renders in
   dark and not in light). Absence of `:modes` means every mode."
  [constraint mode]
  (let [modes (:modes constraint)]
    (or (nil? modes) (contains? modes mode))))

;; ═══════════════════════════════════════════════════════════════════════════
;; The role spec
;; ═══════════════════════════════════════════════════════════════════════════

(def preferences
  "How a cell chooses among candidates that ALL satisfy every constraint.

   `:least-separation` implements the RULE `edn/tokens.edn` stated for the
   primitives `:fg-disabled` and `:olive-600` — the value closest to the floor,
   keeping the most dimming signal. It is stated as \"the smallest achieved
   ratio\" rather than \"the dimmest lightness\" because those coincide only for
   a single reference, and both of those tokens had exactly one. Both primitives
   were DELETED by `217ecfda`; the rule they were named for is what survives, and
   `:fg-dim` / `:olive-500` state it in the same words today.

   IT DOES NOT REPRODUCE EITHER RETIRED VALUE, and saying so is worth more than
   the claim it replaces. Applying the stated rule on the stated line lands ONE
   8-bit blue level away in both cases, and the reason is sharper than a
   rounding disagreement: neither retired tone is EMITTABLE on the (hue, chroma)
   line its own comment named. `#9A9BB6` is absent from the 8-bit colours
   reachable at `:fg-dim`'s hue and chroma (the nearest on-line neighbours are
   `#9A9BB5` and `#9B9BB6`), and `#3D3C2C` is absent from `:olive-500`'s line
   (nearest are `#3D3C2B` and `#3D3D2C`). Rounding the coordinates to the
   figures the comment quotes (h 285.2, C 0.039) changes nothing.

   BOTH TONES ARE RETIRED, AND THIS PARAGRAPH SAID \"BOTH SHIPPED TONES\" LONG
   AFTER THEY STOPPED SHIPPING. `217ecfda` dropped the foreground ladder to three
   rungs: `disabled-fg` is `#A7A8C3`/`#3D3C2B` now, and the primitives these two
   were named for — `:fg-disabled` and `:olive-600` — were deleted by that same
   commit. The paragraph is kept rather than dropped because its SUBJECT is the
   retired derivation and the disagreement it records is still unexplained; only
   the tense was wrong. (This is the third instance of one defect in this file:
   `protogen-spec`'s `:shipped` mirror and the test namespace's
   `proven-pairs-rows` were the other two, and the spec's is now gate-held.)

   Both tones DID clear their 6:1 floor on the fills named above — 6.0436:1 and
   6.0318:1, which this namespace agrees with — so this was never a claim that
   the palette is broken. It is a claim that two derivations of \"the same\"
   quantity disagree at the resolution the palette is actually written in, which
   is the failure this namespace's quantise-first design exists to make
   impossible. Whatever produced those values is not in this tree, so WHICH of
   the two paths stepped off the line cannot be settled here.
   `test-the-retired-disabled-tones-are-not-on-their-own-stated-line` pins the
   measurement; if it ever goes green the finding has been retired and the
   sentence above should go with it."
  #{:least-separation :most-separation :closest-to-shipped})

(def role-schema
  [:map {:closed true}
   [:role :keyword]
   [:kind [:enum :pinned :solved]]
   ;; Pinned roles carry their value; solved roles carry the value their hue,
   ;; chroma budget and :closest-to-shipped preference are read from. Both are
   ;; per mode, or a single hex for a mode-invariant role.
   [:shipped [:or :string [:map-of :keyword :string]]]
   [:mode-invariant? {:optional true} :boolean]
   [:prefer {:optional true} (into [:enum] preferences)]
   ;; Least fraction of the shipped chroma this role still carries its identity
   ;; at. Declared as a FRACTION so no absolute chroma is ever copied into
   ;; source; 0.0 means the hue may be surrendered entirely.
   [:chroma-retain-min {:optional true} [:double {:min 0.0 :max 1.0}]]
   [:constraints {:optional true} [:sequential constraint-schema]]
   [:note {:optional true} :string]])

(def spec-schema
  [:map {:closed true}
   [:spec-id :keyword]
   [:modes [:sequential :keyword]]
   [:roles [:sequential role-schema]]])

(defn shipped-hex
  "The shipped value of a role in a mode. A mode-invariant role declares one
   string; a mode-variant role declares a per-mode map."
  [role mode]
  (let [s (:shipped role)]
    (if (string? s)
      s
      (or (get s mode)
          (throw (ex-info "role has no shipped value for this mode"
                          {:role (:role role) :mode mode}))))))
(m/=> shipped-hex [:=> [:cat [:map-of :keyword :any] :keyword] [:re hex-pattern]])

;; ═══════════════════════════════════════════════════════════════════════════
;; The dependency graph
;; ═══════════════════════════════════════════════════════════════════════════

(defn- constraint-references
  "Every role a constraint reads. `:dimmer-than` reads TWO - the reference the
   pair is measured on, and the rung above it - and a dependency walk that
   counted only `:against` would order the ladder wrong while looking correct."
  [c]
  (case (:kind c)
    :contrast-min #{(:against c)}
    :dimmer-than #{(:against c) (:than c)}
    :hosts-foreground #{}))

(defn- role-dependencies
  "Every role key `role`'s own constraints read, feeding `topological-order`'s
   dependency graph — a role with no constraints depends on nothing."
  [role]
  (into #{} (mapcat constraint-references) (:constraints role)))

(defn topological-order
  "Role keys in dependency order. Throws naming the cycle — a palette whose
   roles depend on each other circularly has no derivation order at all, and
   guessing one is how a solver blesses whichever value it happened to visit
   first."
  [roles]
  (let [by-key (into {} (map (juxt :role identity)) roles)
        deps (into {} (map (fn [r] [(:role r) (role-dependencies r)])) roles)]
    (doseq [[k ds] deps
            d ds]
      (when-not (contains? by-key d)
        (throw (ex-info "constraint references an undeclared role"
                        {:role k :missing d}))))
    (loop [ordered [] placed #{} remaining (vec (map :role roles))]
      (if (empty? remaining)
        ordered
        ;; REVERT-TO-BREAK: replace the throw with `ordered` and the cycle test
        ;; goes red on a missing exception rather than hanging. Written as an
        ;; `if` for exactly that reason: deleting a `when`-guarded throw here
        ;; would loop forever, and a canary whose mutation HANGS teaches nothing.
        (let [ready (filterv #(every? placed (get deps %)) remaining)]
          (if (empty? ready)
            (throw (ex-info "cyclic role dependency - no derivation order exists"
                            {:unresolved (vec remaining)
                             :edges (select-keys deps remaining)}))
            (recur (into ordered ready)
                   (into placed ready)
                   (vec (remove (set ready) remaining)))))))))
(m/=> topological-order [:=> [:cat [:sequential [:map-of :keyword :any]]] [:sequential :keyword]])

(defn validate-spec
  "Static findings about the SPEC, before anything is solved.

   `:missing-host-obligation` is the one that earns the dependency axis its
   keep. If role A is measured against SOLVED role B at floor F and B declares
   no `:hosts-foreground` at floor >= F, then B's own solve is free to land in
   the dead band and doom A — and the failure would surface on A, one role away
   from its cause. A PINNED reference cannot carry an obligation, so that case
   is deliberately not reported here; it is checked at solve time and lands as
   `:reference-infeasible` on A, attributed to B."
  [spec]
  (when-not (m/validate spec-schema spec)
    (throw (ex-info "palette spec failed its schema"
                    {:explain (m/explain spec-schema spec)})))
  (let [roles (:roles spec)
        by-key (into {} (map (juxt :role identity)) roles)
        host-floor (fn [role]
                     (->> (:constraints role)
                          (filter #(= :hosts-foreground (:kind %)))
                          (map #(floor-ratio (:floor %)))
                          (reduce max 0.0)))]
    (vec
     (concat
      (for [r roles
            c (:constraints r)
            :when (= :contrast-min (:kind c))
            :let [referenced-role (get by-key (:against c))
                  needed (floor-ratio (:floor c))]
            :when (and (= :solved (:kind referenced-role))
                       (< (host-floor referenced-role) needed))]
        {:finding :missing-host-obligation
         :role (:role r)
         :reference (:against c)
         :needed-floor-ratio needed
         :declared-host-floor-ratio (host-floor referenced-role)
         :detail (str (name (:role r)) " is measured against SOLVED role "
                      (name (:against c)) " at " needed
                      ":1, but " (name (:against c))
                      " declares no :hosts-foreground obligation at that floor."
                      " Its own solve may therefore land in the dead band and"
                      " doom " (name (:role r)) " with the failure reported one"
                      " role away from its cause.")})
      (for [r roles
            :when (= :pinned (:kind r))
            c (:constraints r)]
        {:finding :constraint-on-pinned-role
         :role (:role r)
         :detail (str (name (:role r))
                      " is pinned but declares constraints; a pinned value is a"
                      " given and cannot be solved to satisfy them. Either make"
                      " it :solved or drop the constraint.")
         :constraint c})))))
(m/=> validate-spec [:=> [:cat [:map-of :keyword :any]] [:sequential [:map-of :keyword :any]]])

;; ═══════════════════════════════════════════════════════════════════════════
;; Solving one cell
;; ═══════════════════════════════════════════════════════════════════════════

(def resolved-statuses
  "Statuses whose cell carries a usable colour. A cell outside this set must
   never be silently read as a value by a dependent — that is what
   `:blocked-upstream` exists to say out loud."
  #{:pinned :solved :solved-chroma-reduced})

(defn- preference-key
  "The sort key `solve-cell` orders feasible candidates by, one per `:prefer`
   strategy: the tightest achieved ratio for `:least-separation`, its negation
   for `:most-separation`, or OKLCH-lightness distance from the shipped tone
   for `:closest-to-shipped`."
  [prefer shipped-oklch-l evaluations candidate]
  (case prefer
    :least-separation (reduce min Double/MAX_VALUE (map :wcag-ratio evaluations))
    :most-separation (- (reduce min Double/MAX_VALUE (map :wcag-ratio evaluations)))
    :closest-to-shipped (Math/abs (- (double (:oklch-l-actual candidate))
                                     (double shipped-oklch-l)))))

(defn constraint-label
  "A short, greppable name for one constraint. A conflict that reports only a
   COUNT of dropped constraints tells the reader a repair exists and not what it
   is, which is the difference between a finding and a shrug."
  [c]
  (case (:kind c)
    :contrast-min (str "min " (name (:floor c)) " vs " (name (:against c)))
    :hosts-foreground (str "hosts " (name (:floor c)))
    :dimmer-than (str "dimmer than " (name (:than c)) " on " (name (:against c)))))
(m/=> constraint-label [:=> [:cat [:map-of :keyword :any]] [:string {:min 1}]])

(defn- maximal-satisfiable-subset
  "For a `:reference-conflict`, the INDICES of the largest set of constraints
   that CAN be met together, so the repair is a named split rather than an
   instruction to go and experiment. Exhaustive over the subsets of a handful of
   constraints; ties broken by declaration order so the answer is deterministic.

   Indices, not the constraint maps: a mode-invariant role evaluates the SAME
   constraint map once per mode, so a set of maps collapses the two into one and
   the dropped list comes out empty. That is a silent wrong answer of exactly
   the shape this namespace exists to refuse."
  [n feasible-by-constraint]
  (let [idxs (vec (range n))]
    (->> (range (bit-shift-left 1 (long n)))
         (map (fn [bits]
                (let [chosen (filterv #(pos? (bit-and bits (bit-shift-left 1 (long %)))) idxs)]
                  {:chosen chosen
                   :hexes (when (seq chosen)
                            (reduce (fn [acc i]
                                      (set/intersection acc (nth feasible-by-constraint i)))
                                    (nth feasible-by-constraint (first chosen))
                                    (rest chosen)))})))
         (filter #(and (seq (:chosen %)) (seq (:hexes %))))
         (sort-by (fn [{:keys [chosen]}] [(- (count chosen)) chosen]))
         first
         :chosen
         vec)))

(defn solve-cell
  "Solve one (role, mode) cell — or refuse it with an attributed status.

   `resolved-by-mode` is {mode {role-key realized}}, populated in topological
   order. `modes` is the set of modes this cell must satisfy simultaneously: one
   for a mode-variant role, all of them for a mode-invariant one."
  [role modes resolved-by-mode]
  (let [primary-mode (first modes)
        shipped (shipped-hex role primary-mode)
        base (hex->oklch shipped)
        prefer (get role :prefer :closest-to-shipped)
        retain-min (double (get role :chroma-retain-min 0.0))
        chroma-min (* retain-min (double (:oklch-c base)))
        ;; Every constraint, tagged with the mode it is evaluated in. A
        ;; mode-invariant role gathers them from EVERY mode: a single value has
        ;; to satisfy dark and light at once, and solving the modes separately
        ;; would silently split a token the theme declares as one.
        active (vec (for [mode modes
                          c (:constraints role)
                          :when (constraint-applies? c mode)]
                      [c mode]))
        upstream-missing (vec (for [[c mode] active
                                    needed-role (constraint-references c)
                                    :when (nil? (get-in resolved-by-mode [mode needed-role]))]
                                {:reference needed-role :mode mode}))]
    (cond
      (seq upstream-missing)
      {:role (:role role) :modes (vec modes) :status :blocked-upstream
       :hex nil
       :blocked-by upstream-missing
       :detail (str (name (:role role))
                    " was never judged: " (count upstream-missing)
                    " reference(s) did not resolve - "
                    (str/join ", " (map #(str (name (:reference %)) "/" (name (:mode %)))
                                        upstream-missing))
                    ". An unjudged cell is a finding, never a skip.")}

      :else
      (let [infeasible
            (vec (for [[c mode] active
                       :when (= :contrast-min (:kind c))
                       :let [reference (get-in resolved-by-mode [mode (:against c)])
                             need (floor-ratio (:floor c))
                             best (max-hostable-ratio (:wcag-y reference))]
                       :when (< best need)]
                   {:reference (:against c)
                    :mode mode
                    :reference-hex (:hex reference)
                    :reference-wcag-y (:wcag-y reference)
                    :floor-ratio need
                    :max-hostable-wcag-ratio best
                    :reference-must-reach (hostable-band need)}))]
        (if (seq infeasible)
          {:role (:role role) :modes (vec modes) :status :reference-infeasible
           :hex nil
           :infeasible-references infeasible
           :detail (str (name (:role role))
                        " cannot be fixed by ANY hue, chroma or lightness: "
                        (str/join "; "
                                  (for [i infeasible]
                                    (str (name (:reference i)) " (" (:reference-hex i)
                                         ", " (name (:mode i)) ") admits at most "
                                         (format "%.2f" (:max-hostable-wcag-ratio i))
                                         ":1 against pure black or white, under the "
                                         (format "%.1f" (:floor-ratio i)) ":1 floor")))
                        ". The fix belongs to the reference, not here.")}
          ;; THE SHIPPED CHROMA IS A CEILING, NOT A TARGET. The search asks for
          ;; the shipped chroma and may only lose some of it to the gamut, so no
          ;; proposal is ever MORE saturated than what ships. That is a
          ;; deliberate conservatism, not a property of the maths: a role whose
          ;; floor is easier at a higher chroma will not be offered it here.
          (let [cands (->> (candidates {:oklch-c (:oklch-c base) :oklch-h (:oklch-h base)})
                           (filterv #(>= (double (:oklch-c-used %)) (- chroma-min 1.0e-12))))
                evaluate (fn [cand]
                           (mapv (fn [[c mode]]
                                   (evaluate-constraint c cand (get resolved-by-mode mode) mode))
                                 active))
                per-constraint (mapv (fn [[c mode]]
                                       (into #{}
                                             (comp (filter #(:satisfied?
                                                             (evaluate-constraint
                                                              c % (get resolved-by-mode mode) mode)))
                                                   (map :hex))
                                             cands))
                                     active)
                feasible (filterv (fn [cand] (every? :satisfied? (evaluate cand))) cands)
                empty-constraints (vec (for [[i s] (map-indexed vector per-constraint)
                                             :when (empty? s)]
                                         (let [[c mode] (nth active i)]
                                           {:constraint c :mode mode})))]
            (cond
              ;; TWO REASONS, ONE STATUS, AND THEY ARE NOT THE SAME REPAIR. The
              ;; first says the hue cannot be carried at that chroma AT ALL; the
              ;; second says it can, but never where the floor is. Sharing a
              ;; status without distinguishing them would leave a reader unable
              ;; to tell whether to relax the chroma budget or move the hue.
              (empty? cands)
              {:role (:role role) :modes (vec modes) :status :unreachable-in-gamut
               :hex nil
               :reason :no-candidate-at-chroma-budget
               :chroma-retain-min retain-min
               :detail (str (name (:role role))
                            " has no emittable colour at hue "
                            (format "%.1f" (double (:oklch-h base)))
                            " retaining " (format "%.0f%%" (* 100.0 retain-min))
                            " of its shipped chroma - the hue cannot be carried"
                            " at any lightness, before any floor is applied.")}

              (seq empty-constraints)
              {:role (:role role) :modes (vec modes) :status :unreachable-in-gamut
               :hex nil
               :reason :floor-unmet-at-chroma-budget
               :chroma-retain-min retain-min
               :unmet empty-constraints
               :detail (str (name (:role role))
                            " has hostable, mutually compatible references, but"
                            " no emittable colour at its own hue "
                            (format "%.1f" (double (:oklch-h base)))
                            " retaining " (format "%.0f%%" (* 100.0 retain-min))
                            " of its shipped chroma meets: "
                            ;; Mode-tagged, like the conflict detail. A
                            ;; mode-invariant role fails the same constraint once
                            ;; per mode, and listing it twice with no mode is a
                            ;; report that looks like a duplicate instead of two
                            ;; facts.
                            (str/join ", " (map #(str (constraint-label (:constraint %))
                                                      " (" (name (:mode %)) ")")
                                                empty-constraints))
                            ". The fix is this role's hue or chroma budget, not"
                            " the reference.")}

              (empty? feasible)
              (let [keep-idx (maximal-satisfiable-subset (count active) per-constraint)
                    dropped (mapv #(nth active %)
                                  (remove (set keep-idx) (range (count active))))]
                {:role (:role role) :modes (vec modes) :status :reference-conflict
                 :hex nil
                 :satisfiable-together (mapv #(nth active %) keep-idx)
                 :must-drop dropped
                 :detail (str (name (:role role))
                              " satisfies every reference individually and no"
                              " single value satisfies them together. Largest"
                              " compatible subset keeps " (count keep-idx)
                              " of " (count active)
                              "; the binding constraint(s) to drop, split the"
                              " role over, or move: "
                              (str/join ", " (map (fn [[c m]]
                                                    (str (constraint-label c)
                                                         " (" (name m) ")"))
                                                  dropped))
                              ".")})

              :else
              (let [chosen (->> feasible
                                (sort-by #(preference-key prefer (:oklch-l base) (evaluate %) %))
                                first)
                    evaluations (evaluate chosen)]
                ;; Re-verify the chosen candidate through the SAME evaluator the
                ;; search filtered with. A tautology while there is one path,
                ;; which is precisely what it is here to keep true.
                (when-not (every? :satisfied? evaluations)
                  (throw (ex-info "solver accepted a candidate its own evaluator rejects"
                                  {:role (:role role) :hex (:hex chosen)
                                   :evaluations evaluations})))
                {:role (:role role)
                 :modes (vec modes)
                 :status (if (:gamut-mapped? chosen) :solved-chroma-reduced :solved)
                 :hex (:hex chosen)
                 :realized chosen
                 :shipped-hex shipped
                 :moved? (not= (:hex chosen) (str/upper-case shipped))
                 :prefer prefer
                 :evaluations evaluations
                 :candidate-count (count cands)
                 :feasible-count (count feasible)}))))))))

;; ═══════════════════════════════════════════════════════════════════════════
;; Solving the whole spec
;; ═══════════════════════════════════════════════════════════════════════════

(def ambiguous-lightness-keys
  "Keys BANNED from anything this namespace emits. Each one is a name that does
   not say whether it means OKLCH lightness or WCAG relative luminance, which is
   the confusion `docs/UI-QUALITY-CONTRACTS.md` and a published table have both
   come close to. `scan-ambiguous-keys` enforces it over the real proposal, so
   the rule is a check rather than a convention."
  #{:l :c :h :lightness :luminance :y :contrast :ratio :chroma :hue})

(defn scan-ambiguous-keys
  "Every banned key found anywhere in `x`, with the path it was found at.
   Empty is the only acceptable result for an emitted proposal.

   `x` IS GENUINELY `:any` AND MUST STAY SO. This is a polymorphic recursive
   walker: `walk` descends through maps and sequentials into values of every
   shape, and nil is a legitimate node mid-recursion. Narrowing the argument
   would either be false (it is not always a map — the recursion re-enters with
   scalars) or would have to be widened straight back at the first nested value.
   When the arrow-spec checker is hosted here it will flag this as a BLOCKING
   weak arg-schema; the correct disposition is a proof-carrying allowlist entry
   of the same class as the other lvgl_codegen walkers, NOT a tightening."
  [x]
  (letfn [(walk [node path]
            (cond
              (map? node)
              (mapcat (fn [[k v]]
                        (concat (when (contains? ambiguous-lightness-keys k)
                                  [{:key k :path (conj path k)}])
                                (walk v (conj path k))))
                      node)
              (sequential? node)
              (mapcat (fn [i v] (walk v (conj path i))) (range) node)
              :else nil))]
    (vec (walk x []))))
(m/=> scan-ambiguous-keys [:=> [:cat :any] [:sequential [:map-of :keyword :any]]])

(defn solve
  "Solve every role in dependency order. Returns
   {:cells {[role mode] cell} :order [...] :spec-findings [...]}.

   A mode-invariant role is solved ONCE against every mode's constraints and the
   one result is registered under each mode, so the modes cannot silently drift
   apart. A role whose cell did not resolve registers NOTHING, which is what
   makes its dependents report `:blocked-upstream` instead of reading a hole as
   a colour."
  [spec]
  (let [spec-findings (validate-spec spec)
        order (topological-order (:roles spec))
        by-key (into {} (map (juxt :role identity)) (:roles spec))
        modes (vec (:modes spec))]
    (loop [remaining order
           resolved (into {} (map (fn [m] [m {}])) modes)
           cells {}]
      (if (empty? remaining)
        {:cells cells :order order :spec-findings spec-findings}
        (let [role-key (first remaining)
              role (get by-key role-key)
              invariant? (boolean (:mode-invariant? role))
              groups (if invariant? [modes] (mapv vector modes))
              results (for [group groups]
                        (if (= :pinned (:kind role))
                          (mapv (fn [m]
                                  [m {:role role-key :modes [m] :status :pinned
                                      :hex (str/upper-case (shipped-hex role m))
                                      :realized (realize-hex (shipped-hex role m))}])
                                group)
                          (let [cell (solve-cell role group resolved)]
                            (mapv (fn [m] [m cell]) group))))
              flat (apply concat results)
              resolved' (reduce (fn [acc [m cell]]
                                  (if (contains? resolved-statuses (:status cell))
                                    (assoc-in acc [m role-key]
                                              (or (:realized cell) (realize-hex (:hex cell))))
                                    acc))
                                resolved
                                flat)
              cells' (reduce (fn [acc [m cell]] (assoc acc [role-key m] cell)) cells flat)]
          (recur (rest remaining) resolved' cells'))))))
(m/=> solve [:=> [:cat [:map-of :keyword :any]] [:map-of :keyword :any]])

(defn propose
  "The PROPOSAL: a solved palette plus every cell that could not be solved,
   with a status that says whose fix it is.

   `:palette` carries only cells that resolved. `:findings` carries every cell
   that did not — never an omission, and never a best-effort colour standing in
   for one. `:premise` sizes the brief's own claim over THIS spec instead of
   restating it: how many cells held hue and chroma outright, how many bought
   their floor by surrendering chroma, and how many were impossible."
  [spec]
  (let [{:keys [cells order spec-findings]} (solve spec)
        vals* (vals cells)
        status-of (fn [s] (filterv #(= s (:status %)) vals*))
        solved (status-of :solved)
        chroma-reduced-cells (status-of :solved-chroma-reduced)
        unresolved (filterv #(not (contains? resolved-statuses (:status %))) vals*)
        judged (+ (count solved) (count chroma-reduced-cells) (count unresolved))]
    {:spec-id (:spec-id spec)
     :order order
     :spec-findings spec-findings
     :palette (into (sorted-map)
                    (for [[[role-key mode] cell] cells
                          :when (contains? resolved-statuses (:status cell))]
                      [[role-key mode] {:hex (:hex cell)
                                        :status (:status cell)
                                        :wcag-y (get-in cell [:realized :wcag-y])
                                        :oklch-l-actual (get-in cell [:realized :oklch-l-actual])
                                        :oklch-c-actual (get-in cell [:realized :oklch-c-actual])
                                        :oklch-h-actual (get-in cell [:realized :oklch-h-actual])
                                        :oklch-c-surrendered (get-in cell [:realized :oklch-c-surrendered])
                                        :shipped-hex (:shipped-hex cell)
                                        :moved? (:moved? cell)
                                        ;; How many emittable colours at this
                                        ;; role's own hue and chroma budget
                                        ;; satisfied EVERY constraint. 1 is a
                                        ;; solution with no slack: it holds,
                                        ;; and any later move of any reference
                                        ;; breaks it.
                                        :feasible-count (:feasible-count cell)}]))
     ;; DEDUPED, unlike the counts. A mode-invariant role registers ONE cell
     ;; under every mode — it occupies both (role, mode) slots and is counted in
     ;; both, but printing its single refusal twice would read as two defects.
     :findings (vec (sort-by (juxt :status :role) (distinct unresolved)))
     :summary (into (sorted-map) (frequencies (map :status vals*)))
     :premise {:judged-cells judged
               :held-hue-and-chroma (count solved)
               :bought-floor-by-surrendering-chroma (count chroma-reduced-cells)
               :impossible (count unresolved)
               :note (str "Cells whose floor was reached at the declared hue and"
                          " full chroma, versus cells that had to surrender"
                          " chroma, versus cells no hue or chroma reaches. Pinned"
                          " roles are excluded: they were never solved.")}}))
(m/=> propose [:=> [:cat [:map-of :keyword :any]] [:map-of :keyword :any]])

;; ═══════════════════════════════════════════════════════════════════════════
;; protogen's own spec
;; ═══════════════════════════════════════════════════════════════════════════

(def provenance
  "How strongly each reference edge is evidenced. A finding resting on
   `:inferred` deserves to be read as resting on a guess, and merging the tiers
   is how a weak edge acquires the authority of a derived one."
  {:proven-pairs "Co-declared (ink, fill) pair from docs/PROVEN-PAIRS.md, derived by tools/devcards/dev/proven_pairs.clj from theme.c + components.edn + screens + fixtures."
   :token-comment "Stated in a derivation comment in tools/renderer-gen/edn/tokens.edn."
   :contracts-doc "Stated in docs/UI-QUALITY-CONTRACTS.md."
   :inferred "Neither co-declared nor documented - this derivation's own reading of the role. Weakest tier."})

(defn- text-pair
  "A `:contrast-min` constraint at the `:text-shall` floor against `against`,
   optionally scoped to a mode subset — the shape every ink-vs-fill edge in
   `protogen-spec` is declared through."
  [against provenance-key & {:keys [modes]}]
  (cond-> {:kind :contrast-min :against against :floor :text-shall :provenance provenance-key}
    modes (assoc :modes modes)))

(defn- non-text-pair
  "A `:contrast-min` constraint at the `:non-text` floor against `against` —
   the shape every chrome/border edge in `protogen-spec` is declared through."
  [against provenance-key]
  {:kind :contrast-min :against against :floor :non-text :provenance provenance-key})

(defn- hosts
  "A `:hosts-foreground` constraint declaring that `floor` is met by some sRGB
   foreground. `for-roles` documents which dependents rely on this but is read
   by nothing (see the call-site comment on `:accent-bg` in `protogen-spec`) —
   only `:floor` drives `evaluate-constraint` and `validate-spec`."
  [floor for-roles provenance-key]
  {:kind :hosts-foreground :floor floor :for-roles for-roles :provenance provenance-key})

(defn- rung-under
  "A `:dimmer-than` constraint: the role that declares this must measure
   strictly dimmer than `than`, both against the same `against` reference —
   one ladder rung, expressed as data instead of a comment."
  [than against provenance-key]
  {:kind :dimmer-than :against against :than than :provenance provenance-key})

(def protogen-spec
  "protogen's own role graph, at the values `edn/tokens.edn` ships today.

   Surfaces are PINNED. They are the ladder's base and moving them is a
   different decision from deriving what sits on them; a consumer that wants
   them derived changes `:kind` to `:solved` and gives them constraints — the
   solver treats them identically either way.

   Every hue and chroma below is read from the shipped hex by `hex->oklch`, so
   no OKLCH COORDINATE is ever copied into source.

   THAT IS NOT THE SAME AS \"CANNOT DRIFT\", WHICH IS WHAT THIS PARAGRAPH USED
   TO SAY. Each `:shipped` hex IS a transcription of `edn/tokens.edn`, and the
   drift the retired sentence excluded is precisely the drift that happened
   twice: `:accent-bg`'s note below records the first, and `:fg-1`, `:fg-2` and
   `:disabled-fg` all kept their pre-repair values through the commit that moved
   the foreground ladder to three rungs.

   A stale hex is not cosmetic here — it is the SEARCH ANCHOR. `hex->oklch` reads
   the hue and chroma from it, so `candidates` enumerates a line THROUGH it and
   the whole search space is wrong; `:chroma-retain-min` is a fraction of its
   chroma; `:closest-to-shipped`/`:least-separation` rank against it; `:moved?`
   is computed from it.

   SCOPE THAT HONESTLY FOR THE THREE ROLES REPAIRED HERE: their harm was LATENT,
   not observed. All four ink rungs are `:blocked-upstream` or
   `:reference-conflict` in every one of the three `report-modes` today — the
   status fills conflict, which blocks `fg-0`, which blocks the rest of the
   ladder — so `solve-cell` returns before it ever reaches `candidates`, and
   reverting any of the three anchors moves ZERO emitted cells. The anchor is
   load-bearing and the values were wrong; what was not true is that a wrong
   value was silently changing this proposal's output. It would have, the moment
   the upstream conflict is repaired — which is the whole point of the
   derivation. `:accent-bg`, whose note records the first instance, IS `:solved`,
   so the harm was live there; generalising from it is the overstatement to
   avoid.

   The guarantee is now MECHANICAL rather than asserted:
   `palette-ladder-test/test-the-spec-mirrors-the-shipped-token-home` resolves
   every role through `lvgl-codegen.resolve` against the token home and fails on
   any cell that disagrees, in both modes, plus a totality check so an empty
   drift list cannot mean nothing was read."
  {:spec-id :protogen-shipped
   :modes [:dark :light]
   :roles
   [;; ── the ladder's base ────────────────────────────────────────────────
    {:role :surface-0 :kind :pinned :shipped {:dark "#0A0A12" :light "#F0F0E8"}}
    {:role :surface-1 :kind :pinned :shipped {:dark "#12121F" :light "#E0E0D4"}}
    {:role :surface-2 :kind :pinned :shipped {:dark "#1E1E2E" :light "#D0D0C0"}}
    {:role :surface-overlay :kind :pinned :shipped {:dark "#0A0A12" :light "#C0C0A8"}}
    {:role :pressed-surface :kind :pinned :shipped {:dark "#2A2A3E" :light "#C0C0A8"}}

    ;; ── fills, solved BEFORE the inks measured against them ──────────────
    {:role :accent-bg
     :kind :solved
     :shipped {:dark "#B18AF4" :light "#5C14D7"}
     :mode-invariant? false
     :chroma-retain-min 0.75
     :prefer :closest-to-shipped
     :note "MODE-FORKED in tokens.edn: no single fill clears the 6:1 text shall, so each mode takes the pole that clears both the ink floor and button-vs-card. The asgard family bakes the per-mode value into the stock parent's color_primary. It was mode-invariant #7C3AED, and this mirror kept saying so after the fork — which made every solve here model the accent at a value that no longer shipped, and left :accent-text's proven-pair constraint proving against a fill that did not exist."
     :constraints [(non-text-pair :surface-1 :inferred)
                   ;; HOSTS DROPS :fg-0 ONLY, matching the constraint deleted
                   ;; from fg-0 below — and fg-1 STAYS, because fg-1 still
                   ;; declares the pair from a shipped screen. The two halves
                   ;; have to agree: a role declaring `(text-pair :accent-bg)`
                   ;; while accent-bg's hosts set omits it is a contradiction
                   ;; between two statements of one fact.
                   ;;
                   ;; KNOW WHAT THIS EDIT IS AND IS NOT. `:for-roles` is
                   ;; written by the `hosts` helper and typed by the schema,
                   ;; and READ BY NOTHING — `evaluate-constraint` asks only
                   ;; whether SOME sRGB foreground clears the floor, and
                   ;; `validate-spec`'s host-floor reads `:floor` alone. So
                   ;; this set is documentation, and editing it changes no
                   ;; verdict. It is corrected because a wrong document is
                   ;; still wrong, not because a gate moved. Wiring
                   ;; `:for-roles` into `validate-spec` — so a role declaring
                   ;; a text pair against X while X's hosts omits it becomes a
                   ;; spec finding — is what would make this self-enforcing,
                   ;; and is not done here.
                   (hosts :text-shall #{:accent-text :fg-1} :proven-pairs)]}
    {:role :pressed-accent
     :kind :solved
     :shipped {:dark "#6B4FA0" :light "#8B5CF6"}
     :chroma-retain-min 0.75
     :constraints [(non-text-pair :surface-1 :inferred)
                   (hosts :text-shall #{:fg-0 :fg-1} :proven-pairs)]}
    {:role :status-error
     :kind :solved :shipped "#EF4444" :mode-invariant? true :chroma-retain-min 0.75
     :constraints [(non-text-pair :surface-1 :inferred)
                   (hosts :text-shall #{:fg-0} :proven-pairs)]}
    {:role :status-success
     :kind :solved :shipped "#10B981" :mode-invariant? true :chroma-retain-min 0.75
     :constraints [(non-text-pair :surface-1 :inferred)
                   (hosts :text-shall #{:fg-0} :proven-pairs)]}
    {:role :status-warning
     :kind :solved :shipped "#F59E0B" :mode-invariant? true :chroma-retain-min 0.75
     :constraints [(non-text-pair :surface-1 :inferred)
                   (hosts :text-shall #{:fg-0} :proven-pairs)]}

    ;; ── non-text chrome ──────────────────────────────────────────────────
    {:role :edge-0
     :kind :solved
     :shipped {:dark "#6B6B8A" :light "#70705A"}
     :chroma-retain-min 0.5
     :prefer :least-separation
     :note "tokens.edn derives this one against BOTH surface-1 and surface-2 at the 3:1 non-text floor."
     :constraints [(non-text-pair :surface-1 :token-comment)
                   (non-text-pair :surface-2 :token-comment)]}
    {:role :focused-edge
     :kind :solved
     :shipped {:dark "#22D3EE" :light "#0891B2"}
     :chroma-retain-min 0.5
     :constraints [(non-text-pair :surface-1 :inferred)]}
    {:role :checked-accent
     :kind :solved
     :shipped "#0E7490"
     :mode-invariant? true
     :chroma-retain-min 0.75
     :note "tokens.edn derives this against the 3:1 non-text floor on the surface AND a white glyph on the fill; the roller band's glyph colour comes from the stock parent."
     :constraints [(non-text-pair :surface-1 :token-comment)
                   (hosts :text-shall #{:stock-parent-glyph} :token-comment)]}

    ;; ── inks ─────────────────────────────────────────────────────────────
    {:role :accent-text
     :kind :solved
     :shipped {:dark "#1A1A28" :light "#E8E8F0"}
     :mode-invariant? false
     :chroma-retain-min 0.0
     :note "Authored ONLY as ink on accent-bg (UI-QUALITY-CONTRACTS 6.9); scoring it against a surface would score a pair nothing writes. It FORKS WITH THAT FILL and must: being the ink for a mode-forked fill, a single value cannot clear the shall in both modes — held mode-invariant across the accent fork it measured 2.21:1 in dark."
     :constraints [(text-pair :accent-bg :proven-pairs)]}
    {:role :fg-0
     :kind :solved
     :shipped {:dark "#E8E8F0" :light "#1A1A28"}
     :chroma-retain-min 0.0
     :prefer :closest-to-shipped
     :note "Every reference is a pair PROVEN-PAIRS shows co-declared and rendered - including the status fills, which UI-QUALITY-CONTRACTS 6.9 measures at 1.76:1 and 2.08:1 in dark mode. accent-bg is the ONE co-declared pair deliberately NOT constrained here; see the comment on that omission below."
     :constraints [(text-pair :surface-0 :proven-pairs)
                   (text-pair :surface-1 :proven-pairs)
                   (text-pair :surface-2 :proven-pairs)
                   ;; NO `(text-pair :accent-bg ...)`, and this is the one
                   ;; place the note's "every co-declared pair" rule is
                   ;; knowingly not applied, so the omission owes an argument.
                   ;;
                   ;; THE PAIR REALLY RENDERS — this is not a resolver
                   ;; artifact. Verified by rendering on the pinned wasm and
                   ;; reading `dump_tree`: `vr_state_base`'s label carries its
                   ;; own `text-fg-0`, which beats the button's theme ink, and
                   ;; comes out #E8E8F0 on #B18AF4 = 2.21:1 dark, 2.08:1 light.
                   ;; `vr_mod_bg` (an `lv_obj`, so no theme ink at all) and the
                   ;; `@hud-btn` fixtures at xl do the same.
                   ;;
                   ;; It is dropped because it is UNSATISFIABLE, and the
                   ;; binding half is LIGHT mode, for any sRGB colour
                   ;; whatsoever: fg-0 must sit at wcag-y <= 0.0622 to clear
                   ;; 6:1 on light surface-2, while the accent must sit at
                   ;; <= 0.2130 to clear 3:1 on light surface-1 (its lighter
                   ;; branch needs y >= 2.317, off-scale) — and 6:1 BETWEEN
                   ;; them needs one of the two at >= 0.25. Those bands are
                   ;; disjoint, so no pair exists.
                   ;;
                   ;; Dark mode alone WOULD be satisfiable, and saying
                   ;; otherwise would be the easy overstatement: a band exists
                   ;; at y in [0.11985, 0.125] — e.g. accent #744CB0 with fg-0
                   ;; #FBFCFF at 6.015:1 — but it clears button-vs-card by
                   ;; 0.006, forces accent-text to near-white, and has no
                   ;; light-mode partner. (For scale, the retired #7C3AED sits
                   ;; at 0.13426, just above that ceiling, which is exactly why
                   ;; it maxed at 5.70:1.)
                   ;;
                   ;; This changes what the ladder REQUIRES and hides nothing:
                   ;; docs/PROVEN-PAIRS.md derives the pair on its own path and
                   ;; still prints it FAIL/FAIL in both modes.
                   ;;
                   ;; fg-1 KEEPS its identical constraint on purpose, and the
                   ;; asymmetry is the point: fg-0's co-declarations are all
                   ;; FIXTURES, while fg-1's is `kitchen_sink` — a SHIPPED
                   ;; screen, rendering 1.05:1. Dropping fg-1 for symmetry
                   ;; would delete the ladder's only edge onto a real defect.
                   (text-pair :pressed-accent :proven-pairs)
                   (text-pair :status-error :proven-pairs)
                   (text-pair :status-success :proven-pairs)
                   (text-pair :status-warning :proven-pairs)]}
    {:role :fg-1
     :kind :solved
     :shipped {:dark "#C6C7E0" :light "#2A2938"}
     :chroma-retain-min 0.0
     :note "THE LADDER: tokens.edn requires fg-2 to stay visibly dimmer than fg-1, which is only meaningful if the rungs are ordered at all."
     :constraints [(text-pair :surface-1 :proven-pairs)
                   (text-pair :surface-2 :proven-pairs)
                   (text-pair :pressed-surface :proven-pairs)
                   (text-pair :accent-bg :proven-pairs)
                   (text-pair :pressed-accent :proven-pairs)
                   (rung-under :fg-0 :surface-1 :token-comment)]}
    {:role :fg-2
     :kind :solved
     :shipped {:dark "#A7A8C3" :light "#3D3C2B"}
     :chroma-retain-min 0.0
     :prefer :least-separation
     :note "The DIMMEST rung, and tokens.edn pins it at the dimmest tone clearing 6:1 on the WORST fill - pressed-surface in dark, surface-overlay in light. The constraint below names surface-1 because that is the pair PROVEN-PAIRS shows co-declared, and surface-1 is genuinely the LOOSER reference in both modes (7.98:1 dark / 8.40:1 light, against 6.02 / 6.04 on the binding fills), so a solve that satisfies it has NOT thereby satisfied the fill the tone actually lands on. Note which fills those are: pressed-surface is outside the surface-* ladder entirely, while surface-overlay is in it - so 'not in the surface-* ladder' is true of the dark case only, and tokens.edn correspondingly says only 'NOT :surface-2' for the light one."
     :constraints [(text-pair :surface-1 :proven-pairs)
                   (rung-under :fg-1 :surface-1 :token-comment)]}
    {:role :disabled-fg
     :kind :solved
     :shipped {:dark "#A7A8C3" :light "#3D3C2B"}
     :chroma-retain-min 0.0
     :prefer :least-separation
     :note "IT IS THE SAME TONE AS fg-2 IN BOTH MODES, deliberately: tokens.edn merged the fourth foreground rung into the third because LIGHT has no room for it at the governing floor (band 0.1272 OKLCH-L against a 0.0586 visible gap = two gaps, three rungs; dark's 0.1936 would carry four). The `rung-under` constraint below therefore CANNOT be satisfied by any distinct value the light mode admits, and that is the shipped design rather than a defect in it - read a refusal here as the merge, not as a solvable conflict."
     :constraints [(text-pair :surface-2 :proven-pairs)
                   (rung-under :fg-2 :surface-1 :token-comment)]}]})

;; ═══════════════════════════════════════════════════════════════════════════
;; Spec transforms — the same solver aimed at a different question
;; ═══════════════════════════════════════════════════════════════════════════

(defn with-pinned
  "Freeze `role-keys` at their shipped values. This turns the derivation into an
   AUDIT of what is shipped today: a pinned reference cannot be solved out of
   the dead band, so a dependent that the graph would otherwise have rescued
   reports `:reference-infeasible` attributed to it.

   Constraints on the frozen roles are dropped, because a pinned value cannot
   satisfy anything - `validate-spec` reports keeping them as a spec defect."
  [spec role-keys]
  (update spec :roles
          (fn [roles]
            (mapv (fn [r]
                    (if (contains? role-keys (:role r))
                      (-> r (assoc :kind :pinned) (dissoc :constraints))
                      r))
                  roles))))
(m/=> with-pinned [:=> [:cat [:map-of :keyword :any] [:set :keyword]] [:map-of :keyword :any]])

(defn without-roles
  "Drop `role-keys` and every constraint that reads them. This answers \"which
   reference must move\": a cell that conflicts with the full edge set and
   solves without one names that edge as the binding one."
  [spec role-keys]
  (update spec :roles
          (fn [roles]
            (into []
                  (comp (remove #(contains? role-keys (:role %)))
                        (map (fn [r]
                               (update r :constraints
                                       (fn [cs]
                                         (vec (remove #(seq (set/intersection
                                                             role-keys
                                                             (constraint-references %)))
                                                      cs)))))))
                  roles))))
(m/=> without-roles [:=> [:cat [:map-of :keyword :any] [:set :keyword]] [:map-of :keyword :any]])

;; ═══════════════════════════════════════════════════════════════════════════
;; Reporting
;; ═══════════════════════════════════════════════════════════════════════════

(defn report-lines
  "Human-readable proposal. Every lightness in it names its space."
  [proposal]
  (concat
   [(str "palette proposal - spec " (name (:spec-id proposal)))
    ""
    "NOT APPLIED. This is a derivation; nothing here writes tokens.edn or the"
    "generated header, and landing it is a separate, golden-moving change."
    ""
    "== summary =="]
   (for [[status n] (:summary proposal)]
     (format "  %-24s %d" (name status) n))
   [""
    (format "  premise: %d judged, %d held hue+chroma, %d surrendered chroma, %d impossible"
            (get-in proposal [:premise :judged-cells])
            (get-in proposal [:premise :held-hue-and-chroma])
            (get-in proposal [:premise :bought-floor-by-surrendering-chroma])
            (get-in proposal [:premise :impossible]))
    ""
    "== spec findings (static, before any solve) =="]
   (if (seq (:spec-findings proposal))
     (for [f (:spec-findings proposal)]
       (format "  %-26s %s" (name (:finding f)) (:detail f)))
     ["  none"])
   [""
    "== proposed palette =="
    (format "  %-18s %-6s %-9s %-9s %8s %8s %7s %5s %s"
            "role" "mode" "shipped" "proposed" "oklch-L" "wcag-Y" "d-chr" "slack" "status")]
   (for [[[role-key mode] cell] (:palette proposal)]
     (format "  %-18s %-6s %-9s %-9s %8.4f %8.4f %7.4f %5s %s%s"
             (name role-key) (name mode)
             (or (:shipped-hex cell) "(pinned)")
             (:hex cell)
             (double (:oklch-l-actual cell))
             (double (:wcag-y cell))
             (double (or (:oklch-c-surrendered cell) 0.0))
             (str (or (:feasible-count cell) "-"))
             (name (:status cell))
             (if (:moved? cell) "  MOVED" "")))
   [""
    "== findings - cells with no proposed value =="]
   (if (seq (:findings proposal))
     (mapcat (fn [f]
               (concat [(format "  [%s] %s %s" (name (:status f)) (name (:role f))
                                (str/join "+" (map name (:modes f))))]
                       [(str "      " (:detail f))]))
             (:findings proposal))
     ["  none"])))
(m/=> report-lines [:=> [:cat [:map-of :keyword :any]] [:sequential :string]])

(def report-modes
  "The three questions this derivation answers, each one the SAME solver over a
   transformed spec. They are printed together because reading any of them alone
   invites the wrong conclusion:

   `:derive`       the full graph. The dependency axis is free to move a fill so
                   that its own label becomes reachable, which is the fix
                   docs/UI-QUALITY-CONTRACTS.md 6.8 prescribes.
   `:audit-shipped` the same graph with the accent fill FROZEN at what ships
                   today. This is what the flat table would have been asked, and
                   it is where `:reference-infeasible` appears - the derive run
                   never shows it, because the axis repairs it upstream first.
   `:ladder-only`  the ink ladder with the status fills dropped, which is how you
                   read WHICH reference is binding: cells that conflict in the
                   full run and solve here name the dropped edge as the cause."
  [[:derive identity]
   [:audit-shipped #(with-pinned % #{:accent-bg})]
   [:ladder-only #(without-roles % #{:status-error :status-success :status-warning})]])

(defn -main
  "Print all three readings of protogen's own spec. Read-only: it writes
   nothing. Exits non-zero when a SPEC is defective or an emitted key is
   ambiguous about which lightness it means - a spec finding means the graph is
   under-constrained, so every colour below it was derived under a premise that
   does not hold."
  [& _args]
  (let [results (for [[label transform] report-modes]
                  (let [proposal (propose (transform protogen-spec))]
                    [label proposal (scan-ambiguous-keys proposal)]))]
    (doseq [[label proposal ambiguous] results]
      (println)
      (println (str "################ " (name label) " ################"))
      (println)
      (run! println (report-lines proposal))
      (when (seq ambiguous)
        (println)
        (println "AMBIGUOUS LIGHTNESS KEYS IN THE EMITTED PROPOSAL:")
        (run! #(println "  " (pr-str %)) ambiguous)))
    (when (some (fn [[_ proposal ambiguous]]
                  (or (seq (:spec-findings proposal)) (seq ambiguous)))
                results)
      (System/exit 1))))
(m/=> -main [:=> [:cat [:* :string]] :nil])
