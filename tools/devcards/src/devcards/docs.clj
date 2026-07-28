(ns devcards.docs
  "The EMITTER half of the generated doc tree (T2.7): protogen's committed data
   and the JPEGs this namespace renders, assembled into UNIT MANIFESTS and
   handed to `devcards.docindex`, which owns every byte of layout.

   THIS HALF KEEPS THE PRIVILEGES — the ui.WidgetType enum, the committed JSON
   FileDescriptorSet, corpus/spec.edn, corpus/composition.edn, the conventions
   manifest, GraalWasm, and the per-card JPEGs it writes. Everything downstream
   of a render crosses the seam as FINISHED data, because none of it is
   expressible as a reference the render-free half could resolve: a props table
   is rows of cells by the time it leaves here, and an image is a literal
   filename.

   WHAT THE SPLIT BOUGHT. The index used to restate the same hand-kept list of
   unit classes in five places — the emitted sections, the output directories,
   the slug literals, the preview map, and a page count written as
   `registry + 3` — of which only the first was visible in the output. A new
   class took five coordinated edits and a missed one shipped green. There is
   now ONE list, the vector of units `generate!` assembles, and the page count
   is derived from it.

   The manifests are an IN-MEMORY intermediate, never written: see
   `devcards.docindex`'s docstring for why, and for what that costs.

   The F2 gotchas this generator still pins:
   - The props schema source is output/json-descriptors/descriptor-set.json
     — docs/.protodoc/proto-db.edn LACKS the ui package (protogen gap,
     Backlog), so the descriptor IS the one usable home. It is walked HERE and
     nowhere else; the render-free half holds no descriptor and could never
     look a message name up.
   - The registry is ENUM-DERIVED, never hand-typed: WidgetType values come
     from the descriptor, each resolved to its WidgetNode widget_props
     oneof arm, its corpus spec class, and its conventions widget-states
     row — any missing/extra mapping FAILS generation (an undeclared
     WidgetType is red by construction). That claim crosses the seam as a
     closed-set stamp, which `devcards.docindex` re-checks arithmetically.
   - Markdown is hand-rolled GFM (the F2 buy-before-build survey: pipe
     tables + image refs are the whole surface; a templating dep would hide
     the byte shape a DO-NOT-EDIT page should keep visible).
   - Every table gets a REAL header row — md-table REJECTS blank headers
     (the F2 index-grid defect, pinned mechanically). Because a manifest
     carries structured rows rather than finished GFM, that refusal now fires
     on the CONSUMER side of the seam too: a malformed table cannot be handed
     across and cheerfully written out.

   PROSE WITH NO UPSTREAM HOME. Every page lede, the committed-states
   explainer, the shape-only footnote, the index lede and both index blurbs are
   string literals below, and they carry interpolated counts. This namespace
   owns both the sentence and the number in it and regenerates them together.
   It is authored English with no source of truth — the one honest gap the
   split surfaces rather than papers over.

   Cross-link fact (documented not guessed): the ui_ast.proto and
   protodoc-index links are written for the protogen root layout
   (docs/widgets/<WIDGET>/ sits 3 segments below the repo root). The per-card
   images and inter-page links are colocated and resolve everywhere.

   Shapes are closed and hand-validated (no malli dependency); the
   descriptor subset is walked string-keyed — protobuf's JSON descriptor
   uses camelCase + literal `[buf.validate.field]` option keys, both
   dishonest as Clojure keywords."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [devcards.conventions :as conventions]
            [devcards.docgen :as docgen]
            [devcards.docindex :as docindex]
            [devcards.gallery :as gallery]
            [devcards.jpeg :as jpeg])
  (:import (java.io File)
           (java.util.regex Pattern)))

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

