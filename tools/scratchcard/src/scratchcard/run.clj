(ns scratchcard.run
  "The orchestrator: screen file in, run directory out.

  IN-PROCESS AND DAEMON-FREE. Nothing here knows about sockets or containers,
  so the whole pipeline is testable and usable without either. The daemon is a
  transport wrapped around `regenerate!`, not a layer it depends on.

  RENDERS RUN IN PARALLEL, AND THAT IS SOUND RATHER THAN OPTIMISTIC. A
  GraalWasm `Context` is its own module instance with its own linear memory, so
  LVGL being single-threaded is a PER-INSTANCE property, not a global one.
  Separate contexts on separate threads over the shared engine is what
  `devcards.docs/generate!` already does for the committed gallery.

  SO THERE IS DELIBERATELY NO OPS LOCK. The sibling fleet's GPU worker
  serialises every op because a GPU is one resource; a renderer here is not,
  and copying that lock would triple the wall time of the matrix for no
  correctness gain. Do not 'restore' it for symmetry.

  ONE BAD CELL IS ONE BAD CELL. Each render gets a fresh context, so a trap, a
  timeout or a decode failure kills that context and nothing else; the other
  cells still land and the manifest names which are missing and why. A partial
  matrix is a legitimate, reportable result.

  EVERY INVOCATION WRITES A RUN DIRECTORY, INCLUDING ONE THAT FAILED BEFORE IT
  RENDERED ANYTHING. The return value distinguishes success from failure; the
  directory always exists, because the failed run is exactly the one a reader
  wants to diff against the last good one."
  (:require
   [clojure.data.json :as json]
   [clojure.java.io :as io]
   [devcards.host :as host]
   [scratchcard.input :as input]
   [scratchcard.lanes :as lanes]
   [scratchcard.manifest :as manifest]
   [scratchcard.matrix :as matrix]
   [scratchcard.png :as png]
   [scratchcard.provenance :as prov]
   [scratchcard.report :as report]
   [scratchcard.retention :as retention]
   [scratchcard.runs :as runs]
   [scratchcard.stats :as stats])
  (:import
   (java.io File)
   (java.time Instant)))

(def default-cell-timeout-ms
  "Per-cell deadline.

  Generous: a cold first render pays GraalWasm compilation, measured well over
  a second, while steady-state cells run in single-digit milliseconds. This
  bound exists to stop a WEDGED cell hanging the matrix, not to police speed."
  60000)

(defn- ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))

(defn- render-one!
  "Render, dump, encode and judge ONE cell. Returns a cell result.

  Opens and closes its own context — the hermeticity that makes a failure
  local."
  [{:keys [wasm assets run-dir card-id ^bytes pb]} {:keys [family dark w h bp] :as cell}]
  (let [label (matrix/cell-label cell)
        h* (host/start! {:wasm wasm :assets assets :w w :h h})]
    (try
      (when (pos? (long family)) (host/set-theme-family! h* family))
      (let [t0 (System/nanoTime)
            fb (host/render-card! h* {:pb pb :bp bp :dark dark})
            render-ms (ms-since t0)
            dump (host/dump-tree! h*)
            tree (json/read-str dump :key-fn keyword)
            t1 (System/nanoTime)
            png-file (png/write! (io/file run-dir "renders" (str label ".png")) fb w h)
            encode-ms (ms-since t1)
            dump-file (io/file run-dir "renders" (str label ".dump.json"))
            _ (spit dump-file dump)
            t2 (System/nanoTime)
            judged (lanes/judge {:card-id card-id :tree tree
                                 :emissions (some-> (:captured h*) deref)
                                 :family family :cell label})
            st (stats/collect {:host h* :framebuffer fb :pb-bytes (alength pb)
                               :dump-bytes (count dump) :w w :h h
                               :timings {:render-ms render-ms
                                         :encode-ms encode-ms
                                         :judge-ms (ms-since t2)}})]
        (merge (select-keys cell [:family :dark :w :h :bp :disp-tier])
               {:cell label
                :status :ok
                ;; Facts only a LIVE host can answer. Carried out of the cell
                ;; because provenance is collected after every context is
                ;; closed, and a manifest recording `abi: nil` cannot tell a
                ;; reader whether the module was old or merely unasked.
                :runtime {:abi (:abi h*)}
                :fb-sha256 (get-in st [:framebuffer :sha256])
                :png (str "renders/" (.getName ^File png-file))
                :dump (str "renders/" (.getName dump-file))
                :stats st
                :live (:live judged)
                :stale-exemptions (:stale-exemptions judged)}))
      (finally (host/close! h*)))))

(defn- render-cell-guarded!
  "`render-one!` with a deadline and failure isolation.

  A failure becomes a cell whose `:status` is `:error` — never an exception
  that takes the other cells with it."
  [ctx cell timeout-ms]
  (let [label (matrix/cell-label cell)
        base (merge (select-keys cell [:family :dark :w :h :bp :disp-tier])
                    {:cell label})]
    (try
      (let [f (future (render-one! ctx cell))
            r (deref f timeout-ms ::timeout)]
        (if (= ::timeout r)
          (do (future-cancel f)
              (assoc base :status :error :live [] :stale-exemptions []
                     :error {:code "RENDER_TIMEOUT"
                             :message (str "no render within " timeout-ms "ms")}))
          r))
      (catch Exception e
        (assoc base :status :error :live [] :stale-exemptions []
               :error {:code (or (:error (ex-data e)) "RENDER_FAILED")
                       :message (str (ex-message e))})))))

