(ns gate.report-test
  "The HTML boundary preserves data while preventing script termination through graph labels."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.canonical :as canonical]
            [gate.fixtures :as fixtures]
            [gate.report :as report]))

(deftest hostile-labels-remain-data-in-both-embedded-and-visible-edn
  (let [graph (assoc-in (fixtures/example) [:nodes 0 :label] "</script><script>alert('label')</script>")
        html (report/render graph "window.reportLoaded = true;")
        data (second (re-find #"(?s)<script id=\"gate-data\" type=\"application/edn\">(.*?)</script>" html))]
    (is (= 2 (count (re-seq #"</script>" html))))
    (is (str/includes? html "connect-src &apos;none&apos;"))
    (is (not (re-find #"<(?:script|link)[^>]+(?:src|href)=" html)))
    (is (= (canonical/normalize-graph graph)
           (admission/decode data :graph admission/default-limits)))))

(deftest release-bundles-cannot-break-out-of-the-script-element
  (doseq [bundle [" " "/* </script> */" "/* </ScRiPt> */"]]
    (is (= :invalid-viewer-bundle
           (try (report/render (fixtures/example) bundle) nil
                (catch clojure.lang.ExceptionInfo error (:code (ex-data error))))))))
