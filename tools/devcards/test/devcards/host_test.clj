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

;; ── dump-draw-palette!: what ABSENCE means ──────────────────────────────────
;; These pin the two guard paths and NOTHING ELSE. The module path — a real
;; wasm, a real export, real bytes copied out of linear memory — is exercised
;; by no test here, because nothing consumes this fn yet; the probe that will
;; is the remaining half of the work. Saying so is the point: a reader must not
;; take these greens as evidence the export was ever successfully called.

(deftest dump-draw-palette-returns-nil-ONLY-for-a-module-without-the-export
  (testing "a wasm that predates the observer is a real state — a consumer pins
            its own build — and NIL is how it is reported, so the palette rule
            reaches its :observer-not-exposed reason and says out loud that it
            could not look"
    (is (nil? (host/dump-draw-palette!
               {:export? (constantly false)
                :call! (fn [& _]
                         (throw (AssertionError.
                                 "must not call an export it just found absent")))}))))
  (testing "the control: the probe is CONSULTED rather than ignored. Without
            this, a fn hard-wired to return nil would satisfy the assertion
            above while reporting every module as unobserved."
    (let [asked (atom [])]
      (try
        (host/dump-draw-palette! {:export? (fn [n] (swap! asked conj n) false)})
        (catch Exception _ nil))
      (is (= [host/draw-palette-export] @asked)))))

(deftest dump-draw-palette-THROWS-when-the-host-map-cannot-answer
  (testing "a host map with no :export? probe is a WIRING defect in the caller,
            not an answer about the module. Returning nil there would launder a
            broken caller into 'the observer is absent' and quietly mark the
            whole corpus unobserved — the one failure this repo refuses, a green
            that is indistinguishable from never having looked."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no :export\? probe"
         (host/dump-draw-palette! {:call! (constantly nil)})))))

(deftest utf8-string-decodes-multi-byte-glyphs-rather-than-one-char-per-byte
  (testing "a DEGREE SIGN survives. The decoder used to append one char per
            byte — Latin-1 — so U+00B0 (UTF-8 c2 b0) became the two chars
            U+00C2 U+00B0 and was written back out as c3 82 c2 b0. That is the
            mangling measured in a consumer's generated pages, and this is the
            assertion that goes red if the byte loop reverts."
    (is (= "°" (host/utf8-string (byte-array [(unchecked-byte 0xc2)
                                              (unchecked-byte 0xb0)])))))
  (testing "and so does a PRIVATE-USE-AREA icon codepoint, which is the case
            that actually reaches an operator: the icon font's warning triangle
            U+F071 (UTF-8 ef 81 b1) became c3 af c2 81 c2 b1 and rendered in the
            page as a three-character mojibake before the label text."
    (is (= "" (host/utf8-string (byte-array [(unchecked-byte 0xef)
                                              (unchecked-byte 0x81)
                                              (unchecked-byte 0xb1)])))))
  (testing "CONTROL — ASCII is a FIXED POINT of the old bug, which is why it
            survived unnoticed. Without this case the two above could be
            satisfied by a decoder that mangles ordinary labels instead, and
            every label this corpus asserts on is ASCII."
    (is (= "Turn Off" (host/utf8-string (.getBytes "Turn Off" "UTF-8")))))
  (testing "the empty buffer is the empty string, not nil — read-cstring hits
            this on any node whose text is absent."
    (is (= "" (host/utf8-string (byte-array 0))))))
