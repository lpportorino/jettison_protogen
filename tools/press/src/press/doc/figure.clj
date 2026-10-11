(ns press.doc.figure
  "Figure descriptions, their canonical JSON, and their freshness.

   A figure is authored as `<doc>/figs/<id>.edn`. The browser half solves it
   into a pick record (`<id>.pick.json`) and an SVG (`<id>.svg`), both
   committed beside it. This namespace:

     canonical-json — the description as JSON with keywords as strings, keys
                      sorted, no whitespace: the same data in any key order is
                      the same bytes, so its sha256 is the figure's PROBLEM
                      HASH
     house          — the house constants, read from the browser half's ONE
                      copy: the configured `:house` file (`press.config`, by
                      default `tools/press/press/house.json`) under the
                      configured root
     read-description — a description that `press.doc.desc` (the mirror of
                      `desc.py`) accepts, or why not
     fig-status     — :current / :stale / :unpicked / :missing-svg / :invalid
                      per figure, by comparing the description's problem hash
                      with the pick's `problem_hash` and the SVG's sha256 with
                      the pick's `svg_sha256`

   The canonical bytes are exactly what the browser half's own writer
   produces — Python's `json.dumps(tree, sort_keys=True, ensure_ascii=False,
   separators=(',', ':'))` plus one newline, UTF-8 — and it REFUSES a file
   whose bytes differ, so the two halves cannot disagree silently. A figure
   description carries INTEGERS only: a fractional number has no form both
   languages print alike, so it is refused here rather than hashed."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [press.config :as config]
            [press.doc.desc :as desc]
            [press.doc.spec :as spec])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files]
           [java.security MessageDigest]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------- canonical JSON

(defn- json-string
  "`s` as a JSON string literal: `\"` and `\\` escaped, the short escapes for
   \\b \\f \\n \\r \\t, `\\u00xx` for every other control character, and
   everything else (non-ASCII included) as itself. A string holding a lone
   surrogate is not UTF-8 text and is refused."
  [^String s]
  (when-not (.canEncode (.newEncoder StandardCharsets/UTF_8) s)
    (throw (ex-info (str "figure string holds a lone surrogate: " (pr-str s))
                    {:doc/refusal :doc/not-json :value (pr-str s)})))
  (let [sb (StringBuilder. "\"")]
    (doseq [c s]
      (let [code (int c)]
        (case c
          \" (.append sb "\\\"")
          \\ (.append sb "\\\\")
          \backspace (.append sb "\\b")
          \formfeed (.append sb "\\f")
          \newline (.append sb "\\n")
          \return (.append sb "\\r")
          \tab (.append sb "\\t")
          (if (< code 0x20)
            (.append sb (format "\\u%04x" code))
            (.append sb c)))))
    (str (.append sb "\""))))

(m/=> json-string [:=> [:cat spec/any-string] spec/ne-string])

(defn- json-number
  "An integer as itself. Any other number is refused (`:doc/not-json`): the
   two halves print fractions differently, so a description holds none."
  [x]
  (if (or (int? x) (instance? clojure.lang.BigInt x) (instance? java.math.BigInteger x))
    (str x)
    (throw (ex-info (str "figure numbers are integers only, got " x)
                    {:doc/refusal :doc/not-json :value (str x)}))))

(m/=> json-number [:=> [:cat number?] spec/ne-string])

(defn- json-key
  "A map key as its JSON string: a keyword's name (with namespace), or a
   string. Anything else is refused."
  [k]
  (cond (keyword? k) (subs (str k) 1)
        (string? k) k
        :else (throw (ex-info (str "figure map key is not a keyword or string: " (pr-str k))
                              {:doc/refusal :doc/not-json :value (pr-str k)}))))

(m/=> json-key [:=> [:cat spec/anything] spec/any-string])

(declare json-value)

(defn code-point-compare
  "Compare two strings by Unicode code point — Python's string order, which
   `json.dumps(sort_keys=True)` uses. Java's `compareTo` compares UTF-16
   units and puts an astral-plane character (a surrogate pair) BEFORE
   U+E000..U+FFFF; this does not."
  [^String a ^String b]
  (let [xa (.toArray (.codePoints a))
        xb (.toArray (.codePoints b))
        n (min (alength xa) (alength xb))]
    (loop [i 0]
      (if (< i n)
        (let [c (Integer/compare (aget xa i) (aget xb i))]
          (if (zero? c) (recur (inc i)) c))
        (Integer/compare (alength xa) (alength xb))))))

(m/=> code-point-compare [:=> [:cat spec/any-string spec/any-string] :int])

