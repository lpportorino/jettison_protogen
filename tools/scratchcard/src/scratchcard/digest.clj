(ns scratchcard.digest
  "sha256 over strings, bytes and files — ONE implementation.

  Two call sites need this and they need it to AGREE: `scratchcard.scope`
  hashes the repo root into the per-fork key, and `scratchcard.provenance`
  hashes the wasm and the input screen into the run manifest. A second copy of
  the hex loop is where a sign-extension bug lives on one side and not the
  other, and a manifest hash that disagrees with a golden hash is worse than
  no hash at all.

  THE HEX LOOP IS THE SUBTLE PART. A JVM `byte` is SIGNED, so the obvious
  `(format \"%02x\" b)` yields `ffffffc3` for any byte with the high bit set.
  Masking to `0xff` first is what keeps a digest 64 characters wide.

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
  "Lowercase hex of `bytes`.

  Masks each byte to 0xff — see the namespace docstring."
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
