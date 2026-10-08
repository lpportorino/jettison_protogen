(ns gate.viewer-contract
  "Closed provenance for the packaged public browser program; contains no report data."
  (:require [gate.contract :as c]))

(def ResourcePath
  ;; Unlike '$' alone, the final assertion also refuses trailing newlines in
  ;; both JVM and JavaScript regex engines.
  [:and [:string {:min 1 :max 256}]
   [:re #"^gate/(?:[A-Za-z_][A-Za-z0-9_]*/)*[A-Za-z_][A-Za-z0-9_]*\.(?:cljc|cljs)$(?![\s\S])"]])

(def Input
  [:map {:closed true}
   [:path ResourcePath]
   [:digest c/Digest]])

(def Manifest
  [:map {:closed true}
   [:schema/version [:= 1]]
   [:bundle c/Digest]
   [:inputs [:vector {:min 1 :max 256} Input]]
   [:dependencies c/Digest]
   [:configuration c/Digest]])
