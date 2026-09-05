(ns protocol-gen.core
  "The generator's command line.

   SUBCOMMANDS

     survey --db <path>
       The construct enumeration: what a descriptor database holds and what of
       it this generator cannot emit, by reason. A REPORT — a refusal here is a
       fact about the corpus and becomes a failure only when a policy grants
       the thing that carries it.

     generate --db <path> --minted <path> --registry <path> --policy <path>
              --out <dir>
       Emit one `.proto` per group, one Rust ACCESS MODULE per group, the flat
       permission mirror, the NESTED permission tree a byte-level scanner walks,
       and the SUBJECT GROUP TABLE a read path narrows against — all derived
       from the same projection in the same run, so no two of them can
       disagree.
       Every path is named; nothing is defaulted.

     reconcile --minted <path> --registry <path>
       Grow the assign-once field-number registry to cover every declared mint,
       and write it back. DELIBERATELY SEPARATE FROM GENERATING: a generator
       that could grow its own registry would assign a number as a side effect
       of being run, which is the one thing assign-once exists to prevent. The
       registry file must already exist — an empty `{}` is the honest starting
       state, and inventing the path would write a wire contract somewhere
       nobody is looking.

   FLAGS ARE CLOSED AND VALUES ARE NEVER GUESSED. An unknown flag fails loudly
   rather than being ignored, and no path has a default: a generator that
   invents a destination writes somewhere nobody checked, and a generator that
   invents an input reads something nobody chose."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [malli.core :as m]
            [protocol-gen.constraints :as constraints]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]
            [protocol-gen.emit :as emit]
            [protocol-gen.mirror :as mirror]
            [protocol-gen.numbering :as numbering]
            [protocol-gen.permission-tree :as permission-tree]
            [protocol-gen.policy :as policy]
            [protocol-gen.projection :as projection]
            [protocol-gen.render :as render]
            [protocol-gen.rust-access :as rust-access]
            [protocol-gen.state-table :as state-table]))

(set! *warn-on-reflection* true)

(def ^:private flag-keys
  "Every flag this CLI accepts, mapped to the key it lands on. Closed: a flag
   absent from this map is a usage error, never a silently ignored argument."
  {"--db" :db
   "--minted" :minted
   "--registry" :registry
   "--policy" :policy
   "--out" :out})

(defn parse-args
  "Parse `args` into a flag map. Throws on an odd argument count, on an unknown
   flag, and on a repeated flag — a repeat is ambiguous, and silently taking
   the last one is how a caller's typo becomes a run against the wrong file."
  [args]
  (when (odd? (count args))
    (throw (ex-info "Every flag takes a value" {:args (vec args)})))
  (reduce (fn [acc [flag value]]
            (let [k (or (get flag-keys flag)
                        (throw (ex-info "Unknown flag"
                                        {:flag flag :expected (sort (keys flag-keys))})))]
              (when (contains? acc k)
                (throw (ex-info "Repeated flag" {:flag flag})))
              (assoc acc k value)))
          {}
          (partition 2 args)))

(m/=> parse-args
      [:=> [:cat [:sequential [:string {:min 1}]]] [:map-of :keyword [:string {:min 1}]]])

(defn- require-flag
  "The value of `k`, or a throw naming the flag that is missing."
  [opts k flag]
  (or (get opts k)
      (throw (ex-info "Required flag missing" {:flag flag :got (sort (keys opts))}))))

(m/=> require-flag
      [:=> [:cat [:map-of :keyword [:string {:min 1}]] :keyword [:string {:min 1}]]
       [:string {:min 1}]])

(defn survey!
  "Print the construct enumeration for the database named by `--db`."
  [opts]
  (let [path (require-flag opts :db "--db")]
    (pp/pprint (constructs/survey (db/load-database path))))
  nil)

(m/=> survey! [:=> [:cat [:map-of :keyword [:string {:min 1}]]] :nil])

