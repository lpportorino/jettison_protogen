(ns press.doc.report
  "`press pdf`'s `pdf_report.json`, read strictly into a verdict on ONE page.

   The report names the page it judged: `html_sha256` must equal the
   SHA-256 of the built page as it is NOW, else the report is
   `:doc/stale-report` (a missing field too) — a report is never read as a
   verdict on a page it did not print.

   Every field the verdict rests on must be present and of its type: a
   missing field, `findings` that is not a list, a finding without a
   detector, page, element, measure, severity or `allowed`, a page that is
   not an integer, a severity outside `blocking fix info`, a `layout.allowed`
   that is missing or not a list of names, a finding whose `allowed` does
   not say exactly whether its detector is in `layout.allowed` — each is a
   refusal (`:doc/bad-report`) naming it. Nothing in a malformed report can
   come back as `:ok? true`.

   The print fails on a `blocking` finding that was NOT allowed (the same
   set that makes `press pdf` exit 1); allowed findings come back apart in
   `:allowed`, so the caller sees what was waved through. `press pdf` writes
   from inside its container, where the output directory is `/out`; the
   PDF path is mapped back onto the directory the report was read from."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [malli.core :as m]
            [press.doc.figure :as fig]
            [press.doc.spec :as spec])
  (:import [java.io File]
           [java.nio.file Files]))

(set! *warn-on-reflection* true)

