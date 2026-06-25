(ns protodoc.gencorpus.pool
  "Codec-agnostic proto serialize off a committed FileDescriptorSet — NO compiled
   gencode, NO per-message classes. The KEY to project-neutrality.

   `build-pool` parses `descriptor-set.binpb` (the protogen-committed
   FileDescriptorSet) and topologically builds every FileDescriptor (deps before
   dependents), yielding `full-message-name → Descriptor`. `build-msg` turns an
   EDN value map into a `DynamicMessage` by setting each field on its
   FieldDescriptor; `->bin` is the wire `.bin`. Any of the ~420 messages
   resolvable from the binpb alone serializes this way.

   The EDN value shapes this layer consumes (produced by the assembler):
   - scalar  → number / boolean / string
   - enum    → the enum NUMBER (an int). Set by NUMBER on the builder — NOT a
               JsonFormat name parse — so an unknown name can never SILENTLY drop
               the field (the gap a name-based path hides), and the violating
               corpus can inject an out-of-set number explicitly.
   - bytes   → a vector of octets [0..255], set as a ByteString (base64 is never
               involved — a generated string would mis-encode or crash).
   - repeated→ a vector of element values (each per the element type above).
   - message → a nested EDN value map (recursively built).

   Integer coercion mirrors the wire's two's-complement: a uint32 in
   (INT32_MAX, UINT32_MAX] and a uint64 in (INT64_MAX, 2^64-1] have no positive
   Java int/long, so they wrap to the negative bit pattern the unsigned wire
   encodes — `unchecked-int` / `BigInteger.longValue`, never `(int …)`/`(long …)`
   which throw on those legitimate unsigned values."
  (:require [clojure.java.io :as io])
  (:import [com.google.protobuf
            ByteString
            DescriptorProtos$FileDescriptorSet
            DescriptorProtos$FileDescriptorProto
            Descriptors$Descriptor
            Descriptors$EnumDescriptor
            Descriptors$FieldDescriptor
            Descriptors$FieldDescriptor$JavaType
            Descriptors$FileDescriptor
            DynamicMessage
            DynamicMessage$Builder
            Message]))

(set! *warn-on-reflection* true)

;; ── Descriptor pool (binpb → full-name → Descriptor) ──────────────────

(defn- nested-descs
  "A descriptor and all its (transitively) nested message types."
  [^Descriptors$Descriptor d]
  (cons d (mapcat nested-descs (Descriptors$Descriptor/.getNestedTypes d))))

(defn build-pool
  "Parse a FileDescriptorSet `.binpb` (bytes) and build a
   `full-message-name → Descriptors$Descriptor` map. Every FileDescriptor is
   built topologically (its dependencies first) with NO compiled gencode."
  [^bytes binpb-bytes]
  (let [fdset (DescriptorProtos$FileDescriptorSet/parseFrom binpb-bytes)
        proto-by-name (into {} (for [^DescriptorProtos$FileDescriptorProto fp
                                     (DescriptorProtos$FileDescriptorSet/.getFileList fdset)]
                                 [(DescriptorProtos$FileDescriptorProto/.getName fp) fp]))
        built (atom {})]
    (letfn [(build [fname]
              (or (@built fname)
                  (let [^DescriptorProtos$FileDescriptorProto fp (proto-by-name fname)
                        deps (into-array Descriptors$FileDescriptor
                                         (map build (DescriptorProtos$FileDescriptorProto/.getDependencyList fp)))
                        fd (Descriptors$FileDescriptor/buildFrom fp deps)]
                    (swap! built assoc fname fd)
                    fd)))]
      (let [fds (mapv build (keys proto-by-name))]
        (into {} (for [^Descriptors$FileDescriptor fd fds
                       ^Descriptors$Descriptor md (mapcat nested-descs
                                                          (Descriptors$FileDescriptor/.getMessageTypes fd))]
                   [(Descriptors$Descriptor/.getFullName md) md]))))))

(defn load-pool
  "Read a `descriptor-set.binpb` from `path` and build the descriptor pool."
  [path]
  (with-open [in (io/input-stream (io/file path))]
    (build-pool (.readAllBytes in))))

;; ── EDN value → Java field value ──────────────────────────────────────

