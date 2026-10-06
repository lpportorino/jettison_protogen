(ns devcards.trace
  "The diagnosable record of a Throwable as plain, CLOSED, printable data: the
   class, the message, the LEADING stack frames with a COUNTED remainder, and a
   bounded cause chain — and, for a GraalVM PolyglotException, which side raised
   it.

   WHY IT EXISTS. A message alone is not a diagnosis. A render that fails ONCE
   under load and passes on the re-run leaves behind exactly what its driver
   recorded and nothing else, so `Index 12 out of bounds for length 12` with no
   class, no frame and no cause is a crash nobody can localise. The evidence is
   therefore captured at the moment it exists, as data a log can print and a
   test can assert on, rather than reconstructed from a reproduction that may
   never come.

   THE SHAPE, closed at both levels (`trace-problems` judges it):

     {:chain            [link ...] ; outermost first, 1..`cause-limit` links
      :chain-truncated? boolean}   ; a further distinct throwable lay beyond

     link = {:class           \"java.lang.IllegalStateException\"
             :message         \"...\" or nil      ; <= `message-limit` chars
             :message-omitted n                   ; chars beyond the bound
             :frames          [\"pkg.Cls.method(File.java:12)\" ...] ; <= `frame-limit`
             :frames-omitted  n
             :polyglot        nil | :internal | :host | :guest}

   Frames are `StackTraceElement` strings — the form a JVM prints — so the
   record stays EDN with no host object inside it, and it prints identically in
   any log that `pr-str`s it. The frames are NOT stable across runs: they carry
   generated class names and line numbers of whatever threw, so a record is
   evidence to read, never a value to diff.

   THE CHAIN FOLLOWS A HOST EXCEPTION INTO WHAT IT WRAPS. A PolyglotException
   raised because JVM code the guest called back into threw reports a nil
   `getCause`; the throwable that actually carries that JVM code's frames is
   reached through `asHostException`, and the chain takes that step. Nothing
   else is followed — suppressed exceptions are not part of the record.

   WHAT IS NOT RETAINED: `ex-data`. An ExceptionInfo's data map is arbitrary
   consumer data — it may be large, it may hold an infinite lazy sequence, and
   printing it may run code — so copying it would bring back the unbounded,
   possibly-throwing work every bound here exists to refuse. Its class and
   message are kept like any other throwable's; read the data where it was
   thrown.

   BOUNDED AND TERMINATING BY CONSTRUCTION. A cause chain is a linked list a
   buggy library can close into a loop, so the walk is identity-deduplicated:
   each throwable appears at most once and a cycle ends the walk rather than
   repeating. The walk also never visits more than one link past
   `cause-limit` — it asks only whether ANOTHER distinct throwable exists, never
   how many — so its cost is fixed whatever threw. Frames and messages are
   different: an array's length and a string's are already known, so what lies
   beyond `frame-limit` and `message-limit` is COUNTED exactly at no cost.

   AN ACCESSOR THAT THROWS PROPAGATES. `throwable-trace` calls `getMessage`,
   `getCause` and `getStackTrace`, which a throwable may override; if one
   throws, so does `throwable-trace`. A caller recording a failure it must not
   lose — `devcards.corpus` is one — guards the call and degrades.

   Dependency-lean like the rest of this tool: no malli, the shape is
   hand-validated. It knows the polyglot exception TYPE because GraalVM polyglot
   is already a hard dependency of this tool; it knows nothing about hosts,
   wasm or rendering."
  (:import [java.util Collections IdentityHashMap Set]
           [org.graalvm.polyglot PolyglotException]))

(set! *warn-on-reflection* true)

(def frame-limit
  "Leading stack frames copied per throwable. Large enough to cross the JDK and
   polyglot layers into the first frames of the code that threw, which sit at
   the TOP of the stack; the frames below the bound are the caller's thread
   machinery, and they are counted in `:frames-omitted` rather than dropped
   silently."
  32)

(def cause-limit
  "Throwables copied from the cause chain. A bound rather than a guess at depth:
   a chain is a linked list of arbitrary length, and a diagnosis must never be
   the thing that runs out of memory or time. `:chain-truncated?` says whether
   any distinct throwable lay past it."
  8)

(def message-limit
  "Characters of a message copied per link; the rest are counted in
   `:message-omitted`. A message is author-controlled and unbounded, and a
   caller that folds a whole stack trace into the message it throws would
   otherwise make the first link as large as the trace it already carries. The
   bound reaches only this record's COPY — a caller keeping the full message
   elsewhere (`devcards.corpus`'s `:error` does) loses nothing."
  1024)

(def polyglot-kinds
  "Every value `:polyglot` may carry besides nil. CLOSED, and closed by the
   pinned polyglot API rather than by taste — see `polyglot-kind-of`."
  #{:internal :host :guest})

(def trace-keys
  "The keys of a trace map — exactly these."
  #{:chain :chain-truncated?})

(def link-keys
  "The keys of one chain link — exactly these. `:message` and `:polyglot` are
   always PRESENT, nil when absent, so every link prints with the same keys."
  #{:class :message :message-omitted :frames :frames-omitted :polyglot})

(defn polyglot-kind-of
  "THE DECISION, over plain data: which side raised a PolyglotException, from
   its two discriminating flags. `:internal` for a fault inside the language
   implementation, `:host` for an exception thrown by JVM code the guest called
   into, `:guest` otherwise.

   THE ORDER IS LOAD-BEARING. In the pinned polyglot API `isGuestException` is
   defined as the NEGATION of `isHostException` (read off the bytecode of
   `PolyglotException`), so an INTERNAL error also reports itself as a guest
   exception — observed on a real one, which reports internal AND guest. A
   classifier that asks \"guest?\" before \"internal?\" therefore labels every
   engine fault as the guest's own failure, the one confusion this field exists
   to prevent. The same definition is why there is no fourth value: every
   exception is host or not-host."
  [{:keys [internal? host?]}]
  (cond internal? :internal
        host? :host
        :else :guest))

(defn polyglot-flags
  "THE ADAPTER: the two flags `polyglot-kind-of` decides on, read off a
   PolyglotException and nothing else. Kept this thin so that every read is
   reachable by a real exception — a guest trap, a host throw and an internal
   error each have one in this namespace's tests."
  [^PolyglotException p]
  {:internal? (.isInternalError p) :host? (.isHostException p)})

(defn polyglot-kind
  "The kind of `t` when it is a PolyglotException; nil for any other throwable."
  [^Throwable t]
  (when (instance? PolyglotException t)
    (polyglot-kind-of (polyglot-flags t))))

(defn- next-throwable
  "The throwable beneath `t`: its cause, or — for a polyglot HOST exception,
   whose `getCause` is nil — the host exception it wraps.

   It asks the exception ITSELF whether it is a host exception rather than
   reading `polyglot-kind`, because `asHostException` THROWS on anything else:
   a classification error must cost a mislabelled link, never a diagnosis that
   dies while recording a failure."
  [^Throwable t]
  (or (.getCause t)
      (when (and (instance? PolyglotException t) (.isHostException ^PolyglotException t))
        (.asHostException ^PolyglotException t))))

(defn- bounded-chain
  "The first `cause-limit` distinct throwables reachable from `t`, and whether
   another distinct one lies beyond them. Visits at most `cause-limit` + 1
   links. Identity, not equality, decides distinctness: two throwables are
   never the same failure merely because they compare equal."
  [^Throwable t]
  (let [^Set seen (Collections/newSetFromMap (IdentityHashMap.))]
    (loop [x t
           kept (transient [])]
      (cond (or (nil? x) (not (.add seen x))) {:kept (persistent! kept) :truncated? false}
            (= cause-limit (count kept)) {:kept (persistent! kept) :truncated? true}
            :else (recur (next-throwable x) (conj! kept x))))))

(defn- link
  "One throwable as a chain link: class, the leading `message-limit` characters
   of its message, the leading `frame-limit` frames, the counts of both
   remainders, and its polyglot kind."
  [^Throwable t]
  (let [stack (.getStackTrace t)
        frames (mapv str (take frame-limit stack))
        message (.getMessage t)
        kept (when message (subs message 0 (min message-limit (count message))))]
    {:class (.getName (class t))
     :message kept
     :message-omitted (if message (- (count message) (count kept)) 0)
     :frames frames
     :frames-omitted (- (alength stack) (count frames))
     :polyglot (polyglot-kind t)}))

(defn throwable-trace
  "The closed trace map for `t` (see the namespace docstring): every distinct
   throwable on its cause chain up to `cause-limit`, outermost first, each with
   its bounded message and leading frames and the counts of what lay beyond
   them, and whether the chain went on past the bound. Never empty — `t` itself
   is always the first link. Throws if an accessor of a throwable on the chain
   throws."
  [^Throwable t]
  (let [{:keys [kept truncated?]} (bounded-chain t)]
    {:chain (mapv link kept)
     :chain-truncated? truncated?}))

;; ── the shape, judged ────────────────────────────────────────────────────

(defn- closed-keys-problems
  "A problem string when `m` is not a map carrying exactly `ks`."
  [what m ks]
  (cond (not (map? m)) [(str what " is not a map: " (pr-str m))]
        (not= ks (set (keys m))) [(str what " keys " (vec (sort-by pr-str (keys m)))
                                       " are not exactly " (vec (sort ks)))]
        :else []))

(defn- message-problems
  "Every way a link's message and its omitted count depart from the shape."
  [what message message-omitted]
  (cond-> []
    (not (or (nil? message) (string? message)))
    (conj (str what " :message is neither nil nor a string"))
    (and (string? message) (> (count message) message-limit))
    (conj (str what " :message is " (count message) " chars, over message-limit " message-limit))
    (not (nat-int? message-omitted))
    (conj (str what " :message-omitted is not a natural number"))
    (and (nil? message) (pos-int? message-omitted))
    (conj (str what " omits " message-omitted " chars of a nil :message"))))

(defn- link-problems
  "Every way one chain link departs from its closed shape."
  [i {class-name :class :keys [message message-omitted frames frames-omitted polyglot] :as l}]
  (let [what (str "link " i)
        shape (closed-keys-problems what l link-keys)]
    (if (seq shape)
      shape
      (cond-> (message-problems what message message-omitted)
        (not (and (string? class-name) (seq class-name)))
        (conj (str what " :class is not a non-blank string"))
        (not (and (vector? frames) (every? string? frames)))
        (conj (str what " :frames is not a vector of strings"))
        (and (vector? frames) (> (count frames) frame-limit))
        (conj (str what " carries " (count frames) " frames, over frame-limit " frame-limit))
        (not (nat-int? frames-omitted))
        (conj (str what " :frames-omitted is not a natural number"))
        (not (or (nil? polyglot) (contains? polyglot-kinds polyglot)))
        (conj (str what " :polyglot " (pr-str polyglot) " is not nil or one of "
                   (vec (sort polyglot-kinds))))))))

(defn trace-problems
  "Every way `x` departs from the closed trace shape, as strings; empty when it
   conforms. This is the shape's one executable statement — a reader asking
   what `throwable-trace` may return reads it here, and the tests hold every
   trace they produce to it."
  [x]
  (let [shape (closed-keys-problems "trace" x trace-keys)]
    (if (seq shape)
      shape
      (let [{:keys [chain chain-truncated?]} x]
        (cond-> []
          (not (and (vector? chain) (seq chain)))
          (conj "trace :chain is not a non-empty vector")
          (and (vector? chain) (> (count chain) cause-limit))
          (conj (str "trace carries " (count chain) " links, over cause-limit " cause-limit))
          (not (boolean? chain-truncated?))
          (conj "trace :chain-truncated? is not a boolean")
          (and (true? chain-truncated?) (vector? chain) (< (count chain) cause-limit))
          (conj (str "trace is truncated? with only " (count chain)
                     " links — a chain shorter than cause-limit cannot have been cut"))
          (vector? chain)
          (into (mapcat link-problems (range) chain)))))))
