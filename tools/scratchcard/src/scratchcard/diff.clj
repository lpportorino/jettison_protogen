(ns scratchcard.diff
  "Compares two runs and says WHY they differ.

  THE WHOLE POINT IS ATTRIBUTION, not a list of moved pixels. A changed
  framebuffer has exactly three possible causes, and a diff that reports
  \"3 cells moved\" without naming which one sends the reader hunting in the
  wrong place:

    1. THE INPUT changed      — the screen file is different
    2. THE RENDERER changed   — a different wasm, or a different protogen sha
    3. THE JUDGEMENT changed  — different producers, thresholds, or class table

  The third is the one a naive diff omits, and omitting it makes a threshold
  edit read as a pixel regression. It is why the manifest carries the lane
  descriptor at all.

  CAUSES ARE REPORTED AS A SET, NEVER AS A SINGLE VERDICT. Two runs can differ
  in the input AND the renderer at once, and picking one to report would be a
  guess presented as a finding. When nothing upstream differs but pixels did,
  that is `:unexplained` — which is a REAL answer and the most interesting one,
  because it means the renderer is not deterministic under inputs this archive
  can see.

  PIXEL COMPARISON IS EXACT. Framebuffer hashes are sha256 over raw bytes;
  there is no tolerance band, so a cell either matches or it does not. Do not
  add one: `geometry` is exact integer arithmetic for the same reason, and
  importing an \"uncertain\" verdict here would manufacture doubt the
  arithmetic does not have."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [scratchcard.manifest :as manifest]
   [scratchcard.runs :as runs]))

(defn- cells-by-label
  [mf]
  (into {} (map (juxt :cell identity)) (:renders mf)))

(defn- lane-fingerprint
  "The judgement's identity: what judged, with what knobs."
  [mf]
  (select-keys (:lanes mf) [:producers :thresholds :classes-sha :caps]))

(defn causes
  "Which of the three explanations are in play, as a set.

  `:unexplained` when framebuffers moved and nothing upstream did."
  [a b moved?]
  (let [cs (cond-> #{}
             (not= (get-in a [:input :sha256]) (get-in b [:input :sha256]))
             (conj :input)

             (or (not= (get-in a [:wasm :sha256]) (get-in b [:wasm :sha256]))
                 (not= (get-in a [:protogen :sha]) (get-in b [:protogen :sha])))
             (conj :renderer)

             (not= (lane-fingerprint a) (lane-fingerprint b))
             (conj :judgement))]
    (if (and moved? (empty? cs)) #{:unexplained} cs)))

(defn compare-runs
  "Structured difference between two run manifests."
  [a b]
  (let [ca (cells-by-label a)
        cb (cells-by-label b)
        shared (set/intersection (set (keys ca)) (set (keys cb)))
        moved (into (sorted-set)
                    (filter #(not= (:fb-sha256 (ca %)) (:fb-sha256 (cb %))))
                    shared)
        fa (frequencies (map :invariant (get-in a [:findings :live-sample])))
        fb (frequencies (map :invariant (get-in b [:findings :live-sample])))]
    {:runs {:from (get-in a [:run :id]) :to (get-in b [:run :id])}
     :causes (causes a b (seq moved))
     :cells {:moved (vec moved)
             :unchanged (- (count shared) (count moved))
             ;; A cell present in only one run is NOT a moved pixel — it is a
             ;; matrix change, and conflating the two would report a resolution
             ;; being added as a rendering regression.
             :only-in-from (vec (sort (set/difference (set (keys ca)) shared)))
             :only-in-to (vec (sort (set/difference (set (keys cb)) shared)))}
     :findings {:from (get-in a [:findings :count])
                :to (get-in b [:findings :count])
                :appeared (into (sorted-map)
                                (remove (fn [[k v]] (<= v (get fa k 0))))
                                fb)
                :disappeared (into (sorted-map)
                                   (remove (fn [[k v]] (<= v (get fb k 0))))
                                   fa)}
     :provenance {:input {:from (get-in a [:input :sha256])
                          :to (get-in b [:input :sha256])}
                  :wasm {:from (get-in a [:wasm :sha256])
                         :to (get-in b [:wasm :sha256])}
                  :protogen {:from (get-in a [:protogen :sha])
                             :to (get-in b [:protogen :sha])}
                  :classes-sha {:from (get-in a [:lanes :classes-sha])
                                :to (get-in b [:lanes :classes-sha])}}}))

(defn previous-id
  "The id one below the newest run of `card-slug`, or nil.

  The default `from` for a diff: the question an author actually asks is
  \"what did my last edit change\", which is latest-vs-previous."
  [^String scratch-root ^String card-slug]
  (let [ids (runs/existing-ids (runs/runs-dir scratch-root card-slug))]
    (last (butlast ids))))

(defn diff
  "Compare two runs of `card-slug`.

  Refs are `:latest`, `:previous`, an integer id, or a directory name."
  [^String scratch-root ^String card-slug from-ref to-ref]
  (let [deref-ref (fn [r] (if (= :previous r)
                            (or (previous-id scratch-root card-slug)
                                (throw (ex-info "no previous run to compare against"
                                                {:error "NO_SUCH_RUN" :card card-slug})))
                            r))
        from-ref (deref-ref from-ref)
        to-ref (deref-ref to-ref)
        resolve1 (fn [r]
                   (or (runs/resolve-run scratch-root card-slug r)
                       (throw (ex-info (str "no such run: " (pr-str r))
                                       {:error "NO_SUCH_RUN" :ref r :card card-slug}))))]
    (compare-runs (manifest/read-manifest (resolve1 from-ref))
                  (manifest/read-manifest (resolve1 to-ref)))))

(defn describe
  "A one-line human summary of a `compare-runs` result."
  ;; `why`, not `causes` — binding it would shadow this namespace's own
  ;; `causes` fn, which is the same rename hazard as shadowing a core var.
  ^String [{:keys [cells findings] why :causes}]
  (let [moved (count (:moved cells))]
    (str (if (zero? moved)
           "no pixel change"
           (str moved " cell(s) moved"))
         "; cause: " (if (seq why)
                       (str/join "+" (map name (sort why)))
                       "none")
         "; findings " (:from findings) " -> " (:to findings))))
