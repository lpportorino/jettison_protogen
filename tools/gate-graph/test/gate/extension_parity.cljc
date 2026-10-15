(ns gate.extension-parity
  "Identical public fixture bytes, identities, pages and refusals on JVM and JavaScript."
  (:require [clojure.string :as str]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.extension :as extension]
            [gate.extension-contract :as ec]
            [gate.extension-fixture :as fixture]
            [gate.extension-view :as view]))

(defn failure [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error (:code (ex-data error)))))
(defn result []
  (let [{:keys [context core pair sidecar]} (fixture/example)
        encoded (extension/encode pair) limits (assoc admission/default-limits :bytes (inc (:sidecar-bytes ec/limits)))
        decoded (extension/decode context core encoded limits)
        page (view/page decoded {:path [:observations 0 :payload :cache :bytes]
                                 :budget {:rows 1 :visits 1 :bytes 4096}})
        unknown (failure #(extension/decode context core (str/replace encoded ":hit" ":unregistered/new") limits))
        trailing (failure #(extension/decode context core (str encoded "{}") limits))
        bounded (failure #(extension/decode context core encoded (assoc limits :bytes 12)))
        checks [{:case "whole-sidecar-roundtrip" :passed (= sidecar (:sidecar decoded))}
                {:case "exact-large-natural" :passed (= "900719925474099312345" (get-in page [:container :value]))}
                {:case "non-bmp" :passed (= "Portable 🪐" (get-in sidecar [:observations 0 :payload :label]))}
                {:case "finite-vocabulary" :passed (= :unknown-keyword unknown)}
                {:case "trailing-input" :passed (= :trailing-input trailing)}
                {:case "byte-bound" :passed (= :input-byte-limit bounded)}]]
    (when-not (every? :passed checks) (throw (ex-info "Portable extension parity failed" {:checks checks})))
    {:checks checks :core/artifact (:artifact core) :artifact (:artifact sidecar)
     :encoded encoded :page page :refusals [unknown trailing bounded]}))
(defn -main [& _] (println (canonical/encode-portable (result) 65536)))
