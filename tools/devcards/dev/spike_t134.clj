(ns spike-t134
  "T1.3.4 family smoke on the freshly themed ABI-3 wasm (vr_button, dark):
     family=2 (stock-skip) must BIT-EQUAL the recorded pre-theme stock hash
       1b88ace6... — proves the child theme adds NOTHING under family 2;
     family=1 (vanilla)  must equal family=2 — the FIRST vanilla≡stock
       idempotency proof on a real card;
     family=0 (asgard)   must DIFFER — the theme actually changes pixels.
   Fresh Context per family (hermetic; family switch also exercises the
   in-place path via set-theme-family! on a 4th render)."
  (:require [devcards.host :as host])
  (:import [java.security MessageDigest]))

(def ^:private stock-hash
  "vr_button dark under the PRE-theme stock wasm (R2.2 spike record)."
  "1b88ace6e0ca79ec44b47ef44950938687d12025c014a49e8fbea9971cbcb176")

(defn- sha256-hex
  ^String [^bytes b]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") b))))

(defn- render-family
  ^String [ws ^long family]
  (let [h
        (host/start!
         {:wasm (str ws "/output/controls.wasm") :assets (str ws "/assets") :w 480 :h 320})]
    (try (when (pos? family) (host/set-theme-family! h family))
         (sha256-hex (host/render-card! h
                                        {:pb (java.nio.file.Files/readAllBytes
                                              (java.nio.file.Path/of
                                               (str ws "/output/fixtures/vr_button.pb")
                                               (into-array String [])))
                                         :bp 0
                                         :dark 1}))
         (finally (host/close! h)))))

(defn -main
  [& _]
  (let [ws "../../renderer"
        stock (render-family ws 2)
        vanilla (render-family ws 1)
        asgard (render-family ws 0)]
    (println "family2/stock  :" stock)
    (println "family1/vanilla:" vanilla)
    (println "family0/asgard :" asgard)
    (println "stock == pre-theme record?" (= stock stock-hash))
    (println "vanilla == stock?         " (= vanilla stock))
    (println "asgard != stock?          " (not= asgard stock))
    (if (and (= stock stock-hash) (= vanilla stock) (not= asgard stock))
      (println "T1.3.4 SMOKE OK")
      (do (binding [*out* *err*] (println "T1.3.4 SMOKE FAIL")) (System/exit 1)))))