(defn- json-object
  "A map with its keys sorted by code point of their JSON string; two keys
   that collapse onto one string (`:a` and \"a\") are refused."
  [mp]
  (let [entries (sort-by first code-point-compare (map (fn [[k v]] [(json-key k) v]) mp))]
    (when-not (apply distinct? (map first entries))
      (throw (ex-info "figure map has two keys with the same JSON name"
                      {:doc/refusal :doc/not-json :keys (mapv first entries)})))
    (str "{" (str/join "," (map (fn [[k v]] (str (json-string k) ":" (json-value v))) entries)) "}")))

(m/=> json-object [:=> [:cat [:and [:map-of spec/anything spec/anything] [:fn seq]]] spec/ne-string])

(defn- json-coll
  "A map or a sequence as canonical JSON; anything else is refused."
  [x]
  (cond (and (map? x) (empty? x)) "{}"
        (map? x) (json-object x)
        (sequential? x) (str "[" (str/join "," (map json-value x)) "]")
        :else (throw (ex-info (str "figure value has no JSON form: " (pr-str x))
                              {:doc/refusal :doc/not-json :value (pr-str x)}))))

(m/=> json-coll [:=> [:cat spec/anything] spec/ne-string])

(defn- json-value
  "`x` as canonical JSON text, no trailing newline."
  [x]
  (cond (nil? x) "null"
        (boolean? x) (str x)
        (string? x) (json-string x)
        (keyword? x) (json-string (subs (str x) 1))
        (number? x) (json-number x)
        :else (json-coll x)))

(m/=> json-value [:=> [:cat spec/anything] spec/ne-string])

(defn canonical-json
  "`x` (EDN data: maps, vectors, strings, keywords, integers, booleans,
   nil) as canonical JSON — keys sorted, keywords as strings, no whitespace,
   one trailing newline. Refuses anything else (`:doc/not-json`), a
   fractional number included, rather than guessing a form."
  [x]
  (str (json-value x) "\n"))

(m/=> canonical-json [:=> [:cat spec/anything] spec/ne-string])

