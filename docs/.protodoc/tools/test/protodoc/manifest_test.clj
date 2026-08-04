(ns protodoc.manifest-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [protodoc.manifest :as manifest]))

;; ============================================================================
;; Name Derivation Tests
;; ============================================================================

(deftest snake->camel-test
  (testing "standard multi-word fields"
    (is (= "focusPos" (manifest/snake->camel "focus_pos")))
    (is (= "zoomTablePos" (manifest/snake->camel "zoom_table_pos")))
    (is (= "isStarted" (manifest/snake->camel "is_started")))
    (is (= "cpuTemperature" (manifest/snake->camel "cpu_temperature"))))

  (testing "single-word fields"
    (is (= "voltage" (manifest/snake->camel "voltage")))
    (is (= "azimuth" (manifest/snake->camel "azimuth"))))

  (testing "fields with numbers"
    (is (= "s0" (manifest/snake->camel "s0")))
    (is (= "s7" (manifest/snake->camel "s7"))))

  (testing "subsystem field names"
    (is (= "cameraDay" (manifest/snake->camel "camera_day")))
    (is (= "cameraHeat" (manifest/snake->camel "camera_heat")))
    (is (= "actualSpaceTime" (manifest/snake->camel "actual_space_time")))
    (is (= "recOsd" (manifest/snake->camel "rec_osd")))
    (is (= "meteoInternal" (manifest/snake->camel "meteo_internal")))))

(deftest snake->kebab-test
  (testing "PascalCase package names"
    (is (= "day-camera" (manifest/snake->kebab "DayCamera")))
    (is (= "heat-camera" (manifest/snake->kebab "HeatCamera")))
    (is (= "rotary-platform" (manifest/snake->kebab "RotaryPlatform"))))

  (testing "snake_case command names"
    (is (= "set-iris" (manifest/snake->kebab "set_iris")))
    (is (= "set-value" (manifest/snake->kebab "set_value")))
    (is (= "halt-all" (manifest/snake->kebab "halt_all"))))

  (testing "mixed case"
    (is (= "lrf-calib" (manifest/snake->kebab "Lrf_calib"))))

  (testing "acronyms"
    (is (= "cv" (manifest/snake->kebab "CV")))
    (is (= "osd" (manifest/snake->kebab "OSD")))
    (is (= "pmu" (manifest/snake->kebab "PMU")))
    (is (= "lrf" (manifest/snake->kebab "Lrf")))))

(deftest make-signal-name-test
  (testing "signal name derivation matches ARM convention"
    ;; These are the exact signal names ARM's csk/->camelCaseString produces
    ;; from kebab-case "prefix-field" → camelCase
    (is (= "cameraDayFocusPos"
           (manifest/make-signal-name "cameraDay" "focus_pos")))
    (is (= "cameraDayZoomPos"
           (manifest/make-signal-name "cameraDay" "zoom_pos")))
    (is (= "gpsLatitude"
           (manifest/make-signal-name "gps" "latitude")))
    (is (= "compassAzimuth"
           (manifest/make-signal-name "compass" "azimuth")))
    (is (= "powerS0Voltage"
           ;; Power sub-signal: prefix is "powerS0" (from parent "power" + nested "s0")
           (manifest/make-signal-name "powerS0" "voltage")))
    (is (= "systemCpuTemperature"
           (manifest/make-signal-name "system" "cpu_temperature")))
    (is (= "lrfIsScanning"
           (manifest/make-signal-name "lrf" "is_scanning")))
    (is (= "recOsdScreen"
           (manifest/make-signal-name "recOsd" "screen")))))

;; ============================================================================
;; Root / Group Identification Tests
;; ============================================================================

(deftest root-message-test
  (is (manifest/root-message? {:id "cmd.Compass.Root"}))
  (is (manifest/root-message? {:id "cmd.DayCamera.Root"}))
  (is (not (manifest/root-message? {:id "cmd.DayCamera.SetIris"})))
  (is (not (manifest/root-message? {:id "cmd.DayCamera.Focus"}))))

(deftest group-message-test
  (testing "standard group with required cmd oneof"
    (is (manifest/group-message?
         {:id "cmd.DayCamera.Focus"
          :oneofs [{:name "cmd" :required true :fields [1 2 3]}]})))

  (testing "Lrf_calib.Offsets (has cmd oneof)"
    (is (manifest/group-message?
         {:id "cmd.Lrf_calib.Offsets"
          :oneofs [{:name "cmd" :required true :fields [1 2 3 4]}]})))

  (testing "Root is NOT a group"
    (is (not (manifest/group-message?
              {:id "cmd.Compass.Root"
               :oneofs [{:name "cmd" :required true :fields [1 2]}]}))))

  (testing "leaf command is NOT a group"
    (is (not (manifest/group-message?
              {:id "cmd.DayCamera.SetIris"
               :fields [{:number 1 :name "value" :type :double}]})))))

