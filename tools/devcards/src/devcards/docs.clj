(ns devcards.docs
  "Per-widget doc pages + the gallery index (T2.7) — the promoted F2-POC
   generator. For every ui.WidgetType value it emits
   docs/widgets/<WIDGET_ENUM_NAME>/README.md (GENERATED, DO-NOT-EDIT)
   assembling: the per-card JPEGs (one image per card x family, no baked
   label — devcards.gallery), the
   conventions manifest's committed-states row, the props-message schema
   table from protogen's committed JSON FileDescriptorSet, protodoc
   cross-links, and the corpus spec widget's :notes as known limitations.
   Plus docs/widgets/README.md (all-widget index + kitchen-sink section +
   states legend) and docs/widgets/kitchen-sinks/ (the 6 authored
   compositions get their own gallery dir — recorded call: a sink is not a
   WidgetType, so it must not masquerade as a widget page, and burying six
   compositions in the index would hide the only multi-widget renders).

   The F2 gotchas this generator pins:
   - The props schema source is output/json-descriptors/descriptor-set.json
     — docs/.protodoc/proto-db.edn LACKS the ui package (protogen gap,
     Backlog), so the descriptor IS the one usable home.
   - The registry is ENUM-DERIVED, never hand-typed: WidgetType values come
     from the descriptor, each resolved to its WidgetNode widget_props
     oneof arm, its corpus spec class, and its conventions widget-states
     row — any missing/extra mapping FAILS generation (an undeclared
     WidgetType is red by construction).
   - Markdown is hand-rolled GFM (the F2 buy-before-build survey: pipe
     tables + image refs are the whole surface; a templating dep would hide
     the byte shape a DO-NOT-EDIT page should keep visible).
   - Every table gets a REAL header row — md-table REJECTS blank headers
     (the F2 index-grid defect, pinned mechanically).

   Cross-link fact (documented not guessed): the ui_ast.proto and
   protodoc-index links are written for the protogen root layout
   (docs/widgets/<WIDGET>/ sits 3 segments below the repo root). The per-card
   images and inter-page links are colocated and resolve everywhere.

   Shapes are closed and hand-validated (no malli dependency); the
   descriptor subset is walked string-keyed — protobuf's JSON descriptor
   uses camelCase + literal `[buf.validate.field]` option keys, both
   dishonest as Clojure keywords."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [devcards.conventions :as conventions]
            [devcards.gallery :as gallery]
            [devcards.jpeg :as jpeg])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def descriptor-path
  "The repo's committed JSON FileDescriptorSet (tool-relative; the props
   schema source — see the ns docstring for why not proto-db.edn)."
  "../../output/json-descriptors/descriptor-set.json")

(def ui-proto-path "The descriptor `file[]` name of the UI-AST proto." "ui/ui_ast.proto")

(def out-dir
  "The generated doc tree root (tool-relative; the protogen-destined
   layout — docs/widgets/<WIDGET>/ holds README.md + one JPEG per card x
   family)."
  "docs/widgets")

;; ── Descriptor reading (string-keyed JSON walking) ──────────────────────
(defn load-descriptor
  "Parse the descriptor-set JSON at `path` into a string-keyed map."
  [^String path]
  (json/read-str (slurp path)))