(def sha256-hex
  "A lowercase hex sha256 (the whole string)."
  (spec/whole #"[0-9a-f]{64}"))

(defn sha256
  "Lowercase hex sha256 of `data` (bytes, or a string as UTF-8)."
  [data]
  (let [^bytes b (if (string? data) (.getBytes ^String data StandardCharsets/UTF_8) data)
        md (MessageDigest/getInstance "SHA-256")]
    (str/join (map #(format "%02x" (bit-and (int %) 0xff)) (.digest md b)))))

(m/=> sha256 [:=> [:cat [:or spec/any-string bytes?]] sha256-hex])

(defn house
  "The house constants (string keys), read from the configured `:house`
   file under the configured root (`press.config` — by default
   `tools/press/press/house.json` under the repository root, never the
   JVM's working directory). Refuses `:doc/no-house` when the file is
   missing or lacks a constant `press.doc` reads."
  []
  (let [f (io/file (config/from-root (config/house)))]
    (when-not (.isFile f)
      (throw (ex-info (str "no house constants at " f) {:doc/refusal :doc/no-house :path (str f)})))
    (let [h (json/parse-string (slurp f))]
      (when-not (m/validate desc/house-spec h)
        (throw (ex-info (str f " does not carry the constants press.doc reads")
                        {:doc/refusal :doc/no-house :explain (pr-str (me/humanize (m/explain desc/house-spec h)))})))
      h)))

(m/=> house [:=> [:cat] desc/house-spec])

;; ------------------------------------------------------------- the files

(def fig-id
  "A figure id — also its file stem."
  (spec/whole desc/id-re))

(defn fig-file
  "The file `<doc>/figs/<id><suffix>`."
  ^File [doc-dir id suffix]
  (io/file doc-dir "figs" (str id suffix)))

(m/=> fig-file [:=> [:cat spec/ne-string fig-id spec/any-string] [:fn #(instance? File %)]])

(defn description-ids
  "The stems of every `figs/*.edn` description in `doc-dir`, sorted."
  [doc-dir]
  (let [d (io/file doc-dir "figs")]
    (vec (sort (for [^File f (or (.listFiles d) [])
                     :let [n (.getName f)]
                     :when (and (.isFile f) (str/ends-with? n ".edn"))]
                 (subs n 0 (- (count n) 4)))))))

(m/=> description-ids [:=> [:cat spec/ne-string] [:vector spec/ne-string]])

(def read-result
  "A description read from disk: the data and its canonical JSON, or why it
   cannot be used."
  [:or [:map {:closed true} [:ok? [:= true]] [:fig spec/edn-map] [:json spec/ne-string]]
   [:map {:closed true} [:ok? [:= false]] [:reason spec/ne-string]]])

(defn- parse-edn
  "The EDN value in file `f`, or `{::unreadable message}`."
  [^File f]
  (try (edn/read-string (slurp f))
       (catch Exception e {::unreadable (ex-message e)})))

(m/=> parse-edn [:=> [:cat [:fn #(instance? File %)]] spec/anything])

(defn- to-json
  "`x`'s canonical JSON, or `{::refused message}` when it has none."
  [x]
  (try (canonical-json x)
       (catch clojure.lang.ExceptionInfo e {::refused (ex-message e)})))

(m/=> to-json [:=> [:cat spec/anything] [:or spec/ne-string [:map-of :keyword spec/ne-string]]])

(defn- tree-problem
  "Why the canonical JSON `j` of figure `id` is refused, or nil."
  [j id hs]
  (let [t (json/parse-string j)
        ps (desc/problems t hs)]
    (cond (seq ps) (str "is refused: " (str/join "; " (map #(str (subs (str (:rule %)) 1) " " (:message %)) ps)))
          (not= id (get t "id")) (str "carries id " (pr-str (get t "id")) ", not its file stem " id)
          :else nil)))

(m/=> tree-problem [:=> [:cat spec/ne-string spec/ne-string desc/house-spec] [:maybe spec/ne-string]])

(defn- description-problem
  "Why the parsed description `x` of figure `id` cannot be used, or nil."
  [x id hs]
  (cond (and (map? x) (::unreadable x)) (str "is not readable EDN: " (::unreadable x))
        (not (map? x)) "is not a map"
        :else (let [j (to-json x)]
                (if (map? j) (::refused j) (tree-problem j id hs)))))

(m/=> description-problem [:=> [:cat spec/anything spec/ne-string desc/house-spec] [:maybe spec/ne-string]])

(defn read-description
  "Read and judge `figs/<id>.edn` exactly as `press` will judge its canonical
   JSON: it must exist, parse, have a canonical form (integers only), pass
   `press.doc.desc/problems`, and carry its own file stem as `:id`. A
   `:reason` names files relative to the document directory, never by an
   absolute path (it is copied into RUNBOOK.md)."
  [doc-dir id hs]
  (let [f (fig-file doc-dir id ".edn")]
    (if-not (.isFile f)
      {:ok? false :reason (str "no description file figs/" id ".edn")}
      (let [x (parse-edn f)]
        (if-let [p (description-problem x id hs)]
          {:ok? false :reason (str "figs/" id ".edn " p)}
          {:ok? true :fig x :json (canonical-json x)})))))

(m/=> read-description [:=> [:cat spec/ne-string fig-id desc/house-spec] read-result])

(defn- read-pick
  "The pick record's two hashes, or a reason string when it cannot be read."
  [^File f id]
  (let [p (try (json/parse-string (slurp f)) (catch Exception e (ex-message e)))]
    (if (and (map? p) (m/validate sha256-hex (get p "problem_hash")) (m/validate sha256-hex (get p "svg_sha256")))
      {:problem-hash (get p "problem_hash") :svg-sha256 (get p "svg_sha256")}
      (str "figs/" id ".pick.json is not a pick record with problem_hash and svg_sha256"))))

(m/=> read-pick [:=> [:cat [:fn #(instance? File %)] spec/ne-string]
                 [:or spec/ne-string [:map {:closed true} [:problem-hash sha256-hex] [:svg-sha256 sha256-hex]]]])

(defn- state-of
  "A readable description's state against its pick record and SVG."
  [doc-dir id json-text]
  (let [pick-f (fig-file doc-dir id ".pick.json")
        svg-f (fig-file doc-dir id ".svg")
        pick (when (.isFile pick-f) (read-pick pick-f id))]
    (cond (nil? pick) {:state :unpicked :reason (str "no pick record figs/" id ".pick.json")}
          (string? pick) {:state :invalid :reason pick}
          (not (.isFile svg-f)) {:state :missing-svg :reason (str "no SVG figs/" id ".svg")}
          (not= (:problem-hash pick) (sha256 json-text))
          {:state :stale :reason "the description changed since the pick (problem_hash differs)"}
          (not= (:svg-sha256 pick) (sha256 (Files/readAllBytes (.toPath svg-f))))
          {:state :stale :reason "the SVG bytes differ from the pick's svg_sha256"}
          :else {:state :current})))

(m/=> state-of [:=> [:cat spec/ne-string fig-id spec/ne-string]
                [:map [:state spec/fig-states] [:reason {:optional true} spec/ne-string]]])

(defn status
  "The state of figure `id` in `doc-dir` (see `spec/fig-states`)."
  [doc-dir id hs]
  (if-not (m/validate fig-id id)
    {:id id :state :invalid :reason (str "figure id " (pr-str id) " is not [A-Za-z0-9][A-Za-z0-9_-]*")}
    (let [{:keys [ok? json reason]} (read-description doc-dir id hs)]
      (merge {:id id}
             (if ok? (state-of doc-dir id json) {:state :invalid :reason reason})))))

(m/=> status [:=> [:cat spec/ne-string spec/ne-string desc/house-spec] spec/fig-status-entry])
