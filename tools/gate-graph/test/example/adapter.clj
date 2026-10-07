(ns example.adapter
  "Synthetic consumer namespace for explicit diagnostic enrollment tests."
  (:require [malli.core :as m]))

(defn count-value
  "Return a value whose contract requires a nonnegative count."
  [value]
  value)
(m/=> count-value [:=> [:cat [:int {:min 0 :max 1000}]] [:int {:min 0 :max 1000}]])
