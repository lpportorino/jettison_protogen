(ns protocol-gen.mirror
  "The machine-readable PERMISSION MIRROR, derived from the same projection the
   `.proto` text is derived from.

   WHY IT EXISTS. Two facts a group needs cannot live in a `.proto` file at
   all. The first is DIRECTION: proto describes a shape, and `may send` versus
   `may receive` is not a shape. The second is PROVENANCE: the emitted file
   says a field is number 7 and cannot say whether 7 came from a descriptor or
   from the assign-once registry.

   WHY IT IS EMITTED FROM THE SAME RUN AND THE SAME VALUE. A mirror produced by
   a second pass over the policy would be a second opinion about what was
   granted, and the two would disagree the first time one of them was wrong.
   Deriving both from one projection makes disagreement unrepresentable rather
   than merely unlikely — there is nothing for the mirror to be wrong ABOUT
   that the schema could be right about.

   WHAT IT IS NOT. It is a record of what the generator was told, not an
   enforcement mechanism. Nothing at run time reads it and refuses a message; a
   group cannot send what its schema cannot express, and THAT is the mechanism.
   The mirror is what lets a reviewer check the mechanism did what the policy
   said."
  (:require [malli.core :as m]
            [protocol-gen.db :as db]
            [protocol-gen.projection :as projection]))

(set! *warn-on-reflection* true)

(def mirror-schema
  "The emitted mirror. `:version` is the mirror FORMAT's version, so a consumer
   can tell which vocabulary produced the file it is holding."
  [:map {:closed true}
   [:version [:= 1]]
   [:groups
    [:map-of :keyword
     [:map {:closed true}
      [:package db/proto-qualified-name]
      [:messages
       [:map-of db/proto-qualified-name
        [:map {:closed true}
         [:proto-name db/proto-identifier]
         [:origin [:enum :descriptor :minted]]
         [:access [:set [:enum :read :write]]]
         [:fields [:map-of db/proto-identifier
                   [:map {:closed true}
                    [:number :int]
                    [:number-source [:enum :descriptor :registry]]]]]]]]
      [:enums [:map-of db/proto-qualified-name
               [:map {:closed true} [:proto-name db/proto-identifier]]]]]]]])

(defn- message-entry
  "One projected message as its mirror entry, keyed by its source id."
  [msg]
  [(:id msg)
   {:proto-name (:proto-name msg)
    :origin (:origin msg)
    :access (:access msg)
    :fields (into (sorted-map)
                  (map (juxt :name #(select-keys % [:number :number-source])))
                  (:fields msg))}])

(defn- group-entry
  "One projected group as its mirror entry, keyed by its policy id."
  [g]
  [(:id g)
   {:package (:package g)
    :messages (into (sorted-map) (map message-entry) (:messages g))
    :enums (into (sorted-map)
                 (map (juxt :id #(select-keys % [:proto-name])))
                 (:enums g))}])

(defn mirror
  "The permission mirror for every projected group.

   Sorted maps throughout, so two runs over the same inputs write byte-identical
   files and a diff of the mirror is a diff of the policy's effect."
  [groups]
  {:version 1
   :groups (into (sorted-map) (map group-entry) groups)})

(m/=> mirror [:=> [:cat [:sequential projection/projected-group]] mirror-schema])
