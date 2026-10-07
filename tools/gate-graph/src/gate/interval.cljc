(ns gate.interval
  "Exact interval algebra; elapsed envelopes, occupied time and duration sums differ."
  (:require [gate.contract :as c]
            [gate.decimal :as d]
            [malli.core :as m]))

(def Intervals [:vector {:max 128000} c/Interval])
(def Summary
  [:map {:closed true} [:count [:int {:min 0 :max 128000}]]
   [:start-ns [:maybe c/Natural]] [:end-ns [:maybe c/Natural]]
   [:occupied-ns c/Natural] [:envelope-ns c/Natural] [:duration-sum-ns c/Natural]])

(defn separated?
  "Prove disjoint coverage; touching positive intervals qualify, touching instants do not."
  [a b]
  (when (or (not (d/before-or-equal? (:start-ns a) (:end-ns a)))
            (not (d/before-or-equal? (:start-ns b) (:end-ns b))))
    (throw (ex-info "Reversed interval" {:code :invalid-interval})))
  (let [positive? (and (neg? (d/compare (:start-ns a) (:end-ns a)))
                       (neg? (d/compare (:start-ns b) (:end-ns b))))
        left (d/compare (:end-ns a) (:start-ns b))
        right (d/compare (:end-ns b) (:start-ns a))]
    (if positive? (or (not (pos? left)) (not (pos? right)))
        (or (neg? left) (neg? right)))))
(m/=> separated? [:=> [:cat c/Interval c/Interval] :boolean])

(defn- compare-intervals
  "Order by exact start and then exact end, never decimal lexical order alone."
  [a b]
  (let [start (d/compare (:start-ns a) (:start-ns b))]
    (if (zero? start) (d/compare (:end-ns a) (:end-ns b)) start)))
(m/=> compare-intervals [:=> [:cat c/Interval c/Interval] :int])

(defn- append-interval
  "Merge touching or overlapping coverage; preserve real gaps."
  [result interval]
  (if-let [last-interval (peek result)]
    (if (d/before-or-equal? (:start-ns interval) (:end-ns last-interval))
      (if (pos? (d/compare (:end-ns interval) (:end-ns last-interval)))
        (conj (pop result) (assoc last-interval :end-ns (:end-ns interval))) result)
      (conj result interval))
    [interval]))
(m/=> append-interval [:=> [:cat Intervals c/Interval] Intervals])

(defn union
  "Return ordered disjoint occupied intervals; a reversed input is a named error."
  [intervals]
  (doseq [{:keys [start-ns end-ns]} intervals]
    (when-not (d/before-or-equal? start-ns end-ns)
      (throw (ex-info "Reversed interval" {:code :invalid-interval}))))
  (reduce append-interval [] (sort compare-intervals intervals)))
(m/=> union [:=> [:cat Intervals] Intervals])

(defn duration-sum
  "Sum interval lengths, including overlap; this quantity is not occupied time."
  [intervals]
  (reduce (fn [total {:keys [start-ns end-ns]}]
            (d/add total (d/subtract end-ns start-ns))) "0" intervals))
(m/=> duration-sum [:=> [:cat Intervals] c/Natural])

(defn summarize
  "Describe disjoint coverage, elapsed envelope and summed durations independently."
  [intervals]
  (let [occupied (union intervals)
        start (:start-ns (first occupied)) end (:end-ns (peek occupied))]
    {:count (count intervals) :start-ns start :end-ns end
     :occupied-ns (duration-sum occupied)
     :envelope-ns (if start (d/subtract end start) "0")
     :duration-sum-ns (duration-sum intervals)}))
(m/=> summarize [:=> [:cat Intervals] Summary])
