(ns gate.view.contract
  "Closed presentation contracts. Folds reference observations; they are never observed tasks."
  (:require [gate.contract :as c]
            [gate.inspection-contract :as ic]
            [gate.interval :as interval]))

(def Members [:vector {:min 1 :max 256} c/Id])
(def Counts [:map-of {:max 10} [:enum :passed :failed :error :cancelled :running :cached :deselected :blocked :refused] [:int {:min 1 :max 256}]])
(def Fold
  [:map {:closed true}
   [:id c/Id] [:members Members] [:summary interval/Summary]
   [:occupied interval/Intervals] [:outcomes Counts]
   [:unfinished [:int {:min 0 :max 256}]]
   [:decisions [:int {:min 0 :max 256}]]])
(def Selection
  [:multi {:dispatch :op}
   [:members [:map {:closed true} [:op [:= :members]] [:members Members]]]
   [:edges [:map {:closed true} [:op [:= :edges]] [:members Members]]]
   [:neighborhood [:map {:closed true} [:op [:= :neighborhood]] [:members Members]]]
   [:search [:map {:closed true} [:op [:= :search]] [:text [:string {:max 512}]]]]
   [:correlation [:map {:closed true} [:op [:= :correlation]] [:interval c/Interval]]]])
(def Request
  [:map {:closed true} [:select Selection] [:budget ic/Budget]
   [:cursor {:optional true} ic/Cursor]])
(def Row [:or c/Node c/Edge c/Measurement])
(def Page
  [:map {:closed true} [:artifact c/Digest]
   [:rows [:vector {:max 1000} Row]]
   [:visited [:int {:min 0 :max 128000}]] [:stop ic/StopReason]
   [:next [:maybe ic/Cursor]]])
(def Failure [:map {:closed true} [:code [:enum :invalid-view-request :invalid-view-cursor :missing-view-member]]])
(def registry
  {:gate.view/fold Fold :gate.view/selection Selection :gate.view/request Request
   :gate.view/page Page :gate.view/failure Failure})
(def Encodable [:or Fold Selection Request Page Failure Row])
