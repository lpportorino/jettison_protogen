(ns example.adapter.child
  "Synthetic namespace that must not inherit its parent's diagnostic enrollment."
  (:require [malli.core :as m]))

(defn count-value
  "Return a value whose contract is dormant until this exact namespace is enrolled."
  [value]
  value)
(m/=> count-value [:=> [:cat [:int {:min 0 :max 1000}]] [:int {:min 0 :max 1000}]])
