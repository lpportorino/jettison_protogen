(ns gate.report
  "One-file offline report assembly; all observations remain available as canonical EDN."
  (:require [clojure.string :as str]
            [gate.archive :as archive]
            [gate.archive-contract :as ac]
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
  "Embed a validated graph, optional bound archive metadata and trusted release bundle.
   Metadata includes declared scope and before/after provenance; the browser rechecks its graph
   binding before enabling drill controls. All data escapes the HTML script boundary."
  ([value bundle] (render value bundle nil))
  ([value bundle metadata]
   (graph/require-valid! value)
   (when metadata (archive/require-valid! (assoc metadata :graph (canonical/normalize-graph value))))
   (when (or (str/blank? bundle) (re-find #"(?i)</script" bundle))
     (throw (ex-info "Viewer bundle cannot be embedded" {:code :invalid-viewer-bundle})))
   (let [edn (canonical/encode (canonical/normalize-graph value) 134217728)
        ;; '<' only occurs within encoded string values; EDN escapes retain its value.
         embedded (str/replace edn "<" "\\u003c")
         metadata-edn (when metadata (canonical/encode metadata 8388608))]
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
              (when metadata
                [:section {:aria-label "Run scope and provenance"}
                 [:h2 (get-in metadata [:scope :label])]
                 [:p {:id "archive-status"}
                  (str "Archive: " (name (:status metadata)) " · Scope: " (name (get-in metadata [:scope :kind]))
                       " · Repository evidence: " (name (get-in metadata [:provenance :stability])))]
                 (when (seq (get-in metadata [:scope :omissions]))
                   [:p (str "Outside this run: " (str/join "; " (get-in metadata [:scope :omissions])))])
                 [:details [:summary "Archive identity and repository provenance"] [:pre metadata-edn]]])
              [:p {:id "status" :role "status"} "Loading embedded graph…"]
              [:nav {:id "breadcrumbs" :aria-label "Task ancestry"}]
              [:section {:id "timeline" :aria-label "Task timeline"}]
              [:section {:id "details" :aria-label "Selected task data"}]
              [:details [:summary "Full canonical EDN (copy for offline tooling)"] [:pre edn]]
              [:noscript "Enable JavaScript to drill the timeline. The full EDN remains available above."]]
             [:script {:id "gate-data" :type "application/edn"} (h/raw embedded)]
             (when metadata-edn
               [:script {:id "archive-data" :type "application/edn"} (h/raw (str/replace metadata-edn "<" "\\u003c"))])
             [:script (h/raw bundle)]]])))))
(m/=> render [:function [:=> [:cat c/Graph Bundle] Html]
              [:=> [:cat c/Graph Bundle [:maybe ac/Metadata]] Html]])
