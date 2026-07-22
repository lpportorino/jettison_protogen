(ns asgard.proto.state
  "Pronto mapper for state protobuf ↔ EDN conversion.
   Proto Java classes must be compiled and on classpath BEFORE this ns loads."
  (:require [malli.core :as m]
            [pronto.core :as pronto]
            [pronto.utils :as pronto-utils])
  (:import
   [com.google.protobuf Message]
   [java.util Arrays]
   [ser JonSharedData$JonGUIState JonSharedDataActualSpaceTime$JonGuiDataActualSpaceTime
    JonSharedDataCameraDay$JonGuiDataCameraDay JonSharedDataCameraHeat$JonGuiDataCameraHeat
    JonSharedDataCompass$JonGuiDataCompass
    JonSharedDataCompassCalibration$JonGuiDataCompassCalibration
    JonSharedDataCv$JonGuiDataCV JonSharedDataGps$JonGuiDataGps
    JonSharedDataHeater$JonGuiDataHeater JonSharedDataLrf$JonGuiDataLrf
    JonSharedDataPmu$JonGuiDataPMU JonSharedDataPower$JonGuiDataPower
    JonSharedDataRecOsd$JonGuiDataRecOsd JonSharedDataRotary$JonGuiDataRotary
    JonSharedDataSystem$JonGuiDataSystem JonSharedDataTime$JonGuiDataTime]))

(set! *warn-on-reflection* true)

(pronto/defmapper state-mapper
  [JonSharedData$JonGUIState]
  :key-name-fn
  pronto-utils/->kebab-case)

(defn bytes->state
  "Deserialize binary protobuf JonGUIState → Clojure EDN map (kebab-case keys)."
  [^bytes pb-bytes]
  (let [proto-obj (JonSharedData$JonGUIState/parseFrom pb-bytes)
        proto-map (pronto/proto->proto-map state-mapper proto-obj)]
    (pronto/proto-map->clj-map proto-map)))
(m/=> bytes->state [:=> [:cat bytes?] [:map-of :keyword :any]])

;; ── Per-subsystem partial decode (O7) ──────────────────────────────────
;;
;; The wire-scan gate (asgard.wirescan) reports which subsystems CHANGED and their
;; byte slices; only those need decoding. Decoding just the changed subsystems
;; (~2 of 16 at rest) instead of the whole JonGUIState cuts the dominant residual
;; decode allocation. The decoded result is a PARTIAL state ({changed-kw → submap})
;; — safe because the O6 consumer (scoped-delta) only reads `(get state kw)` for kw
;; in `changed`; a dropped subsystem has no slice ⇒ no entry ⇒ scoped-delta's
;; dropped-detection handles it.
(def ^:private subsystem-parser
  "{field-kw → (fn [^bytes slice] → Message)} for the 16 EMITTED JonGUIState
   subsystem fields (every subsystem-tags key EXCEPT :meteo-internal, which is
   root-skipped). Each parser closes over a LITERAL imported class's static
   parseFrom (zero reflection, load-order-safe) — NOT a string transform on the
   proto type-ref, whose outer-class wrapping (`ser.JonGuiDataGps` →
   `ser.JonSharedDataGps$JonGuiDataGps`) the type-ref does not carry. A kw absent
   here (e.g. :meteo-internal) is never decoded — the one home for `this subsystem
   has no signal binding` on the partial-decode path."
  {:system (fn [^bytes b] (JonSharedDataSystem$JonGuiDataSystem/parseFrom b))
   :lrf (fn [^bytes b] (JonSharedDataLrf$JonGuiDataLrf/parseFrom b))
   :time (fn [^bytes b] (JonSharedDataTime$JonGuiDataTime/parseFrom b))
   :gps (fn [^bytes b] (JonSharedDataGps$JonGuiDataGps/parseFrom b))
   :compass (fn [^bytes b] (JonSharedDataCompass$JonGuiDataCompass/parseFrom b))
   :rotary (fn [^bytes b] (JonSharedDataRotary$JonGuiDataRotary/parseFrom b))
   :camera-day (fn [^bytes b] (JonSharedDataCameraDay$JonGuiDataCameraDay/parseFrom b))
   :camera-heat (fn [^bytes b] (JonSharedDataCameraHeat$JonGuiDataCameraHeat/parseFrom b))
   :compass-calibration
   (fn [^bytes b]
     (JonSharedDataCompassCalibration$JonGuiDataCompassCalibration/parseFrom b))
   :rec-osd (fn [^bytes b] (JonSharedDataRecOsd$JonGuiDataRecOsd/parseFrom b))
   :actual-space-time
   (fn [^bytes b] (JonSharedDataActualSpaceTime$JonGuiDataActualSpaceTime/parseFrom b))
   :power (fn [^bytes b] (JonSharedDataPower$JonGuiDataPower/parseFrom b))
   :cv (fn [^bytes b] (JonSharedDataCv$JonGuiDataCV/parseFrom b))
   :pmu (fn [^bytes b] (JonSharedDataPmu$JonGuiDataPMU/parseFrom b))
   :heater (fn [^bytes b] (JonSharedDataHeater$JonGuiDataHeater/parseFrom b))})

