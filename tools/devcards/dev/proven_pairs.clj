(ns proven-pairs
  "Read-only probe: which (ink, fill) token pairs does this repo actually
   DECLARE TOGETHER, and what contrast ratio does each one achieve?

   THE TABLE IS DERIVED, NEVER HAND-WRITTEN, and it is NOT a cross product.
   `docs/UI-QUALITY-CONTRACTS.md` §6.9 states why a declared-FG × declared-BG
   product fails in both directions at once, and this probe is the constructive
   answer to it. Re-derived here rather than trusted, because a probe that
   quotes its own spec proves nothing:

     - UNSOUND: the product invents pairs nothing authors. `accent-text` is
       authored ONLY as ink on `accent-bg`; scored against every surface it
       yields a mode-invariant near-white at ~1.06:1 on a light fill, read as a
       catastrophic failure of a pair that cannot occur.
     - INCOMPLETE: the FG/BG split is not a property of a token. `bg-fg-0` is a
       real class in the visual-regression fixtures (a foreground token used as
       a FILL) and `hud-label` is `text-accent-bg` (a background token used as
       INK). Any hand-written split is an assumption about USAGE, and it is the
       assumption that decides what the gate can see.

   So the pair set comes from CO-DECLARATION — two colour declarations that are
   both in force on the same drawn glyph — established four ways, each exact and
   each labelled in the output so a reader can audit provenance:

     :theme-style   ONE `lv_style_*` object in `renderer/src/theme.c` sets both
                    `text_color` and `bg_color`. No cross-style inference: the
                    theme's apply dispatch decides which styles co-apply at
                    RUNTIME, and this probe does not model it.
     :self          ONE class string sets both `text-<tok>` and `bg-<tok>`.
     :ancestor      A text-bearing node's nearest self-or-ancestor ink
                    declaration against its nearest self-or-ancestor fill
                    declaration, inside ONE authored tree.
     :literal-*     The same, where one or both ends is a hex LITERAL rather
                    than a token (`demo_widgets.edn`, the ported LVGL demo).
                    Reported apart from the token table — a literal is a fact
                    about what is drawn, not a declared token pair.

   THE THIRD ANSWER IS MANDATORY AND IS PRINTED, never left as an empty vector.
   An ink declaration that reaches no text, a glyph whose fill is undeclared
   inside its own tree, a theme style that declares only one end, a class token
   the structural pass never visited — each is a FINDING with its own key. A
   rule that passes over what it could not classify reports \"clean\" and \"I
   could not look\" as the same empty result.

   VERDICTS ARE PASS/FAIL WITH NO NOISE BAND. This is exact arithmetic on
   declared values against a declared floor — the same verdict shape as
   `devcards.geometry`, and importing an \"uncertain\" band here would
   manufacture doubt the arithmetic does not have. What IS uncertain is
   coverage, and that is what the findings carry.

   WHAT IT CANNOT SEE, stated because a gate that implies more than it measures
   is the failure this repo refuses everywhere else:
     - A COMPOSITED fill. `hud-btn` carries `opa-overlay-opa`, which resolves to
       `bg-opa` (`style-props`), fading the FILL over whatever is behind it
       while leaving the glyphs untouched. The rendered pair is then a token ink
       on a composite, and the exact byte depends on the SW blend path. This
       probe reports the AUTHORED pair and flags the node — it does not pretend
       to compute the composite. That value has to come from the dump.
     - A STOCK LVGL colour. `checked_accent` sets a fill and no ink; the
       selected-band glyph is stock's white. Reported as a one-ended theme
       style, never silently completed from a token.
     - RUNTIME co-application. Two theme styles landing on one object is a
       property of `theme_apply`'s C dispatch, not of any declaration.

   SISTER PROBES, and the line between them: `dev/disabled_pair_probe.clj`
   measures the pair a widget RENDERS off the framebuffer; this one measures the
   pair the sources DECLARE. They answer different questions and the opacity ban
   exists to make the answers agree.

   RUN (regenerates docs/PROVEN-PAIRS.md; gates nothing, exits 0):
     tools/uber.sh 'cd tools/devcards && clojure -M:proven-pairs'
   SELF-TEST (the derivation's own canaries; exits non-zero on failure):
     tools/uber.sh 'cd tools/devcards && clojure -M:proven-pairs --self-test'"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [lvgl-codegen.component :as component]
            [lvgl-codegen.expand :as expand]
            [lvgl-codegen.style-props :as style-props]
            [lvgl-codegen.theme-tokens :as theme-tokens]))

(set! *warn-on-reflection* true)

;; ── Paths. Repo-root-relative, resolved from the devcards project dir, the
;; same convention `lvgl-codegen.core/design-tokens-path` uses. The alias runs
;; with tools/devcards as CWD.
(def ^:private repo-root (or (System/getenv "PROVEN_PAIRS_ROOT") "../.."))

(defn- at [^String p] (str repo-root "/" p))

(def tokens-manifest "output/manifests/design-tokens.json")
(def components-file "tools/renderer-gen/edn/components.edn")
(def screens-dir "renderer/edn/screens")
(def vr-fixtures-file "tools/renderer-gen/src/lvgl_codegen/fixtures.clj")
(def theme-c-file "renderer/src/theme.c")
(def output-doc "docs/PROVEN-PAIRS.md")

(def regen-command
  "The one home of the command the generated doc names in its own header."
  "tools/uber.sh 'cd tools/devcards && clojure -M:proven-pairs'")

;; ── The floor. WCAG AA body text; the value the brief for this table asks
;; against. This repo's OWN governing floor for operator-facing text is
;; MIL-STD-1472H 5.2.2.7's 6:1 (see the :fg-disabled derivation in
;; tools/renderer-gen/edn/tokens.edn), which is STRICTER — so both columns are
;; emitted and neither is presented as the other.
(def ^:const wcag-aa-floor 4.5)

(def ^:const governing-floor 6.0)

;; ── WCAG 2.x relative luminance + contrast ratio.
;; IDENTICAL arithmetic to dev/disabled_pair_probe.clj (and to the palette
;; audit it cites), so a declared ratio here is comparable digit for digit with
;; a measured one there. Do not "modernise" the 0.03928 knee in one file only.
(defn lin ^double [^long c]
  (let [c (/ (double c) 255.0)]
    (if (<= c 0.03928) (/ c 12.92) (Math/pow (/ (+ c 0.055) 1.055) 2.4))))

(defn luminance ^double [[r g b]]
  (+ (* 0.2126 (lin r)) (* 0.7152 (lin g)) (* 0.0722 (lin b))))