(defn- manifest-render
  "A cell result reduced to its manifest entry — no framebuffers, no findings."
  [r]
  (cond-> (select-keys r [:cell :family :dark :w :h :bp :disp-tier :status
                          :fb-sha256 :png :dump :stats])
    true (dissoc :runtime)
    (:error r) (assoc :error (:error r))))

(defn- write-run!
  "Persist every artefact for a run and return the manifest."
  [{:keys [run-dir card-slug run-id utc elapsed-ms repo-root image-tag
           matrix-opts input-info cells status error]}]
  (let [summary (lanes/summarise cells)
        ;; Any successful cell can answer these; they are properties of the
        ;; module, not of the render.
        runtime (or (some :runtime (filter #(= :ok (:status %)) cells)) {})
        prov (prov/collect {:repo-root repo-root :image-tag image-tag
                            :runtime runtime})
        mf (merge prov
                  {:run (cond-> {:id run-id :utc utc :card card-slug
                                 :status status :elapsed-ms elapsed-ms}
                          error (assoc :error error))
                   :matrix matrix-opts
                   :input (dissoc input-info :runtime)
                   :lanes (lanes/descriptor)
                   :renders (mapv manifest-render cells)
                   :findings summary})]
    (report/write-findings! run-dir cells)
    (manifest/write! run-dir mf)
    (report/write-report! run-dir mf)
    mf))

(defn regenerate!
  "Build `screen-path`, render the matrix, judge it, and write a run directory.

  Returns a COMPACT summary — paths and counts, never image bytes. That is the
  same rule the transport enforces, applied one layer earlier so the in-process
  caller and the socket caller see the same shape."
  ;; `keep-runs`, not `keep` — the option KEY stays `:keep` because it is data
  ;; the caller supplies, but binding `keep` would shadow `clojure.core/keep`.
  ;; Rename the local, never the key.
  [{:keys [repo-root screen-path card image-tag matrix-opts timeout-ms]
    keep-runs :keep
    :or {matrix-opts {} timeout-ms default-cell-timeout-ms}}]
  (let [t0 (System/nanoTime)
        instant (Instant/now)
        scratch-root (str repo-root "/.protogen/scratch")
        card-slug (or card (-> (io/file screen-path) .getName
                               (.replaceAll "\\.edn$" "")))
        run-dir (runs/allocate! scratch-root card-slug instant)
        run-id (subs (.getName ^File run-dir) 0 4)
        base {:run-dir run-dir :card-slug card-slug :run-id run-id
              :utc (runs/utc-stamp instant) :repo-root repo-root
              :image-tag image-tag :matrix-opts matrix-opts}]
    (try
      ;; The verbatim input copy is what makes a run self-contained: the
      ;; archive must answer "what exactly was rendered" without depending on
      ;; the working tree, which will have moved on. io/copy does not create
      ;; parents.
      (let [copy (io/file run-dir "input" "screen.edn")]
        (io/make-parents copy)
        (io/copy (io/file screen-path) copy))
      (let [built (input/build! {:repo-root repo-root :screen-path screen-path
                                 :out-pb (str (io/file run-dir "input" "screen.pb"))})
            cells (matrix/expand matrix-opts)
            _ (when (not= (count cells) (matrix/expected-count matrix-opts))
                (throw (ex-info "matrix expansion did not produce the expected cell count"
                                {:error "EMPTY_MATRIX"})))
            ctx {:wasm (str repo-root "/renderer/output/controls.wasm")
                 :assets (str repo-root "/renderer/assets")
                 :run-dir run-dir :card-id card-slug
                 ;; with-open, not a bare input-stream: this runs once per
                 ;; regenerate in a process that never restarts, so a leaked
                 ;; descriptor per call is a slow exhaustion rather than a
                 ;; visible bug.
                 :pb (with-open [in (io/input-stream (:pb-path built))]
                       (.readAllBytes in))}
            results (vec (pmap #(render-cell-guarded! ctx % timeout-ms) cells))
            failed (count (remove #(= :ok (:status %)) results))
            mf (write-run! (assoc base
                                  :elapsed-ms (ms-since t0)
                                  :input-info (assoc built :path (str screen-path))
                                  :cells results
                                  :status (if (pos? failed) :error :ok)))]
        (runs/latest-link! scratch-root card-slug run-dir)
        (runs/append-roster! scratch-root card-slug
                             {:run run-id :utc (:utc base) :status (get-in mf [:run :status])
                              :cells (count results) :failed failed
                              :findings (get-in mf [:findings :count])})
        {:ok (zero? failed)
         :dir (str run-dir)
         :run run-id
         :cells (count results)
         :failed failed
         :findings (select-keys (:findings mf) [:count :unjudged :by-invariant :clean?])
         :elapsed-ms (ms-since t0)
         :retention (retention/prune! scratch-root card-slug
                                      {:keep (or keep-runs retention/default-keep)
                                       :protect [(.getName ^File run-dir)]})})
      (catch Exception e
        ;; The run directory still gets a manifest — a failure that leaves no
        ;; trace cannot be compared against the last success.
        (let [err {:code (or (:error (ex-data e)) "RENDER_FAILED")
                   :message (str (ex-message e))}]
          (write-run! (assoc base :elapsed-ms (ms-since t0) :status :error
                             :error err :cells []
                             :input-info {:path (str screen-path)}))
          (runs/append-roster! scratch-root card-slug
                               {:run run-id :utc (:utc base) :status :error
                                :cells 0 :failed 0 :error (:code err)})
          {:ok false :dir (str run-dir) :run run-id
           :error (:code err) :message (:message err)
           :details (:errors (ex-data e))
           :elapsed-ms (ms-since t0)})))))
