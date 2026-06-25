(ns protodoc.gencorpus.oracle
  "protovalidate conformance oracle. Wraps a process-shared protovalidate
   Validator (the same build.buf engine the cross-language stack uses) so the
   corpus can be filtered to the constraint-VALID set (positive corpus) and the
   violating sub-corpus can be confirmed REJECTED."
  (:import [build.buf.protovalidate
            Validator ValidatorFactory ValidatorFactory$ValidatorBuilder ValidationResult]
           [com.google.protobuf Message]))

(set! *warn-on-reflection* true)

(def validator
  "Process-shared protovalidate validator, built once on first use."
  (delay (ValidatorFactory$ValidatorBuilder/.build (ValidatorFactory/newBuilder))))

(defn valid?
  "Does `msg` satisfy its proto's buf.validate constraints?"
  [^Message msg]
  (ValidationResult/.isSuccess (Validator/.validate @validator msg)))

(defn violations
  "The buf.validate violation strings for `msg` (empty when valid)."
  [^Message msg]
  (mapv str (ValidationResult/.getViolations (Validator/.validate @validator msg))))
