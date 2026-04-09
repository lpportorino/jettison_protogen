(ns protodoc.binary-dedup-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [protodoc.binary-dedup :as bd]
            [protodoc.manifest :as manifest]))

;; ============================================================================
;; FRAMEWORK SELF-CHECK (MUST BE FIRST — detects silent test-runner breakage)
;;
;; If clojure.test is silently broken (assertions no-op'd, reports swallowed,
;; test discovery fails), these tests must either fail loudly or not appear
;; in the run output. Tests use distinctive "CANARY-" prefixes so CI can grep
;; for them as a "tests-were-discovered" marker.
;;
;; Canary guidance: do NOT use the `is` macro to verify `is` itself. If `is`
;; is silently broken, using it for the canary produces a circular false
;; negative (both the broken and the verifying `is` pass). Instead, use raw
;; `throw` for the canary failure path — a real thrown exception is reported
;; by clojure.test as an :error regardless of the `is` macro's state.
;; ============================================================================

(deftest CANARY-1-body-executes-test
  (testing "CANARY-1: test body runs to completion"
    (let [counter (atom 0)]
      (swap! counter inc)
      (swap! counter inc)
      (swap! counter inc)
      (swap! counter inc)
      (swap! counter inc)
      (when-not (= 5 @counter)
        (throw (ex-info (str "CANARY FAILURE: body counter=" @counter " expected 5")
                        {:type :canary-failure :counter @counter}))))))

