(ns protodoc.binary-dedup-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [protodoc.binary-dedup :as bd]
            [protodoc.manifest :as manifest]))

;; ============================================================================
;; Test Helpers
;; ============================================================================

(defn- make-field
  "Create a field descriptor for testing."
  ([name number type]
   {"name" name "number" number "type" type})
  ([name number type type-name]
   {"name" name "number" number "type" type "typeName" type-name})
  ([name number type type-name label]
   {"name" name "number" number "type" type "typeName" type-name "label" label}))

(defn- make-message
  "Create a message descriptor for testing."
  [name fields & {:keys [nested-types]}]
  (cond-> {"name" name "field" fields}
    nested-types (assoc "nestedType" nested-types)))

(defn- make-descriptor
  "Create a minimal descriptor-set structure for testing."
  [& file-specs]
  {"file" (vec file-specs)})

(defn- make-file
  "Create a file descriptor for testing."
  [filename package messages]
  {"name" filename "package" package "messageType" messages})

(defn- write-temp-descriptor!
  "Write a descriptor to a temp file and return the path."
  [descriptor]
  (let [f (java.io.File/createTempFile "test-descriptor" ".json")]
    (.deleteOnExit f)
    (spit f (json/write-str descriptor))
    (.getPath f)))

;; ============================================================================
;; Minimal valid JonGUIState descriptor for testing
;; ============================================================================

(def ^:private minimal-gui-state
  "A minimal JonGUIState with two subsystem fields and root scalars."
  (make-file
    "jon_shared_data.proto" "ser"
    [(make-message "JonGUIState"
       [(make-field "protocol_version" 1 "TYPE_UINT32")
        (make-field "system_monotonic_time_us" 2 "TYPE_UINT64")
        (make-field "system" 13 "TYPE_MESSAGE" ".ser.JonGuiDataSystem")
        (make-field "gps" 17 "TYPE_MESSAGE" ".ser.JonGuiDataGps")])]))

(def ^:private minimal-subsystems
  "Companion subsystem message definitions."
  (make-file
    "jon_shared_data_system.proto" "ser"
    [(make-message "JonGuiDataSystem"
       [(make-field "cpu_temperature" 1 "TYPE_DOUBLE")
        (make-field "gpu_temperature" 2 "TYPE_DOUBLE")
        (make-field "tracking" 18 "TYPE_BOOL")])]))

(def ^:private minimal-gps
  (make-file
    "jon_shared_data_gps.proto" "ser"
    [(make-message "JonGuiDataGps"
       [(make-field "longitude" 1 "TYPE_DOUBLE")
        (make-field "latitude" 2 "TYPE_DOUBLE")
        (make-field "fix_type" 3 "TYPE_INT32")])]))

;; ============================================================================
;; snake->camel Tests
;; ============================================================================

(deftest snake-to-camel-conversion-test
  (testing "Standard conversions"
    (is (= "cameraDay" (manifest/snake->camel "camera_day")))
    (is (= "compassCalibration" (manifest/snake->camel "compass_calibration")))
    (is (= "recOsd" (manifest/snake->camel "rec_osd")))
    (is (= "actualSpaceTime" (manifest/snake->camel "actual_space_time")))
    (is (= "meteoInternal" (manifest/snake->camel "meteo_internal"))))

  (testing "No-underscore names pass through"
    (is (= "lrf" (manifest/snake->camel "lrf")))
    (is (= "system" (manifest/snake->camel "system")))
    (is (= "gps" (manifest/snake->camel "gps")))
    (is (= "compass" (manifest/snake->camel "compass")))
    (is (= "rotary" (manifest/snake->camel "rotary")))
    (is (= "power" (manifest/snake->camel "power")))
    (is (= "heater" (manifest/snake->camel "heater")))))

;; ============================================================================
;; Subsystem Field Extraction Tests
;; ============================================================================