(deftest routing-container-test
  (is (manifest/routing-container?
       {:id "cmd.Compass.Root"
        :oneofs [{:name "cmd" :required true :fields [1]}]}))
  (is (manifest/routing-container?
       {:id "cmd.DayCamera.Focus"
        :oneofs [{:name "cmd" :required true :fields [1 2]}]}))
  (is (not (manifest/routing-container?
            {:id "cmd.DayCamera.SetIris"
             :fields [{:number 1 :name "value" :type :double}]}))))

;; ============================================================================
;; Constraints → Malli Tests
;; ============================================================================

;; Helper: an edn-able schema must equal an expected DATA form AND survive a
;; pr-str → edn/read-string roundtrip yielding the same schema. (The
;; double-exclusive :fn kind is NOT edn-able — a fn cannot be read back — so it
;; is asserted via m/validate on boundary values instead.)
(defn- edn-roundtrips? [schema]
  (= schema (edn/read-string (pr-str schema))))

;; A small enum-def map shaped exactly like proto-db.edn's :enums entries:
;; each value carries :number (the numeric, possibly the proto3 zero default)
;; and :name (the constant). The :not-in exclusion joins :number.
(def ^:private enums-fixture
  {"ser.JonGuiDataRotaryDirection"
   {:id "ser.JonGuiDataRotaryDirection"
    :values [{:number 0 :name "JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED"}
             {:number 1 :name "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"}
             {:number 2 :name "JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE"}]}})

