(ns scratchcard.matrix
  "Expands one screen into the render matrix: theme families x modes x canvases.

  ALL SIX THEME STATES, ALWAYS. Three families (ASGARD 0, VANILLA 1, STOCK 2)
  times dark/light. Rendering only the three committed gallery combinations
  would be cheaper and would throw away the best property of the set: VANILLA
  restates the stock formulas, so family 1 MUST be bit-identical to family 2.
  Every run therefore carries a free idempotency check on the theme layer, at
  the cost of renders that are already sub-second.

  THE CANVAS SET IS CALLER-DRIVEN. `default-resolutions` applies only when the
  caller names none — the reader of a scratch card knows which sizes matter to
  the screen under test far better than this namespace does.

  THE DISPLAY TIER IS A SILENT CLIFF, WHICH IS WHY IT IS RECORDED. LVGL's
  default theme picks its radius and padding scale from the GREATER axis of
  the canvas, and crossing a threshold changes the rendered geometry with no
  error and no log line. A reader comparing an 800x480 render against a
  640x480 one is looking at two different theme tiers, and without
  `disp-tier` in the manifest that reads as a widget bug.

  `bp` IS A SEPARATE AXIS FROM THE CANVAS, and this namespace does not
  conflate them. `controls_set_breakpoint` selects which StyleVariant in the
  pushed screen applies (variant_index = bp*2 + theme_dark); the canvas is
  what `controls_init` was given. The devcard corpus has never varied bp — it
  renders everything at 0 — so `:bp-from-canvas?` defaults to FALSE and
  matches the rest of this repo. Set it true to sweep the token breakpoint
  tiers as well, and know that you are then off the path every other render
  here takes."
  (:require
   [clojure.string :as str]))

(def families
  "The three theme families the interpreter exposes.

  Mirrors `asgard_theme_family_t` in renderer/src/theme.h. Out-of-range is
  rejected by `controls_set_theme_family` with -1 rather than clamped, so an
  invalid entry here would fail at render time, not silently render family 0."
  [0 1 2])

(def family-names
  {0 "asgard" 1 "vanilla" 2 "stock"})

(def modes
  "0 light, 1 dark — the argument to `controls_set_theme_dark`."
  [0 1])

(def mode-names
  {0 "light" 1 "dark"})

;; ── display tier ───────────────────────────────────────────────────────────
;;
;; MIRRORED FROM C, and the mirror is TESTED rather than trusted.
;; renderer/lvgl/src/themes/default/lv_theme_default.c:646-648
;;
;;     if(greater_res <= 320)     new_size = DISP_SMALL;
;;     else if(greater_res < 720) new_size = DISP_MEDIUM;
;;     else                       new_size = DISP_LARGE;
;;
;; renderer/src/theme.c:25 restates the same rule for the child theme. There is
;; no way to read this at runtime — the tier is not in `dump_tree` — so a
;; mirror is unavoidable. `matrix_test` greps the C source and fails when these
;; numbers stop matching it, which is the closest thing to a single home that
;; a cross-language constant can have.

(def disp-small-max
  "greater_res AT OR BELOW this is DISP_SMALL."
  320)

(def disp-large-min
  "greater_res AT OR ABOVE this is DISP_LARGE."
  720)

(defn disp-tier
  "The LVGL default-theme display tier for a `w` x `h` canvas."
  ^String [w h]
  (let [greater (max (long w) (long h))]
    (cond
      (<= greater disp-small-max) "DISP_SMALL"
      (< greater disp-large-min) "DISP_MEDIUM"
      :else "DISP_LARGE")))

;; ── breakpoint tiers ───────────────────────────────────────────────────────

(def breakpoint-bounds
  "Lower width bound of each breakpoint tier, ascending.

  Mirrors `:breakpoints` in tools/renderer-gen/edn/tokens.edn
  (sm [0 640] md [640 1024] lg [1024 1280] xl [1280 nil]). Tier index is what
  `controls_set_breakpoint` takes, clamped 0..3 by the interpreter."
  [[0 :sm] [640 :md] [1024 :lg] [1280 :xl]])

(defn bp-for-width
  "The breakpoint tier index (0..3) for a canvas `w`."
  [w]
  (->> breakpoint-bounds
       (keep-indexed (fn [i [lo _]] (when (>= (long w) (long lo)) i)))
       last))

