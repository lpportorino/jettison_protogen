(ns devcards.palette
  "Opt-in draw-stream palette conformance producer.

   WHAT IT MEASURES. The renderer's read-only LVGL draw unit observes scalar
   colours declared by colour-bearing draw descriptors before framebuffer
   blending. `controls_dump_draw_palette` projects those task records to
   deterministic unique {:hex :theme_recolor} pairs. This producer reports a
   `:drawn-colour-in-no-token-table` finding for a non-token pair unless the
   observer proves it came from one of this theme's declared recolor
   signatures. It does not inspect glyph ink, calculate contrast, or infer a
   cause from the colour.

   IT IS NOT IN THE TREE, AND MUST NOT BE. The palette is what has been PAINTED
   since the last window boundary — a property of the ROUTE to a screen, not of
   the screen. The renderer's morph-parity oracle measured the difference
   directly: the same tree reached by a patch carried 29 task records against
   18 reached by a fresh load. Since the entire value of the draw stream is
   that it reports what the tree cannot (a colour later covered over), it can
   never be an equality-checked member of the tree. So it arrives as its own
   context key, `:draw-palette`.

   THAT KEY IS READ BUT NOT YET DECLARED, and the difference matters. The
   registry validates `:requires` against a CLOSED key set in
   `devcards.findings`, so naming `:draw-palette` there today is rejected as an
   unknown context key — and that file is outside the fork that wrote this, so
   this producer does not reach for it. The consequence is a WEAKER refusal, not
   a silent one: a caller that never plumbed the export supplies nothing, and
   the `:draw-palette-unavailable` / `:cantTell` branch below answers instead of
   the registry. Adding `:draw-palette` to that closed set, and only then
   listing it in :requires, is step one of arming this producer — it upgrades a
   producer-level third answer into a registry-level hard stop.

   WHICH PALETTE — the FULL semantic catalogue, not the C projection. Two
   declared tables exist and they are not the same size: the semantic colour
   tokens emitted into `output/manifests/design-tokens.json` (this producer's
   default, loaded live) and the strictly narrower subset that reaches
   `renderer/src/generated/theme_tokens.h`, which is what theme.c can actually
   select. The finding's NAME is what settles the choice: it promises `in no
   token table`, and a colour equal to an authoring-only semantic token IS in a
   declared table even though theme.c cannot select it — so judging against the
   narrow projection would report a true statement about the C header as a false
   one about the catalogue. Consumers replace :palette/colors-by-mode with their
   own declared catalogue when they arm the producer.

   THE OBSERVATION WINDOW IS ONE COMPOSITE. The observer is cleared by
   `controls_load_ui`, by any composite change (breakpoint or dark/light) and by
   a theme-family change, so every record it holds was drawn under the single
   `theme_dark` the root reports. That clearing is load-bearing rather than
   hygiene: this producer picks ONE token table from that one field, so records
   surviving an in-place theme switch would be judged against the wrong table.

   WHY IT IS OPT-IN. This namespace exports `producer` but does not add it to
   devcards.findings/builtin-producers or either vector in devcards.lanes.
   Arming must capture a dump for every judged mode and size the existing
   non-token population; the shipped corpus is deliberately not made red by
   introducing the measurement.

   IMAGES ARE OUTSIDE THE OBSERVATION VOCABULARY. IMAGE/LAYER/MASK/BLUR tasks
   do not declare one scalar painted `color`; asset pixels and framebuffer
   composites are not silently substituted for the descriptor-level fact this
   producer names."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def manifest-path
  "The emitted semantic-token catalogue, relative to tools/devcards (the
   working directory of every devcards entry point)."
  "../../output/manifests/design-tokens.json")

(def ^:private hex-pattern #"^#[0-9A-F]{6}$")

(defn- canonical-hex?
  [x]
  (and (string? x) (boolean (re-matches hex-pattern x))))

(defn palette-table?
  "A complete mode -> canonical-hex-set table."
  [x]
  (and (map? x)
       (= #{:dark :light} (set (keys x)))
       (every? (fn [xs]
                 (and (set? xs) (seq xs) (every? canonical-hex? xs)))
               (vals x))))

(defn- load-palette
  []
  (let [f (io/file manifest-path)]
    (when-not (.exists f)
      (throw (ex-info
              (str "design-tokens.json not found at " manifest-path
                   " — run `make -f renderer.mk manifests` first")
              {:path manifest-path})))
    (let [entries (vals (get (json/read-str (slurp f)) "tokens"))
          colours (filter #(= "color" (get % "kind")) entries)]
      {:dark (into #{} (map #(str/upper-case (get % "dark"))) colours)
       :light (into #{} (map #(str/upper-case (get % "light"))) colours)})))

(def palette-by-mode
  "The live full semantic colour catalogue. Public so an arming report can
   state the exact distinct-value population it judged rather than copying a
   count into prose."
  (load-palette))

(when-not (palette-table? palette-by-mode)
  (throw (ex-info "emitted design-token palette has an invalid shape"
                  {:palette palette-by-mode :path manifest-path})))

(defn- cant-tell
  [card invariant reason detail]
  {:card card
   :invariant invariant
   :node "draw-stream"
   :detail detail
   :act/outcome :cantTell
   :act/reason reason})

(defn palette-findings
  "Pure producer body. Context needs :card-id, :tree (for the rendered mode),
   :draw-palette and resolved :thresholds {:colors-by-mode ...}. The
   :draw-palette value is the parsed `controls_dump_draw_palette` payload:
     {:records n
      :colors [{:hex \"#RRGGBB\" :theme_recolor boolean} ...]
      :overflow true?}

   Missing/overflowed/empty instrumentation is a blocking third answer, never
   an empty clean result. `:records` is the observer's own pre-projection task
   count and is what separates `nothing was drawn` from `nothing was watching`:
   a colour set can be empty for either reason, and a rule that reads the two
   the same way reports a dead observer as a clean screen."
  [{:keys [card-id tree thresholds] :as ctx}]
  (let [draw-palette (:draw-palette ctx)
        theme-dark (:theme_dark tree)
        mode (cond (= 1 theme-dark) :dark
                   (= 0 theme-dark) :light
                   :else nil)
        palette (:colors-by-mode thresholds)]
    (cond
      ;; `records` absent, not merely zero: a payload without it came from some
      ;; other projection of this observer, and its empty-vs-unwatched
      ;; distinction cannot be recovered. This also catches a caller that
      ;; supplied no :draw-palette at all, which the registry cannot refuse for
      ;; the closed-key-set reason in the ns docstring.
      (nil? (:records draw-palette))
      [(cant-tell card-id :draw-palette-unavailable :observer-not-exposed
                  "the draw-palette payload carries no record count — this renderer did not expose the draw-stream observation")]

      (:overflow draw-palette)
      [(cant-tell card-id :draw-palette-overflow :observer-buffer-overflow
                  "draw-stream observer overflowed its bounded task buffer; palette result is incomplete")]

      (nil? mode)
      [(cant-tell card-id :draw-palette-mode-unknown :mode-not-serialised
                  "root theme_dark is absent or outside 0|1; no mode-specific token table can be selected")]

      ;; Measured over every shipped card in both modes: the smallest observed
      ;; count is 1 and no render produced an empty colour set, so zero here is
      ;; an anomaly (an unregistered observer, an image-only screen) and not a
      ;; card that happens to be clean.
      (zero? (long (:records draw-palette)))
      [(cant-tell card-id :draw-palette-empty :no-colour-bearing-task
                  "the observer recorded no colour-bearing draw task; a clean palette here would assert something never watched")]

      :else
      (let [valid (get palette mode)]
        (into []
              (comp
               (filter (fn [observation]
                         (and (not (:theme_recolor observation))
                              (not (contains? valid
                                              (str/upper-case (:hex observation)))))))
               (map #(update % :hex str/upper-case))
               (distinct)
               (map (fn [{:keys [hex]}]
                      {:card card-id
                       :invariant :drawn-colour-in-no-token-table
                       :mode mode
                       :node "draw-stream"
                       :detail (str hex " was declared by a draw descriptor, "
                                    "matches no " (name mode)
                                    " semantic colour token, and was not "
                                    "tagged as a declared theme recolor")})))
              (:colors draw-palette))))))

(def producer
  "Opt-in registry producer. It is intentionally absent from every shipped
   producer vector; consumers arm it by name after arranging a dump for each
   mode they claim to judge."
  {:id :palette
   :fn palette-findings
   ;; :draw-palette is READ from the context but cannot be listed here: the
   ;; registry's context-key set is closed (see the ns docstring). Its absence
   ;; is caught by the :draw-palette-unavailable branch instead, so it is never
   ;; an empty vector indistinguishable from a clean screen.
   :requires #{:tree}
   :thresholds
   {:colors-by-mode
    {:pred palette-table?
     :default palette-by-mode
     :doc (str "Complete dark/light semantic colour catalogue. The default "
               "comes from output/manifests/design-tokens.json, not the "
               "narrower native-theme C projection.")}}
   :outcomes #{:failed :cantTell}
   :reasons
   {:observer-not-exposed
    "The renderer/dump predates the draw observer; install it before judging."
    :observer-buffer-overflow
    "The bounded observer lost task records; raise/rework the bound and rerun."
    :mode-not-serialised
    "The dump did not identify dark versus light, so palette membership is undecidable."
    :no-colour-bearing-task
    "Nothing colour-bearing was observed; check the observer is registered before reading this as clean."}})