(defn reconcile!
  "Grow the registry named by `--registry` to cover every minted MESSAGE named
   by `--minted`, write it back, and print what moved. A minted ENUM carries its
   members' numbers inline and is skipped: there is no assignment for
   assign-once to protect in a number that is already written down.

   Prints the ADDED pins rather than a bare success line: a reconcile that
   silently wrote nothing and one that silently minted a wire contract look
   identical otherwise."
  [opts]
  (let [mints-path (require-flag opts :minted "--minted")
        registry-path (require-flag opts :registry "--registry")
        mints (numbering/load-mints mints-path)
        before (numbering/load-registry registry-path)
        after (numbering/reconcile before mints)
        added (into (sorted-map)
                    (for [[msg-id entry] after
                          [field-name n] entry
                          :when (not= n (get-in before [msg-id field-name]))]
                      [(str msg-id "." field-name) n]))]
    (numbering/save-registry! registry-path after)
    (if (seq added)
      (do (println (str "protocol-gen reconcile: " (count added) " pin(s) added"))
          (doseq [[subject n] added] (println (str "  " subject " = " n))))
      (println "protocol-gen reconcile: no change — every declared field was already pinned")))
  nil)

(m/=> reconcile! [:=> [:cat [:map-of :keyword [:string {:min 1}]]] :nil])

(defn- write-group!
  "Emit one group's `.proto` into `out-dir`, and return the projected group so
   the mirror is built from the same value that produced the text.

   IMPORTS ARE DERIVED FROM THE RENDERED FILE, not predicted from the policy: a
   file that emits no annotation must not import the validation proto, and an
   unused import is not something protoc reports."
  [out-dir g]
  (let [bare (render/render-group g constraints/field-options [])
        annotated? (boolean (some (comp seq :options) (mapcat :fields (:messages bare))))
        file (assoc bare :imports (constraints/imports-for annotated?))
        path (str out-dir "/" (name (:id g)) ".proto")]
    (io/make-parents path)
    (spit path (emit/file->proto file))
    (println (str "protocol-gen: " path " — "
                  (count (:messages file)) " message(s), "
                  (count (:enums file)) " enum(s), "
                  (reduce + 0 (map (comp count :fields) (:messages file))) " field(s)"))
    g))

(defn- write-rust-access!
  "Emit one group's Rust ACCESS MODULE into `out-dir`, from the SAME projected
   group `write-group!` just wrote the `.proto` from.

   IT TAKES THE PROJECTION, NEVER THE EMITTED TEXT. Deriving the access facts
   by reading back a file this run wrote would make the module a second opinion
   about the first, and the two would disagree the first time either was wrong.

   Prints the message count so a run that emitted an EMPTY module is
   distinguishable from one that emitted a full one — the two are otherwise one
   line of output apart."
  [out-dir g]
  (let [path (str out-dir "/" (name (:id g)) ".rs")]
    (io/make-parents path)
    (spit path (rust-access/module g))
    (println (str "protocol-gen: " path " — "
                  (count (:messages g)) " granted message(s)"))
    g))

(defn- write-permission-tree!
  "Emit the NESTED permission tree — every group's static, in one file — from
   the SAME projected groups the other artefacts were written from.

   ONE FILE RATHER THAN ONE PER GROUP, unlike the `.proto` and the access
   module beside it, and the reason is the consumer rather than a convention: a
   scanner selects a group's tree at run time from the connecting client's
   identity, so it needs every tree plus the table that maps an id to one. A
   file per group would make that table something the consumer assembles by
   hand out of N includes.

   IT TAKES THE TREES ALREADY BUILT, never the projection, and that is the
   ORDER contract `generate!` states: building them is what refuses a cyclic
   grant and a colliding static name, so it happens before any file appears
   rather than after four of them do.

   Prints the node count so an empty tree is distinguishable from a full one —
   the two are otherwise one line of output apart."
  [out-dir built]
  (let [path (str out-dir "/permission_tree.rs")
        nodes (fn nodes [n] (inc (reduce + 0 (map nodes (:children n)))))]
    (io/make-parents path)
    (spit path (permission-tree/module built))
    (println (str "protocol-gen: " path " — " (count built) " group tree(s), "
                  (reduce + 0 (for [t built, r (:roots t)] (nodes r))) " node(s)")))
  nil)

