(ns devcards.corpus-test
  "Unit tests for the generic themed-corpus driver (`devcards.corpus`) — the
   render-loop/error-partitioning/golden-diff mechanics every bring-your-own-
   corpus consumer plugs into, exercised over a fake render fn (no wasm, no
   host)."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.corpus :as corpus]
            [devcards.probe :as probe]))

(def ^:private screens
  [{:id "b-screen" :payload :b} {:id "a-screen" :payload :a}])

(def ^:private variants
  [{:key :dark :dark 1} {:key :light :dark 0}])

(defn- fake-result
  "Render stand-in: deterministic sha per (id, variant); throws for the one
   poisoned pair; carries a finding for a-screen dark."
  [{:keys [id]} {vk :key}]
  (when (and (= id "b-screen") (= vk :light))
    (throw (ex-info "renderer rejected screen" {:id id})))
  {:sha256 (str (name vk) "-" id)
   :w 10
   :h 20
   :findings (if (and (= id "a-screen") (= vk :dark))
               [{:invariant :zero-area :node "lv_label#1"}]
               [])})

(defn- without-traces
  "`errors` with each entry's `:trace` removed. A trace's frames carry line
   numbers from whatever threw, so a literal can only pin the REST of an entry;
   the trace itself is asserted by the tests written for it."
  [errors]
  (mapv #(dissoc % :trace) errors))

(deftest render-corpus-partitions-and-tags
  (let [{:keys [by-variant findings errors]}
        (corpus/render-corpus screens variants fake-result)]
    (testing "errored pair lands in :errors, tagged, and OUT of the golden maps"
      (is (= [{:variant :light :id "b-screen" :error "renderer rejected screen"}]
             (without-traces errors)))
      (is (every? :trace errors) "and every entry carries its trace beside the message")
      (is (= #{"a-screen" "b-screen"} (set (keys (get by-variant :dark)))))
      (is (= #{"a-screen"} (set (keys (get by-variant :light))))))
    (testing "golden entries carry exactly sha/w/h"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20}
             (get-in by-variant [:dark "a-screen"]))))
    (testing "golden maps are sorted by id (stable manifest diffs)"
      (is (sorted? (get by-variant :dark)))
      (is (= ["a-screen" "b-screen"] (vec (keys (get by-variant :dark))))))
    (testing "findings are tagged with their variant + id"
      (is (= [{:invariant :zero-area :node "lv_label#1" :variant :dark :id "a-screen"}]
             findings)))))

(deftest render-corpus-refuses-empty
  (testing "a zero-screen or zero-variant corpus proves nothing — throw, never
            return a vacuous green"
    (is (thrown? Exception (corpus/render-corpus [] variants fake-result)))
    (is (thrown? Exception (corpus/render-corpus screens [] fake-result)))))

;; ── the card KEY SET the driver carries ──────────────────────────────────
;;
;; The driver is the seam a consumer builds its own corpus on, so what it does
;; with the keys a render fn returns is a contract, not an implementation
;; detail. Three properties, each asserted against a render fn shaped for it:
;; extra keys RIDE ALONG, the documented set is carried BYTE-IDENTICALLY, and a
;; driver-owned key is REFUSED rather than honoured or ignored.

(def ^:private baseline-prstr
  "The EXACT printed form the driver produced for `fake-result` before card-key
   preservation landed, captured from a run against the pre-change function.
   The structural assertions elsewhere in this ns say the VALUES still match;
   this says the BYTES do — key order included — which is what a consumer
   minting an EDN manifest through this seam actually diffs. It is a literal on
   purpose: a re-derivation would compare the new driver against itself.

   THE `:trees []` TERM WAS ADDED DELIBERATELY when the `:tree` channel landed,
   and this is the one edit that may be made to this literal: the RETURN grew a
   channel, so the printed form legitimately changed. What it must never absorb
   is a change under :by-variant — that would mean a card's bytes moved, which
   is the drift this baseline exists to catch.

   AN `:errors` ENTRY ALSO CARRIES `:trace`, AND THE LITERAL DOES NOT. A trace's
   frames carry the line numbers of whatever threw, so no literal could hold
   one stably; the comparison strips exactly that key, which leaves every other
   byte of every entry pinned — a SECOND new key on an entry would still show
   up here as drift."
  (str "{:by-variant {:dark {\"a-screen\" {:sha256 \"dark-a-screen\", :w 10, :h 20},"
       " \"b-screen\" {:sha256 \"dark-b-screen\", :w 10, :h 20}},"
       " :light {\"a-screen\" {:sha256 \"light-a-screen\", :w 10, :h 20}}},"
       " :findings [{:invariant :zero-area, :node \"lv_label#1\","
       " :variant :dark, :id \"a-screen\"}],"
       " :trees [],"
       " :errors [{:variant :light, :id \"b-screen\","
       " :error \"renderer rejected screen\"}]}"))

(deftest render-corpus-output-unchanged-for-documented-key-set
  (testing "a render fn returning exactly the documented {:sha256 :w :h
            :findings} yields output byte-identical to the pre-preservation
            driver"
    (let [result (corpus/render-corpus screens variants fake-result)]
      (is (= baseline-prstr (pr-str (update result :errors without-traces))))
      (is (every? :trace (:errors result))
          "the stripped key was really there — otherwise the strip proves nothing"))))

(defn- thin-result
  "Render stand-in that returns ONLY :sha256 — no :w, no :h, no :findings.
   Destructuring used to fill the two missing dims with an explicit nil; a
   merge-based driver must keep filling them, or a consumer whose render fn is
   this shape gets a manifest that differs from the one it minted yesterday."
  [{:keys [id]} {vk :key}]
  {:sha256 (str (name vk) "-" id)})

(def ^:private baseline-thin-prstr
  "The pre-change printed form for `thin-result`, captured the same way."
  (str "{:by-variant {:dark {\"a-screen\" {:sha256 \"dark-a-screen\", :w nil, :h nil},"
       " \"b-screen\" {:sha256 \"dark-b-screen\", :w nil, :h nil}},"
       " :light {\"a-screen\" {:sha256 \"light-a-screen\", :w nil, :h nil},"
       " \"b-screen\" {:sha256 \"light-b-screen\", :w nil, :h nil}}},"
       " :findings [], :trees [], :errors []}"))

(deftest render-corpus-keeps-the-nil-filled-card-skeleton
  (testing "a render fn that omits :w/:h still gets them, explicitly nil,
            exactly as destructuring produced them"
    (is (= baseline-thin-prstr (pr-str (corpus/render-corpus screens variants thin-result))))))

(defn- bbox-result
  "Render stand-in that returns one key BEYOND the documented set — the shape
   of a consumer recording a per-card geometry rect so a later run can tell
   which rendered images actually differ."
  [{:keys [id]} {vk :key}]
  (assoc (fake-result {:id id} {:key vk}) :bbox [0 0 (count id) 7]))

(deftest render-corpus-preserves-extra-card-keys
  (let [{:keys [by-variant findings]} (corpus/render-corpus screens variants bbox-result)
        card (get-in by-variant [:dark "a-screen"])]
    (testing "the extra key reaches the card — this seam is what a consumer
              threads per-card data through, and dropping it silently is what
              leaves a manifest READING as migrated"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20 :bbox [0 0 8 7]} card)))
    (testing "preservation does not leak the driver's own bookkeeping into the
              card: :findings is CONSUMED (it would push a finding vector into
              every golden entry), and the tag keys are the map's own keys"
      (is (= #{:sha256 :w :h :bbox} (set (keys card)))))
    (testing "the consumed :findings still reaches the findings channel"
      (is (= [{:invariant :zero-area :node "lv_label#1" :variant :dark :id "a-screen"}]
             findings)))))

(defn- hijack
  "Render stand-in that returns a DRIVER-OWNED key. Measured against the
   pre-change driver: `:id` collapsed a four-card corpus into one entry per
   variant, and `:error` partitioned every SUCCESSFUL render into :errors and
   left the golden maps empty. Both silently."
  [k v]
  (fn [{:keys [id]} {vk :key}]
    {:sha256 (str (name vk) "-" id) :w 10 :h 20 :findings [] k v}))

(deftest render-corpus-refuses-driver-owned-keys
  (testing "a render fn returning :id / :variant / :error is REFUSED — honouring
            it corrupts the golden map's keys or its success partition, and
            ignoring it is just as silent"
    (doseq [[k v] [[:id "HIJACKED"] [:variant :nope] [:error "not really"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"driver-owned"
                            (corpus/render-corpus screens variants (hijack k v)))
          (str "expected a refusal for " k))))
  (testing "the refusal names EVERY offending pair, not whichever parallel task
            lost the race, and names the reserved set it enforced"
    (let [data (try (corpus/render-corpus screens variants (hijack :id "HIJACKED"))
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= [:variant :id :error] (:driver-owned data)))
      (is (= [{:variant :dark :id "b-screen" :keys [:id]}
              {:variant :dark :id "a-screen" :keys [:id]}
              {:variant :light :id "b-screen" :keys [:id]}
              {:variant :light :id "a-screen" :keys [:id]}]
             (:clashes data))))))

(deftest diff-cards-three-ways
  (let [expected {"a" {:sha256 "x"} "b" {:sha256 "y"} "gone" {:sha256 "z"}}
        actual {"a" {:sha256 "x"} "b" {:sha256 "DRIFT"} "new" {:sha256 "n"}}
        {:keys [mismatched missing unexpected]} (corpus/diff-cards expected actual)]
    (is (= [{:id "b" :expected "y" :actual "DRIFT"}] mismatched))
    (is (= ["gone"] missing))
    (is (= ["new"] unexpected))))

(deftest diff-cards-clean
  (let [cards {"a" {:sha256 "x"}}]
    (is (= {:mismatched [] :missing [] :unexpected []}
           (corpus/diff-cards cards cards)))))

(deftest probe-find-uid
  (testing "hit returns the exact node (depth-first, descendants included);
            miss returns nil"
    (let [tree {:type "lv_obj" :coords [0 0 9 9]
                :children [{:type "lv_obj" :coords [1 1 4 4] :uid 7
                            :children [{:type "lv_label" :coords [2 2 3 3]
                                        :uid 42 :children []}]}]}]
      (is (= {:type "lv_label" :coords [2 2 3 3] :uid 42 :children []}
             (probe/find-uid tree 42)))
      (is (nil? (probe/find-uid tree 999))))))

(defn- tree-result
  "Render stand-in that returns a dump TREE beside the documented set — the
   shape of a consumer that already holds each tree because it judged the card
   with it, and wants it back without a second render pass."
  [{:keys [id]} {vk :key}]
  (assoc (fake-result {:id id} {:key vk})
         :tree {:type "lv_obj" :id (str (name vk) "-" id)}))

(deftest tree-is-routed-to-its-channel-and-never-into-the-card
  (let [{:keys [by-variant trees]} (corpus/render-corpus screens variants tree-result)]
    (testing "the tree does NOT ride into the golden entry — that is the whole
              reason it is consumed rather than left to ride along, since a
              manifest carrying a dump tree per card is unreviewable and would
              churn on every renderer change"
      (is (= {:sha256 "dark-a-screen" :w 10 :h 20}
             (get-in by-variant [:dark "a-screen"]))
          "a :tree key reached card-of — it must be stripped by consumed-card-keys"))
    (testing "and it arrives on the channel, tagged like a finding"
      (is (= 3 (count trees)) "one per SUCCESSFUL pair; the errored pair contributes none")
      (is (= #{{:variant :dark :id "a-screen" :tree {:type "lv_obj" :id "dark-a-screen"}}
               {:variant :dark :id "b-screen" :tree {:type "lv_obj" :id "dark-b-screen"}}
               {:variant :light :id "a-screen" :tree {:type "lv_obj" :id "light-a-screen"}}}
             (set trees))))))

;; ── what a FAILED render leaves behind ───────────────────────────────────
;;
;; The driver is the only witness to a render that fails ONCE under load and
;; passes on the re-run, so whatever it does not record is gone. These drive the
;; real `render-corpus` over a render fn that throws, and read the record it
;; returns — never a trace built by the test itself.

(defn- nested-failure
  "A three-deep cause chain thrown from a NAMED fn, so the test can tell the
   throw site's frame from the driver's own frames."
  []
  (throw (ex-info "render failed"
                  {:stage :load}
                  (IllegalStateException. "renderer rejected the node"
                                          (ArithmeticException. "divide by zero")))))

(defn- failing-on
  "Render stand-in that throws `thunk`'s exception for exactly one pair and
   behaves like `fake-result` elsewhere."
  [thunk]
  (fn [{:keys [id] :as screen} {vk :key :as variant}]
    (if (and (= id "a-screen") (= vk :dark))
      (thunk)
      (fake-result screen variant))))

(defn- the-error
  "The :errors entry for the pair `failing-on` made fail. `fake-result` fails one
   pair of its own, so the entry is selected by its tag, never by position.

   EVERY entry of the result is also held to `corpus/error-entry-problems`, so
   each failure-path test that calls this checks the driver's REAL output
   against the closed shape — the trace included, through
   `devcards.trace/trace-problems` — and not only the fields it names."
  [result]
  (doseq [e (:errors result)]
    (is (= [] (corpus/error-entry-problems e)) (str "entry off its shape: " (pr-str e))))
  (let [hits (filterv #(and (= "a-screen" (:id %)) (= :dark (:variant %))) (:errors result))]
    (is (= 1 (count hits)) "exactly one entry for the pair made to fail")
    (first hits)))

(deftest a-failed-render-keeps-its-message-and-records-its-trace-beside-it
  (let [e (the-error (corpus/render-corpus screens variants (failing-on nested-failure)))
        chain (get-in e [:trace :chain])]
    (testing ":error keeps EXACTLY its existing meaning — the outer message — so
              a consumer reading it as a string sees no change"
      (is (= "render failed" (:error e)))
      (is (= {:variant :dark :id "a-screen"} (select-keys e [:variant :id]))))
    (testing "the CLASS of every throwable in the cause chain is retained, in
              order, outermost first"
      (is (= ["clojure.lang.ExceptionInfo"
              "java.lang.IllegalStateException"
              "java.lang.ArithmeticException"]
             (mapv :class chain))))
    (testing "and each one's own message, which `:error` alone drops for every
              cause beneath the outermost"
      (is (= ["render failed" "renderer rejected the node" "divide by zero"]
             (mapv :message chain))))
    (testing "the LEADING frame of the outermost throwable is the throw site — the
              render fn's own code, not the driver's thread pool — because the
              frames are kept from the top of the stack"
      (is (re-find #"nested_failure" (str (first (:frames (first chain)))))
          (str "first frame was " (pr-str (first (:frames (first chain)))))))
    (testing "frames dropped are COUNTED, so a reader can tell a short stack from
              a truncated one"
      (is (every? #(nat-int? (:frames-omitted %)) chain))
      (is (false? (get-in e [:trace :chain-truncated?]))))
    (testing "an ordinary JVM throwable is not mistaken for a polyglot one"
      (is (= [nil nil nil] (mapv :polyglot chain))))))

(defn- deep-failure
  "A throwable carrying `n-frames` synthetic frames at the head of a cause chain
   `depth` links long — far past any sane bound, so a driver that copied the
   whole thing would be visible in the counts."
  [n-frames depth]
  (let [root (reduce (fn [cause i] (RuntimeException. (str "link " i) cause))
                     nil
                     (range (dec depth) 0 -1))
        top (RuntimeException. "link 0" root)]
    (.setStackTrace top (into-array StackTraceElement
                                    (for [i (range n-frames)]
                                      (StackTraceElement. "synthetic.Frame" (str "f" i) "Frame.java" i))))
    top))

(deftest a-failed-renders-trace-is-bounded-and-says-what-it-dropped
  (let [n-frames 10000
        depth 1000
        e (the-error (corpus/render-corpus screens variants
                                           (failing-on #(throw (deep-failure n-frames depth)))))
        {:keys [chain chain-truncated?]} (:trace e)
        top (first chain)]
    (testing "the frame list is BOUNDED, and what it kept plus what it counted as
              omitted is the whole stack — the remainder is counted, not lost"
      (is (pos? (count (:frames top))))
      (is (< (count (:frames top)) n-frames))
      (is (= n-frames (some->> (:frames-omitted top) (+ (count (:frames top)))))))
    (testing "the frames kept are the LEADING ones, in order"
      (is (= "synthetic.Frame.f0(Frame.java:0)" (first (:frames top))))
      (is (= "synthetic.Frame.f1(Frame.java:1)" (second (:frames top)))))
    (testing "the cause chain is BOUNDED too, keeps its OUTERMOST links, and says
              it was cut"
      (is (< 1 (count chain) depth))
      (is (true? chain-truncated?))
      (is (= ["link 0" "link 1"] (mapv :message (take 2 chain)))))))

(deftest a-cyclic-cause-chain-terminates
  (testing "a cause chain is a linked list a buggy library can close into a
            loop; the record must still be finite and must name each throwable
            once"
    (let [a (RuntimeException. "a")
          b (RuntimeException. "b" a)
          _ (.initCause a b)
          e (the-error (corpus/render-corpus screens variants (failing-on #(throw a))))]
      (is (= ["a" "b"] (mapv :message (get-in e [:trace :chain]))))
      (is (false? (get-in e [:trace :chain-truncated?])) "a cycle is not a cut"))))

(deftest a-message-less-failure-keeps-the-class-fallback-in-error
  (testing "`:error` still falls back to the class when there is no message,
            and the trace says so with a nil message rather than inventing one"
    (let [e (the-error (corpus/render-corpus screens variants
                                             (failing-on #(throw (UnsupportedOperationException.)))))]
      (is (= "class java.lang.UnsupportedOperationException" (:error e)))
      (is (= {:class "java.lang.UnsupportedOperationException" :message nil}
             (select-keys (first (get-in e [:trace :chain])) [:class :message]))))))

(deftest an-Error-thrown-by-a-render-fn-is-one-screens-failure-not-the-sweeps
  (testing "the catch is Throwable on purpose: an `assert` failing in a render fn
            is an AssertionError, and a narrower catch would let it escape the
            parallel map and discard every OTHER screen's result"
    (let [result (try (corpus/render-corpus screens variants
                                            (failing-on #(assert false "render precondition")))
                      (catch Throwable t t))]
      (is (map? result) (str "the sweep aborted instead of recording the pair: "
                             (when (instance? Throwable result) (ex-message result))))
      (when (map? result)
        (testing "the other screens still rendered"
          (is (= #{"b-screen"} (set (keys (get-in result [:by-variant :dark]))))))
        (testing "and the record says it was an Error, so it cannot be mistaken
                  for a screen the renderer rejected"
          (let [e (the-error result)]
            (is (re-find #"render precondition" (:error e)))
            (is (= "java.lang.AssertionError" (get-in e [:trace :chain 0 :class])))))))))

(defn- throwing-accessor
  "A throwable whose `method` accessor itself throws — the case a diagnosis must
   survive, since a render fn may throw any Throwable subclass at all."
  [method]
  (case method
    :message (proxy [RuntimeException] ["never read"]
               (getMessage [] (throw (IllegalStateException. "getMessage exploded"))))
    :cause (proxy [RuntimeException] ["the cause accessor explodes"]
             (getCause [] (throw (IllegalStateException. "getCause exploded"))))))

(defn- sweep-or-throwable
  "`render-corpus` over a render fn throwing `t` for the one failing pair, or the
   Throwable that escaped it — so an aborted sweep reads as an assertion FAILURE
   rather than an error in the test."
  [t]
  (try (corpus/render-corpus screens variants (failing-on #(throw t)))
       (catch Throwable escaped escaped)))

(deftest a-throwable-whose-accessor-throws-degrades-the-entry-not-the-sweep
  (testing "getCause throws: the message is still the outer one, the trace could
            not be recorded and SAYS so, and every other screen still rendered"
    (let [result (sweep-or-throwable (throwing-accessor :cause))]
      (is (map? result) (str "the sweep aborted: " (when (instance? Throwable result)
                                                     (.getName (class result)))))
      (when (map? result)
        (let [e (the-error result)]
          (is (= "the cause accessor explodes" (:error e)))
          (is (= "java.lang.IllegalStateException" (:trace-unavailable e)))
          (is (not (contains? e :trace)) "exactly one of :trace / :trace-unavailable")
          (is (= #{"b-screen"} (set (keys (get-in result [:by-variant :dark])))))))))
  (testing "getMessage throws: :error falls back to the class, exactly as for a
            message-less throwable, and the trace is marked unavailable"
    (let [result (sweep-or-throwable (throwing-accessor :message))]
      (is (map? result) (str "the sweep aborted: " (when (instance? Throwable result)
                                                     (.getName (class result)))))
      (when (map? result)
        (let [e (the-error result)]
          (is (re-find #"^class " (:error e)))
          (is (= "java.lang.IllegalStateException" (:trace-unavailable e)))
          (is (not (contains? e :trace)) "exactly one of :trace / :trace-unavailable"))))))

(deftest error-entry-problems-accepts-both-real-shapes-and-names-each-departure
  (let [traced (the-error (corpus/render-corpus screens variants (failing-on nested-failure)))
        degraded (the-error (sweep-or-throwable (throwing-accessor :cause)))]
    (testing "controls: the driver's own TRACED and DEGRADED entries conform"
      (is (contains? traced :trace))
      (is (contains? degraded :trace-unavailable))
      (is (= [] (corpus/error-entry-problems traced)))
      (is (= [] (corpus/error-entry-problems degraded))))
    (testing "each departure is refused, and the problem names what departed"
      (doseq [[label bad pattern]
              [["not a map" "x" #"not a map"]
               ["BOTH trace keys" (assoc traced :trace-unavailable "x") #"are neither"]
               ["NEITHER trace key" (dissoc traced :trace) #"are neither"]
               ["an extra key" (assoc degraded :extra 1) #"are neither"]
               ["non-string :error" (assoc traced :error 7) #":error is not a string"]
               ["a trace off its own shape" (assoc-in traced [:trace :chain-truncated?] 0)
                #"entry :trace: .*:chain-truncated\?"]
               ["blank marker" (assoc degraded :trace-unavailable "") #":trace-unavailable is not"]]]
        (let [problems (corpus/error-entry-problems bad)]
          (is (seq problems) (str label ": refused"))
          (is (some #(re-find pattern %) problems)
              (str label ": named — got " (pr-str problems))))))))

(deftest an-empty-message-is-kept-empty-and-the-entry-still-conforms
  (testing "`(ex-info \"\" {})` and its kin carry an EMPTY message, not a nil
            one: :error keeps it exactly as the driver always has, and the entry
            the driver produces must still satisfy error-entry-problems (which
            `the-error` asserts)"
    (let [e (the-error (corpus/render-corpus screens variants
                                             (failing-on #(throw (RuntimeException. "")))))]
      (is (= "" (:error e)))
      (is (= "" (:message (first (get-in e [:trace :chain]))))))))

(deftest a-long-message-is-bounded-in-the-trace-and-whole-in-error
  (testing "a consumer may fold a whole stack trace into the message it throws;
            the trace's copy is bounded and counts what it dropped, while :error
            keeps every character as it always has"
    (let [message (apply str (map #(char (+ 97 (mod % 26))) (range 5000)))
          e (the-error (corpus/render-corpus screens variants
                                             (failing-on #(throw (RuntimeException. ^String message)))))
          link0 (first (get-in e [:trace :chain]))]
      (is (= message (:error e)))
      (is (< (count (:message link0)) (count message)))
      (is (= (count message)
             (some->> (:message-omitted link0) (+ (count (:message link0))))))
      (is (= (subs message 0 (count (:message link0))) (:message link0))
          "the kept part is the LEADING part"))))

(deftest a-render-fn-with-no-tree-contributes-nothing
  ;; `keep`, not `map`: the absence must be EMPTY rather than a vector of
  ;; {:tree nil}, so a consumer can tell "this driver carried no trees" from
  ;; "every card had a nil one". The two read identically at a call site that
  ;; only checks `seq`, and the second is a defect wearing the first's clothes.
  (testing "a documented-set render fn yields an empty channel, not nil entries"
    (is (= [] (:trees (corpus/render-corpus screens variants fake-result))))))
