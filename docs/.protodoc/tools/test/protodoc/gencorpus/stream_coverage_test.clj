(ns protodoc.gencorpus.stream-coverage-test
  "CLASSIFICATION COMPLETENESS for the stream profiles: every `cmd.Root` payload
   branch is either EXERCISED by at least one registered profile phase, or
   classified in `uncovered-payload-branches` with a one-line reason.

   A NEW payload branch added to the proto fails this test until it is either:
   (a) added to a profile's phases (preferred — keep profiles realistic), or
   (b) classified in `uncovered-payload-branches` with a reason.

   The ratchet is BIDIRECTIONAL: staleness is asserted in both directions so
   neither half can quietly drift — if a classified-uncovered branch is secretly
   covered by a profile, that entry must be removed from the classification.

   The `cmd.Root` payload branch list is derived from the LIVE descriptor pool
   (never hardcoded), so proto evolution is the trigger for classification work,
   not a manual counter update.

   Hermetic: reads only the committed descriptor-set.binpb + proto-db.edn."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.pool :as pool]
            [protodoc.gencorpus.stream :as stream])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$FieldDescriptor
            Descriptors$OneofDescriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private pool* (delay (pool/load-pool binpb)))

;; ── live payload branch enumeration ──────────────────────────────────────────

(defn- payload-branches
  "The `payload` oneof field names of `cmd.Root` from the LIVE descriptor pool."
  [pool]
  (let [^Descriptors$Descriptor d (get pool "cmd.Root")]
    (when-not d (throw (ex-info "cmd.Root not found in descriptor pool" {})))
    (let [payload-oo (some #(when (= "payload"
                                     (Descriptors$OneofDescriptor/.getName
                                      ^Descriptors$OneofDescriptor %))
                              %)
                           (Descriptors$Descriptor/.getOneofs d))]
      (when-not payload-oo
        (throw (ex-info "cmd.Root has no 'payload' oneof" {})))
      (set (map #(Descriptors$FieldDescriptor/.getName ^Descriptors$FieldDescriptor %)
                (Descriptors$OneofDescriptor/.getFields ^Descriptors$OneofDescriptor payload-oo))))))

;; ── classification: uncovered payload branches ────────────────────────────────
;;
;; A payload branch that no registered profile exercises, with a one-line reason.
;; Format: branch-name → reason string.
;;
;; Current exercised set (from stream/covered-payload-branches):
;;   ping, noop (ping/pause phases in every profile)
;;   rotary     (pan-tilt-drag: axis/relative; patrol: set_platform_azimuth,
;;               set_platform_elevation)
;;   day_camera (zoom-ramp: set_iris; patrol: set_iris)
;;   heat_camera (patrol: set_dde_level)
;;
;; Everything else is classified below with a reason explaining why adding it to
;; a profile is not currently warranted.

(def ^:private uncovered-payload-branches
  "Proof-carrying classification: branch-name → reason.
   Each entry here asserts the branch is INTENTIONALLY absent from all profiles."
  {"gps"      "sensor/lifecycle: GPS is started by the system; operator sessions do not send GPS commands in a realistic synthesized stream"
   "compass"  "sensor/lifecycle: compass calibration and offset commands are rare setup operations outside normal operator sessions"
   "lrf"      "operator/rare: LRF measure and scan commands are triggered by a specific operator action, not part of a continuous sweep profile"
   "lrf_calib" "admin/rare: LRF calibration is a factory/maintenance procedure, not part of a routine operator session"
   "osd"      "display/control: OSD show/hide commands are event-driven UI actions, not continuous stream events suited to a sweep profile"
   "frozen"   "lifecycle/rare: frozen payload is a connection-lifecycle sentinel; it does not represent a regular command"
   "system"   "lifecycle/rare: system-level commands (reboot, power-off, recording) are rare operator actions, not continuous sweep candidates"
   "cv"       "feature/optional: computer-vision mode commands are conditional on CV subsystem availability; not part of a baseline patrol profile"
   "lira"     "feature/optional: lira refine_target is a single-shot optional enhancement command"
   "power"    "admin/rare: power channel control is a setup/maintenance command, not a continuous operator session action"
   "pmu"      "admin/rare: PMU control commands are system-level power management, not part of a routine operator session"
   "heater"   "admin/rare: heater start/stop/control is a system-level environmental command, not part of a continuous operator sweep"})

;; ── tests ─────────────────────────────────────────────────────────────────────

(deftest every-payload-branch-is-exercised-or-classified
  (testing "every cmd.Root payload branch is either exercised by a profile or classified
            in uncovered-payload-branches — a NEW branch fails until classified"
    (let [all-branches (payload-branches @pool*)
          exercised (stream/covered-payload-branches)
          classified (set (keys uncovered-payload-branches))
          accounted-for (set/union exercised classified)
          unaccounted (set/difference all-branches accounted-for)]
      (is (seq all-branches)
          "cmd.Root must have at least one payload branch (pool health check)")
      (is (empty? unaccounted)
          (str (count unaccounted) " payload branch(es) are neither exercised by any "
               "profile NOR classified in uncovered-payload-branches — add to a profile "
               "or classify with a reason: " (vec (sort unaccounted)))))))

(deftest no-classified-uncovered-branch-is-secretly-exercised
  (testing "no classified-uncovered branch is also exercised by a profile (staleness
            in the OTHER direction: a profile was updated to cover the branch but the
            classification was not trimmed)"
    (let [exercised (stream/covered-payload-branches)
          classified (set (keys uncovered-payload-branches))
          secretly-exercised (set/intersection classified exercised)]
      (is (empty? secretly-exercised)
          (str "these branches are classified uncovered but ARE exercised by a profile — "
               "trim uncovered-payload-branches: " (vec (sort secretly-exercised)))))))

(deftest covered-payload-branches-is-a-subset-of-all-branches
  (testing "stream/covered-payload-branches only names branches that exist in the
            live descriptor (no phantom branch names)"
    (let [all-branches (payload-branches @pool*)
          exercised (stream/covered-payload-branches)
          phantom (set/difference exercised all-branches)]
      (is (empty? phantom)
          (str "covered-payload-branches contains names that are NOT in cmd.Root's "
               "payload oneof — update profiles or covered-payload-branches: "
               (vec (sort phantom)))))))

(deftest uncovered-classification-names-live-branches
  (testing "every branch in uncovered-payload-branches exists in the live descriptor
            (no stale names after a proto branch rename or removal)"
    (let [all-branches (payload-branches @pool*)
          stale (filter #(not (contains? all-branches %)) (keys uncovered-payload-branches))]
      (is (empty? stale)
          (str "uncovered-payload-branches contains names not in cmd.Root's payload "
               "oneof — trim the classification: " (vec (sort stale)))))))

(deftest coverage-completeness-floor
  (testing "the live descriptor has the expected payload branch count (regression
            floor — detects a collapsed pool or descriptor mismatch)"
    (let [all-branches (payload-branches @pool*)]
      (is (<= 15 (count all-branches))
          (str "expected >= 15 cmd.Root payload branches; got "
               (count all-branches) ": " (vec (sort all-branches)))))))
