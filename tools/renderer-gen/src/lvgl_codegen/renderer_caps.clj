(ns lvgl-codegen.renderer-caps
  "Codegen-side mirrors of the WASM renderer's static contract — the pool
   caps (`src/renderer.c` #defines) and the compiled-in font set
   (`resolve_font`) — plus the headroom checks that make capacity a CODEGEN
   error instead of a target-side surprise.

   One home: renderer.c owns the values; this namespace is a declared
   mirror, and `renderer-caps-mirror-test` parses renderer.c and fails
   the suite the moment either side drifts.

   The headroom rule: a screen whose count exceeds 80% of any cap FAILS
   emit loudly, naming the count and the cap — the remaining 20% is the
   patch-time growth reserve (REPLACE/INSERT ops consume pool slots that
   only a full reload reclaims)."
  (:require [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

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
   :pending-bindings {:define "MAX_PENDING_BINDINGS" :cap 64}
   ;; Not a pool — the decoder's max widget-tree nesting (children_decode_cb
   ;; recurses the C stack). The headroom rule doubles as a codegen guard: a
   ;; screen nested past 80% of the cap fails emit, so a legit screen can never
   ;; approach the renderer's crash-safety limit (a crafted .pb is the only way
   ;; to reach it, and the runtime cap rejects that loudly).
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
   "MAX_TABLE_ROWS"
   "per-NODE ceiling on ONE table's declared row_count (it bounds that table's
    own allocation), not a screen pool the codegen sums; the renderer refuses an
    over-cap table loudly."
   "MAX_TABLE_COLS"
   "per-NODE ceiling on ONE table's declared column_count; see MAX_TABLE_ROWS."
   "MAX_PENDING_VISIBILITY"
   "transient load-time deferral queue (show-when bindings), drained during the
    build — not a persistent pool; its binding class's counted representative is
    pending-bindings."
   "MAX_PENDING_CHECKED"
   "transient load-time deferral queue (checked bindings), drained during the
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
   "MAX_PROXIES"
   "host_proxy pool — a screen aggregate, but small and uncontended (the busiest
    live screen, host_proxy_demo, mounts 3 of a max 8); allowlisted pending a
    screen that approaches it, at which point it graduates to a counted cap."
   "MAX_DROPDOWN_VALUE_MAPS"
   "sparse registry of value-bound enum dropdowns' option->index maps (SYNC C1);
    a screen aggregate like host_proxy but tiny and uncontended — few enum
    dropdowns carry a value bind — reset on full load + swap-removed with its
    widget in unregister_subtree. Allowlisted pending a screen that approaches
    it, at which point it graduates to a counted cap (see MAX_PROXIES)."})

(def compiled-in-fonts
  "Font symbol names the renderer resolves WITHOUT touching the binfont
   pool — the strcmp ladder in renderer.c resolve_font. Any other
   PROP_TEXT_FONT string must resolve as an asset font (P:fonts/<name>.bin
   or P:fonts/<family>.ttf) or codegen rejects it (validate-font-refs!)."
  #{"b612mono_bold_12" "b612mono_bold_14" "b612mono_bold_16" "b612mono_bold_18"
    "b612mono_bold_20" "orbitron_bold_22" "orbitron_bold_28" "orbitron_bold_32"
    "montserrat_14" "montserrat_16" "montserrat_18" "montserrat_22" "montserrat_24"})

;; ── IR walking ──────────────────────────────────────────────────────
(def ^:private ir-node
  "An emitted proto-IR widget node (emit-screen :root shape) — open
   beyond the discriminating key; proto-schema owns the full shape."
  [:map [:type :keyword]])

(def ^:private screen-ir
  "An emitted proto-IR screen."
  [:map [:root [:map [:type :keyword]]]])

(defn- ir-nodes
  "Every widget node of an emitted screen IR, root first."
  [ir]
  (tree-seq map? :children (:root ir)))

(defn- font-strings
  "Every PROP_TEXT_FONT string an emitted node carries (across all
   style-group variants)."
  [node]
  (for [group (:style_groups node)
        variant (:variants group)
        prop (:properties variant)
        :when (= :PROP_TEXT_FONT (:type prop))]
    (:string_value prop)))

(defn screen-fonts
  "The distinct PROP_TEXT_FONT strings an emitted screen references —
   the surface validate-font-refs! and the binfont headroom count share."
  [ir]
  (vec (distinct (mapcat font-strings (ir-nodes ir)))))

;; ── Per-cap counts ──────────────────────────────────────────────────
(defn- style-slot-estimate
  "Worst-case style-pool slots one screen load consumes. Per style group:
   the base style, plus one more when the group carries sparse override
   entries (an exact-match decode parks the base un-attached but the slot
   is consumed). Per scale section: the indicator/items style plus the
   optional MAIN-part style — counted as 2 (overestimate is safe; the
   check fires a loud codegen error, never a silent target failure)."
  [nodes]
  (reduce +
          0
          (for [node nodes]
            (+ (reduce +
                       0
                       (for [group (:style_groups node)]
                         (if (> (count (:variants group)) 1) 2 1)))
               (* 2 (count (get-in node [:scale_props :sections])))))))

