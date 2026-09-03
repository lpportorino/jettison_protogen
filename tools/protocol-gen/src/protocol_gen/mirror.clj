(ns protocol-gen.mirror
  "The machine-readable PERMISSION TRANSCRIPT, derived from the same projection
   the `.proto` text is derived from.

   THE ARTEFACT IS THE TRANSCRIPT; THE WORD MIRROR NAMES THE TREE. This
   namespace emits a FLAT EDN file and `protocol-gen.permission-tree` emits a
   NESTED Rust tree; two artefacts under one name leave a reader sent to `the
   permission mirror` unable to tell which is meant, so MIRROR is the tree's
   alone and the flat one is the TRANSCRIPT.

   THE IDENTIFIERS DO NOT MATCH THAT SPLIT, and this file is where a reader
   meets the mismatch: it is `mirror.clj` and the artefact it describes is not
   a mirror. The namespace, the public function and the emitted file name are
   referenced by a consumer, so moving them is a coordinated change on both
   sides rather than something this docstring can do.

   WHY IT EXISTS. Two facts a group needs cannot live in a `.proto` file at
   all. The first is DIRECTION: proto describes a shape, and `may send` versus
   `may receive` is not a shape. The second is PROVENANCE: the emitted file
   says a field is number 7 and cannot say whether 7 came from a descriptor or
   from the assign-once registry.

   WHY IT IS EMITTED FROM THE SAME RUN AND THE SAME VALUE. A transcript
   produced by a second pass over the policy would be a second opinion about
   what was granted, and the two would disagree the first time one of them was
   wrong. Deriving both from one projection makes disagreement unrepresentable
   rather than merely unlikely — there is nothing for the transcript to be
   wrong ABOUT that the schema could be right about.

   WHAT IT IS NOT. It is a record of what the generator was told, not an
   enforcement mechanism: nothing at run time reads this file and refuses a
   message.

   AND THE SCHEMA IS NOT THE MECHANISM EITHER. The reading to refuse is `a
   group cannot send what its schema cannot express, and THAT is the mechanism`
   — the obvious one to reach for, and false. A schema constrains what an
   HONEST client CONSTRUCTS. Bytes on the wire carry no trace of the generated
   code that produced them, so a peer that ignores its own schema, or was never
   built from one, is unconstrained by it — and a receiver that trusted the
   schema to have filtered its input would be enforcing nothing at all.

   WHERE ENFORCEMENT LIVES: on the RECEIVE side, and over the NESTED tree
   `protocol-gen.permission-tree` emits from this same projection. A receiver
   walks encoded bytes tag by tag against that tree and refuses any tag the
   tree does not describe. That is why the tree is TOTAL over the fields a
   source message declares, and why an undescribed tag is a refusal there
   rather than a gap. This transcript describes nothing a scanner can walk and
   enforces nothing; it is what lets a reviewer check that what the run emitted
   is what the policy said."
  (:require [malli.core :as m]
            [protocol-gen.db :as db]
            [protocol-gen.projection :as projection]))

(set! *warn-on-reflection* true)

(def mirror-schema
  "The emitted transcript. `:version` is the transcript FORMAT's version, so a
   consumer can tell which vocabulary produced the file it is holding."
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
  "One projected message as its transcript entry, keyed by its source id."
  [msg]
  [(:id msg)
   {:proto-name (:proto-name msg)
    :origin (:origin msg)
    :access (:access msg)
    :fields (into (sorted-map)
                  (map (juxt :name #(select-keys % [:number :number-source])))
                  (:fields msg))}])

(defn- group-entry
  "One projected group as its transcript entry, keyed by its policy id."
  [g]
  [(:id g)
   {:package (:package g)
    :messages (into (sorted-map) (map message-entry) (:messages g))
    :enums (into (sorted-map)
                 (map (juxt :id #(select-keys % [:proto-name])))
                 (:enums g))}])

(defn mirror
  "The permission transcript for every projected group.

   Sorted maps throughout, so two runs over the same inputs write byte-identical
   files and a diff of the transcript is a diff of the policy's effect."
  [groups]
  {:version 1
   :groups (into (sorted-map) (map group-entry) groups)})

(m/=> mirror [:=> [:cat [:sequential projection/projected-group]] mirror-schema])
