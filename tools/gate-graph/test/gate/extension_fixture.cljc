(ns gate.extension-fixture
  "Public synthetic producer shared by JVM, browser and portable parity tests."
  (:require [gate.archive :as archive]
            [gate.canonical :as canonical]
            [gate.extension :as extension]))

(def source {:id "synthetic" :kind :declaration :digest (canonical/sha256 "synthetic producer declaration") :label "Synthetic producer"})
(def unavailable {:state :unavailable :failure {:code :repository-unreadable}})
(defn core []
  (archive/create {:id "example" :label "Public example" :kind :battery :omissions []}
                  {:schema/version 1
                   :run {:id "example" :source-digest (canonical/sha256 "example") :complete? true
                         :clock {:id "logical" :origin-ns "0"} :end-ns "10" :expected-keys ["compile"]}
                   :sources [source] :resources [] :edges [] :measurements []
                   :nodes [{:id "compile" :key "compile" :label "Compile" :parent nil :source "synthetic"
                            :kind :gate :record :execution :attempt 1 :outcome :passed :interval {:start-ns "0" :end-ns "10"}}]}
                  unavailable unavailable))
(def cache-contract
  {:extension/id :example.cache/observation :extension/version 1 :schema/dialect 1
   :payload/schema [:map {:closed true} [:cache [:ref :example.cache/result]]
                    [:label {:optional true} [:string {:min 0 :max 4096}]]]
   :payload/definitions
   [{:schema/id :example.cache/count :schema/form :gate.extension/natural}
    {:schema/id :example.cache/result
     :schema/form [:multi {:dispatch :state}
                   [:hit [:map {:closed true} [:state [:= :hit]] [:bytes [:ref :example.cache/count]]]]
                   [:miss [:map {:closed true} [:state [:= :miss]] [:reason [:enum :cold :changed]]]]]}]})
(def quality-contract
  {:extension/id :example.quality/notes :extension/version 1 :schema/dialect 1
   :payload/schema [:map {:closed true} [:notes [:vector {:min 0 :max 32}
                                                 [:tuple [:string {:min 0 :max 200}] :boolean]]]]
   :payload/definitions []})
(def payload {:cache {:state :hit :bytes "900719925474099312345"} :label "Portable 🪐"})
(defn projector [observed]
  {:title "Computation observation"
   :fields [{:label "Observed bytes" :value {:kind :natural :value (get-in observed [:cache :bytes] "0") :unit "byte"}}]
   :text (:label observed "")})
(defn registrations []
  [{:contract cache-contract :projector {:projector/id "example/projector" :projector/version 1
                                         :implementation/source source :projector/fn projector}}
   {:contract quality-contract}])
(defn request []
  {:observation/id "cache-1" :extension/id :example.cache/observation :extension/version 1
   :subject {:kind :node :id "compile"} :source "synthetic" :payload payload})
(defn example []
  (let [context (extension/prepare (registrations)) archive (core)
        cache-observation (extension/observe context archive (request))
        quality-observation (extension/observe context archive {:observation/id "quality-1" :extension/id :example.quality/notes
                                                                :extension/version 1 :subject {:kind :run :id "example"}
                                                                :source "synthetic" :payload {:notes [["Known limit" true]]}})
        sidecar (extension/create context archive [quality-observation cache-observation])]
    {:context context :core archive :sidecar sidecar :pair (extension/pair context archive sidecar)}))
