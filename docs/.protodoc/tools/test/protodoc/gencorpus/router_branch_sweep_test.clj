(ns protodoc.gencorpus.router-branch-sweep-test
  "BRANCH-COVERAGE: every branch of every router's required oneof is reachable
   and produces an oracle-valid message.

   A ROUTER is a message that either ends in `.Root` OR has a oneof named `cmd`
   (same predicate `parity-test` uses). For each such message and each branch of
   its required oneof, we:

   1. Bind `assemble/*pins*` to select that branch (at the router's own level).
   2. Call `assemble/generate` + `pool/build-message` + `oracle/valid?`.
   3. Assert VALID — or record the pair in the proof-carrying `no-valid-branch`
      allowlist (each entry carries a reason string).

   This automatically catches:
   - A NEW proto branch that cannot compose validly.
   - A branch the pin-filter machinery cannot reach (collision in the shared
     `\"cmd\"` oneof name across nested routers).

   PIN-COLLISION HANDLING: four branches produce a collision when pinned with a
   simple `{oneof-name #{branch-name}}` map because the branch's sub-message
   itself carries a `\"cmd\"` oneof, and `*pins*` propagates to ALL nested oneofs
   by name. The fix is a UNION pin that adds the sub-level branch names alongside
   the parent branch name, so both oneof levels resolve without contradiction.
   Each collision case is covered by a custom entry in `branch-pin-overrides`.
   The correctness of this approach is verified by the probes that discovered it:
   without the union the generator throws `gen-corpus --pin selects no available
   oneof branch`; with the union it passes the oracle.

   Hermetic + deterministic: reads only the committed descriptor-set.binpb +
   proto-db.edn; every generate call uses a fixed seed; no disk writes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$FieldDescriptor
            Descriptors$OneofDescriptor]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")
(def ^:private db-path "../proto-db.edn")
(def ^:private pool* (delay (pool/load-pool binpb)))
(def ^:private db* (delay (constraints/effective-db @pool* binpb db-path)))

;; ── router identification (mirrors parity_test.clj) ──────────────────────────

(defn- oneof-names [^Descriptors$Descriptor d]
  (set (map #(Descriptors$OneofDescriptor/.getName ^Descriptors$OneofDescriptor %)
            (Descriptors$Descriptor/.getOneofs d))))

(defn- router?
  "A routing container — ends in `.Root` OR has a oneof named `cmd`."
  [full-name ^Descriptors$Descriptor d]
  (or (str/ends-with? full-name ".Root")
      (contains? (oneof-names d) "cmd")))

(defn- primary-oneof-name
  "The oneof to sweep for a router: `cmd` when present, otherwise `channel`
   (cmd.Lrf_calib.Root), otherwise the first oneof by descriptor order."
  [^Descriptors$Descriptor d]
  (let [oos (Descriptors$Descriptor/.getOneofs d)
        names (map #(Descriptors$OneofDescriptor/.getName ^Descriptors$OneofDescriptor %) oos)]
    (or (some #{"cmd"} names)
        (some #{"channel"} names)
        (first names))))

(defn- oneof-branches
  "The field names in the named oneof of descriptor `d`."
  [^Descriptors$Descriptor d ^String oneof-name]
  (when-let [oo (some #(when (= oneof-name
                               (Descriptors$OneofDescriptor/.getName ^Descriptors$OneofDescriptor %))
                          %)
                      (Descriptors$Descriptor/.getOneofs d))]
    (mapv #(Descriptors$FieldDescriptor/.getName ^Descriptors$FieldDescriptor %)
          (Descriptors$OneofDescriptor/.getFields ^Descriptors$OneofDescriptor oo))))

;; ── pin-collision overrides ───────────────────────────────────────────────────
;;
;; Some router branches contain a sub-message that itself has a `"cmd"` oneof.
;; Because `assemble/*pins*` propagates to ALL nested `"cmd"` oneofs by name, a
;; simple `{"cmd" #{branch-name}}` constrains the sub-level oneof to a set that
;; has no match there, throwing. The fix is a UNION pin: include the sub-level
;; branch names in the same `"cmd"` set.
;;
;; Format: [router-full-name oneof-name branch-name] → pins-map.

(def ^:private branch-pin-overrides
  {;; cmd.DayCamera.Root cmd=focus: the "focus" branch carries a cmd.DayCamera.Focus
   ;; sub-message whose own "cmd" oneof has {set_value move halt offset reset_focus
   ;; save_to_table_focus}. The union pin includes all sub-level names.
   ["cmd.DayCamera.Root" "cmd" "focus"]
   {"cmd" #{"focus" "set_value" "move" "halt" "offset" "reset_focus" "save_to_table_focus"}}

   ;; cmd.DayCamera.Root cmd=zoom: cmd.DayCamera.Zoom has "cmd" {set_value move halt
   ;; set_zoom_table_value next_zoom_table_pos prev_zoom_table_pos offset reset_zoom
   ;; save_to_table}.
   ["cmd.DayCamera.Root" "cmd" "zoom"]
   {"cmd" #{"zoom" "set_value" "move" "halt" "set_zoom_table_value"
            "next_zoom_table_pos" "prev_zoom_table_pos" "offset"
            "reset_zoom" "save_to_table"}}

   ;; cmd.HeatCamera.Root cmd=zoom: cmd.HeatCamera.Zoom has "cmd"
   ;; {set_zoom_table_value next_zoom_table_pos prev_zoom_table_pos}.
   ["cmd.HeatCamera.Root" "cmd" "zoom"]
   {"cmd" #{"zoom" "set_zoom_table_value" "next_zoom_table_pos" "prev_zoom_table_pos"}}

   ;; cmd.RotaryPlatform.Root cmd=axis: the "axis" branch carries
   ;; cmd.RotaryPlatform.Axis (no oneofs) whose azimuth + elevation fields are
   ;; cmd.RotaryPlatform.Azimuth / .Elevation — each has a "cmd" oneof with
   ;; {set_value rotate_to rotate relative relative_set halt}.
   ["cmd.RotaryPlatform.Root" "cmd" "axis"]
   {"cmd" #{"axis" "set_value" "rotate_to" "rotate" "relative" "relative_set" "halt"}}})

(defn- branch-pins
  "The `*pins*` map to use when sweeping `branch-name` of `oneof-name` on
   `router-name`. Consults `branch-pin-overrides` first; falls back to the simple
   `{oneof-name #{branch-name}}`."
  [router-name oneof-name branch-name]
  (or (get branch-pin-overrides [router-name oneof-name branch-name])
      {oneof-name #{branch-name}}))

;; ── proof-carrying no-valid-branch allowlist ──────────────────────────────────
;;
;; A (router, oneof, branch) triple that CANNOT produce an oracle-valid message at
;; the fixed seed. Each entry REQUIRES a reason string explaining why. An empty
;; map is the ideal — any new entry is a debt, not a convenience.

(def ^:private no-valid-branch
  "Proof-carrying allowlist: [router oneof branch] → reason.
   Empty: after union-pin resolution every discovered router branch generates a
   valid message at seed 1."
  {})

;; ── helpers ───────────────────────────────────────────────────────────────────

(defn- routers
  "All router messages in the pool, sorted for determinism."
  [pool]
  (sort-by key
           (into {}
                 (for [[nm ^Descriptors$Descriptor d] pool
                       :when (router? nm d)]
                   [nm d]))))

;; ── the sweep ─────────────────────────────────────────────────────────────────

(deftest every-router-branch-generates-valid
  (testing "every branch of every router's primary oneof is reachable and
            oracle-valid at seed 1 — or is allowlisted with a reason"
    (let [p @pool*
          db @db*
          failures (atom [])]
      (doseq [[nm ^Descriptors$Descriptor d] (routers p)
              :let [oo-name (primary-oneof-name d)
                    branches (when oo-name (oneof-branches d oo-name))]
              :when (seq branches)
              branch branches
              :let [key [nm oo-name branch]
                    allowlisted? (contains? no-valid-branch key)]
              :when (not allowlisted?)
              :let [pins (branch-pins nm oo-name branch)
                    result (try
                             (let [edn (binding [assemble/*pins* pins]
                                         (assemble/generate p db nm 1))
                                   msg (pool/build-message p nm edn)
                                   ok? (oracle/valid? msg)]
                               (when-not ok?
                                 {:router nm :oneof oo-name :branch branch
                                  :why :oracle-invalid
                                  :violations (oracle/violations msg)}))
                             (catch Throwable e
                               {:router nm :oneof oo-name :branch branch
                                :why :threw :error (.getMessage e)}))]
              :when result]
        (swap! failures conj result))

      (is (empty? @failures)
          (str (count @failures) " router branch(es) cannot produce an oracle-valid "
               "message — fix the generator or add to no-valid-branch with a reason "
               "(showing <=20):\n"
               (str/join "\n" (map pr-str (take 20 @failures)))))

      ;; staleness check: every allowlisted entry should still actually fail;
      ;; a passing entry means the allowlist has drifted and should be trimmed.
      (let [stale (for [[key _reason] no-valid-branch
                        :let [[nm _oo-name branch] key
                              oo-name (second key)
                              ^Descriptors$Descriptor d (get p nm)
                              pins (when d (branch-pins nm oo-name branch))
                              still-fails? (when d
                                             (try
                                               (not (oracle/valid?
                                                     (pool/build-message p nm
                                                      (binding [assemble/*pins* pins]
                                                        (assemble/generate p db nm 1)))))
                                               (catch Throwable _ true)))]
                        :when (false? still-fails?)]
                    key)]
        (is (empty? stale)
            (str "no-valid-branch contains stale entries whose branch NOW generates "
                 "a valid message — trim the allowlist: " (vec stale))))

      ;; coverage floor: the sweep must actually find routers, or the
      ;; router-detection predicate collapsed.
      (is (<= 15 (count (routers p)))
          (str "expected >= 15 router messages; got " (count (routers p)))))))

(deftest every-router-has-a-sweepable-oneof
  (testing "every router has at least one oneof (so the branch sweep is non-vacuous)"
    (doseq [[nm ^Descriptors$Descriptor d] (routers @pool*)]
      (is (seq (Descriptors$Descriptor/.getOneofs d))
          (str nm ": router has no oneofs — sweep would be vacuous")))))
