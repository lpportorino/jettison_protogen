(ns lvgl-codegen.proto-ser-presence-test
  "The proto3 EXPLICIT-PRESENCE round trip, in Clojure — the lane that was
   missing when `ui_ast`'s five stolen-zero fields became `optional`.

   WHAT THIS COVERS THAT NOTHING ELSE DID. `lvgl-codegen.proto-ser/bytes->screen`
   had exactly one caller in this tree — `lvgl-codegen.normalize/canonicalize` —
   and NO test namespace required either one, so `clojure -M:test` never called
   the decoder and never evaluated its `m/=>` output spec. The C renderer's own
   presence lanes (`renderer/wasm_harness/tests/presence_semantics.rs`) prove the
   WASM side and cannot see the Clojure side at all: they decode the same bytes
   through nanopb, not through pronto. So the whole Clojure decode boundary was
   green by never being driven.

   THE CONTRACT, one sentence: for a field with explicit presence, ABSENT and
   PRESENT-ZERO are DIFFERENT, and every layer here must keep them different —
   pronto's decode (which reports absence as `nil`), the Malli schema (which must
   ACCEPT that `nil`), and the schema-driven normalizer (which must not
   manufacture a zero for a field nobody set). Collapsing them re-creates, one
   layer up in Clojure, exactly the stolen-zero defect the proto conversion
   removed from the wire.

   Hermetic: every case builds its bytes in-memory through this repo's own
   producer (`ir->bytes`) and reads them back through its own decoder. No I/O, no
   fixtures, no renderer, no sleep."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.normalize :as normalize]
            [lvgl-codegen.proto-schema :as proto-schema]
            [lvgl-codegen.proto-ser :as proto-ser]
            [malli.core :as m]
            [malli.error :as me]))

(set! *warn-on-reflection* true)

;; The five fields ui_ast gave explicit presence, paired with the IR path that
;; reaches each and a non-zero value that is legal for it. `scroll_dir` is the
;; field the conversion exists for: LV_DIR_NONE IS zero.
(def ^:private presence-fields
  [{:label "WidgetNode.x" :path [:root :x] :node-key :x :value 7}
   {:label "WidgetNode.y" :path [:root :y] :node-key :y :value 9}
   {:label "WidgetNode.scroll_dir" :path [:root :scroll_dir] :node-key :scroll_dir :value 3}
   {:label "TabviewProps.tab_bar_size"
    :path [:root :tabview_props :tab_bar_size]
    :props-key :tabview_props
    :type :WIDGET_TABVIEW
    ;; TabviewProps' OTHER keys are required, and `normalize-with-schema`
    ;; validates its INPUT against the same schema it normalizes with, so a bare
    ;; {} props map is rejected before any default is filled. Supplying them
    ;; keeps the subject of this test the presence field alone.
    :props-base {:tab_names [] :active_index 0 :tab_bar_position :DIR_NONE
                 :tab_bar_pad_left 0}
    :node-key :tab_bar_size
    :value 40}
   {:label "TargetOverlayProps.border_width"
    :path [:root :target_overlay_props :border_width]
    :props-key :target_overlay_props
    :type :WIDGET_TARGET_OVERLAY
    :node-key :border_width
    :value 3}])

(defn- screen-ir
  "A one-node screen IR carrying `field` set to `v`, or carrying it NOWHERE when
   `v` is `::absent`. Absence is expressed by OMITTING the key, which is what an
   author who never mentioned the field produces."
  [{:keys [props-key props-base node-key] widget-type :type} v]
  (let [absent? (= v ::absent)
        leaf (if absent? {} {node-key v})]
    {:root (if props-key
             {:type widget-type props-key (merge props-base leaf)}
             (merge {:type :WIDGET_OBJ} leaf))}))

(defn- round-trip
  "IR → protobuf bytes → IR, through this repo's own producer and decoder."
  [ir]
  (proto-ser/bytes->screen (proto-ser/ir->bytes ir)))

(defn- explain-str
  [decoded]
  (pr-str (me/humanize (m/explain proto-schema/screen decoded))))

