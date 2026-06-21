(ns protodoc.manifest-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
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

  (testing "uint32 without constraints → [:int {:min 0}] (no upper cap)"
    (let [s (manifest/constraints->malli {:type :uint32} {})]
      (is (= [:int {:min 0}] s))
      (is (edn-roundtrips? s))))

  (testing "bool"
    (let [s (manifest/constraints->malli {:type :bool} {})]
      (is (= :boolean s))
      (is (edn-roundtrips? s))))

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
      (is (= [:int {:min 1 :max 2147483647 :no-default true}] s))
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
        (let [focus-eps (filter #(clojure.string/starts-with? (:path %) "cmd/day-camera/focus/")
                                (:endpoints result))]
          (is (>= (count focus-eps) 4))
          (is (some #(= "cmd/day-camera/focus/set-value" (:path %)) focus-eps))))

      (testing "lrf-calib has day/heat channel dispatch"
        (let [lrf-eps (filter #(= "lrf-calib" (:subsystem %)) (:endpoints result))]
          (is (= 8 (count lrf-eps)))
          (is (some #(= "cmd/lrf-calib/day/set" (:path %)) lrf-eps))
          (is (some #(= "cmd/lrf-calib/heat/set" (:path %)) lrf-eps))))

      (testing "no Root or group messages appear as endpoints"
        (is (not-any? #(clojure.string/ends-with? (:id %) ".Root")
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
          sub-signals-data (manifest/extract-sub-signals db config)
          result (manifest/build-reverse-index endpoints-data signals-data sub-signals-data)]
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

(deftest full-manifest-generation-test
  (when-let [_db (load-db)]
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/manifest-test-" (System/nanoTime))
          result (manifest/generate-manifests
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