(deftest CANARY-2-is-macro-reports-failures-test
  (testing "CANARY-2: (is (= 1 2)) actually reports :fail to clojure.test/report"
    ;; Capture all report calls via with-redefs. A known-false assertion must
    ;; produce exactly one :fail entry. If `is` is silently broken (does not
    ;; call report), captured is empty and we throw a raw exception.
    (let [captured (atom [])]
      (with-redefs [clojure.test/report (fn [m] (swap! captured conj m))]
        (is (= 1 2) "intentional failure used by canary"))
      (let [fails (filter #(= :fail (:type %)) @captured)]
        (when-not (= 1 (count fails))
          (throw (ex-info
                   (str "CANARY FAILURE: (is (= 1 2)) did not produce exactly 1 :fail "
                        "report. Got " (count fails) " fails from " (count @captured)
                        " total reports. clojure.test is silently broken — NO OTHER "
                        "TEST RESULT CAN BE TRUSTED.")
                   {:type :canary-failure
                    :captured @captured
                    :fail-count (count fails)})))))))

(deftest CANARY-3-is-macro-reports-passes-test
  (testing "CANARY-3: (is true) reports :pass to clojure.test/report"
    ;; Mirror of CANARY-2 for the pass path. If `is` silently fails to report
    ;; passes either, test counts will be wrong.
    (let [captured (atom [])]
      (with-redefs [clojure.test/report (fn [m] (swap! captured conj m))]
        (is true "intentional pass used by canary"))
      (let [passes (filter #(= :pass (:type %)) @captured)]
        (when-not (= 1 (count passes))
          (throw (ex-info
                   (str "CANARY FAILURE: (is true) did not produce exactly 1 :pass "
                        "report. Got " (count passes) " passes.")
                   {:type :canary-failure
                    :pass-count (count passes)})))))))

(deftest CANARY-4-thrown-with-msg-works-test
  (testing "CANARY-4: thrown-with-msg? matches exception patterns"
    ;; Uses a throw outside any is to verify the macro machinery works.
    (let [captured (atom [])]
      (with-redefs [clojure.test/report (fn [m] (swap! captured conj m))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"CANARY-SENTINEL"
                              (throw (ex-info "CANARY-SENTINEL error" {})))))
      (let [passes (filter #(= :pass (:type %)) @captured)
            errors (filter #(= :error (:type %)) @captured)
            fails (filter #(= :fail (:type %)) @captured)]
        (when-not (and (= 1 (count passes)) (zero? (count errors)) (zero? (count fails)))
          (throw (ex-info
                   (str "CANARY FAILURE: thrown-with-msg? did not pass on a matching "
                        "exception. passes=" (count passes) " errors=" (count errors)
                        " fails=" (count fails))
                   {:type :canary-failure})))))))

(deftest CANARY-5-sentinel-test-discovery-marker
  (testing "CANARY-5 sentinel: this test name appears in test output"
    ;; Trivial — its value is that the test name appears in clojure.test output.
    ;; CI can grep for "CANARY-5 sentinel" to verify this file was loaded and
    ;; tests were enumerated. If this line doesn't appear in output, test
    ;; discovery is broken.
    (let [marker "CANARY-sentinel-present"]
      (when-not (= marker "CANARY-sentinel-present")
        (throw (ex-info "CANARY FAILURE: string equality broken (unreachable)"
                        {:type :canary-failure}))))))

(deftest CANARY-6-namespace-loaded-test
  (testing "CANARY-6: binary-dedup namespace loaded and functions resolvable"
    ;; If the namespace under test failed to load (e.g., due to compile error),
    ;; none of the other tests would run. This canary verifies the target
    ;; functions actually exist. Uses raw var resolution instead of `is`.
    (let [required-vars ['extract-subsystem-fields 'has-map-field?
                         'validate-determinism! 'generate-typescript 'generate!]]
      (doseq [sym required-vars]
        (let [v (ns-resolve 'protodoc.binary-dedup sym)]
          (when-not (var? v)
            (throw (ex-info (str "CANARY FAILURE: protodoc.binary-dedup/" sym
                                 " is not resolvable. Namespace load failed?")
                            {:type :canary-failure :missing-var sym}))))))))

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

(defn- make-map-entry
  "Construct a nested descriptor for a map<K,V> entry type.
   map-entry-name is the short type name (e.g., \"TagsEntry\")."
  [map-entry-name key-type value-type]
  (assoc (make-message map-entry-name
                       [(make-field "key" 1 key-type)
                        (make-field "value" 2 value-type)])
         "options" {"mapEntry" true}))

(defn- make-map-field
  "Construct a TYPE_MESSAGE field referencing a nested map entry type."
  [parent-fqn map-entry-name field-name field-number]
  (make-field field-name field-number "TYPE_MESSAGE"
              (str "." parent-fqn "." map-entry-name)))

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
      (is (= [13 17 29] (mapv :number fields)))))

  ;; --- New boundary tests (Group C) ---

  (testing "Boundary: number == 12 excluded, number == 13 included"
    (let [msg (make-message "JonGUIState"
                            [(make-field "below" 12 "TYPE_MESSAGE" ".ser.A")
                             (make-field "at" 13 "TYPE_MESSAGE" ".ser.B")
                             (make-field "above" 14 "TYPE_MESSAGE" ".ser.C")])
          fields (bd/extract-subsystem-fields msg)]
      (is (= 2 (count fields)))
      (is (= 13 (:number (first fields))))
      (is (= "at" (:name (first fields))))))

  (testing "Very large field number included if >= 13"
    (let [msg (make-message "JonGUIState"
                            [(make-field "huge" 536000000 "TYPE_MESSAGE" ".ser.Big")])
          fields (bd/extract-subsystem-fields msg)]
      (is (= 1 (count fields)))
      (is (= 536000000 (:number (first fields))))))

  (testing "Scalar at boundary excluded"
    (let [msg (make-message "JonGUIState"
                            [(make-field "scalar_at_13" 13 "TYPE_INT32")])
          fields (bd/extract-subsystem-fields msg)]
      (is (empty? fields))))

  (testing "TYPE_GROUP at boundary throws (fail-closed)"
    (let [msg (make-message "JonGUIState"
                            [(make-field "legacy_group" 13 "TYPE_GROUP" ".ser.LegacyGroup")])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"TYPE_GROUP"
                            (bd/extract-subsystem-fields msg)))
      (try
        (bd/extract-subsystem-fields msg)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :unsupported-type-group (:type (ex-data e))))
          (is (= 13 (:field-number (ex-data e))))))))

  (testing "Unknown TYPE_* throws (fail-closed whitelist)"
    (let [msg (make-message "JonGUIState"
                            [(make-field "wibble" 13 "TYPE_WIBBLE" ".ser.Wibble")])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unknown field type"
                            (bd/extract-subsystem-fields msg)))
      (try
        (bd/extract-subsystem-fields msg)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :unknown-field-type (:type (ex-data e))))
          (is (= "TYPE_WIBBLE" (:type-value (ex-data e))))))))

  (testing "Duplicate field numbers throw (fail-closed)"
    (let [msg (make-message "JonGUIState"
                            [(make-field "foo" 13 "TYPE_MESSAGE" ".ser.A")
                             (make-field "bar" 13 "TYPE_MESSAGE" ".ser.B")])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"duplicate"
                            (bd/extract-subsystem-fields msg)))
      (try
        (bd/extract-subsystem-fields msg)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :duplicate-field-number (:type (ex-data e))))
          (is (= 13 (:number (ex-data e))))
          (is (= #{"foo" "bar"} (set (:names (ex-data e))))))))))

;; ============================================================================
;; Map Field Detection Tests
;; ============================================================================

(deftest has-map-field-clean-test
  (testing "Clean message with only scalars returns nil"
    (let [msg (make-message "Clean" [(make-field "value" 1 "TYPE_DOUBLE")])
          idx {"ser.Clean" msg}]
      (is (nil? (bd/has-map-field? idx "ser.Clean" msg #{})))))

  (testing "Message with nested non-map message returns nil"
    (let [inner (make-message "Inner" [(make-field "x" 1 "TYPE_DOUBLE")])
          outer (make-message "Outer"
                              [(make-field "inner" 1 "TYPE_MESSAGE" ".ser.Inner")])
          idx {"ser.Inner" inner "ser.Outer" outer}]
      (is (nil? (bd/has-map-field? idx "ser.Outer" outer #{}))))))

(deftest has-map-field-direct-test
  (testing "Direct map field detected"
    (let [map-entry (make-map-entry "TagsEntry" "TYPE_STRING" "TYPE_STRING")
          msg (make-message "WithMap"
                            [(make-map-field "ser.WithMap" "TagsEntry" "tags" 1)]
                            :nested-types [map-entry])
          idx {"ser.WithMap" msg}]
      (is (= ["tags"] (bd/has-map-field? idx "ser.WithMap" msg #{}))))))

(deftest has-map-field-nested-test
  (testing "Map field 2 levels deep detected"
    (let [map-entry (make-map-entry "DataEntry" "TYPE_STRING" "TYPE_INT32")
          inner (make-message "Inner"
                              [(make-map-field "ser.Inner" "DataEntry" "data" 5)]
                              :nested-types [map-entry])
          outer (make-message "Outer"
                              [(make-field "inner" 1 "TYPE_MESSAGE" ".ser.Inner")])
          idx {"ser.Inner" inner "ser.Outer" outer}]
      (is (= ["inner" "data"] (bd/has-map-field? idx "ser.Outer" outer #{}))))))

;; --- Group A: Cycle protection and deep nesting ---

(deftest has-map-field-self-cycle-test
  (testing "Self-referencing message terminates without hanging"
    (let [tree (make-message "Tree"
                             [(make-field "child" 1 "TYPE_MESSAGE" ".ser.Tree")
                              (make-field "value" 2 "TYPE_DOUBLE")])
          idx {"ser.Tree" tree}
          start (System/nanoTime)
          result (bd/has-map-field? idx "ser.Tree" tree #{})
          elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)]
      (is (nil? result))
      (is (< elapsed-ms 100) "Self-cycle should terminate in under 100ms"))))

(deftest has-map-field-mutual-cycle-test
  (testing "A→B→A mutual cycle terminates"
    (let [a (make-message "A"
                          [(make-field "b" 1 "TYPE_MESSAGE" ".ser.B")])
          b (make-message "B"
                          [(make-field "a" 1 "TYPE_MESSAGE" ".ser.A")])
          idx {"ser.A" a "ser.B" b}]
      (is (nil? (bd/has-map-field? idx "ser.A" a #{}))))))

(deftest has-map-field-cycle-with-map-test
  (testing "Cycle containing a direct map still detects the map"
    (let [map-entry (make-map-entry "TagsEntry" "TYPE_STRING" "TYPE_STRING")
          a (make-message "A"
                          [(make-field "b" 1 "TYPE_MESSAGE" ".ser.B")
                           (make-map-field "ser.A" "TagsEntry" "tags" 2)]
                          :nested-types [map-entry])
          b (make-message "B"
                          [(make-field "a" 1 "TYPE_MESSAGE" ".ser.A")])
          idx {"ser.A" a "ser.B" b}]
      (is (= ["tags"] (bd/has-map-field? idx "ser.A" a #{}))))))

(deftest has-map-field-deep-4-levels-clean-test
  (testing "4-level clean chain returns nil"
    (let [l4 (make-message "L4" [(make-field "x" 1 "TYPE_DOUBLE")])
          l3 (make-message "L3" [(make-field "l4" 1 "TYPE_MESSAGE" ".ser.L4")])
          l2 (make-message "L2" [(make-field "l3" 1 "TYPE_MESSAGE" ".ser.L3")])
          l1 (make-message "L1" [(make-field "l2" 1 "TYPE_MESSAGE" ".ser.L2")])
          idx {"ser.L1" l1 "ser.L2" l2 "ser.L3" l3 "ser.L4" l4}]
      (is (nil? (bd/has-map-field? idx "ser.L1" l1 #{}))))))

(deftest has-map-field-deep-4-levels-map-leaf-test
  (testing "4-level chain with map at the leaf returns full 4-element path"
    (let [map-entry (make-map-entry "MEntry" "TYPE_STRING" "TYPE_STRING")
          l4 (make-message "L4"
                           [(make-map-field "ser.L4" "MEntry" "m" 1)]
                           :nested-types [map-entry])
          l3 (make-message "L3" [(make-field "l4" 1 "TYPE_MESSAGE" ".ser.L4")])
          l2 (make-message "L2" [(make-field "l3" 1 "TYPE_MESSAGE" ".ser.L3")])
          l1 (make-message "L1" [(make-field "l2" 1 "TYPE_MESSAGE" ".ser.L2")])
          idx {"ser.L1" l1 "ser.L2" l2 "ser.L3" l3 "ser.L4" l4}]
      (is (= ["l2" "l3" "l4" "m"] (bd/has-map-field? idx "ser.L1" l1 #{}))))))

;; --- Group B: Repeated and oneof containers ---

(deftest has-map-field-repeated-msg-with-inner-map-test
  (testing "repeated SubMsg where SubMsg has a map — detected"
    (let [map-entry (make-map-entry "CountsEntry" "TYPE_STRING" "TYPE_INT32")
          entry-msg (make-message "Entry"
                                  [(make-map-field "ser.Entry" "CountsEntry" "counts" 1)]
                                  :nested-types [map-entry])
          outer (make-message "Outer"
                              [(make-field "entries" 1 "TYPE_MESSAGE" ".ser.Entry" "LABEL_REPEATED")])
          idx {"ser.Entry" entry-msg "ser.Outer" outer}]
      (is (= ["entries" "counts"] (bd/has-map-field? idx "ser.Outer" outer #{}))))))

(deftest has-map-field-oneof-with-inner-map-test
  (testing "oneof { MsgWithMap a = 1; MsgPlain b = 2; } — detected"
    (let [map-entry (make-map-entry "TagsEntry" "TYPE_STRING" "TYPE_STRING")
          sub (make-message "Sub"
                            [(make-map-field "ser.Sub" "TagsEntry" "tags" 1)]
                            :nested-types [map-entry])
          plain (make-message "Plain"
                              [(make-field "x" 1 "TYPE_DOUBLE")])
          outer (-> (make-message "Outer"
                                  [(assoc (make-field "a" 1 "TYPE_MESSAGE" ".ser.Sub") "oneofIndex" 0)
                                   (assoc (make-field "b" 2 "TYPE_MESSAGE" ".ser.Plain") "oneofIndex" 0)])
                    (assoc "oneofDecl" [{"name" "variant"}]))
          idx {"ser.Sub" sub "ser.Plain" plain "ser.Outer" outer}]
      (is (= ["a" "tags"] (bd/has-map-field? idx "ser.Outer" outer #{}))))))

(deftest has-map-field-proto3-synthetic-oneof-test
  (testing "proto3 'optional' (synthetic oneof) with inner map — detected"
    (let [map-entry (make-map-entry "MetaEntry" "TYPE_STRING" "TYPE_STRING")
          sub (make-message "Sub"
                            [(make-map-field "ser.Sub" "MetaEntry" "meta" 1)]
                            :nested-types [map-entry])
          ;; proto3 optional synthesizes a single-field oneof named "_sub"
          outer (-> (make-message "Outer"
                                  [(assoc (make-field "sub" 1 "TYPE_MESSAGE" ".ser.Sub")
                                          "oneofIndex" 0
                                          "proto3Optional" true)])
                    (assoc "oneofDecl" [{"name" "_sub"}]))
          idx {"ser.Sub" sub "ser.Outer" outer}]
      (is (= ["sub" "meta"] (bd/has-map-field? idx "ser.Outer" outer #{}))))))

;; ============================================================================
;; Determinism Validation Tests
;; ============================================================================

(deftest validate-determinism-clean-test
  (testing "Clean schema passes validation"
    (let [descriptor (make-descriptor minimal-gui-state minimal-subsystems minimal-gps)
          msg-index (#'bd/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      ;; Should not throw
      (bd/validate-determinism! msg-index fields))))

(deftest validate-determinism-map-fails-test
  (testing "Map field in subsystem fails with structured error"
    (let [map-entry (make-map-entry "CoordsEntry" "TYPE_STRING" "TYPE_DOUBLE")
          gps-with-map (make-file
                         "jon_shared_data_gps.proto" "ser"
                         [(make-message "JonGuiDataGps"
                                        [(make-map-field "ser.JonGuiDataGps" "CoordsEntry" "coords" 10)]
                                        :nested-types [map-entry])])
          descriptor (make-descriptor minimal-gui-state minimal-subsystems gps-with-map)
          msg-index (#'bd/build-message-index descriptor)
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
    (let [map-entry (make-map-entry "MetaEntry" "TYPE_STRING" "TYPE_STRING")
          meteo (make-file
                  "jon_shared_data_types.proto" "ser"
                  [(make-message "JonGuiDataMeteo"
                                 [(make-map-field "ser.JonGuiDataMeteo" "MetaEntry" "meta" 10)]
                                 :nested-types [map-entry])])
          system-with-meteo (make-file
                              "jon_shared_data_system.proto" "ser"
                              [(make-message "JonGuiDataSystem"
                                             [(make-field "meteo" 30 "TYPE_MESSAGE"
                                                          ".ser.JonGuiDataMeteo")])])
          descriptor (make-descriptor minimal-gui-state system-with-meteo meteo minimal-gps)
          msg-index (#'bd/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      (try
        (bd/validate-determinism! msg-index fields)
        (is false "Should have thrown ex-info")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :non-deterministic-field (:type data)))
            (is (= ["system" "meteo" "meta"] (:path data)))))))))

;; --- Group D: Shared type propagation ---

(deftest validate-determinism-shared-type-first-hit-test
  (testing "Two subsystems share a bad type — failure reports lowest-numbered subsystem"
    ;; Construct: tag 13 (system) → Meteo; tag 14 (meteo_internal) → Meteo
    ;; Meteo has a map. Sort order puts system first → error points to system.
    (let [map-entry (make-map-entry "TagsEntry" "TYPE_STRING" "TYPE_STRING")
          meteo (make-file
                  "jon_shared_data_meteo.proto" "ser"
                  [(make-message "JonGuiDataMeteo"
                                 [(make-map-field "ser.JonGuiDataMeteo" "TagsEntry" "tags" 1)]
                                 :nested-types [map-entry])])
          ;; system subsystem has ONE field: meteo → Meteo (with map)
          system-file (make-file
                        "jon_shared_data_system.proto" "ser"
                        [(make-message "JonGuiDataSystem"
                                       [(make-field "meteo" 1 "TYPE_MESSAGE" ".ser.JonGuiDataMeteo")])])
          ;; Root with two subsystems, both reaching the bad Meteo
          root (make-file
                 "jon_shared_data.proto" "ser"
                 [(make-message "JonGUIState"
                                [(make-field "system" 13 "TYPE_MESSAGE" ".ser.JonGuiDataSystem")
                                 (make-field "meteo_internal" 14 "TYPE_MESSAGE" ".ser.JonGuiDataMeteo")])])
          descriptor (make-descriptor root system-file meteo)
          msg-index (#'bd/build-message-index descriptor)
          gui-state (get msg-index "ser.JonGUIState")
          fields (bd/extract-subsystem-fields gui-state)]
      (try
        (bd/validate-determinism! msg-index fields)
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :non-deterministic-field (:type data)))
            ;; System is sorted first (tag 13 < 14), fails there first.
            (is (= 13 (:field-number data)))
            (is (= ["system" "meteo" "tags"] (:path data)))))))))

;; --- Group E: Error paths (V2, V5, V4, V7 validator fixes) ---

(deftest has-map-field-unresolved-ref-throws-test
  (testing "Unresolved typeName throws :unresolved-type-reference (V2 fix)"
    (let [outer (make-message "Outer"
                              [(make-field "missing" 1 "TYPE_MESSAGE" ".ser.Missing")])
          idx {"ser.Outer" outer}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"unresolved type reference"
                            (bd/has-map-field? idx "ser.Outer" outer #{})))
      (try
        (bd/has-map-field? idx "ser.Outer" outer #{})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :unresolved-type-reference (:type data)))
            (is (= "ser.Missing" (:type-name data)))
            (is (= "missing" (:field-name data)))
            (is (= "ser.Outer" (:parent-fqn data)))))))))

(deftest has-map-field-null-type-name-throws-test
  (testing "TYPE_MESSAGE with literal null typeName throws :null-type-name (V5 fix)"
    ;; Note: JSON.write-str would emit `null` for nil. We simulate by building a
    ;; field map with a nil value for "typeName".
    (let [outer (make-message "Outer"
                              [{"name" "bad" "number" 1 "type" "TYPE_MESSAGE" "typeName" nil}])
          idx {"ser.Outer" outer}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (bd/has-map-field? idx "ser.Outer" outer #{})))
      (try
        (bd/has-map-field? idx "ser.Outer" outer #{})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :null-type-name (:type (ex-data e)))))))))

(deftest has-map-field-missing-type-name-key-throws-test
  (testing "TYPE_MESSAGE with MISSING typeName key throws :null-type-name (fail-closed)"
    (let [outer (make-message "Outer"
                              [{"name" "bad" "number" 1 "type" "TYPE_MESSAGE"}])
          idx {"ser.Outer" outer}]
      (try
        (bd/has-map-field? idx "ser.Outer" outer #{})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :null-type-name (:type (ex-data e)))))))))

(deftest has-map-field-type-group-throws-test
  (testing "TYPE_GROUP field at recursion throws :unsupported-type-group (V1 fix)"
    (let [outer (make-message "Outer"
                              [(make-field "legacy" 1 "TYPE_GROUP" ".ser.Legacy")])
          idx {"ser.Outer" outer}]
      (try
        (bd/has-map-field? idx "ser.Outer" outer #{})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :unsupported-type-group (:type data)))
            (is (= "legacy" (:field-name data)))
            (is (= "ser.Outer" (:parent-fqn data)))))))))

(deftest has-map-field-unknown-type-throws-test
  (testing "Unknown field type at recursion throws :unknown-field-type (V6 fix)"
    (let [outer (make-message "Outer"
                              [(make-field "weird" 1 "TYPE_WIBBLE" ".ser.Wibble")])
          idx {"ser.Outer" outer}]
      (try
        (bd/has-map-field? idx "ser.Outer" outer #{})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)]
            (is (= :unknown-field-type (:type data)))
            (is (= "TYPE_WIBBLE" (:type-value data)))
            (is (= "weird" (:field-name data)))))))))

(deftest has-map-field-all-scalars-whitelist-test
  (testing "All known scalar types pass without recursion"
    (doseq [scalar ["TYPE_DOUBLE" "TYPE_FLOAT"
                    "TYPE_INT64" "TYPE_UINT64" "TYPE_INT32" "TYPE_UINT32"
                    "TYPE_FIXED64" "TYPE_FIXED32" "TYPE_SFIXED32" "TYPE_SFIXED64"
                    "TYPE_SINT32" "TYPE_SINT64"
                    "TYPE_BOOL" "TYPE_STRING" "TYPE_BYTES" "TYPE_ENUM"]]
      (let [msg (make-message "M" [(make-field "f" 1 scalar)])
            idx {"ser.M" msg}]
        (is (nil? (bd/has-map-field? idx "ser.M" msg #{}))
            (str scalar " should be whitelisted"))))))

(deftest generate!-missing-jongui-state-test
  (testing "Descriptor without ser.JonGUIState throws :missing-message"
    (let [descriptor (make-descriptor minimal-subsystems minimal-gps)
          desc-path (write-temp-descriptor! descriptor)
          out-file (java.io.File/createTempFile "out" ".ts")]
      (.deleteOnExit out-file)
      (try
        (bd/generate! desc-path (.getPath out-file))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :missing-message (:type (ex-data e))))
          (is (str/includes? (ex-message e) "JonGUIState")))))))

(deftest generate!-malformed-json-throws-test
  (testing "Malformed JSON descriptor throws :descriptor-parse-error (V7 fix)"
    (let [bad-file (java.io.File/createTempFile "bad" ".json")]
      (.deleteOnExit bad-file)
      (spit bad-file "this is not valid json {]")
      (let [out-file (java.io.File/createTempFile "out" ".ts")]
        (.deleteOnExit out-file)
        (try
          (bd/generate! (.getPath bad-file) (.getPath out-file))
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :descriptor-parse-error (:type (ex-data e))))))))))

(deftest generate!-nonexistent-file-throws-test
  (testing "Nonexistent descriptor file throws :descriptor-parse-error"
    (let [out-file (java.io.File/createTempFile "out" ".ts")]
      (.deleteOnExit out-file)
      (try
        (bd/generate! "/tmp/nonexistent-descriptor-xyz.json" (.getPath out-file))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :descriptor-parse-error (:type (ex-data e)))))))))

;; --- Group F: FQN-keyed visited set ---

(deftest has-map-field-fqn-visited-no-shadow-test
  (testing "Two distinct types with same short name 'Leaf' in different scopes are tracked independently"
    ;; Scenario: Outer has two fields pointing to two distinct nested Leaf types.
    ;; Foo.Leaf is clean. Bar.Leaf has a map. The validator must detect the map
    ;; in Bar.Leaf regardless of whether Foo.Leaf was processed first.
    (let [map-entry (make-map-entry "MEntry" "TYPE_STRING" "TYPE_STRING")
          ;; Two distinct nested Leaf types in different scopes
          foo-leaf (make-message "Leaf" [(make-field "v" 1 "TYPE_DOUBLE")])
          bar-leaf (make-message "Leaf"
                                 [(make-map-field "ser.Outer.Bar.Leaf" "MEntry" "m" 1)]
                                 :nested-types [map-entry])
          foo (make-message "Foo"
                            [(make-field "l" 1 "TYPE_MESSAGE" ".ser.Outer.Foo.Leaf")]
                            :nested-types [foo-leaf])
          bar (make-message "Bar"
                            [(make-field "l" 1 "TYPE_MESSAGE" ".ser.Outer.Bar.Leaf")]
                            :nested-types [bar-leaf])
          outer (make-message "Outer"
                              [(make-field "a" 1 "TYPE_MESSAGE" ".ser.Outer.Foo")
                               (make-field "b" 2 "TYPE_MESSAGE" ".ser.Outer.Bar")]
                              :nested-types [foo bar])
          descriptor (make-descriptor (make-file "t.proto" "ser" [outer]))
          msg-index (#'bd/build-message-index descriptor)
          outer-msg (get msg-index "ser.Outer")]
      ;; Sanity check: the index has distinct FQNs for the two Leaf types
      (is (contains? msg-index "ser.Outer.Foo.Leaf"))
      (is (contains? msg-index "ser.Outer.Bar.Leaf"))
      ;; The map is detected via the "b" field path, not hidden by Foo.Leaf's prior visit
      (is (= ["b" "l" "m"] (bd/has-map-field? msg-index "ser.Outer" outer-msg #{}))))))

(deftest has-map-field-fqn-cycle-protection-regression-test
  (testing "Mutual cycle A→B→A still terminates with FQN keying (regression for V3)"
    (let [a (make-message "A" [(make-field "b" 1 "TYPE_MESSAGE" ".ser.B")])
          b (make-message "B" [(make-field "a" 1 "TYPE_MESSAGE" ".ser.A")])
          idx {"ser.A" a "ser.B" b}]
      (is (nil? (bd/has-map-field? idx "ser.A" a #{}))))))

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

;; --- Group G: Structural validity of generated TS ---

(defn- parse-ts-entries
  "Parse the STATE_SUBSYSTEM_TAGS body from generated TS and return [[key num] ...]."
  [ts]
  (let [body-re #"(?s)STATE_SUBSYSTEM_TAGS\s*=\s*\{(.*?)\}\s*as\s+const"
        body-match (re-find body-re ts)
        body (when body-match (second body-match))]
    (when body
      (->> (str/split-lines body)
           (keep (fn [line]
                   (when-let [m (re-find #"^\s*([a-zA-Z][a-zA-Z0-9]*)\s*:\s*(\d+)\s*,?\s*$" line)]
                     [(nth m 1) (Integer/parseInt (nth m 2))])))))))

(deftest generate-typescript-structure-test
  (testing "Generated TS has a parseable object literal with unique integer values"
    (let [fields [{:name "system" :number 13 :type-name "ser.A"}
                  {:name "camera_day" :number 20 :type-name "ser.B"}
                  {:name "heater" :number 29 :type-name "ser.C"}]
          ts (bd/generate-typescript fields)
          entries (parse-ts-entries ts)]
      (is (= 3 (count entries)))
      (is (= ["system" "cameraDay" "heater"] (mapv first entries)))
      (is (= [13 20 29] (mapv second entries)))
      ;; Keys unique
      (is (= (count entries) (count (distinct (map first entries)))))
      ;; Values all positive
      (is (every? pos? (map second entries)))
      ;; STATE_SUBSYSTEM_TAG_SET block appears after the const close
      (let [const-end (.indexOf ^String ts "} as const;")
            set-start (.indexOf ^String ts "STATE_SUBSYSTEM_TAG_SET")]
        (is (> const-end 0))
        (is (> set-start const-end)
            "TAG_SET must come after the const literal")))))

(deftest generate-typescript-camel-roundtrip-test
  (testing "All 16 production subsystem names round-trip through snake->camel"
    (let [production-names ["system" "meteo_internal" "lrf" "time"
                            "gps" "compass" "rotary"
                            "camera_day" "camera_heat" "compass_calibration"
                            "rec_osd" "actual_space_time" "power" "cv" "pmu" "heater"]
          fields (mapv #(hash-map :name %1 :number (+ 13 %2) :type-name "ser.X")
                       production-names
                       (range (count production-names)))
          ts (bd/generate-typescript fields)
          entries (parse-ts-entries ts)
          expected-keys (mapv manifest/snake->camel production-names)]
      (is (= expected-keys (mapv first entries))))))

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

;; --- Group H: Closed-loop scanner/generator coherence ---

(deftest scanner-wire-tag-coherence-test
  (testing "Generated TS values are all length-delimited wire tag 2"
    (let [fields [{:name "system" :number 13 :type-name "ser.A"}
                  {:name "gps" :number 17 :type-name "ser.B"}
                  {:name "camera_day" :number 20 :type-name "ser.C"}
                  {:name "heater" :number 29 :type-name "ser.D"}]
          ts (bd/generate-typescript fields)
          entries (parse-ts-entries ts)
          numbers (mapv second entries)
          wire-tags (mapv #(bit-or (bit-shift-left % 3) 2) numbers)]
      (is (= [13 17 20 29] numbers))
      (is (= [106 138 162 234] wire-tags))
      ;; All wire tags distinct
      (is (= (count wire-tags) (count (distinct wire-tags))))
      ;; All wire tags have wire type 2 (length-delimited)
      (is (every? #(= 2 (bit-and % 7)) wire-tags)))))

(deftest generate!-renumbered-fields-coherence-test
  (testing "Swapping subsystem numbers still produces coherent output"
    (let [swapped-root (make-file
                         "jon_shared_data.proto" "ser"
                         [(make-message "JonGUIState"
                                        [(make-field "heater" 13 "TYPE_MESSAGE" ".ser.Heater")
                                         (make-field "system" 31 "TYPE_MESSAGE" ".ser.System")])])
          heater-file (make-file "heater.proto" "ser"
                                 [(make-message "Heater"
                                                [(make-field "t" 1 "TYPE_FLOAT")])])
          system-file (make-file "system.proto" "ser"
                                 [(make-message "System"
                                                [(make-field "cpu" 1 "TYPE_DOUBLE")])])
          descriptor (make-descriptor swapped-root heater-file system-file)
          desc-path (write-temp-descriptor! descriptor)
          out-file (java.io.File/createTempFile "swapped" ".ts")]
      (.deleteOnExit out-file)
      (bd/generate! desc-path (.getPath out-file))
      (let [ts (slurp out-file)
            entries (parse-ts-entries ts)]
        (is (= [["heater" 13] ["system" 31]] entries))))))

;; ============================================================================
;; build-message-index direct coverage (Group J)
;; ============================================================================

(deftest build-message-index-deep-nesting-test
  (testing "3-level nested types indexed by full FQN"
    (let [c (make-message "C" [(make-field "x" 1 "TYPE_DOUBLE")])
          b (make-message "B" [] :nested-types [c])
          a (make-message "A" [] :nested-types [b])
          descriptor (make-descriptor (make-file "t.proto" "ser" [a]))
          idx (#'bd/build-message-index descriptor)]
      (is (contains? idx "ser.A"))
      (is (contains? idx "ser.A.B"))
      (is (contains? idx "ser.A.B.C")))))

(deftest build-message-index-empty-package-test
  (testing "File with no package key defaults to empty string, FQN starts with '.'"
    (let [foo (make-message "Foo" [(make-field "x" 1 "TYPE_DOUBLE")])
          ;; File with no "package" key
          file-no-pkg {"name" "t.proto" "messageType" [foo]}
          descriptor {"file" [file-no-pkg]}
          idx (#'bd/build-message-index descriptor)]
      (is (contains? idx ".Foo")))))

;; ============================================================================
;; Integration Test (uses real descriptor if available)
;; ============================================================================

(def ^:private real-descriptor-path
  "../../../output/json-descriptors/descriptor-set.json")

(deftest integration-real-descriptor-test
  (when (.exists (io/file real-descriptor-path))
    (testing "Real descriptor-set.json produces correct subsystem count"
      (let [descriptor (json/read-str (slurp (io/file real-descriptor-path)))
            msg-index (#'bd/build-message-index descriptor)
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

;; --- Group K: Regression snapshot — production schema still passes ---

(deftest integration-real-descriptor-whitelist-passes-test
  (testing "Tightened whitelist validator does NOT reject current production schema"
    (when (.exists (io/file real-descriptor-path))
      (let [out-file (java.io.File/createTempFile "regression" ".ts")]
        (.deleteOnExit out-file)
        ;; Full generate! must succeed (no V1-V7 rejections on real schema)
        (let [result (bd/generate! real-descriptor-path (.getPath out-file))]
          (is (= 16 (count (:fields result))))
          ;; Assert all expected production subsystem names are present
          (let [names (set (map :name (:fields result)))]
            (is (contains? names "system"))
            (is (contains? names "gps"))
            (is (contains? names "cv"))
            (is (contains? names "heater"))))))))

;; ============================================================================
;; SMOKE TESTS — minimal happy-path validation of each entry point.
;; Fast checks that "nothing is catastrophically broken". Must run green in <1s.
;; ============================================================================

(deftest smoke-generate!-minimal-descriptor-test
  (testing "smoke: generate! produces a valid TS file from a minimal descriptor"
    (let [descriptor (make-descriptor minimal-gui-state minimal-subsystems minimal-gps)
          desc-path (write-temp-descriptor! descriptor)
          out-file (java.io.File/createTempFile "smoke" ".ts")]
      (.deleteOnExit out-file)
      (let [result (bd/generate! desc-path (.getPath out-file))]
        ;; Result has the expected shape
        (is (map? result))
        (is (vector? (:fields result)))
        (is (= 2 (count (:fields result))))
        ;; Output file was created and is non-empty
        (is (.exists out-file))
        (is (pos? (.length out-file)))
        ;; Output content is recognizable as the generated TS literal
        (let [content (slurp out-file)]
          (is (str/includes? content "STATE_SUBSYSTEM_TAGS"))
          (is (str/includes? content "system: 13,"))
          (is (str/includes? content "gps: 17,"))
          (is (str/includes? content "STATE_SUBSYSTEM_TAG_SET")))))))

(deftest smoke-extract-subsystem-fields-test
  (testing "smoke: extract-subsystem-fields on the real descriptor returns 16 entries"
    (when (.exists (io/file real-descriptor-path))
      (let [descriptor (json/read-str (slurp (io/file real-descriptor-path)))
            msg-index (#'bd/build-message-index descriptor)
            gui-state (get msg-index "ser.JonGUIState")
            fields (bd/extract-subsystem-fields gui-state)]
        (is (= 16 (count fields)))
        (is (every? :name fields))
        (is (every? :number fields))
        (is (every? :type-name fields))))))

(deftest smoke-has-map-field-clean-scalar-test
  (testing "smoke: has-map-field? returns nil for a trivial scalar-only message"
    (let [m (make-message "Smoke"
                          [(make-field "a" 1 "TYPE_DOUBLE")
                           (make-field "b" 2 "TYPE_STRING")
                           (make-field "c" 3 "TYPE_BOOL")
                           (make-field "d" 4 "TYPE_ENUM")])]
      (is (nil? (bd/has-map-field? {"ser.Smoke" m} "ser.Smoke" m #{}))))))

(deftest smoke-generate-typescript-non-empty-test
  (testing "smoke: generate-typescript produces the expected scaffolding even for empty input"
    (let [ts (bd/generate-typescript [])]
      (is (string? ts))
      (is (str/includes? ts "AUTO-GENERATED"))
      (is (str/includes? ts "STATE_SUBSYSTEM_TAGS"))
      (is (str/includes? ts "} as const;"))
      (is (str/includes? ts "STATE_SUBSYSTEM_TAG_SET")))))

;; ============================================================================
;; COVERAGE FAILURE DETECTION TESTS
;;
;; These tests explicitly guard against incomplete validator/generator behavior.
;; If a new proto TYPE is added, a new subsystem is added, or the production
;; descriptor drifts from expectations, one of these tests breaks — forcing the
;; developer to understand and approve the change rather than silently shipping
;; broken dedup semantics.
;; ============================================================================

(deftest coverage-safe-scalar-whitelist-is-complete-test
  (testing "Whitelist covers ALL proto3 scalar types — no silent drops"
    ;; The whitelist must include every deterministic scalar the proto spec
    ;; supports. If a new TYPE_* is added upstream and not mirrored here,
    ;; `has-map-field?` would throw :unknown-field-type — which is the
    ;; fail-closed desired behavior. This test documents the exact set so
    ;; a change of intent is visible.
    (let [expected-scalars #{"TYPE_DOUBLE" "TYPE_FLOAT"
                             "TYPE_INT64" "TYPE_UINT64"
                             "TYPE_INT32" "TYPE_UINT32"
                             "TYPE_FIXED64" "TYPE_FIXED32"
                             "TYPE_SFIXED32" "TYPE_SFIXED64"
                             "TYPE_SINT32" "TYPE_SINT64"
                             "TYPE_BOOL" "TYPE_STRING" "TYPE_BYTES" "TYPE_ENUM"}
          actual @(var bd/safe-scalar-types)]
      (is (= expected-scalars actual)
          "safe-scalar-types must match the expected proto3 scalar set exactly.
           If a new type was intentionally added, update this test with justification.
           If a type was removed, ensure recursion still handles it via :unknown-field-type throw."))))

(deftest coverage-real-descriptor-subsystem-count-test
  (testing "Real descriptor has exactly the expected production subsystem count"
    (when (.exists (io/file real-descriptor-path))
      (let [out-file (java.io.File/createTempFile "coverage" ".ts")]
        (.deleteOnExit out-file)
        (let [result (bd/generate! real-descriptor-path (.getPath out-file))
              ts (slurp out-file)
              parsed (parse-ts-entries ts)]
          ;; Expected count is a snapshot — if subsystems are added/removed,
          ;; this test breaks and forces explicit acknowledgement.
          (is (= 16 (count (:fields result)))
              "Production descriptor subsystem count changed. If intentional,
               update expected count here AND verify generated TS matches.")
          ;; Parsed TS must have the same count as the generator said
          (is (= (count (:fields result)) (count parsed))
              "Generated TS literal entries must match generator output count.
               A mismatch indicates the generator/parser disagree on structure."))))))

(deftest coverage-real-descriptor-all-subsystems-mapped-test
  (testing "Every subsystem in the descriptor has a corresponding TS entry with matching camelCase key"
    (when (.exists (io/file real-descriptor-path))
      (let [out-file (java.io.File/createTempFile "coverage" ".ts")]
        (.deleteOnExit out-file)
        (let [result (bd/generate! real-descriptor-path (.getPath out-file))
              ts (slurp out-file)
              parsed-map (into {} (parse-ts-entries ts))]
          (doseq [{:keys [name number]} (:fields result)]
            (let [camel (manifest/snake->camel name)]
              (is (contains? parsed-map camel)
                  (str "Subsystem '" name "' (camel: " camel ") missing from generated TS"))
              (is (= number (get parsed-map camel))
                  (str "Subsystem '" name "' field number mismatch: descriptor="
                       number " ts=" (get parsed-map camel))))))))))

(deftest coverage-all-subsystem-wire-tags-are-length-delimited-test
  (testing "Every subsystem field number must produce a wire-type-2 tag"
    (when (.exists (io/file real-descriptor-path))
      (let [out-file (java.io.File/createTempFile "coverage" ".ts")]
        (.deleteOnExit out-file)
        (let [result (bd/generate! real-descriptor-path (.getPath out-file))]
          (doseq [{:keys [name number]} (:fields result)]
            (let [wire-tag (bit-or (bit-shift-left number 3) 2)]
              ;; Wire tag must be positive (no varint overflow)
              (is (pos? wire-tag)
                  (str "Subsystem '" name "' wire tag overflowed: " wire-tag))
              ;; Wire type must be 2 (length-delimited)
              (is (= 2 (bit-and wire-tag 7))
                  (str "Subsystem '" name "' wire type is not 2: " (bit-and wire-tag 7)))
              ;; Field number must fit in a single-byte varint (shifted) for our schema —
              ;; sanity check that we're not using absurdly large field numbers
              (is (< number (bit-shift-left 1 14))
                  (str "Subsystem '" name "' field number " number
                       " is unusually large; scanner varint handling should still be correct"
                       " but this warrants explicit review.")))))))))

(deftest coverage-validator-rejects-every-unsafe-pattern-test
  (testing "Validator fail-closed behavior: every unsafe pattern throws with the right ex-info :type"
    ;; This test enumerates every fail-closed rejection path. If a future change
    ;; silently weakens one of them, this test breaks.
    (let [cases
          [;; TYPE_GROUP at subsystem level
           {:label "TYPE_GROUP at extraction"
            :expected :unsupported-type-group
            :fn #(bd/extract-subsystem-fields
                   (make-message "JonGUIState"
                                 [(make-field "g" 13 "TYPE_GROUP" ".ser.G")]))}
           ;; TYPE_GROUP at recursion (via has-map-field?)
           {:label "TYPE_GROUP at recursion"
            :expected :unsupported-type-group
            :fn #(let [m (make-message "M"
                                       [(make-field "g" 1 "TYPE_GROUP" ".ser.G")])]
                    (bd/has-map-field? {"ser.M" m} "ser.M" m #{}))}
           ;; Unresolved type reference
           {:label "Unresolved type reference"
            :expected :unresolved-type-reference
            :fn #(let [m (make-message "M"
                                       [(make-field "f" 1 "TYPE_MESSAGE" ".ser.Missing")])]
                    (bd/has-map-field? {"ser.M" m} "ser.M" m #{}))}
           ;; Null typeName
           {:label "Null typeName"
            :expected :null-type-name
            :fn #(let [m (make-message "M"
                                       [{"name" "f" "number" 1 "type" "TYPE_MESSAGE" "typeName" nil}])]
                    (bd/has-map-field? {"ser.M" m} "ser.M" m #{}))}
           ;; Unknown field type at extraction
           {:label "Unknown type at extraction"
            :expected :unknown-field-type
            :fn #(bd/extract-subsystem-fields
                   (make-message "JonGUIState"
                                 [(make-field "w" 13 "TYPE_WIBBLE")]))}
           ;; Unknown field type at recursion
           {:label "Unknown type at recursion"
            :expected :unknown-field-type
            :fn #(let [m (make-message "M"
                                       [(make-field "w" 1 "TYPE_WIBBLE")])]
                    (bd/has-map-field? {"ser.M" m} "ser.M" m #{}))}
           ;; Duplicate field numbers
           {:label "Duplicate field numbers"
            :expected :duplicate-field-number
            :fn #(bd/extract-subsystem-fields
                   (make-message "JonGUIState"
                                 [(make-field "a" 13 "TYPE_MESSAGE" ".ser.A")
                                  (make-field "b" 13 "TYPE_MESSAGE" ".ser.B")]))}]]
      (doseq [{:keys [label expected fn]} cases]
        (try
          (fn)
          (is false (str "Case '" label "' should have thrown but didn't"))
          (catch clojure.lang.ExceptionInfo e
            (is (= expected (:type (ex-data e)))
                (str "Case '" label "' threw with wrong :type: " (:type (ex-data e))))))))))