(deftest constraints->malli-test
  (testing "double — inclusive range (gte/lte) needs NO :fn"
    (let [s (manifest/constraints->malli
             {:type :double :constraints {:gte 0 :lte 1}} {})]
      (is (= [:double {:min 0 :max 1}] s))
      (is (edn-roundtrips? s))))

  (testing "double without constraints"
    (let [s (manifest/constraints->malli {:type :double} {})]
      (is (= :double s))
      (is (edn-roundtrips? s))))

  (testing "float kept DISTINCT from double"
    (let [s (manifest/constraints->malli
             {:type :float :constraints {:gte 0 :lte 1}} {})]
      (is (= [:float {:min 0 :max 1}] s))
      (is (edn-roundtrips? s))))

  (testing "int32 with inclusive range (gte/lte)"
    (let [s (manifest/constraints->malli
             {:type :int32 :constraints {:gte 0 :lte 255}} {})]
      (is (= [:int {:min 0 :max 255}] s))
      (is (edn-roundtrips? s))))

  (testing "int32 lt is exclusive — dec IS correct for ints (max = b-1)"
    (let [s (manifest/constraints->malli
             {:type :int32 :constraints {:lt 595 :gte 0}} {})]
      (is (= [:int {:min 0 :max 594}] s))
      (is (edn-roundtrips? s))
      (is (m/validate s 594))
      (is (not (m/validate s 595)))))

  (testing "int32 gt is exclusive — inc IS correct for ints (min = a+1)"
    (let [s (manifest/constraints->malli
             {:type :int32 :constraints {:gt 0}} {})]
      ;; gt:0 → :no-default marker AND open upper end capped at int32-max
      (is (= [:int {:min 1 :max 2147483647 :no-default true}] s))
      (is (edn-roundtrips? s))))

  (testing "int gte-only caps the open upper end at int32-max (finite gen)"
    (let [s (manifest/constraints->malli
             {:type :int32 :constraints {:gte 5}} {})]
      (is (= [:int {:min 5 :max 2147483647}] s))
      (is (edn-roundtrips? s))))

  (testing "uint32 with range"
    (let [s (manifest/constraints->malli
             {:type :uint32 :constraints {:gte 0 :lte 100}} {})]
      (is (= [:int {:min 0 :max 100}] s))
      (is (edn-roundtrips? s))))

  (testing "uint32 without constraints → finite-bounded at UINT32_MAX"
    (let [s (manifest/constraints->malli {:type :uint32} {})]
      (is (= [:int {:min 0 :max 4294967295}] s))
      (is (edn-roundtrips? s))
      (is (m/validate s 4294967295))
      (is (not (m/validate s 4294967296)))))

  (testing "bool"
    (let [s (manifest/constraints->malli {:type :bool} {})]
      (is (= :boolean s))
      (is (edn-roundtrips? s))))

  (testing "int64 → bounded :int honoring constraints (NOT bare :int)"
    (let [s (manifest/constraints->malli
             {:type :int64 :constraints {:gte 0}} {})]
      (is (= [:int {:min 0 :max 9223372036854775807}] s))
      (is (edn-roundtrips? s))
      (is (not (m/validate s -1)))
      (is (m/validate s 9223372036854775807))))

  (testing "int64 unconstrained → full signed-64 range"
    (let [s (manifest/constraints->malli {:type :int64} {})]
      (is (= [:int {:min -9223372036854775808 :max 9223372036854775807}] s))
      (is (edn-roundtrips? s))))

  (testing "uint64 → custom [:uint64 ...] BigInt marker (malli :int can't hold the upper half)"
    (let [s (manifest/constraints->malli {:type :uint64} {})]
      (is (= [:uint64 {:min 0N :max 18446744073709551615N}] s))
      (is (edn-roundtrips? s))))

  (testing "uint64 with the unsigned floor — no negatives, upper half reachable"
    (let [s (manifest/constraints->malli
             {:type :uint64 :constraints {:gte 0 :lte 1000}} {})]
      (is (= [:uint64 {:min 0N :max 1000N}] s))
      (is (edn-roundtrips? s))))

  (testing ":bytes → custom [:bytes ...] octet marker (NOT a string)"
    (let [s (manifest/constraints->malli {:type :bytes} {})]
      (is (= :bytes s))
      (is (edn-roundtrips? s)))
    (let [s (manifest/constraints->malli
             {:type :bytes :constraints {:max-len 64}} {})]
      (is (= [:bytes {:max 64}] s))
      (is (edn-roundtrips? s))))

  (testing "string :pattern → [:re pattern] (regex subsumes length)"
    (let [uuid-re "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
          s (manifest/constraints->malli
             {:type :string :constraints {:pattern uuid-re :min-len 36 :max-len 36}} {})]
      (is (= [:re uuid-re] s))
      (is (edn-roundtrips? s))
      (is (m/validate s "12345678-1234-1234-1234-123456789abc"))
      (is (not (m/validate s "not-a-uuid")))))

  (testing "string :in → [:enum allowed...]"
    (let [s (manifest/constraints->malli
             {:type :string :constraints {:in ["error" "warn" "info"]}} {})]
      (is (= [:enum "error" "warn" "info"] s))
      (is (edn-roundtrips? s))
      (is (m/validate s "warn"))
      (is (not (m/validate s "trace")))))

  (testing "unconstrained :float is finite-bounded at ±FLT_MAX (wire would saturate to Inf)"
    (let [s (manifest/constraints->malli {:type :float} {})]
      (is (= [:float {:min (- (double Float/MAX_VALUE))
                      :max (double Float/MAX_VALUE)}]
             s))
      (is (edn-roundtrips? s))))

  (testing "int gt at the type maximum is an empty range → fail loud (not a silent accept)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (manifest/constraints->malli
                  {:type :int32 :constraints {:gt 2147483647}} {}))))

  (testing "string with min length"
    (let [s (manifest/constraints->malli
             {:type :string :constraints {:min-len 1}} {})]
      (is (= [:string {:min 1}] s))
      (is (edn-roundtrips? s))))

  (testing "string with min AND max length"
    (let [s (manifest/constraints->malli
             {:type :string :constraints {:min-len 36 :max-len 36}} {})]
      (is (= [:string {:min 36 :max 36}] s))
      (is (edn-roundtrips? s))))

  (testing ":no-default marker — scalar gte:1 (proto3 zero-reject)"
    (let [s (manifest/constraints->malli
             {:type :uint32 :constraints {:gte 1}} {})]
      (is (= [:int {:min 1 :max 4294967295 :no-default true}] s))
      (is (edn-roundtrips? s))
      ;; the marker is a real malli property — schema still validates/excludes 0
      (is (not (m/validate s 0)))
      (is (m/validate s 1))))

  (testing "double exclusive lt — NOT dec, boundary excluded via :fn guard"
    (let [s (manifest/constraints->malli
             {:type :double :constraints {:lt 360 :gte 0}} {})]
      ;; props keep the bound INCLUSIVE (360, not 359); the :fn excludes it.
      ;; A fn is not edn-readable, so assert via m/validate on boundaries.
      (is (= :and (first s)))
      (is (= [:double {:min 0 :max 360}] (second s)))
      (is (m/validate s 0.0))
      (is (m/validate s 359.9999))
      (is (not (m/validate s 360.0)))
      (is (not (m/validate s -0.1)))))

  (testing "double exclusive both ends (gt/lt) — both boundaries excluded"
    (let [s (manifest/constraints->malli
             {:type :double :constraints {:lt 360 :gt -360}} {})]
      (is (= [:double {:min -360 :max 360}] (second s)))
      (is (m/validate s 0.0))
      (is (m/validate s 359.9999))
      (is (not (m/validate s 360.0)))
      (is (not (m/validate s -360.0)))))

  (testing "enum not_in:[0] — sentinel excluded + :no-default marker"
    (let [s (manifest/constraints->malli
             {:type :enum
              :type-ref "ser.JonGuiDataRotaryDirection"
              :constraints {:defined-only true :not-in [0]}}
             enums-fixture)]
      ;; UNSPECIFIED (:number 0) dropped; allowed subset is the explicit names
      (is (= [:enum {:no-default true}
              "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"
              "JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE"]
             s))
      (is (edn-roundtrips? s))
      (is (not (m/validate s "JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED")))
      (is (m/validate s "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"))))

  (testing "enum defined_only WITHOUT not_in — full value set, no marker"
    (let [s (manifest/constraints->malli
             {:type :enum
              :type-ref "ser.JonGuiDataRotaryDirection"
              :constraints {:defined-only true}}
             enums-fixture)]
      (is (= [:enum
              "JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED"
              "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"
              "JON_GUI_DATA_ROTARY_DIRECTION_COUNTER_CLOCKWISE"]
             s))
      (is (edn-roundtrips? s))))

  (testing "enum general not_in:[2] is a LITERAL exclusion (no :no-default)"
    (let [s (manifest/constraints->malli
             {:type :enum
              :type-ref "ser.JonGuiDataRotaryDirection"
              :constraints {:not-in [2]}}
             enums-fixture)]
      (is (= [:enum
              "JON_GUI_DATA_ROTARY_DIRECTION_UNSPECIFIED"
              "JON_GUI_DATA_ROTARY_DIRECTION_CLOCKWISE"]
             s))
      (is (edn-roundtrips? s))))

  (testing "enum not in enums-map falls back to :string"
    (let [s (manifest/constraints->malli
             {:type :enum :type-ref "ser.Missing"} enums-fixture)]
      (is (= :string s))
      (is (edn-roundtrips? s)))))

