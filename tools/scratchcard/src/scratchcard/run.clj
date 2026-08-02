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
  and copying that lock would serialise a matrix that currently renders
  concurrently, for no correctness gain. Do not 'restore' it for symmetry.

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
   (java.time Instant)
   (org.graalvm.polyglot Context)))

(def default-cell-timeout-ms
  "Per-cell deadline.

  Generous: a cold first render pays GraalWasm compilation, measured well over
  a second, while steady-state cells run in single-digit milliseconds. This
  bound exists to stop a WEDGED cell hanging the matrix, not to police speed."
  60000)

(defn- ms-since [t0] (/ (- (System/nanoTime) t0) 1e6))

(defn- render-one!
  "Render, dump, encode and judge ONE cell using an ALREADY-STARTED host.

  DOES NOT own the host's lifecycle. The caller starts and closes it, because
  the caller is the only party that can still act when this function is wedged
  inside a guest call — see `render-cell-guarded!`."
  [{:keys [run-dir card-id ^bytes pb]} h* {:keys [family dark w h] :as cell} bp]
  (let [label (matrix/cell-label cell)]
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
                ;; Facts only a LIVE host can answer, carried out of the cell
                ;; because provenance is collected after every context is
                ;; closed. `:fb-format` and `:engine-impl` are NOT here:
                ;; `devcards.host/start!` exposes neither, and claiming them
                ;; would be a manifest field that is structurally always nil.
                ;; What IS derivable is that the render happened, and
                ;; `assert-optimizing-runtime!` hard-fails on a non-optimizing
                ;; runtime — so reaching this line proves it.
              :runtime {:abi (:abi h*) :optimizing-runtime? true}
              :fb-sha256 (get-in st [:framebuffer :sha256])
              :png (str "renders/" (.getName ^File png-file))
              :dump (str "renders/" (.getName dump-file))
              :stats st
              :live (:live judged)
              :stale-exemptions (:stale-exemptions judged)}))))

(defn- cancel-context!
  "Force a polyglot context closed, CANCELLING any guest code still running.

  `devcards.host/close!` is the graceful path: it calls `controls_destroy` and
  then a plain `.close`, both of which BLOCK while the guest is executing. On
  the timeout path the guest is precisely what is not returning, so the
  graceful close would hang the thread that is trying to clean up.

  `Context.close(true)` is the only thing that cancels executing guest code.
  `future-cancel` cannot: it is `Thread.interrupt()`, and a Truffle guest call
  ignores interrupts. Without this the wedged thread AND its context — 8 MiB of
  linear memory minimum — leak permanently in a process designed never to
  restart, while the run reports the cell as merely timed out."
  [{:keys [^Context ctx]}]
  (try (.close ctx true) (catch Exception _ nil)))

(defn- render-cell-guarded!
  "Render ONE cell with a deadline and failure isolation.

  OWNS THE CONTEXT so that a wedged guest can actually be cancelled. A failure
  becomes a cell whose `:status` is `:error` — never an exception that takes
  the other cells with it."
  [{:keys [wasm assets] :as ctx} {:keys [w h bp] :as cell} timeout-ms]
  (let [label (matrix/cell-label cell)
        base (merge (select-keys cell [:family :dark :w :h :bp :disp-tier])
                    {:cell label})
        fail (fn [code msg] (assoc base :status :error :live [] :stale-exemptions []
                                   :error {:code code :message msg}))]
    (try
      (let [h* (host/start! {:wasm wasm :assets assets :w w :h h})
            f (future (render-one! ctx h* cell bp))
            r (deref f timeout-ms ::timeout)]
        (if (= ::timeout r)
          (do (future-cancel f)
              ;; MUST cancel the context, not merely the future.
              (cancel-context! h*)
              (fail "RENDER_TIMEOUT" (str "no render within " timeout-ms "ms")))
          (do (host/close! h*) r)))
      (catch Exception e
        (fail (or (:error (ex-data e)) "RENDER_FAILED") (str (ex-message e)))))))

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
      (let [built (input/build! {:repo-root repo-root :screen-path screen-path
                                 :out-pb (str (io/file run-dir "input" "screen.pb"))})
            ;; The verbatim input copy is what makes a run self-contained: the
            ;; archive must answer "what exactly was rendered" without
            ;; depending on the working tree, which will have moved on. Taken
            ;; AFTER the build accepted it, so an arbitrary unreadable-as-EDN
            ;; file cannot be copied into the archive merely by naming it.
            ;; io/copy does not create parents.
            _ (let [copy (io/file run-dir "input" "screen.edn")]
                (io/make-parents copy)
                (io/copy (io/file screen-path) copy))
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
        ;; BOOKKEEPING IS ISOLATED FROM THE RUN'S VERDICT. `write-run!` has
        ;; already written findings.edn, manifest.edn and report.md. If the
        ;; roster append or the symlink then threw, the outer catch would call
        ;; write-run! AGAIN with `:cells []` and `:status :error` — overwriting
        ;; a correct manifest with one claiming zero renders, while 42 PNGs sat
        ;; on disk beside it. The archive would permanently misdescribe a run
        ;; that succeeded.
        (try
          (runs/latest-link! scratch-root card-slug run-dir)
          (runs/append-roster! scratch-root card-slug
                               {:run run-id :utc (:utc base) :status (get-in mf [:run :status])
                                :cells (count results) :failed failed
                                :findings (get-in mf [:findings :count])})
          (catch Exception e
            (binding [*out* *err*]
              (println "scratchcard: roster bookkeeping failed (the run itself stands):"
                       (ex-message e)))))
        {:ok (zero? failed)
         :dir (str run-dir)
         :run run-id
         :cells (count results)
         :failed failed
         :findings (select-keys (:findings mf) [:count :unjudged :by-invariant :clean?])
         :elapsed-ms (ms-since t0)
         ;; Same isolation: a prune failure must not turn a good run into a
         ;; failed one, and must not reach the catch below.
         :retention (try (retention/prune! scratch-root card-slug
                                           {:keep (or keep-runs retention/default-keep)
                                            :protect [(.getName ^File run-dir)]})
                         (catch Exception e
                           {:error "PRUNE_FAILED" :message (str (ex-message e))}))})
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