(deftest extract-subsystem-fields-test
  (let [gui-state (get (first (get (make-descriptor minimal-gui-state) "file"))
                       "messageType")
        gui-msg (first gui-state)]

    (testing "Extracts only TYPE_MESSAGE fields with number >= 13"
      (let [fields (bd/extract-subsystem-fields gui-msg)]
        (is (= 2 (count fields)))
        (is (= "system" (:name (first fields))))
        (is (= 13 (:number (first fields))))
        (is (= "gps" (:name (second fields))))
        (is (= 17 (:number (second fields))))))

    (testing "Excludes scalar fields (tags 1-7)"
      (let [fields (bd/extract-subsystem-fields gui-msg)]
        (is (not-any? #(< (:number %) 13) fields)))))

  (testing "Excludes repeated message fields"
    (let [msg (make-message "JonGUIState"
               [(make-field "opaque_payloads" 8 "TYPE_MESSAGE" ".ser.Payload" "LABEL_REPEATED")
                (make-field "system" 13 "TYPE_MESSAGE" ".ser.System")])
          fields (bd/extract-subsystem-fields msg)]
      (is (= 1 (count fields)))
      (is (= "system" (:name (first fields))))))

  (testing "Empty fields produces empty result"
    (let [msg (make-message "JonGUIState"
               [(make-field "version" 1 "TYPE_UINT32")])
          fields (bd/extract-subsystem-fields msg)]
      (is (empty? fields))))

  (testing "Fields sorted by number"
    (let [msg (make-message "JonGUIState"
               [(make-field "heater" 29 "TYPE_MESSAGE" ".ser.Heater")
                (make-field "system" 13 "TYPE_MESSAGE" ".ser.System")
                (make-field "gps" 17 "TYPE_MESSAGE" ".ser.Gps")])
          fields (bd/extract-subsystem-fields msg)]
      (is (= [13 17 29] (mapv :number fields))))))

;; ============================================================================
;; Map Field Detection Tests
;; ============================================================================

(deftest has-map-field-clean-test
  (testing "Clean message with only scalars returns nil"
    (let [msg (make-message "Clean" [(make-field "value" 1 "TYPE_DOUBLE")])
          idx {"ser.Clean" msg}]
      (is (nil? (bd/has-map-field? idx msg #{})))))

  (testing "Message with nested non-map message returns nil"
    (let [inner (make-message "Inner" [(make-field "x" 1 "TYPE_DOUBLE")])
          outer (make-message "Outer"
                  [(make-field "inner" 1 "TYPE_MESSAGE" ".ser.Inner")])
          idx {"ser.Inner" inner "ser.Outer" outer}]
      (is (nil? (bd/has-map-field? idx outer #{}))))))

(deftest has-map-field-direct-test
  (testing "Direct map field detected"
    (let [map-entry (assoc (make-message "TagsEntry"
                             [(make-field "key" 1 "TYPE_STRING")
                              (make-field "value" 2 "TYPE_STRING")])
                           "options" {"mapEntry" true})
          msg (make-message "WithMap"
                [(make-field "tags" 1 "TYPE_MESSAGE" ".ser.WithMap.TagsEntry")]
                :nested-types [map-entry])
          idx {"ser.WithMap" msg}]
      (is (= ["tags"] (bd/has-map-field? idx msg #{}))))))

(deftest has-map-field-nested-test
  (testing "Map field 2 levels deep detected"
    (let [map-entry (assoc (make-message "DataEntry"
                             [(make-field "key" 1 "TYPE_STRING")
                              (make-field "value" 2 "TYPE_INT32")])
                           "options" {"mapEntry" true})
          inner (make-message "Inner"
                  [(make-field "data" 5 "TYPE_MESSAGE" ".ser.Inner.DataEntry")]
                  :nested-types [map-entry])
          outer (make-message "Outer"
                  [(make-field "inner" 1 "TYPE_MESSAGE" ".ser.Inner")])
          idx {"ser.Inner" inner "ser.Outer" outer}]
      (is (= ["inner" "data"] (bd/has-map-field? idx outer #{}))))))

;; ============================================================================
;; Determinism Validation Tests
;; ============================================================================

(deftest validate-determinism-clean-test
  (testing "Clean schema passes validation"
    (let [descriptor (make-descriptor minimal-gui-state minimal-subsystems minimal-gps)
          msg-index (#'protodoc.binary-dedup/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      ;; Should not throw
      (bd/validate-determinism! msg-index fields))))

(deftest validate-determinism-map-fails-test
  (testing "Map field in subsystem fails with structured error"
    (let [map-entry (assoc (make-message "CoordsEntry"
                             [(make-field "key" 1 "TYPE_STRING")
                              (make-field "value" 2 "TYPE_DOUBLE")])
                           "options" {"mapEntry" true})
          gps-with-map (make-file
                         "jon_shared_data_gps.proto" "ser"
                         [(make-message "JonGuiDataGps"
                            [(make-field "coords" 10 "TYPE_MESSAGE"
                                         ".ser.JonGuiDataGps.CoordsEntry")]
                            :nested-types [map-entry])])
          descriptor (make-descriptor minimal-gui-state minimal-subsystems gps-with-map)
          msg-index (#'protodoc.binary-dedup/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      (try
        (bd/validate-determinism! msg-index fields)
        (is false "Should have thrown ex-info")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :non-deterministic-field (:type data)))
            (is (= ["gps" "coords"] (:path data)))
            (is (= 17 (:field-number data)))
            (is (str/includes? (ex-message e) "map fields"))
            (is (str/includes? (ex-message e) "ser.JonGUIState.gps"))
            (is (str/includes? (ex-message e) "Fix:"))))))))

(deftest validate-determinism-nested-map-fails-test
  (testing "Map field nested 2 levels deep fails with full path"
    (let [map-entry (assoc (make-message "MetaEntry"
                             [(make-field "key" 1 "TYPE_STRING")
                              (make-field "value" 2 "TYPE_STRING")])
                           "options" {"mapEntry" true})
          meteo (make-file
                  "jon_shared_data_types.proto" "ser"
                  [(make-message "JonGuiDataMeteo"
                     [(make-field "meta" 10 "TYPE_MESSAGE"
                                  ".ser.JonGuiDataMeteo.MetaEntry")]
                     :nested-types [map-entry])])
          system-with-meteo (make-file
                              "jon_shared_data_system.proto" "ser"
                              [(make-message "JonGuiDataSystem"
                                 [(make-field "meteo" 30 "TYPE_MESSAGE"
                                              ".ser.JonGuiDataMeteo")])])
          descriptor (make-descriptor minimal-gui-state system-with-meteo meteo minimal-gps)
          msg-index (#'protodoc.binary-dedup/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      (try
        (bd/validate-determinism! msg-index fields)
        (is false "Should have thrown ex-info")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :non-deterministic-field (:type data)))
            (is (= ["system" "meteo" "meta"] (:path data)))))))))

;; ============================================================================
;; TypeScript Generation Tests
;; ============================================================================

(deftest generate-typescript-test
  (testing "Generates valid TypeScript with correct structure"
    (let [fields [{:name "system" :number 13 :type-name "ser.JonGuiDataSystem"}
                  {:name "camera_day" :number 20 :type-name "ser.JonGuiDataCameraDay"}
                  {:name "actual_space_time" :number 25 :type-name "ser.JonGuiDataActualSpaceTime"}]
          ts (bd/generate-typescript fields)]
      (is (str/includes? ts "AUTO-GENERATED"))
      (is (str/includes? ts "DO NOT EDIT"))
      (is (str/includes? ts "as const"))
      (is (str/includes? ts "system: 13,"))
      (is (str/includes? ts "cameraDay: 20,"))
      (is (str/includes? ts "actualSpaceTime: 25,"))
      (is (str/includes? ts "StateSubsystemKey"))
      (is (str/includes? ts "ReadonlySet<number>"))))

  (testing "Empty fields generates empty map"
    (let [ts (bd/generate-typescript [])]
      (is (str/includes? ts "STATE_SUBSYSTEM_TAGS = {"))
      (is (str/includes? ts "ReadonlySet<number>")))))

(deftest generate-typescript-reserved-gap-test
  (testing "Reserved field gap (tag 24) handled correctly"
    (let [fields [{:name "rec_osd" :number 23 :type-name "ser.RecOsd"}
                  {:name "actual_space_time" :number 25 :type-name "ser.AST"}]
          ts (bd/generate-typescript fields)]
      (is (str/includes? ts "recOsd: 23,"))
      (is (str/includes? ts "actualSpaceTime: 25,"))
      (is (not (str/includes? ts "24"))))))

;; ============================================================================
;; Wire Tag Value Tests
;; ============================================================================

(deftest wire-tag-values-test
  (testing "Wire tag calculation: (field_number << 3) | 2 for length-delimited"
    ;; Verify against known values from the generated TS decode switch
    (let [expected {13 106, 14 114, 15 122, 16 130, 17 138, 18 146,
                    19 154, 20 162, 21 170, 22 178, 23 186, 25 202,
                    26 210, 27 218, 28 226, 29 234}]
      (doseq [[field-number expected-tag] expected]
        (is (= expected-tag (bit-or (bit-shift-left field-number 3) 2))
            (str "Field " field-number " should have wire tag " expected-tag))))))

;; ============================================================================
;; Integration Test (uses real descriptor if available)
;; ============================================================================

(def ^:private real-descriptor-path
  "../../../output/json-descriptors/descriptor-set.json")

(deftest integration-real-descriptor-test
  (when (.exists (io/file real-descriptor-path))
    (testing "Real descriptor-set.json produces correct subsystem count"
      (let [descriptor (json/read-str (slurp (io/file real-descriptor-path)))
            msg-index (#'protodoc.binary-dedup/build-message-index descriptor)
            gui-state (get msg-index "ser.JonGUIState")
            fields (bd/extract-subsystem-fields gui-state)]
        ;; Expected: 16 subsystem fields (tags 13-29, gap at 24)
        (is (= 16 (count fields)))
        ;; Verify specific known fields
        (is (= 13 (:number (first fields))))
        (is (= "system" (:name (first fields))))
        (is (= 29 (:number (last fields))))
        (is (= "heater" (:name (last fields))))
        ;; Tag 24 should not be present (reserved)
        (is (not-any? #(= 24 (:number %)) fields))
        ;; Validation should pass (no map fields in production protos)
        (bd/validate-determinism! msg-index fields)))

    (testing "Full generate! produces valid TypeScript"
      (let [output-file (java.io.File/createTempFile "binary-dedup" ".ts")]
        (try
          (bd/generate! real-descriptor-path (.getPath output-file))
          (let [content (slurp output-file)]
            (is (str/includes? content "STATE_SUBSYSTEM_TAGS"))
            (is (str/includes? content "system: 13,"))
            (is (str/includes? content "heater: 29,")))
          (finally
            (.delete output-file)))))))
