(ns gate.report
  "One-file offline report assembly; all observations remain available as canonical EDN."
  (:require [clojure.string :as str]
            [gate.archive :as archive]
            [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.graph :as graph]
            [hiccup2.core :as h]
            [malli.core :as m])
  (:import [java.nio.charset StandardCharsets]))

(def Bundle [:string {:min 1 :max 16777216}])
(def Html [:string {:min 1 :max 167772160}])
(def ^:private max-html-bytes 167772160)
(def ^:private css
  ":root{color-scheme:dark;--bg:#10151d;--panel:#1b2735;--ink:#edf3fa;--muted:#bccbdb;--line:#52677d;--accent:#94d4ff;--mark:#6bb6ed;--mark-problem:#ed897d;--mark-run-a:#dcae57;--mark-run-b:#73562a;--mark-decision:#c4aedf;--mark-fold:#84bfbd;--mark-sample:#83bfba;--focus:#e99520} :root[data-light]{color-scheme:light;--bg:#f5f8fc;--panel:#e1eaf4;--ink:#18293b;--muted:#334d66;--line:#617a92;--accent:#135483;--mark:#2f72ad;--mark-problem:#b5402f;--mark-run-a:#8a6116;--mark-run-b:#f3dfa9;--mark-decision:#6d4fa0;--mark-fold:#2c7a76;--mark-sample:#2b7873;--focus:#a8590a}*{box-sizing:border-box}body{background:var(--bg);color:var(--ink);font:16px system-ui,sans-serif;margin:0;padding:20px}main{max-width:1400px;margin:auto}h1{font-size:1.55rem;margin:0 0 8px}h2{font-size:1.15rem;margin:12px 0}p{line-height:1.45;color:var(--muted);margin:8px 0;overflow-wrap:anywhere}button,input,select,summary{font:inherit;min-height:44px;min-width:44px}button,input,select{color:var(--ink);background:var(--panel);border:1px solid var(--line);border-radius:7px;padding:9px 12px}button{cursor:pointer;overflow-wrap:anywhere;text-align:left}button:hover{border-color:var(--accent)}button[aria-pressed=true]{background:var(--accent);color:var(--bg);border-color:var(--accent)}:focus-visible{outline:3px solid var(--focus);outline-offset:3px}h2[tabindex='-1']:focus{outline:none}nav,#view-controls{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}input{width:min(100%,420px)}input::placeholder{color:var(--muted);opacity:1}.search-controls{display:flex;gap:8px;align-items:center}.search-controls input{min-width:0;flex:1;max-width:420px}#attention-controls button{white-space:nowrap}label{display:block;max-width:100%}select{max-width:100%;display:block;margin:8px 0}summary{cursor:pointer;padding:10px 0}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--panel);padding:12px;font-size:13px;border-radius:7px}#timeline{position:relative}.row{position:relative;padding:8px 0;border-bottom:1px solid var(--line);display:grid;grid-template-columns:minmax(200px,30%) 1fr;gap:8px;align-items:center}.row-head,.fold-summary{position:relative;z-index:1;background:var(--bg)}.row-head{display:flex;flex-wrap:wrap;gap:6px;min-width:0}.row-head button:first-child{flex:1;min-width:44px}.track,.resource-track{position:relative;height:28px;background:var(--panel);overflow:hidden;border-radius:3px}.bar{position:absolute;height:100%;background:var(--mark);top:0}.failed,.error,.blocked,.refused,.cancelled{background:var(--mark-problem)}.running{background:repeating-linear-gradient(45deg,var(--mark-run-a),var(--mark-run-a) 5px,var(--mark-run-b) 5px,var(--mark-run-b) 10px)}.decision{width:2px!important;background:var(--mark-decision)}.fold{background:var(--mark-fold)}.fold-summary{grid-column:1/-1;font-size:13px;margin:0}.caption{font-size:13px}.ruler{position:relative;height:18px;margin:12px 0 8px calc(30% + 8px);font-size:13px;font-variant-numeric:tabular-nums}.ruler span{position:absolute;top:0;transform:translateX(-50%);white-space:nowrap}.ruler span:first-child{transform:none}.ruler span:last-child{transform:translateX(-100%)}.connectors{position:absolute;inset:0;width:100%;height:100%;pointer-events:none;overflow:hidden}.edge-casing{fill:none;stroke:var(--bg);stroke-width:3.5}.selected-casing{stroke-width:5}.edge{fill:none;stroke:var(--accent);stroke-width:1.5;opacity:.7}.declared{stroke-dasharray:5 4}.selected-edge{stroke-width:3;opacity:1}#resources{border-top:1px solid var(--line);padding:8px 0;margin:16px 0}#opportunities{border-left:3px solid var(--accent);padding-left:12px;margin:12px 0}#opportunities button{max-width:100%}#opportunities details button,#opportunities>button{margin:4px 6px 4px 0}.resource-row button{margin:4px 6px 4px 0}.nowrap{white-space:nowrap}.resource-row{display:grid;grid-template-columns:minmax(200px,30%) 1fr;align-items:center;gap:8px}.resource-row .caption{grid-column:1/-1}.resource-track{height:42px}.track::after,.resource-track::after{content:'';position:absolute;left:var(--cursor,-10%);top:0;height:100%;border-left:2px solid var(--focus);box-shadow:-1px 0 0 var(--bg),1px 0 0 var(--bg);pointer-events:none}.sample{position:absolute;bottom:0;background:var(--mark-sample)}.track-label{font-size:13px}.absent{border-left:3px solid var(--line);padding-left:12px}dialog{background:var(--bg);color:var(--ink);border:1px solid var(--line);border-radius:12px;width:min(780px,calc(100% - 24px));max-height:calc(100dvh - 24px);padding:0}dialog::backdrop{background:#0009}.dialog-head{position:sticky;top:0;background:var(--bg);padding:10px;display:flex;justify-content:flex-end;z-index:2}#details{padding:0 16px 20px}#details button{margin:4px 0;width:100%}.crumb{padding:10px}#status{font-weight:600;color:var(--ink)}#archive-status{font-size:14px}details{margin:8px 0}noscript{color:var(--ink)}@media(min-width:601px) and (max-width:1100px){.row-head{flex-direction:column}.row-head button{width:100%}}@media(max-width:600px){.introduction{display:none}#attention-controls button{font-size:14px;padding:8px}body{padding:12px}.row,.resource-row{grid-template-columns:1fr}.row-head{flex-wrap:wrap}.track{width:100%}.ruler{margin-left:0}.row-head button{font-size:14px}.row-head button:first-child{flex-basis:65%}h1{font-size:1.3rem}#view-controls{gap:6px}#view-controls button{padding:8px;font-size:14px}.resource-row .caption{grid-column:1}.fold-summary{grid-column:1}}")