;; ============================================================================
;; Full Manifest Generation from Real proto-db.edn
;; ============================================================================

(def ^:private db-path "../proto-db.edn")
(def ^:private config-path "../manifest-config.edn")

(defn- load-db []
  (when (.exists (io/file db-path))
    (edn/read-string (slurp db-path))))

(defn- load-config []
  (if (.exists (io/file config-path))
    (edn/read-string (slurp config-path))
    {:root-skip-fields #{}
     :cmd-skip-fields #{}
     :derived-signals []}))

(deftest endpoint-extraction-test
  (when-let [db (load-db)]
    (let [config (load-config)
          result (manifest/extract-endpoints db config)]
      (testing "has endpoints"
        (is (pos? (count (:endpoints result)))))

      (testing "has subsystems"
        (is (pos? (count (:subsystems result)))))

      (testing "compass subsystem has expected endpoints"
        (let [compass-eps (filter #(= "compass" (:subsystem %)) (:endpoints result))]
          (is (>= (count compass-eps) 8))
          (is (some #(= "cmd/compass/start" (:path %)) compass-eps))
          (is (some #(= "cmd/compass/stop" (:path %)) compass-eps))))

      (testing "day-camera has nested focus/zoom endpoints"
        (let [focus-eps (filter #(str/starts-with? (:path %) "cmd/day-camera/focus/")
                                (:endpoints result))]
          (is (>= (count focus-eps) 4))
          (is (some #(= "cmd/day-camera/focus/set-value" (:path %)) focus-eps))))

      (testing "lrf-calib has day/heat channel dispatch"
        (let [lrf-eps (filter #(= "lrf-calib" (:subsystem %)) (:endpoints result))]
          (is (= 8 (count lrf-eps)))
          (is (some #(= "cmd/lrf-calib/day/set" (:path %)) lrf-eps))
          (is (some #(= "cmd/lrf-calib/heat/set" (:path %)) lrf-eps))))

      (testing "no Root or group messages appear as endpoints"
        (is (not-any? #(str/ends-with? (:id %) ".Root")
                      (:endpoints result)))
        ;; Check no group IDs appear
        (let [group-ids #{"cmd.DayCamera.Focus" "cmd.DayCamera.Zoom"
                          "cmd.HeatCamera.Zoom" "cmd.RotaryPlatform.Azimuth"
                          "cmd.RotaryPlatform.Elevation" "cmd.Lrf_calib.Offsets"}]
          (is (not-any? #(group-ids (:id %)) (:endpoints result)))))

      (testing "leaf endpoints have fields"
        (let [set-iris (first (filter #(= "cmd.DayCamera.SetIris" (:id %))
                                      (:endpoints result)))]
          (is set-iris)
          (is (= 1 (count (:fields set-iris))))
          (is (= "value" (:name (first (:fields set-iris))))))))))

(deftest signal-extraction-test
  (when-let [db (load-db)]
    (let [config (load-config)
          result (manifest/extract-signals db config)]
      (testing "has signals"
        (is (pos? (count (:signals result)))))

      (testing "has subsystems"
        (is (pos? (count (:subsystems result)))))

      (testing "signal names are camelCase prefixed"
        (is (some #(= "cameraDayFocusPos" (:signal-name %)) (:signals result)))
        (is (some #(= "gpsLatitude" (:signal-name %)) (:signals result)))
        (is (some #(= "systemCpuTemperature" (:signal-name %)) (:signals result)))
        (is (some #(= "compassAzimuth" (:signal-name %)) (:signals result))))

      (testing "signals have correct types"
        (let [focus-sig (first (filter #(= "cameraDayFocusPos" (:signal-name %))
                                       (:signals result)))]
          (is focus-sig)
          (is (= "double" (:type focus-sig)))
          (is (= "camera_day" (:subsystem-field focus-sig)))))

      (testing "skip fields are excluded"
        (is (not-any? #(= "protocolVersion" (:signal-name %)) (:signals result)))))))

(deftest sub-signal-extraction-test
  (when-let [db (load-db)]
    (let [config (load-config)
          result (manifest/extract-sub-signals db config)]
      (testing "has nested signals"
        (is (pos? (count (:nested-signals result)))))

      (testing "power modules produce sub-signals"
        (let [power-entries (filter #(= "power" (:parent-subsystem %))
                                    (:nested-signals result))]
          ;; s0-s7 + meteo = at least 8 power entries
          (is (>= (count power-entries) 8))
          (let [s0 (first (filter #(= "s0" (:parent-field %)) power-entries))]
            (is s0)
            (is (= "ser.JonGuiDataPowerModule" (:nested-message s0)))
            (is (some #(= "powerS0Voltage" (:signal-name %)) (:signals s0)))
            (is (some #(= "powerS0IsOn" (:signal-name %)) (:signals s0)))))))))

(deftest reverse-index-test
  (when-let [db (load-db)]
    (let [config (load-config)
          endpoints-data (manifest/extract-endpoints db config)
          signals-data (manifest/extract-signals db config)
          result (manifest/build-reverse-index endpoints-data signals-data)]
      (testing "has all three index types"
        (is (map? (:by-doc-file result)))
        (is (map? (:by-endpoint result)))
        (is (map? (:by-signal result))))

      (testing "endpoints have doc-file in reverse index"
        (let [compass-start (get (:by-endpoint result) "cmd/compass/start")]
          (is compass-start)
          (is (string? (:doc-file compass-start)))))

      (testing "signals have doc-file in reverse index"
        (let [sig (get (:by-signal result) "cameraDayFocusPos")]
          (is sig)
          (is (= "proto/ser.JonGuiDataCameraDay.md" (:doc-file sig))))))))

;; ============================================================================
;; Field-grained related-state, over a SYNTHETIC index
;;
;; Hermetic on purpose: the committed db decides how many entries carry a field
;; half, so a test driven from it would report on today's migration progress
;; rather than on the mechanism. These two endpoints differ in exactly one thing
;; — the grain of their reference — which is what makes the narrowing
;; attributable to the grain and not to anything else in the fixture.
;; ============================================================================

(def ^:private grain-endpoints
  {:endpoints [{:path "cmd/x/set-a"
                :doc-file "proto/cmd.X.SetA.md"
                :interaction {:related-state ["ser.S#a"]}}
               {:path "cmd/x/refresh"
                :doc-file "proto/cmd.X.Refresh.md"
                :interaction {:related-state ["ser.S"]}}]})

(def ^:private grain-signals
  {:signals [{:signal-name "sA" :field-name "a"
              :proto-message "ser.S" :doc-file "proto/ser.S.md"}
             {:signal-name "sB" :field-name "b"
              :proto-message "ser.S" :doc-file "proto/ser.S.md"}]})

(deftest field-grained-related-state-narrows-the-reverse-index
  (let [result (manifest/build-reverse-index grain-endpoints grain-signals)]

    (testing "the field-grained endpoint reaches only the signal it names"
      (is (= ["proto/cmd.X.SetA.md" "proto/cmd.X.Refresh.md"]
             (:related-cmd-docs (get (:by-signal result) "sA"))))
      (is (= ["proto/cmd.X.Refresh.md"]
             (:related-cmd-docs (get (:by-signal result) "sB")))))

    (testing "the field half never reaches a doc-file path"
      (is (= ["proto/ser.S.md"]
             (:related-state-docs (get (:by-endpoint result) "cmd/x/set-a"))))
      (is (= ["proto/ser.S.md"]
             (:related-state-docs (get (:by-endpoint result) "cmd/x/refresh")))))))

(deftest state-ref-helpers-test
  (testing "doc-file drops the field half"
    (is (= "proto/ser.S.md" (manifest/state-ref-doc-file "ser.S")))
    (is (= "proto/ser.S.md" (manifest/state-ref-doc-file "ser.S#a"))))

  (testing "a message-grained reference matches every signal of that message"
    (is (manifest/state-ref-matches-signal?
         "ser.S" {:proto-message "ser.S" :field-name "a"}))
    (is (manifest/state-ref-matches-signal?
         "ser.S" {:proto-message "ser.S" :field-name "b"})))

  (testing "a field-grained reference matches one"
    (is (manifest/state-ref-matches-signal?
         "ser.S#a" {:proto-message "ser.S" :field-name "a"}))
    (is (not (manifest/state-ref-matches-signal?
              "ser.S#a" {:proto-message "ser.S" :field-name "b"}))))

  (testing "a different message never matches, either grain"
    (is (not (manifest/state-ref-matches-signal?
              "ser.Other" {:proto-message "ser.S" :field-name "a"})))
    (is (not (manifest/state-ref-matches-signal?
              "ser.Other#a" {:proto-message "ser.S" :field-name "a"})))))

(deftest full-manifest-generation-test
  (when-let [_db (load-db)]
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/manifest-test-" (System/nanoTime))
          _result (manifest/generate-manifests
                   {:db-path db-path
                    :config-path config-path
                    :output-dir tmp-dir
                    :git-sha "test123"})]
      (testing "produces all four manifest files"
        (is (.exists (io/file tmp-dir "endpoints.json")))
        (is (.exists (io/file tmp-dir "signals.json")))
        (is (.exists (io/file tmp-dir "sub-signals.json")))
        (is (.exists (io/file tmp-dir "reverse-index.json"))))

      (testing "endpoints.json is valid JSON"
        (let [data (json/read-str (slurp (io/file tmp-dir "endpoints.json")))]
          (is (= "1.0.0" (get data "version")))
          (is (= "test123" (get data "protogen-commit")))
          (is (pos? (count (get data "endpoints"))))))

      (testing "signals.json is valid JSON"
        (let [data (json/read-str (slurp (io/file tmp-dir "signals.json")))]
          (is (= "1.0.0" (get data "version")))
          (is (pos? (count (get data "signals"))))))

      ;; Cleanup
      (doseq [f (.listFiles (io/file tmp-dir))]
        (.delete f))
      (.delete (io/file tmp-dir)))))

(deftest manifest-generation-is-deterministic
  ;; Regression guard for the wall-clock idempotence defect: identical inputs
  ;; (same proto-db, same git-sha) MUST produce byte-identical manifests. A
  ;; wall-clock :generated-at stamp broke this — every regeneration differed, so
  ;; a re-generated manifest could never byte-match the committed copy. Two full
  ;; generations with the same git-sha are now byte-for-byte equal.
  (when-let [_db (load-db)]
    (let [gen! (fn []
                 (let [dir (str (System/getProperty "java.io.tmpdir")
                                "/manifest-det-" (System/nanoTime))]
                   (manifest/generate-manifests
                    {:db-path db-path :config-path config-path
                     :output-dir dir :git-sha "detsha"})
                   dir))
          dir-a (gen!)
          dir-b (gen!)
          files ["endpoints.json" "signals.json"
                 "sub-signals.json" "reverse-index.json"]]
      ;; Non-vacuity guard: a pass over two EMPTY generations would be a false
      ;; green (nothing-ran == passed), so assert real content exists first.
      (testing "generation is non-vacuous"
        (is (pos? (count (get (json/read-str (slurp (io/file dir-a "endpoints.json")))
                              "endpoints")))))
      (doseq [f files]
        (testing (str f " is byte-identical across two generations")
          (is (= (slurp (io/file dir-a f)) (slurp (io/file dir-b f))))))
      ;; Cleanup
      (doseq [d [dir-a dir-b]]
        (doseq [ff (.listFiles (io/file d))] (.delete ff))
        (.delete (io/file d))))))

;; ============================================================================
;; resolve-leaf-commands RECURSES ONE LEVEL — deeper nesting must FAIL LOUD
;;
;; The nested-group branch walks a group's fields and filters out anything that is
;; itself a routing container. So a group reached THROUGH a group had no branch and
;; was silently dropped, taking every leaf command beneath it out of the manifest
;; with no diagnostic. A command that silently does not exist is worse than a build
;; that stops.
;;
;; MEASURED LATENT, NOT LIVE: zero groups sit at depth >= 2 relative to any of the 14
;; subsystem roots in the current proto tree, so the guard costs nothing today and
;; the tracked manifest cannot move — the guard only throws, it adds no data path.
;; `Lrf_calib` is NOT an instance of this: `resolve-lrf-calib-commands` exists for a
;; different ROOT SHAPE (channel dispatch), at the same depth.
;;
;; NOT fixed by recursing, deliberately: recursion would begin emitting endpoints at
;; paths nobody designed, silently changing a TRACKED manifest. The throw forces the
;; path scheme to be decided by a person on the day the proto actually nests.
;;
;; SYNTHETIC because the real tree cannot express it — which is the whole point: a
;; fixture one level deeper than anything in the corpus is the only way to reach this
;; branch (the audit's own recommendation for the class).

(def ^:private resolve-leaf-commands #'protodoc.manifest/resolve-leaf-commands)

(defn- msg
  "A minimal proto-db message. A `cmd` oneof makes it a GROUP; no oneofs and a
  non-Root name makes it a LEAF."
  [id nm fields oneofs]
  {:id id :name nm :fields fields :oneofs oneofs :package "cmd" :source "t.proto"})

(defn- field [nm type-ref] {:name nm :type :message :type-ref type-ref})

(deftest one-level-nesting-resolves
  ;; CONTROL, and it must come first: the guard must not have broken the depth the
  ;; resolver DOES support. Root -> group -> leaf.
  (let [msgs {"cmd.S.Root" (msg "cmd.S.Root" "Root" [(field "grp" "cmd.S.Grp")] [{:name "cmd"}])
              "cmd.S.Grp" (msg "cmd.S.Grp" "Grp" [(field "go" "cmd.S.Go")] [{:name "cmd"}])
              "cmd.S.Go" (msg "cmd.S.Go" "Go" [] [])}
        out (resolve-leaf-commands msgs (get msgs "cmd.S.Root") "s" "cmd/s")]
    (testing "a leaf under one group level is collected"
      (is (= 1 (count out)))
      (is (= "cmd.S.Go" (:id (first out))))
      (is (= "cmd/s/grp/go" (:path (first out)))))))

(deftest two-level-nesting-throws-instead-of-dropping
  ;; Root -> group -> GROUP -> leaf. Before the guard this returned EMPTY: the inner
  ;; group was filtered by the routing-container? test and its leaf vanished.
  ;;
  ;; REVERT-TO-BREAK: delete the `:let [_ (when (and leaf (group-message? leaf)) …)]`
  ;; guard from resolve-leaf-commands' nested-group branch. This deftest must go red;
  ;; `one-level-nesting-resolves` above must stay GREEN, which attributes the red to
  ;; the guard rather than to the resolver generally.
  (let [msgs {"cmd.S.Root" (msg "cmd.S.Root" "Root" [(field "outer" "cmd.S.Outer")] [{:name "cmd"}])
              "cmd.S.Outer" (msg "cmd.S.Outer" "Outer" [(field "inner" "cmd.S.Inner")] [{:name "cmd"}])
              "cmd.S.Inner" (msg "cmd.S.Inner" "Inner" [(field "go" "cmd.S.Go")] [{:name "cmd"}])
              "cmd.S.Go" (msg "cmd.S.Go" "Go" [] [])}]
    (testing "the unsupported depth is refused, naming what would have been dropped"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"nested deeper than resolve-leaf-commands recurses"
           (doall (resolve-leaf-commands msgs (get msgs "cmd.S.Root") "s" "cmd/s")))))
    (testing "and the diagnosis names the root, the group and the nested group"
      (let [d (try (doall (resolve-leaf-commands msgs (get msgs "cmd.S.Root") "s" "cmd/s"))
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "cmd.S.Root" (:root d)))
        (is (= "cmd.S.Outer" (:group d)))
        (is (= "cmd.S.Inner" (:nested-group d)))))))

;; ============================================================================
;; THE FIELD AXIS — a message-typed field NAMES the message it is
;;
;; The sibling of the depth guard above, on the other axis. An endpoint field is
;; projected one level deep, so a MESSAGE-typed field publishes no interior. That
;; truncation is deliberate and stays: the interior of `cmd.RotaryPlatform.Azimuth`
;; is a REQUIRED 6-arm oneof whose arms are themselves messages, and flattening it
;; into the flat `:fields` vector would publish six mutually-exclusive arms as if
;; they were co-settable — a manifest asserting something FALSE, which is strictly
;; worse than one that stops short.
;;
;; What the entry may NOT do is decline to say what it stopped short OF. `:type-ref`
;; is the whole of the repair: the field names the message its value is, so a reader
;; holding only the manifest can resolve the interior in proto-db instead of
;; re-deriving the reference by a name lookup the manifest already knows.
;;
;; MEASURED: every one of the 327 message-typed fields in proto-db.edn carries a
;; `:type-ref` (and all 52 enum-typed ones do too), so the guard below is LATENT —
;; it cannot red the current tree, exactly like the depth guard above.
;;
;; SYNTHETIC for the guard, because the real tree cannot express its input.

(defn- scalar-field
  "A minimal proto-db SCALAR field. The parameter is `ftype`, not `type`:
  clj-kondo errors on a binding that shadows `clojure.core/type`."
  [nm ftype]
  {:name nm :type ftype})

(defn- leaf-endpoint-fields
  "The `:fields` vector resolve-leaf-commands projects for a Root -> leaf whose
  leaf carries `leaf-fields`."
  [leaf-fields]
  (let [msgs {"cmd.S.Root" (msg "cmd.S.Root" "Root" [(field "go" "cmd.S.Go")] [{:name "cmd"}])
              "cmd.S.Go" (msg "cmd.S.Go" "Go" leaf-fields [])}]
    (:fields (first (resolve-leaf-commands msgs (get msgs "cmd.S.Root") "s" "cmd/s")))))

(deftest scalar-fields-carry-no-type-ref
  ;; CONTROL, and it must come first: the projection must not have started
  ;; decorating fields whose value space is already published inline. A scalar
  ;; carries its bounds in :constraints; it refers to nothing.
  (let [out (leaf-endpoint-fields [(scalar-field "value" :int32)])]
    (testing "a scalar field projects name and type only"
      (is (= 1 (count out)))
      (is (= "value" (:name (first out))))
      (is (= "int32" (:type (first out)))))
    (testing "and gains no :type-ref"
      (is (not (contains? (first out) :type-ref))))))

(deftest message-typed-field-names-its-message
  ;; A message field is the ONE entry whose value space is neither published inline
  ;; nor nameable without this key. Before the repair it emitted {name, type} and a
  ;; reader could not tell WHICH message it had stopped short of.
  (let [out (leaf-endpoint-fields [(field "azimuth" "cmd.S.Azimuth")])]
    (testing "the field still reports its true proto type"
      (is (= 1 (count out)))
      (is (= "azimuth" (:name (first out))))
      (is (= "message" (:type (first out)))))
    (testing "and now names the message its value is"
      (is (= "cmd.S.Azimuth" (:type-ref (first out)))))))

(deftest message-typed-field-without-a-type-ref-throws
  ;; The field the projection cannot express even BY REFERENCE. proto-db's own Field
  ;; schema documents :type-ref as "For enum/message refs", so a message field
  ;; without one is a malformed db, not a shape to publish: emitting {name, type}
  ;; there would republish the exact opaque entry this section exists to retire.
  ;;
  ;; REVERT-TO-BREAK: delete the `when`/`throw` clause from `endpoint-field`. This
  ;; deftest must go red; `message-typed-field-names-its-message` and
  ;; `scalar-fields-carry-no-type-ref` above must both stay GREEN, which attributes
  ;; the red to the guard rather than to the projection generally.
  (let [bad [{:name "azimuth" :type :message}]]
    (testing "the unnameable field is refused rather than published opaque"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"message-typed command field names no message"
           (doall (leaf-endpoint-fields bad)))))
    (testing "and the diagnosis names the field and its owning message"
      (let [d (try (doall (leaf-endpoint-fields bad))
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "azimuth" (:field d)))
        (is (= "cmd.S.Go" (:message d)))))))
