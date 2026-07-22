(ns protodoc.gencorpus.stream
  "Command-stream synthesizer — the operator-session sibling of gencorpus.

   Where `gencorpus` emits INDEPENDENT boundary/violating `cmd.Root` messages
   (one message per seed, no relation between them), this emits an ORDERED
   SEQUENCE of valid `cmd.Root` messages that read like a real operator session:
   pan/tilt sweeps, zoom ramps, keepalive pings, and pauses — each a wire `.bin`
   with a schedule delay, so a consumer can replay them at cadence (the native
   client's real 300 ms cmd keepalive being the reference beat).

   A PROFILE is an ordered vector of PHASE maps (pure data in this namespace).
   A phase is one of:
   - `:sweep`  — assemble a base `cmd.Root` with `:pins` bound for the phase's
                 payload branch, then walk a swept leaf field from one
                 constraint-clamped endpoint to the other across `:steps`,
                 interpolating `:linear` or `:sinusoidal`. Endpoints are FRACTIONS
                 of the field's LIVE constraint range (`:from-frac`/`:to-frac`),
                 so nothing is hardcoded beyond a sane fraction inside the range.
   - `:ping`   — `:count` keepalive `ping` payloads at `:every-ms` cadence.
   - `:pause`  — a single `noop` payload holding for `:ms` (a schedule gap with a
                 real, valid message, not a silent hole).

   Every emitted message carries monotonic, DETERMINISTIC envelope times derived
   from the cumulative schedule (never wall clock): `client_time_ms` is the
   running schedule position from 0; `state_time`/`frame_time_*` are fixed
   plausible offsets of it; `session_id` and `protocol_version` are fixed. Each
   message MUST pass the protovalidate oracle — a rejection is a generator bug and
   throws (never written).

   Output mirrors `gencorpus/write-corpus!`: a directory of
   `<safe-name>-stream-<profile>-<%04d>.bin` + a `stream-manifest.edn` whose
   entries carry the corpus contract keys PLUS `:profile :sequence-index
   :delay-ms` (`:kind :stream`).

   CLI: gen-stream --profile <name> --seed S --output <dir>
        --descriptor <binpb> --db-path <proto-db.edn> --list-profiles"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:private root-msg
  "The stream is a sequence of top-level command envelopes."
  "cmd.Root")

;; ── envelope (deterministic, schedule-derived — never wall clock) ─────────

(def ^:private protocol-version 1)

;; A plausible operator client identity (both enums are `not_in [0]`): a
;; local-network desktop-native session.
(def ^:private client-type 2)                            ; LOCAL_NETWORK
(def ^:private client-app 3)                             ; DESKTOP_NATIVE

;; Fixed plausible offsets (µs/ms) of the schedule clock, so the four timing
;; fields differ but track `client_time_ms` monotonically. state_time trails the
;; client's own clock slightly; the two frame times bracket it.
(def ^:private state-time-lag-ms 20)
(def ^:private frame-day-lag-ms 8)
(def ^:private frame-heat-lag-ms 12)

(defn- envelope-times
  "The monotonic envelope timing fields for a message at cumulative schedule
   position `t-ms` (a non-negative long, deterministic). Times are BigInt (the
   uint64 wire width) and never negative."
  [t-ms]
  (let [nn (fn [n] (bigint (max 0 n)))]
    {"client_time_ms" (nn t-ms)
     "state_time" (nn (- t-ms state-time-lag-ms))
     "frame_time_day" (nn (- t-ms frame-day-lag-ms))
     "frame_time_heat" (nn (- t-ms frame-heat-lag-ms))}))

(defn- session-id
  "A fixed per-run session id derived from the base seed (uint32 range)."
  [seed]
  (int (mod (+ 1000 (Math/abs (long seed))) 0xFFFF)))

;; ── interpolation (endpoints derived FROM live constraints) ───────────────

(def ^:private default-lo -1.0e6)
(def ^:private default-hi 1.0e6)

(defn field-range
  "The `[lo hi]` numeric range a swept field may take, read from the LIVE
   constraint db (`effective-db`) for `field-msg`/`field-name`. Inclusive bounds
   pass through; exclusive bounds are nudged one sane step inside so an endpoint
   never lands on a rejected value. A field with no declared bound falls back to a
   wide sane default (the sweep still stays well inside it via fractions)."
  [db field-msg field-name]
  (let [cs (get (assemble/db-field-constraints db field-msg) field-name)
        {:keys [gte gt lte lt]} cs
        lo (cond gte (double gte) gt (double gt) :else default-lo)
        hi (cond lte (double lte) lt (double lt) :else default-hi)]
    [lo hi]))

(defn- frac->value
  "The value at fraction `frac` (0..1) of the range `[lo hi]`, clamped inside it."
  [[lo hi] frac]
  (-> (+ lo (* frac (- hi lo)))
      (max lo)
      (min hi)))

(defn interpolate
  "The swept value at step `i` of `steps` (0-indexed) between `from`/`from-value`
   and `to`/`to-value`, by `:linear` or `:sinusoidal` easing. A single-step sweep
   yields the `to` endpoint. Sinusoidal uses a half-cosine ease so the endpoints
   are hit exactly and the middle moves fastest (a natural drag/ramp shape)."
  [interp from to i steps]
  (let [span (max 1 (dec steps))
        t (/ (double i) (double span))
        eased (case interp
                :linear t
                :sinusoidal (/ (- 1.0 (Math/cos (* Math/PI t))) 2.0))]
    (+ from (* eased (- to from)))))

;; ── one swept step → a valid cmd.Root EDN ─────────────────────────────────

(defn- step-seed
  "A deterministic per-step seed from the base seed, phase index and step index —
   the corpus's `seed + i` idiom, spread so phases never collide."
  [base-seed phase-idx step-idx]
  (+ (long base-seed) (* 100000 phase-idx) step-idx))

(defn- coerce-leaf
  "Coerce an interpolated double to the swept leaf's wire kind: an `:int` field
   (e.g. DDE level) takes a rounded int; everything else stays double."
  [leaf-kind v]
  (case leaf-kind
    :int (int (Math/round (double v)))
    (double v)))

(defn- build-root
  "Assemble a base `cmd.Root` under `pins`, overlay the swept `path`→`value` (when
   given) and the deterministic envelope, and return the EDN map. Throws (fail
   fast) if the resulting message is not oracle-valid — a violation is a generator
   bug, never written."
  [pool db pins path value t-ms seed]
  (let [base (binding [assemble/*pins* pins]
               (assemble/generate pool db root-msg seed))
        edn (cond-> (merge base
                           (envelope-times t-ms)
                           {"protocol_version" protocol-version
                            "session_id" (session-id seed)
                            "client_type" client-type
                            "client_app" client-app})
              path (assoc-in path value))
        msg (pool/build-message pool root-msg edn)]
    (when-not (oracle/valid? msg)
      (throw (ex-info "stream synthesized an oracle-INVALID cmd.Root (generator bug)"
                      {:pins pins :path path :value value
                       :violations (oracle/violations msg)})))
    edn))

;; ── jitter (deterministic, seeded — never a real RNG) ─────────────────────

(defn- jitter-ms
  "A small deterministic delay perturbation for step `i` under `seed`, in
   `[-max-jitter, +max-jitter]`. Derived arithmetically from the seed+index so a
   re-run reproduces the same schedule (the determinism contract)."
  [seed phase-idx i max-jitter]
  (if (pos? max-jitter)
    (let [h (mod (unchecked-multiply (unchecked-add (long seed)
                                                    (unchecked-add (* 31 phase-idx) (* 7 i)))
                                     2654435761)
                 (inc (* 2 max-jitter)))]
      (- h max-jitter))
    0))

;; ── phase expansion (phase map → seq of step maps) ────────────────────────

(defn- ping-steps
  "Expand a `:ping` phase into `:count` keepalive steps at `:every-ms` cadence."
  [pool db phase phase-idx base-seed t0]
  (let [{:keys [every-ms], cnt :count} phase]
    (loop [i 0, t t0, acc []]
      (if (= i cnt)
        {:steps acc :end-ms t}
        (let [seed (step-seed base-seed phase-idx i)
              edn (build-root pool db {"payload" #{"ping"}} nil nil t seed)]
          (recur (inc i) (+ t every-ms)
                 (conj acc {:edn-value edn :delay-ms every-ms :seed seed})))))))

(defn- pause-step
  "Expand a `:pause` phase into a single holding `noop` step of `:ms`."
  [pool db phase phase-idx base-seed t0]
  (let [{:keys [ms]} phase
        seed (step-seed base-seed phase-idx 0)
        edn (build-root pool db {"payload" #{"noop"}} nil nil t0 seed)]
    {:steps [{:edn-value edn :delay-ms ms :seed seed}] :end-ms (+ t0 ms)}))

(defn- sweep-steps
  "Expand a `:sweep` phase into `:steps` interpolated command steps. Endpoints are
   `:from-frac`/`:to-frac` of the swept field's LIVE constraint range; each step's
   value is clamped inside that range and coerced to the leaf's wire kind."
  [pool db phase phase-idx base-seed t0]
  (let [{:keys [pins path field-msg field-name leaf-kind interp steps
                from-frac to-frac delay-ms max-jitter]
         :or {interp :linear from-frac 0.0 to-frac 1.0 max-jitter 0}} phase
        rng (field-range db field-msg field-name)
        from (frac->value rng from-frac)
        to (frac->value rng to-frac)]
    (loop [i 0, t t0, acc []]
      (if (= i steps)
        {:steps acc :end-ms t}
        (let [seed (step-seed base-seed phase-idx i)
              raw (interpolate interp from to i steps)
              value (coerce-leaf leaf-kind raw)
              edn (build-root pool db pins path value t seed)
              d (+ delay-ms (jitter-ms base-seed phase-idx i max-jitter))]
          (recur (inc i) (+ t d)
                 (conj acc {:edn-value edn :delay-ms d :seed seed})))))))

(defn- expand-phase
  "Expand one phase map into `{:steps [...] :end-ms N}` starting at schedule
   position `t0`. Dispatches on `:kind`."
  [pool db phase phase-idx base-seed t0]
  (case (:kind phase)
    :ping (ping-steps pool db phase phase-idx base-seed t0)
    :pause (pause-step pool db phase phase-idx base-seed t0)
    :sweep (sweep-steps pool db phase phase-idx base-seed t0)
    (throw (ex-info "unknown stream phase :kind" {:phase phase}))))

(defn synthesize
  "Expand a full profile (a vector of phase maps) into an ORDERED seq of step maps
   `{:edn-value :delay-ms :seed}` — the cumulative schedule threads through the
   phases so `client_time_ms` is strictly monotonic across the whole session."
  [pool db profile base-seed]
  (loop [[phase & more] profile, phase-idx 0, t 0, acc []]
    (if-not phase
      acc
      (let [{:keys [steps end-ms]} (expand-phase pool db phase phase-idx base-seed t)]
        (recur more (inc phase-idx) (long end-ms) (into acc steps))))))

;; ── profile registry (pure data) ──────────────────────────────────────────
;;
;; Constraint-clamped sweep endpoints are FRACTIONS of the live range, so a range
;; change in the proto reshapes the sweep automatically (nothing hardcoded past a
;; sane fraction). Pins name the union of branches across a payload's nested "cmd"
;; oneofs on the path (both RotaryPlatform.Root and .Azimuth/.Elevation use the
;; oneof name "cmd"; the union filters correctly at each depth).

(def ^:private ping-phase
  "A short keepalive burst at the native client's real 300 ms cmd cadence."
  {:kind :ping :every-ms 300 :count 4})

(def profiles
  "The stream profile registry: name → ordered vector of phase maps."
  {:idle
   [{:kind :ping :every-ms 300 :count 16}]

   :pan-tilt-drag
   ;; A burst lane: an azimuth relative drag at ~16 ms steps (a mouse-drag
   ;; cadence), then a settle, then a halt-shaped noop.
   [{:kind :sweep :interp :sinusoidal :steps 24 :delay-ms 16 :max-jitter 3
     :pins {"payload" #{"rotary"} "cmd" #{"axis" "relative"}}
     :path ["rotary" "axis" "azimuth" "relative" "value"]
     :field-msg "cmd.RotaryPlatform.RotateAzimuthRelative" :field-name "value"
     :from-frac 0.45 :to-frac 0.62}
    {:kind :pause :ms 120}]

   :zoom-ramp
   ;; A day-camera iris ramp up, a pause, then an iris ramp back down — the
   ;; plausible "open aperture, hold, close" gesture.
   [{:kind :sweep :interp :linear :steps 20 :delay-ms 60 :max-jitter 5
     :pins {"payload" #{"day_camera"} "cmd" #{"set_iris"}}
     :path ["day_camera" "set_iris" "value"]
     :field-msg "cmd.DayCamera.SetIris" :field-name "value"
     :from-frac 0.1 :to-frac 0.9}
    {:kind :pause :ms 500}
    {:kind :sweep :interp :linear :steps 20 :delay-ms 60 :max-jitter 5
     :pins {"payload" #{"day_camera"} "cmd" #{"set_iris"}}
     :path ["day_camera" "set_iris" "value"]
     :field-msg "cmd.DayCamera.SetIris" :field-name "value"
     :from-frac 0.9 :to-frac 0.1}]

   :patrol
   ;; The realistic composite operator session: keepalives bracket an azimuth
   ;; rotate_to sweep, elevation adjustments, an iris ramp, and a heat DDE set,
   ;; with pauses between gestures.
   [ping-phase
    {:kind :sweep :interp :sinusoidal :steps 30 :delay-ms 40 :max-jitter 4
     :pins {"payload" #{"rotary"} "cmd" #{"set_platform_azimuth"}}
     :path ["rotary" "set_platform_azimuth" "value"]
     :field-msg "cmd.RotaryPlatform.SetPlatformAzimuth" :field-name "value"
     :from-frac 0.3 :to-frac 0.7}
    {:kind :pause :ms 300}
    {:kind :sweep :interp :linear :steps 16 :delay-ms 50 :max-jitter 4
     :pins {"payload" #{"rotary"} "cmd" #{"set_platform_elevation"}}
     :path ["rotary" "set_platform_elevation" "value"]
     :field-msg "cmd.RotaryPlatform.SetPlatformElevation" :field-name "value"
     :from-frac 0.45 :to-frac 0.6}
    ping-phase
    {:kind :sweep :interp :linear :steps 18 :delay-ms 55 :max-jitter 5
     :pins {"payload" #{"day_camera"} "cmd" #{"set_iris"}}
     :path ["day_camera" "set_iris" "value"]
     :field-msg "cmd.DayCamera.SetIris" :field-name "value"
     :from-frac 0.2 :to-frac 0.8}
    {:kind :sweep :interp :linear :steps 12 :delay-ms 70 :max-jitter 5
     :pins {"payload" #{"heat_camera"} "cmd" #{"set_dde_level"}}
     :path ["heat_camera" "set_dde_level" "value"]
     :field-msg "cmd.HeatCamera.SetDDELevel" :field-name "value" :leaf-kind :int
     :from-frac 0.2 :to-frac 0.75}
    {:kind :pause :ms 400}
    ping-phase]})

(defn profile-names
  "The registered profile names (keywords), sorted for stable help text."
  []
  (vec (sort (keys profiles))))

(defn covered-payload-branches
  "The set of `cmd.Root` `payload` branch names exercised by ALL registered
   profiles — derived from the `:pins` maps in each phase. `:ping` and `:pause`
   phases emit `ping` and `noop` payloads respectively via explicit constant pins
   in the synthesizer; those are included here so the set is the COMPLETE
   exercised surface.

   This is a pure function over the `profiles` registry (no pool / descriptor
   access) and is used by `stream-coverage-test` to compute the complement
   (the unexercised payload branches that require classification)."
  []
  (into #{"ping" "noop"}
        (for [[_profile-name phases] profiles
              phase phases
              :when (:pins phase)
              :let [payload-branches (get (:pins phase) "payload")]
              :when payload-branches
              branch payload-branches]
          branch)))

;; ── output (.bin files + stream-manifest.edn) ─────────────────────────────

(defn- safe-name [s] (str/replace s #"[^A-Za-z0-9._-]" "_"))

(defn entry
  "One stream manifest entry for step `i`: the corpus contract keys
   (msg-name/seed/edn-value/byte-count/verdict/kind) PLUS the temporal keys
   (:profile :sequence-index :delay-ms), and the wire bytes under :bin (stripped
   before the manifest is written, like write-corpus!)."
  [pool profile i {:keys [edn-value delay-ms seed]}]
  (let [msg (pool/build-message pool root-msg edn-value)
        wire (pool/->bin msg)
        vios (oracle/violations msg)]
    {:msg-name root-msg
     :seed seed
     :kind :stream
     :profile profile
     :sequence-index i
     :delay-ms delay-ms
     :edn-value edn-value
     :byte-count (alength ^bytes wire)
     :verdict (if (empty? vios) :valid {:invalid vios})
     :bin wire}))

(defn write-stream!
  "Write `entries` (in order) as `.bin` files + a `stream-manifest.edn` index
   under `dir`. File names carry the profile + a zero-padded sequence index so the
   replay order is the file order. Returns the manifest (each entry sans :bin)."
  [dir profile entries]
  (let [out (io/file dir)]
    (.mkdirs out)
    (let [manifest
          (vec (map-indexed
                (fn [i e]
                  (let [fname (format "%s-stream-%s-%04d.bin"
                                      (safe-name (:msg-name e)) (name profile) i)]
                    (with-open [os (io/output-stream (io/file out fname))]
                      (.write os ^bytes (:bin e)))
                    (-> e (dissoc :bin) (assoc :file fname))))
                entries))]
      (spit (io/file out "stream-manifest.edn")
            (with-out-str (pprint/pprint manifest)))
      manifest)))

;; ── orchestration ─────────────────────────────────────────────────────────

(defn run
  "Programmatic entry: build the pool + live-constraint db, synthesize the
   profile's session, optionally write it, and return a summary
   `{:profile :count :total-ms :bytes :output}`. Every step is oracle-validated
   during synthesis (a violation throws)."
  [{:keys [descriptor db-path profile seed output]
    :or {descriptor "../../../output/json-descriptors/descriptor-set.binpb"
         db-path "../proto-db.edn"
         seed 1}}]
  (when-not profile
    (throw (ex-info "gen-stream requires --profile <name>" {})))
  (let [prof (get profiles profile)]
    (when-not prof
      (throw (ex-info "unknown profile" {:profile profile :available (profile-names)}))))
  (let [pool (pool/load-pool descriptor)
        db (constraints/effective-db pool descriptor db-path)
        steps (synthesize pool db (get profiles profile) seed)
        entries (map-indexed (fn [i s] (entry pool profile i s)) steps)
        entries (vec entries)
        total-ms (reduce + 0 (map :delay-ms entries))
        byte-total (reduce + 0 (map :byte-count entries))]
    (when output
      (write-stream! output profile entries))
    {:profile profile
     :count (count entries)
     :total-ms total-ms
     :bytes byte-total
     :output output}))

;; ── CLI ────────────────────────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [opts {} [k v & more] args]
    (cond
      (nil? k) opts
      (= k "--list-profiles") (recur (assoc opts :list-profiles true) (cons v more))
      :else (recur (assoc opts (keyword (subs k 2)) v) more))))

(defn- profile-list-str []
  (str/join ", " (map name (profile-names))))

(defn -main
  "CLI: gen-stream --profile <name> [--seed S] [--output DIR] [--descriptor PATH]
   [--db-path PATH] [--list-profiles]."
  [& args]
  (let [{:keys [profile seed list-profiles] :as opts} (parse-args args)]
    (cond
      list-profiles
      (println "Profiles:" (profile-list-str))

      (not profile)
      (do (println "Usage: gen-stream --profile <name> [--seed S] [--output DIR]"
                   "[--descriptor PATH] [--db-path PATH] [--list-profiles]")
          (println "Profiles:" (profile-list-str))
          (System/exit 1))

      (not (contains? profiles (keyword profile)))
      (do (println (format "Unknown profile '%s'. Available: %s" profile (profile-list-str)))
          (System/exit 1))

      :else
      (try
        (let [r (run (cond-> (assoc opts :profile (keyword profile))
                       seed (assoc :seed (or (parse-long seed)
                                             (throw (ex-info "--seed must be an integer"
                                                             {:seed seed}))))))]
          (println (format "gen-stream %s: %d messages, %d ms total, %d bytes%s"
                           (name (:profile r)) (:count r) (:total-ms r) (:bytes r)
                           (if (:output r) (str " -> " (:output r)) ""))))
        (catch clojure.lang.ExceptionInfo e
          ;; a synthesized invalid message (should be impossible) or a bad profile:
          ;; surface the violation and exit non-zero (fail fast).
          (println "gen-stream FAILED:" (ex-message e) (pr-str (ex-data e)))
          (System/exit 1))))))
