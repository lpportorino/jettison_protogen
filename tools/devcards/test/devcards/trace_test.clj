(ns devcards.trace-test
  "Canaries for `devcards.trace` — the closed record a failed render leaves.

   The JVM half runs on throwables built here. The POLYGLOT half runs on REAL
   PolyglotExceptions raised by a real wasm module — a dozen bytes written out
   below — through a real Context, because the kind of a polyglot exception is
   the engine's answer and no hand-built stand-in can give it.

   All three kinds are raised for real: a guest trap, a host throw, and an
   INTERNAL error — provoked hermetically by handing the engine a byte source
   whose `toByteArray` throws while the engine parses it, so the fault happens
   inside the language implementation rather than in guest or host code."
  (:require [clojure.test :refer [deftest is testing]]
            [devcards.trace :as trace])
  (:import [java.util.stream IntStream]
           [org.graalvm.polyglot Context Engine PolyglotException Source]
           [org.graalvm.polyglot.io ByteSequence]
           [org.graalvm.polyglot.proxy ProxyExecutable ProxyObject]))

(set! *warn-on-reflection* true)

(defn- conforms
  "Assert trace `tr` satisfies the closed shape, and return it."
  [tr]
  (is (= [] (trace/trace-problems tr)) (str "shape problems in " (pr-str tr)))
  tr)

(defn- with-frames
  "`t` carrying exactly `n` synthetic frames, numbered from 0 at the top."
  ^Throwable [^Throwable t n]
  (.setStackTrace t (into-array StackTraceElement
                                (for [i (range n)]
                                  (StackTraceElement. "synthetic.Frame" (str "f" i) "Frame.java" i))))
  t)

(defn- chain-of
  "A cause chain `depth` links long, messages \"link 0\" outermost."
  ^Throwable [depth]
  (reduce (fn [cause i] (RuntimeException. (str "link " i) cause))
          nil
          (range (dec depth) -1 -1)))

;; ── the JVM half ─────────────────────────────────────────────────────────

(deftest a-nested-chain-is-recorded-outermost-first
  (let [^Throwable t (ex-info "outer" {} (IllegalStateException. "middle" (ArithmeticException. "root")))
        {:keys [chain chain-truncated?]} (conforms (trace/throwable-trace t))]
    (is (= ["clojure.lang.ExceptionInfo" "java.lang.IllegalStateException"
            "java.lang.ArithmeticException"]
           (mapv :class chain)))
    (is (= ["outer" "middle" "root"] (mapv :message chain)))
    (is (false? chain-truncated?))
    (testing "each link's frames are ITS OWN stack, as printed, kept from the top"
      (is (= (mapv str (take trace/frame-limit (.getStackTrace t)))
             (:frames (first chain)))))
    (is (= [nil nil nil] (mapv :polyglot chain)))))

(deftest the-frame-bound-keeps-the-LEADING-frames-and-counts-the-rest
  (testing "one past the bound: exactly the bound kept, one counted"
    (let [l (first (:chain (conforms (trace/throwable-trace
                                      (with-frames (RuntimeException. "x")
                                        (inc trace/frame-limit))))))]
      (is (= trace/frame-limit (count (:frames l))))
      (is (= 1 (:frames-omitted l)))
      (is (= "synthetic.Frame.f0(Frame.java:0)" (first (:frames l))))
      (is (= (str "synthetic.Frame.f" (dec trace/frame-limit)
                  "(Frame.java:" (dec trace/frame-limit) ")")
             (peek (:frames l))))))
  (testing "AT the bound: all kept, none counted — the boundary is inclusive"
    (let [l (first (:chain (trace/throwable-trace
                            (with-frames (RuntimeException. "x") trace/frame-limit))))]
      (is (= trace/frame-limit (count (:frames l))))
      (is (= 0 (:frames-omitted l)))))
  (testing "an empty stack is a valid record, not an error"
    (let [l (first (:chain (conforms (trace/throwable-trace
                                      (with-frames (RuntimeException. "x") 0)))))]
      (is (= [] (:frames l)))
      (is (= 0 (:frames-omitted l))))))

