(ns uigen.cmd-spec
  "R5a — the native cmd-out pre-encode half (docs/ui-nodes/README.md).

   The uigen GENERATOR pre-encodes a full `cmd.Root` template at GENERATION
   time: the deterministic envelope (protocol-version + client-type +
   client-app, via `asgard.proto.cmd/wrap-in-root`) wrapping the subsystem
   command, with the command's mutable leaf(s) sitting in a FIXED-WIDTH wire
   slot. The template + a patch descriptor ride the `ui_ast` `.pb` as a
   `CmdSpec`; at runtime the renderer (R5b) memcpy's `root_template` and
   overwrites JUST the slot(s) with the live value, then relays the result as
   OPAQUE `cmd.*` bytes — controls.wasm builds the full device command itself.

   Wire-width is the load-bearing subtlety (the de-risk target):
   - DOUBLE leaves (NDC x/y on RotateToNDC/HaltWithNDC/StartTrackNDC) are
     wire-type 1 = 8 FIXED little-endian bytes → a clean fixed-offset slot
     located by a SENTINEL byte pattern. The NDC double is written VERBATIM
     (ui_input NDC convention == cmd.* NDC), so no recast — byte-identical to
     a fresh build (`wire-scale` = 1).
   - VARINT int leaves (SetZoomTableValue.value, int32, variable width)
     CANNOT be a fixed slot as a minimal varint. The template is built with a
     SENTINEL value that forces the MAX varint width (2^31-1 → 5 bytes for an
     int32), so the slot — AND every enclosing length prefix — is already
     sized for the max; the runtime patch rewrites the slot with a NON-MINIMAL
     padded varint (continuation bytes 0x80…, final byte clears bit 7) of the
     real value. A padded varint is valid wire and decodes to the same int, so
     VALUE-identity (not byte-identity) holds for a varint leaf.

   GUARDRAIL: proto appears only at the `ui_ast` wire boundary; this pre-encode
   is generation-time EDN→bytes (pronto), one layer below the `.pb` emit."
  (:require [asgard.api.backend :as backend]
            [asgard.proto.cmd :as pcmd]
            [asgard.schema :as s]
            [clojure.string :as str]
            [malli.core :as m]
            [pronto.utils :as pronto-utils]
            [uigen.resolve :as res]
            [uigen.scales :as scales]
            [uigen.wire-bounds :as wb])
  (:import [java.nio ByteBuffer ByteOrder]))

(set! *warn-on-reflection* true)

;; ── slot widths ─────────────────────────────────────────────────────────────
(def ^:private double-width
  "A protobuf double is wire-type 1 = 8 fixed little-endian bytes."
  8)

(def ^:private int32-varint-width
  "Fixed slot width for an int32 varint leaf: the MAX minimal width (2^31-1
   takes ⌈31/7⌉ = 5 bytes). The template is built at this width and the patch
   rewrites it as a non-minimal padded varint, so the slot never resizes."
  5)

(def ^:private int32-max-sentinel
  "The int32 value whose minimal varint is the full 5-byte width — used as the
   leaf SENTINEL so the template (and its length prefixes) are pre-sized for
   the widest patch. 2^31-1, a valid `gte:0` int32."
  2147483647)

