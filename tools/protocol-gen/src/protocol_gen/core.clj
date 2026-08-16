(ns protocol-gen.core
  "The generator's command line.

   SUBCOMMANDS

     survey --db <path>
       The construct enumeration: what a descriptor database holds and what of
       it this generator cannot emit, by reason. A REPORT — a refusal here is a
       fact about the corpus and becomes a failure only when a policy grants
       the thing that carries it.

   FLAGS ARE CLOSED AND VALUES ARE NEVER GUESSED. An unknown flag fails loudly
   rather than being ignored, and no path has a default: a generator that
   invents a destination writes somewhere nobody checked, and a generator that
   invents an input reads something nobody chose."
  (:require [clojure.pprint :as pp]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]))

(set! *warn-on-reflection* true)

(def ^:private flag-keys
  "Every flag this CLI accepts, mapped to the key it lands on. Closed: a flag
   absent from this map is a usage error, never a silently ignored argument."
  {"--db" :db})

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

(def ^:private subcommands
  "Subcommand name -> handler. Closed for the same reason the flag map is."
  {"survey" survey!})

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