(deftest the-cause-bound-keeps-the-OUTERMOST-links-and-says-it-cut
  (testing "ONE past the bound: exactly the bound kept, and the cut reported"
    (let [{:keys [chain chain-truncated?]}
          (conforms (trace/throwable-trace (chain-of (inc trace/cause-limit))))]
      (is (= trace/cause-limit (count chain)))
      (is (true? chain-truncated?))
      (is (= "link 0" (:message (first chain))))
      (is (= (str "link " (dec trace/cause-limit)) (:message (peek chain))))))
  (testing "AT the bound: all kept, NOT reported as cut — the boundary is
            inclusive in both directions"
    (let [{:keys [chain chain-truncated?]}
          (conforms (trace/throwable-trace (chain-of trace/cause-limit)))]
      (is (= trace/cause-limit (count chain)))
      (is (false? chain-truncated?)))))

(deftest a-cyclic-chain-names-each-throwable-once
  (let [a (RuntimeException. "a")
        b (RuntimeException. "b" a)
        _ (.initCause a b)
        {:keys [chain chain-truncated?]} (conforms (trace/throwable-trace a))]
    (is (= ["a" "b"] (mapv :message chain)))
    (is (false? chain-truncated?) "a cycle is not a cut")))

(deftest a-null-message-is-recorded-as-nil
  (is (= {:class "java.lang.UnsupportedOperationException" :message nil :message-omitted 0}
         (select-keys (first (:chain (conforms (trace/throwable-trace
                                                (UnsupportedOperationException.)))))
                      [:class :message :message-omitted]))))

