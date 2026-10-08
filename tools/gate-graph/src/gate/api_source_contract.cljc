(ns gate.api-source-contract
  "Closed bounded passive source observations; runtime export completeness is a separate proof."
  (:require [gate.contract :as c]))

(def Source [:string {:max 1048576}])
(def Name [:string {:min 1 :max 512}])
(def Coordinate [:int {:min 1 :max 1048577}])
(def Position [:map {:closed true} [:row Coordinate] [:column Coordinate]])
(def Limits
  [:map {:closed true} [:bytes [:int {:min 1 :max 1048576}]]
   [:forms [:int {:min 1 :max 4096}]] [:nodes [:int {:min 1 :max 262144}]]])
(def Request
  [:map {:closed true} [:source Source] [:platform [:enum :clj :cljs]] [:limits Limits]])
(def Declaration
  [:map {:closed true} [:id Name] [:kind [:enum :function :macro :binding]]
   [:private? :boolean] [:position Position] [:source c/Digest]])
(def Registration
  [:map {:closed true} [:id Name] [:position Position] [:source c/Digest]])
(def Result
  [:map {:closed true} [:profile [:= :passive-declarations-v1]]
   [:platform [:enum :clj :cljs]] [:namespace Name] [:source c/Digest]
   [:forms [:int {:min 1 :max 4096}]] [:nodes [:int {:min 1 :max 262144}]]
   [:declarations [:vector {:max 4096} Declaration]]
   [:registrations [:vector {:max 4096} Registration]]])
(def Code
  [:enum :api-source-shape :api-source-read :api-source-limit :api-source-namespace
   :api-source-form :api-source-duplicate :api-source-unbound :api-source-spec])
(def Failure [:map {:closed true} [:code Code] [:row Coordinate] [:column Coordinate]])
(def Encodable [:or Result Failure])
(def registry
  {::request Request ::result Result ::failure Failure ::declaration Declaration
   ::registration Registration ::limits Limits})
