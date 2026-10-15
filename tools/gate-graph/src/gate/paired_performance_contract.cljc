(ns gate.paired-performance-contract
  "Closed, finite protocol and raw evidence for descriptive paired performance comparisons."
  (:require [gate.contract :as c]))

(def Duration [:and [:string {:min 1 :max 18}] [:re #"^(0|[1-9][0-9]*)(?![\s\S])"]])
(def Fraction [:map {:closed true} [:numerator Duration] [:denominator Duration]])
(def Identity [:map {:closed true} [:source c/Digest] [:build c/Digest]])
(def Policy [:map {:closed true}
             [:minimum-per-order [:int {:min 3 :max 16}]]
             [:resolution-multiple [:int {:min 100 :max 1000000}]]
             [:control-tolerance-ppm [:int {:min 1 :max 500000}]]
             [:order-tolerance-ppm [:int {:min 1 :max 500000}]]
             [:spread-tolerance-ppm [:int {:min 1 :max 1000000}]]
             [:effect-floor-ppm [:int {:min 1 :max 500000}]]
             [:median-bound-miss-ppm [:int {:min 1 :max 250000}]]])
(def Order [:enum :reference-first :candidate-first])
(def Block [:int {:min 0 :max 31}])
(def ScheduleEntry [:map {:closed true} [:block Block] [:order Order]
                    [:first-kind [:enum :comparison :control]]])
(def Protocol [:map {:closed true} [:profile [:= :paired-wall-v1]]
               [:reference Identity] [:candidate Identity] [:runtime c/Digest]
               [:work c/Digest] [:output c/Digest] [:warmup [:int {:min 0 :max 1000000}]]
               [:cache [:= :disabled]] [:timer [:= :monotonic]] [:resolution-ns Duration]
               [:policy Policy] [:schedule [:vector {:max 32} ScheduleEntry]]])
(def Observation [:map {:closed true} [:index [:int {:min 0 :max 127}]]
                  [:variant [:enum :reference :candidate]] [:source c/Digest] [:build c/Digest]
                  [:runtime c/Digest] [:work c/Digest] [:output c/Digest]
                  [:warmup [:int {:min 0 :max 1000000}]] [:cache [:enum :disabled :hit]]
                  [:status [:enum :succeeded :failed]] [:elapsed-ns Duration]])
(def Pair [:map {:closed true} [:id [:string {:min 1 :max 128}]]
           [:kind [:enum :comparison :control]] [:order Order] [:block Block]
           [:reference Observation] [:candidate Observation]])
(def ResolutionEvidence
  [:or
   [:map {:closed true} [:status [:= :declared-unit-only]] [:source c/Digest]]
   [:map {:closed true} [:status [:= :measured]] [:method c/Digest] [:record c/Digest] [:observed-ns Duration]]])
(def Document [:map {:closed true} [:schema/version [:= 1]]
               [:protocol Protocol] [:protocol-digest c/Digest] [:resolution-evidence ResolutionEvidence]
               [:declaration-evidence c/Digest] [:measurement-evidence c/Digest]
               [:pairs [:vector {:max 64} Pair]]])
(def Reason [:enum :no-companion :protocol-binding :duplicate-pair :block-schedule
             :observation-binding :failed-observation :cached-observation :invalid-duration :invalid-order :resolution-binding
             :effect-floor-unresolvable :replication-unresolvable
             :insufficient-pairs :unbalanced-order :timer-resolution :timer-resolution-unverified
             :control-noise :order-effect :pair-spread :effect-unresolved :protocol-satisfied])
(def RatioRow [:map {:closed true} [:id [:string {:min 1 :max 128}]]
               [:kind [:enum :comparison :control]] [:order [:enum :reference-first :candidate-first]] [:ratio Fraction]])
(def Summary [:map {:closed true} [:minimum Fraction] [:median-low Fraction] [:median-high Fraction] [:maximum Fraction]
              [:median-lower-bound {:optional true} Fraction] [:median-upper-bound {:optional true} Fraction]])
(def Direction [:enum :candidate-faster :candidate-slower])
(def evidence-entries
  [[:protocol-digest c/Digest] [:resolution-evidence ResolutionEvidence] [:policy Policy]
   [:ratios [:vector {:max 64} RatioRow]] [:interpretation [:= :descriptive-declared-protocol]]])
(def summary-keys [:comparison :control :reference-first :candidate-first])
(def Observed
  (into [:map {:closed true}] (concat evidence-entries (for [k summary-keys] [k {:optional true} Summary]))))
(def Result
  [:multi {:dispatch :status}
   [:unavailable [:map {:closed true} [:status [:= :unavailable]] [:reasons [:= [:no-companion]]]
                  [:ratios [:= []]] [:interpretation [:= :descriptive-declared-protocol]]]]
   [:refused (into [:map {:closed true} [:status [:= :refused]] [:reasons [:vector {:min 1 :max 20} Reason]]]
                   evidence-entries)]
   [:inconclusive (into [:map {:closed true} [:status [:= :inconclusive]] [:reasons [:vector {:min 1 :max 20} Reason]]]
                        (concat evidence-entries (for [k summary-keys] [k {:optional true} Summary])))]
   [:qualified (into [:map {:closed true} [:status [:= :qualified]] [:reasons [:= [:protocol-satisfied]]]
                      [:direction Direction]]
                     (concat evidence-entries (for [k summary-keys] [k Summary])))]])
(def Encodable [:or Document Protocol Policy Result Fraction Summary RatioRow])
(def registry {::duration Duration ::fraction Fraction ::identity Identity ::policy Policy ::order Order ::block Block ::schedule-entry ScheduleEntry ::protocol Protocol
               ::observation Observation ::pair Pair ::resolution-evidence ResolutionEvidence ::document Document ::reason Reason ::ratio-row RatioRow
               ::summary Summary ::direction Direction ::observed Observed ::result Result})
