(ns scratchcard.protocol-test
  "Pins the wire.

  Two properties here are worth more than the rest and both are about
  FAILURE, not success: an unknown argument must be REFUSED rather than
  dropped, and a closed connection must never decode to something a caller
  reads as success. Both failure modes are silent by nature — they produce a
  normal-looking envelope for work that did not happen."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.protocol :as proto]))

(deftest envelopes-round-trip-through-ndjson
  (let [req (proto/request 7 "regenerate" {:file "screen.edn"})]
    (is (= req (proto/decode-line (str/trim-newline (proto/encode-line req)))))
    (testing "every encoded message is exactly ONE line — the framing is the line"
      (is (= 1 (count (str/split-lines (proto/encode-line req)))))
      (is (str/ends-with? (proto/encode-line req) "\n"))))
  (testing "success and failure envelopes both survive the trip"
    (let [o (proto/ok 1 {:dir "/x/y"})
          e (proto/err 1 "RENDER_FAILED" "boom" {:cell "asgard-dark-800x480"})]
      (is (= o (proto/decode-line (str/trim-newline (proto/encode-line o)))))
      (is (= e (proto/decode-line (str/trim-newline (proto/encode-line e))))))))

(deftest an-embedded-newline-is-refused-rather-than-desyncing-the-stream
  ;; A message containing a raw newline would be read as two messages, and
  ;; every message after it on that connection would be misattributed.
  (let [nasty (proto/ok 1 {:message "line one\nline two"})]
    (testing "JSON escapes it, so the encoded form is still one line"
      (is (= 1 (count (str/split-lines (proto/encode-line nasty))))))
    (testing "and it survives the round trip intact"
      (is (= nasty (proto/decode-line (str/trim-newline (proto/encode-line nasty))))))))

(deftest a-closed-connection-is-never-success-shaped
  (let [r (proto/decode-response nil)]
    (is (proto/error? r))
    (is (= "NO_RESPONSE" (:error r)))
    (is (false? (:ok r))))
  (testing "and neither is garbage on the wire"
    (let [r (proto/decode-response "{not json")]
      (is (proto/error? r))
      (is (= "MALFORMED_REQUEST" (:error r)))))
  (testing "a JSON scalar is not a response either"
    (is (proto/error? (proto/decode-response "42"))))
  (testing "a real response decodes as success"
    (is (not (proto/error? (proto/decode-response
                            (str/trim-newline (proto/encode-line (proto/ok 1 :v)))))))))

(deftest unknown-arguments-are-refused-with-both-sides-named
  (let [[status env] (proto/validate-request
                      (proto/request 1 "regenerate" {:file "s.edn" :sneaky true}))]
    (is (= :error status))
    (is (= "UNKNOWN_ARGUMENT" (:error env)))
    (testing "the offending key is named"
      (is (= [:sneaky] (:unknown env)))
      (is (str/includes? (:message env) "sneaky")))
    (testing "and so is the accepted set — an error a caller can act on"
      (is (contains? (set (:accepted env)) :file))
      (is (str/includes? (:message env) "accepted:")))))

(deftest ops-with-no-args-refuse-every-argument
  (let [[status env] (proto/validate-request (proto/request 1 "ping" {:file "x"}))]
    (is (= :error status))
    (is (= "UNKNOWN_ARGUMENT" (:error env)))
    (is (str/includes? (:message env) "none"))))

(deftest op-vocabulary-is-closed
  (testing "an unknown op is refused and the known set is offered"
    (let [[status env] (proto/validate-request (proto/request 1 "render-everything" {}))]
      (is (= :error status))
      (is (= "UNKNOWN_OP" (:error env)))
      (is (contains? (set (:accepted env)) "regenerate"))))
  (testing "a blank or absent op"
    (is (= "MISSING_OP" (:error (second (proto/validate-request {:id 1})))))
    (is (= "MISSING_OP" (:error (second (proto/validate-request {:id 1 :op ""}))))))
  (testing "args that are not a map"
    (is (= "MALFORMED_REQUEST"
           (:error (second (proto/validate-request {:id 1 :op "ping" :args [1 2]})))))))

(deftest well-formed-requests-pass-and-are-normalised
  (let [[status req] (proto/validate-request (proto/request 3 "regenerate" {:file "s.edn"}))]
    (is (= :ok status))
    (is (= "regenerate" (:op req)))
    (is (= {:file "s.edn"} (:args req))))
  (testing "absent args normalise to an empty map so handlers need no nil branch"
    (let [[status req] (proto/validate-request {:id 4 :op "ping"})]
      (is (= :ok status))
      (is (= {} (:args req)))))
  (testing "every declared op validates with its own full arg set"
    (doseq [[op accepted] proto/ops]
      (let [args (zipmap accepted (repeat "v"))
            [status _] (proto/validate-request (proto/request 1 op args))]
        (is (= :ok status) (str op " rejected its own declared args"))))))

(deftest error-codes-are-disjoint-across-halves
  ;; A code shared between the client and the daemon would make a transport
  ;; failure indistinguishable from a render failure.
  (is (empty? (set/intersection proto/client-errors proto/render-errors)))
  (is (empty? (set/intersection proto/client-errors proto/input-errors)))
  (is (empty? (set/intersection proto/protocol-errors proto/render-errors)))
  (testing "the union is what error-codes reports — no code is unreachable"
    (is (= proto/error-codes
           (set/union proto/input-errors proto/render-errors
                      proto/protocol-errors proto/client-errors)))))

(deftest input-errors-name-each-validation-stage
  ;; The authoring pipeline runs seven checks. Collapsing them into one
  ;; BUILD_FAILED would throw away the tool's most useful answer: WHICH stage
  ;; rejected the screen.
  (is (>= (count proto/input-errors) 7))
  (is (every? #(str/starts-with? % "INPUT_") proto/input-errors)))
