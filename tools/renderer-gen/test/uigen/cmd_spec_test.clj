(ns uigen.cmd-spec-test
  "Pins the WIRE ENCODERS in `uigen.cmd-spec` — the padded varint above all.

  WHY THIS FILE EXISTS, because the gap it closes is not the obvious one.
  `padded-varint` is one half of a CROSS-LANGUAGE MIRROR: `cmd_patch_padded_varint`
  in `renderer/src/cmd_patch.c` implements the identical encoding, and that C
  function's own header says it mirrors this one EXACTLY. Nothing asserted either
  half, and nothing asserts they agree — the mirror was stated in prose only.

  THE THREE WAYS IT WAS INVISIBLE, all at once, which is why it survived:

    1. It has NO CALLER in Clojure. It is the reference definition of an encoding
       the C side performs, so no Clojure path exercises it and no coverage number
       could ever have flagged it.
    2. Its `m/=>` spec is DECORATIVE. The fn is primitive-hinted
       (`^bytes [^long v ^long width]`) and malli REFUSES to instrument those, so
       arming instrumentation checks it exactly as much as not arming it does —
       nothing. It is one of seven such functions in this tree.
    3. The C half is `extern` and its header says `Exposed for unit reach`, but no
       test reaches it. `controls_cmd_patch_probe` covers the neighbouring
       SLOT-BOUNDS check and not the encoding.

  So the bytes this function defines are produced on every build, land in a `.pb`,
  and get canonised by a golden hash — which catches CHANGE and never
  CORRECTNESS. A first mint of a wrong encoding would be green forever.

  ASSERTED FROM THE DOCUMENTED CONTRACT, NOT FROM THE CURRENT OUTPUT. Every
  property below is derivable from `padded-varint`'s own docstring without running
  it, which is what makes this a test rather than a snapshot of today's behaviour.
  A golden vector copied from a run would agree with the code by construction and
  could not fail.

  WHAT THIS STILL DOES NOT COVER, said plainly so a green is not over-read: it
  pins the CLOJURE half. The C half is not reachable from any test in this
  repository — it is neither a wasm export nor behind a probe — so the mirror
  itself remains unasserted. Closing it needs `cmd_patch_padded_varint` exported,
  or a probe entry point that returns its bytes."
  (:require
   [clojure.test :refer [deftest is testing]]
   [uigen.cmd-spec :as cs]))

(def widths
  "Widths to exercise. 1 is the degenerate case where the first byte is also the
  last, so the continuation-bit rule and the terminator rule apply to one byte."
  [1 2 3 5 10])

(def values
  "Values spanning one group, a group boundary, and multi-group fan-out. 127 and
  128 straddle the 7-bit boundary, which is where an off-by-one in the shift or
  the mask shows up."
  [0 1 2 126 127 128 129 255 300 16383 16384 1000000])

(defn- decode-varint
  "Decode a little-endian base-128 varint from `bytes`, ignoring padding.

  Written INDEPENDENTLY of the encoder rather than by reusing its internals: a
  round-trip through two halves of the same implementation proves only
  self-consistency, and would pass with the shift width wrong in both."
  [^bytes ba]
  (loop [i 0 shift 0 acc 0]
    (if (>= i (alength ba))
      acc
      (let [b (bit-and (long (aget ba i)) 0xff)
            acc' (+ acc (bit-shift-left (bit-and b 0x7f) shift))]
        (if (zero? (bit-and b 0x80))
          acc'
          (recur (inc i) (+ shift 7) acc'))))))

(deftest padded-varint-is-exactly-width-bytes
  (testing "the docstring's 'padded to exactly `width` bytes'"
    (doseq [w widths, v values]
      (is (= w (alength (cs/padded-varint v w)))
          (str "width " w " value " v)))))

(deftest padded-varint-continuation-bits-mark-every-byte-but-the-last
  (testing "'the low groups carry the value with bit 7 set, the final byte clears bit 7'"
    (doseq [w widths, v values]
      (let [ba (cs/padded-varint v w)]
        (doseq [i (range (dec w))]
          (is (= 0x80 (bit-and (long (aget ba i)) 0x80))
              (str "byte " i " of width " w " value " v " must set bit 7")))
        (is (zero? (bit-and (long (aget ba (dec w))) 0x80))
            (str "final byte of width " w " value " v " must clear bit 7"))))))

(deftest padded-varint-is-value-identity
  (testing "'a padded varint is valid wire and decodes to `v` (value-identity)'"
    ;; Only where the value FITS: `width` groups carry 7 bits each, so a value
    ;; needing more groups than the width provides is truncated by construction and
    ;; value-identity cannot hold. Asserting it there would be asserting against the
    ;; documented contract rather than for it.
    (doseq [w widths, v values
            :when (< v (bit-shift-left 1 (* 7 w)))]
      (is (= v (decode-varint (cs/padded-varint v w)))
          (str "value " v " at width " w " must decode back to itself")))))

(deftest padded-varint-is-non-minimal
  (testing "'NON-MINIMAL': padding to a wider slot must not shorten the encoding"
    ;; The point of the padding is that a slot's byte width is fixed when the
    ;; template is built, long before the value is known. An encoder that emitted a
    ;; MINIMAL varint would leave the slot's tail bytes untouched, and the command
    ;; would decode with whatever was there before.
    (doseq [v [0 1 127 128]]
      (let [narrow (cs/padded-varint v 1)
            wide (cs/padded-varint v 5)]
        (is (= 1 (alength narrow)))
        (is (= 5 (alength wide)))
        (is (zero? (bit-and (long (aget wide 4)) 0x80))
            "the terminator must move to the END of the wider slot, not stay at byte 0")))))

(deftest varint-le-bytes-round-trips
  (testing "the minimal encoder decodes to its input"
    (doseq [v [0 1 127 128 300 16383 16384 1000000]]
      (is (= v (decode-varint (cs/varint-le-bytes v)))
          (str "value " v)))))
