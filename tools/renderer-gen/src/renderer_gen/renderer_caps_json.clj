(ns renderer-gen.renderer-caps-json
  "Emit `renderer-caps.json` — the renderer static-contract manifest
   (a ratified home-move decision): the pool caps (renderer.c's MAX_*
   #defines), the deliberately-uncounted caps with their proof rationales,
   and the compiled-in font ladder. Consumers: the authoring consumer's
   headroom COUNTING half (which reads caps from this pinned manifest
   instead of a source mirror) and any binding consumer that needs the
   renderer's capacity facts.

   Generation IS the mirror check: renderer.c is parsed as the home, and the
   manifest is emitted from the source-of-truth tables below (`caps` /
   `non-headroom-caps` / `compiled-in-fonts`, moved here from the consumer so
   it can read them back from this manifest without a cycle) only after proving
     - every counted cap equals its renderer.c #define value,
     - every renderer.c MAX_* is either counted or rationale-allowlisted
       (and never both, and no allowlisted name is absent from the source),
     - the compiled-in font set equals resolve_font's strcmp ladder.
   Any drift throws — a manifest is never emitted from a lying mirror.

   Shape (CLOSED):
     {\"source\": \"renderer/src/renderer.c\",
      \"caps\": {<concern> {\"define\": MAX_*, \"cap\": int}},
      \"non-headroom-caps\": {MAX_* <rationale string>},
      \"compiled-in-fonts\": [<font symbol> …]}

   Run (from tools/renderer-gen/):
     clojure -M -m renderer-gen.renderer-caps-json \\
       --renderer ../../renderer/src/renderer.c \\
       --output ../../output/manifests/renderer-caps.json"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ── The source-of-truth tables (moved here from lvgl-codegen.renderer-caps so
;; the consumer can READ them from this emitter's manifest without a cycle). ──
(def caps
  "The renderer's static pool caps, keyed by the concern the codegen can
   count. :cap mirrors the renderer #define named by :define."
  {:subjects {:define "MAX_SUBJECTS" :cap 32}
   :styles {:define "MAX_STYLES" :cap 2048}
   :binfonts {:define "MAX_BINFONTS" :cap 16}
   :uid-nodes {:define "MAX_UID_NODES" :cap 1024}
   :grid-templates {:define "MAX_GRID_TEMPLATES" :cap 64}
   ;; Two DISTINCT scale bounds (renderer.c apply_scale_text_src): scale-texts
   ;; is the max labels WITHIN one scale's text_src ("\n"-split tokens);
   ;; scale-text-pool is the max NUMBER of distinct scale text sources per
   ;; screen (each apply_scale_text_src call consumes a pool slot). The pool
   ;; bound (4) is the tighter per-screen limiter — undertested before ITEM 8a.
   :scale-texts {:define "MAX_SCALE_TEXTS" :cap 16}
   :scale-text-pool {:define "MAX_SCALE_TEXT_POOL" :cap 4}
   ;; Each PROP_BG_IMAGE_SRC style property persists one pool string
   ;; (persist_bg_image_src) — count the property occurrences per screen.
   :bg-image-srcs {:define "MAX_BG_IMAGE_SRCS" :cap 8}
   ;; Two DISTINCT buttonmatrix bounds (renderer.c apply_buttonmatrix_map),
   ;; mirroring the scale pair: btnmatrix-map-entries is the max map ENTRIES
   ;; within one map_str (buttons + row breaks, "\n"-split); btnmatrix-map-pool
   ;; is the max NUMBER of non-empty map_str sources per screen (each
   ;; apply_buttonmatrix_map call consumes a pool slot).
   :btnmatrix-map-entries {:define "MAX_BTNMATRIX_BUTTONS" :cap 32}
   :btnmatrix-map-pool {:define "MAX_BTNMATRIX_MAP_POOL" :cap 4}
   ;; Two DISTINCT line bounds (renderer.c apply_line_points), the third
   ;; instance of the scale/buttonmatrix pair shape: line-points is the max
   ;; POINTS within one line (and is simultaneously the wire's max_count for
   ;; ui.LineProps.points — a _Static_assert in renderer.c ties the #define to
   ;; the generated array, so the two cannot drift); line-point-pool is the max
   ;; NUMBER of point-carrying lines per screen, each consuming one pool slot.
   ;; The pool slot is write-once for the widget's lifetime because
   ;; lv_line_set_points keeps the array BY POINTER and never copies it.
   :line-points {:define "MAX_LINE_POINTS" :cap 32}
   :line-point-pool {:define "MAX_LINE_POINT_POOL" :cap 16}
   :pending-bindings {:define "MAX_PENDING_BINDINGS" :cap 64}
   ;; Not a pool — the decoder's max widget-tree nesting (children_decode_cb
   ;; recurses the C stack). The headroom rule doubles as a codegen guard: a
   ;; screen nested past 80% of the cap fails emit, so a legit screen can never
   ;; approach the renderer's crash-safety limit (a crafted .pb is the only way
   ;; to reach it, and the runtime cap rejects that loudly).
   ;;
   ;; That last clause is only true because the LINK reserves enough C stack to
   ;; reach the cap. Each level costs ~4.8 KB, so the WASI-SDK's 64 KiB default
   ;; would strand the decoder around a dozen levels — far under this number —
   ;; turning the runtime guard into unreachable dead code and a rejection into
   ;; a trap, while emit still permitted everything under 80% of 32. wasm.mk's
   ;; -Wl,-z,stack-size is what closes that gap; raising this cap obliges
   ;; raising that reservation in the same change.
   :decode-depth {:define "MAX_DECODE_DEPTH" :cap 32}})

(def non-headroom-caps
  "Renderer MAX_* #defines deliberately NOT mirrored as codegen-counted headroom
   pools — proof-carrying: each names WHY it is not a screen-level pool the
   codegen aggregates. The mirror-completeness test asserts every `#define MAX_*`
   in renderer.c is EITHER a counted `caps` entry OR listed here, so a NEW
   renderer cap forces a classify-or-count decision and can never silently escape
   the headroom gate (the ITEM 8a / bg_image_src blind-spot class). Every value
   must be a non-empty rationale."
  {"MAX_BINDINGS_PER_WIDGET"
   "per-NODE cap (a single widget's binding count), not a screen aggregate; the
    renderer fails loud on overflow, and pending-bindings counts the screen total."
   "MAX_STYLES_PER_WIDGET"
   "per-NODE cap (a single widget's attached-style count); the renderer's own
    guard fails loud on overflow (state-honesty), not a screen pool the codegen
    sums — styles counts the screen-wide slot total separately."
   "MAX_TABVIEW_CHILDREN" "per-NODE cap (one tabview's tab count), not a screen aggregate."
   "MAX_LIVE_CHILDREN"
   "per-PARENT bound on the LIVE child count (LVGL keeps it in a uint16_t, so the
    65536th sibling wraps it to zero), not a screen aggregate the codegen sums.
    Deliberately uncountable at emit besides: the bound is on the live tree, and a
    parent also grows through controls_apply_patch INSERT ops the codegen never
    sees, so a static per-screen count could not be the enforcement point even in
    principle — the renderer refuses loudly at both decode entry points instead."
   "MAX_TABLE_ROWS"
   "per-NODE ceiling on ONE table's declared row_count (it bounds that table's
    own allocation), not a screen pool the codegen sums; the renderer refuses an
    over-cap table loudly."
   "MAX_TABLE_COLS"
   "per-NODE ceiling on ONE table's declared column_count; see MAX_TABLE_ROWS."
   "MAX_TARGET_BOXES"
   "per-NODE ceiling on ONE target overlay's decoded boxes_count, not a screen
    pool the codegen sums. It is also the only DECODER-side bound that field
    has: ui.TargetOverlayProps.boxes is FT_POINTER, so nanopb allocates
    whatever the stream declares and buf.validate max_items:32 binds a
    conforming producer only — this is what refuses a crafted .pb, loudly and
    without drawing a partial frame."
   "MAX_TARGET_BORDER_WIDTH"
   "a WIRE VALUE bound rather than a capacity: it mirrors
    ui.TargetOverlayProps.border_width's buf.validate lte:16, which
    scripts/proto_cleanup.awk deletes before the C leg is generated. Nothing is
    allocated against it and there is nothing for the codegen to count — what it
    bounds is the magnitude of one field, whose value reaches LV_DPX's int32
    multiply unclamped. Its account is output/manifests/ui-ast-constraints.json,
    which names this #define and the test that drives it; this entry exists so
    the completeness assertion stays total over renderer.c's MAX_* grammar."
   "MAX_HIT_SLOP"
   "a WIRE VALUE bound, like MAX_TARGET_BORDER_WIDTH: it mirrors
    ui.WidgetNode.hit_slop's buf.validate lte:64, stripped before the C leg.
    Not a pool — an oversized value costs no memory at all; it silently widens
    one node's reachable box until it absorbs presses aimed at its siblings."
   "MAX_PENDING_VISIBILITY"
   "transient load-time deferral queue (show-when bindings), drained during the
    build — not a persistent pool; its binding class's counted representative is
    pending-bindings."
   "MAX_PENDING_CHECKED"
   "transient load-time deferral queue (checked bindings), drained during the
    build; see MAX_PENDING_VISIBILITY."
   "MAX_PENDING_ENABLED"
   "transient load-time deferral queue (enabled_when bindings), drained during
    the build; see MAX_PENDING_VISIBILITY."
   "MAX_PENDING_COLOR"
   "transient load-time deferral queue (color_when bindings), drained during the
    build; see MAX_PENDING_VISIBILITY."
   "MAX_PENDING_TABVIEW"
   "transient load-time deferral queue (tabview attachment), drained during the
    build; see MAX_PENDING_VISIBILITY."
   "MAX_PENDING_EVENT_SUBJECT"
   "transient load-time deferral queue (an EventBinding's set_subject, resolved
    at the batch-end drain because Screen.subjects streams after the tree),
    drained during the build; see MAX_PENDING_VISIBILITY. Not a screen pool the
    codegen sums — and unreachable from authored EDN besides, since
    validate-screen-semantics rejects an undeclared :set/:toggle subject before
    emit (:undeclared-event-subject)."
   "MAX_PENDING_PATCH_SUBJECT"
   "transient load-time deferral queue, the exact sibling of
    MAX_PENDING_EVENT_SUBJECT: a cmd patch's SUBJECT_VALUE slot names a subject
    that cannot be looked up while the spec is copied (Screen.subjects streams
    after the tree), so the RESOLVABILITY check is queued and drained at the
    batch end. Drained during the build, so it is not a screen pool the codegen
    sums. Overflow is reported and fails the load DEFECTIVE rather than skipping
    a check silently. Unlike its sibling it is NOT yet unreachable from authored
    EDN: no screen-semantics rule rejects a patch naming an undeclared subject
    before emit, so the renderer's load-time check is currently the only thing
    standing between a mistyped form field and a dead Apply button."
   "MAX_PROXIES"
   "host_proxy pool — a screen aggregate, but small and uncontended (the busiest
    live screen, host_proxy_demo, mounts 3 of a max 8); allowlisted pending a
    screen that approaches it, at which point it graduates to a counted cap."
   "MAX_DROPDOWN_VALUE_MAPS"
   "sparse registry of value-bound enum dropdowns' option->index maps (SYNC C1);
    a screen aggregate like host_proxy but tiny and uncontended — few enum
    dropdowns carry a value bind — reset on full load + swap-removed with its
    widget in unregister_subtree. Allowlisted pending a screen that approaches
    it, at which point it graduates to a counted cap (see MAX_PROXIES)."
   "MAX_DESIGNED_OVERLAYS"
   "registry of nodes declaring WidgetNode.designed_overlay; the exact
    structural sibling of MAX_DROPDOWN_VALUE_MAPS — a screen aggregate, sparse,
    reset on full load and swap-removed with its widget in unregister_subtree.
    It carries one property none of the others do, and that property rather than
    the size is why it is here: EXHAUSTING IT CANNOT MAKE A SCREEN
    UNRENDERABLE. Headroom is counted so codegen can refuse a screen the
    renderer could not render; this pool allocates nothing, sets no flag, moves
    no pixel and is read by nothing in the render path — it exists so dump_obj
    can echo an author's declaration to devcards.overlap. Overflow therefore
    WARNS and drops the declaration, and the overlap lane then REPORTS the pairs
    it would have excluded: the degradation is toward more findings, never
    fewer. Counting it would convert a diagnostic annotation into a hard
    authoring limit, refusing at codegen a screen that renders perfectly. It
    graduates to a counted cap if the declaration ever gains a rendering
    effect — not merely if a screen approaches the bound."})

