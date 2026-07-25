(ns lvgl-codegen.renderer-caps
  "The renderer's static contract, read from the PINNED renderer-caps
   manifest (protogen `output/manifests/renderer-caps.json` — generated
   from the reference interpreter's own `renderer/src/renderer.c` MAX_*
   defines, mirror-completeness-gated THERE), plus the headroom counting
   that makes capacity a CODEGEN error instead of a target-side surprise.

   The headroom rule: a screen whose count exceeds 80% of any cap FAILS
   emit loudly, naming the count and the cap — the remaining 20% is the
   patch-time growth reserve (REPLACE/INSERT ops consume pool slots that
   only a full reload reclaims)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def renderer-caps-path
  "The pinned renderer-caps manifest (repo-root relative, like every
   codegen default path)."
  "../../output/manifests/renderer-caps.json")

(def ^:private manifest
  "The parsed pinned manifest — {:caps {concern {:define :cap}},
   :non-headroom-caps {DEFINE rationale}, :compiled-in-fonts [names]}.
   A delay: test fixtures and REPL loads must not hard-require the
   submodule at namespace-load time; the first cap READ does, loudly."
  (delay (let [f (io/file renderer-caps-path)]
           (when-not (java.io.File/.isFile f)
             (throw (ex-info (str "renderer-caps manifest not found: "
                                  renderer-caps-path
                                  " — is the protogen submodule checked out?")
                             {:path renderer-caps-path})))
           (with-open [rdr (io/reader f)] (json/read rdr :key-fn keyword)))))

(defn caps
  "The renderer's static pool caps from the pinned manifest, keyed by the
   concern the codegen counts: {concern {:define \"MAX_*\" :cap n}}. Every
   concern `counts` measures must be present — a manifest that dropped a
   counted cap would silently un-gate that count, so the read fails loud
   instead (fail-fast)."
  []
  (let [m (:caps @manifest)]
    (when (empty? m)
      (throw (ex-info "pinned renderer-caps manifest carries no :caps"
                      {:path renderer-caps-path})))
    m))

(defn compiled-in-fonts
  "Font symbol names the renderer resolves WITHOUT touching the binfont
   pool (the reference interpreter's resolve_font ladder), from the pinned
   manifest. Any other PROP_TEXT_FONT string must resolve as an asset font
   (P:fonts/<name>.bin or P:fonts/<family>.ttf) or codegen rejects it
   (validate-font-refs!)."
  []
  (let [fonts (set (:compiled-in-fonts @manifest))]
    (when (empty? fonts)
      (throw (ex-info "pinned renderer-caps manifest carries no :compiled-in-fonts"
                      {:path renderer-caps-path})))
    fonts))

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
     :binfonts (count (remove (compiled-in-fonts) (screen-fonts ir)))
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
        pinned (caps)
        missing (vec (sort (remove pinned (keys measured))))
        _ (when (seq missing)
            ;; A counted concern absent from the pinned manifest would
            ;; silently un-gate that count — fail the build, not the gate.
            (throw (ex-info (str "counted concern(s) missing from the pinned "
                                 "renderer-caps manifest: "
                                 missing)
                            {:missing missing :path renderer-caps-path})))
        over (for [[k {:keys [define cap]}] pinned
                   :let [n (get measured k)]
                   :when (and n (> (* 5 n) (* 4 cap)))]
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
(m/=> caps
      [:=> [:cat] [:map-of :keyword [:map [:define [:string {:min 1}]] [:cap pos-int?]]]])

(m/=> compiled-in-fonts [:=> [:cat] [:set [:string {:min 1}]]])

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