(defn- write-state-table!
  "Emit the SUBJECT GROUP TABLE from the SAME projected groups, and the closed
   set the policy declared them against.

   THE UNIVERSE COMES FROM THE POLICY AND THE PERMITTED SET FROM THE PROJECTION,
   and neither is a second source for the other. A group's list is an
   authorisation fact and is carried in the projection with `:access`; the set it
   is a subset of is a POLICY-level declaration that no single group can see, so
   copying it into each projected group would be one fact with N homes.

   Prints both counts, so a policy that declares no state axis is
   distinguishable from one whose groups all receive nothing — the two emit
   different files and would otherwise read as one line of output."
  [out-dir declared groups]
  (let [path (str out-dir "/subject_groups.rs")
        rows (state-table/rows declared groups)]
    (io/make-parents path)
    (spit path (state-table/module declared rows))
    (println (str "protocol-gen: " path " — " (count declared)
                  " declared subject group(s), "
                  (reduce + 0 (for [r rows, e (:entries r) :when (:permitted e)] 1))
                  " permitted across " (count rows) " group(s)")))
  nil)

(defn generate!
  "Project the database and the minted messages through the policy, then emit
   one `.proto` per group, one Rust access module per group, one flat
   permission mirror, one nested permission tree and one subject group table
   beside them.

   THE ARTEFACTS ARE ONE VALUE SEEN SEVERAL WAYS. `groups` is projected once
   and each writer is handed it; none of them reads what another wrote, so
   there is nothing for two of them to disagree about that a third could be
   right about.

   THE ORDER IS THE CONTRACT. Mints are numbered from the registry FIRST, so a
   run against an incomplete registry dies before it writes anything; the
   projection then refuses anything it cannot emit truthfully; only then does
   any file appear. A generator that wrote as it went would leave a partial
   output set behind on a refusal, and a partial set is indistinguishable from
   a policy that granted less."
  [opts]
  (let [database (db/load-database (require-flag opts :db "--db"))
        mints (numbering/load-mints (require-flag opts :minted "--minted"))
        registry (numbering/load-registry (require-flag opts :registry "--registry"))
        p (policy/load-policy (require-flag opts :policy "--policy"))
        out-dir (require-flag opts :out "--out")
        minted (numbering/apply-numbering registry mints)
        groups (projection/project database minted p)
        trees (permission-tree/trees (projection/universe database minted) groups)
        mirror-path (str out-dir "/permissions.edn")]
    (run! #(write-group! out-dir %) groups)
    (run! #(write-rust-access! out-dir %) groups)
    (io/make-parents mirror-path)
    (spit mirror-path (with-out-str (pp/pprint (mirror/mirror groups))))
    (println (str "protocol-gen: " mirror-path " — " (count groups) " group(s)"))
    (write-permission-tree! out-dir trees)
    (write-state-table! out-dir (policy/declared-subject-groups p) groups))
  nil)

(m/=> generate! [:=> [:cat [:map-of :keyword [:string {:min 1}]]] :nil])

(def ^:private subcommands
  "Subcommand name -> handler. Closed for the same reason the flag map is."
  {"survey" survey!
   "reconcile" reconcile!
   "generate" generate!})

(defn -main
  "Dispatch on the first argument; anything unrecognised is a usage error."
  [& args]
  (let [[cmd & rest-args] args
        handler (or (get subcommands cmd)
                    (throw (ex-info "Unknown subcommand"
                                    {:subcommand cmd :expected (sort (keys subcommands))})))]
    (handler (parse-args rest-args)))
  nil)

(m/=> -main [:=> [:cat [:* [:string {:min 1}]]] :nil])
