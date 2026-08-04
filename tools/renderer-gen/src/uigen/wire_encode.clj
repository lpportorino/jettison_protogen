(ns uigen.wire-encode
  "The wire-encode core of the cmd-out pre-encode: the byte encoders, the
   sentinel locator, and the slot-locating loop — WITHOUT any leaf table.

   ═══ WHAT IT IS FOR ═══

   The pre-encode splits cleanly in two, and only one half is invariant.

   The INVARIANT half is here: how a protobuf `double`/`float`/varint leaf is
   written into a fixed-width slot, how a slot is FOUND in a template by its
   sentinel byte pattern, and what a located slot's descriptor looks like. All
   of it is the wire's own arithmetic; none of it depends on which commands a
   producer sends or on what it writes into a leaf to make the slot findable.
   `renderer/src/cmd_patch.c` implements the same encodings on the runtime side,
   so this half is one arm of a CROSS-LANGUAGE MIRROR.

   The DIVERGENT half is the LEAF TABLE — which proto fields are patchable, what
   sentinel value marks each one, and which `PatchKind`/`PatchEncoding` the slot
   carries. That stays with the producer, because it is exactly what two
   producers of the same vocabulary are allowed to disagree about.

   ═══ WHY NO SENTINEL LIVES HERE, AND WHY THAT IS LOAD-BEARING ═══

   A sentinel is a LOCATOR, not a value: it is written into the template only so
   its byte pattern can be searched for, and the runtime patcher overwrites
   every located slot before the bytes reach a device. Its value is therefore
   free in one axis and pinned in another — it must be UNIQUE within a template
   (`find-slot!` refuses an ambiguous needle), and for a varint leaf it must
   encode to the FULL slot width, so the slot and every enclosing length prefix
   are pre-sized for the widest patch that can land in them.

   That freedom is why two producers can legitimately pick different sentinels
   for the same leaf, and why a shared core that supplied a DEFAULT table would
   be a trap: it would look like a convenience and would silently move a
   producer's template bytes the first time it was relied on. So this namespace
   has no table, no default, and no opinion about which value marks what. A
   caller passes the needle it computed from its own table, or it gets nothing.

   Read `uigen.cmd-spec` for one such table, including the two divergences it
   maintains deliberately WITHIN itself: widget/gesture leaves are keyed by
   FIELD NAME (so an ROI's corner-1 leaves share the single-point x/y
   sentinels), while form leaves are keyed by INDEX (because a form carries many
   same-typed fields that must differ by construction)."
  (:require [asgard.schema :as s]
            [malli.core :as m])
  (:import [java.nio ByteBuffer ByteOrder]))

(set! *warn-on-reflection* true)

;; ── scalar wire encoders ────────────────────────────────────────────────────