(def severities
  "The finding severities: only an un-allowed
   `blocking` fails the print."
  #{"blocking" "fix" "info"})

(def finding
  "One paged-layout detector finding."
  [:map {:closed true}
   [:detector spec/ne-string] [:page :int] [:element spec/any-string]
   [:measure spec/any-string] [:severity [:enum "blocking" "fix" "info"]] [:allowed :boolean]])

(def verdict
  "`pdf-report`'s answer. READ `:ok?` FIRST — true only when the PDF has
   pages, none blank, no dead or unresolved link, nothing wider than the
   printable width, no network attempt, and no `blocking` finding that was
   not allowed. `:allowed` lists the findings `--allow-layout` waved
   through. A green verdict is not a page review."
  [:map {:closed true}
   [:report spec/ne-string]
   [:ok? :boolean]
   [:pdf spec/ne-string]
   [:pages :int]
   [:blank-pages [:vector spec/any-string]]
   [:dead-links :int]
   [:unresolved [:vector spec/any-string]]
   [:overflow [:vector spec/any-string]]
   [:requests-failed :int]
   [:findings [:vector finding]]
   [:blocking [:vector finding]]
   [:allowed [:vector finding]]])

(def ^:private file? [:fn #(instance? File %)])

(defn- bad!
  "Refuse the report `f` for `what`."
  [f what]
  (throw (ex-info (str f ": " what) {:doc/refusal :doc/bad-report :path (str f)})))

(m/=> bad! [:=> [:cat spec/anything spec/ne-string] :nil])

(defn- field
  "`(get-in r path)`, which must satisfy `pred`, else a refusal naming the
   field and what it should be."
  [f r path pred what]
  (let [v (get-in r path ::missing)]
    (cond (= v ::missing) (bad! f (str (pr-str path) " is missing"))
          (not (pred v)) (bad! f (str (pr-str path) " is not " what ": " (pr-str v)))
          :else v)))

(m/=> field [:=> [:cat spec/anything [:map-of spec/anything spec/anything] vector? ifn? spec/ne-string] spec/anything])

(defn- read-finding
  "One raw finding, strictly; its `allowed` must say exactly whether its
   detector is in `allowed-set`."
  [f allowed-set i x]
  (when-not (map? x) (bad! f (str "findings[" i "] is not an object")))
  (let [g (fn [k pred what] (field f x [k] pred (str what " (findings[" i "])")))
        out {:detector (g "detector" #(and (string? %) (seq %)) "a non-empty string")
             :page (g "page" int? "an integer")
             :element (g "element" string? "a string")
             :measure (let [v (g "measure" some? "present")] (if (string? v) v (pr-str v)))
             :severity (g "severity" #(contains? severities %) "one of blocking, fix, info")
             :allowed (g "allowed" boolean? "true or false")}]
    (when-not (= (:allowed out) (contains? allowed-set (:detector out)))
      (bad! f (str "findings[" i "] says allowed " (:allowed out) " but detector " (:detector out)
                   (if (:allowed out) " is not" " is") " in layout.allowed")))
    out))

(m/=> read-finding [:=> [:cat spec/anything [:set spec/anything] :int spec/anything] finding])

(defn- host-pdf
  "The report's container PDF path (`/out/<name>.pdf`) mapped onto `dir`."
  [f dir p]
  (if-let [[_ nm] (re-matches #"/out/([^/]+\.pdf)" p)]
    (str (io/file dir nm))
    (bad! f (str "\"pdf\" is not a /out/<name>.pdf path: " p))))

(m/=> host-pdf [:=> [:cat spec/anything spec/anything spec/ne-string] spec/ne-string])

(defn- clean?
  "Does the read verdict `v` hold nothing that fails the print?"
  [v]
  (and (pos? (:pages v)) (empty? (:blocking v)) (empty? (:blank-pages v))
       (zero? (:dead-links v)) (empty? (:unresolved v)) (empty? (:overflow v))
       (zero? (:requests-failed v))))

(m/=> clean? [:=> [:cat [:map [:pages :int]]] :boolean])

(defn- read-json
  "The JSON object in report file `f`, or a refusal."
  [^File f]
  (when-not (.isFile f)
    (throw (ex-info (str "no " f " — run the `pdf` command first")
                    {:doc/refusal :doc/no-report :path (str f)})))
  (let [r (try (json/parse-string (slurp f)) (catch Exception e (bad! f (str "not JSON: " (ex-message e)))))]
    (when-not (map? r) (bad! f "not a JSON object"))
    r))

(m/=> read-json [:=> [:cat file?] [:map-of spec/anything spec/anything]])

(defn- same-page!
  "Refuse `:doc/stale-report` unless report `r` names `page` by its hash."
  [f r ^File page]
  (let [want (fig/sha256 (Files/readAllBytes (.toPath page)))
        got (get r "html_sha256")]
    (when-not (= want got)
      (throw (ex-info (str f (if got " judged another page (html_sha256 " " names no page (no html_sha256")
                           (when got (str got " ≠ " want)) ") — print the current page again")
                      {:doc/refusal :doc/stale-report :path (str f)})))))

(m/=> same-page! [:=> [:cat spec/anything [:map-of spec/anything spec/anything] file?] :nil])

(defn read-verdict
  "The strict verdict of `pdf_report.json` in directory `dir` (a File) on
   the built `page` (a File): stale, malformed or a verdict."
  [dir page]
  (let [f (io/file dir "pdf_report.json")
        r (read-json f)
        _ (same-page! f r page)
        strs #(and (vector? %) (every? string? %))
        allowed-set (set (field f r ["layout" "allowed"] strs "a list of detector names"))
        fs (vec (map-indexed #(read-finding f allowed-set %1 %2) (field f r ["findings"] vector? "a list")))
        v {:report (str f)
           :pdf (host-pdf f dir (field f r ["pdf"] string? "a string"))
           :pages (field f r ["page_count"] int? "an integer")
           :blank-pages (field f r ["blank_pages"] strs "a list of names")
           :dead-links (field f r ["links" "dead_links"] int? "an integer")
           :unresolved (field f r ["links" "html_unresolved"] strs "a list of ids")
           :overflow (mapv #(str (get % "what") " " (get % "how"))
                           (field f r ["overflow" "offenders"] #(and (vector? %) (every? map? %)) "a list of objects"))
           :requests-failed (count (field f r ["requests_failed"] vector? "a list"))
           :findings fs
           :blocking (filterv #(and (= "blocking" (:severity %)) (not (:allowed %))) fs)
           :allowed (filterv :allowed fs)}]
    (assoc v :ok? (clean? v))))

(m/=> read-verdict [:=> [:cat file? file?] verdict])
