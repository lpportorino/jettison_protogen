(ns protodoc.gencorpus
  "Generative proto-binary corpus tool — orchestrator + CLI.

   Pipeline: descriptor-set.binpb → descriptor pool → (per message) malli-driven
   assembler + deterministic boundary injection → DynamicMessage → wire .bin +
   manifest.edn. The positive corpus is protovalidate-filtered to the
   constraint-VALID set; a separate violating sub-corpus carries oracle-REJECTED
   edge values (out-of-range, unknown enum number, NaN/Inf into a bounded float)
   for the codec-divergence differential.

   The output contract a consumer relies on (jettison's manifold C↔Rust
   differential, and any other): a directory of wire `.bin` files + a
   `manifest.edn` of entries
     {:msg-name :seed :edn-value :byte-count :verdict :kind :file}
   — no per-language vectors, no Clojure glue in the consumer.

   CLI: gen-corpus --descriptor <binpb> --db-path <proto-db.edn>
        --message <full.Name> --count N --seed S --output <dir>
        --leaf-only true|false --self-test true|false"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [protodoc.gencorpus.assemble :as assemble]
            [protodoc.gencorpus.constraints :as constraints]
            [protodoc.gencorpus.oracle :as oracle]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf
            Descriptors$Descriptor
            Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType])
  (:gen-class))

(set! *warn-on-reflection* true)

;; ── round-trip faithfulness (presence + byte-identity) ────────────────

(defn roundtrip
  "Build → serialize → reparse → re-serialize. Returns
   {:wire bytes :reparsed msg :wire2 bytes :byte-identical? bool} — the
   byte-faithful-roundtrip + no-silent-field-drop evidence."
  [^Descriptors$Descriptor d edn-value]
  (let [msg (pool/build-msg d edn-value)
        wire (pool/->bin msg)
        reparsed (pool/reparse d wire)
        wire2 (pool/->bin reparsed)]
    {:wire wire :reparsed reparsed :wire2 wire2
     :byte-identical? (java.util.Arrays/equals ^bytes wire ^bytes wire2)}))

