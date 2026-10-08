(ns gate.repository-git
  "Bounded read-only Git protocol for trusted working trees; no inherited Git environment."
  (:require [clojure.string :as str]
            [gate.inputs :as inputs]
            [gate.process :as process]
            [gate.repository-contract :as rc]
            [gate.run-contract :as r]
            [malli.core :as m])
  (:import [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets CodingErrorAction CharacterCodingException]
           [java.nio.file Files Path OpenOption LinkOption]
           [java.util UUID]))

(set! *warn-on-reflection* true)
(def Text [:string {:max 67108864}])
(def CountLimit [:int {:min 1 :max 1000000}])
(def Settings
  [:map {:closed true} [:git inputs/Root] [:log-directory inputs/Root]
   [:timeout-ms [:int {:min 1 :max 600000}]] [:output-bytes [:int {:min 1 :max 67108864}]]])
(def Arguments [:vector {:min 1 :max 32} process/Argument])
(def environment
  {"PATH" "/usr/bin:/bin" "LC_ALL" "C" "GIT_CONFIG_NOSYSTEM" "1"
   "GIT_CONFIG_GLOBAL" "/dev/null" "GIT_TERMINAL_PROMPT" "0" "GIT_NO_REPLACE_OBJECTS" "1"})

(defn refuse!
  "Raise a closed repository diagnostic without paths, command output or native exception text."
  [code]
  (throw (ex-info "Repository observation refused" {:code code})))
(m/=> refuse! [:=> [:cat rc/Code] :nil])

(defn budget!
  "Report the exceeded resource with exact decimal quantities and no private path or value."
  [kind used maximum]
  (throw (ex-info "Repository budget exhausted"
                  {:code :repository-budget :limit kind :used (str used) :maximum (str maximum)})))
(m/=> budget! [:=> [:cat rc/BudgetKind [:int {:min 0 :max 9007199254740991}]
                    [:int {:min 1 :max 9007199254740991}]] :nil])

(defn- read-output!
  "Bound actual retained bytes and require strict UTF-8; never replace malformed Git filenames."
  [directory file limit]
  (with-open [stream (Files/newInputStream (.resolve (Path/of directory (make-array String 0)) ^String file)
                                           (into-array OpenOption [LinkOption/NOFOLLOW_LINKS]))]
    (let [content (.readNBytes stream (int (inc limit)))]
      (when (> (alength content) limit) (refuse! :repository-budget))
      (try (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                           (.onMalformedInput CodingErrorAction/REPORT)
                           (.onUnmappableCharacter CodingErrorAction/REPORT)) (ByteBuffer/wrap content)))
           (catch CharacterCodingException _ (refuse! :repository-protocol))))))
(m/=> read-output! [:=> [:cat inputs/Root r/LogFileName [:int {:min 1 :max 67108864}]] Text])

(defn command!
  "Execute read-only Git argv through the bounded process supervisor and retain its local log.
   Refuse nonzero exit, timeout, truncation or incomplete observed-process cleanup. Environment
   is cleared, system/global config and fsmonitor disabled; local repository config stays input.
   Callers must verify Git's top-level directory before trusting a checkout boundary."
  ([directory settings arguments] (command! directory settings arguments (atom false)))
  ([directory settings arguments cancellation]
   (let [log (str "git-" (UUID/randomUUID) ".log")
         result (process/run! {:directory directory :cwd "." :stdin nil
                               :command (into [(:git settings) "--no-optional-locks" "--literal-pathspecs"
                                               "-c" "core.fsmonitor=false" "-c" "core.untrackedCache=false"] arguments)
                               :environment environment :log-directory (:log-directory settings) :log log
                               :limits {:timeout-ms (:timeout-ms settings) :cleanup-ms 1000
                                        :log-bytes (:output-bytes settings) :processes 32 :stdin-bytes 1}}
                              cancellation)]
     (when-not (and (= :exited (:status result)) (= 0 (:exit result))
                    (not (:cleanup-required? result)) (:observed-processes-stopped? result)
                    (:log result) (not (get-in result [:log :truncated?])))
       (refuse! (if @cancellation :repository-cancelled :repository-git)))
     (read-output! (:log-directory settings) log (:output-bytes settings)))))
(m/=> command! [:function [:=> [:cat inputs/Root Settings Arguments] Text]
                [:=> [:cat inputs/Root Settings Arguments inputs/Budget] Text]])

(defn- records
  "Split NUL records incrementally, charging membership before retaining another bounded token."
  [text limit]
  (loop [start 0 result []]
    (if (= start (count text)) result
        (let [end (.indexOf ^String text (int 0) (int start))]
          (when (or (neg? end) (= end start) (> (- end start) 4300)) (refuse! :repository-protocol))
          (when (>= (count result) limit) (budget! :git-records (inc (count result)) limit))
          (recur (inc end) (conj result (subs text start end)))))))
(m/=> records [:=> [:cat Text CountLimit] [:vector {:max 1000000} [:string {:min 1 :max 4300}]]])

(defn- valid-path!
  "Require a portable relative path and exclude Git administrative components at every depth."
  [path]
  (when (or (not (m/validate r/Path path)) (some #{".git"} (str/split path #"/")))
    (refuse! :repository-protocol))
  path)
(m/=> valid-path! [:=> [:cat [:string {:max 4300}]] r/Path])

(defn- unique!
  "Reject duplicate membership rather than silently collapsing index or untracked records."
  [paths]
  (when-not (= (count paths) (count (set paths))) (refuse! :repository-protocol))
  nil)
(m/=> unique! [:=> [:cat rc/Paths] :nil])

(defn index
  "Parse ls-files --stage -z; reject conflicts, unsupported modes, duplicate paths and unsafe names."
  [text limit]
  (let [result (mapv (fn [record]
                       (let [tab (.indexOf ^String record (int 9))
                             header (when (pos? tab) (subs record 0 tab))
                             match (when header (re-matches #"(100644|100755|120000|160000) ([0-9a-f]{40}|[0-9a-f]{64}) 0" header))]
                         (when-not match (refuse! :repository-protocol))
                         {:mode (nth match 1) :object (nth match 2) :path (valid-path! (subs record (inc tab)))}))
                     (records text limit))]
    (unique! (mapv :path result))
    (vec (sort-by :path result))))
(m/=> index [:=> [:cat Text CountLimit] rc/Index])

(defn untracked
  "Parse ls-files --others --exclude-standard -z with exact membership and finite record counts."
  [text limit]
  (let [paths (mapv valid-path! (records text limit))]
    (unique! paths)
    (vec (sort paths))))
(m/=> untracked [:=> [:cat Text CountLimit] rc/Paths])

(defn line
  "Remove exactly Git's terminal newline; reject extra lines and empty or NUL-containing values."
  [text]
  (when-not (and (str/ends-with? text "\n") (<= 2 (count text) 4097)) (refuse! :repository-protocol))
  (let [value (subs text 0 (dec (count text)))]
    (when (re-find #"[\r\n\x00]" value) (refuse! :repository-protocol))
    value))
(m/=> line [:=> [:cat Text] [:string {:min 1 :max 4096}]])
