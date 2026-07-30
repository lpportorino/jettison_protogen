(ns devcards.designed-test
  "Canaries for the designed-flag declaration LOADER (`devcards.designed`).

   It owns only the file's ENVELOPE — the entry shape belongs to
   `devcards.invariants` and is canaried there. What is pinned here is the set
   of refusals, each of which had never been observed firing: the ns docstring
   advertised that a canary could name this namespace, which reads as a claim
   that one existed.

   Every refusal writes to a temp file rather than perturbing the tracked
   `corpus/designed-flags.edn`, so these run on a dirty checkout and entangle
   with nothing."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.designed :as designed]))

(defn- with-file
  "Write `content` to a temp file, hand its path to `f`, delete it after."
  [content f]
  (let [tmp (java.io.File/createTempFile "designed-canary" ".edn")]
    (try (spit tmp content) (f (.getPath tmp))
         (finally (.delete tmp)))))

(defn- refusal
  "The message `load-declarations` throws for `content`, or nil if it loads."
  [content]
  (with-file content
    (fn [path]
      (try (designed/load-declarations path) nil
           (catch Exception e (ex-message e))))))

(def ^:private well-formed
  "{:doc \"d\" :cards {\"lv_obj/default/small\" [{:invariant :clipped}]}}")

(deftest a-missing-file-is-refused-rather-than-defaulted
  (testing "an absent file and a file declaring nothing are the same value to
            every caller downstream, and the second is a CLAIM while the first
            is an accident. Defaulting to {} would make a checkout that lost the
            file report every declared finding as a fresh defect with no hint a
            declaration file had ever existed.
            REVERT-TO-BREAK: replace the `.exists` throw with `{}`."
    (let [msg (try (designed/load-declarations "corpus/does-not-exist.edn") nil
                   (catch Exception e (ex-message e)))]
      (is (some? msg))
      (is (str/includes? (str msg) "missing designed-flag declarations"))))
  (testing "CONTROL: a well-formed file at a real path loads, so the assertion
            above keys on absence and not on the loader being broken"
    (is (nil? (refusal well-formed)))))

(deftest the-envelope-is-closed-and-typed
  (testing "an unknown top-level key is refused rather than ignored"
    (is (str/includes? (str (refusal "{:doc \"d\" :cards {} :extra 1}"))
                       "unknown top-level keys")))
  (testing "a non-map file is refused"
    (is (str/includes? (str (refusal "[1 2 3]")) "expected a map")))
  (testing ":cards must be a map — a vector would make `for-card` return nil for
            every id, which is indistinguishable from a corpus that declares
            nothing"
    (is (str/includes? (str (refusal "{:doc \"d\" :cards [1]}")) ":cards must be a map")))
  (testing "card keys must be strings, because `for-card` looks them up by
            `(str card-id)` and a keyword key would never match"
    (is (str/includes? (str (refusal "{:doc \"d\" :cards {:kw []}}"))
                       ":cards keys must be card id strings")))
  (testing "CONTROL: the well-formed envelope passes every clause above"
    (is (nil? (refusal well-formed)))))

(deftest for-card-answers-nil-rather-than-empty
  (testing "nil is the honest spelling of `declares none`: an empty VECTOR is
            refused by `invariants/validate-designed-flags!`, so returning []
            here would turn every undeclared card into a refusal"
    (with-file well-formed
      (fn [path]
        (let [d (designed/load-declarations path)]
          (is (nil? (designed/for-card d "no-such-card")))
          (is (some? (designed/for-card d "lv_obj/default/small"))))))))

(deftest an-id-naming-no-card-is-reported
  (testing "the STALE clause structurally cannot see this: it asks whether an
            entry matched a finding, and it is only ever asked about cards the
            run judged — so an entry filed under a MISSPELLED id is never
            consulted, never matched and never reported stale. Dead and invisible
            at once, defeated by one keystroke.
            REVERT-TO-BREAK: have `unknown-card-ids` return []."
    (is (= ["lv_obj/typoed"]
           (designed/unknown-card-ids {"lv_obj/typoed" [] "real" []} ["real"]))))
  (testing "CONTROL: every id present means nothing is reported, so the
            assertion above keys on the mismatch and not on the fn always
            answering"
    (is (empty? (designed/unknown-card-ids {"real" []} ["real"]))))
  (testing "the comparison does NOT normalise: both sides are strings by
            construction — the loader refuses a non-string key and every corpus
            id is a string — so a keyword passed in is a caller error and reads
            as a mismatch rather than being quietly coerced"
    (is (= ["real"] (designed/unknown-card-ids {"real" []} [:real])))))
