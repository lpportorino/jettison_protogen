(ns asgard.api.backend
  "Command backend protocol and implementations."
  (:require [asgard.proto :as proto]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(defprotocol CommandBackend
  (execute! [this client-id subsystem command cmd-path params]
    "Execute a command. client-id correlates to the SSE session.
     command: display name (e.g. \"set-zoom-table-value\")
     cmd-path: proto nesting path (e.g. \"zoom/set-zoom-table-value\")
     Returns a response map with :status and optional details."))

(defn cmd-path->map
  "Build a nested command map from a slash-separated proto path.
   \"set-magnetic-declination\" {:value 15} → {:set-magnetic-declination {:value 15}}
   \"zoom/set-zoom-table-value\" {:value 0} → {:zoom {:set-zoom-table-value {:value 0}}}"
  [^String cmd-path params]
  (let [segments (str/split cmd-path #"/")]
    (reduce (fn [m seg] {(keyword seg) m}) (or params {}) (reverse segments))))
(m/=> cmd-path->map
      [:=> [:cat [:string {:min 1}] [:maybe [:map-of :keyword :any]]]
       [:map-of :keyword :any]])

(defn logging-backend
  "Backend that logs the command, optionally validates via proto, and returns accepted.
   Used in tests and dev when cmd_server is unavailable."
  []
  (reify
    CommandBackend
    (execute! [_ _client-id subsystem command cmd-path params]
      (log/info "CMD"
                (str subsystem "/" command)
                (when (seq params) (str "params=" params)))
       ;; Try to build proto (validates field types via pronto)
      (try (proto/wrap-in-root subsystem (cmd-path->map cmd-path params))
           (catch Exception e (log/debug e "Proto build failed for" subsystem command)))
      {:status "accepted" :subsystem subsystem :command command :params params})))
(m/=> logging-backend [:=> [:cat] some?])

(defn platform-backend
  "Backend that routes commands to cmd_server via per-client WebSocket.
   Lazy-loads asgard.cmd-client to avoid circular deps."
  []
  (let [send-fn (delay (requiring-resolve 'asgard.cmd-client/send-command!))]
    (reify
      CommandBackend
      (execute! [_ client-id subsystem command cmd-path params]
        (@send-fn client-id subsystem command cmd-path params)))))
(m/=> platform-backend [:=> [:cat] some?])