(defn presence-violations
  "Top-level fields the EDN SET but that are ABSENT/zeroed after reparse — the
   silent-drop detector (bug #5/#8). A field whose set value is the proto3 zero
   default is legitimately omitted, so it is not counted."
  [^Descriptors$Descriptor d edn-value ^com.google.protobuf.Message reparsed]
  (for [[fname v] edn-value
        :when (some? v)
        :let [f (Descriptors$Descriptor/.findFieldByName d (name fname))]
        :when f
        :let [zero-default? (and (not (Descriptors$FieldDescriptor/.isRepeated f))
                                 (or (false? v) (= "" v) (and (number? v) (zero? v))))
              present? (if (Descriptors$FieldDescriptor/.isRepeated f)
                         (or (empty? v) (pos? (.getRepeatedFieldCount reparsed f)))
                         (or zero-default?
                             (.hasField reparsed f)
                             ;; proto3 scalar: hasField throws → compare the value
                             (try (= (.getField reparsed f)
                                     (.getField (pool/build-msg d edn-value) f))
                                  (catch Exception _ true))))]
        :when (not present?)]
    fname))

;; ── corpus entries ────────────────────────────────────────────────────

(defn- entry
  "One corpus entry map for `edn-value` of message `full-name`. Carries a
   reparse-based `:drops` (top-level fields the wire silently lost — the
   no-silent-field-drop guard wired into EMISSION, not just the self-test)."
  [pool full-name edn-value kind seed]
  (let [^Descriptors$Descriptor d (get pool full-name)
        msg (pool/build-msg d edn-value)
        wire (pool/->bin msg)
        drops (presence-violations d edn-value (pool/reparse d wire))
        vios (oracle/violations msg)]
    {:msg-name full-name
     :seed seed
     :kind kind
     :edn-value edn-value
     :byte-count (alength ^bytes wire)
     :verdict (if (empty? vios) :valid {:invalid vios})
     :drops (vec drops)
     :bin wire}))

(defn random-entries
  "`count` seed-deterministic random entries for `full-name` (base seed `seed`)."
  [pool db full-name seed count]
  (for [i (range count)
        :let [s (+ seed i)]]
    (entry pool full-name (assemble/generate pool db full-name s) :random s)))

(defn boundary-entries
  "Deterministic type/constraint boundary entries for `full-name`."
  [pool db full-name base-seed]
  (for [{:keys [edn-value]} (assemble/boundary-corpus pool db full-name base-seed)]
    (entry pool full-name edn-value :boundary base-seed)))

;; ── violating sub-corpus (oracle-REJECTED edge values) ────────────────

(defn- bad-numeric
  "A single out-of-bounds value for a constrained numeric field (one step past
   the boundary), or nil if no clean single-field violation exists."
  [type-kw {:keys [gte gt lte lt]}]
  (case type-kw
    (:double :float) (cond lt (double lt) gt (double gt)
                           lte (+ (double lte) 1.0) gte (- (double gte) 1.0))
    (:int32 :uint32 :int64 :uint64) (cond lt lt gt gt
                                          lte (inc' lte)
                                          (and gte (> gte 0)) (dec' gte))
    nil))

(defn- bad-strings
  "Single-field string violations: too-short (< min_len), too-long (> max_len,
   capped to keep the .bin small), and pattern-mismatch — each oracle-rejected."
  [{:keys [min-len max-len pattern]}]
  (filter some?
          [(when (and min-len (pos? min-len)) (apply str (repeat (dec min-len) "a")))
           (when (and max-len (<= max-len 1024)) (apply str (repeat (inc max-len) "a")))
           (when pattern "!!not-a-valid-pattern-instance!!")]))

(defn violating-entries
  "Per-field constraint VIOLATIONS that the oracle must REJECT: a numeric pushed
   past its bound, NaN/±Inf into a CONSTRAINED float/double, an UNDEFINED enum
   number for a defined_only/not_in enum, and a string that violates
   min_len/max_len/pattern. Each is built on a valid base. (Repeated-cardinality
   and bytes-length violations are not yet generated — the boundary corpus
   exercises the VALID endpoints of those; the violating side is a follow-up.)"
  [pool db full-name base-seed]
  (let [^Descriptors$Descriptor d (get pool full-name)
        constraints (assemble/db-field-constraints db full-name)
        base (assemble/generate pool db full-name base-seed)]
    (for [^Descriptors$FieldDescriptor f (Descriptors$Descriptor/.getFields d)
          :let [fname (Descriptors$FieldDescriptor/.getName f)
                type-kw (assemble/descriptor-type->kw f)
                c (constraints fname)]
          :when (and c
                     (not (Descriptors$FieldDescriptor/.isRepeated f))
                     (not (= :message type-kw)))
          bad (cond
                (#{:double :float} type-kw)
                (filter some? [(bad-numeric type-kw c)
                               (when (or (:gte c) (:lte c) (:gt c) (:lt c))
                                 Double/NaN)])
                (#{:int32 :uint32 :int64 :uint64} type-kw)
                (filter some? [(bad-numeric type-kw c)])
                (= :enum type-kw)
                (when (or (:defined-only c) (:not-in c))
                  (let [defined (set (assemble/enum-numbers f nil))]
                    [(inc (apply max 0 defined))]))
                (= :string type-kw)
                (bad-strings c)
                :else nil)
          :when (some? bad)]
      (assoc (entry pool full-name (assoc base fname bad) :violating base-seed)
             :field fname :bad-value bad))))

;; ── orchestration ─────────────────────────────────────────────────────

(defn gen-corpus
  "Generate the positive (random + boundary, protovalidate-filtered) and
   violating corpora for `message`. Returns
   {:positive [...] :boundary-failures [...] :violating [...]}. A boundary entry
   that fails the oracle is surfaced (a faithful boundary should be valid) — not
   silently dropped."
  [{:keys [pool db message seed count]
    :or {seed 1 count 16}}]
  (let [^Descriptors$Descriptor d (get pool message)
        ;; the boundary base (same seed boundary-corpus uses) — if IT is already
        ;; oracle-invalid (proto-db drift in some OTHER field, e.g. CvChannelMeta's
        ;; stale min_items), a rejected boundary entry inherits that, NOT a bad
        ;; boundary value. boundary-failures are meaningful only on a valid base.
        base-valid? (oracle/valid? (pool/build-msg d (assemble/generate pool db message seed)))
        randoms (random-entries pool db message seed count)
        bounds (boundary-entries pool db message seed)
        candidates (violating-entries pool db message seed)
        valid? #(= :valid (:verdict %))
        positive (filter valid? (concat randoms bounds))
        ;; a boundary value should be VALID — a rejected one ON A VALID BASE is a
        ;; real faithfulness bug (the boundary value itself broke it), surfaced,
        ;; never silently dropped. On an invalid base it is drift, not a bug.
        boundary-failures (if base-valid? (remove valid? bounds) [])
        ;; the oracle (the LIVE descriptor) is the arbiter of what is a
        ;; violation. A candidate it accepts is NOT a violation — it means
        ;; proto-db carries a constraint the live .proto lacks (a documented
        ;; proto-db↔gencode drift, e.g. the ser-side altitude fields). Recorded
        ;; as drift, NOT counted as the violating corpus.
        violating (remove valid? candidates)
        violating-drift (filter valid? candidates)
        ;; any POSITIVE entry that lost a set field on reparse is a silent-drop
        ;; bug (no-silent-field-drop, checked at EMISSION not just in the suite).
        silent-drops (filter (comp seq :drops) positive)]
    {:positive (vec positive)
     :boundary-failures (vec boundary-failures)
     :violating (vec violating)
     :violating-drift (vec violating-drift)
     :silent-drops (vec silent-drops)}))

;; ── output (.bin files + manifest.edn) ────────────────────────────────

(defn- safe-name [s] (str/replace s #"[^A-Za-z0-9._-]" "_"))

(defn write-corpus!
  "Write `entries` as `.bin` files + a `manifest.edn` index under `dir`. The
   manifest carries the consumer contract per entry (msg-name, seed, edn-value,
   byte-count, verdict, kind, file). Returns the manifest data."
  [dir entries]
  (let [out (io/file dir)]
    (.mkdirs out)
    (let [indexed (map-indexed
                   (fn [i e]
                     (let [fname (format "%s-%s-%04d.bin" (safe-name (:msg-name e)) (name (:kind e)) i)]
                       (with-open [os (io/output-stream (io/file out fname))]
                         (.write os ^bytes (:bin e)))
                       ;; :bin is the wire (written to the .bin); :drops is an
                       ;; internal emission-check artifact — neither belongs in
                       ;; the consumer manifest.
                       (-> e (dissoc :bin :drops) (assoc :file fname))))
                   entries)
          manifest (vec indexed)]
      (spit (io/file out "manifest.edn") (with-out-str (pprint/pprint manifest)))
      manifest)))

(defn run
  "Programmatic entry: build the pool + db, generate the corpora, optionally
   write them. opts: :descriptor :db-path :message :count :seed :output."
  [{:keys [descriptor db-path message count seed output pins]
    :or {descriptor "../../../output/json-descriptors/descriptor-set.binpb"
         db-path "../proto-db.edn"
         count 16 seed 1}}]
  (when-not message
    (throw (ex-info "gen-corpus requires --message <full.Name>" {})))
  (let [pool (pool/load-pool descriptor)
        ;; constraints from the LIVE descriptor (drift-immune), not the drifting
        ;; proto-db — only :example domain seeds come from proto-db.
        db (constraints/effective-db pool descriptor db-path)
        ;; `pins` scopes the whole recursive generator's oneof selection (bound
        ;; once — the generator tree builds eagerly under this binding, so every
        ;; nested oneof is filtered). nil ⇒ unconstrained.
        {:keys [positive violating boundary-failures violating-drift silent-drops]}
        (binding [assemble/*pins* pins]
          (gen-corpus {:pool pool :db db :message message :seed seed :count count}))
        all (concat positive violating)]
    (when output
      (write-corpus! output all))
    {:message message
     :positive (clojure.core/count positive)
     :violating (clojure.core/count violating)
     :boundary-failures (clojure.core/count boundary-failures)
     :violating-drift (clojure.core/count violating-drift)
     :silent-drops (clojure.core/count silent-drops)
     :empty-positive? (empty? positive)
     :output output}))

;; ── CLI ───────────────────────────────────────────────────────────────

(defn- parse-args [args]
  (loop [opts {} [k v & more] args]
    (if k (recur (assoc opts (keyword (subs k 2)) v) more) opts)))

(defn- parse-pos-int
  "Parse a CLI integer option, failing loud (not an NPE) on a non-integer."
  [flag v]
  (or (parse-long v)
      (throw (ex-info (str flag " must be an integer") {:flag flag :value v}))))

(defn- parse-pins
  "Parse the `--pin` EDN argument into an `assemble/*pins*` map
   `{oneof-name #{field ...}}` — each branch collection coerced to a set. Fails
   loud on a non-map, or a non-string oneof name / branch. Example EDN:
   `{\"payload\" #{\"heat_camera\"} \"cmd\" #{\"set_dde_level\" \"enable_dde\"}}`."
  [s]
  (let [m (edn/read-string s)]
    (when-not (map? m)
      (throw (ex-info "--pin must be an EDN map {oneof-name #{field ...}}" {:value s})))
    (reduce-kv (fn [acc k v]
                 (when-not (string? k)
                   (throw (ex-info "--pin oneof name must be a string" {:key k})))
                 (assoc acc k (into #{} (map (fn [b]
                                               (if (string? b) b
                                                   (throw (ex-info "--pin branch must be a string"
                                                                   {:branch b}))))) v)))
               {} m)))

(defn -main
  "CLI: gen-corpus options (see ns docstring)."
  [& args]
  (let [{:keys [count seed pin] :as opts} (parse-args args)
        opts (cond-> opts
               count (assoc :count (parse-pos-int "--count" count))
               seed (assoc :seed (parse-pos-int "--seed" seed))
               pin (assoc :pins (parse-pins pin)))]
    (if-not (:message opts)
      (do (println "Usage: gen-corpus --message <full.Name> [--descriptor PATH] [--db-path PATH] [--count N] [--seed S] [--output DIR] [--pin '{\"oneof\" #{\"branch\"}}']")
          (System/exit 1))
      (let [r (run opts)]
        (println (format "gen-corpus %s: %d positive, %d violating, %d boundary-failures, %d proto-db-drift, %d silent-drops%s"
                         (:message r) (:positive r) (:violating r)
                         (:boundary-failures r) (:violating-drift r) (:silent-drops r)
                         (if (:output r) (str " -> " (:output r)) "")))
        (when (:empty-positive? r)
          (println "WARNING: zero positive corpus entries — the message may carry"
                   "a message-level CEL rule the field-wise generator cannot satisfy,"
                   "or every draw was oracle-rejected."))
        ;; FAIL LOUD: a boundary value the oracle rejects is a real faithfulness
        ;; bug; a silent field drop corrupts the corpus; an empty positive corpus
        ;; for a requested message is a coverage failure. (proto-db-drift is an
        ;; upstream data note, not a tool failure.)
        (when (or (pos? (:boundary-failures r))
                  (pos? (:silent-drops r))
                  (:empty-positive? r))
          (System/exit 1))))))
