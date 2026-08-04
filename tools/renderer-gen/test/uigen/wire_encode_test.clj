(ns uigen.wire-encode-test
  "Pins the WIRE ENCODERS in `uigen.wire-encode` — the padded varint above all.

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
   [uigen.wire-encode :as we]))

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
      (is (= w (alength (we/padded-varint v w)))
          (str "width " w " value " v)))))

(deftest padded-varint-continuation-bits-mark-every-byte-but-the-last
  (testing "'the low groups carry the value with bit 7 set, the final byte clears bit 7'"
    (doseq [w widths, v values]
      (let [ba (we/padded-varint v w)]
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
      (is (= v (decode-varint (we/padded-varint v w)))
          (str "value " v " at width " w " must decode back to itself")))))

(deftest padded-varint-is-non-minimal
  (testing "'NON-MINIMAL': padding to a wider slot must not shorten the encoding"
    ;; The point of the padding is that a slot's byte width is fixed when the
    ;; template is built, long before the value is known. An encoder that emitted a
    ;; MINIMAL varint would leave the slot's tail bytes untouched, and the command
    ;; would decode with whatever was there before.
    (doseq [v [0 1 127 128]]
      (let [narrow (we/padded-varint v 1)
            wide (we/padded-varint v 5)]
        (is (= 1 (alength narrow)))
        (is (= 5 (alength wide)))
        (is (zero? (bit-and (long (aget wide 4)) 0x80))
            "the terminator must move to the END of the wider slot, not stay at byte 0")))))

(deftest varint-le-bytes-round-trips
  (testing "the minimal encoder decodes to its input"
    (doseq [v [0 1 127 128 300 16383 16384 1000000]]
      (is (= v (decode-varint (we/varint-le-bytes v)))
          (str "value " v)))))

;; ── the locator + the slot-locating loop ────────────────────────────────────
;; These cover the half of this namespace that the encoder tests above do not:
;; finding a sentinel's slot in a template, and turning a producer's slot
;; declarations into FieldPatch descriptors. Every case builds its own template
;; out of the encoders, so nothing here needs a command, a manifest or a leaf
;; table — which is the property the split exists to create.

(defn- template
  "A synthetic template: `parts` (byte arrays) concatenated."
  ^bytes [parts]
  (let [out (byte-array (reduce + (map alength parts)))]
    (loop [at 0 [p & more] parts]
      (when p
        (System/arraycopy ^bytes p 0 out at (alength ^bytes p))
        (recur (+ at (alength ^bytes p)) more)))
    out))

(defn- clause
  "The ex-message of the refusal `f` makes, or nil when it does not refuse."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

(deftest find-slot-returns-the-offset-of-a-unique-needle
  (testing "the passing direction — a locator that refused everything is useless"
    (let [pad (byte-array 3)
          needle (we/double->le-bytes 1.5)]
      (is (= 3 (we/find-slot! (template [pad needle pad]) needle {}))))))

(deftest find-slot-refuses-an-absent-needle
  (testing "a sentinel that is not in the template is a build error"
    (is (re-find #"not found"
                 (clause #(we/find-slot! (byte-array 8) (we/double->le-bytes 1.5) {}))))))

(deftest find-slot-refuses-an-ambiguous-needle
  (testing "a sentinel occurring twice cannot name one slot"
    ;; This is the case that makes per-index form sentinels necessary: two
    ;; same-typed fields sharing a sentinel land here rather than mis-patching.
    (let [needle (we/varint-le-bytes 2147483647)]
      (is (re-find #"ambiguous"
                   (clause #(we/find-slot! (template [needle needle]) needle {})))))))

(deftest slot-patches-carries-the-producers-own-keys-through
  (testing "the loop adds an offset and changes nothing else"
    ;; The `:subject` key is the point: it is a FORM slot's key, this namespace
    ;; has never heard of it, and it must survive verbatim — that is what makes
    ;; the core leaf-table-free rather than merely leaf-table-light.
    (let [pad (byte-array 2)
          n1 (we/double->le-bytes 1.5)
          n2 (we/float->le-bytes 2.5)
          t (template [pad n1 pad n2])]
      (is (= [{:byte-offset 2 :byte-width 8 :kind :K1 :subject "s1"}
              {:byte-offset 12 :byte-width 4 :kind :K2 :subject "s2"}]
             (we/slot-patches
              t
              [{:field "a" :needle n1 :patch {:byte-width 8 :kind :K1 :subject "s1"}}
               {:field "b" :needle n2 :patch {:byte-width 4 :kind :K2 :subject "s2"}}]
              {:command-id "cmd.Test"}))))))

(deftest slot-patches-preserves-declaration-order
  (testing "declaration order is the producer's contract with its own emitter"
    ;; Ordering by byte offset would look tidier and would silently rewrite the
    ;; index a widget's cmd_by_value / a form's slot list is selected by.
    (let [pad (byte-array 2)
          early (we/double->le-bytes 1.5)
          late (we/double->le-bytes 2.5)
          t (template [pad early pad late])]
      (is (= [{:byte-offset 12 :byte-width 8} {:byte-offset 2 :byte-width 8}]
             (we/slot-patches t
                              [{:field "late" :needle late :patch {:byte-width 8}}
                               {:field "early" :needle early :patch {:byte-width 8}}]
                              {}))))))

(deftest slot-patches-refuses-a-declaration-that-already-carries-an-offset
  (testing "two homes for the offset can disagree, so one of them is refused"
    (let [n (we/double->le-bytes 1.5)]
      (is (re-find #"already carries :byte-offset"
                   (clause #(we/slot-patches
                             n
                             [{:field "a" :needle n :patch {:byte-offset 0 :byte-width 8}}]
                             {})))))))

(deftest slot-patches-names-the-field-and-the-context-in-a-refusal
  (testing "a refusal identifies the command AND the leaf, not just 'a slot'"
    (let [e (try (we/slot-patches (byte-array 8)
                                  [{:field "latitude"
                                    :needle (we/double->le-bytes 1.5)
                                    :patch {:byte-width 8}}]
                                  {:command-id "cmd.Gps.SetManualPosition"})
                 nil
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= {:command-id "cmd.Gps.SetManualPosition" :field "latitude" :template-len 8}
             e)))))