(defn- alphabet
  "An `n`-character string whose every position is recoverable from its content,
   so a bound that kept the wrong END would be visible."
  ^String [n]
  (apply str (map #(char (+ 97 (mod % 26))) (range n))))

(defn- message-link
  "The single link recorded for a throwable carrying an `n`-character message."
  [n]
  (first (:chain (conforms (trace/throwable-trace (RuntimeException. (alphabet n)))))))

(deftest the-message-bound-keeps-the-LEADING-characters-and-counts-the-rest
  (testing "AT the bound: all kept, none counted"
    (let [l (message-link trace/message-limit)]
      (is (= trace/message-limit (count (:message l))))
      (is (= 0 (:message-omitted l)))))
  (testing "ONE past the bound: exactly the bound kept, one counted — and it is
            the LEADING part"
    (let [l (message-link (inc trace/message-limit))]
      (is (= trace/message-limit (count (:message l))))
      (is (= 1 (:message-omitted l)))
      (is (= (alphabet trace/message-limit) (:message l)))))
  (testing "far past it: still the bound, and the count is exact"
    (let [l (message-link 100000)]
      (is (= trace/message-limit (count (:message l))))
      (is (= (- 100000 trace/message-limit) (:message-omitted l))))))

;; ── the polyglot half ────────────────────────────────────────────────────

(def ^:private probe-wasm
  "A minimal wasm module: it imports `env.boom` : () -> (), and exports `run`,
   which calls that import, and `trap`, which executes `unreachable`. So `run`
   raises whatever the host's `boom` throws, and `trap` raises a guest trap."
  (byte-array
   (map unchecked-byte
        [0x00 0x61 0x73 0x6d 0x01 0x00 0x00 0x00
         0x01 0x04 0x01 0x60 0x00 0x00
         0x02 0x0c 0x01 0x03 0x65 0x6e 0x76 0x04 0x62 0x6f 0x6f 0x6d 0x00 0x00
         0x03 0x03 0x02 0x00 0x00
         0x07 0x0e 0x02 0x03 0x72 0x75 0x6e 0x00 0x01 0x04 0x74 0x72 0x61 0x70 0x00 0x02
         0x0a 0x0a 0x02 0x04 0x00 0x10 0x00 0x0b 0x03 0x00 0x00 0x0b])))

(defn- polyglot-failure
  "Call `export` of `probe-wasm`, with `boom` as its host import, and return
   `(observe p)` for the PolyglotException it raises — evaluated while the
   context is still OPEN, so nothing observed depends on a closed context. Fails
   the test, returning nil, if nothing is thrown."
  [^String export boom observe]
  (with-open [engine (.build (Engine/newBuilder (into-array String ["wasm"])))
              ctx (-> (Context/newBuilder (into-array String ["wasm"])) (.engine engine) (.build))]
    (let [module (.eval ctx (.build (Source/newBuilder "wasm" (ByteSequence/create ^bytes probe-wasm) "probe")))
          inst (.newInstance module (object-array [(ProxyObject/fromMap
                                                    {"env" (ProxyObject/fromMap {"boom" boom})})]))
          exports (if (.hasMember inst "exports") (.getMember inst "exports") inst)]
      (try (.execute (.getMember exports export) (object-array 0))
           (is false (str export " threw nothing"))
           nil
           (catch PolyglotException p (observe p))))))

(defn- observed
  "What the polyglot tests read off a PolyglotException."
  [^PolyglotException p]
  {:kind (trace/polyglot-kind p) :cause (.getCause p) :trace (trace/throwable-trace p)})

(def ^:private throwing-boom
  (reify ProxyExecutable
    (execute [_ _] (throw (IllegalStateException. "host boom")))))

(def ^:private quiet-boom
  (reify ProxyExecutable
    (execute [_ _] nil)))

(deftest a-guest-trap-is-a-guest-exception
  (let [{:keys [kind trace]} (polyglot-failure "trap" quiet-boom observed)
        {:keys [chain chain-truncated?]} (conforms trace)]
    (is (= :guest kind))
    (is (= 1 (count chain)) "a trap has no cause to follow")
    (is (false? chain-truncated?))
    (is (= {:class "org.graalvm.polyglot.PolyglotException" :polyglot :guest}
           (select-keys (first chain) [:class :polyglot])))))

(deftest a-host-exception-is-followed-into-the-throwable-it-wraps
  (let [{:keys [kind cause trace]} (polyglot-failure "run" throwing-boom observed)
        {:keys [chain]} (conforms trace)]
    (testing "precondition: the polyglot layer reports NO cause, so without the
              host step the chain would stop at one link"
      (is (nil? cause)))
    (is (= :host kind))
    (is (= ["org.graalvm.polyglot.PolyglotException" "java.lang.IllegalStateException"]
           (mapv :class chain)))
    (is (= [:host nil] (mapv :polyglot chain)))
    (is (= "host boom" (:message (second chain))))
    (testing "the wrapped throwable brings its OWN frames, which reach the host
              code that threw"
      (is (seq (:frames (second chain))))
      (is (some #(re-find #"trace_test" %) (:frames (second chain)))))))

(defn- throwing-bytes
  "A byte source whose `toByteArray` throws. Handed to the engine as a module,
   the throw happens while the LANGUAGE parses it — inside the implementation,
   which is exactly what the polyglot layer reports as an internal error."
  ^ByteSequence []
  (reify ByteSequence
    (length [_] 8)
    (byteAt [_ _] (byte 0))
    (toByteArray [_] (throw (IllegalStateException. "byte source exploded")))
    (subSequence [this _ _] this)
    (bytes [_] (IntStream/empty))))

(defn- internal-failure
  "`(observe p)` for the PolyglotException evaluating `throwing-bytes` raises,
   taken while the context is open; fails the test, returning nil, if nothing is
   thrown."
  [observe]
  (with-open [engine (.build (Engine/newBuilder (into-array String ["wasm"])))
              ctx (-> (Context/newBuilder (into-array String ["wasm"])) (.engine engine) (.build))]
    (try (.eval ctx (.build (Source/newBuilder "wasm" (throwing-bytes) "throwing")))
         (is false "evaluating a throwing byte source threw nothing")
         nil
         (catch PolyglotException p (observe p)))))

(deftest a-fault-inside-the-engine-is-an-internal-exception
  (let [{:keys [raw kind trace flags]}
        (internal-failure (fn [^PolyglotException p]
                            {:raw {:internal (.isInternalError p)
                                   :host (.isHostException p)
                                   :guest (.isGuestException p)}
                             :kind (trace/polyglot-kind p)
                             :flags (trace/polyglot-flags p)
                             :trace (trace/throwable-trace p)}))
        {:keys [chain]} (conforms trace)]
    (testing "FIXTURE precondition, read straight off the exception before any
              code under test: the throwing byte source really produced an
              internal error. If this reds, the fixture broke, not the classifier.
              It also shows the premise of the precedence on a REAL internal
              error — it reports itself as a GUEST exception too."
      (is (= {:internal true :host false :guest true} raw)))
    (is (= {:internal? true :host? false} flags))
    (is (= :internal kind))
    (is (= :internal (:polyglot (first chain))))
    (testing "its frames reach the byte source that threw, so the record locates
              the fault"
      (is (some #(re-find #"toByteArray" %) (:frames (first chain)))))))

(deftest polyglot-flags-reads-each-flag-off-a-real-exception
  (is (= {:internal? false :host? false}
         (polyglot-failure "trap" quiet-boom #(trace/polyglot-flags %))))
  (is (= {:internal? false :host? true}
         (polyglot-failure "run" throwing-boom #(trace/polyglot-flags %)))))

(deftest polyglot-kind-of-asks-internal-BEFORE-anything-else
  (testing "the pinned API defines guest as not-host, so an internal error is
            ALSO a guest exception; asking in any other order mislabels it"
    (is (= :internal (trace/polyglot-kind-of {:internal? true :host? false})))
    (is (= :internal (trace/polyglot-kind-of {:internal? true :host? true}))))
  (is (= :host (trace/polyglot-kind-of {:internal? false :host? true})))
  (is (= :guest (trace/polyglot-kind-of {:internal? false :host? false})))
  (is (every? trace/polyglot-kinds
              (for [i [true false] h [true false]]
                (trace/polyglot-kind-of {:internal? i :host? h})))))

(deftest a-non-polyglot-throwable-has-no-kind
  (is (nil? (trace/polyglot-kind (RuntimeException. "x")))))

;; ── the shape, judged ────────────────────────────────────────────────────

(def ^:private good
  "A conforming trace, built by the code under test."
  (trace/throwable-trace (RuntimeException. "x" (IllegalStateException. "y"))))

(deftest trace-problems-accepts-a-real-trace-and-names-each-departure
  (is (= [] (trace/trace-problems good)) "control: a real trace conforms")
  (testing "each departure is refused, and the problem names what departed"
    (doseq [[label bad pattern]
            [["not a map" "x" #"not a map"]
             ["extra trace key" (assoc good :extra 1) #"trace keys"]
             ["missing trace key" (dissoc good :chain-truncated?) #"trace keys"]
             ["empty chain" (assoc good :chain []) #":chain is not a non-empty vector"]
             ["chain over the bound"
              (assoc good :chain (vec (repeat (inc trace/cause-limit) (first (:chain good)))))
              #"over cause-limit"]
             ["non-boolean truncation" (assoc good :chain-truncated? 0) #":chain-truncated\? is not"]
             ["a short chain claiming a cut" (assoc good :chain-truncated? true) #"cannot have been cut"]
             ["extra link key" (assoc-in good [:chain 0 :extra] 1) #"link 0 keys"]
             ["missing link key" (update-in good [:chain 1] dissoc :polyglot) #"link 1 keys"]
             ["blank class" (assoc-in good [:chain 0 :class] "") #":class"]
             ["non-string message" (assoc-in good [:chain 0 :message] 7) #":message"]
             ["frames not strings" (assoc-in good [:chain 0 :frames] [1]) #":frames is not"]
             ["frames over the bound"
              (assoc-in good [:chain 0 :frames] (vec (repeat (inc trace/frame-limit) "f")))
              #"over frame-limit"]
             ["negative frames omitted" (assoc-in good [:chain 0 :frames-omitted] -2) #":frames-omitted"]
             ["unknown kind" (assoc-in good [:chain 0 :polyglot] :other) #":polyglot"]
             ["message over the bound"
              (assoc-in good [:chain 0 :message] (apply str (repeat (inc trace/message-limit) "m")))
              #"over message-limit"]
             ["negative message omitted" (assoc-in good [:chain 0 :message-omitted] -1)
              #":message-omitted is not"]
             ["omitted chars of a nil message"
              (-> good (assoc-in [:chain 0 :message] nil) (assoc-in [:chain 0 :message-omitted] 3))
              #"of a nil :message"]
             ["missing message-omitted" (update-in good [:chain 0] dissoc :message-omitted)
              #"link 0 keys"]]]
      (let [problems (trace/trace-problems bad)]
        (is (seq problems) (str label ": refused"))
        (is (some #(re-find pattern %) problems)
            (str label ": named — got " (pr-str problems)))))))