(def audit-root
  "The root `devcards.docgen/audit` reconciles this generator's claimed
   paths against — the PARENT of `out-dir`, deliberately.

   It is the tree CI diffs (`git diff --exit-code tools/devcards/goldens
   tools/devcards/docs`), and the audit has to cover exactly that: a file
   sitting under `docs/` but outside `docs/widgets/` is inside the tree the
   freshness gate treats as generated while being claimed by nothing, which
   is the orphan case in its purest form. Auditing `out-dir` instead would
   leave that one blind spot open under a root whose whole point is that
   everything in it is generated. Measured: every file under `docs/` today
   is under `docs/widgets/` and is claimed, so the wider root costs nothing
   and closes the gap.

   IT IS ALSO THE PRUNE'S ROOT, and the gap between the two roots is exactly
   what keeps the prune narrow. `orphan-disposition` is built over `out-dir`,
   so an orphan in the band between the two — under `docs/`, outside
   `docs/widgets/` — is reconciled and REPORTED but can never be deleted."
  "docs")

(def sinks-unit-id
  "The kitchen-sinks unit's id — THE ONE HOME of that name. It is the page
   directory, the colocated image slug and the manifest's `:unit/id` at once, so
   a second copy would be a second convention waiting to disagree."
  "kitchen-sinks")

(def legos-unit-id
  "The composition-legos unit's id — one home, for the same reason."
  "legos")

(defn- unit-dir
  "Where a unit's colocated artifacts are written. `devcards.docindex/page-path`
   puts that unit's page INSIDE this same directory; if the two ever disagreed,
   every image reference on the page would resolve outside it and
   `devcards.docindex/validate-image-refs!` would refuse the run."
  ^String [^String unit-id]
  (str out-dir "/" unit-id))

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

;; ── Provenance + prose with no upstream home ────────────────────────────
(def regen-cmd
  "The DO-NOT-EDIT banner's regenerate clause, hand-wrapped exactly as
   committed."
  "`clojure -M:bindings:run gallery` from the devcards tool root\n(tools/devcards/)")

(def sources-note
  "The banner's sources clause. ONE hand-wrapped string, never a vector a
   renderer joins: the committed banner's line breaks fall INSIDE a source
   entry, so any structured shape would reflow the banner on every page for no
   semantic gain.

   The manifest schema makes provenance PER UNIT, which is the point — this one
   shared value names corpus/spec.edn and the descriptor even on the
   composition page, whose real source is corpus/composition.edn. As data that
   inaccuracy is a one-line fix rather than a generator change; it is not taken
   here, because taking it would change committed bytes and that belongs in its
   own deliberate change rather than riding along with the split."
  (str "corpus/spec.edn (cards + notes) +\n"
       "conventions/ui-render-conventions.edn (:widget-states, :state-selectors) +\n"
       "output/json-descriptors/descriptor-set.json (props schema) + the colocated\n"
       "per-card JPEGs rendered from the pinned controls.wasm"))

(def ^:private provenance
  "The DO-NOT-EDIT banner halves every unit carries."
  {:provenance/regen-cmd regen-cmd :provenance/sources sources-note})

(def committed-states-explainer
  "The distinctness/inertness paragraph above every widget's state list."
  (str "The asgard theme commits to rendering each state below **visually "
       "distinct** from `default` (gate-held: distinctness). Any state *not* "
       "listed renders identical to `default` (inertness — hovered-on-a-label "
       "is the canonical probe)."))

(def props-shape-only-note
  "The footnote under every props section — a headingless block following the
   headed table or prose, which is how the manifest vocabulary does without a
   trailing-prose slot."
  (str "*Shape-only: the committed JSON descriptor carries no `SourceCodeInfo` "
       "comments and `docs/.protodoc/proto-db.edn` does not cover the `ui` "
       "package, so no per-field prose exists to include (protogen backlog).*"))

(defn- widget-lede
  "A widget page's lede, with its card count resolved."
  ^String [^String tag card-count]
  (str "`" tag "` — " card-count " atomic corpus cards (state × size[/value], ids `" tag
       "/<state>/<size>[/<value>]`), rendered unstyled so everything unset falls "
       "through to the loaded theme — the object under test. One image per card × "
       "family, cropped to the card's dump_tree content box; the row caption is the "
       "card-id tail."))