(def default-resolutions
  "The canvases used when a caller names none: 1080p, the common breakpoints
  down, and iPhone 13 in both orientations.

  COVERAGE NOTE, stated rather than left for a reader to discover: this set
  spans DISP_LARGE and DISP_MEDIUM but never reaches DISP_SMALL, whose
  threshold is a greater axis of 320 or less. The small tier halves the
  scrollbar radius and drops PAD_TINY from 8 to 2, so a screen that must work
  there needs a canvas named explicitly."
  [{:w 1920 :h 1080 :note "1080p"}
   {:w 1280 :h 720}
   {:w 1024 :h 600}
   {:w 800 :h 480 :note "the devcard corpus canvas"}
   {:w 640 :h 480}
   {:w 390 :h 844 :note "iPhone 13 portrait, logical pt"}
   {:w 844 :h 390 :note "iPhone 13 landscape"}])

;; ── validation ─────────────────────────────────────────────────────────────

(def max-dim
  "Mirrors CONTROLS_MAX_DIM in renderer/src/main.c.

  `controls_init` returns -1 above this rather than clamping, so a caller's
  oversized canvas must be refused here with a message rather than surfacing
  as an opaque render failure."
  16384)

(defn resolution-problem
  "nil when `res` is a usable canvas, else a message saying why not."
  [{:keys [w h] :as res}]
  (cond
    (not (and (integer? w) (integer? h)))
    (str "canvas needs integer :w and :h — got " (pr-str res))

    (or (not (pos? w)) (not (pos? h)))
    (str "canvas dimensions must be positive — got " w "x" h)

    (or (> (long w) max-dim) (> (long h) max-dim))
    (str "canvas " w "x" h " exceeds CONTROLS_MAX_DIM " max-dim)))

(defn validate-resolutions!
  "Return `resolutions`, or throw naming every problem at once.

  All problems at once rather than the first: a caller fixing a supplied set
  should not have to re-run to discover the second mistake."
  [resolutions]
  (when (empty? resolutions)
    (throw (ex-info "empty resolution set — a matrix over no canvases renders nothing"
                    {:error "EMPTY_MATRIX"})))
  (let [problems (keep resolution-problem resolutions)]
    (when (seq problems)
      (throw (ex-info (str "invalid resolutions: " (str/join "; " problems))
                      {:error "INVALID_RESOLUTION" :problems (vec problems)}))))
  resolutions)

;; ── expansion ──────────────────────────────────────────────────────────────

(defn cell-label
  "A stable, filesystem-safe label for one matrix cell."
  ^String [{:keys [family dark w h]}]
  (str (family-names family) "-" (mode-names dark) "-" w "x" h))

(defn expand
  "Every render cell for `opts`.

  `:resolutions` defaults to `default-resolutions`; `:families` and `:modes`
  default to all. `:bp-from-canvas?` (default false) derives the breakpoint
  tier from the canvas width instead of pinning it at 0.

  ORDER IS DETERMINISTIC — resolution, then family, then mode — so two runs
  produce comparable manifests and a reader can diff cell N against cell N."
  [{:keys [resolutions bp-from-canvas?]
    fams :families
    ms :modes
    :or {bp-from-canvas? false}}]
  (let [resolutions (validate-resolutions! (or (seq resolutions) default-resolutions))
        fams (or (seq fams) families)
        ms (or (seq ms) modes)]
    (vec
     (for [{:keys [w h note]} resolutions
           family fams
           dark ms]
       (cond-> {:family family
                :dark dark
                :w w
                :h h
                :bp (if bp-from-canvas? (bp-for-width w) 0)
                :disp-tier (disp-tier w h)}
         note (assoc :note note))))))

(defn expected-count
  "How many cells `expand` must produce for `opts`.

  The NON-VACUITY FLOOR's other half: a caller asserts against this rather
  than against `pos?`, because a discovery collapse and a clean small matrix
  print the same reassuring number otherwise."
  [{:keys [resolutions] fams :families ms :modes}]
  (* (count (or (seq resolutions) default-resolutions))
     (count (or (seq fams) families))
     (count (or (seq ms) modes))))
