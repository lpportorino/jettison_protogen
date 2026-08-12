(ns devcards.fixtures
  "The PUBLIC devcard fixture builder (T2.2) — turns the corpus spec
   (`corpus/spec.edn`, the T2.1 machine-readable card inventory) into
   `ui.UiAst` Screen protobuf BYTES via protogen's OWN generated Java
   bindings. No pronto, no private authoring layer (@-macros / tokens /
   class-defs are BANNED here — the T2.1 unstyled law): every card carries
   raw renderer-public vocabulary only, and everything unset falls through
   to the loaded wasm's theme, which is the object under test.

   The builder laws (spec header `:builder-laws`, all enforced here):
   - Root nodes set explicit full-canvas PROP_WIDTH/PROP_HEIGHT (an unstyled
     root defaults to 160x160 and clips almost anything — the F1 GOTCHA:
     `controls_load_ui` builds the root WidgetNode as a CHILD of the active
     screen, so it does not inherit the screen size).
   - The root also sets PROP_PAD_ALL 0 — harness geometry, never theme
     styling: the stock card PAD_DEF=24 on the root would clip full-bleed
     cells (table 480x240, tabview 460x320, scale vertical 56x360).
   - WIDGET_BUTTON requires an explicit child WIDGET_LABEL (renderer.c
     builds an EMPTY lv_button; text is a separate child) — asserted, not
     assumed.
   - State is applied ONLY via the wire `node.states` bitmask (the card's
     authored `:states-bits`), except where a state is a widget-prop bit by
     contract (switch/checkbox `:checked` ride their props messages; those
     cards carry the residual bits only — already encoded in the spec).
   - Widget slugs derive from the WidgetType enum (`:type` keywords are
     enum names verbatim).

   Flex on a node is expressed through the WidgetNode `Layout` message,
   NEVER the PROP_FLEX_FLOW style slot, for two verified renderer.c facts:
   (1) the style route indexes `flex_flow_lut` by the PROTO FlexFlow wire
   number and SKIPS value 0, and (2) `lv_style_set_flex_flow` alone never
   sets LV_STYLE_LAYOUT, so flex would not activate; only the Layout route
   (`apply_node_layout` -> `lv_obj_set_flex_flow`) resolves LV_LAYOUT_FLEX.
   A card `:flex-flow` prop (LVGL vocabulary per the spec: 0 = ROW) is
   therefore translated to the Layout message.

   Every shape here is CLOSED and hand-validated (the tool is
   dependency-lean — no malli): an unknown card key, props key, enum
   keyword, or a props-message/widget-type mismatch throws with a
   diagnostic payload naming the exact card. A spec card the builder cannot
   express must FAIL, never silently skip.

   Two lanes share the one node builder:
   - the ATOMIC lane (build-card / build-all) — the unstyled law above:
     no events, no absolute position, no part-selector styling, no
     opacity; everything unset falls through to the loaded wasm's theme.
   - the AUTHORED-COMPOSITION lane (build-authored-card) — the EXPLICIT
     opt-in for interaction/composition cards (the palette legos): node
     x/y, an EventBinding (host-event identity fields only),
     part-selector style groups, background color/opacity, and gesture
     POSITION PROBES (a slot buffer, never a command — see § Gesture
     specs). The extension is gated by a dynamic flag bound ONLY by
     build-authored-card, so an atomic card can never smuggle an
     authored key — the unstyled law stays builder-held, not
     discipline-held.

   Requires the `ui.UiAst` classes on the classpath: deps.edn `:bindings`
   (protogen's own output/java compiled by `tools/compile-bindings.sh`).
   `build.buf/protovalidate` is a class-init dependency:
   UiAst.java references `build.buf.validate.ValidateProto` while building
   its file descriptor, before any validation is ever invoked."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [devcards.designed :as designed]
            [lvgl-codegen.generated.enums :as enums])
  (:import [com.google.protobuf ByteString]
           [ui UiAst$ArcProps UiAst$BarMode UiAst$BarProps UiAst$ButtonMatrixProps
            UiAst$ChartAxis UiAst$ChartProps UiAst$ChartSeries UiAst$ChartType
            UiAst$CheckboxProps UiAst$CmdSpec UiAst$Color UiAst$DropdownProps
            UiAst$EventBinding
            UiAst$EventTrigger UiAst$FieldPatch UiAst$FlexAlign UiAst$FlexFlow
            UiAst$GestureDeltaSign UiAst$GestureKind UiAst$GestureSpec UiAst$HostProxyProps
            UiAst$ImageProps UiAst$NdcYSense UiAst$PatchKind
            UiAst$LabelLongMode UiAst$LabelProps UiAst$Layout UiAst$LedProps UiAst$LineProps
            UiAst$Point UiAst$ProxyMode UiAst$RollerProps UiAst$ScaleMode UiAst$ScaleProps
            UiAst$ScaleSection UiAst$Screen UiAst$SliderProps UiAst$SpinboxProps
            UiAst$SpinnerProps UiAst$StyleGroup UiAst$StyleProperty UiAst$StylePropertyType
            UiAst$StyleVariant UiAst$SwitchProps UiAst$TableProps UiAst$TabviewProps
            UiAst$TargetBox UiAst$TargetOverlayProps
            UiAst$TextareaProps UiAst$WidgetNode UiAst$WidgetNode$Builder
            UiAst$WidgetType]))

(set! *warn-on-reflection* true)

(def spec-path
  "The corpus spec (tool-relative). Protogen home: tools/devcards/corpus/."
  "corpus/spec.edn")

(def pb-out-dir
  "Where `-main` writes the built .pb files (gitignored transient output;
   committing built fixtures would be a deliberate contract change, not
   this tool's default)."
  "out/pbs")

;; ── Closed-shape guards ─────────────────────────────────────────────────
(defn- assert-closed
  "Throw unless every key of `m` is in the `allowed` set — the closed-shape
   guard every card / props / child map passes before any byte is built."
  [ctx allowed m]
  (when-not (map? m) (throw (ex-info (str ctx ": expected a map") {:ctx ctx :got m})))
  (let [extra (remove allowed (keys m))]
    (when (seq extra)
      (throw (ex-info (str ctx ": unknown keys (closed shape)")
                      {:ctx ctx :unknown (vec extra) :allowed (vec (sort allowed))})))))

(defn- enum-of
  "Closed keyword->enum lookup; throws naming the context on a miss."
  [table kw ctx]
  (or (get table kw)
      (throw (ex-info (str ctx ": unknown enum keyword")
                      {:ctx ctx :keyword kw :known (vec (sort (keys table)))}))))

