(ns scratchcard.digest
  "sha256 over strings, bytes and files — ONE implementation.

  Two call sites need this and they need it to AGREE: `scratchcard.scope`
  hashes the repo root into the per-fork key, and `scratchcard.provenance`
  hashes the wasm and the input screen into the run manifest. A second copy of
  the hex loop is where a sign-extension bug lives on one side and not the
  other, and a manifest hash that disagrees with a golden hash is worse than
  no hash at all.

  THE HEX LOOP IS THE SUBTLE PART. A JVM `byte` is SIGNED, and widening it to
  an `int` before hexing sign-extends: `(Integer/toHexString b)` on an
  unmasked byte yields `ffffffc3` for any byte with the high bit set, not
  `c3` — exactly the function this loop calls below. TWO steps keep a digest 64
  characters wide, and `Integer/toHexString` supplies neither: the mask to
  `0xff` stops the sign extension, and the explicit zero-pad below
  (`(when (< b 0x10) …)`) restores the leading nibble it drops for any byte
  under `0x10`. Masking alone would still emit `5` for `0x05`.

  FRAMEBUFFER HASHES DELIBERATELY USE `devcards.golden/sha256-hex` INSTEAD —
  not this namespace. That is the function the committed goldens were minted
  with, so routing scratch framebuffers through it is what makes a scratch
  hash directly comparable to a golden. Using this one there would be a
  second implementation of exactly the fact that must not drift."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.io File InputStream)
   (java.security MessageDigest)))

(def ^:private buffer-bytes 65536)

(defn hex
  "Lowercase hex of `bytes`, exactly two characters per byte.

  Masks each byte to 0xff AND zero-pads anything below 0x10 — both steps are
  load-bearing, for the reasons in the namespace docstring."
  ^String [^bytes bs]
  (let [sb (StringBuilder. (* 2 (alength bs)))]
    (dotimes [i (alength bs)]
      (let [b (bit-and (aget bs i) 0xff)]
        (when (< b 0x10) (.append sb \0))
        (.append sb (Integer/toHexString b))))
    (str sb)))

(defn of-bytes
  "sha256 of `bs`, hex."
  ^String [^bytes bs]
  (hex (.digest (MessageDigest/getInstance "SHA-256") bs)))

(defn of-string
  "sha256 of `s` read as UTF-8, hex."
  ^String [^String s]
  (of-bytes (.getBytes s "UTF-8")))

(defn of-stream
  "sha256 of everything readable from `in`, hex. Does not close `in`."
  ^String [^InputStream in]
  (let [md (MessageDigest/getInstance "SHA-256")
        buf (byte-array buffer-bytes)]
    (loop []
      (let [n (.read in buf)]
        (when (pos? n)
          (.update md buf 0 n)
          (recur))))
    (hex (.digest md))))

(defn of-file
  "sha256 of `f`'s contents, hex, or nil when `f` does not exist.

  nil rather than a throw because provenance collection must degrade: a
  missing wasm is a fact the manifest should RECORD, not a reason to lose the
  whole run's stamp."
  [f]
  (let [file (io/file f)]
    (when (.isFile ^File file)
      (with-open [in (io/input-stream file)]
        (of-stream in)))))