(defn- scale-text-max
  "The largest per-scale text_src label count ('\\n'-separated tokens —
   the renderer's per-source MAX_SCALE_TEXTS bound)."
  [nodes]
  (transduce (comp (keep #(get-in % [:scale_props :text_src]))
                   (map #(count (str/split % #"\n"))))
             max
             0
             nodes))

(defn- scale-text-pool-count
  "The number of scale nodes carrying a NON-EMPTY text_src — each consumes one
   apply_scale_text_src pool slot (MAX_SCALE_TEXT_POOL). Matches the renderer,
   which skips an empty text_src. Distinct from scale-text-max, which bounds the
   labels WITHIN one source (MAX_SCALE_TEXTS)."
  [nodes]
  (count (remove #(str/blank? (get-in % [:scale_props :text_src])) nodes)))

(defn- bg-image-src-count
  "The number of PROP_BG_IMAGE_SRC style properties a screen emits — each
   consumes one persist_bg_image_src pool slot (MAX_BG_IMAGE_SRCS)."
  [nodes]
  (count (for [node nodes
               group (:style_groups node)
               variant (:variants group)
               prop (:properties variant)
               :when (= :PROP_BG_IMAGE_SRC (:type prop))]
           prop)))

(defn- btnmatrix-map-entries-max
  "The most map ENTRIES any one buttonmatrix map_str yields — the renderer
   splits on \"\\n\" and each segment (button OR row break) takes one
   MAX_BTNMATRIX_BUTTONS slot. Mirrors apply_buttonmatrix_map's parse: a
   trailing \"\\n\" is absorbed by the terminator, so it is not an entry."
  [nodes]
  (transduce (comp (keep #(get-in % [:buttonmatrix_props :map_str]))
                   (remove str/blank?)
                   (map #(count (str/split % #"\n"))))
             max
             0
             nodes))

(defn- btnmatrix-map-pool-count
  "The number of buttonmatrix nodes carrying a NON-EMPTY map_str — each consumes
   one apply_buttonmatrix_map pool slot (MAX_BTNMATRIX_MAP_POOL). Matches the
   renderer, which skips an empty map_str (LVGL's default map stands)."
  [nodes]
  (count (remove #(str/blank? (get-in % [:buttonmatrix_props :map_str])) nodes)))

(defn- tree-depth
  "Deepest widget nesting of the emitted screen (root = 0, each child level +1)
   — the same measure the renderer's per-node `depth` bounds by MAX_DECODE_DEPTH."
  [ir]
  (letfn [(d [node lvl]
            (if-let [kids (seq (:children node))]
              (reduce max lvl (map #(d % (inc lvl)) kids))
              lvl))]
    (d (:root ir) 0)))

(defn counts
  "Count one emitted screen IR against every mirrored cap key."
  [ir]
  (let [nodes (ir-nodes ir)]
    {:subjects (count (:subjects ir))
     :styles (style-slot-estimate nodes)
     :binfonts (count (remove compiled-in-fonts (screen-fonts ir)))
     :uid-nodes (count nodes)
     :grid-templates (+ (count (filter #(seq (:grid_col_dsc %)) nodes))
                        (count (filter #(seq (:grid_row_dsc %)) nodes)))
     :scale-texts (scale-text-max nodes)
     :scale-text-pool (scale-text-pool-count nodes)
     :bg-image-srcs (bg-image-src-count nodes)
     :btnmatrix-map-entries (btnmatrix-map-entries-max nodes)
     :btnmatrix-map-pool (btnmatrix-map-pool-count nodes)
     :pending-bindings (count (filter #(seq (:bindings %)) nodes))
     :decode-depth (tree-depth ir)}))

(defn check-headroom!
  "Fail loud when any count exceeds 80% of its renderer cap (5n > 4cap),
   naming every offending count, cap, and renderer #define. Capacity is a
   codegen error here — never a truncated render on the target."
  [ir output-path]
  (let [measured (counts ir)
        over (for [[k {:keys [define cap]}] caps
                   :let [n (get measured k)]
                   :when (> (* 5 n) (* 4 cap))]
               {:concern k :count n :cap cap :define define})]
    (when (seq over)
      (throw (ex-info (str "Renderer headroom exceeded (>80% of a static cap): "
                           (str/join "; "
                                     (for [o over]
                                       (str (name (:concern o))
                                            " "
                                            (:count o)
                                            " vs cap "
                                            (:cap o)
                                            " ("
                                            (:define o)
                                            ")"))))
                      {:over (vec over) :output output-path})))
    nil))

;; -- Function schema registrations --
(m/=> ir-nodes [:=> [:cat screen-ir] [:sequential ir-node]])

(m/=> font-strings [:=> [:cat ir-node] [:sequential [:maybe [:string {:min 1}]]]])

(m/=> screen-fonts [:=> [:cat screen-ir] [:vector [:maybe [:string {:min 1}]]]])

(m/=> style-slot-estimate [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> scale-text-max [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> scale-text-pool-count [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> bg-image-src-count [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> btnmatrix-map-entries-max [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> btnmatrix-map-pool-count [:=> [:cat [:sequential ir-node]] nat-int?])

(m/=> tree-depth [:=> [:cat screen-ir] nat-int?])

(m/=> counts [:=> [:cat screen-ir] [:map-of :keyword nat-int?]])

(m/=> check-headroom! [:=> [:cat screen-ir [:string {:min 1}]] :nil])