;; ── varint + double wire helpers ────────────────────────────────────────────
(defn varint-le-bytes
  "The MINIMAL little-endian varint bytes of a non-negative int — used to
   LOCATE the int32 sentinel slot (the minimal encoding of 2^31-1 is what
   pronto wrote into the template)."
  ^bytes [^long v]
  (loop [v v
         acc []]
    (let [b (bit-and v 0x7f)
          v' (unsigned-bit-shift-right v 7)]
      (if (zero? v')
        (byte-array (conj acc (unchecked-byte b)))
        (recur v' (conj acc (unchecked-byte (bit-or b 0x80))))))))
(m/=> varint-le-bytes [:=> [:cat :int] bytes?])

(defn padded-varint
  "Encode non-negative `v` as a NON-MINIMAL varint padded to exactly `width`
   bytes: the low groups carry the value with bit 7 set (continuation), the
   final byte clears bit 7. A padded varint is valid wire and decodes to `v`
   (value-identity). This mirrors what R5b's C patcher writes into the slot."
  ^bytes [^long v ^long width]
  (let [out (byte-array width)]
    (loop [i 0
           r v]
      (when (< i width)
        (let [last? (= i (dec width))
              group (bit-and r 0x7f)
              b (if last? group (bit-or group 0x80))]
          (aset out i (unchecked-byte b))
          (recur (inc i) (unsigned-bit-shift-right r 7)))))
    out))
(m/=> padded-varint [:=> [:cat :int :int] bytes?])

(defn double->le-bytes
  "The 8 little-endian wire bytes of a protobuf double (the on-wire form of a
   fixed64/double field), for locating a sentinel slot AND for the de-risk
   patcher that writes an NDC double verbatim into a slot."
  ^bytes [^double d]
  (let [bb (ByteBuffer/allocate 8)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putDouble bb d)
    (.array bb)))
(m/=> double->le-bytes [:=> [:cat :double] bytes?])

(defn float->le-bytes
  "The 4 little-endian wire bytes of a protobuf float (wire-type 5, fixed32) —
   the float counterpart of double->le-bytes, for locating a form's float slot.
   Mirrors the renderer's float_le_bytes (cmd_patch.c)."
  ;; NOT a ^float param hint: Clojure supports only long/double primitive hints
  ;; on a fn arg, so the cast happens at the putFloat call instead.
  ^bytes [f]
  (let [bb (ByteBuffer/allocate 4)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putFloat bb (float f))
    (.array bb)))
(m/=> float->le-bytes [:=> [:cat number?] bytes?])

;; ── sentinel doubles (NDC x/y) ──────────────────────────────────────────────
;; Distinctive bit patterns (qNaN payloads) so the 8 wire bytes are unique in
;; the template and never collide with the envelope or each other. They exist
;; ONLY to be LOCATED; the patcher overwrites them with the real NDC double.
(def ^:private ndc-x-sentinel (Double/longBitsToDouble 0x7ff8a5a5a5a5a5a5))

(def ^:private ndc-y-sentinel (Double/longBitsToDouble 0x7ff85a5a5a5a5a5a))

;; ROI rubber-band 2nd-corner sentinels — distinct qNaN payloads so a FocusROI
;; template's four NDC slots (x1/y1/x2/y2) each locate uniquely.
(def ^:private ndc-x2-sentinel (Double/longBitsToDouble 0x7ff8c3c3c3c3c3c3))

(def ^:private ndc-y2-sentinel (Double/longBitsToDouble 0x7ff83c3c3c3c3c3c))

(def ^:private ndc-double-leaves
  "NDC double leaf field-name → its PatchKind + sentinel double. x/y are the
   shared single-point NDC convention; x1/y1/x2/y2 are the ROI rubber-band's
   two corners (x1/y1 = corner 1 → NDC_X/Y, x2/y2 = corner 2 → NDC_X2/Y2). All
   written verbatim, wire-scale 1. A single command never carries both an x/y
   and an x1/x2 leaf, so the shared corner-1 sentinels never collide in-template."
  {"x" {:kind :PATCH_KIND_NDC_X :sentinel ndc-x-sentinel}
   "y" {:kind :PATCH_KIND_NDC_Y :sentinel ndc-y-sentinel}
   "x1" {:kind :PATCH_KIND_NDC_X :sentinel ndc-x-sentinel}
   "y1" {:kind :PATCH_KIND_NDC_Y :sentinel ndc-y-sentinel}
   "x2" {:kind :PATCH_KIND_NDC_X2 :sentinel ndc-x2-sentinel}
   "y2" {:kind :PATCH_KIND_NDC_Y2 :sentinel ndc-y2-sentinel}})

(def ^:private value-double-sentinel
  "Sentinel for a non-NDC `double value` slider leaf (a distinctive qNaN
   payload, unique vs the NDC x/y sentinels)."
  (Double/longBitsToDouble 0x7ff833333333cccc))

;; ── sentinel locator ────────────────────────────────────────────────────────
(defn- index-of-bytes
  "First index where `needle` occurs in `hay`, or nil. The sentinel locator —
   the byte-offset of a leaf's fixed-width slot in the pre-encoded template."
  [^bytes hay ^bytes needle]
  (let [hn (alength hay)
        nn (alength needle)]
    (loop [i 0]
      (when (<= i (- hn nn))
        (if (loop [j 0]
              (cond (= j nn) true
                    (= (aget hay (+ i j)) (aget needle j)) (recur (inc j))
                    :else false))
          i
          (recur (inc i)))))))
(m/=> index-of-bytes [:=> [:cat bytes? bytes?] [:maybe nat-int?]])

(defn- find-slot!
  "Locate `needle`'s unique fixed-width slot in `template`, fail-loud. A
   sentinel that is absent (the leaf field name is wrong) or ambiguous (the
   sentinel pattern collides) is a build error, not a silent mis-patch."
  [^bytes template ^bytes needle ctx]
  (let [first-idx (index-of-bytes template needle)]
    (when-not first-idx
      (throw (ex-info "uigen.cmd-spec: sentinel slot not found in template"
                      (assoc ctx :template-len (alength template)))))
    ;; ambiguity guard: the sentinel must occur exactly once
    (let [after (inc first-idx)
          rest-from (byte-array (- (alength template) after))]
      (System/arraycopy template after rest-from 0 (alength rest-from))
      (when (index-of-bytes rest-from needle)
        (throw (ex-info "uigen.cmd-spec: sentinel slot is ambiguous (collides)"
                        (assoc ctx :first-index first-idx)))))
    first-idx))
(m/=> find-slot! [:=> [:cat bytes? bytes? [:map-of :keyword :any]] nat-int?])

;; ── command-id → (subsystem, cmd-path) ──────────────────────────────────────
(defn- split-route
  "Split an endpoint route \"cmd/<subsystem>/<rest…>\" into the `wrap-in-root`
   subsystem (the proto Root field name, e.g. \"rotary\", \"day-camera\") + the
   cmd-path (`cmd-path->map`'s nesting INSIDE that Root, e.g. \"rotate-to-ndc\",
   \"zoom/set-zoom-table-value\"). Fail-loud on a route not under `cmd/`."
  [route]
  (let [segs (str/split route #"/")]
    (when-not (= "cmd" (first segs))
      (throw (ex-info (str "uigen.cmd-spec: route not under cmd/: " route) {:route route})))
    {:subsystem (second segs) :cmd-path (str/join "/" (drop 2 segs))}))
(m/=> split-route
      [:=> [:cat s/ne-string] [:map [:subsystem s/ne-string] [:cmd-path s/ne-string]]])

(defn- subsystem+cmd-path
  "Resolve a command-id's endpoint route and split it (compose-don't-rederive —
   the route IS the home). Fail-loud on a command-id with no route."
  [command-id]
  (split-route (or (res/command-route command-id)
                   (throw (ex-info (str "uigen.cmd-spec: no route for " command-id)
                                   {:command-id command-id})))))
(m/=> subsystem+cmd-path
      [:=> [:cat s/ne-string] [:map [:subsystem s/ne-string] [:cmd-path s/ne-string]]])

;; ── leaf patch descriptors ──────────────────────────────────────────────────
;; A leaf is one {field-name → {:kind PatchKind …}} the patcher rewrites.
;; Derived from the endpoint's fields so no command shape is hard-coded: a
;; double field named x/y → an NDC slot; the int32 `value` leaf → the
;; widget/delta varint slot.
(defn- field-key
  "The param-map key for a proto field NAME, matching cmd-mapper's
   `:key-name-fn` (pronto.utils/->kebab-case) EXACTLY. Must be pronto's own
   transform, not camel-snake-kebab: the two agree on letter-only names but
   DIVERGE on a digit-suffixed field (\"x1\" → pronto \"x1\" but csk \":x-1\"),
   so a rubber-band ROI leaf (x1/y1/x2/y2) would assoc a non-existent key."
  [fname]
  (keyword (pronto-utils/->kebab-case fname)))
(m/=> field-key [:=> [:cat s/ne-string] :keyword])

(defn- varint-wire-scale
  "Gen-time wire-scale for a varint leaf (uigen.scales). A numeric
   semantic-type uses its scale; SetZoomTableValue.value is nil-semantic
   (a discrete zoom-table index) → scale 1."
  [f]
  (let [st (:semantic-type f)]
    (if (scales/numeric-semantic-type? st) (scales/scale-for st) 1)))
(m/=> varint-wire-scale [:=> [:cat [:map-of :keyword :any]] :int])

(def ^:private cmd-spec-ir
  "The CmdSpec IR map both `cmd-spec` + `fixed-cmd-spec` (and the by-value
   builders) return: a source command-id, the pre-encoded cmd.Root template
   bytes, and the fixed-width slot patches (empty for a fixed template)."
  [:map [:command-id s/ne-string] [:root-template bytes?]
   [:patches [:sequential [:map-of :keyword :any]]]])

(def ^:private opts-schema
  "Optional cmd-spec opts. `:fixed` pins enum-typed leaf values that have NO live
   widget/gesture source — the video `channel` on the shared RotaryPlatform/CV NDC
   commands, which the pane (day=2 / heat=1) selects, not the pointer. A channel
   field with no `:fixed` channel is a build error (field-params throws) — never a
   silent default (the old blanket `channel → 1` aimed every day-pane gesture at
   the HEAT camera). `:value-field` (default \"value\") is the RAW proto field name
   of the mutable widget leaf — the slider/number field whose live int patches the
   fixed-width slot; generalizing off the hard-coded \"value\" lets a non-\"value\"
   value leaf (e.g. `index` on ScanDeleteNode) fail loud at gen if unpatchable,
   not silently."
  [:map [:fixed {:optional true} [:map-of :keyword :int]]
   [:value-field {:optional true} s/ne-string]])

(defn- field-params
  "The wrap-in-root leaf params for `command-id`'s `fields`: each NDC double leaf
   set to its SENTINEL (so the template carries a locatable fixed-width slot),
   the double/int32 VALUE leaf (named by `opts :value-field`, default \"value\")
   to the MAX-width sentinel, a `channel` enum leaf to the REQUIRED `opts :fixed
   :channel` (fail-loud when absent — no silent default), uint64/other scalars → 0
   (renderer fills live; not a patched slot in R5a). Returns {:params …
   :patch-fields ordered-subset}."
  [command-id fields varint-kind opts]
  (let [value-field (get opts :value-field "value")]
    (reduce
     (fn [{:keys [params patch-fields]} f]
       ;; pronto's cmd-mapper kebab-cases proto field names; the endpoint field
       ;; :name is snake_case, so the params key must be its pronto kebab keyword.
       (let [fname (:name f)
             fkw (field-key fname)
             ftype (:type f)]
         (cond (and (= "double" ftype) (contains? ndc-double-leaves fname))
               {:params (assoc params fkw (:sentinel (ndc-double-leaves fname)))
                :patch-fields (conj patch-fields
                                    {:name fname
                                     :kind (:kind (ndc-double-leaves fname))
                                     ;; An NDC slot's encoding IS its kind — see
                                     ;; PatchEncoding; naming one here would be
                                     ;; the same fact in two fields, and the
                                     ;; renderer refuses that.
                                     :encoding :PATCH_ENCODING_UNSPECIFIED
                                     :width double-width
                                     :wire-scale 1
                                     :slot :ndc-double})}
               ;; The non-NDC `double` VALUE slider leaf. It is a fixed64 slot,
               ;; so it takes the DOUBLE_LE encoding — the widget int DIVIDED by
               ;; the wire-scale, written as 8 verbatim IEEE-754 bytes. Before
               ;; PatchEncoding existed this slot was emitted with the only
               ;; encoding the kind could imply, a padded varint, which the
               ;; receiving decoder read as a fixed64 double: a 50% iris
               ;; (widget 500, scale 1000) arrived as 2.94e-306 instead of 0.5.
               (and (= "double" ftype) (= value-field fname))
               {:params (assoc params fkw value-double-sentinel)
                :patch-fields (conj patch-fields
                                    {:name fname
                                     :kind varint-kind
                                     :encoding :PATCH_ENCODING_DOUBLE_LE
                                     :width double-width
                                     :wire-scale (varint-wire-scale f)
                                     :slot :value-double})}
               (and (#{"int32" "uint32"} ftype) (= value-field fname))
               {:params (assoc params fkw int32-max-sentinel)
                :patch-fields (conj patch-fields
                                    {:name fname
                                     :kind varint-kind
                                     :encoding :PATCH_ENCODING_PADDED_VARINT
                                     :width int32-varint-width
                                     :wire-scale (varint-wire-scale f)
                                     :slot :value-varint})}
               (= "channel" fname)
               (let [ch (get-in opts [:fixed :channel])]
                 (when (nil? ch)
                   (throw (ex-info (str
                                    "uigen.cmd-spec: "
                                    command-id
                                    " has an enum `channel`"
                                    " leaf — pass {:fixed {:channel N}} to pin the pane's"
                                    " video channel (no silent default)")
                                   {:command-id command-id :field fname})))
                 {:params (assoc params fkw ch) :patch-fields patch-fields})
               (#{"uint64" "uint32" "int32" "int64"} ftype) {:params (assoc params fkw 0)
                                                             :patch-fields patch-fields}
               :else {:params params :patch-fields patch-fields})))
     {:params {} :patch-fields []}
     fields)))
(m/=> field-params
      [:=> [:cat s/ne-string [:sequential [:map-of :keyword :any]] :keyword opts-schema]
       [:map [:params [:map-of :keyword :any]]
        [:patch-fields [:sequential [:map-of :keyword :any]]]]])

;; ── the pre-encode entry point ──────────────────────────────────────────────
(defn cmd-spec
  "Build the CmdSpec IR map for `command-id`:
   (a) split the route into subsystem + cmd-path,
   (b) build the leaf params with SENTINELs at the fixed-width slots,
   (c) serialize the cmd.Root template via wrap-in-root (pronto, gen-time),
   (d) LOCATE each leaf slot by its sentinel (double: the 8 LE bytes; varint:
       the 5-byte max-width minimal varint of 2^31-1), emit a FieldPatch with
       the byte-offset + width + kind + gen-time wire-scale.
   `varint-kind` selects the int-leaf PatchKind (:PATCH_KIND_WIDGET_VALUE for a
   widget click, :PATCH_KIND_DELTA for a pinch/wheel step). `opts` (3-arity)
   carries `:fixed` — enum-leaf values with no live source, REQUIRED for a
   `channel`-bearing command (the pane pins day=2 / heat=1). The returned IR is
   the `:cmd` the emit/lvgl + emit-proto layers serialize into EventBinding.cmd
   / GestureSpec.cmd."
  ([command-id varint-kind] (cmd-spec command-id varint-kind {}))
  ([command-id varint-kind opts]
   (let [{:keys [subsystem cmd-path]} (subsystem+cmd-path command-id)
         fields (res/all-fields command-id)
         {:keys [params patch-fields]} (field-params command-id fields varint-kind opts)
         _ (when (empty? patch-fields)
             (throw (ex-info
                     (str "uigen.cmd-spec: no fixed-width patch leaf for " command-id
                          " — R5a pre-encodes only commands with an"
                          " NDC double (x/y) or an int32 `value` leaf")
                     {:command-id command-id :fields (mapv (juxt :name :type) fields)})))
         ^bytes template (pcmd/wrap-in-root subsystem
                                            (backend/cmd-path->map cmd-path params))
         patches
         (mapv
          (fn [{fname :name :keys [kind width wire-scale slot encoding]}]
            (let [^bytes needle (case slot
                                  :ndc-double (double->le-bytes (:sentinel
                                                                 (ndc-double-leaves fname)))
                                  :value-double (double->le-bytes value-double-sentinel)
                                  :value-varint (varint-le-bytes int32-max-sentinel))
                  offset (find-slot! template needle {:command-id command-id :field fname})]
              {:byte-offset offset :byte-width width :kind kind :wire-scale wire-scale
               :encoding encoding}))
          patch-fields)]
     {:command-id command-id :root-template template :patches patches})))
(m/=> cmd-spec
      [:function [:=> [:cat s/ne-string :keyword] cmd-spec-ir]
       [:=> [:cat s/ne-string :keyword opts-schema] cmd-spec-ir]])

;; ── fixed (patch-free) pre-encode — the :action / :bool-set / :enum egress ───
(def ^:private template-byte-cap
  "Max pre-encoded cmd.Root template width — READ from the published wire-bound
   manifest, never re-typed. A template wider than this cannot be stored by the
   renderer (`ui_CmdSpec.root_template` is a fixed `PB_BYTES_ARRAY_T`), so a
   generator that emitted one would ship a screen the renderer refuses at load.

   The number has three consumers and this used to be the only unheld one: the C
   `#define` is bound by a `_Static_assert` against the nanopb-generated struct,
   the manifest by the freshness lane that regenerates and diffs it, and this
   literal by nothing at all. Reading it closes that."
  (wb/max-size "ui.CmdSpec.root_template"))

(defn- byte-len
  "Byte-array length via a `^bytes` PARAM — kondo's array check trusts a typed
   param but not a `^bytes`-hinted local bound from `wrap-in-root` (whose return
   it infers as a char sequence). Mirrors the index-of-bytes helper pattern."
  ^long [^bytes b]
  (alength b))
(m/=> byte-len [:=> [:cat bytes?] :int])

(def ^:private fixed-opts-schema
  "fixed-cmd-spec opts. `:field`+`:raw-value` bake the ONE mutable leaf's value
   in at gen time (a bool for :bool-set, an enum number for :enum); `:fixed`
   pins enum leaves with no live source (the video `channel`). Every enum leaf
   must be covered by `:field` or `:fixed` — an unpinned one fails loud (D6, no
   silent default). NOTE two key namespaces: `:field` is the RAW proto field
   name (matched against `res/all-fields` `:name`, e.g. \"value\"/\"mode\"),
   while `:fixed` keys are KEBAB keywords (the param-map convention, e.g.
   `:channel`) — they are not interchangeable."
  [:map [:command-id s/ne-string] [:field {:optional true} [:maybe s/ne-string]]
   ;; the baked leaf value: a bool (bool-set/momentary), an enum NUMBER (enum
   ;; option), or a numeric scalar — an int32 or a DOUBLE (a slider-preset
   ;; quick-jump value, e.g. SetIris 0.05).
   [:raw-value {:optional true} [:or :boolean number?]]
   [:fixed {:optional true} [:map-of :keyword :int]]])

(defn fixed-cmd-spec
  "Build a FIXED CmdSpec IR map (`:patches []` — patch_count 0, no runtime slot
   rewrite) for `command-id`: every leaf value is baked in at GENERATION time.
   This is the pre-encode for commands whose device value is KNOWN at gen time,
   not read from a widget's live position — a parameterless :action (Lrf.Measure),
   one arm of a :bool-set (SetAutoFocus{value false|true}), or one option of an
   :enum (SetFxMode{mode N}). It is the by-value entry builder: EventBinding
   .cmd_by_value is a vector of these, index-selected by the widget's int value.

   Params are built from the endpoint's fields: the `:field` leaf ← `:raw-value`;
   an enum leaf in `:fixed` ← its pinned number; any OTHER enum leaf fails loud
   (no silent default); scalar int/uint leaves default 0; bool/string default
   (omitted — proto3 false/\"\" are value-identical to explicit). The template is
   asserted ≤ the renderer's published `root_template` byte cap. The renderer
   emits it VERBATIM
   (cmd_patch_emit over patch_count 0), so its bytes ARE the device command."
  [{:keys [command-id field raw-value fixed]}]
  (let [{:keys [subsystem cmd-path]} (subsystem+cmd-path command-id)
        fields (res/all-fields command-id)
        fixed* (or fixed {})]
    (when (and field (not-any? #(= field (:name %)) fields))
      (throw
       (ex-info
        (str "uigen.cmd-spec/fixed-cmd-spec: " command-id " has no field named `" field "`")
        {:command-id command-id :field field :fields (mapv :name fields)})))
    (let [params (reduce
                  (fn [p f]
                    (let [fname (:name f)
                          fkw (field-key fname)
                          ftype (:type f)]
                      (cond (and field (= fname field)) (assoc p fkw raw-value)
                            (contains? fixed* fkw) (assoc p fkw (get fixed* fkw))
                            (= "enum" ftype)
                            (throw (ex-info
                                    (str
                                     "uigen.cmd-spec/fixed-cmd-spec: " command-id
                                     " has an unpinned enum leaf `" fname
                                     "` — pass it as"
                                     " :field/:raw-value or in :fixed (no silent default)")
                                    {:command-id command-id :field fname}))
                            (#{"uint64" "uint32" "int64" "int32"} ftype) (assoc p fkw 0)
                            (= "double" ftype) (assoc p fkw 0.0)
                            :else p)))
                  {}
                  fields)
          ^bytes template (pcmd/wrap-in-root subsystem
                                             (backend/cmd-path->map cmd-path params))
          tlen (byte-len template)]
      (when (> tlen template-byte-cap)
        (throw (ex-info (str "uigen.cmd-spec/fixed-cmd-spec: " command-id
                             " template " tlen
                             "B exceeds cap " template-byte-cap)
                        {:command-id command-id :len tlen})))
      {:command-id command-id :root-template template :patches []})))
(m/=> fixed-cmd-spec [:=> [:cat fixed-opts-schema] cmd-spec-ir])

;; ── by-value entry builders — the :bool-set / :enum cmd_by_value vectors ──────
;; EventBinding.cmd_by_value is a vector of FIXED templates the widget's INT value
;; index-selects among. Both builders compose `fixed-cmd-spec`; the ORDER is the
;; contract (index i → entry i), so the callers pass values/options in widget order.
(defn bool-pair-specs
  "The 2-entry cmd_by_value vector for a :bool-set switch, index-selected by the
   switch's 0/1 checked state: index 0 = the `{field false}` template, index 1 =
   `{field true}`, each a patch-free `fixed-cmd-spec`. `:fixed` pins any enum leaf
   with no live source (the video `channel` on cmd.CV.SetAutoFocus) — fail-loud if
   an enum leaf is left unpinned (D6, no silent default)."
  [{:keys [command-id field fixed]}]
  [(fixed-cmd-spec (cond-> {:command-id command-id :field field :raw-value false}
                     fixed (assoc :fixed fixed)))
   (fixed-cmd-spec (cond-> {:command-id command-id :field field :raw-value true}
                     fixed (assoc :fixed fixed)))])
(m/=> bool-pair-specs
      [:=>
       [:cat
        [:map [:command-id s/ne-string] [:field s/ne-string]
         [:fixed {:optional true} [:map-of :keyword :int]]]] [:vector cmd-spec-ir]])

(defn enum-specs
  "The cmd_by_value vector for an :enum picker — one patch-free `fixed-cmd-spec`
   per dropdown option, in OPTION ORDER. The widget's selected INDEX index-selects
   the entry, so option-order MUST equal cmd_by_value-order: BOTH derive from the
   SAME `options` vector (D4). Each option's `:value` (the enum NUMBER, which may
   start at 1 — index ≠ value, D3) is baked into the `field` leaf."
  [{:keys [command-id field]} options]
  (mapv (fn [{:keys [value]}]
          (fixed-cmd-spec {:command-id command-id :field field :raw-value value}))
        options))
(m/=> enum-specs
      [:=>
       [:cat [:map [:command-id s/ne-string] [:field s/ne-string]]
        [:sequential [:map [:value :int]]]] [:vector cmd-spec-ir]])

(defn patchable?
  "True when `command-id`'s endpoint has an R5a fixed-width slot leaf: an NDC
   double x/y, or the VALUE leaf (named by `value-field`, default \"value\") of
   type double/int32/uint32. The guard the value-widget emitter uses to decide
   whether an EventBinding gets a pre-encoded patched `:cmd` — the renderer
   memcpy-relays the OPAQUE cmd bytes itself, so a patchable value widget needs
   no host round-trip. Passing the widget's actual field name lets a non-\"value\"
   value leaf (e.g. `index` on ScanDeleteNode) resolve, so a numeric value widget
   is never wrongly judged non-patchable."
  ([command-id] (patchable? command-id "value"))
  ([command-id value-field]
   (boolean (when-let [ep (res/endpoint command-id)]
              (some (fn [f]
                      (let [t (:type f)
                            nm (:name f)]
                        (or (and (= "double" t) (contains? ndc-double-leaves nm))
                            (and (= "double" t) (= value-field nm))
                            (and (#{"int32" "uint32"} t) (= value-field nm)))))
                    (:fields ep))))))
(m/=> patchable?
      [:function [:=> [:cat s/ne-string] :boolean]
       [:=> [:cat s/ne-string s/ne-string] :boolean]])

;; ── subject-sourced pre-encode — the multi-field FORM egress ────────────────
;; A form's submit control has no value of its own and the form has N fields,
;; so neither `cmd-spec` (one widget value) nor `fixed-cmd-spec` (a gen-time
;; constant) can express it. Each field instead names the SUBJECT its input
;; widget writes, and the renderer reads each slot's own subject at emit — so N
;; slots carry N independent values without the emit growing an argument each.

(defn- form-sentinel
  "A DISTINCT locator sentinel for form field `i` of `ftype`. Distinctness is
   the whole requirement: `find-slot!` refuses an ambiguous needle, so two
   fields sharing a sentinel would be a build error rather than a mis-patch —
   but a form has many same-typed fields, so they must differ by construction.
   Doubles/floats take qNaN payloads keyed by index; integers take values near
   (but below) the int32 max, every one of which still encodes to the FULL
   5-byte varint so the slot stays pre-sized for the widest patch."
  [ftype i]
  (case ftype
    "double" (Double/longBitsToDouble (bit-or 0x7ff8d00000000000 (long i)))
    "float" (Float/intBitsToFloat (unchecked-int (bit-or 0x7fc0d000 (long i))))
    ("int32" "uint32") (- int32-max-sentinel i)
    (throw (ex-info (str "uigen.cmd-spec: no form sentinel for proto type " ftype)
                    {:type ftype :index i}))))
(m/=> form-sentinel [:=> [:cat s/ne-string :int] some?])

(def ^:private form-encoding
  "proto scalar type → the slot encoding that writes it. A `double` leaf is
   wire-type 1 and a `float` leaf wire-type 5, so each takes its own verbatim
   IEEE-754 encoding; only a genuine varint leaf takes the padded varint."
  {"double" :PATCH_ENCODING_DOUBLE_LE
   "float" :PATCH_ENCODING_FLOAT_LE
   "int32" :PATCH_ENCODING_PADDED_VARINT
   "uint32" :PATCH_ENCODING_PADDED_VARINT})

(def ^:private form-width
  "Slot width per proto scalar type: 8 for a fixed64 double, 4 for a fixed32
   float, and the max-width 5 for an int32/uint32 padded varint."
  {"double" 8 "float" 4 "int32" int32-varint-width "uint32" int32-varint-width})

(defn subject-form-cmd-spec
  "Build the CmdSpec IR for a multi-field FORM submit: `field->subject` maps a
   proto field NAME to the local subject name whose current int carries that
   field's operator-entered value.

   Every named field becomes a PATCH_KIND_SUBJECT_VALUE slot located by its own
   distinct sentinel and encoded for its own proto type; every field NOT named
   is pinned exactly as `fixed-cmd-spec` pins one (`:fixed` for an enum leaf
   with no live source, 0 for a scalar), so a partially-wired form fails at
   GENERATION rather than shipping a slot nobody writes. Fail-loud on a field
   the endpoint does not have, and on an enum leaf left unpinned.

   The scale rides each patch and the renderer DIVIDES by it, because
   uigen.scales defines `proto value x scale = the ABI int` the widgets and
   subjects ride — so a 1e7-scaled geo subject at 555000000 is the wire's 55.5
   degrees."
  [{:keys [command-id field->subject fixed]}]
  (let [{:keys [subsystem cmd-path]} (subsystem+cmd-path command-id)
        fields (res/all-fields command-id)
        fixed* (or fixed {})
        by-name (into {} (map (juxt :name identity)) fields)]
    (doseq [fname (keys field->subject)]
      (when-not (contains? by-name fname)
        (throw (ex-info (str "uigen.cmd-spec/subject-form-cmd-spec: " command-id
                             " has no field named `" fname "`")
                        {:command-id command-id :field fname
                         :fields (mapv :name fields)}))))
    (let [;; Index the patched fields in PROTO ORDER, not map order, so the
          ;; sentinel assignment is deterministic across runs (a map's seq
          ;; order is not a contract, and a non-deterministic template would
          ;; break byte-for-byte artifact comparison).
          patched (filterv #(contains? field->subject (:name %)) fields)
          indexed (map-indexed vector patched)
          params (reduce
                  (fn [p f]
                    (let [fname (:name f)
                          fkw (field-key fname)
                          ftype (:type f)]
                      (cond
                        (contains? field->subject fname)
                        (let [i (first (first (filter #(= fname (:name (second %)))
                                                      indexed)))]
                          (assoc p fkw (form-sentinel ftype i)))
                        (contains? fixed* fkw) (assoc p fkw (get fixed* fkw))
                        (= "enum" ftype)
                        (throw (ex-info
                                (str "uigen.cmd-spec/subject-form-cmd-spec: " command-id
                                     " has an unpinned enum leaf `" fname
                                     "` — pass it in :fixed (no silent default)")
                                {:command-id command-id :field fname}))
                        (#{"uint64" "uint32" "int64" "int32"} ftype) (assoc p fkw 0)
                        (= "double" ftype) (assoc p fkw 0.0)
                        (= "float" ftype) (assoc p fkw (float 0.0))
                        :else p)))
                  {}
                  fields)
          ^bytes template (pcmd/wrap-in-root subsystem
                                             (backend/cmd-path->map cmd-path params))
          tlen (byte-len template)
          _ (when (> tlen template-byte-cap)
              (throw (ex-info (str "uigen.cmd-spec/subject-form-cmd-spec: " command-id
                                   " template " tlen "B exceeds cap " template-byte-cap)
                              {:command-id command-id :len tlen})))
          patches
          (mapv (fn [[i f]]
                  (let [fname (:name f)
                        ftype (:type f)
                        sentinel (form-sentinel ftype i)
                        ^bytes needle (case ftype
                                        "double" (double->le-bytes sentinel)
                                        "float" (float->le-bytes sentinel)
                                        ("int32" "uint32") (varint-le-bytes sentinel))
                        offset (find-slot! template needle
                                           {:command-id command-id :field fname})]
                    {:byte-offset offset
                     :byte-width (form-width ftype)
                     :kind :PATCH_KIND_SUBJECT_VALUE
                     :wire-scale (varint-wire-scale f)
                     :encoding (form-encoding ftype)
                     :subject (get field->subject fname)}))
                indexed)]
      {:command-id command-id :root-template template :patches patches})))
(m/=> subject-form-cmd-spec
      [:=> [:cat [:map [:command-id s/ne-string]
                  [:field->subject [:map-of s/ne-string s/ne-string]]
                  [:fixed {:optional true} [:map-of :keyword :int]]]]
       cmd-spec-ir])
