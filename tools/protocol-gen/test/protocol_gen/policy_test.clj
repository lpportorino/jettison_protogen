(ns protocol-gen.policy-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [protocol-gen.instrument :as instrument]
            [protocol-gen.policy :as policy]))

(def ^:private minimal
  {:version 1
   :groups [{:id :g :package "p.g"
             :grants [{:message "p.M" :access #{:read} :fields :all}]}]})

(defn- refuses?
  [p]
  (try
    ((instrument/uninstrumented #'policy/validate!) p "<inline>")
    false
    (catch clojure.lang.ExceptionInfo e
      (boolean (re-find #"Not an access policy" (ex-message e))))))

(deftest a-well-formed-policy-validates
  (is (= minimal ((instrument/uninstrumented #'policy/validate!) minimal "<inline>"))))

(deftest the-policy-shape-is-closed
  (testing "an unknown key on a group is refused rather than ignored"
    (is (refuses? (assoc-in minimal [:groups 0 :note] "hi"))))
  (testing "an unknown key on a grant is refused"
    (is (refuses? (assoc-in minimal [:groups 0 :grants 0 :notes] "hi")))))

(deftest an-empty-access-set-is-refused
  ;; A grant that authorises nothing is either a mistake or a message that
  ;; should not be granted at all; either way it must not read as a grant.
  (is (refuses? (assoc-in minimal [:groups 0 :grants 0 :access] #{}))))

(deftest an-unknown-access-mode-is-refused
  (is (refuses? (assoc-in minimal [:groups 0 :grants 0 :access] #{:execute}))))

(deftest fields-must-be-an-explicit-set-or-the-word-all
  (is (refuses? (assoc-in minimal [:groups 0 :grants 0 :fields] #{})))
  (is (refuses? (assoc-in minimal [:groups 0 :grants 0 :fields] :everything)))
  (is (not (refuses? (assoc-in minimal [:groups 0 :grants 0 :fields] #{"a"})))))

(deftest a-namespaced-group-id-is-refused
  ;; A group id becomes the emitted file's name, and a namespaced keyword
  ;; carries a slash — a path separator, not a filename.
  (is (refuses? (assoc-in minimal [:groups 0 :id] :ns/g))))

(deftest an-empty-policy-is-refused
  ;; A policy granting nothing emits nothing, and a run that wrote no schema
  ;; would still exit 0.
  (is (refuses? (assoc minimal :groups []))))

(deftest duplicates-are-reported-so-a-caller-can-refuse-them
  (let [p (update minimal :groups conj (first (:groups minimal)))]
    (is (= [:g] (policy/duplicate-group-ids p))))
  (let [g (update (first (:groups minimal)) :grants
                  conj {:message "p.M" :access #{:write} :fields :all})]
    (is (= ["p.M"] (policy/duplicate-grants g)))))

(deftest a-missing-policy-file-is-a-hard-failure
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Access policy not found"
       (policy/load-policy "does/not/exist.edn"))))

(deftest load-round-trips-a-file
  (let [f (java.io.File/createTempFile "protocol-gen-policy" ".edn")]
    (try
      (spit f (pr-str minimal))
      (is (= minimal (policy/load-policy (java.io.File/.getPath f))))
      (finally (io/delete-file f true)))))
