(ns protodoc.gencorpus.pool-test
  "Pool / serialize layer: a descriptor pool built from the committed
   descriptor-set.binpb (NO gencode) serializes ANY message from an EDN value
   map, and the wire round-trips faithfully — including the unsigned upper
   halves and arbitrary octets the old bare-:string/bare-:int path corrupted."
  (:require [clojure.test :refer [deftest is testing]]
            [protodoc.gencorpus.pool :as pool])
  (:import [com.google.protobuf
            ByteString
            Descriptors$Descriptor
            Descriptors$EnumValueDescriptor
            Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType]))

(def ^:private binpb-path
  "../../../output/json-descriptors/descriptor-set.binpb")

(def ^:private pool* (delay (pool/load-pool binpb-path)))

(defn- get-field [^Descriptors$Descriptor d ^bytes wire fname]
  (let [f (Descriptors$Descriptor/.findFieldByName d fname)]
    (.getField (pool/reparse d wire) f)))

(deftest pool-resolves-the-whole-schema-from-binpb-alone
  (testing "the descriptor pool resolves every message from the binpb with no gencode"
    (is (<= 400 (count @pool*))
        (str "expected the full schema in the pool; got " (count @pool*)))
    (is (get @pool* "ser.JonGUIState"))
    (is (get @pool* "cmd.Root"))))

(deftest doubles-round-trip
  (testing "ser.JonGuiDataGps doubles serialize + reparse faithfully"
    (let [d (get @pool* "ser.JonGuiDataGps")
          wire (pool/->bin (pool/build-msg d {"latitude" 51.4778 "longitude" -0.0014}))]
      (is (pos? (alength wire)))
      (is (= 51.4778 (get-field d wire "latitude")))
      (is (= -0.0014 (get-field d wire "longitude"))))))

(deftest unsigned-64-two-complement-round-trips
  (testing "a uint64/int64 LONG field carries a full-width value via two's-complement"
    (let [;; find any LONG-typed field anywhere to exercise the .longValue path
          [full-name f]
          (first (for [[mname d] @pool*
                       ^Descriptors$FieldDescriptor fld (Descriptors$Descriptor/.getFields d)
                       :when (= Descriptors$FieldDescriptor$JavaType/LONG
                                (Descriptors$FieldDescriptor/.getJavaType fld))]
                   [mname fld]))]
      (is (some? f) "schema has at least one 64-bit integer field")
      (let [d (get @pool* full-name)
            fname (Descriptors$FieldDescriptor/.getName f)
            ;; 2^64-1 as a BigInt — no positive Java long; must wrap to -1
            wire (pool/->bin (pool/build-msg d {fname 18446744073709551615N}))
            back (get-field d wire fname)]
        ;; protobuf-java returns the signed long view; -1 IS the 2^64-1 bit pattern
        (is (= -1 back)
            (str full-name "/" fname " uint64-max round-trips to the -1 bit pattern"))))))

(deftest bytes-round-trip-arbitrary-octets
  (testing "a bytes field carries arbitrary octets (NUL, 0xFF) — not base64"
    (when-let [d (get @pool* "ser.JonOpaquePayload")]
      (when (Descriptors$Descriptor/.findFieldByName d "payload")
        (let [octets [0 255 16 0 127 128]
              wire (pool/->bin (pool/build-msg d {"payload" octets}))
              ^Descriptors$FieldDescriptor f (Descriptors$Descriptor/.findFieldByName d "payload")
              ^ByteString back (.getField (pool/reparse d wire) f)]
          (is (= octets (mapv #(bit-and (long %) 0xff) (ByteString/.toByteArray back)))
              "octets survive byte-exact (the base64 path would mangle them)"))))))

(deftest enum-set-by-number
  (testing "an enum field set by NUMBER round-trips to that number (no name-drop)"
    (let [[full-name f]
          (first (for [[mname d] @pool*
                       ^Descriptors$FieldDescriptor fld (Descriptors$Descriptor/.getFields d)
                       :when (and (= Descriptors$FieldDescriptor$JavaType/ENUM
                                     (Descriptors$FieldDescriptor/.getJavaType fld))
                                  (not (Descriptors$FieldDescriptor/.isRepeated fld)))]
                   [mname fld]))]
      (is (some? f) "schema has at least one singular enum field")
      (let [d (get @pool* full-name)
            fname (Descriptors$FieldDescriptor/.getName f)
            enum-num 1
            wire (pool/->bin (pool/build-msg d {fname enum-num}))
            ^Descriptors$FieldDescriptor fld (Descriptors$Descriptor/.findFieldByName d fname)
            ^Descriptors$EnumValueDescriptor back (.getField (pool/reparse d wire) fld)]
        (is (= enum-num (Descriptors$EnumValueDescriptor/.getNumber back)))))))
