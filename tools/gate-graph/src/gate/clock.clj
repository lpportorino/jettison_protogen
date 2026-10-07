(ns gate.clock
  "One-JVM monotonic contexts and exact portable offsets; native origins never enter artifacts."
  (:require [gate.contract :as c]
            [malli.core :as m])
  (:import [java.util UUID]))

(def Tick [:int {:min Long/MIN_VALUE :max Long/MAX_VALUE}])
(def ^:private owner (Object.))
(def Context
  [:map {:closed true} [:id c/Id] [:origin Tick] [:owner [:fn #(identical? owner %)]]])
(def Failure [:map {:closed true} [:code [:= :invalid-clock-anchor]]])

(defn difference
  "Subtract same-JVM signed ticks with long wraparound; refuse negative or ambiguous elapsed time.
   The profile requires ordered marks less than 2^63 nanoseconds apart. Wall clocks and other JVM
   origins are not comparable. Nanosecond precision does not assert nanosecond timer resolution."
  [later earlier]
  (let [elapsed (unchecked-subtract (long later) (long earlier))]
    (when (neg? elapsed) (throw (ex-info "Monotonic clock anchor refused" {:code :invalid-clock-anchor})))
    (str elapsed)))
(m/=> difference [:=> [:cat Tick Tick] c/Natural])

(defn start!
  "Create a fresh immutable native clock context for this JVM. Pass this exact context unchanged
   to the coordinator and its observers; persist only its unique ID and decimal relative offsets."
  []
  {:id (str "jvm-" (UUID/randomUUID)) :origin (System/nanoTime) :owner owner})
(m/=> start! [:=> [:cat] Context])

(defn at
  "Anchor an already sampled same-JVM tick to the shared context without another timer sample."
  [context tick]
  (difference tick (:origin context)))
(m/=> at [:=> [:cat Context Tick] c/Natural])

(defn now
  "Return a current exact relative mark in this context's monotonic domain."
  [context]
  (at context (System/nanoTime)))
(m/=> now [:=> [:cat Context] c/Natural])