(defn subsystem-parsers
  "The {field-kw → parser-fn} partial-decode map (subsystem-parser), exposed for the
   coverage assertion that it covers every emitted subsystem-tags key."
  []
  subsystem-parser)
(m/=> subsystem-parsers [:=> [:cat] [:map-of :keyword fn?]])

(defn decode-subsystem
  "Decode ONE subsystem's wire slice → its kebab-case submap, generically via the
   shared state-mapper (pronto dispatches on the runtime message class). `slice` is
   the [offset length] of the length-delimited VALUE bytes within `buf` (directly
   parseFrom-able). Mirrors bytes->state's parseFrom → proto->proto-map →
   proto-map->clj-map shape, per subsystem. Returns exactly what scoped-delta
   expects as `(get state field-kw)`, or nil when `field-kw` has no parser (a
   non-emitted subsystem like :meteo-internal)."
  [field-kw ^bytes buf slice]
  (when-let [parse (get subsystem-parser field-kw)]
    (let [[off len] slice
          off (long off)
          len (long len)
          sub (Arrays/copyOfRange buf (int off) (int (+ off len)))
          ^Message pojo (parse sub)]
      (pronto/proto-map->clj-map (pronto/proto->proto-map state-mapper pojo)))))
(m/=> decode-subsystem
      [:=> [:cat :keyword bytes? [:tuple :int :int]] [:maybe [:map-of :keyword :any]]])

(defn decode-changed
  "Decode ONLY the changed subsystems from their wire slices into a PARTIAL state
   map ({changed-present-kw → submap}). For each kw in `changed` that has a parser
   (root-skipped kws like :meteo-internal have none ⇒ no wasted parseFrom) and a
   present slice (looked up by its field tag in `subsystem-tags` → `cur-slices`),
   assoc its decoded submap. A changed kw whose subsystem dropped (no slice) gets
   no entry — scoped-delta's dropped-detection handles it from the missing key.
   This is the O7 partial-decode equivalent of bytes->state, scoped to `changed`."
  [^bytes buf changed subsystem-tags cur-slices]
  (reduce (fn [acc field-kw]
            (let [tag (get subsystem-tags field-kw)
                  slice (get cur-slices tag)
                  sub (and slice (decode-subsystem field-kw buf slice))]
              (cond-> acc sub (assoc field-kw sub))))
          {}
          changed))
(m/=> decode-changed
      [:=>
       [:cat bytes? [:set :keyword] [:map-of :keyword :int]
        [:map-of :int [:tuple :int :int]]] [:map-of :keyword :any]])

(defn state->bytes
  "Serialize Clojure EDN map → binary protobuf JonGUIState."
  [state-map]
  ;; clj-map->proto-map is a macro — class must be a literal symbol
  (let [proto-map
        (pronto/clj-map->proto-map state-mapper ser.JonSharedData$JonGUIState state-map)]
    (pronto/proto-map->bytes proto-map)))
(m/=> state->bytes [:=> [:cat [:map-of :keyword :any]] bytes?])