(defn ^:private octet-bytes
  "A vector of octets [0..255] → a ByteString (unchecked-byte wraps 128..255 to
   the negative Java byte the wire carries)."
  ^ByteString [octets]
  (ByteString/copyFrom (byte-array (map unchecked-byte octets))))

(defn- coerce-scalar
  "Coerce an EDN scalar to the Java value its FieldDescriptor JavaType expects.
   INT/LONG use two's-complement truncation so the unsigned upper halves carry
   through; FLOAT narrows to 32-bit (the wire width)."
  [^Descriptors$FieldDescriptor$JavaType jt v]
  (condp = jt
    Descriptors$FieldDescriptor$JavaType/INT (unchecked-int v)
    Descriptors$FieldDescriptor$JavaType/LONG (.longValue (biginteger v))
    ;; (float v) range-checks and THROWS on ±Inf, but a float field can carry
    ;; ±Inf on the wire (a valid IEEE-754 value the violating corpus injects),
    ;; so map an infinite double to the float infinity directly. NaN passes
    ;; through (float) unchecked.
    Descriptors$FieldDescriptor$JavaType/FLOAT
    (let [d (double v)]
      (if (Double/isInfinite d)
        (if (pos? d) Float/POSITIVE_INFINITY Float/NEGATIVE_INFINITY)
        (float d)))
    Descriptors$FieldDescriptor$JavaType/DOUBLE (double v)
    Descriptors$FieldDescriptor$JavaType/BOOLEAN (boolean v)
    Descriptors$FieldDescriptor$JavaType/STRING (str v)
    (throw (ex-info "unhandled scalar JavaType" {:javatype (str jt) :value v}))))

(declare build-msg)

(defn- field-value
  "The Java value to set/add for ONE field element `v` (a singular value, or one
   element of a repeated field)."
  [^Descriptors$FieldDescriptor f v]
  (let [jt (Descriptors$FieldDescriptor/.getJavaType f)]
    (condp = jt
      Descriptors$FieldDescriptor$JavaType/MESSAGE
      (build-msg (Descriptors$FieldDescriptor/.getMessageType f) v)

      Descriptors$FieldDescriptor$JavaType/ENUM
      ;; set by NUMBER via …CreatingIfUnknown so a DEFINED number sets the value
      ;; AND the violating corpus can carry an UNDEFINED number (the open-enum
      ;; passthrough) — neither path silently drops the field the way a
      ;; name-based JsonFormat parse would.
      (Descriptors$EnumDescriptor/.findValueByNumberCreatingIfUnknown
       (Descriptors$FieldDescriptor/.getEnumType f) (int v))

      Descriptors$FieldDescriptor$JavaType/BYTE_STRING
      (octet-bytes v)

      (coerce-scalar jt v))))

(defn build-msg
  "Build a DynamicMessage for descriptor `d` from an EDN value map keyed by proto
   field NAME (snake_case). A repeated field's value is a vector; a message
   field's value is a nested EDN map. nil values and unknown field names are
   skipped (an absent field is the proto3 default)."
  ^DynamicMessage [^Descriptors$Descriptor d edn-map]
  (let [b (DynamicMessage/newBuilder d)]
    (doseq [[fname v] edn-map
            :when (some? v)
            :let [f (Descriptors$Descriptor/.findFieldByName d (name fname))]
            :when f]
      (if (Descriptors$FieldDescriptor/.isRepeated f)
        (doseq [el v]
          (DynamicMessage$Builder/.addRepeatedField b f (field-value f el)))
        (DynamicMessage$Builder/.setField b f (field-value f v))))
    (DynamicMessage$Builder/.build b)))

(defn build-message
  "Build a DynamicMessage for `full-name` (resolved in `pool`) from `edn-map`."
  ^DynamicMessage [pool full-name edn-map]
  (if-let [d (get pool full-name)]
    (build-msg d edn-map)
    (throw (ex-info "message not in descriptor pool" {:message full-name}))))

(defn ->bin
  "The wire `.bin` bytes of a Message."
  ^bytes [^Message msg]
  (Message/.toByteArray msg))

(defn reparse
  "Parse wire bytes back to a DynamicMessage against descriptor `d` — the
   independent decode the round-trip + presence checks read."
  ^DynamicMessage [^Descriptors$Descriptor d ^bytes wire]
  (DynamicMessage/parseFrom d wire))