(def compiled-in-fonts
  "Font symbol names the renderer resolves WITHOUT touching the binfont
   pool — the strcmp ladder in renderer.c resolve_font. Any other
   PROP_TEXT_FONT string must resolve as an asset font (P:fonts/<name>.bin
   or P:fonts/<family>.ttf) or codegen rejects it (validate-font-refs!)."
  #{"b612mono_bold_12" "b612mono_bold_14" "b612mono_bold_16" "b612mono_bold_18"
    "b612mono_bold_20" "orbitron_bold_22" "orbitron_bold_28" "orbitron_bold_32"
    "montserrat_14" "montserrat_16" "montserrat_18" "montserrat_22" "montserrat_24"})

(def manifest-source
  "The renderer source the caps are parsed from, as protogen consumers see
   it."
  "renderer/src/renderer.c")

(defn parse-max-defines
  "Every `#define MAX_* <int>` in the renderer source: {define-name value}.
   Value-shaped defines only — the name-only scan below catches the rest."
  [source]
  (into {}
        (map (fn [[_ define value]] [define (Long/parseLong value)]))
        (re-seq #"#define\s+(MAX_[A-Z_]+)\s+(\d+)" source)))

(defn parse-max-names
  "Every `#define MAX_*` NAME in the renderer source, independent of value
   shape — a paren/macro-valued cap must still be caught by the
   completeness check."
  [source]
  (into #{} (map second) (re-seq #"#define\s+(MAX_[A-Z_]+)\b" source)))

(defn parse-font-ladder
  "The compiled-in font symbols of resolve_font's strcmp ladder
   (layout-tolerant, the mirror test's own pattern)."
  [source]
  (into #{} (map second) (re-seq #"(?s)strcmp\(\s*name,\s*\"([^\"]+)\"\s*\)" source)))

(defn check-mirror!
  "The three mirror assertions (ns docstring); throws with the exact drift on
   any failure, else returns nil."
  [source]
  (let [values (parse-max-defines source)
        defined (parse-max-names source)
        counted (into #{} (map (comp :define val)) caps)
        allowlisted (set (keys non-headroom-caps))]
    (when (empty? defined)
      (throw (ex-info "no MAX_* defines found — wrong renderer source?"
                      {:source manifest-source})))
    (doseq [[k {:keys [define cap]}] caps]
      (let [actual (get values define)]
        (when-not (= cap actual)
          (throw (ex-info (str "cap mirror drift on " (name k)
                               ": mirror says " cap
                               " but " define
                               " in renderer.c says " actual)
                          {:concern k :define define :mirror cap :source actual})))))
    (let [unaccounted (set/difference defined (set/union counted allowlisted))
          orphaned (set/difference allowlisted defined)
          doubled (set/intersection counted allowlisted)]
      (when (seq unaccounted)
        (throw (ex-info (str "renderer.c MAX_* neither counted nor allowlisted: "
                             unaccounted)
                        {:unaccounted unaccounted})))
      (when (seq orphaned)
        (throw (ex-info (str "non-headroom-caps names a #define absent from "
                             "renderer.c: "
                             orphaned)
                        {:orphaned orphaned})))
      (when (seq doubled)
        (throw (ex-info (str "a define is BOTH counted and allowlisted: " doubled)
                        {:doubled doubled}))))
    (let [ladder (parse-font-ladder source)]
      (when-not (= ladder compiled-in-fonts)
        (throw (ex-info
                "compiled-in font mirror drift vs resolve_font's ladder"
                {:ladder-only (set/difference ladder compiled-in-fonts)
                 :mirror-only (set/difference compiled-in-fonts ladder)}))))
    nil))

(defn- one-line
  "Collapse a multi-line rationale into single-spaced prose."
  [s]
  (str/join " " (str/split s #"\s+")))

(defn manifest
  "The full manifest map (JSON-ready, deterministically ordered), emitted
   only after `check-mirror!` proves source and mirror agree."
  [source]
  (check-mirror! source)
  {"source" manifest-source
   "caps" (into (sorted-map)
                (map (fn [[k {:keys [define cap]}]] [(name k) {"define" define "cap" cap}]))
                caps)
   "non-headroom-caps" (into (sorted-map)
                             (map (fn [[define reason]] [define (one-line reason)]))
                             non-headroom-caps)
   "compiled-in-fonts" (vec (sort compiled-in-fonts))})

(defn- parse-args
  "Closed --renderer/--output flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--renderer" (assoc acc :renderer value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag :expected #{"--renderer" "--output"}}))))
          {:renderer "src/renderer.c"}
          (partition 2 args)))

(defn -main
  "Parse renderer.c, prove the mirror, emit renderer-caps.json, print the
   cap counts. `--output` is mandatory — this tool never guesses a
   destination."
  [& args]
  (let [{:keys [renderer output]} (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (let [m (manifest (slurp renderer))]
      (io/make-parents output)
      (with-open [w (io/writer output)]
        (json/write m w :indent true :escape-slash false)
        (.write w "\n"))
      (println (str "renderer-caps.json: " (count (get m "caps"))
                    " counted caps, " (count (get m "non-headroom-caps"))
                    " non-headroom caps, " (count (get m "compiled-in-fonts"))
                    " compiled-in fonts -> " output)))))