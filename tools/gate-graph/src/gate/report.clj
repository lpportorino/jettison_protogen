(ns gate.report
  "One-file offline report assembly; all observations remain available as canonical EDN."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.graph :as graph]
            [hiccup2.core :as h]
            [malli.core :as m]))

(def Bundle [:string {:min 1 :max 16777216}])
(def Html [:string {:min 1 :max 167772160}])
(def ^:private css
  "body{background:#10151d;color:#e7edf5;font:16px system-ui,sans-serif;margin:0;padding:2rem}main{max-width:1400px;margin:auto}h1{font-size:1.8rem}p{line-height:1.5;color:#bdcada}button,a{color:#9fd6ff}button{background:#233347;border:1px solid #60738b;border-radius:5px;padding:.45rem .7rem;cursor:pointer}button:focus-visible,a:focus-visible{outline:3px solid #ffc477}button:hover{background:#344b65}nav{display:flex;gap:.5rem;flex-wrap:wrap;margin:1rem 0}.row{display:grid;grid-template-columns:minmax(170px,30%) 1fr auto;gap:1rem;align-items:center;margin:.7rem 0}.track{height:28px;background:#1d2938;border-radius:4px;position:relative}.bar{position:absolute;top:0;height:28px;min-width:2px;background:#5892c9;border-radius:3px}.failed,.error{background:#e58f87}.running{background:repeating-linear-gradient(45deg,#cfb26d,#cfb26d 5px,#88784f 5px,#88784f 10px)}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#192332;padding:1rem;border-radius:5px;font-size:13px}.axis{display:flex;justify-content:space-between;color:#bdcada;margin-left:30%;font-size:13px}.actions{display:flex;gap:.5rem}#status{color:#ffd28e}details{margin:1rem 0}summary{cursor:pointer}#details{border-top:1px solid #60738b;margin-top:2rem}noscript{color:#ffd28e}")

(defn render
  "Embed a validated graph and trusted release bundle, escaping the HTML script boundary."
  [value bundle]
  (graph/require-valid! value)
  (when (or (str/blank? bundle) (re-find #"(?i)</script" bundle))
    (throw (ex-info "Viewer bundle cannot be embedded" {:code :invalid-viewer-bundle})))
  (let [edn (canonical/encode (canonical/normalize-graph value) 134217728)
        ;; '<' only occurs within encoded string values; EDN escapes retain its value.
        embedded (str/replace edn "<" "\\u003c")]
    (str "<!doctype html>\n"
         (h/html
          [:html {:lang "en"}
           [:head [:meta {:charset "utf-8"}]
            [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
            [:meta {:http-equiv "Content-Security-Policy"
                    :content "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src data:; base-uri 'none'; form-action 'none'"}]
            [:title "Gate execution report"] [:style (h/raw css)]]
           [:body
            [:main [:h1 "Gate execution report"]
             [:p "Explore concurrent work by logical task. Each parent opens its own timeline. Width is elapsed time; lanes are tasks, not CPU cores."]
             [:p "CPU observations include waited descendants. Overlapping inclusive CPU totals and memory peaks must not be added. Background pressure and instruction counts appear only when collected."]
             [:p {:id "status" :role "status"} "Loading embedded graph…"]
             [:nav {:id "breadcrumbs" :aria-label "Task ancestry"}]
             [:section {:id "timeline" :aria-label "Task timeline"}]
             [:section {:id "details" :aria-label "Selected task data"}]
             [:details [:summary "Full canonical EDN (copy for offline tooling)"] [:pre edn]]
             [:noscript "Enable JavaScript to drill the timeline. The full EDN remains available above."]]
            [:script {:id "gate-data" :type "application/edn"} (h/raw embedded)]
            [:script (h/raw bundle)]]]))))
(m/=> render [:=> [:cat c/Graph Bundle] Html])
