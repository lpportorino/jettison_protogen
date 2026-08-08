(ns protodoc.gencorpus.constraints-test
  "The LIVE constraint extraction, at the one seam where a buf.validate rule can
   be dropped without anything saying so.

   `constraints/extract` whitelists rule fields to the modelled surface, and a
   rule outside it is discarded silently — the field's entry then vanishes
   entirely, because `extract` keeps only fields with a non-empty constraints
   map. That is invisible to every other lane: the generator emits an
   unconstrained value, the ORACLE still enforces the real rule, and the message
   simply stops being able to produce a valid positive. `sweep-test`'s
   `whole-pool-generates-a-valid-positive` is where it surfaces, which reports
   the affected MESSAGES rather than the rule that did it.

   These cases name the rule instead, so a red here says which clause broke."
  (:require [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.constraints :as constraints]))

(set! *warn-on-reflection* true)

(def ^:private binpb "../../../output/json-descriptors/descriptor-set.binpb")

(def ^:private live (delay (constraints/extract binpb)))

(deftest exact-length-expands-to-the-modelled-pair
  (testing "buf.validate's `len` is the conjunction of `min_len` and `max_len`,
            and is EXPANDED into that already-modelled pair rather than carried
            as a `:len` key nothing downstream reads"
    (let [db @live]
      ;; non-vacuity: the extraction ran and found the corpus. Without this a
      ;; collapsed descriptor read would satisfy every assertion below by
      ;; returning nil for each lookup and matching nothing.
      (is (seq db) "extract returned no messages at all")

      (doseq [[msg field] [["jon.cvdump.VideoSegment" "sha256"]
                           ["jon.cvdump.StreamGroup" "decoded_sha256"]]]
        (is (= {:min-len 64 :max-len 64} (get-in db [msg field]))
            (str msg "." field
                 " declares string.len = 64; it must reach the generator as the"
                 " modelled min/max pair, or the field is dropped entirely and"
                 " the message can never generate an oracle-valid positive")))

      ;; CONTROL — this field hand-writes the pair the clause above SYNTHESISES.
      ;; It shares no code path with `len`, so it stays green when that clause
      ;; breaks; a red here instead means the modelled surface itself moved.
      (is (= {:min-len 36 :max-len 36}
             (select-keys (get-in db ["ser.JonGuiDataTrackedObject" "uuid"])
                          [:min-len :max-len]))
          "the explicitly-declared min_len/max_len pair must be unaffected")

      (testing "and no `:len` key survives into a constraints map"
        (is (empty? (for [[msg fields] db
                          [field cs] fields
                          :when (contains? cs :len)]
                      [msg field]))
            "a raw :len reached the constraints map; downstream reads neither
             it nor anything derived from it")))))