;; ── The decode contract ──────────────────────────────────────────────────────

(deftest absent-optional-scalar-round-trips-as-nil-and-satisfies-the-screen-schema
  (testing "a field with explicit presence that was never set decodes to nil —
            pronto emits the KEY with a nil VALUE, not proto3's zero — and
            proto-schema/screen must accept that, because it is the honest
            report of absence and the only thing the decoder can say"
    (doseq [{:keys [label path] :as f} presence-fields]
      (let [decoded (round-trip (screen-ir f ::absent))
            ok (m/validate proto-schema/screen decoded)]
        (is (nil? (get-in decoded path))
            (str label ": an unset optional scalar decodes as nil"))
        (is ok
            (str label ": the decoded screen satisfies proto-schema/screen — "
                 (when-not ok (explain-str decoded))))))))

(deftest present-zero-optional-scalar-round-trips-as-zero
  (testing "a PRESENT zero survives the round trip as 0 — this is the value the
            conversion bought, and a schema fix that merely tolerated nil while
            losing this would have solved nothing"
    (doseq [{:keys [label path] :as f} presence-fields]
      (let [decoded (round-trip (screen-ir f 0))]
        (is (= 0 (get-in decoded path)) (str label ": a present zero decodes as 0"))
        (is (m/validate proto-schema/screen decoded)
            (str label ": the decoded screen satisfies proto-schema/screen"))))))

(deftest present-non-zero-optional-scalar-round-trips-verbatim
  (testing "the ordinary case still works — the control that would catch a fix
            that made every optional field read as nil"
    (doseq [{:keys [label path value] :as f} presence-fields]
      (let [decoded (round-trip (screen-ir f value))]
        (is (= value (get-in decoded path)) (str label ": a present value decodes verbatim"))
        (is (m/validate proto-schema/screen decoded)
            (str label ": the decoded screen satisfies proto-schema/screen"))))))

(deftest absent-and-present-zero-are-distinguishable-after-the-round-trip
  (testing "THE contract. Absent and present-zero must not collapse into each
            other anywhere between the IR, the wire and the decoded map. This is
            the assertion that refuses a 'fix' which coerces the decoder's nil to
            0 — that would restore the exact ambiguity ui_ast dropped `optional`
            in to remove"
    (doseq [{:keys [label path] :as f} presence-fields]
      (let [absent (get-in (round-trip (screen-ir f ::absent)) path)
            zero (get-in (round-trip (screen-ir f 0)) path)]
        (is (not= absent zero)
            (str label ": absent (" (pr-str absent) ") differs from present-zero ("
                 (pr-str zero) ")"))
        (is (nil? absent) (str label ": absence is reported as nil"))
        (is (= 0 zero) (str label ": a present zero is reported as 0"))))))

;; ── The normalizer contract ──────────────────────────────────────────────────

(deftest schema-normalizer-does-not-manufacture-a-value-for-an-unset-field
  (testing "the schema-driven normalizer must not fill a presence-bearing field
            with proto3's zero. A `:default 0` there launders 'nobody set this'
            into 'position at the origin' / 'do not scroll' / 'hide the tab bar'
            — the stolen zero re-created in Clojure, one layer above the wire it
            was removed from"
    (doseq [{:keys [label path] :as f} presence-fields]
      (let [normalized (normalize/normalize-with-schema (screen-ir f ::absent))]
        (is (not= 0 (get-in normalized path))
            (str label ": normalize-with-schema leaves an unset field unset, "
                 "got " (pr-str (get-in normalized path))))))))

(deftest schema-normalizer-agrees-with-the-serialization-round-trip
  (testing "`normalize-with-schema` documents itself as the alternative to
            `canonicalize` that skips the serialization round trip. Two functions
            declared alternatives must produce the same map, and on a sparse
            screen the presence fields are exactly where they came apart"
    (let [sparse {:root {:type :WIDGET_OBJ}}]
      (is (= (normalize/canonicalize sparse) (normalize/normalize-with-schema sparse))
          "the two normalizers agree on a sparse screen"))))