(defn render
  "Embed a validated graph, optional bound archive metadata and trusted release bundle.
   Metadata includes declared scope and before/after provenance; the browser rechecks its graph
   binding before enabling drill controls. All data escapes the HTML script boundary.
   Refuse complete UTF-8 output above 160 MiB with :html-byte-limit before returning it."
  ([value bundle] (render value bundle nil))
  ([value bundle metadata]
   (graph/require-valid! value)
   (when metadata (archive/require-valid! (assoc metadata :graph (canonical/normalize-graph value))))
   (when (or (str/blank? bundle) (re-find #"(?i)</script" bundle))
     (throw (ex-info "Viewer bundle cannot be embedded" {:code :invalid-viewer-bundle})))
   (let [edn (canonical/encode (canonical/normalize-graph value) 134217728)
        ;; '<' only occurs within encoded string values; EDN escapes retain its value.
         embedded (str/replace edn "<" "\\u003c")
         metadata-edn (when metadata (canonical/encode metadata 8388608))
         html (str "<!doctype html>\n"
                   (h/html
                    [:html {:lang "en"}
                     [:head [:meta {:charset "utf-8"}]
                      [:meta {:name "viewport" :content "width=device-width,initial-scale=1"}]
                      [:meta {:http-equiv "Content-Security-Policy"
                              :content "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'none'; img-src data:; base-uri 'none'; form-action 'none'"}]
                      [:title "Gate execution report"] [:style (h/raw css)]]
                     [:body
                      [:main [:h1 "Gate execution report"]
                       [:p {:class "caption introduction"} "Chronological work, dependencies and resource context · fully offline"]
                       (when metadata
                         [:section {:aria-label "Run scope and provenance"}
                          [:h2 (get-in metadata [:scope :label])]
                          [:p {:id "archive-status"}
                           (str "Archive: " (name (:status metadata)) " · Scope: " (name (get-in metadata [:scope :kind]))
                                " · Repository evidence: " (name (get-in metadata [:provenance :stability])))]
                          (when (seq (get-in metadata [:scope :omissions]))
                            [:details [:summary "Scope omissions"] [:p (str "Outside this run: " (str/join "; " (get-in metadata [:scope :omissions])))]])
                          [:details [:summary "Archive identity and repository provenance"] [:pre metadata-edn]]])
                       [:p {:id "status" :role "status"} "Loading embedded graph…"]
                       [:section {:id "opportunities" :aria-label "Optimization opportunities"}]
                       [:nav {:id "breadcrumbs" :aria-label "Task ancestry"}]
                       [:label {:for "search"} "Find tasks"]
                       [:div {:class "search-controls"}
                        [:input {:id "search" :type "search" :maxlength "512" :placeholder "Name, ID, key or status"}]
                        [:div {:id "attention-controls"}]]
                       [:details [:summary "Timeline controls"] [:div {:id "view-controls" :aria-label "Timeline controls"}]
                        [:details [:summary "Time window and shared cursor"]
                         [:label {:for "window-start"} "Window start (%)"] [:input {:id "window-start" :type "range" :min "0" :max "99" :value "0"}]
                         [:label {:for "window-end"} "Window end (%)"] [:input {:id "window-end" :type "range" :min "1" :max "100" :value "100"}]
                         [:label {:for "time-cursor"} "Shared cursor (%)"] [:input {:id "time-cursor" :type "range" :min "0" :max "100" :value "50"}]]]
                       [:section {:id "timeline" :aria-label "Task timeline"}]
                       [:section {:id "resources" :aria-label "Aligned resource tracks"}]
                       [:dialog {:id "detail-dialog" :aria-label "Task details"}
                        [:div {:class "dialog-head"} [:button {:id "close-details" :type "button"} "Close details"]]
                        [:section {:id "details" :aria-label "Selected task data"}]]
                       [:details [:summary "Full canonical EDN (copy for offline tooling)"] [:pre edn]]
                       [:noscript "Enable JavaScript to drill the timeline. The full EDN remains available above."]]
                      [:script {:id "gate-data" :type "application/edn"} (h/raw embedded)]
                      (when metadata-edn
                        [:script {:id "archive-data" :type "application/edn"} (h/raw (str/replace metadata-edn "<" "\\u003c"))])
                      [:script (h/raw bundle)]]]))]
     (when (or (> (count html) max-html-bytes)
               (> (alength (.getBytes ^String html StandardCharsets/UTF_8)) max-html-bytes))
       (throw (ex-info "HTML output exceeds its byte limit" {:code :html-byte-limit})))
     html)))
(m/=> render [:function [:=> [:cat c/Graph Bundle] Html]
              [:=> [:cat c/Graph Bundle [:maybe ac/Metadata]] Html]])