;; ── The blocks this emitter owns ────────────────────────────────────────
(def render-sets
  "The committed gallery render sets narrowed to the two keys the image grid
   reads. NARROWED rather than passed whole, so the render-free half never sees
   render vocabulary; narrowed FROM the published var rather than restated, so
   there is no second copy to drift."
  (mapv #(select-keys % [:title :file-suffix]) gallery/family-renders))

(def preview-suffix
  "The render set the index previews with. It belongs on this side: it is a
   fact about how the images were rendered, and the other half only ever sees
   the resulting filename."
  "asgard-dark")

(def caption-header
  "The image grid's caption-column header. It stays this literal even under a
   heading that is not about states — the grid's own default, made explicit as
   data so no caller has to know a default."
  "state")

(defn props-block
  "A widget's props-message fields as an index-vocabulary block: a `:table`
   when the message has fields, a `:prose` note when it has none
   (ObjProps/ButtonProps — no fields is a real, documentable shape, not a
   generator failure). SAME heading either way, and the footnote under it is
   the caller's separate headingless block: the empty case is a different
   VALUE, never a branch in the renderer.

   Rows are `message-fields` output straight off the descriptor. THIS IS THE
   ONE PLACE THE PROPS SCHEMA COMES FROM. Deriving it from anything else — most
   temptingly, reading it back out of the very page it is about to reproduce —
   makes the resulting byte comparison circular, proving only that a substring
   survived a round trip."
  [^String message-name ^String qualified-name fields]
  (merge {:section/heading (str "Props schema — `" message-name "` (`" qualified-name "`)")}
         (if (empty? fields)
           {:section/kind :prose
            :prose/text (format (str "`%s` carries **no widget-specific properties** — this "
                                     "widget draws entirely from the shared `WidgetNode` "
                                     "contract (`text`, `children`, `style_groups`, "
                                     "`states`).")
                                qualified-name)}
           {:section/kind :table
            :table/headers ["field" "number" "type" "constraints"]
            :table/rows (mapv (juxt :name :number :type :constraints) fields)})))

(defn- grid-block
  "A unit's card grid: one row per card, its caption in column one and one
   literal filename per render set. `rows` is `card-files!` output, which is
   already the grid's row shape."
  [^String heading rows]
  {:section/kind :grid
   :section/heading heading
   :grid/caption-header caption-header
   :grid/render-sets render-sets
   :grid/rows (vec rows)})

(defn- preview-block
  "A unit's index preview as a first-class `:image` block, so the block
   validator can see it and the disk audit can be held against it."
  [^String alt ^String unit-id ^String preview]
  {:section/kind :image
   :image/alt alt
   :image/src (str "./" unit-id "/" preview)
   :image/href (str "./" unit-id "/README.md")})

;; ── Rendering + writing the per-card artifacts ──────────────────────────
(defn- card-file-name
  "The colocated per-card artifact name: <slug>-<state>-<family>.jpg. One
   JPEG per (card, family), no label baked in — the caption lives in the grid.
   THE ONE HOME of this convention: the filename is carried into the manifest
   literally, so nothing downstream re-derives it."
  ^String [^String slug ^String state-slug fam]
  (str slug "-" state-slug "-" (:file-suffix fam) ".jpg"))

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
                                 :file (docgen/write-bytes!
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
  "The index-preview filename for a unit: its first card's preview-set image.
   A MISSING one THROWS. It has to: a nil here ships an image target that
   resolves to the unit's DIRECTORY, which exists — so the page renders, the
   link is dead, and the index is green."
  ^String [^String unit-id rows]
  (or (get-in (first rows) [:imgs preview-suffix])
      (throw (ex-info "unit has no preview image for the index"
                      {:unit unit-id :render-set preview-suffix}))))

;; ── Unit manifests ──────────────────────────────────────────────────────
(defn widget-manifest
  "One WidgetType's manifest. `order` is its enum ordinal, which doubles as its
   `:set/index` in the closed-set stamp — the arithmetic the other half checks
   in place of the enum it cannot see."
  [{:keys [enum message-name qualified-name fields widget states]} rows order total]
  (merge provenance
         {:manifest/version 1
          :unit/id enum
          :unit/order order
          :unit/page? true
          :unit/index? false
          :page/title enum
          :page/lede (widget-lede (:tag widget) (count (:cards widget)))
          :page/sections
          [(grid-block "States" rows)
           ;; "## Committed states" is a HEADED prose block followed by a
           ;; HEADINGLESS bullets block, which is why the vocabulary needs no
           ;; lede slot...
           {:section/kind :prose
            :section/heading "Committed states"
            :prose/text committed-states-explainer}
           {:section/kind :bullets
            :bullets/items (mapv #(str "`" (name %) "`") states)}
           ;; ...and "## Props schema" is a HEADED table (or prose) followed by
           ;; a HEADINGLESS footnote, which is why it needs no tail slot either.
           (props-block message-name qualified-name fields)
           {:section/kind :prose :prose/text props-shape-only-note}
           {:section/kind :prose
            :section/heading "Known limitations"
            :prose/text (if (str/blank? (str (:notes widget)))
                          "None recorded in the corpus spec for this class."
                          (:notes widget))}]
          :page/cross-links
          [{:link/text "`ui_ast.proto`" :link/href "../../../proto/ui/ui_ast.proto"}
           {:link/text "protodoc index" :link/href "../../index.md"}
           {:link/text "widget gallery index" :link/href "../README.md"}]
          :index/group "widgets"
          :index/heading nil
          :index/order-range [0 (dec total)]
          :index/blocks
          [{:section/kind :table
            :section/merge :widget-index
            :table/headers ["widget" "committed states" "preview (asgard dark)"]
            :table/rows [[(str "[`" enum "`](./" enum "/README.md)")
                          (str/join ", " (map #(str "`" (name %) "`") states))
                          (str "[![" enum "](./" enum "/" (unit-preview enum rows)
                               ")](./" enum "/README.md)")]]}]
          :provenance/closed-set {:set/id "ui.WidgetType"
                                  :set/size total
                                  :set/index order}}))

(defn sinks-manifest
  "The kitchen-sinks unit. A sink is not a WidgetType, so it is its OWN unit —
   and that now costs one manifest, not five coordinated generator edits. No
   closed-set stamp: the class is OPEN and may come and go freely, which is
   exactly the asymmetry the design wants."
  [sinks tag->enum rows]
  (let [unit sinks-unit-id]
    (merge provenance
           {:manifest/version 1
            :unit/id unit
            :unit/order 100
            :unit/page? true
            :unit/index? false
            :page/title "Kitchen sinks"
            :page/lede (str "The " (count rows) " authored multi-widget compositions "
                            "from the same corpus the gates verify — the only cells where "
                            "widgets render as neighbors. Each is a whole composite screen, "
                            "cropped to the sink container: one image per sink × family.")
            :page/sections
            [(grid-block "Screens" rows)
             {:section/kind :table
              :section/heading "The compositions"
              :table/headers ["sink" "member widgets" "what it proves"]
              :table/rows
              (mapv (fn [{:keys [id widgets description]}]
                      [(str "`" id "`")
                       (str/join ", "
                                 (for [tag widgets
                                       :let [e (or (get tag->enum tag)
                                                   (throw (ex-info
                                                           "sink member tag has no widget page"
                                                           {:sink id :tag tag})))]]
                                   (str "[" e "](../" e "/README.md)")))
                       description])
                    sinks)}]
            :page/cross-links [{:link/text "widget gallery index" :link/href "../README.md"}]
            :index/group "kitchen-sinks"
            :index/heading "Kitchen sinks"
            :index/order-range [100 100]
            :index/blocks
            [{:section/kind :prose
              :prose/text (str (count rows) " authored multi-widget compositions render "
                               "on their own page: [kitchen sinks](./kitchen-sinks/README.md).")}
             (preview-block "kitchen sinks" unit (unit-preview unit rows))]})))

(defn legos-manifest
  "The composition-legos unit, from the authored-composition inventory. Also
   OPEN — no closed-set stamp."
  [cards rows]
  (let [unit legos-unit-id]
    (merge provenance
           {:manifest/version 1
            :unit/id unit
            :unit/order 200
            :unit/page? true
            :unit/index? false
            :page/title "Composition legos"
            :page/lede (str "The " (count cards) " authored-composition cards from "
                            "`corpus/composition.edn` — the public `devcards.legos` builders "
                            "compiled through the authored lane "
                            "(`devcards.fixtures/build-authored-card`), the only corpus cells "
                            "carrying events, absolute placement, and part-selector styling. "
                            "Interaction contracts (press-seek, drag, the ext-click halo, dock "
                            "event identities) are gate-held on BOTH engines; the images "
                            "document the pixels. Each is cropped to the lego's own box — the "
                            "scrubber's includes its transparent hit-halo wrapper.")
            :page/sections
            [(grid-block "States" rows)
             {:section/kind :table
              :section/heading "The cards"
              :table/headers ["card" "lego" "what it proves"]
              :table/rows (mapv (fn [{:keys [id lego notes]}]
                                  [(str "`" id "`") (str "`" (name lego) "`") notes])
                                cards)}]
            :page/cross-links [{:link/text "widget gallery index" :link/href "../README.md"}]
            :index/group "legos"
            :index/heading "Composition legos"
            :index/order-range [200 200]
            :index/blocks
            [{:section/kind :prose
              :prose/text (str "The authored-composition corpus — the public "
                               "`devcards.legos` builders (media scrubber + foldable "
                               "stage-manager dock) with their gate-held interaction "
                               "contracts — renders on its own page: "
                               "[composition legos](./legos/README.md).")}
             (preview-block "composition legos" unit (unit-preview unit rows))]})))

(defn states-legend-manifest
  "The states legend: a unit with index blocks and NO page. It is what keeps
   \"unit\" from silently meaning \"page\" — a projection of the conventions
   manifest belongs on the index and nowhere else, and it rides exactly the
   same group machinery as everything that does have a page. Its rows ride as a
   real table block, width-checked on both sides of the seam, rather than as
   pre-rendered markdown smuggled through a blurb."
  [state-selectors]
  (merge provenance
         {:manifest/version 1
          :unit/id "states-legend"
          :unit/order 900
          :unit/page? false
          :unit/index? false
          :index/group "states-legend"
          :index/heading "States legend"
          :index/order-range [900 900]
          :index/blocks
          [{:section/kind :prose
            :prose/text (str "Card captions are card-id tails "
                             "(`<state>/<size>[/<value>]`); the state vocabulary below is a "
                             "generated projection of `ui-render-conventions.edn` "
                             "`:state-selectors` (the manifest is the one home).")}
           {:section/kind :table
            :table/headers ["state" "lv_state bit"]
            :table/rows (mapv (fn [[k v]] [(str "`" (name k) "`") v])
                              (sort-by val state-selectors))}]}))

(defn index-manifest
  "The index is a UNIT TOO — same page frame, same banner, same provenance;
   only its body comes from the fold instead of from itself. Its `:unit/order`
   sorts it ahead of every group, which is where its H1 and lede belong."
  [widget-count]
  (merge provenance
         {:manifest/version 1
          :unit/id "index"
          :unit/order -1
          :unit/page? true
          :unit/index? true
          :page/title "Widget gallery"
          :page/lede (str "Per-widget rendered doc pages, generated from the same corpus "
                          "the devcard gates verify — all " widget-count
                          " `ui.WidgetType` values, enum-derived (an undeclared WidgetType "
                          "fails generation). Previews are one asgard-dark card; each page "
                          "adds vanilla + asgard-light per card.")}))

;; ── Retirement: which stale files under the doc tree may be DELETED ─────
;; Two questions, kept apart on purpose because they fail differently.
;;
;;   IS THE RUN ENTITLED TO CALL AN ABSENCE A RETIREMENT?  `required-pages`
;;   below answers it, and `devcards.docgen/prune-orphans!` enforces the
;;   answer. That is where "retired" is separated from "this run did not
;;   produce it".
;;
;;   IS THIS PARTICULAR FILE ONE OF OURS?  `orphan-disposition` answers it,
;;   and it is a question purely about the NAMING SCHEME this emitter owns.
;;   That is where "the retired unit's own artifacts" is separated from
;;   "anything else that happens to sit under the doc tree".
;;
;; Neither answer is sufficient alone: totality without the naming policy
;; sweeps a stray file the moment a unit is retired, and the naming policy
;; without totality deletes a live unit's whole directory on a bad run.

(def ^:private page-basename
  "The basename `devcards.docindex/page-path` gives a unit's page. DERIVED by
   calling that fn rather than spelled here, so the recogniser cannot drift
   from the writer — the same reason `card-file-name` is the one home of the
   image name."
  (let [p (docindex/page-path "R" {:unit/id "U"})]
    (subs p (inc (long (str/last-index-of p "/"))))))

(defn- card-file-pattern
  "The regex matching every image name `card-file-name` can produce for
   `unit-id` — one alternative per committed render set.

   BUILT BY CALLING `card-file-name` with a sentinel state slug and splitting
   its output on the sentinel, so the recogniser is a projection of the writer
   instead of a second copy of its format. Move the format and this pattern
   moves with it; the alternative is a literal `<slug>-<state>-<family>.jpg`
   spelled twice, one of which would eventually stop matching what is on disk
   and would then quietly classify every real artifact as `not ours`.

   `[^/]+` for the state slug is exact rather than lax: `gallery/state-slug`
   replaces every `/` in a card-id tail with `_`, and `gallery/cell-label`
   refuses an id with no tail at all, so the slug is always a non-empty
   separator-free token."
  ^Pattern [^String unit-id]
  (let [sentinel " STATE "
        sentinel-re (Pattern/compile (Pattern/quote sentinel))
        alt (fn [fam]
              (let [[pre post] (str/split (card-file-name unit-id sentinel fam)
                                          sentinel-re 2)]
                (str (Pattern/quote pre) "[^/]+" (Pattern/quote post))))]
    (Pattern/compile (str "^(?:" (str/join "|" (map alt gallery/family-renders)) ")$"))))

(defn orphan-disposition
  "A `devcards.docgen/prune-orphans!` disposition over `units-root` (the
   directory unit directories are children of — `out-dir` in production).

   Answers `:retired` for a path this emitter's own naming scheme could have
   produced for the unit directory it sits in, and a NAMED REASON for
   everything else. Not a boolean: a rule that declines must not read like a
   rule that never looked, and the reason is what the caller reports.

   `:retired` covers BOTH shapes of retirement with one clause, which is the
   point of putting the totality precondition somewhere else. A card dropped
   from `corpus/spec.edn` orphans an image inside a directory the run still
   writes to; a whole unit dropped from `ui.WidgetType` orphans an entire
   directory the run no longer names. Under a run whose declared pages were
   all written, both are gone from the corpus and neither needs the live unit
   set consulted to know it.

   Everything else is a REASON and is kept:
   - `:outside-the-unit-tree` — under the audited root but not under
     `units-root` at all. The audited root is deliberately the PARENT of
     `units-root`, so this is the class the audit exists to see and the prune
     must never touch.
   - `:at-the-tree-root` — a file directly in `units-root`, which is where the
     index page lives. The index is a constant unit and cannot be orphaned; a
     file that turns up here unclaimed is not this emitter's to remove.
   - `:below-a-unit-directory` — nested deeper than `<unit>/<file>`. Nothing
     here writes at that depth.
   - `:not-an-emitter-artifact` — right depth, wrong name.

   Paths arrive CANONICAL (that is the identity `docgen/audit` reconciles on),
   so `units-root` is canonicalised to match. `File.getCanonicalPath` does not
   require the path to exist, so this is safe to build before a tree does."
  [^String units-root]
  (let [prefix (str (.getCanonicalPath (File. units-root)) File/separator)
        sep-re (Pattern/compile (Pattern/quote File/separator))]
    (fn [^String path]
      (if-not (str/starts-with? path prefix)
        :outside-the-unit-tree
        (let [parts (str/split (subs path (count prefix)) sep-re)]
          (case (count parts)
            1 :at-the-tree-root
            2 (let [[unit-id fname] parts]
                (if (or (= fname page-basename)
                        (boolean (re-matches (card-file-pattern unit-id) fname)))
                  :retired
                  :not-an-emitter-artifact))
            :below-a-unit-directory))))))

(defn required-pages
  "The page paths this run MUST write for its absences to count as
   retirements — `devcards.docgen/prune-orphans!`'s totality precondition.

   DECLARED, NEVER OBSERVED. Every entry is resolved from the enum-derived
   registry and the two open-class unit-id constants BEFORE a single card is
   rendered, so checking it against what the run claimed is a real comparison
   rather than the run agreeing with itself. Derive this from the manifests
   the render produced and the check becomes circular: it would then hold for
   any subset the generator happened to emit, which is the exact reading that
   turns a crashed render into a mass deletion.

   The registry is what makes it trustworthy, and the guard is one it already
   carries: `widget-registry` resolves the WidgetType enum in BOTH directions
   — a spec widget class outside the enum throws just as an enum value with no
   spec class does. A descriptor truncated to fewer WidgetType values (the one
   input that could shrink this set without anybody editing the corpus)
   therefore cannot produce a registry at all while `corpus/spec.edn` still
   declares the classes it dropped.

   The states legend is absent because it declares no page (`:unit/page?
   false`); a unit that writes nothing can attest to nothing."
  [registry]
  (-> (mapv (fn [{:keys [enum]}] (docindex/page-path out-dir {:unit/id enum})) registry)
      (conj (docindex/page-path out-dir {:unit/id sinks-unit-id})
            (docindex/page-path out-dir {:unit/id legos-unit-id})
            (docindex/page-path out-dir {:unit/index? true}))))

;; ── Generation (renders + writes) ───────────────────────────────────────
(defn generate!
  "The T2.7 build: for every WidgetType — one JPEG per card x family + README.md
   under docs/widgets/<ENUM>/ — plus docs/widgets/kitchen-sinks/,
   docs/widgets/legos/ (the authored-composition corpus), and the index.
   `opts` = {:spec <parsed corpus spec> :built <build-all output>
   :composition {:cards <inventory cards> :built <composition build-all
   output>} :paths {:wasm :assets}}. Returns {:files [{:path :bytes}]
   :images n :pages n :cells n :prune <prune-orphans! result>} for the
   caller's report; every gap (a widget with zero built cards, a sink or
   composition card absent from the build) throws.

   ORDER IS LOAD-BEARING: every JPEG is rendered, then the WHOLE unit set is
   assembled, then the set is validated and every page rendered, and only then
   is a single page written. A set-level refusal — a class reaching the index
   through no group, a closed set that lost a member or got ordered against its
   own indices — therefore fires before any README lands on disk.

   AND THE PRUNE IS LAST, after the final write and after `validate-image-refs!`.
   It reconciles the tree against the COMPLETE claim, so it cannot run before
   the claim is complete; and running it after image-ref validation means a
   page that points at an artifact nobody wrote refuses the run while every
   file it was about to judge is still on disk.

   IT NARRATES ITS OWN DELETIONS, which is a deliberate exception to this fn
   leaving the report to its caller. Every other outcome here is a value the
   caller prints; a deletion has already happened by the time the value is
   returned, and its only other record is the operator's git working tree."
  [{:keys [spec built composition paths]}]
  (let [conv (conventions/load-conventions)
        descriptor (load-descriptor descriptor-path)
        registry (widget-registry descriptor spec (:widget-states conv))
        ;; DECLARED HERE, one line after the registry and long before anything
        ;; renders — see `required-pages` for why observing it later would make
        ;; the totality check circular.
        required (required-pages registry)
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
        ;; pmap preserves order, so the unit sequence stays deterministic.
        widget-units
        (vec (pmap (fn [[order {:keys [enum widget] :as entry}]]
                     (let [entries (get atomic-by-widget (:tag widget))
                           dir (unit-dir enum)]
                       (when (empty? entries)
                         (throw (ex-info "widget class has ZERO built cards"
                                         {:enum enum :tag (:tag widget)})))
                       (let [{:keys [files rows]} (card-files! paths canvas dir enum entries)]
                         {:manifest (widget-manifest entry rows order (count registry))
                          :images files})))
                   (map-indexed vector registry)))
        sink-dir (unit-dir sinks-unit-id)
        {sink-imgs :files sink-rows :rows} (card-files! paths canvas sink-dir
                                                        sinks-unit-id built-sinks)
        comp-built (:built composition)
        _ (when (not= (count comp-built) (count (:cards composition)))
            (throw (ex-info "built composition cards disagree with the inventory"
                            {:built (mapv :id comp-built)
                             :inventory (mapv :id (:cards composition))})))
        legos-dir (unit-dir legos-unit-id)
        {legos-imgs :files legos-rows :rows} (card-files! paths canvas legos-dir
                                                          legos-unit-id comp-built)
        ;; THE ONE LIST. A new unit class is one more entry here and nothing
        ;; else — no directory literal, no slug literal, no preview entry, no
        ;; page-count arithmetic.
        units (-> widget-units
                  (conj {:manifest (sinks-manifest (:kitchen-sinks spec) tag->enum sink-rows)
                         :images sink-imgs}
                        {:manifest (legos-manifest (:cards composition) legos-rows)
                         :images legos-imgs}
                        {:manifest (states-legend-manifest (:state-selectors conv))
                         :images []}
                        {:manifest (index-manifest (count registry))
                         :images []}))
        manifests (mapv :manifest units)
        page-text (into {} (docindex/pages out-dir manifests))
        files (vec (mapcat
                    (fn [{:keys [manifest images]}]
                      (let [path (docindex/page-path out-dir manifest)]
                        (cond-> (vec images)
                          (:unit/page? manifest)
                          (conj (docgen/write-text!
                                 path
                                 (or (get page-text path)
                                     (throw (ex-info
                                             "unit declares a page the renderer did not produce"
                                             {:unit (:unit/id manifest) :path path}))))))))
                    units))]
    (docindex/validate-image-refs! (seq page-text) (map :path files))
    (let [prune (docgen/run-prune!
                 {:root audit-root
                  :claimed (map :path files)
                  :required-claims required
                  :disposition (orphan-disposition out-dir)})]
      (doseq [p (:deleted prune)] (println "  DELETED (retired)" p))
      (doseq [p (:undeletable prune)] (println "  UNDELETABLE (still orphaned)" p))
      (when-let [r (:refused prune)] (println "  PRUNE REFUSED —" r))
      (println (:line prune))
      {:files files
       :images (count (filter #(str/ends-with? (:path %) ".jpg") files))
       :pages (docindex/page-count manifests)
       :cells (* (count gallery/family-renders)
                 (+ (count (filter #(= :atomic (:kind %)) built))
                    (count built-sinks)
                    (count comp-built)))
       :prune prune})))
