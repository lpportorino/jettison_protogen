(ns protocol-gen.core
  "The generator's command line.

   SUBCOMMANDS

     survey --db <path>
       The construct enumeration: what a descriptor database holds and what of
       it this generator cannot emit, by reason. A REPORT — a refusal here is a
       fact about the corpus and becomes a failure only when a policy grants
       the thing that carries it.

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
  (:require [clojure.pprint :as pp]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]
            [protocol-gen.numbering :as numbering]))

(set! *warn-on-reflection* true)

(def ^:private flag-keys
  "Every flag this CLI accepts, mapped to the key it lands on. Closed: a flag
   absent from this map is a usage error, never a silently ignored argument."
  {"--db" :db
   "--minted" :minted
   "--registry" :registry})

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
  "Grow the registry named by `--registry` to cover every mint named by
   `--minted`, write it back, and print what moved.

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

(def ^:private subcommands
  "Subcommand name -> handler. Closed for the same reason the flag map is."
  {"survey" survey!
   "reconcile" reconcile!})

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