;; ── Wire scalars ────────────────────────────────────────────────────────
(defn- ->color
  "A Color message from either a `{:r :g :b}` map (led/section/series
   colors) or a `#RRGGBB` hex string (the raw text/border color props)."
  ^UiAst$Color [v]
  (let [{:keys [r g b]} (cond (map? v) (do (assert-closed "color map" #{:r :g :b} v) v)
                              (and (string? v) (re-matches #"#[0-9a-fA-F]{6}" v))
                              (let [n (Long/parseLong (subs ^String v 1) 16)]
                                {:r (bit-and (bit-shift-right n 16) 0xFF)
                                 :g (bit-and (bit-shift-right n 8) 0xFF)
                                 :b (bit-and n 0xFF)})
                              :else (throw (ex-info "color: expected {:r :g :b} or #RRGGBB"
                                                    {:got v})))]
    (-> (UiAst$Color/newBuilder)
        (.setR (int r))
        (.setG (int g))
        (.setB (int b))
        .build)))

;; ── Raw style props (renderer-public vocabulary; no authoring registry) ─
(def ^:private style-slot-order
  "Card `:props` style keys -> [StylePropertyType slot, value flavor], in
   EMISSION order — a fixed order keeps the built bytes a deterministic
   function of the card (golden-hash prerequisite). Slots + flavors mirror
   the conventions manifest `:style-prop-slots` table verbatim."
  [[:w "PROP_WIDTH" :uint] [:h "PROP_HEIGHT" :uint] [:x "PROP_X" :int] [:y "PROP_Y" :int]
   [:pad-all "PROP_PAD_ALL" :uint] [:pad-gap "PROP_PAD_GAP" :uint]
   [:radius "PROP_RADIUS" :uint]
   [:border-width "PROP_BORDER_WIDTH" :uint] [:border-color "PROP_BORDER_COLOR" :color]
   [:text-color "PROP_TEXT_COLOR" :color] [:text-font "PROP_TEXT_FONT" :string]
   [:text-align "PROP_TEXT_ALIGN" :text-align]])

(def ^:private text-aligns
  "`ui.TextAlign` numbers. The slot rides the uint value flavor (an enum on
   the wire is a uint), so the keyword lane is what keeps a card readable."
  {:auto 0 :left 1 :center 2 :right 3})

(defn- style-prop
  "One StyleProperty with the slot's declared value flavor."
  ^UiAst$StyleProperty [^String slot flavor v]
  (let [b (-> (UiAst$StyleProperty/newBuilder)
              (.setType (UiAst$StylePropertyType/valueOf slot)))]
    (case flavor
      :uint (.setUintValue b (int v))
      :int (.setIntValue b (int v))
      :string (.setStringValue b ^String v)
      :color (.setColorValue b (->color v))
      :text-align (.setUintValue b (int (enum-of text-aligns v "text-align"))))
    (.build b)))

(defn- style-group
  "The ONE style_groups entry an unstyled card node carries: MAIN|DEFAULT
   (state_selector 0), base variant 0, the node's raw px/color/font props.
   No per-state variant is ever added — state-dependent appearance comes
   exclusively from the wire `states` bitmask, resolved by the loaded
   wasm's own theme (the object under test)."
  ^UiAst$StyleGroup [style-props]
  (let [vb (-> (UiAst$StyleVariant/newBuilder)
               (.setVariantIndex 0))]
    (doseq [[k slot flavor] style-slot-order
            :when (contains? style-props k)]
      (.addProperties vb (style-prop slot flavor (get style-props k))))
    (-> (UiAst$StyleGroup/newBuilder)
        (.setStateSelector 0)
        (.addVariants vb)
        .build)))

;; ── The authored-composition lane (explicit opt-in) ─────────────────────
;; The scoped extension the interaction/composition cards need beyond the
;; unstyled law: node x/y, an EventBinding, part-selector style groups,
;; background color/opacity, and a gesture-surface POSITION PROBE. EXACTLY
;; that surface — anything more (uids, subjects, visibility, a real cmd.Root
;; pre-encode) stays consumer-codegen territory. The probe is the one place a
;; CmdSpec is buildable here and it deliberately cannot express a command:
;; see § Gesture specs for the whole of what it admits and why.
(def ^:private ^:dynamic *authored-lane*
  "True only inside build-authored-card. Gates the authored node keys so
   the atomic lane structurally cannot carry them (an authored key on an
   atomic card is an unknown key and throws)."
  false)

(def ^:private event-triggers
  "EventBinding `:trigger` keywords -> proto EventTrigger (closed)."
  {:clicked UiAst$EventTrigger/TRIGGER_CLICKED
   :value-changed UiAst$EventTrigger/TRIGGER_VALUE_CHANGED
   :long-pressed UiAst$EventTrigger/TRIGGER_LONG_PRESSED})

(def ^:private event-keys
  "The authorable EventBinding surface: the host-event identity fields
   only. Subject mutation (set_subject/set_value/toggle/notify_host) and
   this binding's cmd arms are deliberately unauthorable here — they
   belong to consumer codegen, not to public devcards. (A gesture surface's
   own CmdSpec IS authorable, as a zero-template position probe; that is a
   different field on a different message — see § Gesture specs.)"
  #{:name :trigger :include-widget-value :int-value})

(defn- event-binding
  "A node `:event` map -> EventBinding. `:name` is required (it IS the
   host-event identity); `:trigger` defaults to :clicked; `:int-value`
   carries a static payload (e.g. an item index); `:include-widget-value`
   injects the widget's current value instead."
  ^UiAst$EventBinding [ctx m]
  (assert-closed (str ctx " :event") event-keys m)
  (when-not (and (string? (:name m)) (seq (:name m)))
    (throw (ex-info (str ctx " :event requires a non-empty string :name")
                    {:ctx ctx :event m})))
  (let [b (-> (UiAst$EventBinding/newBuilder)
              (.setName ^String (:name m))
              (.setTrigger (enum-of event-triggers
                                    (:trigger m :clicked)
                                    (str ctx " :event :trigger"))))]
    (when-some [v (:int-value m)] (.setIntValue b (int v)))
    (when (:include-widget-value m) (.setIncludeWidgetValue b true))
    (.build b)))

;; ── Gesture specs: a POSITION PROBE, never a command ────────────────────
;;
;; A gesture surface's `WidgetNode.gestures` is what makes the recognizer's
;; output observable at all: without a registered GestureSpec a drag emits
;; nothing, and (since the interpreter refuses to draw feedback for a gesture
;; that does nothing) nothing is drawn either. So the affordance contract is
;; unreachable from a devcard unless a card can register one.
;;
;; THE `cmd` PRE-ENCODE STAYS UNAUTHORABLE, AND THIS IS NOT AN EXCEPTION TO
;; THAT. A real CmdSpec's `root_template` is a pre-encoded `cmd.Root` — the
;; consumer's device vocabulary, which this tool deliberately does not carry
;; and must never grow. What a card authors here is the OTHER thing a CmdSpec
;; is: a byte buffer with fixed-width slots at declared offsets. The renderer
;; never decodes the template (cmd_patch.c memcpy's it and overwrites the
;; slots), so a buffer of ZEROS relays the gesture's own NDC doubles and
;; nothing else — a probe that reads back exactly what the interpreter wrote,
;; expressed in renderer-public vocabulary with no command in sight.
;;
;; The admitted slot kinds are therefore the FOUR NDC ones and no others.
;; DELTA / WIDGET_VALUE / SUBJECT_VALUE need a wire scale, an encoding and (for
;; the last) a subject — all of them consumer-codegen concerns — so authoring
;; one here fails loudly rather than half-working.
(def ^:private gesture-kinds
  "`:kind` keywords -> proto GestureKind (closed). Names are the enum's own."
  {:GESTURE_KIND_PAN_MOVE UiAst$GestureKind/GESTURE_KIND_PAN_MOVE
   :GESTURE_KIND_PAN_END UiAst$GestureKind/GESTURE_KIND_PAN_END
   :GESTURE_KIND_TAP UiAst$GestureKind/GESTURE_KIND_TAP
   :GESTURE_KIND_TRACK UiAst$GestureKind/GESTURE_KIND_TRACK
   :GESTURE_KIND_PINCH UiAst$GestureKind/GESTURE_KIND_PINCH
   :GESTURE_KIND_ROI UiAst$GestureKind/GESTURE_KIND_ROI})

(def ^:private patch-kinds
  "The slot kinds a devcard may author: the four NDC doubles, whose encoding
   IS their kind (the renderer refuses an NDC slot that also carries one)."
  {:ndc-x UiAst$PatchKind/PATCH_KIND_NDC_X
   :ndc-y UiAst$PatchKind/PATCH_KIND_NDC_Y
   :ndc-x2 UiAst$PatchKind/PATCH_KIND_NDC_X2
   :ndc-y2 UiAst$PatchKind/PATCH_KIND_NDC_Y2})

(def ^:private patch-keys #{:offset :width :kind})
(def ^:private cmd-probe-keys #{:command-id :template-zeros :patches :ndc-y-sense})

(def ^:private ndc-y-senses
  "`:ndc-y-sense` keywords -> proto NdcYSense (closed). Names are the enum's own.

   WHICH VERTICAL PLANE the destination reads this command's y leaves in. The
   renderer works in ONE plane (ui_input's, +y UP) and the device's NDC commands
   do not all share it, so a y-patching spec that states no plane is REFUSED at
   the decode boundary rather than defaulted — see ui.NdcYSense. A card is not
   exempt from that: it is bytes through the same loader."
  {:NDC_Y_SENSE_UP UiAst$NdcYSense/NDC_Y_SENSE_UP
   :NDC_Y_SENSE_DOWN UiAst$NdcYSense/NDC_Y_SENSE_DOWN})

(def ^:private ndc-y-patch-kinds
  "The patch kinds whose slot receives a y, and therefore oblige a plane."
  #{:ndc-y :ndc-y2})
(def ^:private gesture-delta-signs
  "`:delta-sign` keywords -> proto GestureDeltaSign (closed). Names are the
   enum's own. ANY is the zero value and the default an entry omitting the key
   takes, which is what a spec that says nothing about direction means."
  {:GESTURE_DELTA_SIGN_ANY UiAst$GestureDeltaSign/GESTURE_DELTA_SIGN_ANY
   :GESTURE_DELTA_SIGN_POSITIVE UiAst$GestureDeltaSign/GESTURE_DELTA_SIGN_POSITIVE
   :GESTURE_DELTA_SIGN_NEGATIVE UiAst$GestureDeltaSign/GESTURE_DELTA_SIGN_NEGATIVE})

(def ^:private gesture-keys #{:kind :delta-sign :cmd})

(defn- field-patch
  "One `{:offset :width :kind}` slot -> FieldPatch. `:width` must be 8 (an NDC
   slot is an 8-byte little-endian double and the renderer refuses any other
   width), and the slot must lie inside the template — asserted HERE as well as
   in the renderer, so an unbuildable card names itself instead of surfacing as
   a silent no-emit at render time."
  ^UiAst$FieldPatch [ctx template-zeros p]
  (assert-closed (str ctx " patch") patch-keys p)
  (let [{:keys [offset width kind]} p]
    (when-not (= 8 width)
      (throw (ex-info (str ctx " patch: :width must be 8 (an NDC slot is a"
                           " little-endian double)")
                      {:ctx ctx :patch p})))
    (when-not (and (int? offset) (<= 0 offset) (<= (+ offset width) template-zeros))
      (throw (ex-info (str ctx " patch: slot outside the template")
                      {:ctx ctx :patch p :template-zeros template-zeros})))
    (-> (UiAst$FieldPatch/newBuilder)
        (.setByteOffset (int offset))
        (.setByteWidth (int width))
        (.setKind (enum-of patch-kinds kind (str ctx " patch :kind")))
        ;; An NDC slot is written verbatim, so the scale is never applied; 1 is
        ;; the identity the renderer would use if it ever were.
        (.setWireScale (int 1))
        .build)))

(defn- cmd-probe
  "A `{:command-id :template-zeros :patches}` map -> CmdSpec: `:template-zeros`
   zero bytes with the declared NDC slots over them. `:command-id` is free
   provenance text the renderer never interprets."
  ^UiAst$CmdSpec [ctx m]
  (assert-closed (str ctx " :cmd") cmd-probe-keys m)
  (let [n (:template-zeros m)]
    (when-not (and (int? n) (pos? n))
      (throw (ex-info (str ctx " :cmd requires a positive :template-zeros")
                      {:ctx ctx :cmd m})))
    (let [needs-plane (some (comp ndc-y-patch-kinds :kind) (:patches m))]
      ;; Refused HERE as well as in the renderer, and for the reason the width
      ;; check above is: a card that cannot be built names itself, while the
      ;; same card built without a plane surfaces as a whole fixture the loader
      ;; rejects, one lane later, with nothing pointing at the card.
      (when (and needs-plane (not (:ndc-y-sense m)))
        (throw (ex-info (str ctx " :cmd patches an NDC y and must declare"
                             " :ndc-y-sense — the renderer refuses a y-patching"
                             " spec whose destination plane is unstated, because"
                             " guessing it ships a mirrored command")
                        {:ctx ctx :cmd m})))
      (when (and (:ndc-y-sense m) (not needs-plane))
        (throw (ex-info (str ctx " :cmd declares :ndc-y-sense but patches no NDC"
                             " y — a plane for a command with no y is a fact"
                             " about nothing")
                        {:ctx ctx :cmd m}))))
    (let [b (-> (UiAst$CmdSpec/newBuilder)
                (.setRootTemplate (ByteString/copyFrom (byte-array n))))]
      (when-some [id (:command-id m)] (.setCommandId b ^String id))
      (when-some [sense (:ndc-y-sense m)]
        (.setNdcYSense b (enum-of ndc-y-senses sense (str ctx " :cmd :ndc-y-sense"))))
      (doseq [p (:patches m)] (.addPatches b (field-patch ctx n p)))
      (.build b))))

(defn- gesture-spec
  "One `{:kind :delta-sign :cmd}` map -> GestureSpec. `:delta-sign` selects WHICH
   sign of the gesture's step the entry answers and is optional: omitting it
   means ANY, which answers every step. The key set is CLOSED, so it had to be
   widened here in the same change that added the field — a devcard authoring
   `:delta-sign` against the old set aborted rather than degrading."
  ^UiAst$GestureSpec [ctx g]
  (assert-closed (str ctx " :gestures entry") gesture-keys g)
  (-> (UiAst$GestureSpec/newBuilder)
      (.setKind (enum-of gesture-kinds (:kind g) (str ctx " :gestures :kind")))
      (.setDeltaSign (enum-of gesture-delta-signs
                              (:delta-sign g :GESTURE_DELTA_SIGN_ANY)
                              (str ctx " :gestures :delta-sign")))
      (.setCmd (cmd-probe ctx (:cmd g)))
      .build))

(def ^:private part-selectors
  "Authored style-group `:part` keywords -> LVGL part selector values
   (state bits 0 — per-state appearance stays the theme's job even in the
   authored lane). Closed: exactly the parts the composition legos target."
  {:main 0x000000 :indicator 0x020000 :knob 0x030000})

(def ^:private authored-style-slots
  "The authored group's slot table: the shared unstyled slots plus the two
   the composition lane adds — background color (a part group without one
   cannot express an indicator/knob identity) and background opacity (the
   transparent-overlay mechanism)."
  (into style-slot-order
        [[:bg-color "PROP_BG_COLOR" :color] [:bg-opa "PROP_BG_OPA" :uint]]))

(def ^:private authored-slot-keys (into #{} (map first) authored-style-slots))

(def ^:private authored-group-keys #{:part :props})

(defn- authored-style-group
  "One authored `:styles` entry {:part <kw> :props {...}} -> StyleGroup
   (variant 0, state bits 0)."
  ^UiAst$StyleGroup [ctx {:keys [part props] :as g}]
  (assert-closed (str ctx " :styles group") authored-group-keys g)
  (assert-closed (str ctx " :styles " part " :props") authored-slot-keys props)
  (when-some [opa (:bg-opa props)]
    (when-not (<= 0 (long opa) 255)
      (throw (ex-info (str ctx ": :bg-opa outside LVGL opa range 0..255")
                      {:ctx ctx :part part :bg-opa opa}))))
  (let [vb (-> (UiAst$StyleVariant/newBuilder)
               (.setVariantIndex 0))]
    (doseq [[k slot flavor] authored-style-slots
            :when (contains? props k)]
      (.addProperties vb (style-prop slot flavor (get props k))))
    (-> (UiAst$StyleGroup/newBuilder)
        (.setStateSelector (int (enum-of part-selectors part (str ctx " :styles :part"))))
        (.addVariants vb)
        .build)))

(defn- authored-style-groups!
  "Validate + emit a node's authored `:styles` groups onto builder `b`.
   Laws: non-empty, one group per part, and a :main group excludes `:props`
   style slots on the same node (two selector-0 groups would race)."
  [^UiAst$WidgetNode$Builder b ctx groups props-style]
  (when (empty? groups)
    (throw (ex-info (str ctx ": empty :styles — omit the key instead") {:ctx ctx})))
  (let [parts (mapv :part groups)]
    (when-not (= (count parts) (count (set parts)))
      (throw (ex-info (str ctx ": duplicate :part in :styles") {:ctx ctx :parts parts})))
    (when (and (seq props-style) (some #(= :main (:part %)) groups))
      (throw (ex-info (str ctx
                           ": a :main :styles group conflicts with :props"
                           " style slots (two MAIN groups) — author one")
                      {:ctx ctx :props (vec (keys props-style))}))))
  (doseq [g groups] (.addStyleGroups b (authored-style-group ctx g))))

;; ── Enum tables (proto enum names, closed) ──────────────────────────────
(def ^:private label-long-modes
  {:wrap UiAst$LabelLongMode/LABEL_LONG_MODE_WRAP
   :dots UiAst$LabelLongMode/LABEL_LONG_MODE_DOTS
   :scroll UiAst$LabelLongMode/LABEL_LONG_MODE_SCROLL
   :scroll-circular UiAst$LabelLongMode/LABEL_LONG_MODE_SCROLL_CIRCULAR
   :clip UiAst$LabelLongMode/LABEL_LONG_MODE_CLIP})

(def ^:private bar-modes
  {:normal UiAst$BarMode/BAR_MODE_NORMAL
   :symmetrical UiAst$BarMode/BAR_MODE_SYMMETRICAL
   :range UiAst$BarMode/BAR_MODE_RANGE})

(def ^:private scale-modes
  {:horizontal-top UiAst$ScaleMode/SCALE_MODE_HORIZONTAL_TOP
   :horizontal-bottom UiAst$ScaleMode/SCALE_MODE_HORIZONTAL_BOTTOM
   :vertical-left UiAst$ScaleMode/SCALE_MODE_VERTICAL_LEFT
   :vertical-right UiAst$ScaleMode/SCALE_MODE_VERTICAL_RIGHT
   :round-inner UiAst$ScaleMode/SCALE_MODE_ROUND_INNER
   :round-outer UiAst$ScaleMode/SCALE_MODE_ROUND_OUTER})

(def ^:private chart-types
  {:line UiAst$ChartType/CHART_TYPE_LINE
   :curve UiAst$ChartType/CHART_TYPE_CURVE
   :bar UiAst$ChartType/CHART_TYPE_BAR
   :stacked UiAst$ChartType/CHART_TYPE_STACKED
   :scatter UiAst$ChartType/CHART_TYPE_SCATTER})

(def ^:private chart-axes
  {:primary-y UiAst$ChartAxis/CHART_AXIS_PRIMARY_Y
   :secondary-y UiAst$ChartAxis/CHART_AXIS_SECONDARY_Y
   :primary-x UiAst$ChartAxis/CHART_AXIS_PRIMARY_X
   :secondary-x UiAst$ChartAxis/CHART_AXIS_SECONDARY_X})

(def ^:private proxy-modes
  {:static UiAst$ProxyMode/PROXY_MODE_STATIC
   :draggable UiAst$ProxyMode/PROXY_MODE_DRAGGABLE
   :resizable UiAst$ProxyMode/PROXY_MODE_RESIZABLE
   :alignable UiAst$ProxyMode/PROXY_MODE_ALIGNABLE})

(def ^:private flex-flows
  "Layout `:flow` keyword -> proto FlexFlow (the Layout-message route —
   see the ns docstring for why flex never rides the style slot)."
  {:row UiAst$FlexFlow/FLEX_FLOW_ROW
   :column UiAst$FlexFlow/FLEX_FLOW_COLUMN
   :row-wrap UiAst$FlexFlow/FLEX_FLOW_ROW_WRAP
   :column-wrap UiAst$FlexFlow/FLEX_FLOW_COLUMN_WRAP})

(def ^:private flex-aligns
  "Layout `:main-place` / `:cross-place` / `:track-place` keyword -> proto
   FlexAlign. Flex's cross axis defaults to START, so a row of mixed-height
   children top-aligns unless :cross-place says otherwise — without these the
   Layout message could only ever carry a flow, and any alignment a card asked
   for was silently dropped on the floor."
  {:start UiAst$FlexAlign/FLEX_ALIGN_START
   :end UiAst$FlexAlign/FLEX_ALIGN_END
   :center UiAst$FlexAlign/FLEX_ALIGN_CENTER
   :space-evenly UiAst$FlexAlign/FLEX_ALIGN_SPACE_EVENLY
   :space-around UiAst$FlexAlign/FLEX_ALIGN_SPACE_AROUND
   :space-between UiAst$FlexAlign/FLEX_ALIGN_SPACE_BETWEEN})

(def ^:private layout-keys
  "The closed `:layout` shape. Closed on purpose: an unsupported key here used
   to be IGNORED rather than rejected, so a card asking for an alignment the
   builder never emitted rendered wrong with no diagnostic at all."
  #{:flow :main-place :cross-place :track-place})

(def ^:private lvgl-flex-flow->kw
  "The spec's `:flex-flow` prop values are LVGL `lv_flex_flow_t` vocabulary
   (spec note: \"flex-flow 0 = LV_FLEX_FLOW_ROW\"; LVGL: ROW=0, COLUMN=1).
   Only the unambiguous base values are mapped; a wrap/reverse composite
   would be a new deliberate entry."
  {0 :row 1 :column})

(defn- obj-flag-bits
  "OR a node's flag-keyword collection into an LVGL bitmask; an unknown
   keyword throws naming the node context and the key that carried it.

   The vocabulary is `lvgl-codegen.generated.enums/obj-flag-keyword->int` READ
   DIRECTLY — the generated projection of the vendored LVGL headers, whose
   bytes `make -f renderer.mk construct-bindings` asserts equal to a live
   extraction. A local subset would agree with it only until the next LVGL
   bump, and nothing would compare the two. Both wire fields are direct-cast by
   renderer.c: `obj_flags` through `lv_obj_add_flag`, `obj_flags_clear` through
   `lv_obj_remove_flag`, so every flag the headers declare is expressible."
  ^long [ctx node-key ks]
  (reduce (fn [acc k]
            (bit-or acc (long (enum-of enums/obj-flag-keyword->int k
                                       (str ctx " " node-key)))))
          0
          ks))

;; ── Widget-props messages (closed per-message field sets) ───────────────
;; Each builder accepts exactly the fields the corpus spec uses — a spec
;; key outside the set throws, so an unsupported field STOPS its card
;; loudly instead of silently dropping wire content. Extend deliberately.
(defn- label-props
  ^UiAst$LabelProps [m]
  (assert-closed "label_props" #{:long_mode} m)
  (let [b (UiAst$LabelProps/newBuilder)]
    (when-some [v (:long_mode m)]
      (.setLongMode b (enum-of label-long-modes v "label_props :long_mode")))
    (.build b)))

(defn- slider-props
  ^UiAst$SliderProps [m]
  (assert-closed "slider_props" #{:min_value :max_value :value :seek_on_press} m)
  (let [b (UiAst$SliderProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    ;; seek_on_press (field 5): press-seek ONLY. It once also carried an
    ;; LV_DPX(24) ext-click widening, because the wire had nowhere else to put
    ;; a touch affordance; WidgetNode.hit_slop carries that now, for every
    ;; widget, so a card wanting the envelope sets hit_slop too. Still
    ;; pixel-inert by contract (the corpus goldens must not move when it
    ;; lands). False = omit (the differ strips defaults; the renderer treats
    ;; absent as stock).
    (when (:seek_on_press m) (.setSeekOnPress b true))
    (.build b)))

(defn- image-props
  ^UiAst$ImageProps [m]
  (assert-closed "image_props" #{:src} m)
  (let [b (UiAst$ImageProps/newBuilder)]
    (when-some [v (:src m)] (.setSrc b ^String v))
    (.build b)))

(defn- arc-props
  ^UiAst$ArcProps [m]
  (assert-closed "arc_props"
                 #{:start_angle :end_angle :bg_start_angle :bg_end_angle :min_value
                   :max_value :value}
                 m)
  (let [b (UiAst$ArcProps/newBuilder)]
    (when-some [v (:start_angle m)] (.setStartAngle b (int v)))
    (when-some [v (:end_angle m)] (.setEndAngle b (int v)))
    (when-some [v (:bg_start_angle m)] (.setBgStartAngle b (int v)))
    (when-some [v (:bg_end_angle m)] (.setBgEndAngle b (int v)))
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (.build b)))

(defn- bar-props
  ^UiAst$BarProps [m]
  (assert-closed "bar_props" #{:min_value :max_value :value :start_value :mode} m)
  (let [b (UiAst$BarProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (when-some [v (:start_value m)] (.setStartValue b (int v)))
    (when-some [v (:mode m)] (.setMode b (enum-of bar-modes v "bar_props :mode")))
    (.build b)))

(defn- switch-props
  ^UiAst$SwitchProps [m]
  (assert-closed "switch_props" #{:checked} m)
  (let [b (UiAst$SwitchProps/newBuilder)]
    (when (contains? m :checked) (.setChecked b (boolean (:checked m))))
    (.build b)))

(defn- checkbox-props
  ^UiAst$CheckboxProps [m]
  (assert-closed "checkbox_props" #{:checked} m)
  (let [b (UiAst$CheckboxProps/newBuilder)]
    (when (contains? m :checked) (.setChecked b (boolean (:checked m))))
    (.build b)))

(defn- dropdown-props
  ^UiAst$DropdownProps [m]
  (assert-closed "dropdown_props" #{:options :selected} m)
  (let [b (UiAst$DropdownProps/newBuilder)]
    (when-some [v (:options m)] (.setOptions b ^String v))
    (when-some [v (:selected m)] (.setSelected b (int v)))
    (.build b)))

(defn- roller-props
  ^UiAst$RollerProps [m]
  (assert-closed "roller_props" #{:options :selected :visible_row_count} m)
  (let [b (UiAst$RollerProps/newBuilder)]
    (when-some [v (:options m)] (.setOptions b ^String v))
    (when-some [v (:selected m)] (.setSelected b (int v)))
    (when-some [v (:visible_row_count m)] (.setVisibleRowCount b (int v)))
    (.build b)))

(defn- textarea-props
  ^UiAst$TextareaProps [m]
  (assert-closed "textarea_props" #{:placeholder :one_line} m)
  (let [b (UiAst$TextareaProps/newBuilder)]
    (when-some [v (:placeholder m)] (.setPlaceholder b ^String v))
    (when (contains? m :one_line) (.setOneLine b (boolean (:one_line m))))
    (.build b)))

(defn- spinbox-props
  ^UiAst$SpinboxProps [m]
  (assert-closed "spinbox_props" #{:min_value :max_value :value :step :digit_count} m)
  (let [b (UiAst$SpinboxProps/newBuilder)]
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:value m)] (.setValue b (int v)))
    (when-some [v (:step m)] (.setStep b (int v)))
    (when-some [v (:digit_count m)] (.setDigitCount b (int v)))
    (.build b)))

(defn- spinner-props
  ^UiAst$SpinnerProps [m]
  (assert-closed "spinner_props" #{:spin_time :arc_length} m)
  (let [b (UiAst$SpinnerProps/newBuilder)]
    (when-some [v (:spin_time m)] (.setSpinTime b (int v)))
    (when-some [v (:arc_length m)] (.setArcLength b (int v)))
    (.build b)))

(defn- led-props
  ^UiAst$LedProps [m]
  (assert-closed "led_props" #{:color :brightness} m)
  (let [b (UiAst$LedProps/newBuilder)]
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:brightness m)] (.setBrightness b (int v)))
    (.build b)))

(defn- line-props
  ^UiAst$LineProps [m]
  (assert-closed "line_props" #{:points :y_invert} m)
  (let [b (UiAst$LineProps/newBuilder)]
    (when-some [v (:y_invert m)] (.setYInvert b (boolean v)))
    (doseq [p (:points m)]
      (assert-closed "line point" #{:x :y} p)
      (.addPoints b
                  (-> (UiAst$Point/newBuilder)
                      (.setX (int (:x p)))
                      (.setY (int (:y p)))
                      .build)))
    (.build b)))

(defn- scale-section
  ^UiAst$ScaleSection [m]
  (assert-closed "scale section" #{:range_min :range_max :color :width} m)
  (let [b (UiAst$ScaleSection/newBuilder)]
    (when-some [v (:range_min m)] (.setRangeMin b (int v)))
    (when-some [v (:range_max m)] (.setRangeMax b (int v)))
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:width m)] (.setWidth b (int v)))
    (.build b)))

(defn- scale-props
  ^UiAst$ScaleProps [m]
  (assert-closed "scale_props"
                 #{:mode :total_tick_count :major_tick_every :label_show :min_value
                   :max_value :angle_range :text_src :sections}
                 m)
  (let [b (UiAst$ScaleProps/newBuilder)]
    (when-some [v (:mode m)] (.setMode b (enum-of scale-modes v "scale_props :mode")))
    (when-some [v (:total_tick_count m)] (.setTotalTickCount b (int v)))
    (when-some [v (:major_tick_every m)] (.setMajorTickEvery b (int v)))
    (when (contains? m :label_show) (.setLabelShow b (boolean (:label_show m))))
    (when-some [v (:min_value m)] (.setMinValue b (int v)))
    (when-some [v (:max_value m)] (.setMaxValue b (int v)))
    (when-some [v (:angle_range m)] (.setAngleRange b (int v)))
    (when-some [v (:text_src m)] (.setTextSrc b ^String v))
    (doseq [s (:sections m)] (.addSections b (scale-section s)))
    (.build b)))

(defn- buttonmatrix-props
  ^UiAst$ButtonMatrixProps [m]
  (assert-closed "buttonmatrix_props" #{:map_str} m)
  (let [b (UiAst$ButtonMatrixProps/newBuilder)]
    (when-some [v (:map_str m)] (.setMapStr b ^String v))
    (.build b)))

(defn- table-props
  ^UiAst$TableProps [m]
  (assert-closed "table_props" #{:row_count :column_count} m)
  (let [b (UiAst$TableProps/newBuilder)]
    (when-some [v (:row_count m)] (.setRowCount b (int v)))
    (when-some [v (:column_count m)] (.setColumnCount b (int v)))
    (.build b)))

(defn- tabview-props
  ^UiAst$TabviewProps [m]
  (assert-closed "tabview_props" #{:tab_names :tab_bar_size :active_index} m)
  (let [b (UiAst$TabviewProps/newBuilder)]
    (doseq [n (:tab_names m)] (.addTabNames b ^String n))
    (when-some [v (:tab_bar_size m)] (.setTabBarSize b (int v)))
    (when-some [v (:active_index m)] (.setActiveIndex b (int v)))
    (.build b)))

(defn- chart-series
  ^UiAst$ChartSeries [m]
  (assert-closed "chart series" #{:color :axis :values} m)
  (let [b (UiAst$ChartSeries/newBuilder)]
    (when-some [v (:color m)] (.setColor b (->color v)))
    (when-some [v (:axis m)] (.setAxis b (enum-of chart-axes v "chart series :axis")))
    (doseq [v (:values m)] (.addValues b (int v)))
    (.build b)))

(defn- chart-props
  ^UiAst$ChartProps [m]
  (assert-closed "chart_props" #{:series :type :point_count :div_lines} m)
  (let [b (UiAst$ChartProps/newBuilder)]
    (when-some [v (:type m)] (.setType b (enum-of chart-types v "chart_props :type")))
    (when-some [v (:point_count m)] (.setPointCount b (int v)))
    (when-some [dl (:div_lines m)]
      ;; [hdiv vdiv] — presence rides has_div_lines (0 is a VALID count;
      ;; renderer.c: lv_chart_set_div_line_count(obj, hdiv, vdiv)).
      (when-not (and (sequential? dl) (= 2 (count dl)))
        (throw (ex-info "chart_props :div_lines must be [hdiv vdiv]" {:got dl})))
      (.setHasDivLines b true)
      (.setHdivCount b (int (first dl)))
      (.setVdivCount b (int (second dl))))
    (doseq [s (:series m)] (.addSeries b (chart-series s)))
    (.build b)))

(defn- host-proxy-props
  ^UiAst$HostProxyProps [m]
  (assert-closed "host_proxy_props"
                 #{:proxy_id :mode :min_w :min_h :max_w :max_h :handle_size :z}
                 m)
  (let [b (UiAst$HostProxyProps/newBuilder)]
    (when-some [v (:proxy_id m)] (.setProxyId b ^String v))
    (when-some [v (:mode m)] (.setMode b (enum-of proxy-modes v "host_proxy_props :mode")))
    (when-some [v (:min_w m)] (.setMinW b (int v)))
    (when-some [v (:min_h m)] (.setMinH b (int v)))
    (when-some [v (:max_w m)] (.setMaxW b (int v)))
    (when-some [v (:max_h m)] (.setMaxH b (int v)))
    (when-some [v (:handle_size m)] (.setHandleSize b (int v)))
    (when-some [v (:z m)] (.setZ b (int v)))
    (.build b)))

(defn- target-box
  ^UiAst$TargetBox [m]
  (assert-closed "target box" #{:x :y :w :h :label :color} m)
  (let [b (UiAst$TargetBox/newBuilder)]
    (when-some [v (:x m)] (.setX b (int v)))
    (when-some [v (:y m)] (.setY b (int v)))
    (when-some [v (:w m)] (.setW b (int v)))
    (when-some [v (:h m)] (.setH b (int v)))
    (when-some [v (:label m)] (.setLabel b ^String v))
    (when-some [v (:color m)] (.setColor b (->color v)))
    (.build b)))

(defn- target-overlay-props
  ^UiAst$TargetOverlayProps [m]
  (assert-closed "target_overlay_props" #{:boxes :border_width :hide_labels} m)
  (let [b (UiAst$TargetOverlayProps/newBuilder)]
    (doseq [box (:boxes m)] (.addBoxes b (target-box box)))
    (when-some [v (:border_width m)] (.setBorderWidth b (int v)))
    (when-some [v (:hide_labels m)] (.setHideLabels b (boolean v)))
    (.build b)))

(def ^:private widget-props-keys
  "The `:props` keys that are WidgetNode widget-props oneof messages
   (field names verbatim from ui_ast.proto)."
  #{:label_props :slider_props :image_props :arc_props :bar_props :switch_props
    :checkbox_props :dropdown_props :roller_props :textarea_props :spinbox_props
    :spinner_props :led_props :line_props :scale_props :buttonmatrix_props :table_props
    :tabview_props :chart_props :host_proxy_props :target_overlay_props})

(def ^:private type->props-key
  "Widget type -> its ONE legal widget-props oneof key. WIDGET_OBJ and
   WIDGET_BUTTON carry none (ObjProps/ButtonProps are empty messages the
   corpus never sets)."
  {:WIDGET_LABEL :label_props
   :WIDGET_SLIDER :slider_props
   :WIDGET_IMAGE :image_props
   :WIDGET_ARC :arc_props
   :WIDGET_BAR :bar_props
   :WIDGET_SWITCH :switch_props
   :WIDGET_CHECKBOX :checkbox_props
   :WIDGET_DROPDOWN :dropdown_props
   :WIDGET_ROLLER :roller_props
   :WIDGET_TEXTAREA :textarea_props
   :WIDGET_SPINBOX :spinbox_props
   :WIDGET_SPINNER :spinner_props
   :WIDGET_LED :led_props
   :WIDGET_LINE :line_props
   :WIDGET_SCALE :scale_props
   :WIDGET_BUTTONMATRIX :buttonmatrix_props
   :WIDGET_TABLE :table_props
   :WIDGET_TABVIEW :tabview_props
   :WIDGET_CHART :chart_props
   :WIDGET_HOST_PROXY :host_proxy_props
   :WIDGET_TARGET_OVERLAY :target_overlay_props})

(defn- set-widget-props!
  "Set the node builder's widget-props oneof arm for `props-key`."
  [^UiAst$WidgetNode$Builder b props-key m]
  (case props-key
    :label_props (.setLabelProps b (label-props m))
    :slider_props (.setSliderProps b (slider-props m))
    :image_props (.setImageProps b (image-props m))
    :arc_props (.setArcProps b (arc-props m))
    :bar_props (.setBarProps b (bar-props m))
    :switch_props (.setSwitchProps b (switch-props m))
    :checkbox_props (.setCheckboxProps b (checkbox-props m))
    :dropdown_props (.setDropdownProps b (dropdown-props m))
    :roller_props (.setRollerProps b (roller-props m))
    :textarea_props (.setTextareaProps b (textarea-props m))
    :spinbox_props (.setSpinboxProps b (spinbox-props m))
    :spinner_props (.setSpinnerProps b (spinner-props m))
    :led_props (.setLedProps b (led-props m))
    :line_props (.setLineProps b (line-props m))
    :scale_props (.setScaleProps b (scale-props m))
    :buttonmatrix_props (.setButtonmatrixProps b (buttonmatrix-props m))
    :table_props (.setTableProps b (table-props m))
    :tabview_props (.setTabviewProps b (tabview-props m))
    :chart_props (.setChartProps b (chart-props m))
    :host_proxy_props (.setHostProxyProps b (host-proxy-props m))
    :target_overlay_props (.setTargetOverlayProps b (target-overlay-props m))))

;; ── Node building (the internal node shape) ─────────────────────────────
;; A node map is the card children shape plus harness extras:
;;   {:type :WIDGET_*            ; required, WidgetType enum name
;;    :props {...}               ; style slots + at most one *_props key
;;                               ;   + :flex-flow (LVGL vocab, -> Layout)
;;    :text "..."                ; node.text (labels/checkbox/textarea)
;;    :children [node ...]       ; recursive
;;    :bare true                 ; lv_obj_remove_style_all before styles
;;    :flags [:hidden]           ; obj_flags bits (lv_obj_add_flag)
;;    :flags-clear [:scrollable] ; obj_flags_clear bits (lv_obj_remove_flag)
;;    :states-bits 512           ; wire lv_state_t bitmask, verbatim
;;    :hit-slop 24               ; WidgetNode.hit_slop, design px per side
;;    :layout {:flow :row}}      ; the Layout message (kitchen sinks)
(def ^:private node-keys
  #{:type :props :text :children :bare :flags :flags-clear :states-bits :hit-slop
    :layout})

(def ^:private authored-node-keys
  "The authored-lane node shape: the atomic keys plus absolute position,
   an event binding, explicit part-selector style groups, the gesture
   position probes (§ Gesture specs — a slot buffer, never a command), and
   the designed-overlay declaration. Admitted ONLY under *authored-lane*
   (build-authored-card).

   `:designed-overlay` belongs on THIS side of the line and not in
   `node-keys`: it is a statement about a COMPOSITION — this box deliberately
   shares its pixels with what it contains — and the atomic lane renders one
   widget with nothing to compose against, so an atomic card carrying it
   could only ever declare something untrue."
  (into node-keys #{:x :y :event :styles :gestures :designed-overlay}))

(def ^:private prop-keys
  "Every key a node `:props` map may carry."
  (into (conj widget-props-keys :flex-flow) (map first style-slot-order)))

(defn- split-props
  "Split a node's `:props` into {:style {...} :widget [key m] :flow kw}.
   Throws on an unknown key, on two widget-props arms, or on a `:flex-flow`
   value outside the closed LVGL base set."
  [ctx props]
  (assert-closed (str ctx " :props") prop-keys props)
  (let [wkeys (filterv widget-props-keys (keys props))]
    (when (< 1 (count wkeys))
      (throw (ex-info (str ctx ": more than one widget-props arm (oneof)")
                      {:ctx ctx :arms wkeys})))
    {:style (select-keys props (map first style-slot-order))
     :widget (when-let [k (first wkeys)] [k (get props k)])
     :flow (when-some [ff (:flex-flow props)]
             (or (get lvgl-flex-flow->kw ff)
                 (throw (ex-info (str ctx
                                      ": :flex-flow value outside the closed"
                                      " LVGL base set (0=row, 1=column)")
                                 {:ctx ctx :flex-flow ff}))))}))

(declare build-node)

(defn- assert-button-label!
  "The button builder law: renderer.c builds an EMPTY lv_button — text is a
   separate child WIDGET_LABEL. A labelless button card renders a blank
   chip and asserts nothing, so it fails HERE."
  [ctx node]
  (when (and (= :WIDGET_BUTTON (:type node))
             (not (some #(= :WIDGET_LABEL (:type %)) (:children node))))
    (throw (ex-info
            (str ctx ": WIDGET_BUTTON without a child WIDGET_LABEL" " (builder law)")
            {:ctx ctx :node node}))))

(defn- build-node
  "One internal node map -> a built WidgetNode message (recursive). The
   admitted key set is lane-dependent: atomic by default, extended with
   the authored surface under *authored-lane*."
  ^UiAst$WidgetNode [ctx node]
  (assert-closed (str ctx " node") (if *authored-lane* authored-node-keys node-keys) node)
  (let [type-kw (:type node)
        _ (when-not (keyword? type-kw)
            (throw (ex-info (str ctx ": node :type missing") {:node node})))
        _ (assert-button-label! ctx node)
        {:keys [style widget flow]} (split-props ctx (:props node {}))
        [wkey wprops] widget
        _ (when (and wkey (not= wkey (type->props-key type-kw)))
            (throw
             (ex-info
              (str ctx
                   ": widget-props arm does not match node type"
                   " — a mismatched arm decodes as silence")
              {:ctx ctx :type type-kw :arm wkey :expected (type->props-key type-kw)})))
        _ (when-some [lay (:layout node)]
            (assert-closed (str ctx " :layout") layout-keys lay))
        flow (or flow (get-in node [:layout :flow]))
        _ (when (and (get-in node [:layout :flow])
                     (:flex-flow (:props node))
                     (not= (get lvgl-flex-flow->kw (:flex-flow (:props node)))
                           (get-in node [:layout :flow])))
            (throw (ex-info (str ctx ": :flex-flow prop conflicts with :layout")
                            {:ctx ctx :node node})))
        b (UiAst$WidgetNode/newBuilder)]
    (.setType b (UiAst$WidgetType/valueOf (name type-kw)))
    ;; Authored-lane surface (the closed-key assert above already rejected
    ;; these keys outside the lane): absolute position + the event binding.
    (when-some [x (:x node)] (.setX b (int x)))
    (when-some [y (:y node)] (.setY b (int y)))
    (when-some [e (:event node)] (.setEvent b (event-binding ctx e)))
    (doseq [g (:gestures node)] (.addGestures b (gesture-spec ctx g)))
    (when-some [t (:text node)] (.setText b ^String t))
    (when flow
      (let [lay (:layout node)
            lb (-> (UiAst$Layout/newBuilder)
                   (.setFlow (enum-of flex-flows flow (str ctx " :layout :flow"))))]
        (when-some [v (:main-place lay)]
          (.setMainPlace lb (enum-of flex-aligns v (str ctx " :layout :main-place"))))
        (when-some [v (:cross-place lay)]
          (.setCrossPlace lb (enum-of flex-aligns v (str ctx " :layout :cross-place"))))
        (when-some [v (:track-place lay)]
          (.setTrackPlace lb (enum-of flex-aligns v (str ctx " :layout :track-place"))))
        (.setLayout b (.build lb))))
    (when (seq style) (.addStyleGroups b (style-group style)))
    (when-some [groups (:styles node)] (authored-style-groups! b ctx groups style))
    (when wkey (set-widget-props! b wkey wprops))
    (when (:bare node) (.setBare b true))
    (when-some [f (:flags node)] (.setObjFlags b (int (obj-flag-bits ctx :flags f))))
    (when-some [fc (:flags-clear node)]
      (.setObjFlagsClear b (int (obj-flag-bits ctx :flags-clear fc))))
    (when-some [s (:states-bits node)]
      (when-not (<= 0 s 0xFFFF)
        (throw (ex-info (str ctx ": :states-bits outside lv_state_t range")
                        {:ctx ctx :states-bits s})))
      (.setStates b (int s)))
    ;; hit_slop: the wire's only route to a hit box larger than the drawn box.
    ;; Bounded here at the proto's own buf.validate ceiling, because the C leg
    ;; strips those annotations (scripts/proto_cleanup.awk) and nothing else
    ;; would refuse an out-of-range corpus value before the renderer saw it.
    (when-some [hs (:hit-slop node)]
      (when-not (<= 0 hs 64)
        (throw (ex-info (str ctx ": :hit-slop outside the ui.WidgetNode"
                             " hit_slop range (0..64)")
                        {:ctx ctx :hit-slop hs})))
      (.setHitSlop b (int hs)))
    ;; designed_overlay: the author declaring that this box is deliberately
    ;; shared with the interactive nodes it wholly contains, echoed by the
    ;; interpreter into dump_tree and read there by devcards.overlap. Set only
    ;; when true — the field is proto3-default false and the dump emits the key
    ;; only where it applies, so writing an explicit false would be a value the
    ;; wire cannot distinguish from absence anyway.
    (when (:designed-overlay node) (.setDesignedOverlay b true))
    (doseq [c (:children node)] (.addChildren b (build-node ctx c)))
    (.build b)))

;; ── Card -> Screen bytes ────────────────────────────────────────────────
(def ^:private card-keys
  "Every key an atomic card may carry. :designed-flags is deliberately NOT one:
   a card's designed-flag declaration lives in `corpus/designed-flags.edn`
   (`devcards.designed`) and nowhere else. Two spellings of one declaration is
   two places for it to drift, and no gate here could notice them disagreeing."
  #{:id :state :states-bits :size :props :children :wrapper :expect :notes :text :value
    :variant})

(def ^:private wrapper-keys #{:props :flags-clear :notes})

(defn- scaffolding-flags-clear
  "Keep a harness/layout wrapper out of LVGL's pointer path, preserving any
   additional flags the card explicitly clears.

   `lv_obj`'s constructor sets LV_OBJ_FLAG_CLICKABLE, so a bare container is
   hit-testable by default. Scaffolding is a LAYOUT device that never wants a
   press, and `lv_indev_search_obj` tests children before the node itself —
   so any DISABLED subject inside a hit-testable wrapper wins the search,
   absorbs the press and drops it (`devcards.deadzone` ARM 2). Clearing the
   flag resolves that BY CONSTRUCTION rather than by exemption, the same
   mechanism the overlap lane used on two decorative widgets. A flag is not a
   style: it moves no pixel and no golden."
  [flags-clear]
  (into [:clickable] (remove #{:clickable}) flags-clear))

(defn- root-wrap
  "The harness root law: a WIDGET_OBJ sized to the FULL canvas with
   PROP_PAD_ALL 0 AND PROP_BORDER_WIDTH 0 (harness geometry, never theme
   styling of the subject), wrapping the card's node. Border-zeroing is as
   load-bearing as pad-zeroing: the asgard panel border (1px) shrinks the
   root content box to canvas-2, so any full-bleed cell (the 800-wide
   flex-fit wrapper) reads clipped and the root reports scroll extent —
   measured, not hypothetical."
  [canvas node]
  {:type :WIDGET_OBJ
   :props {:w (:w canvas) :h (:h canvas) :pad-all 0 :border-width 0}
   :flags-clear (scaffolding-flags-clear nil)
   :children [node]})

(defn- screen-bytes
  "Serialize a root-wrapped node tree to Screen protobuf bytes."
  ^bytes [canvas node]
  (-> (UiAst$Screen/newBuilder)
      (.setRoot (build-node "screen" (root-wrap canvas node)))
      .build
      .toByteArray))

(defn build-card
  "Build ONE atomic card into Screen protobuf bytes. `canvas` is the spec's
   `:render :canvas` map ({:w :h}); `widget-type` the class's `:type`
   keyword; `card` one `:cards` entry. The card's optional `:wrapper`
   becomes an intermediate WIDGET_OBJ between root and subject."
  ^bytes [canvas widget-type card]
  (assert-closed (str "card " (:id card)) card-keys card)
  (let [ctx (str "card " (:id card))
        node (cond-> {:type widget-type}
               (:props card) (assoc :props (:props card))
               (:text card) (assoc :text (:text card))
               (:children card) (assoc :children (:children card))
               (pos? (long (:states-bits card 0))) (assoc :states-bits (:states-bits card)))
        node (if-some [w (:wrapper card)]
               (do (assert-closed (str ctx " :wrapper") wrapper-keys w)
                   (-> (cond-> {:type :WIDGET_OBJ :children [node]}
                         (:props w) (assoc :props (:props w)))
                       (assoc :flags-clear
                              (scaffolding-flags-clear (:flags-clear w)))))
               node)]
    (screen-bytes canvas node)))

(def ^:private authored-card-keys #{:id :node :expect :notes})

(defn build-authored-card
  "Build ONE authored-composition card into Screen protobuf bytes — the
   explicit interaction/composition lane. `card` is {:id <string> :node
   <node-map>} (+ optional :expect/:notes card meta, carried by the corpus
   not the bytes); the node map is the atomic internal shape EXTENDED with
   the authored surface: absolute :x/:y, an :event binding, and
   part-selector :styles groups (incl. background color/opacity). The
   node gets the same harness root law as an atomic card. The atomic lane
   (build-card / build-all) never admits the authored keys."
  ^bytes [canvas card]
  (assert-closed (str "authored card " (:id card)) authored-card-keys card)
  (when-not (and (string? (:id card)) (seq ^String (:id card)))
    (throw (ex-info "authored card requires a non-empty string :id" {:card card})))
  (when-not (map? (:node card))
    (throw (ex-info (str "authored card " (:id card) " requires a :node map")
                    {:card card})))
  (binding [*authored-lane* true] (screen-bytes canvas (:node card))))

;; ── Kitchen sinks (authored compositions) ───────────────────────────────
;; The spec declares the 6 sinks by id + widget list + prose description;
;; the node trees are AUTHORED here in the same unstyled raw vocabulary
;; (explicit px sizes, flex via the Layout message, zero style tokens).
;; `build-entry` cross-checks each tree against the spec's declared widget
;; list, so a drifted sink fails loudly instead of quietly thinning.
(def ^:private sink-green {:r 16 :g 185 :b 129})

(def kitchen-sink-trees
  "Sink id -> authored node tree (the container card; the harness root is
   added at build time). Compositions follow the spec descriptions:
   real theme cards (NO pad-zeroing — sink containers are themed subjects,
   unlike the harness root), medium-tier member widgets from the atomic
   corpus so a sink defect cross-references its atomic twin."
  {"kitchen-sink/form-row"
   {:type :WIDGET_OBJ
    :props {:w 760 :h 110}
    :layout {:flow :row}
    :children
    [{:type :WIDGET_LABEL :text "Preset" :props {:w 100 :label_props {:long_mode :wrap}}}
     {:type :WIDGET_TEXTAREA
      :props {:w 160 :textarea_props {:placeholder "Search..." :one_line true}}}
     {:type :WIDGET_DROPDOWN
      :props {:w 120 :dropdown_props {:options "Auto\nManual\nOff" :selected 1}}}
     {:type :WIDGET_BUTTON
      :props {:w 80 :h 36}
      :children [{:type :WIDGET_LABEL :text "Save"}]}]}
   "kitchen-sink/toolbar-strip"
   ;; Heights sized for BOTH families: stock (the vanilla family) spends
   ;; PAD_DEF=24 per edge on the matrix bg AND the sink card, so a 60px
   ;; matrix left 12px button slivers under family 1 (measured). 96px keeps
   ;; a 48px stock button (96 - 2*24) and the 152px card keeps a 104px
   ;; stock content box (152 - 2*24) that holds the matrix plus the 64px
   ;; spinbox; under family 0 the tighter asgard pads only gain room.
   {:type :WIDGET_OBJ
    :props {:w 760 :h 152}
    :layout {:flow :row}
    :children [{:type :WIDGET_BUTTONMATRIX
                :props {:w 200 :h 96 :buttonmatrix_props {:map_str "A\nB\nC\n"}}}
               {:type :WIDGET_SWITCH :props {:switch_props {:checked true}}}
               {:type :WIDGET_DROPDOWN
                :props {:w 120 :dropdown_props {:options "Auto\nManual\nOff" :selected 1}}}
               {:type :WIDGET_SPINBOX
                :props
                {:w 120
                 :h 64
                 :spinbox_props
                 {:min_value -99999 :max_value 99999 :step 1 :digit_count 3 :value 500}}}]}
   "kitchen-sink/gauge-cluster"
   {:type :WIDGET_OBJ
    :props {:w 360 :h 460}
    :layout {:flow :column}
    :children [{:type :WIDGET_ARC
                ;; A GAUGE, not a control: clearing CLICKABLE keeps it out of
                ;; the pointer path entirely. Decoration that stays clickable
                ;; is a latent dead zone — it can take a press meant for
                ;; something else, and nothing renders differently either way.
                ;;
                ;; REDUNDANT WITH THE RENDERER TODAY, and kept deliberately.
                ;; An arc carrying no event binding is taken out of the pointer
                ;; path by the renderer itself now, so this clears a flag that
                ;; would be cleared anyway. It stays because it is a statement
                ;; about THIS CARD — the atomic lane's only way to say "gauge,
                ;; not control" — and it remains true whatever the renderer
                ;; derives. Do not read it as the mechanism.
                :flags-clear [:clickable]
                :props {:w 80
                        :h 80
                        :arc_props {:start_angle 0
                                    :end_angle 270
                                    :bg_start_angle 0
                                    :bg_end_angle 360
                                    :min_value 0
                                    :max_value 100
                                    :value 50}}}
               ;; Spacer: clears the round-outer scale's middle major-tick
               ;; label out of the arc drawn directly above it — without the
               ;; standoff the label bleeds into the ring (near-unreadable
               ;; ~1.6:1 in the dark families). A bare obj, not padding: the
               ;; prop vocabulary is pad-all only, and uniform padding would
               ;; shrink the scale's drawn dial instead of separating it.
               {:bare true :type :WIDGET_OBJ :props {:w 96 :h 12}}
               {:type :WIDGET_SCALE
                :props {:w 96
                        :h 96
                        :scale_props {:min_value 0
                                      :max_value 100
                                      :total_tick_count 11
                                      :major_tick_every 5
                                      :label_show true
                                      :mode :round-outer}}}
               {:type :WIDGET_CHART
                :props {:w 200
                        :h 130
                        :chart_props {:type :line
                                      :point_count 8
                                      :div_lines [0 12]
                                      :series [{:color {:r 239 :g 68 :b 68}
                                                :axis :primary-y
                                                :values [10 25 18 40 32 48 27 35]}]}}}
               {:type :WIDGET_LED
                :props {:w 32 :h 32 :led_props {:color sink-green :brightness 255}}}]}
   "kitchen-sink/settings-panel"
   {:type :WIDGET_OBJ
    :props {:w 360 :h 440}
    :layout {:flow :column}
    :children [{:type :WIDGET_CHECKBOX
                :text "Enable telemetry"
                :props {:checkbox_props {:checked true}}}
               {:type :WIDGET_SLIDER
                :props {:w 128 :h 16 :slider_props {:min_value 0 :max_value 100 :value 50}}}
               {:type :WIDGET_SPINBOX
                :props
                {:w 120
                 :h 64
                 :spinbox_props
                 {:min_value -99999 :max_value 99999 :step 1 :digit_count 3 :value 500}}}
               {:type :WIDGET_ROLLER
                :props {:w 120
                        :roller_props
                        {:options "One\nTwo\nThree" :visible_row_count 3 :selected 1}}}]}
   "kitchen-sink/hud-overlay"
   {:type :WIDGET_OBJ
    :props {:w 760 :h 240}
    :layout {:flow :row}
    :children
    [{:type :WIDGET_HOST_PROXY
      :props {:w 320
              :h 180
              :host_proxy_props {:proxy_id "px"
                                 :mode :draggable
                                 :min_w 96
                                 :min_h 54
                                 :max_w 400
                                 :max_h 300
                                 :handle_size 16
                                 :z 1}}
      :children [{:type :WIDGET_OBJ
                  :bare true
                  :props {:w 80 :h 40}
                  :children [{:type :WIDGET_LABEL :text "LIVE"}]}]}
     {:type :WIDGET_LABEL :text "CAM-04" :props {:w 100 :label_props {:long_mode :wrap}}}
     {:type :WIDGET_LED :props {:w 32 :h 32 :led_props {:color sink-green :brightness 255}}}
     {:type :WIDGET_BUTTON
      :props {:w 80 :h 36}
      :children [{:type :WIDGET_LABEL :text "Mark"}]}]}
   "kitchen-sink/tabview-with-content"
   {:type :WIDGET_TABVIEW
    :props {:w 460
            :h 320
            :tabview_props
            {:tab_names ["Alpha" "Beta" "Gamma"] :tab_bar_size 48 :active_index 0}}
    :children (vec (for [label ["Alpha" "Beta" "Gamma"]]
                     ;; Near-fill the page: the old 360x160 card exposed a wide
                     ;; ring of the frozen-geometry STOCK page surface around
                     ;; the themed card — a near-white void against the warm
                     ;; light card. Sizing toward the page's content box keeps
                     ;; the exposure to a thin margin without risking overflow
                     ;; into the frozen tabview geometry.
                     {:type :WIDGET_OBJ
                      :props {:w 400 :h 216}
                      :layout {:flow :row}
                      :children [{:type :WIDGET_BUTTON
                                  :props {:w 80 :h 36}
                                  :children [{:type :WIDGET_LABEL :text label}]}
                                 {:type :WIDGET_SLIDER
                                  :props {:w 128
                                          :h 16
                                          :slider_props
                                          {:min_value 0 :max_value 100 :value 50}}}]}))}})

(defn- tree-types
  "The set of widget :type keywords appearing anywhere in a node tree."
  [node]
  (into #{(:type node)} (mapcat tree-types) (:children node)))

(defn- assert-sink-coverage!
  "Every widget tag the spec declares for a sink must appear in its
   authored tree (tag->type resolved through the spec — one home)."
  [spec sink tree]
  (let [tag->type (into {} (map (juxt :tag :type)) (:widgets spec))
        present (tree-types tree)
        missing (remove #(contains? present
                                    (or (tag->type %)
                                        (throw (ex-info "sink declares unknown widget tag"
                                                        {:sink (:id sink) :tag %}))))
                        (:widgets sink))]
    (when (seq missing)
      (throw (ex-info "kitchen sink tree missing spec-declared widget classes"
                      {:sink (:id sink) :missing (vec missing)})))))

;; ── Spec loading + the build surface ────────────────────────────────────
(defn load-spec
  "Read + hand-validate `corpus/spec.edn`. Asserts the spec's own
   self-description: `:card-count` equals the actual card total,
   `:kitchen-sink-count` the sink total, and the render canvas is present.
   (The mission-level expected totals live in the smoke, not here — the
   spec is the one home for its own counts.)"
  []
  (let [spec (edn/read-string (slurp spec-path))
        actual (count (mapcat :cards (:widgets spec)))]
    (when-not (and (pos? actual) (= (:card-count spec) actual))
      (throw (ex-info "spec :card-count disagrees with its own cards"
                      {:card-count (:card-count spec) :actual actual})))
    (when-not (= (:kitchen-sink-count spec) (count (:kitchen-sinks spec)))
      (throw (ex-info "spec :kitchen-sink-count disagrees with :kitchen-sinks"
                      {:kitchen-sink-count (:kitchen-sink-count spec)
                       :actual (count (:kitchen-sinks spec))})))
    (when-not (and (pos? (long (get-in spec [:render :canvas :w] 0)))
                   (pos? (long (get-in spec [:render :canvas :h] 0))))
      (throw (ex-info "spec :render :canvas missing" {:render (:render spec)})))
    spec))

(defn entries
  "The full ordered build inventory: one entry per atomic card
   ({:kind :atomic :id :widget :type :expect :card}) followed by one per
   kitchen sink ({:kind :sink :id :widgets :sink}). Spec order throughout —
   deterministic output order is part of the contract."
  [spec]
  (into (vec (for [w (:widgets spec)
                   c (:cards w)]
               {:kind :atomic
                :id (:id c)
                :widget (:tag w)
                :type (:type w)
                :expect (:expect c)
                :card c}))
        (for [s (:kitchen-sinks spec)]
          {:kind :sink :id (:id s) :widgets (:widgets s) :sink s})))

(defn build-entry
  "Build one inventory entry (atomic card or kitchen sink) to Screen
   bytes. Throws with the entry id on any law/shape violation."
  ^bytes [spec entry]
  (let [canvas (get-in spec [:render :canvas])]
    (case (:kind entry)
      :atomic (build-card canvas (:type entry) (:card entry))
      :sink (let [tree (or (get kitchen-sink-trees (:id entry))
                           (throw (ex-info "no authored tree for kitchen sink"
                                           {:id (:id entry)
                                            :authored (vec (sort (keys
                                                                  kitchen-sink-trees)))})))]
              (assert-sink-coverage! spec (:sink entry) tree)
              (screen-bytes canvas tree)))))

(defn build-all
  "Build the WHOLE corpus: every atomic card + every kitchen sink, in spec
   order. Returns a vector of {:kind :id :widget :expect :designed-flags
   :bytes}. Throws on the first failing entry (callers wanting per-card error
   collection walk `entries` + `build-entry` themselves — the smoke does).

   `:designed-flags` is attached here from `corpus/designed-flags.edn` rather
   than read off the card: it is a judging DECLARATION, never wire content, so
   nothing about it reaches the Screen bytes. It is attached at this seam
   because it is the one place every card already passes through, and the
   loader REFUSES a missing file so a lost declaration cannot read as a corpus
   that declares nothing."
  ([] (build-all (load-spec)))
  ([spec]
   (let [declared (designed/load-declarations)]
     (mapv (fn [e]
             (assoc (select-keys e [:kind :id :widget :expect])
                    :designed-flags (designed/for-card declared (:id e))
                    :bytes (build-entry spec e)))
           (entries spec)))))

(defn write-pbs!
  "Write built entries to `<dir>/<id>.pb` (ids contain '/': subdirs are
   created). Returns the file count."
  ^long [dir built]
  (doseq [{:keys [id] ^bytes pb :bytes} built
          :let [f (io/file dir (str id ".pb"))]]
    (io/make-parents f)
    (with-open [out (io/output-stream f)] (.write out pb)))
  (count built))

(defn -main
  "Build the full corpus and write the .pb files to `out/pbs/`."
  [& _]
  (let [spec (load-spec)
        built (build-all spec)
        atomic (count (filter #(= :atomic (:kind %)) built))
        sinks (count (filter #(= :sink (:kind %)) built))]
    (write-pbs! pb-out-dir built)
    (println (format "built %d screens (%d atomic + %d kitchen-sinks) -> %s"
                     (count built)
                     atomic
                     sinks
                     pb-out-dir))
    (println (format "total bytes: %d"
                     (reduce + (map #(alength ^bytes (:bytes %)) built))))))
