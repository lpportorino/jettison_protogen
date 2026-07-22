(ns asgard.proto
  "Facade — re-exports proto functions from split sub-namespaces.
   Keeps all existing (:require [asgard.proto :as proto]) call sites working."
  (:require [asgard.proto.cmd :as cmd]
            [asgard.proto.state :as state]))

(set! *warn-on-reflection* true)

;; Re-export state functions
(def bytes->state state/bytes->state)

(def state->bytes state/state->bytes)

;; Re-export command functions
(def wrap-in-root cmd/wrap-in-root)

(def build-ping cmd/build-ping)