(ns lvgl-codegen.pdl-t0
  "The PDL-1 T0 INPUT SURFACE — parsed from the interpreter, never mirrored.

   ═══ WHAT THIS IS FOR ═══

   A T0 clause is a statement about a quantity plus a floor to judge it
   against. Whether this repository can EXERCISE such a clause is not a matter
   of opinion: it holds iff every input the clause names is actually present in
   the interpreter's own output surface, and iff the floor has a source in this
   tree. Both halves were being carried as prose, and prose about a C file
   rots the moment the C file moves.

   So this namespace answers the question mechanically. It PARSES the two
   surfaces a T0 clause can draw on —

     * the key vocabulary `dump_obj` emits (renderer/src/main.c), and
     * the symbols the LINKER exports (renderer/wasm.mk) —

   joins them to the field set `lvgl-codegen.font-metrics` actually emits, and
   prices each declared clause against the result.

   `lvgl-codegen.font-metrics` set the precedent this follows and states it in
   one line: the resolution order is \"parsed, never mirrored\". A mirrored
   list is a second model that drifts silently; a parse fails loudly. Every
   input surface below is therefore derived from the file that owns it, and an
   empty parse is a THROW rather than an empty manifest — an emitter that
   shrugged at a restructured `dump_obj` would report every clause
   NOT-EXERCISABLE and read exactly like an honest negative.

   ═══ WHY THE EXPORT LIST IS THE LINKER'S AND NOT main.c's ═══

   A `controls_*` function defined in main.c that `wasm.mk` does not
   `-Wl,--export` is unreachable from any host, so a clause naming it has no
   input however plainly the C defines it. The authority for reachability is
   the link line, and that is what is read here.

   ═══ THE FLAT KEY SET, SAID OUT LOUD ═══

   `dump_obj` nests: `text_on` carries its own `part` / `color` / `bg` /
   `text_opa` / `bg_opa` members. This parse recovers the set of key NAMES
   emitted anywhere in the function and NOT their nesting, because the question
   a clause asks is whether the interpreter reports a quantity at all. A clause
   that needs a nested member declares the TOP-LEVEL key that carries it.

   ═══ THE BOUNDARY IS dump_obj's BODY, AND ONE KEY LIVES OUTSIDE IT ═══

   The cut is the FUNCTION, not the file, so a key emitted by one of
   `dump_obj`'s CALLERS is not in this vocabulary. There is exactly one today
   and it is the one most likely to be reached for: `truncated`. Its sentinel
   is written by `controls_dump_tree` AFTER the walk returns, because a flooded
   buffer has no room to append and the sentinel must OVERWRITE the tail. So a
   clause naming `truncated` as a `:dump-key` would price NOT-EXERCISABLE
   against a tree that does emit it.

   That is recorded rather than repaired, and the reason is the same one that
   makes the cut right: widening the parse to the whole file would sweep in
   `tree_append_draw_palette`'s keys (`records`, `colors`, `hex`,
   `theme_recolor`, `overflow`), which are NOT tree keys at all — the palette
   is a property of the ROUTE to a screen and rides its own export. A parse
   that pooled them would report a palette key as available on the tree
   surface, which is a wrong answer in the direction that reads as coverage.
   Narrow-and-named beats wide-and-wrong; if a clause ever needs `truncated`,
   give it its own kind rather than widening this one.

   ═══ WHAT AN ANSWER HERE DOES NOT MEAN ═══

   `:exercisable` means THE INPUTS EXIST. It is not a verdict, not a
   producer, and not a claim that a lane is armed — protogen ships no
   readability lane, and `docs/UI-QUALITY-CONTRACTS.md` §0 says so. Reading
   `:exercisable` as \"this clause passes\" would be the over-claim that
   document refuses everywhere else.

   ═══ ENTRY POINTS ═══

     (input-surface {:repo-root \"../..\"})   ; the parsed inputs, as data
     (t0-manifest {:repo-root \"../..\" :tokens <tokens map>})
     clojure -M -m lvgl-codegen.pdl-t0 [--repo-root D] [--tokens F] [--output F]

   `--output` is optional; with no destination the manifest goes to stdout. No
   renderer.mk lane consumes a committed artifact from this generator, so it
   does not invent one."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [lvgl-codegen.font-metrics :as fm]
            [malli.core :as m])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def repo-root-default
  "Repo root relative to the tool root (tools/renderer-gen), which is the CWD
   every renderer.mk lane and the kaocha suite run from."
  fm/repo-root-default)

(def tokens-default "edn/tokens.edn")

(def main-c-path
  "The one home of the dump key vocabulary. Parsed, never mirrored."
  "renderer/src/main.c")

(def wasm-mk-path
  "The one home of the export surface: the linker's own `--export` list."
  "renderer/wasm.mk")

(def ^:private dump-obj-open
  "The exact signature line that opens the tree walker. A rename here fails
   generation rather than silently emitting an empty key vocabulary."
  "static void dump_obj(const lv_obj_t *obj, bool is_root) {")

(defn- at
  "Repo-root-relative File."
  ^File [repo-root & parts]
  (reduce (fn [^File acc p] (io/file acc (str p))) (io/file (str repo-root)) parts))

;; ── surface 1: the dump key vocabulary ──────────────────────────────────────

(defn dump-obj-body
  "The text of `dump_obj`'s body: from its signature to the first column-0
   closing brace. Same cut `lvgl-codegen.font-metrics` takes over
   `resolve_font`, for the same reason — the function, not the file."
  [repo-root]
  (let [f (at repo-root main-c-path)]
    (when-not (.isFile f)
      (throw (ex-info "main.c not found — cannot derive the dump key vocabulary"
                      {:path (.getPath f) :repo-root (str repo-root)})))
    (let [txt (slurp f)
          start (str/index-of txt dump-obj-open)]
      (when-not start
        (throw (ex-info (str "dump_obj signature not found in main.c — the tree "
                             "walker moved and this generator must be re-pointed, "
                             "not silently emptied")
                        {:path (.getPath f) :expected dump-obj-open})))
      (let [end (str/index-of txt "\n}" start)]
        (when-not end
          (throw (ex-info "dump_obj body has no column-0 closing brace"
                          {:path (.getPath f)})))
        (subs txt start end)))))

(def ^:private json-key-re
  "A `\\\"key\\\":` pair inside a C string literal — how `dump_obj` writes every
   key it formats inline."
  #"\\\"([a-z_][a-z0-9_]*)\\\":")

(def ^:private helper-key-re
  "`tree_append_color(\"key\", …)` / `tree_append_opa(\"key\", …)` — the two
   helpers that take the key as an ARGUMENT, so it never appears in a format
   string. A parse that read only format strings would miss every colour and
   opacity key, i.e. exactly the quantities a contrast clause needs, and would
   report them absent."
  #"tree_append_(?:color|opa)\(\s*\"([a-z_][a-z0-9_]*)\"")

(defn dump-key-vocabulary
  "The set of key names `dump_obj` can emit, parsed from its body.

   FLAT — see the namespace docstring. Both patterns are REQUIRED to hit: a
   zero result from either means the emitter changed shape, and an emitter that
   shrugged would price every clause NOT-EXERCISABLE and read like an honest
   negative."
  [repo-root]
  (let [body (dump-obj-body repo-root)
        inline (into #{} (map second) (re-seq json-key-re body))
        helper (into #{} (map second) (re-seq helper-key-re body))]
    (when (empty? inline)
      (throw (ex-info (str "dump_obj emits no inline JSON key — the format-string "
                           "convention changed and this parse is blind")
                      {:pattern (str json-key-re)})))
    (when (empty? helper)
      (throw (ex-info (str "dump_obj makes no tree_append_color/opa call — the "
                           "resolved-style block moved and every colour and "
                           "opacity key would be reported absent")
                      {:pattern (str helper-key-re)})))
    (into (sorted-set) (concat inline helper))))

;; ── surface 2: the linker's export list ─────────────────────────────────────

(def ^:private export-re #"-Wl,--export=([A-Za-z_][A-Za-z0-9_]*)")

(defn exported-symbols
  "Every symbol `wasm.mk` hands the linker via `--export`.

   A `controls_*` function that main.c defines and this list omits is
   unreachable from any host, so reachability is the LINK LINE's answer and not
   the C file's."
  [repo-root]
  (let [f (at repo-root wasm-mk-path)]
    (when-not (.isFile f)
      (throw (ex-info "wasm.mk not found — cannot derive the export surface"
                      {:path (.getPath f) :repo-root (str repo-root)})))
    (let [syms (into (sorted-set) (map second) (re-seq export-re (slurp f)))]
      (when (empty? syms)
        (throw (ex-info (str "wasm.mk declares no --export — the link line changed "
                             "shape and every export-backed clause would be "
                             "reported unreachable")
                        {:path (.getPath f) :pattern (str export-re)})))
      syms)))

;; ── surface 3: the emitted font-metric fields ───────────────────────────────

(def ^:private provenance-fields
  "Per-font keys that describe WHERE a record came from rather than WHAT the
   face measures. Excluded so a clause cannot satisfy a metric input by naming
   a provenance field — `:metrics-from` is a path, not a measurement."
  #{:name :family :symbol :resolution :metrics-from :generator :banner :asset
    :metrics-unavailable :declared-by :renderer-fallback})

(defn font-metric-fields
  "The METRIC field names `lvgl-codegen.font-metrics` actually emits, as a set
   of keywords.

   Derived by CALLING that emitter, never by copying its field list: a metric
   removed there must show up here as an input that no longer resolves. Only
   fields with a non-nil value on at least one font count — a key present on
   every record but null for all of them measures nothing, which is precisely
   the shape a runtime-rasterized face carries."
  [{:keys [repo-root tokens]}]
  (let [fonts (:fonts (fm/font-metrics {:repo-root repo-root :tokens tokens}))]
    (into (sorted-set)
          (comp (mapcat keys)
                (distinct)
                (remove provenance-fields)
                (filter (fn [k] (some (fn [rec] (some? (get rec k))) fonts))))
          fonts)))

;; ── the clause register ─────────────────────────────────────────────────────

(def input-kinds
  "The CLOSED set of input kinds, and the surface each resolves against.

   Closed on purpose, and an unknown kind THROWS rather than resolving to
   false: a typo must never quietly turn into a missing input, because a
   missing input reads as an honest NOT-EXERCISABLE and would retire a clause
   nobody decided to retire."
  {:dump-key "key emitted by dump_obj (renderer/src/main.c)"
   :dump-export "symbol exported by the linker (renderer/wasm.mk)"
   :font-metric "metric field emitted by lvgl-codegen.font-metrics"
   :none "a DECLARED absence — this quantity has no input in any surface"})

(def provenances
  "Where a clause's FLOOR comes from. Closed.

   `:in-tree` requires a `:source` naming the document and section that states
   the floor. `:brief` means the clause reached this register from a donation
   brief or another out-of-tree instruction and this repository holds no
   source for its number."
  {:in-tree "a floor stated by a document in this tree"
   :brief "no in-tree source — the number exists only outside this repository"})

(def clauses
  "The T0 clauses this tree can SOURCE. Deliberately not exhaustive.

   A clause is listed only when this repository states something about it. The
   register refuses to invent one: `docs/UI-QUALITY-CONTRACTS.md` §0 names the
   filling of a permanently-empty slot \"with a plausible number\" as the exact
   defect it was written to prevent, and a clause list padded to look complete
   is that defect in table form.

   Every entry declares its inputs even when the declaration is an absence
   (`:kind :none`). An empty `:inputs` vector is refused, because a clause that
   declares no input would price as exercisable by naming nothing."
  [{:id :text-contrast-floor
    :quantity "contrast ratio between a node's resolved glyph colour and the fill its glyphs ride on"
    :floor "6:1 shall / 10:1 should"
    :provenance :in-tree
    :source "docs/UI-QUALITY-CONTRACTS.md §6.2 (MIL-STD-1472H §5.2.2.7 governs; not WCAG's 4.5:1)"
    :status :in-scope
    :verdict-shape :exact
    :inputs [{:kind :dump-key :name "text_color"}
             {:kind :dump-key :name "bg_color"}
             {:kind :dump-key :name "text_on"}
             {:kind :dump-key :name "backdrop_unresolved"}
             {:kind :dump-key :name "text_opa"}
             {:kind :dump-key :name "opa"}]
    :note (str "INPUTS PRESENT IS NOT A LANE. `devcards.cascade` already joins these "
               "into one three-answer verdict per node and deliberately computes no "
               "ratio: §0 states that no readability lane ships here. What this row "
               "prices is the INPUT half, and the population is the live constraint — "
               "that namespace measures the backdrop UNKNOWN for the dominant text "
               "population, so exercisable does not mean total.")}

   {:id :palette-token-conformance
    :quantity "every scalar colour a draw descriptor declares is a member of a declared token table"
    :floor "set membership — no ratio, no glyph geometry, no legibility metric"
    :provenance :in-tree
    :source "docs/UI-QUALITY-CONTRACTS.md §0 (\"A palette-conformance producer is therefore untouched\")"
    :status :in-scope
    :verdict-shape :exact
    :inputs [{:kind :dump-export :name "controls_dump_draw_palette"}]
    :note (str "The draw palette is a property of the ROUTE to a screen and not of the "
               "screen, so it is NOT a tree key and correctly resolves against the "
               "export surface instead. `devcards.palette` implements this and is not "
               "armed: its context key is not in the registry's closed set yet.")}

   {:id :non-text-boundary-contrast
    :quantity "contrast ratio across a non-text component boundary (border, outline, edge)"
    :floor "3:1"
    :provenance :in-tree
    :source (str "docs/UI-QUALITY-CONTRACTS.md §6.2 + tools/devcards/dev/palette-audit.py "
                 "(WCAG 2.2 §1.4.11 as a legitimate GAP-FILL — 1472H states nothing "
                 "for generic non-coded component boundaries)")
    :status :in-scope
    :verdict-shape :exact
    :inputs [{:kind :dump-key :name "border_color"}
             {:kind :dump-key :name "bg_color"}]
    :note (str "NOT-EXERCISABLE FOR ONE REASON ONLY, which is what makes it worth "
               "listing beside flash-rate: the floor IS sourced in this tree, and the "
               "clause still cannot run because `dump_obj` emits no border, outline or "
               "edge colour at any spelling. That separates the two refusal reasons "
               "the register computes — a clause failing on inputs alone, and one "
               "failing on inputs AND an unsourced floor. A DIFFERENT quantity from "
               "the 6:1 text floor with a different number; §6.2 names mixing them as "
               "the error the precedence rule exists to prevent.")}

   {:id :character-size
    :quantity "angular character size at the operator's eye"
    :floor nil
    :provenance :in-tree
    :source "docs/UI-QUALITY-CONTRACTS.md §0 (\"HARDWARE-SCOPED QUANTITIES ARE OUT OF SCOPE — not pending, not deferred, OUT\")"
    :status :out-of-scope
    :verdict-shape nil
    :inputs [{:kind :font-metric :name :cap-height}
             {:kind :dump-key :name "text_font"}]
    :note (str "OUT OF SCOPE, and that is a DIFFERENT answer from not-exercisable — "
               "the inputs are listed as evidence for the document's own argument "
               "that nothing here can measure this, never as a backlog. Angular size "
               "is a property of a PANEL and an OPERATOR: it needs a pixel pitch and "
               "a viewing distance this repository has never held. `text_font` is now "
               "ON the dump surface and this row did not move, which is the sharpest "
               "demonstration the register offers that :out-of-scope is not a function "
               "of inputs — an arriving input cannot reopen it, and the still-absent "
               ":cap-height is not what holds it shut. Read the earlier claim that "
               "both inputs were absent as retracted, not weakened.")}

   {:id :flash-rate
    :quantity "the rate at which an indicator alternates state"
    :floor nil
    :provenance :brief
    :source nil
    :status :in-scope
    :verdict-shape nil
    :inputs [{:kind :none :name "no surface reports an animation or blink rate"}]
    :note (str "PERMANENTLY NOT-EXERCISED, and it must never report a pass. Two "
               "independent reasons, either sufficient: the vocabulary carries no "
               "input for it, and this repository states no floor for it — the term "
               "appears in no document, token or source file here. A gate judging it "
               "would be checking against an unsourced constant, which §0 refuses.")}])

;; ── pricing ─────────────────────────────────────────────────────────────────

(defn- validate-clause!
  "Structural refusals. Each is a THROW rather than a false input, because a
   malformed clause that priced as NOT-EXERCISABLE would be indistinguishable
   from a well-formed clause whose input is genuinely gone."
  [{:keys [id inputs provenance source status] :as clause}]
  (when-not (keyword? id)
    (throw (ex-info "clause has no keyword :id" {:clause clause})))
  (when (empty? inputs)
    (throw (ex-info (str "clause " id " declares no inputs — a clause that names "
                         "nothing would price as exercisable by default; declare "
                         "the absence with {:kind :none}")
                    {:clause id})))
  (when-not (contains? provenances provenance)
    (throw (ex-info (str "clause " id " has unknown :provenance " (pr-str provenance))
                    {:clause id :expected (set (keys provenances))})))
  (when (and (= :in-tree provenance) (str/blank? (str source)))
    (throw (ex-info (str "clause " id " claims an in-tree floor but cites no :source "
                         "— an uncitable floor is an unsourced constant")
                    {:clause id})))
  (when-not (contains? #{:in-scope :out-of-scope} status)
    (throw (ex-info (str "clause " id " has unknown :status " (pr-str status))
                    {:clause id :expected #{:in-scope :out-of-scope}})))
  (doseq [{:keys [kind] :as input} inputs]
    (when-not (contains? input-kinds kind)
      (throw (ex-info (str "clause " id " names an input of unknown kind " (pr-str kind)
                           " — the kind set is closed so a typo cannot become a "
                           "missing input")
                      {:clause id :input input :expected (set (keys input-kinds))}))))
  clause)

(defn- resolve-input
  "Does one declared input exist on its surface? `:none` NEVER resolves — it is
   a declared absence, not a lookup that happened to fail."
  [{:keys [dump-keys exports font-metrics]} {:keys [kind] input-name :name}]
  (case kind
    :dump-key (contains? dump-keys (str input-name))
    :dump-export (contains? exports (str input-name))
    :font-metric (contains? font-metrics (keyword input-name))
    :none false))

(defn price-clause
  "One clause's disposition against a parsed input surface.

   THREE ANSWERS, never two:

     :out-of-scope     the obligation is real and is discharged elsewhere. Not
                       a backlog item, and not the same answer as blocked.
     :exercisable      every declared input exists AND the floor has an in-tree
                       source.
     :not-exercisable  otherwise, with `:missing` naming every input that did
                       not resolve and `:reasons` naming why.

   THE FLOOR IS HALF THE TEST. A clause whose number exists only outside this
   repository can never be exercisable however complete its inputs are: a gate
   judging against it would be checking an unsourced constant, which
   `docs/UI-QUALITY-CONTRACTS.md` §0 refuses. That is one rule, applied to
   whatever clause happens to trip it, rather than a special case written for a
   clause by name."
  [surface clause]
  (validate-clause! clause)
  (let [{:keys [id inputs provenance status]} clause
        missing (filterv (complement (partial resolve-input surface)) inputs)
        unsourced? (not= :in-tree provenance)
        reasons (cond-> []
                  (seq missing) (conj :inputs-absent)
                  unsourced? (conj :floor-not-sourced-in-tree))
        disposition (cond (= :out-of-scope status) :out-of-scope
                          (seq reasons) :not-exercisable
                          :else :exercisable)]
    (assoc clause
           :id id
           :disposition disposition
           :missing (mapv (fn [i] (select-keys i [:kind :name])) missing)
           :reasons (vec (sort reasons)))))

(defn input-surface
  "The three parsed surfaces a clause is priced against."
  [{:keys [repo-root tokens]}]
  {:dump-keys (dump-key-vocabulary repo-root)
   :exports (exported-symbols repo-root)
   :font-metrics (font-metric-fields {:repo-root repo-root :tokens tokens})})

(defn t0-manifest
  "The full T0 input-surface manifest as data.

   `:repo-root` locates main.c, wasm.mk and the font tables; `:tokens` is the
   PARSED tokens map (not a path), matching `lvgl-codegen.font-metrics` so a
   caller — the test included — can drive cases without editing edn/tokens.edn."
  [{:keys [repo-root tokens]}]
  (let [surface (input-surface {:repo-root repo-root :tokens tokens})
        dup (->> clauses (map :id) frequencies (keep (fn [[k n]] (when (< 1 n) k))) sort vec)]
    (when (seq dup)
      (throw (ex-info (str "duplicate clause id: " (str/join ", " dup))
                      {:duplicates dup})))
    {:schema-version 1
     :kind "pdl-1-t0-input-surface"
     :source-method "parsed-from-interpreter"
     :scope ["key names dump_obj emits (renderer/src/main.c), FLAT — nesting not recovered"
             "symbols wasm.mk exports to the linker (renderer/wasm.mk)"
             "metric fields lvgl-codegen.font-metrics emits from compiled tables"]
     :excluded ["any verdict — :exercisable prices INPUTS, never a pass"
                "any clause this tree does not state; the register is not exhaustive"]
     :input-kinds (into (sorted-map) input-kinds)
     :surface {:dump-keys (vec (:dump-keys surface))
               :dump-exports (vec (:exports surface))
               :font-metrics (mapv name (:font-metrics surface))}
     :clauses (mapv (partial price-clause surface) clauses)}))

;; ── JSON projection + CLI ───────────────────────────────────────────────────

(defn- jsonable
  "Keyword keys and keyword values → their names, recursively."
  [x]
  (cond (map? x) (into (sorted-map)
                       (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) (jsonable v)]))
                       x)
        (sequential? x) (mapv jsonable x)
        (keyword? x) (name x)
        :else x))

(defn- parse-args
  "Parse `--flag value` CLI pairs into {:repo-root :tokens :output}, defaulting
   :repo-root/:tokens when their flags are absent. Throws on an odd arg count
   or an unrecognized flag rather than silently dropping either."
  [args]
  (when (odd? (count args))
    (throw (ex-info "flags must come in --flag value pairs" {:args (vec args)})))
  (reduce (fn [acc [flag value]]
            (case flag
              "--repo-root" (assoc acc :repo-root value)
              "--tokens" (assoc acc :tokens value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag
                               :expected #{"--repo-root" "--tokens" "--output"}}))))
          {:repo-root repo-root-default :tokens tokens-default}
          (partition 2 args)))

(defn -main
  "Emit the T0 input-surface manifest. `--output` optional (stdout)."
  [& args]
  (let [{:keys [repo-root tokens output]} (parse-args args)
        manifest (jsonable (t0-manifest {:repo-root repo-root
                                         :tokens (edn/read-string (slurp tokens))}))
        tally (frequencies (map #(get % "disposition") (get manifest "clauses")))]
    (if output
      (do (io/make-parents output)
          (with-open [w (io/writer output)]
            (json/write manifest w :indent true :escape-slash false)
            (.write w "\n"))
          (println (str "pdl-t0: " (count (get manifest "clauses")) " clauses "
                        (pr-str (into (sorted-map) tally)) " -> " output)))
      (do (json/write manifest *out* :indent true :escape-slash false)
          (println)))))

;; ── function schemas ────────────────────────────────────────────────────────

(m/=> dump-key-vocabulary [:=> [:cat some?] [:set [:string {:min 1}]]])

(m/=> exported-symbols [:=> [:cat some?] [:set [:string {:min 1}]]])

(m/=> price-clause
      [:=> [:cat [:map [:dump-keys [:set string?]]
                  [:exports [:set string?]]
                  [:font-metrics [:set keyword?]]]
            [:map [:id :keyword]]]
       [:map
        [:id :keyword]
        [:disposition [:enum :exercisable :not-exercisable :out-of-scope]]
        [:missing [:vector [:map [:kind :keyword]]]]
        [:reasons [:vector :keyword]]]])

(m/=> t0-manifest
      [:=> [:cat [:map [:tokens [:map-of :keyword some?]]]]
       [:map [:clauses [:vector [:map [:id :keyword]
                                 [:disposition :keyword]]]]]])