(defn find-file
  "The `file[]` entry (FileDescriptorProto, JSON form) named `proto-path`,
   or nil."
  [descriptor ^String proto-path]
  (some #(when (= (get % "name") proto-path) %) (get descriptor "file")))

(defn find-message
  "The `messageType[]` entry named `msg-name` in a `file` entry, or nil.
   protogen's ui/*.proto messages are top-level (no nested types), so a
   flat scan is complete."
  [file ^String msg-name]
  (some #(when (= (get % "name") msg-name) %) (get file "messageType")))

(def ^:private scalar-type-names
  "FieldDescriptorProto.Type JSON names rendered directly (TYPE_ENUM /
   TYPE_MESSAGE need `typeName` instead)."
  {"TYPE_DOUBLE" "double"
   "TYPE_FLOAT" "float"
   "TYPE_INT64" "int64"
   "TYPE_UINT64" "uint64"
   "TYPE_INT32" "int32"
   "TYPE_FIXED64" "fixed64"
   "TYPE_FIXED32" "fixed32"
   "TYPE_BOOL" "bool"
   "TYPE_STRING" "string"
   "TYPE_BYTES" "bytes"
   "TYPE_UINT32" "uint32"
   "TYPE_SFIXED32" "sfixed32"
   "TYPE_SFIXED64" "sfixed64"
   "TYPE_SINT32" "sint32"
   "TYPE_SINT64" "sint64"})

(defn- strip-leading-dot ^String [^String s] (if (str/starts-with? s ".") (subs s 1) s))

(defn field-type-str
  "A field's proto type as a short string (\"int32\", \"enum ui.BarMode\",
   \"message ui.Point\"), prefixed `repeated ` when LABEL_REPEATED."
  ^String [field]
  (let [t (get field "type")
        base (cond (= t "TYPE_ENUM") (str "enum "
                                          (strip-leading-dot (get field "typeName")))
                   (= t "TYPE_MESSAGE") (str "message "
                                             (strip-leading-dot (get field "typeName")))
                   :else (get scalar-type-names t t))]
    (if (= (get field "label") "LABEL_REPEATED") (str "repeated " base) base)))

(defn- flatten-constraints
  "Flatten a nested buf.validate constraint map into dotted `k.k2=v` pairs
   (recursive, so every constraint shape renders without a per-type table)."
  [prefix m]
  (mapcat (fn [[k v]]
            (let [path (if prefix (str prefix "." k) k)]
              (if (map? v) (flatten-constraints path v) [(str path "=" v)])))
          m))

(defn field-constraints-str
  "A field's `[buf.validate.field]` option as a compact `k=v, ...` string,
   or an em-dash when the field carries none."
  ^String [field]
  (if-let [validate (get-in field ["options" "[buf.validate.field]"])]
    (str/join ", " (flatten-constraints nil validate))
    "—"))

(defn message-fields
  "A message's fields as doc rows {:name :number :type :constraints}, in
   the descriptor's own array order (= declaration order)."
  [message]
  (for [field (get message "field")]
    {:name (get field "name")
     :number (get field "number")
     :type (field-type-str field)
     :constraints (field-constraints-str field)}))

;; ── The enum-derived widget registry ────────────────────────────────────
(defn- enum-values
  "The WidgetType value names, descriptor order."
  [ui-file]
  (let [e (some #(when (= (get % "name") "WidgetType") %) (get ui-file "enumType"))]
    (when-not e (throw (ex-info "WidgetType enum missing from the ui descriptor" {})))
    (mapv #(get % "name") (get e "value"))))

(defn- props-arm-name
  "A WidgetType enum name's widget_props oneof arm field name
   (WIDGET_HOST_PROXY -> host_proxy_props)."
  ^String [^String enum-name]
  (str (str/lower-case (subs enum-name (count "WIDGET_"))) "_props"))

(defn widget-registry
  "Resolve every WidgetType value to its full doc-page inputs:
   {:enum :kw :arm :message-name :message :fields :widget :states}. Every
   resolution failure — a WidgetType without a spec class, a spec class
   with an undeclared type, a missing widget-states row, a missing
   widget_props arm or props message — THROWS naming the gap: the registry
   is enum-derived and total, never a hand-typed subset."
  [descriptor spec widget-states]
  (let [ui-file (or (find-file descriptor ui-proto-path)
                    (throw (ex-info "ui/ui_ast.proto missing from descriptor"
                                    {:path descriptor-path})))
        widget-node (or (find-message ui-file "WidgetNode")
                        (throw (ex-info "WidgetNode missing from descriptor" {})))
        arm-fields (into {} (map (juxt #(get % "name") identity)) (get widget-node "field"))
        enums (enum-values ui-file)
        by-type (group-by :type (:widgets spec))
        _ (when-let [dupes (seq (filter #(< 1 (count (val %))) by-type))]
            (throw (ex-info "spec declares a WidgetType twice"
                            {:duplicated (mapv key dupes)})))
        _ (when-let [extra (seq (remove (set (map keyword enums)) (keys by-type)))]
            (throw (ex-info "spec widget class type outside the WidgetType enum"
                            {:extra (vec extra)})))]
    (mapv
     (fn [enum-name]
       (let [kw (keyword enum-name)
             widget (first (get by-type kw))
             _ (when-not widget
                 (throw (ex-info "WidgetType has no corpus spec class" {:enum enum-name})))
             states (get widget-states kw)
             _ (when-not (seq states)
                 (throw (ex-info "WidgetType has no widget-states row" {:enum enum-name})))
             arm (props-arm-name enum-name)
             field (or (get arm-fields arm)
                       (throw (ex-info "WidgetType has no widget_props oneof arm"
                                       {:enum enum-name :arm arm})))
             message-name (strip-leading-dot (get field "typeName"))
             short-name (last (str/split message-name #"\."))
             message (or (find-message ui-file short-name)
                         (throw (ex-info "props message missing from descriptor"
                                         {:enum enum-name :message message-name})))]
         {:enum enum-name
          :kw kw
          :arm arm
          :message-name short-name
          :qualified-name message-name
          :fields (vec (message-fields message))
          :widget widget
          :states (vec states)}))
     enums)))

;; ── GFM building blocks ─────────────────────────────────────────────────
(def ^:private do-not-edit-header
  "<!--
GENERATED by the devcards gallery generator — DO NOT EDIT.
Regenerate: `clojure -M:bindings:run gallery` from the devcards tool root
(tools/devcards/). Sources: corpus/spec.edn (cards + notes) +
conventions/ui-render-conventions.edn (:widget-states, :state-selectors) +
output/json-descriptors/descriptor-set.json (props schema) + the colocated
per-card JPEGs rendered from the pinned controls.wasm.
-->\n\n")

(defn- esc-cell
  "Escape a GFM table cell: pipes break column alignment, literal newlines
   break the row."
  ^String [v]
  (-> (str v)
      (str/replace "|" "\\|")
      (str/replace "\n" " ")))

(defn md-table
  "Render `headers` + `rows` (seqs of cell values, uniform width) as a GFM
   pipe table. Headers must be REAL — a blank header cell throws (the F2
   index-grid defect, pinned). Rows must match the header width."
  ^String [headers rows]
  (when (or (empty? headers) (some #(str/blank? (str %)) headers))
    (throw (ex-info "md-table requires a real, non-blank header row"
                    {:headers (vec headers)})))
  (doseq [row rows]
    (when (not= (count row) (count headers))
      (throw (ex-info "md-table row width != header width"
                      {:headers (vec headers) :row (vec row)}))))
  (let [line (fn [cells] (str "| " (str/join " | " (map esc-cell cells)) " |"))]
    (str/join "\n"
              (concat [(line headers)
                       (str "|" (str/join "|" (repeat (count headers) "---")) "|")]
                      (map line rows)))))

(defn props-table-md
  "A widget's props-message fields as a GFM table, or a prose note when the
   message is empty (ObjProps/ButtonProps — no fields is a real,
   documentable shape, not a generator failure)."
  ^String [^String qualified-name fields]
  (if (empty? fields)
    (format (str "`%s` carries **no widget-specific properties** — this widget "
                 "draws entirely from the shared `WidgetNode` contract (`text`, "
                 "`children`, `style_groups`, `states`).")
            qualified-name)
    (md-table ["field" "number" "type" "constraints"]
              (map (juxt :name :number :type :constraints) fields))))

;; ── Page templates ──────────────────────────────────────────────────────
(defn- card-file-name
  "The colocated per-card artifact name: <slug>-<state>-<family>.jpg. One
   JPEG per (card, family), no label baked in — the caption lives in the grid."
  ^String [^String slug ^String state-slug fam]
  (str slug "-" state-slug "-" (:file-suffix fam) ".jpg"))

(defn- states-grid-md
  "A state x family image grid: one ROW per card (its state caption in
   column one, then the three family thumbnails). The caption is real
   markdown text welded to its images by the table row, so it can never
   float away from its card the way a baked-in sheet label did; the browser
   packs the cells, so no in-repo packing can misplace them. `rows` are the
   maps `card-files!` returns ({:label :imgs {suffix filename}})."
  ^String [rows]
  (md-table (into ["state"] (map :title) gallery/family-renders)
            (for [{:keys [label imgs]} rows]
              (into [(str "`" label "`")]
                    (map (fn [fam]
                           (let [f (get imgs (:file-suffix fam))]
                             (str "![" label " " (:file-suffix fam) "](./" f ")")))
                         gallery/family-renders)))))

(defn widget-page-md
  "The full README.md body for one widget's doc page, from its registry
   entry and the rendered per-card `rows` (card-files! output)."
  ^String [{:keys [enum message-name qualified-name fields widget states]} rows]
  (let [tag (:tag widget)
        notes (:notes widget)]
    (str
     do-not-edit-header
     "# "
     enum
     "\n\n"
     "`"
     tag
     "` — "
     (count (:cards widget))
     " atomic corpus cards "
     "(state × size[/value], ids `" tag
     "/<state>/<size>[/<value>]`), "
     "rendered unstyled so everything unset falls through to the loaded "
     "theme — the object under test. One image per card × family, cropped to "
     "the card's dump_tree content box; the row caption is the card-id tail.\n\n"
     "## States\n\n" (states-grid-md rows)
     "\n\n## Committed states\n\n"
     "The asgard theme commits to rendering each state below **visually "
     "distinct** from `default` (gate-held: distinctness). Any state *not* "
     "listed renders identical to `default` (inertness — hovered-on-a-label "
     "is the canonical probe).\n\n" (str/join "\n" (map #(str "- `" (name %) "`") states))
     "\n\n## Props schema — `" message-name
     "` (`" qualified-name
     "`)\n\n" (props-table-md qualified-name fields)
     "\n\n*Shape-only: the committed JSON descriptor carries no "
     "`SourceCodeInfo` comments and `docs/.protodoc/proto-db.edn` does not "
     "cover the `ui` package, so no per-field prose exists to include "
     "(protogen backlog).*\n\n"
     "## Known limitations\n\n"
     (if (str/blank? (str notes)) "None recorded in the corpus spec for this class." notes)
     "\n\n---\n" "Cross-links: [`ui_ast.proto`](../../../proto/ui/ui_ast.proto) &middot; "
     "[protodoc index](../../index.md) &middot; "
     "[widget gallery index](../README.md)\n")))

(defn legos-page-md
  "The composition-legos gallery page: the 3 sheets over the
   authored-composition corpus (corpus/composition.edn — the source of
   this page, beside the atomic spec) + a table linking each card to its
   `devcards.legos` maker and its contract notes."
  ^String [cards rows]
  (str do-not-edit-header
       "# Composition legos\n\n"
       "The "
       (count cards)
       " authored-composition cards from `corpus/composition.edn` — the "
       "public `devcards.legos` builders compiled through the authored "
       "lane (`devcards.fixtures/build-authored-card`), the only corpus "
       "cells carrying events, absolute placement, and part-selector "
       "styling. Interaction contracts (press-seek, drag, the ext-click "
       "halo, dock event identities) are gate-held on BOTH engines; the "
       "images document the pixels. Each is cropped to the lego's own "
       "box — the scrubber's includes its transparent hit-halo wrapper.\n\n"
       "## States\n\n" (states-grid-md rows)
       "\n\n## The cards\n\n"
       (md-table ["card" "lego" "what it proves"]
                 (for [{:keys [id lego notes]} cards]
                   [(str "`" id "`") (str "`" (name lego) "`") notes]))
       "\n\n---\n" "Cross-links: [widget gallery index](../README.md)\n"))

(defn sinks-page-md
  "The kitchen-sinks gallery page: the 3 sheets over all 6 authored
   compositions + a table linking each sink to its member widget pages.
   `tag->enum` resolves spec widget tags to page dirs."
  ^String [sinks tag->enum rows]
  (str do-not-edit-header
       "# Kitchen sinks\n\n"
       "The "
       (count sinks)
       " authored multi-widget compositions from the same "
       "corpus the gates verify — the only cells where widgets render as "
       "neighbors. Each is a whole composite screen, cropped to the sink "
       "container: one image per sink × family.\n\n"
       "## Screens\n\n" (states-grid-md rows)
       "\n\n## The compositions\n\n"
       (md-table ["sink" "member widgets" "what it proves"]
                 (for [{:keys [id widgets description]} sinks]
                   [(str "`" id "`")
                    (str/join ", "
                              (for [tag widgets
                                    :let [e (or (get tag->enum tag)
                                                (throw (ex-info
                                                        "sink member tag has no widget page"
                                                        {:sink id :tag tag})))]]
                                (str "[" e "](../" e "/README.md)"))) description]))
       "\n\n---\n" "Cross-links: [widget gallery index](../README.md)\n"))

(defn index-page-md
  "The docs/widgets/README.md index: every WidgetType row (link, committed
   states, asgard-dark preview), the kitchen-sink section, and the states
   legend (a generated projection of the conventions manifest's
   :state-selectors — the manifest stays the one home)."
  ^String [registry state-selectors previews]
  (str
   do-not-edit-header
   "# Widget gallery\n\n"
   "Per-widget rendered doc pages, generated from the same corpus the "
   "devcard gates verify — all "
   (count registry)
   " `ui.WidgetType` values, "
   "enum-derived (an undeclared WidgetType fails generation). Previews are "
   "one asgard-dark card; each page adds vanilla + asgard-light per card.\n\n"
   (md-table
    ["widget" "committed states" "preview (asgard dark)"]
    (for [{:keys [enum states]} registry]
      [(str "[`" enum "`](./" enum "/README.md)")
       (str/join ", " (map #(str "`" (name %) "`") states))
       (str "[![" enum "](./" enum "/"
            (or (get previews enum)
                (throw (ex-info "no preview image for widget index row" {:enum enum})))
            ")](./" enum "/README.md)")]))
   "\n\n## Kitchen sinks\n\n"
   "Six authored multi-widget compositions render on their own page: "
   "[kitchen sinks](./kitchen-sinks/README.md).\n\n"
   "[![kitchen sinks](./kitchen-sinks/" (get previews "kitchen-sinks") ")]"
   "(./kitchen-sinks/README.md)\n\n"
   "## Composition legos\n\n"
   "The authored-composition corpus — the public `devcards.legos` "
   "builders (media scrubber + foldable stage-manager dock) with their "
   "gate-held interaction contracts — renders on its own page: "
   "[composition legos](./legos/README.md).\n\n"
   "[![composition legos](./legos/" (get previews "legos") ")]"
   "(./legos/README.md)\n\n"
   "## States legend\n\n"
   "Card captions are card-id tails (`<state>/<size>[/<value>]`); the "
   "state vocabulary below is a generated projection of "
   "`ui-render-conventions.edn` `:state-selectors` (the manifest is the one "
   "home).\n\n"
   (md-table ["state" "lv_state bit"]
             (for [[k v] (sort-by val state-selectors)] [(str "`" (name k) "`") v]))
   "\n"))

;; ── Generation (renders + writes) ───────────────────────────────────────
(defn- write-bytes!
  "Write `bytes` at `path` (parents created). Returns {:path :bytes}."
  [^String path ^bytes content]
  (io/make-parents path)
  (with-open [out (io/output-stream path)] (.write out content))
  {:path path :bytes (alength content)})

(defn- write-text!
  "Write text at `path` (parents created). Returns {:path :bytes}."
  [^String path ^String text]
  (io/make-parents path)
  (spit path text)
  {:path path :bytes (.length (File. path))})

(defn- card-files!
  "Render + encode + write ONE JPEG per (card, family) for a gallery unit
   (`entries` = its built corpus entries, spec order). No label is baked into
   the pixels. Returns {:files [{:path :bytes}...] :rows [{:label :imgs
   {suffix filename}}...]} — :rows drives the README grid, :files the report.
   A unit with zero entries is an ERROR: an empty page documents nothing."
  [paths canvas ^String dir ^String slug entries]
  (when (empty? entries)
    (throw (ex-info "gallery unit has ZERO renderable cards" {:slug slug})))
  (let [per-card
        (mapv
         (fn [{:keys [id] ^bytes pb :bytes}]
           (let [label (gallery/cell-label (str id))
                 sslug (gallery/state-slug label)
                 fams (mapv (fn [fam]
                              (let [fname (card-file-name slug sslug fam)
                                    img (gallery/render-cell! paths canvas pb fam (str id))]
                                {:suffix (:file-suffix fam)
                                 :fname fname
                                 :file (write-bytes!
                                        (str dir "/" fname)
                                        (jpeg/encode img jpeg/default-quality))}))
                            gallery/family-renders)]
             {:label label
              :imgs (into {} (map (juxt :suffix :fname)) fams)
              :files (mapv :file fams)}))
         entries)]
    {:files (vec (mapcat :files per-card))
     :rows (mapv #(select-keys % [:label :imgs]) per-card)}))

(defn- unit-preview
  "The index-preview filename for a unit: its first card's asgard-dark image."
  ^String [rows]
  (get-in (first rows) [:imgs "asgard-dark"]))

(defn generate!
  "The T2.7 build: for every WidgetType — one JPEG per card x family + README.md
   under docs/widgets/<ENUM>/ — plus docs/widgets/kitchen-sinks/,
   docs/widgets/legos/ (the authored-composition corpus), and the index.
   `opts` = {:spec <parsed corpus spec> :built <build-all output>
   :composition {:cards <inventory cards> :built <composition build-all
   output>} :paths {:wasm :assets}}. Returns {:files [{:path :bytes}]
   :images n :pages n :cells n} for the caller's report; every gap (a
   widget with zero built cards, a sink or composition card absent from
   the build) throws."
  [{:keys [spec built composition paths]}]
  (let [conv (conventions/load-conventions)
        descriptor (load-descriptor descriptor-path)
        registry (widget-registry descriptor spec (:widget-states conv))
        canvas (get-in spec [:render :canvas])
        atomic-by-widget (group-by :widget (filter #(= :atomic (:kind %)) built))
        built-sinks (filterv #(= :sink (:kind %)) built)
        _ (when (not= (count built-sinks) (count (:kitchen-sinks spec)))
            (throw (ex-info "built kitchen sinks disagree with the spec"
                            {:built (mapv :id built-sinks)
                             :spec (mapv :id (:kitchen-sinks spec))})))
        tag->enum (into {} (map (fn [{:keys [enum widget]}] [(:tag widget) enum])) registry)
        ;; Parallel over widgets: each render-cell! boots its OWN fresh
        ;; GraalWasm context (hermetic — see gallery/render-cell!), so widget
        ;; units render concurrently on the shared engine with no shared state;
        ;; pmap preserves order, so widget-files stays deterministic.
        ;; Parallel over widgets: each render boots its OWN fresh GraalWasm
        ;; context (hermetic — see gallery/render-cell!), so widget units
        ;; render concurrently with no shared state; pmap preserves order, so
        ;; the output stays deterministic. Each element is [enum files preview].
        widget-units (pmap (fn [{:keys [enum widget] :as entry}]
                             (let [entries (get atomic-by-widget (:tag widget))
                                   dir (str out-dir "/" enum)]
                               (when (empty? entries)
                                 (throw (ex-info "widget class has ZERO built cards"
                                                 {:enum enum :tag (:tag widget)})))
                               (let [{:keys [files rows]} (card-files! paths canvas dir enum entries)
                                     readme (write-text! (str dir "/README.md")
                                                         (widget-page-md entry rows))]
                                 [enum (conj files readme) (unit-preview rows)])))
                           registry)
        widget-files (vec (mapcat second widget-units))
        sink-dir (str out-dir "/kitchen-sinks")
        {sink-imgs :files sink-rows :rows} (card-files! paths canvas sink-dir
                                                        "kitchen-sinks" built-sinks)
        sink-files (conj sink-imgs
                         (write-text! (str sink-dir "/README.md")
                                      (sinks-page-md (:kitchen-sinks spec) tag->enum sink-rows)))
        comp-built (:built composition)
        _ (when (not= (count comp-built) (count (:cards composition)))
            (throw (ex-info "built composition cards disagree with the inventory"
                            {:built (mapv :id comp-built)
                             :inventory (mapv :id (:cards composition))})))
        legos-dir (str out-dir "/legos")
        {legos-imgs :files legos-rows :rows} (card-files! paths canvas legos-dir
                                                          "legos" comp-built)
        legos-files (conj legos-imgs
                          (write-text! (str legos-dir "/README.md")
                                       (legos-page-md (:cards composition) legos-rows)))
        previews (-> (into {} (map (fn [[enum _ preview]] [enum preview])) widget-units)
                     (assoc "kitchen-sinks" (unit-preview sink-rows)
                            "legos" (unit-preview legos-rows)))
        index-file (write-text! (str out-dir "/README.md")
                                (index-page-md registry (:state-selectors conv) previews))
        files (-> widget-files
                  (into sink-files)
                  (into legos-files)
                  (conj index-file))]
    {:files files
     :images (count (filter #(str/ends-with? (:path %) ".jpg") files))
     :pages (+ (count registry) 3)
     :cells (* (count gallery/family-renders)
               (+ (count (filter #(= :atomic (:kind %)) built))
                  (count built-sinks)
                  (count comp-built)))}))