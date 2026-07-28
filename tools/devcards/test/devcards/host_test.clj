(ns devcards.host-test
  "Canaries for the parts of `devcards.host` that do not need a live module.

   Only one thing here, and it is here for a coverage reason rather than a
   complexity one. `host/normalize-dump` is the membrane that turns a
   renderer-buffer overflow into data — without it, structurally cut JSON
   reaches the parser and the run dies before any lane can report
   :dump-truncated. Its only OTHER gate is the `dump-contracts` probe, which
   needs a built module and therefore runs in the renderer workflow; that
   workflow's `paths:` filter does not name `tools/devcards/**`. So a commit
   that deleted the clause and touched nothing outside this directory would
   start no workflow that runs the probe, and no corpus card overruns the
   buffer, so the corpus could not notice either.

   This suite runs under `devcards-test`, which the devcards workflow DOES run
   on exactly that push."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [devcards.host :as host]))

(def ^:private sentinel ",\"truncated\":true")

(deftest normalize-dump-substitutes-a-canonical-root-for-the-overflow-sentinel
  (testing "the renderer appends the sentinel over the TAIL of already-cut
            JSON, so the input is not parseable and the substitution — not the
            parser — is what has to notice"
    (let [cut (str "{\"type\":\"lv_obj\",\"children\":[{\"type\":\"lv_lab" sentinel)]
      (is (= "{\"truncated\":true,\"children\":[]}" (host/normalize-dump cut)))))
  (testing "and the output PARSES, which is the actual contract — the whole
            point is that the raw bytes do not. Asserting merely that the text
            `\"truncated\":true` appears would pass with the membrane deleted,
            since the sentinel the renderer appends contains that very string."
    (let [cut (str "{\"type\":\"lv_obj\",\"children\":[{\"type\":\"lv_lab" sentinel)]
      (is (thrown? Exception (json/read-str cut))
          "precondition: the raw dump is not parseable, so a pass-through
           membrane cannot satisfy the next assertion")
      (is (= {"truncated" true "children" []}
             (json/read-str (host/normalize-dump cut)))))))

(deftest normalize-dump-passes-an-untruncated-dump-through-BYTE-IDENTICAL
  (testing "the control. Without it a membrane hard-wired to return the
            canonical root would satisfy every assertion above while
            discarding every real tree in the corpus."
    (let [whole "{\"type\":\"lv_obj\",\"coords\":[0,0,99,99],\"children\":[]}"]
      (is (= whole (host/normalize-dump whole)))))
  (testing "a dump that merely CONTAINS the sentinel text without ending in it
            is not truncated — the check is anchored at the end on purpose,
            because a label's own text could otherwise trip it"
    (let [decoy (str "{\"type\":\"lv_label\",\"text\":\"" sentinel "\",\"children\":[]}")]
      (is (= decoy (host/normalize-dump decoy))))))
