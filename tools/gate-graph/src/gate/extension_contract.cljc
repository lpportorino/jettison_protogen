(ns gate.extension-contract
  "Versioned observation envelopes and bounded presentations; no core verdict or cache authority."
  (:require [gate.admission :as admission]
            [gate.contract :as c]
            [gate.portable :as p]
            [malli.core :as m]))

(def limits
  "The hard ceilings, read by every count and byte bound in this API; no caller
   override exists. The keyword vocabulary ceiling is admission's, shared so a
   prepared vocabulary can never exceed what decode-portable admits.
   sidecar-bytes bounds the encoded sidecar document; its file carries one more
   byte, the trailing newline the writer appends."
  {:registrations 64 :definitions 256 :keywords admission/max-vocabulary :schema-nodes 4096
   :schema-depth 32 :multi-arms 64 :observations 4096 :payload-bytes 65536
   :sidecar-bytes 8388607 :projection-bytes 16384})
(def Version [:int {:min 1 :max 2147483647}])
(def ExtensionId [:and :qualified-keyword [:fn #(<= (count (str %)) 160)]])
(def Definition [:map {:closed true} [:schema/id ExtensionId] [:schema/form p/Value]])
(def Contract [:map {:closed true} [:extension/id ExtensionId] [:extension/version Version]
               [:schema/dialect [:= 1]] [:payload/schema p/Value]
               [:payload/definitions [:vector {:max (:definitions limits)} Definition]]])
(def Binding [:map {:closed true} [:projector/id c/Id] [:projector/version Version]
              [:implementation/source c/Source] [:projector/fn fn?]])
(def Registration [:map {:closed true} [:contract Contract] [:projector {:optional true} Binding]])
(def SourceDeclaration [:map {:closed true} [:projector/id c/Id] [:projector/version Version]
                        [:implementation/source c/Source]])
(def Scalar [:multi {:dispatch :kind}
             [:text [:map {:closed true} [:kind [:= :text]] [:value [:string {:max 4096}]]]]
             [:natural [:map {:closed true} [:kind [:= :natural]] [:value c/Natural] [:unit c/Label]]]
             [:boolean [:map {:closed true} [:kind [:= :boolean]] [:value :boolean]]]])
(def Projection [:map {:closed true} [:title c/Label]
                 [:fields [:vector {:max 64} [:map {:closed true} [:label c/Label] [:value Scalar]]]]
                 [:text {:optional true} [:string {:max 4096}]]])
(def PersistedProjection (conj Projection [:provenance SourceDeclaration]))
(def Subject [:map {:closed true} [:kind [:enum :run :node]] [:id c/Id]])
(def Manifest [:map {:closed true} [:extension/id ExtensionId] [:extension/version Version]
               [:schema/fingerprint c/Digest]])
(def Observation [:map {:closed true} [:observation/id c/Id] [:extension/id ExtensionId]
                  [:extension/version Version] [:schema/fingerprint c/Digest]
                  [:subject Subject] [:source c/Id] [:payload p/Value]
                  [:projection {:optional true} PersistedProjection]])
(def ObservationRequest
  (into [:map {:closed true}]
        (remove #(contains? #{:schema/fingerprint :projection} (first %)) (drop 2 Observation))))
(def Material [:map {:closed true} [:schema/version [:= 1]] [:core/artifact c/Digest]
               [:contracts [:vector {:max (:registrations limits)} Manifest]]
               [:observations [:vector {:max (:observations limits)} Observation]]])
(def Document (conj Material [:artifact c/Digest]))
(def Code [:enum :extension-registration :extension-duplicate :extension-schema
           :extension-limit :extension-unknown :extension-version :extension-fingerprint
           :extension-payload :extension-projection :extension-projector :extension-source
           :extension-subject :extension-shape :extension-identity :extension-binding
           :extension-manifest :extension-observation :extension-request :extension-cursor
           :extension-path :extension-output :extension-output-exists])

(defn refuse!
  "Refuse an extension boundary without returning payload, source paths or callback messages."
  [code]
  (throw (ex-info "Report extension refused" {:code code})))
(m/=> refuse! [:=> [:cat Code] :nil])

(def Step [:or :keyword [:int {:min 0 :max 1000000}]])
(def Path [:vector {:max 64} Step])
(def ScalarValue [:or :nil :boolean [:int {:min 0 :max 2147483647}] [:string {:max 4096}] :keyword])
(def Descriptor [:multi {:dispatch :kind}
                 [:map [:map {:closed true} [:path Path] [:kind [:= :map]] [:children [:int {:min 0 :max 1000000}]]]]
                 [:vector [:map {:closed true} [:path Path] [:kind [:= :vector]] [:children [:int {:min 0 :max 1000000}]]]]
                 [:leaf [:map {:closed true} [:path Path] [:kind [:= :leaf]] [:value ScalarValue]]]])
(def Cursor [:map {:closed true} [:core/artifact c/Digest] [:artifact c/Digest]
             [:selection c/Digest] [:offset [:int {:min 0 :max 1000000}]] [:position c/Digest]])
(def Request [:map {:closed true} [:path Path]
              [:budget [:map {:closed true} [:rows [:int {:min 1 :max 1000}]]
                        [:visits [:int {:min 1 :max 1000000}]] [:bytes [:int {:min 1024 :max 65536}]]]]
              [:cursor {:optional true} Cursor]])
(def Page [:map {:closed true} [:core/artifact c/Digest] [:artifact c/Digest]
           [:container Descriptor] [:start [:int {:min 0 :max 1000000}]] [:end [:int {:min 0 :max 1000000}]]
           [:visited [:int {:min 0 :max 1000000}]] [:rows [:vector {:max 1000} Descriptor]]
           [:stop [:enum :complete :row-limit :visit-limit :byte-limit]] [:next [:maybe Cursor]]])