(defn contrast ^double [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn hex->rgb
  "\"#RRGGBB\" or \"0xRRGGBB\" or \"RRGGBB\" → [r g b]. Returns nil for anything
   that is not a 6-digit hex triple — a caller must treat nil as UNRESOLVED and
   never as black, which is how a parse failure turns into a silent 21:1."
  [s]
  (when (string? s)
    (let [h (-> s str/trim (str/replace #"^#|^0[xX]" ""))]
      (when (re-matches #"[0-9a-fA-F]{6}" h)
        [(Long/parseLong (subs h 0 2) 16)
         (Long/parseLong (subs h 2 4) 16)
         (Long/parseLong (subs h 4 6) 16)]))))

;; ── Token manifest ───────────────────────────────────────────────────────────
(defn load-tokens
  "The pinned design-tokens manifest → {token-kw {:kind kw :dark v :light v}}.
   Same parse `lvgl-codegen.core/load-design-tokens` performs; inlined rather
   than required because that namespace pulls the protobuf emitter and this
   probe needs no wire at all. An EMPTY manifest is a hard error — a vacuous
   token set would report a clean, empty table."
  [path]
  (let [raw (with-open [rdr (io/reader (io/file path))] (json/read rdr :key-fn keyword))
        tokens (into {} (map (fn [[k v]] [k (update v :kind keyword)])) (:tokens raw))]
    (when (empty? tokens)
      (throw (ex-info "design-tokens manifest carries no tokens" {:path path})))
    tokens))

(defn colour-tokens
  "The colour-kind token keys — the closed vocabulary both ends of a token pair
   are drawn from."
  [tokens]
  (into #{} (comp (filter (fn [[_ v]] (= :color (:kind v)))) (map key)) tokens))

(defn token-hex
  "Concrete hex for `tok` in `mode` (:dark/:light), or nil when unknown."
  [tokens tok mode]
  (get-in tokens [tok mode]))

;; ── Source 1: the theme's own style objects ──────────────────────────────────
(def colour-macro->token
  "`THEME_<X>` C macro base → semantic token key, taken from the generator's own
   field table (`lvgl-codegen.theme-tokens/fields`) rather than re-listed here.
   That table is the CLOSED projection: a token absent from it has no C define
   and is unreachable from src/theme.c, which is why `accent-text` cannot appear
   on the theme side at all."
  (into {} (for [f theme-tokens/fields :when (= :color (:kind f))]
             [(:c-macro f) (:sem-key f)])))

(defn- matching-paren-end
  "Index just past the `)` matching the `(` at `open` in `s`, or nil."
  [^String s ^long open]
  (loop [i (inc open) depth 1]
    (cond (>= i (.length s)) nil
          (= \( (.charAt s i)) (recur (inc i) (inc depth))
          (= \) (.charAt s i)) (if (= 1 depth) (inc i) (recur (inc i) (dec depth)))
          :else (recur (inc i) depth))))

(defn- call-args
  "Every argument-text span of `fn-name(` calls inside `block`."
  [^String block ^String fn-name]
  (let [needle (str fn-name "(")]
    (loop [from 0 acc []]
      (let [i (str/index-of block needle from)]
        (if-not i
          acc
          (let [open (+ (long i) (.length fn-name))
                end (matching-paren-end block open)]
            (if-not end
              acc
              (recur (long end) (conj acc (subs block (inc open) (dec (long end))))))))))))

(defn- tokens-in-args
  "Colour tokens named by `THEME_<X>_DARK|LIGHT` macros inside an argument span.
   A literal `lv_color_hex(0x…)` or an `lv_palette_*` call is deliberately NOT
   collected: those are the VANILLA/stock arms of a `pick_color`, and they are
   not tokens. Their absence is reported as a one-ended style, never completed."
  [^String args]
  (into #{} (keep (fn [[_ base]] (colour-macro->token base))
                  (re-seq #"(THEME_[A-Z0-9_]+)_(?:DARK|LIGHT)" args))))

(defn parse-theme-styles
  "`renderer/src/theme.c` → [{:style name :ink #{tok} :fill #{tok}}], one entry
   per `style_reset(&s->NAME, inited)` block.

   THE BLOCK IS THE UNIT OF CO-DECLARATION and that is the whole soundness
   argument: props set on ONE `lv_style_t` are, by LVGL's own model, applied
   together to whatever object takes that style. Reaching across blocks would
   assert a runtime co-application `theme_apply`'s C dispatch decides, which
   this probe does not model and does not guess."
  [^String src]
  (let [starts (->> (re-seq #"style_reset\(&s->([A-Za-z0-9_]+),"
                            src)
                    (map second))
        idxs (loop [from 0 acc []]
               (let [i (str/index-of src "style_reset(&s->" from)]
                 (if i (recur (inc (long i)) (conj acc i)) acc)))
        bounds (map vector idxs (concat (rest idxs) [(count src)]))]
    (mapv (fn [nm [a b]]
            (let [block (subs src a b)]
              {:style nm
               :ink (into #{} (mapcat tokens-in-args)
                          (call-args block "lv_style_set_text_color"))
               :fill (into #{} (mapcat tokens-in-args)
                           (call-args block "lv_style_set_bg_color"))}))
          starts
          bounds)))

;; ── Sources 2-4: the authoring vocabulary and the trees that use it ──────────
;; A NODE'S COLOUR DECLARATIONS, keyed by the axes LVGL actually resolves on:
;;   state — pressed/focused/disabled are SEPARATE style groups that override
;;           the default group when the object enters that state. A child label
;;           inherits `text_color` resolved in its PARENT's state, which is why
;;           state propagates down the ancestor chain here.
;;   bp    — the composite breakpoint index (`expand/expand->variants`): a token
;;           applies at every index >= its own tier's min index, HIGHEST tier
;;           wins regardless of class-string order.
(def bp-tiers
  "Breakpoint tier → composite min index, from the style-props registry."
  (into (sorted-map-by (fn [a b] (compare (style-props/bp-min-index a)
                                          (style-props/bp-min-index b))))
        (map (fn [[k v]] [k v]))
        style-props/bp-min-index))

(def states
  "The state groups a class string can scope to, plus the default group."
  (into [nil] (sort (keys style-props/state-selector))))

(def prop->role
  "The style props this probe reads, and what each one contributes.

   `:bg-opa` and `:opa` are here because a pair is only the AUTHORED pair while
   nothing composites it. `docs/UI-QUALITY-CONTRACTS.md` §6.9: the authoring
   vocabulary's `opa-` prefix resolves to `bg-opa` (fill only, glyphs untouched)
   while whole-widget `opa` has NO class prefix and arrives only through a raw
   `:style` map — two different mechanisms that this probe must not merge. It
   computes neither composite; it marks the row, because the exact byte depends
   on the SW blend path and has to come from the dump."
  {:text-color :ink :bg-color :fill :bg-opa :fill-opa :opa :layer-opa})

(defn- decl-of
  "One parsed class token or :style entry → a declaration, or nil.
   `:ref` is a token reference; a literal (hex string, or an integer opacity)
   arrives via :style or an :int class token."
  [parsed]
  (when-let [role (prop->role (:prop parsed))]
    (cond (:ref parsed) {:role role
                         :token (:ref parsed)
                         :state (:state parsed)
                         :bp (:bp parsed)}
          (some? (:value parsed)) {:role role
                                   :literal (:value parsed)
                                   :state (:state parsed)
                                   :bp (:bp parsed)})))

(defn node-decls
  "Every colour declaration a node makes, from its class string (macros already
   expanded) and from its raw `:style` map. Returns a seq of declaration maps."
  [class-defs node]
  (let [cls (some->> (:class node)
                     (expand/expand-class-macros class-defs)
                     ;; A `$param` placeholder is a COMPONENT parameter, still
                     ;; unsubstituted when the component's own template is walked
                     ;; as vocabulary. It carries no colour by itself, and every
                     ;; INSTANTIATION is walked separately with the parameter
                     ;; resolved, so dropping it here loses nothing. Anything
                     ;; else that fails to parse still throws, exactly as the
                     ;; real pipeline would — an unknown class token is a defect,
                     ;; not something for this probe to absorb. A class that was
                     ;; ONLY placeholders (a component template like :btn's
                     ;; `:class "$class"`) empties under that removal, and an
                     ;; empty string is not a token: `parse-class-string` would
                     ;; split it into [""] and throw on a token nobody wrote,
                     ;; so blank collapses to no-class instead.
                     (#(str/join " " (remove (fn [t] (str/includes? t "$"))
                                             (str/split (str/trim %) #"\s+"))))
                     (#(when-not (str/blank? %) %)))
        from-class (keep decl-of (expand/parse-class-string cls))
        ;; The nested :style form carries the same props; keyword values on a
        ;; resolvable prop are token refs, anything else is a literal. Nested
        ;; state/bp/part maps are walked one level, matching style-map->tokens.
        ;; `:style` is a map in every EDN source; in Clojure source it can be a
        ;; non-literal expression, which is walked as a plain form and carries no
        ;; readable declaration. Guarding on map? keeps that from destructuring a
        ;; symbol — and the enclosing node is still reported as an opaque subtree.
        from-style
        (for [[k v] (when (map? (:style node)) (:style node))
              :when (contains? prop->role k)]
          (decl-of (if (keyword? v)
                     {:prop k :ref v}
                     {:prop k :value v})))]
    (vec (concat from-class (keep identity from-style)))))

(defn- applies-at?
  "Does a declaration apply at composite index `idx` in state `st`?"
  [decl idx st]
  (and (= (:state decl) st)
       (>= (long idx) (long (if-let [bp (:bp decl)] (style-props/bp-min-index bp 0) 0)))))

(defn- winner
  "The declaration of `role` in force at (idx, st) for one node, or nil.
   Precedence mirrors `expand/expand->variants`: the HIGHEST matching tier wins
   regardless of class-string order, ties keep declaration order (last wins)."
  [decls role idx st]
  (->> decls
       (filter #(and (= role (:role %)) (applies-at? % idx st)))
       (sort-by (fn [d] (if-let [bp (:bp d)] (style-props/bp-min-index bp 0) 0)))
       last))

(defn resolve-end
  "Walk `chain` (node's own decls FIRST, then each ancestor outward) for the
   first node declaring `role` at (idx, st). A state-scoped declaration wins
   over the default group ON THE SAME NODE — LVGL's state cascade — so each
   node is asked for `st` before it is asked for the default.
   Returns {:decl … :depth n} or nil."
  [chain role idx st]
  (first (keep-indexed (fn [i decls]
                         (when-let [d (or (when st (winner decls role idx st))
                                          (winner decls role idx nil))]
                           {:decl d :depth i}))
                       chain)))

(def text-bearing-props
  "Node keys whose presence means the node PUTS GLYPHS ON SCREEN, so an ink
   declaration reaching it is actually drawn. Deliberately narrow: authored
   text content, not a class list of widgets that MIGHT draw. A widget that
   draws text no key here names is an under-report, and the totality check
   below is what makes such a gap loud instead of silent."
  [:text :options])

(defn text-bearing? [node]
  (or (some #(contains? node %) text-bearing-props)
      (some (fn [[_ v]] (and (map? v) (some #(contains? v %) text-bearing-props)))
            node)))

(defn literal-children
  "The children of `node` that are literal widget maps. Returns
   [children opaque?] where `opaque?` is true when `:children` is PRESENT but
   not a literal sequence of maps — a Clojure-source tree whose children are
   built by a function call or bound to a local. That subtree is unreachable
   structurally, and saying so is the third answer; silently treating it as
   childless is the under-report this probe exists to refuse."
  [node]
  (if-not (contains? node :children)
    [[] false]
    (let [ch (:children node)]
      (if (and (sequential? ch) (every? map? ch))
        [ch false]
        [(filter map? (when (sequential? ch) ch)) true]))))

(defn walk-tree
  "Depth-first walk emitting one record per text-bearing node, plus a record of
   every ink declaration seen (so an ink that reaches no glyph can be reported),
   plus a record wherever a subtree could not be reached literally.
   `chain` is innermost-first."
  [class-defs node chain]
  (let [decls (node-decls class-defs node)
        chain' (cons decls chain)
        [kids opaque?] (literal-children node)]
    (concat [{:kind :decls :decls decls :node node}]
            (when (text-bearing? node) [{:kind :glyph :chain chain' :node node}])
            (for [d decls] {:kind :decl :decl d :node node})
            (when opaque? [{:kind :opaque-subtree :node node}])
            (mapcat (fn [child] (walk-tree class-defs child chain')) kids))))

;; ── Authored-tree loaders ────────────────────────────────────────────────────
(defn load-edn [path]
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/file path)))] (edn/read rdr)))

(defn- join-class-vectors
  "The [\"flex\" \"w-48\"] authoring form → the canonical class string, exactly
   as `lvgl-codegen.core` normalises before component resolution."
  [form]
  (walk/postwalk (fn [f] (if (and (map? f) (vector? (:class f)))
                           (update f :class #(str/join " " %))
                           f))
                 form))

(def clj-screen-wrappers
  "DECLARED ADAPTER for the Clojure-defined visual-regression fixtures. Those
   trees are literal maps, but their SCREEN ROOT is supplied by a helper call
   rather than written out — `(make-screen {…})` wraps the argument in a root
   whose class is the string below. Without this the root's fill is invisible to
   the walk and every fixture label would report an undeclared background.

   This is the one place the probe knows a function by name, and it is a
   liability: rename the helper or change its root class and the adapter goes
   stale. Two things keep that from being SILENT — an unknown wrapper leaves the
   inner tree walked with no root (so the fills go missing loudly, as
   :no-declared-fill findings), and the totality check below fails on any colour
   class token in the file the structural walk never visited."
  {'make-screen "w-pct-100 h-pct-100 bg-surface-0"
   'make-screen-raw nil})

(defn read-clj-forms
  "Every top-level form of a Clojure source file, READ but never evaluated
   (`*read-eval*` false). The fixture trees are literal data inside `def`
   forms; reading is how they are reached without dragging the protobuf emitter
   and the compiled bindings onto this probe's classpath."
  [path]
  (binding [*read-eval* false]
    (with-open [rdr (java.io.PushbackReader. (io/reader (io/file path)))]
      (loop [acc []]
        (let [form (read {:eof ::eof :read-cond :allow} rdr)]
          (if (= form ::eof) acc (recur (conj acc form))))))))

(defn clj-trees
  "Widget trees recovered from read (never evaluated) Clojure forms.

   Two resolutions, both structural: a top-level `(def sym <literal>)` binds
   `sym` so a tree referenced by name is substituted, and a wrapper call listed
   in `clj-screen-wrappers` is rebuilt as its root. Everything else is walked as
   plain nested maps — a map carrying `:tag` is a node wherever it sits."
  [forms]
  (let [defs (into {} (keep (fn [f]
                              (when (and (seq? f) (= 'def (first f)))
                                (let [[_ & r] f
                                      nm (first (filter symbol? r))
                                      v (last r)]
                                  (when (and nm (or (map? v) (vector? v))) [nm v]))))
                            forms))
        subst (fn subst [x]
                (walk/prewalk (fn [f]
                                (cond (and (symbol? f) (contains? defs f)) (get defs f)
                                      (and (seq? f)
                                           (contains? clj-screen-wrappers (first f)))
                                      (let [root-class (get clj-screen-wrappers (first f))
                                            arg (second f)
                                            kids (if (vector? arg) arg [arg])]
                                        (if root-class
                                          {:tag :lv_obj :class root-class :children kids}
                                          arg))
                                      :else f))
                              x))
        resolved (subst forms)
        roots (atom [])]
    (walk/postwalk (fn [f]
                     (when (and (map? f) (:tag f) (or (:class f) (:children f)))
                       (swap! roots conj f))
                     f)
                   resolved)
    ;; Keep only MAXIMAL nodes — a child collected on its own would be walked a
    ;; second time with an empty ancestor chain and report a phantom missing fill.
    (let [all @roots
          descend (fn descend [n] (first (literal-children n)))
          inner (into #{} (mapcat (fn [n] (rest (tree-seq (constantly true) descend n))) all))]
      (vec (remove inner all)))))

;; ── Totality: every colour class token in a declared source must be visited ──
(defn textual-colour-classes
  "Every `text-<tok>` / `bg-<tok>` class token in a file's raw text whose tail
   is a COLOUR token. Restricting to the manifest's own colour vocabulary is
   what keeps this from matching Clojure identifiers like `text-free-classes`."
  [^String src colour-toks]
  (into #{} (keep (fn [[_ pfx tail]]
                    (let [k (keyword tail)]
                      (when (contains? colour-toks k)
                        [(if (= "text" pfx) :ink :fill) k])))
                  (re-seq #"\b(text|bg)-([a-z0-9]+(?:-[a-z0-9]+)*)" src))))

;; ── Pair assembly ────────────────────────────────────────────────────────────
(defn- end-key [decl] (if (:token decl) [:token (:token decl)] [:literal (:literal decl)]))

(defn- end-hex [tokens decl mode]
  (if-let [t (:token decl)] (token-hex tokens t mode) (:literal decl)))

(defn- end-label [decl]
  (if-let [t (:token decl)] (name t) (str/upper-case (:literal decl))))

(defn- opa-value
  "The 0-255 value of an opacity declaration, or nil when it cannot be resolved.
   A token resolves through the manifest (opacity tokens are mode-invariant in
   this manifest, and the probe asserts that rather than assuming it); a literal
   is its own value."
  [tokens decl]
  (if-let [t (:token decl)]
    (let [d (get-in tokens [t :dark]) l (get-in tokens [t :light])]
      (when (and (number? d) (= d l)) d))
    (when (number? (:literal decl)) (:literal decl))))

(defn- opa-at
  "The opacity value of `role` in force on one node at (idx, st), or nil when
   the node declares none. State-scoped first, then the default group — the same
   cascade `resolve-end` applies to colour, so a `disabled:` fade cannot mark the
   default state as composited."
  [tokens decls role idx st]
  (when-let [d (or (when st (winner decls role idx st)) (winner decls role idx nil))]
    (opa-value tokens d)))

(defn- transparent-fill?
  "Does this node declare a fill that is not painted at all? `bg_opa` 0 means the
   MAIN rect draws nothing, so the colour under it is what a glyph actually sits
   on — treating such a node as the fill provider would report a pair that is
   never drawn."
  [tokens decls idx st]
  (= 0 (opa-at tokens decls :fill-opa idx st)))

(defn resolve-fill
  "`resolve-end` for the fill role, skipping any node whose fill is declared
   fully transparent. Kept separate from the ink walk because only the fill has
   a paint-or-not question — `text_opa` is not read here and the probe does not
   claim to."
  [tokens chain idx st]
  (first (keep-indexed
          (fn [i decls]
            (when-let [d (or (when st (winner decls :fill idx st))
                             (winner decls :fill idx nil))]
              (when-not (transparent-fill? tokens decls idx st)
                {:decl d :depth i})))
          chain)))

(defn- composite-note
  "Which end(s) of this pair the sources also fade, or nil. `:fill-opa` sits on
   the node that declares the fill and fades ONLY the fill; `:layer-opa` folds
   into `layer->opa` (lv_refr.c `lv_obj_refr`) and re-composites the glyph AND
   the fill together, so it counts anywhere between the glyph and its fill.
   A fade of 0 is not reported here — that node was already skipped as a
   non-painting fill."
  [tokens chain ink fill idx st]
  (let [partial? (fn [decls role]
                   (when-let [v (opa-at tokens decls role idx st)]
                     (< 0 (long v) 255)))
        f (partial? (nth chain (:depth fill) nil) :fill-opa)
        l (some #(partial? % :layer-opa) (take (inc (max (long (:depth fill))
                                                         (long (:depth ink))))
                                               chain))]
    (cond (and f l) "fill-opa+layer-opa" l "layer-opa" f "fill-opa")))

(defn glyph-pairs
  "One text-bearing node's records → pairs and findings, over every (state,
   composite index) the node's own chain actually declares something at. Only
   combinations with a real declaration are enumerated: inventing the rest is
   the cross-product error one axis down."
  [tokens source rec]
  (let [chain (vec (:chain rec))
        all (apply concat chain)
        idxs (into (sorted-set 0)
                   (keep (fn [d] (when (:bp d) (style-props/bp-min-index (:bp d) 0))) all))
        sts (into #{nil} (keep :state all))]
    (for [idx idxs
          st sts
          :let [ink (resolve-end chain :ink idx st)
                fill (resolve-fill tokens chain idx st)]]
      (cond
        (nil? ink) {:finding :no-declared-ink :source source :idx idx :state st
                    :text (:text (:node rec))}
        (nil? fill) {:finding :no-declared-fill :source source :idx idx :state st
                     :ink (end-label (:decl ink)) :text (:text (:node rec))}
        :else {:pair [(end-key (:decl ink)) (end-key (:decl fill))]
               :ink (:decl ink) :fill (:decl fill)
               :source source :idx idx :state st
               :composite (composite-note tokens chain ink fill idx st)
               :provenance (if (and (zero? (long (:depth ink))) (zero? (long (:depth fill))))
                             :self
                             :ancestor)}))))

(defn theme-pairs
  "Theme style blocks → pairs plus one-ended findings. A block declaring both
   ends yields the cross of its own two sets (a block naming two inks and one
   fill genuinely declares both inks over that fill); a block declaring one end
   yields a finding naming which end is missing."
  [styles]
  (mapcat (fn [{:keys [style ink fill]}]
            (cond (and (seq ink) (seq fill))
                  (for [i ink f fill]
                    {:pair [[:token i] [:token f]]
                     :ink {:token i} :fill {:token f}
                     :source :theme-style :idx 0 :state nil
                     :provenance :theme-style :style style})
                  (seq ink)
                  [{:finding :theme-style-ink-only :source :theme-c
                    :style style :ink (vec (sort ink))}]
                  (seq fill)
                  [{:finding :theme-style-fill-only :source :theme-c
                    :style style :fill (vec (sort fill))}]
                  :else nil))
          styles))

;; ── Assembly over all sources ────────────────────────────────────────────────
(defn collect
  "Every record from every declared source. Returns
   {:records [...] :findings [...] :visited {source #{[role token]}}}.
   Takes no token map on purpose: collection is purely structural, and only the
   ARITHMETIC downstream needs concrete values."
  []
  (let [comps-file (load-edn (at components-file))
        class-defs (:class-defs comps-file)
        components (component/load-components (:components comps-file))
        expand-screen (fn [scr] (component/resolve-components components (join-class-vectors scr)))
        ;; VOCABULARY: each class macro is a declaration unit with no tree, and
        ;; each component template is a (small) tree of its own.
        vocab-nodes (concat (for [[k v] class-defs] {:tag :lv_obj :class v ::macro k})
                            (for [[_ v] (:components comps-file)] (:tree v)))
        screens (->> (.listFiles (io/file (at screens-dir)))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
                     (sort-by #(.getName ^java.io.File %)))
        clj-forms (read-clj-forms (at vr-fixtures-file))
        sources
        (concat
         (for [n vocab-nodes] [:vocabulary n])
         (for [f screens] [(keyword (str/replace (.getName ^java.io.File f) #"\.edn$" ""))
                           (:tree (expand-screen (join-class-vectors (load-edn f))))])
         (for [t (clj-trees clj-forms)] [:vr-fixtures t]))
        recs (mapcat (fn [[src tree]]
                       (map #(assoc % :source src) (walk-tree class-defs tree nil)))
                     sources)
        ;; VISITED is computed from every node the walk REACHED, not from the
        ;; pairs it produced — so "a token the walk never saw" (a coverage gap)
        ;; stays distinguishable from "a token the walk saw and correctly made no
        ;; pair from" (a determination). Collapsing the two is what would let
        ;; this probe report clean on a source it never opened.
        visited (reduce (fn [acc r]
                          (reduce (fn [a d]
                                    (if (:token d)
                                      (update a (:source r) (fnil conj #{})
                                              [(:role d) (:token d)])
                                      a))
                                  acc
                                  (:decls r)))
                        {}
                        (filter #(= :decls (:kind %)) recs))]
    {:records recs
     :visited visited
     :class-defs class-defs
     :theme (parse-theme-styles (slurp (at theme-c-file)))}))

(defn analyse
  "The whole derivation: pairs (token and literal, split) + findings."
  [tokens]
  (let [{:keys [records visited theme]} (collect)
        glyph-recs (filter #(= :glyph (:kind %)) records)
        decl-recs (filter #(= :decl (:kind %)) records)
        raw (concat (theme-pairs theme)
                    (mapcat (fn [r] (glyph-pairs tokens (:source r) r)) glyph-recs))
        pairs (remove :finding raw)
        findings (filter :finding raw)
        ;; A declaration that never became one end of a pair: an ink no glyph
        ;; carries, or a fill no glyph sits on. Both are real determinations
        ;; rather than gaps — `bg-fg-0` in the VR fixtures is a foreground token
        ;; used as a FILL with nothing written on it, which is exactly how the
        ;; cross product's FG/BG split is wrong AND why it still yields no pair.
        used (into #{} (mapcat (fn [p] [[:ink (end-key (:ink p))] [:fill (end-key (:fill p))]]))
                   pairs)
        orphan-decls (->> decl-recs
                          (filter (fn [r] (#{:ink :fill} (:role (:decl r)))))
                          (remove (fn [r] (contains? used [(:role (:decl r))
                                                           (end-key (:decl r))])))
                          (map (fn [r] {:finding (if (= :ink (:role (:decl r)))
                                                   :ink-reaches-no-glyph
                                                   :fill-carries-no-glyph)
                                        :source (:source r)
                                        :colour (end-label (:decl r))
                                        :where (or (::macro (:node r)) (:tag (:node r)))}))
                          distinct)
        opaque (->> records
                    (filter #(= :opaque-subtree (:kind %)))
                    (map (fn [r] {:finding :subtree-not-literal
                                  :source (:source r)
                                  :where (:tag (:node r))
                                  :class (:class (:node r))}))
                    distinct)
        ;; Totality: a colour class token present in a source file's TEXT that
        ;; the structural walk never visited is coverage this probe does not
        ;; have, and it is printed rather than dropped.
        colour-toks (colour-tokens tokens)
        file-sources {:vocabulary (at components-file)
                      :vr-fixtures (at vr-fixtures-file)}
        screen-files (into {} (for [f (.listFiles (io/file (at screens-dir)))
                                    :when (str/ends-with? (.getName ^java.io.File f) ".edn")]
                                [(keyword (str/replace (.getName ^java.io.File f) #"\.edn$" ""))
                                 (.getPath ^java.io.File f)]))
        unvisited (for [[src path] (merge file-sources screen-files)
                        :let [textual (textual-colour-classes (slurp path) colour-toks)
                              seen (get visited src #{})]
                        miss (sort (remove seen textual))]
                    {:finding :class-token-never-visited :source src
                     :role (first miss) :token (second miss) :file path})]
    {:pairs pairs
     :findings (concat findings orphan-decls opaque unvisited)}))

;; ── Reporting ────────────────────────────────────────────────────────────────
(def ^:private idx->bp
  (into {} (for [[k v] style-props/bp-min-index] [v k])))

(defn- ctx-label [p]
  (str/join "+" (remove nil? [(some-> (:state p) name)
                              (some-> (idx->bp (:idx p)) name)])))

(defn- fmt-ratio [^double r] (format "%.2f" r))

(defn summarise
  "Pairs → one row per distinct (ink, fill, mode), with the contexts that
   declare it folded together. A pair declared five times is ONE row: the table
   is about the pair, and the contexts are its evidence."
  [tokens pairs]
  (->> pairs
       (mapcat (fn [p]
                 (for [mode [:dark :light]]
                   (let [ih (end-hex tokens (:ink p) mode)
                         fh (end-hex tokens (:fill p) mode)]
                     {:ink (end-label (:ink p)) :fill (end-label (:fill p))
                      :mode mode :ink-hex ih :fill-hex fh
                      :ratio (when (and (hex->rgb ih) (hex->rgb fh))
                               (contrast (hex->rgb ih) (hex->rgb fh)))
                      :token-pair? (boolean (and (:token (:ink p)) (:token (:fill p))))
                      :composite (:composite p)
                      :ctx (str (name (:source p))
                                (when-let [s (:style p)] (str "/" s))
                                (let [c (ctx-label p)] (when (seq c) (str ":" c)))
                                (when-let [c (:composite p)] (str "[" c "]")))}))))
       (group-by (juxt :ink :fill :mode))
       (map (fn [[[ink fill mode] rows]]
              (let [r (first rows)]
                {:ink ink :fill fill :mode mode
                 :ink-hex (:ink-hex r) :fill-hex (:fill-hex r)
                 :ratio (:ratio r) :token-pair? (:token-pair? r)
                 ;; A pair is marked composited only when EVERY context that
                 ;; declares it fades one end. One un-faded context means the
                 ;; authored pair really is drawn somewhere, and the row's ratio
                 ;; is exact there — flagging it anyway would understate a real
                 ;; verdict, which is the same over-claim in the other direction.
                 :composite (when (every? :composite rows)
                              (str/join "/" (sort (distinct (keep :composite rows)))))
                 :ctxs (vec (sort (distinct (map :ctx rows))))})))
       (sort-by (fn [r] [(if (:ratio r) (:ratio r) -1.0) (:ink r) (:fill r) (name (:mode r))]))))

(defn- verdict [^double ratio ^double floor] (if (>= ratio floor) "PASS" "**FAIL**"))

(defn- row-line [r]
  (let [drawn (if-let [c (:composite r)] (str "composited (" c ")") "—")]
    (if-let [^double ratio (:ratio r)]
      (format "| `%s` | `%s` | %s | %s | %s | %s | %s | %s | %s |"
              (:ink r) (:fill r) (name (:mode r)) (:ink-hex r) (:fill-hex r)
              (fmt-ratio ratio) (verdict ratio wcag-aa-floor) (verdict ratio governing-floor)
              drawn)
      (format "| `%s` | `%s` | %s | %s | %s | — | UNRESOLVED | UNRESOLVED | %s |"
              (:ink r) (:fill r) (name (:mode r)) (:ink-hex r) (:fill-hex r) drawn))))

(def ^:private table-head
  (str "| ink | fill | mode | ink hex | fill hex | ratio | "
       (format "≥%.1f:1" wcag-aa-floor) " | " (format "≥%.1f:1" governing-floor)
       " | as drawn |\n"
       "|---|---|---|---|---|---:|---|---|---|"))

(defn- render-text
  "Authored text as evidence. A Clojure-source fixture can build its text from
   an expression rather than a literal; printing the unevaluated form would
   suggest a string that is never drawn, so it is named as computed instead."
  [t]
  (if (string? t) t "<computed>"))

(def ^:private finding-notes
  "One sentence per finding key saying what the derivation could not establish
   — because a bare count leaves a reader to guess whether it is a defect."
  {:no-declared-ink
   (str "A glyph whose ink NO source declares. It falls through to whatever"
        " ancestor style sets `text_color` at apply time, and which style that is"
        " belongs to `theme_apply`'s C dispatch — not to any declaration. Resolving"
        " these needs the rendered dump, not this tier.")
   :no-declared-fill
   (str "A glyph whose ink IS declared but whose fill is not — it sits on whatever"
        " the nearest painting ancestor turns out to be, including the screen"
        " itself, which this repo's screens leave transparent.")
   :ink-reaches-no-glyph
   (str "An ink declaration no text-bearing node inherits. `hud-label` is the"
        " interesting one: it is `text-accent-bg`, a BACKGROUND token authored as"
        " INK, and nothing in this repo instantiates it — so the pair a FG/BG cross"
        " product would score for it is a pair no source declares.")
   :fill-carries-no-glyph
   (str "A fill no declared ink sits on. `bg-fg-0` in the VR fixtures is the"
        " mirror of the case above — a FOREGROUND token used as a FILL, on a box"
        " with nothing written on it.")
   :theme-style-ink-only
   (str "An `lv_style_t` in the theme that sets `text_color` and no `bg_color`."
        " Its fill arrives from whichever other style `theme_apply` puts on the"
        " same object, which is a runtime co-application this probe does not model.")
   :theme-style-fill-only
   (str "An `lv_style_t` that sets `bg_color` and no `text_color` — `checked_accent`"
        " is the one to know: the DROPDOWN selected band takes its glyph colour"
        " from the STOCK parent theme, so the pair has no token on the ink side"
        " at all and is deliberately not completed from one. The ROLLER no longer"
        " belongs in that sentence: its band authors BOTH ends on its own style,"
        " which is what removed a constraint the old arm recorded as infeasible —"
        " stock sets bg and a white text_color together, so while only the fill"
        " was replaced no glyph tone could reach the floor.")
   :subtree-not-literal
   (str "A node in Clojure source whose `:children` are built by an expression"
        " rather than written as literal maps. Everything under it is unreachable"
        " by a non-evaluating read.")
   :class-token-never-visited
   (str "A colour class token present in a source file's TEXT that the structural"
        " walk never reached. This is the coverage check: it is EMPTY when the walk"
        " saw every colour class the files contain, and non-empty means this table"
        " is missing something.")})

(defn render-doc [tokens {:keys [pairs findings]}]
  (let [rows (summarise tokens pairs)
        [tok-rows lit-rows] [(filter :token-pair? rows) (remove :token-pair? rows)]
        by-finding (group-by :finding findings)]
    (str/join
     "\n"
     (concat
      ["# Proven pairs — the (ink, fill) pairs this repo declares together"
       ""
       "GENERATED — do not edit by hand. Regenerate with:"
       ""
       (str "```\n" regen-command "\n```")
       ""
       (str "Producer: `tools/devcards/dev/proven_pairs.clj`. Read its docstring for the"
            " derivation and for what it cannot see.")
       ""
       (str "Sources of co-declaration, in the order the table folds them: `"
            theme-c-file "`, `" components-file "`, `" screens-dir "/*.edn`, `"
            vr-fixtures-file "`.")
       ""
       (str "**This is not a cross product.** A pair appears only where two colour"
            " declarations are in force on the same drawn glyph —"
            " `docs/UI-QUALITY-CONTRACTS.md` §6.9 states why a declared-FG × declared-BG"
            " product is neither sound nor complete, and the producer's docstring"
            " re-derives that argument rather than citing it.")
       ""
       (str "Ratios are WCAG 2.x relative-luminance contrast, the same arithmetic"
            " `tools/devcards/dev/disabled_pair_probe.clj` measures rendered pairs with,"
            " so a declared ratio and a measured one are comparable digit for digit."
            " Verdicts are exact pass/fail on declared values — there is no noise band"
            " and no adjudicator. The two floors are WCAG AA body text ("
            (format "%.1f" wcag-aa-floor) ":1) and this repo's governing"
            " MIL-STD-1472H 5.2.2.7 floor (" (format "%.1f" governing-floor) ":1).")
       ""
       (str "**The `as drawn` column is where a ratio stops being the whole story.**"
            " `composited (fill-opa)` means every context declaring that pair also fades"
            " the FILL (the `opa-` class prefix resolves to `bg-opa`, glyphs untouched),"
            " so the rendered pair is a token ink over a blend and the row's ratio is the"
            " AUTHORED one. `layer-opa` is the whole-widget fade, which re-composites"
            " both ends. Neither composite is computed here — the exact byte depends on"
            " the SW blend path, so it has to come from the dump. A row is marked only"
            " when EVERY context fades it; one un-faded context means the authored pair"
            " really is drawn somewhere and the ratio is exact there.")
       ""
       (format "## Token pairs (%d rows, %d distinct pairs)" (count tok-rows)
               (count (distinct (map (juxt :ink :fill) tok-rows))))
       ""
       table-head]
      (map row-line tok-rows)
      [""
       "### Where each token pair is declared"
       ""
       "| ink | fill | declared at |"
       "|---|---|---|"]
      (->> tok-rows
           (group-by (juxt :ink :fill))
           (sort-by key)
           (map (fn [[[ink fill] rs]]
                  (format "| `%s` | `%s` | %s |" ink fill
                          (str/join ", " (map #(str "`" % "`")
                                              (sort (distinct (mapcat :ctxs rs)))))))))
      [""
       (format "## Non-token pairs (%d rows)" (count lit-rows))
       ""
       (str "One or both ends is a hex LITERAL rather than a declared token —"
            " a drawn colour the manifest does not declare, which"
            " `docs/UI-QUALITY-CONTRACTS.md` §6.7 calls a FACT rather than a defect."
            " Kept out of the token table because the question that table answers is"
            " about the token vocabulary.")
       ""
       table-head]
      (map row-line lit-rows)
      [""
       (format "## The third answer — %d findings this derivation could NOT classify"
               (count findings))
       ""
       (str "An unjudged element is a FINDING, never a skip: a rule that passes over what"
            " it could not classify reports \"clean\" and \"I could not look\" as the same"
            " empty result. Each key below is a distinct reason a pair does not exist or"
            " could not be completed. EVERY key is printed with its count, including the"
            " ones at zero — a section that vanished when it had nothing to say would"
            " print the same thing whether the check ran or not, which is the failure this"
            " whole section exists to refuse.")
       ""]
      (mapcat
       (fn [[k rs]]
         (concat [(format "### `%s` — %d" (name k) (count rs))
                  ""
                  (get finding-notes k "")
                  ""
                  (str "By source: "
                       (str/join ", " (for [[src n] (sort-by key (frequencies (map :source rs)))]
                                        (format "`%s` %d" (some-> src name) n))))
                  ""]
                 (map (fn [r] (str "- " (pr-str (-> (dissoc r :finding)
                                                    (cond-> (contains? r :text)
                                                      (update :text render-text))))))
                      (sort-by pr-str rs))
                 [""]))
       ;; Seed every declared key at zero so a silent check is still VISIBLE.
       (sort-by key (merge (zipmap (keys finding-notes) (repeat [])) by-finding)))
      [""]))))

;; ── Self-test: the derivation's own canaries ─────────────────────────────────
;; Each asserts ONE clause and names it on failure. Mutate a clause, run this,
;; and only that clause's canary must go red — that attribution is the point.
(def ^:private ^:dynamic *assertions* nil)

(defn- check [clause expected actual]
  (swap! *assertions* update :n inc)
  (when-not (= expected actual)
    (swap! *assertions* update :fail conj
           (format "FAIL [%s] expected %s got %s" clause (pr-str expected) (pr-str actual)))))

(defn- check-close [clause ^double expected ^double actual]
  (swap! *assertions* update :n inc)
  (when-not (< (abs (- expected actual)) 0.005)
    (swap! *assertions* update :fail conj
           (format "FAIL [%s] expected ~%s got %s" clause expected actual))))

(defn self-test []
  (binding [*assertions* (atom {:n 0 :fail []})]
    ;; CLAUSE: contrast arithmetic. Anchored on WCAG's own algebraic extremes,
    ;; not on any value this repo states, so the anchors cannot drift with it.
    (check-close "contrast/black-white" 21.0 (contrast [0 0 0] [255 255 255]))
    (check-close "contrast/identity" 1.0 (contrast [18 18 31] [18 18 31]))
    (check-close "contrast/symmetric"
                 (contrast [0 0 0] [124 58 237])
                 (contrast [124 58 237] [0 0 0]))
    ;; CLAUSE: hex parsing refuses what it cannot parse rather than yielding black.
    (check "hex/bad" nil (hex->rgb "#xyzxyz"))
    (check "hex/short" nil (hex->rgb "#FFF"))
    (check "hex/hash" [124 58 237] (hex->rgb "#7C3AED"))
    (check "hex/0x" [124 58 237] (hex->rgb "0x7C3AED"))
    ;; CLAUSE: a class that is ONLY placeholders empties under $-removal and
    ;; must collapse to no-class, never reach the parser as an empty token.
    ;; The red half is measured, not staged: a component template with
    ;; `:class "$class"` crashed the whole vocabulary walk ("Unknown class
    ;; token: ''") until the blank-collapse landed.
    (check "class/placeholder-only" [] (node-decls {} {:class "$class"}))
    (check "class/placeholder-mixed" :surface-1
           (:token (:decl (resolve-end [(node-decls {} {:class "$cls bg-surface-1"})]
                                       :fill 0 nil))))
    ;; CLAUSE: breakpoint precedence — HIGHEST matching tier wins regardless of
    ;; class-string order, and a tier does not apply below its own min index.
    (let [decls (node-decls {} {:class "xl:bg-surface-0 md:bg-surface-2 bg-surface-1"})
          at-idx (fn [i] (:token (:decl (resolve-end [decls] :fill i nil))))]
      (check "bp/base-below-md" :surface-1 (at-idx 0))
      (check "bp/md-wins-at-md" :surface-2 (at-idx (style-props/bp-min-index :md)))
      (check "bp/md-still-wins-at-lg" :surface-2 (at-idx (style-props/bp-min-index :lg)))
      (check "bp/xl-wins-at-xl" :surface-0 (at-idx (style-props/bp-min-index :xl))))
    ;; CLAUSE: state cascade — a state-scoped declaration overrides the default
    ;; group in that state and is invisible outside it.
    (let [decls (node-decls {} {:class "bg-accent-bg pressed:bg-pressed-accent"})]
      (check "state/default" :accent-bg (:token (:decl (resolve-end [decls] :fill 0 nil))))
      (check "state/pressed" :pressed-accent
             (:token (:decl (resolve-end [decls] :fill 0 :pressed))))
      (check "state/focused-falls-back" :accent-bg
             (:token (:decl (resolve-end [decls] :fill 0 :focused)))))
    ;; CLAUSE: ancestor resolution — the NEAREST declaration wins, and an
    ;; ancestor's fill reaches a descendant's ink.
    (let [outer (node-decls {} {:class "bg-surface-1 text-fg-1"})
          inner (node-decls {} {:class "text-fg-0"})]
      (check "ancestor/nearest-ink" :fg-0 (:token (:decl (resolve-end [inner outer] :ink 0 nil))))
      (check "ancestor/inherited-fill" :surface-1
             (:token (:decl (resolve-end [inner outer] :fill 0 nil))))
      (check "ancestor/absent" nil (resolve-end [inner] :fill 0 nil)))
    ;; CLAUSE: a fill declared FULLY TRANSPARENT is not a fill. The node still
    ;; names a colour; it just paints none of it, so the walk must pass through
    ;; to the ancestor that does. Reporting the skipped colour would be a pair
    ;; that is never drawn.
    (let [toks {:surface-1 {:kind :color :dark "#12121F" :light "#E0E0D4"}
                :surface-0 {:kind :color :dark "#0A0A12" :light "#F0F0E8"}
                :zero-opa {:kind :opacity :dark 0 :light 0}
                :overlay-opa {:kind :opacity :dark 200 :light 200}
                :full-opa {:kind :opacity :dark 255 :light 255}}
          ghost (node-decls {} {:class "bg-surface-1 opa-zero-opa"})
          root (node-decls {} {:class "bg-surface-0"})]
      (check "transparent/skips-unpainted-fill" :surface-0
             (:token (:decl (resolve-fill toks [ghost root] 0 nil))))
      (check "transparent/opaque-fill-still-wins" :surface-1
             (:token (:decl (resolve-fill toks
                                          [(node-decls {} {:class "bg-surface-1 opa-full-opa"})
                                           root]
                                          0 nil))))
      ;; CLAUSE: a PARTIAL fade marks the row composited; a full one does not;
      ;; and a state-scoped fade does not reach the default state.
      (let [faded (node-decls {} {:class "bg-surface-1 opa-overlay-opa"})
            st-faded (node-decls {} {:class "bg-surface-1 disabled:opa-overlay-opa"})
            ink {:depth 0}]
        (check "composite/partial-fill-opa" "fill-opa"
               (composite-note toks [faded] ink {:depth 0} 0 nil))
        (check "composite/full-is-not-composited" nil
               (composite-note toks [(node-decls {} {:class "bg-surface-1 opa-full-opa"})]
                               ink {:depth 0} 0 nil))
        (check "composite/state-scoped-misses-default" nil
               (composite-note toks [st-faded] ink {:depth 0} 0 nil))
        (check "composite/state-scoped-hits-its-state" "fill-opa"
               (composite-note toks [st-faded] ink {:depth 0} 0 :disabled))
        (check "composite/layer-opa-from-style" "layer-opa"
               (composite-note toks
                               [(node-decls {} {:class "bg-surface-1" :style {:opa 128}})]
                               ink {:depth 0} 0 nil))))
    ;; CLAUSE: class-macro expansion reaches the tokens inside a macro.
    (let [decls (node-decls {:tactical-btn "bg-accent-bg text-accent-text"}
                            {:class "@tactical-btn"})]
      (check "macro/fill" :accent-bg (:token (:decl (resolve-end [decls] :fill 0 nil))))
      (check "macro/ink" :accent-text (:token (:decl (resolve-end [decls] :ink 0 nil)))))
    ;; CLAUSE: theme.c block parsing — the BLOCK is the unit, a stock literal is
    ;; not a token, and a one-ended block declares one end only.
    (let [src (str "style_reset(&s->alpha, inited);\n"
                   "lv_style_set_bg_color(&s->alpha, lv_color_hex(pick_u32(t->dark,"
                   " THEME_SURFACE1_DARK, THEME_SURFACE1_LIGHT)));\n"
                   "lv_style_set_text_color(&s->alpha, pick_color(v, lv_color_hex(0x282b30),"
                   " lv_color_hex(pick_u32(t->dark, THEME_FG0_DARK, THEME_FG0_LIGHT))));\n"
                   "style_reset(&s->beta, inited);\n"
                   "lv_style_set_bg_color(&s->beta, lv_color_hex(pick_u32(t->dark,"
                   " THEME_CHECKED_DARK, THEME_CHECKED_LIGHT)));\n")
          parsed (parse-theme-styles src)
          by-name (into {} (map (juxt :style identity)) parsed)]
      (check "theme/block-count" 2 (count parsed))
      (check "theme/alpha-fill" #{:surface-1} (:fill (by-name "alpha")))
      (check "theme/alpha-ink" #{:fg-0} (:ink (by-name "alpha")))
      (check "theme/stock-literal-is-not-a-token" #{:fg-0} (:ink (by-name "alpha")))
      (check "theme/beta-one-ended" #{} (:ink (by-name "beta")))
      (check "theme/no-cross-block-leak" #{:checked-accent} (:fill (by-name "beta")))
      (check "theme/one-ended-is-a-finding" [:theme-style-fill-only]
             (mapv :finding (filter :finding (theme-pairs parsed)))))
    ;; CLAUSE: totality — a colour class token present in text is matched, and a
    ;; Clojure identifier that merely LOOKS like one is not.
    (let [toks #{:fg-0 :surface-1}]
      (check "totality/finds-ink" #{[:ink :fg-0]}
             (textual-colour-classes "\"text-fg-0 font-font-body\"" toks))
      (check "totality/finds-fill" #{[:fill :surface-1]}
             (textual-colour-classes "bg-surface-1" toks))
      (check "totality/ignores-non-token" #{}
             (textual-colour-classes "text-free-classes bg-opa text-heavy" toks)))
    ;; CLAUSE: text-bearing detection — authored text content, including text
    ;; carried inside a props sub-map.
    (check "text/plain" true (boolean (text-bearing? {:tag :lv_label :text "x"})))
    (check "text/nested-options" true
           (boolean (text-bearing? {:tag :lv_roller :roller_props {:options "a\nb"}})))
    (check "text/none" false (boolean (text-bearing? {:tag :lv_obj :class "bg-surface-1"})))
    (let [{:keys [n fail]} @*assertions*]
      (println (format "proven-pairs self-test: %d assertions, %d failures" n (count fail)))
      (doseq [f fail] (println " " f))
      (when (seq fail) (println "MUTATION/REGRESSION DETECTED — a clause above is broken."))
      {:assertions n :failures (count fail)})))

;; ── Entry point ──────────────────────────────────────────────────────────────
(defn -main [& args]
  (if (some #{"--self-test"} args)
    (let [{:keys [failures]} (self-test)] (System/exit (if (zero? (long failures)) 0 1)))
    (let [tokens (load-tokens (at tokens-manifest))
          result (analyse tokens)
          rows (summarise tokens (:pairs result))
          tok-rows (filter :token-pair? rows)
          below (filter #(and (:ratio %) (< (double (:ratio %)) wcag-aa-floor)) tok-rows)
          below-gov (filter #(and (:ratio %) (< (double (:ratio %)) governing-floor)) tok-rows)]
      (io/make-parents (at output-doc))
      (spit (at output-doc) (render-doc tokens result))
      (println "wrote" (at output-doc))
      (println (format "token-pair rows: %d (%d distinct pairs)"
                       (count tok-rows)
                       (count (distinct (map (juxt :ink :fill) tok-rows)))))
      (println (format "non-token rows:  %d" (- (count rows) (count tok-rows))))
      (println (format "below %.1f:1 (WCAG AA):        %d" wcag-aa-floor (count below)))
      (println (format "below %.1f:1 (governing floor): %d" governing-floor (count below-gov)))
      (doseq [r below]
        (println (format "  FAIL %s on %s (%s) = %s:1"
                         (:ink r) (:fill r) (name (:mode r)) (fmt-ratio (:ratio r)))))
      (println (format "findings (could not classify): %d" (count (:findings result))))
      (doseq [[k rs] (sort-by key (group-by :finding (:findings result)))]
        (println (format "  %-28s %d" (name k) (count rs)))))))