(defn varint-le-bytes
  "The MINIMAL little-endian varint bytes of a non-negative int — used to
   LOCATE a varint sentinel slot (the minimal encoding is what the protobuf
   serializer wrote into the template)."
  ^bytes [^long v]
  (loop [v v
         acc []]
    (let [b (bit-and v 0x7f)
          v' (unsigned-bit-shift-right v 7)]
      (if (zero? v')
        (byte-array (conj acc (unchecked-byte b)))
        (recur v' (conj acc (unchecked-byte (bit-or b 0x80))))))))
(m/=> varint-le-bytes [:=> [:cat :int] bytes?])

(defn padded-varint
  "Encode non-negative `v` as a NON-MINIMAL varint padded to exactly `width`
   bytes: the low groups carry the value with bit 7 set (continuation), the
   final byte clears bit 7. A padded varint is valid wire and decodes to `v`
   (value-identity). This mirrors what the renderer's C patcher writes into the
   slot."
  ^bytes [^long v ^long width]
  (let [out (byte-array width)]
    (loop [i 0
           r v]
      (when (< i width)
        (let [last? (= i (dec width))
              group (bit-and r 0x7f)
              b (if last? group (bit-or group 0x80))]
          (aset out i (unchecked-byte b))
          (recur (inc i) (unsigned-bit-shift-right r 7)))))
    out))
(m/=> padded-varint [:=> [:cat :int :int] bytes?])

(defn double->le-bytes
  "The 8 little-endian wire bytes of a protobuf double (the on-wire form of a
   fixed64/double field), for locating a sentinel slot AND for the patcher that
   writes a double verbatim into a slot."
  ^bytes [^double d]
  (let [bb (ByteBuffer/allocate 8)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putDouble bb d)
    (.array bb)))
(m/=> double->le-bytes [:=> [:cat :double] bytes?])

(defn float->le-bytes
  "The 4 little-endian wire bytes of a protobuf float (wire-type 5, fixed32) —
   the float counterpart of double->le-bytes, for locating a form's float slot.
   Mirrors the renderer's float_le_bytes (cmd_patch.c)."
  ;; NOT a ^float param hint: Clojure supports only long/double primitive hints
  ;; on a fn arg, so the cast happens at the putFloat call instead.
  ^bytes [f]
  (let [bb (ByteBuffer/allocate 4)]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (.putFloat bb (float f))
    (.array bb)))
(m/=> float->le-bytes [:=> [:cat number?] bytes?])

(defn byte-len
  "Byte-array length via a `^bytes` PARAM — kondo's array check trusts a typed
   param but not a `^bytes`-hinted local bound from a serializer call (whose
   return it infers as a char sequence). Mirrors the index-of-bytes helper
   pattern."
  ^long [^bytes b]
  (alength b))
(m/=> byte-len [:=> [:cat bytes?] :int])

;; ── sentinel locator ────────────────────────────────────────────────────────

(defn- index-of-bytes
  "First index where `needle` occurs in `hay`, or nil. The sentinel locator —
   the byte-offset of a leaf's fixed-width slot in the pre-encoded template."
  [^bytes hay ^bytes needle]
  (let [hn (alength hay)
        nn (alength needle)]
    (loop [i 0]
      (when (<= i (- hn nn))
        (if (loop [j 0]
              (cond (= j nn) true
                    (= (aget hay (+ i j)) (aget needle j)) (recur (inc j))
                    :else false))
          i
          (recur (inc i)))))))
(m/=> index-of-bytes [:=> [:cat bytes? bytes?] [:maybe nat-int?]])

(defn find-slot!
  "Locate `needle`'s unique fixed-width slot in `template`, fail-loud. A
   sentinel that is absent (the leaf field name is wrong) or ambiguous (the
   sentinel pattern collides) is a build error, not a silent mis-patch."
  [^bytes template ^bytes needle ctx]
  (let [first-idx (index-of-bytes template needle)]
    (when-not first-idx
      (throw (ex-info "uigen.wire-encode: sentinel slot not found in template"
                      (assoc ctx :template-len (alength template)))))
    ;; ambiguity guard: the sentinel must occur exactly once
    (let [after (inc first-idx)
          rest-from (byte-array (- (alength template) after))]
      (System/arraycopy template after rest-from 0 (alength rest-from))
      (when (index-of-bytes rest-from needle)
        (throw (ex-info "uigen.wire-encode: sentinel slot is ambiguous (collides)"
                        (assoc ctx :first-index first-idx)))))
    first-idx))
(m/=> find-slot! [:=> [:cat bytes? bytes? [:map-of :keyword :any]] nat-int?])

;; ── the slot-locating loop ──────────────────────────────────────────────────

(def slot-declaration
  "One slot a producer declares, as `slot-patches` takes it.

   `:needle` is the sentinel's WIRE BYTES — already encoded by the caller from
   its own leaf table, which is what keeps every sentinel value out of this
   namespace. `:field` reaches error messages only. `:patch` is carried through
   VERBATIM into the emitted descriptor: this loop adds a `:byte-offset` and
   changes nothing else, so a producer that needs a key this core has never
   heard of (a form slot's `:subject`, say) simply puts it there."
  [:map {:closed true}
   [:field s/ne-string]
   [:needle bytes?]
   [:patch [:map-of :keyword :any]]])

(defn slot-patches
  "Locate every declared slot in `template` and return its FieldPatch
   descriptor, in DECLARATION ORDER.

   The leaf table is the caller's: this takes the already-encoded needles and
   never asks what they mean. Declaration order is preserved because a
   producer's order is a contract with its own emitter, and re-ordering by byte
   offset would silently rewrite it.

   `ctx` is merged into every refusal so a failure names the command, not just
   the field. A declaration that already carries a `:byte-offset` is REFUSED
   rather than overwritten: the offset is this loop's to compute, and two homes
   for it can disagree with nothing to say which won."
  [^bytes template slots ctx]
  (mapv (fn [{:keys [field needle patch]}]
          (when (contains? patch :byte-offset)
            (throw (ex-info (str "uigen.wire-encode: slot declaration for " field
                                 " already carries :byte-offset — that is this"
                                 " loop's to compute")
                            (assoc ctx :field field))))
          ;; `:byte-offset` FIRST, then the caller's own keys in their own
          ;; order: the result stays an array-map, so a producer's descriptor
          ;; reads the same way it did when the offset was assoc'd inline.
          (into {:byte-offset (find-slot! template needle (assoc ctx :field field))}
                patch))
        slots))
(m/=> slot-patches
      [:=> [:cat bytes? [:sequential slot-declaration] [:map-of :keyword :any]]
       [:vector [:map-of :keyword :any]]])
