(ns scratchcard.protocol
  "The wire between the client and the warm daemon: NDJSON over AF_UNIX.

      Request:  {\"id\": <n>, \"op\": \"<name>\", \"args\": {...}}
      Response: {\"id\": <same>, \"ok\": true,  \"value\": ...}
              | {\"id\": <same>, \"ok\": false, \"error\": \"<CODE>\", \"message\": \"...\"}

  One JSON object per line. DELIBERATELY SIMPLE — no JSON-RPC envelope, no
  session multiplexing, no handshake — so the whole protocol stays debuggable
  with `nc -U` and a text editor when the client itself is the suspect.

  NO IMAGE BYTES CROSS THIS CHANNEL. Renders are written to the run directory
  and the response carries the PATH plus a compact summary. The sibling
  fleet's GPU worker states the same rule for the same reason: a transport
  that can carry a megabyte will eventually be asked to, and then a wedged
  read looks exactly like a slow render.

  REJECTING BEATS IGNORING. An unknown argument is refused with both the
  offending key and the accepted set, never dropped. The failure this prevents
  is specific and was paid for elsewhere in this fleet: a handler destructured
  what it knew, the rest evaporated, and the call returned a normal success
  envelope for work it had not done. A silent drop turns a typo into a lie.

  A CLOSED STREAM IS AN ERROR, NEVER A SUCCESS-SHAPED NIL. `decode-response`
  maps a nil line to NO_RESPONSE so a crashed daemon can never be read as a
  quiet success by a client that forgot to check."
  (:require
   [clojure.data.json :as json]
   [clojure.string :as str]))

;; ── error vocabulary ───────────────────────────────────────────────────────
;;
;; Codes are STRINGS on the wire so a non-Clojure client reads them without a
;; keyword convention, and so an unknown code from a newer daemon degrades to
;; "some error" rather than an exception in the reader.

(def input-errors
  "Refusals attributable to the caller's screen file.

  One code PER VALIDATION STAGE rather than a single BUILD_FAILED: the
  authoring pipeline runs seven distinct checks, and collapsing them would
  throw away the tool's most useful property — telling the author WHICH stage
  rejected the screen."
  #{"INPUT_MISSING"
    "INPUT_READ_FAILED"
    "INPUT_SCHEMA_INVALID"
    "INPUT_COMPONENT_UNRESOLVED"
    "INPUT_SEMANTICS_INVALID"
    "INPUT_ASSET_MISSING"
    "INPUT_EXPAND_FAILED"
    "INPUT_FONT_UNRESOLVED"
    "INPUT_IR_INVALID"})

(def render-errors
  #{"RENDER_FAILED" "RENDER_TIMEOUT" "LANE_FAILED" "WRITE_FAILED"})

(def protocol-errors
  "Refusals attributable to the request itself, not to its payload."
  #{"MALFORMED_REQUEST" "MISSING_OP" "UNKNOWN_OP" "UNKNOWN_ARGUMENT"})

(def client-errors
  "Raised by the CLIENT, never sent by the daemon.

  Named here so both halves share one vocabulary — a client-side code that
  collided with a server-side one would make a transport failure
  indistinguishable from a render failure."
  #{"DAEMON_DOWN" "DAEMON_TIMEOUT" "NO_RESPONSE" "SPAWN_FAILED"})

(def error-codes
  (into #{} cat [input-errors render-errors protocol-errors client-errors]))

;; ── ops ────────────────────────────────────────────────────────────────────

(def ops
  "Every op the daemon answers, with the args each accepts.

  The arg sets are CLOSED and are what `validate-request` checks against, so
  adding an arg to a handler without adding it here fails loudly at the seam
  rather than being silently dropped on the way in."
  {"ping" #{}
   "status" #{}
   "stop" #{}
   "regenerate" #{:file :card :resolutions :families :modes :bp-from-canvas?
                  :keep :timeout-ms}})

;; ── envelopes ──────────────────────────────────────────────────────────────

(defn request
  "A request envelope."
  ([id op] (request id op {}))
  ([id op args] {:id id :op op :args (or args {})}))

(defn ok
  "A success envelope."
  [id value]
  {:id id :ok true :value value})

(defn err
  "A failure envelope. `extra` is merged for diagnostic context."
  ([id code message] (err id code message nil))
  ([id code message extra]
   (merge {:id id :ok false :error code :message message} extra)))

(defn error?
  [resp]
  (boolean (and (map? resp) (not (:ok resp)))))

;; ── codec ──────────────────────────────────────────────────────────────────

(defn encode-line
  "`m` as one JSON line, newline included.

  Asserts the encoded form contains no bare newline, because the framing IS
  the line: an embedded newline would split one message into two and desync
  the stream for every message after it."
  ^String [m]
  (let [s (json/write-str m :escape-slash false)]
    (when (str/includes? s "\n")
      (throw (ex-info "encoded message contains a newline — would desync the stream"
                      {:error "MALFORMED_REQUEST"})))
    (str s "\n")))

(defn decode-line
  "Parse one NDJSON line into a keywordized map, or nil when unparseable.

  nil rather than a throw: the caller has an `id` in hand and can build a
  proper MALFORMED_REQUEST envelope, which a throw here would deny it."
  [^String line]
  (when-not (str/blank? line)
    (try
      (let [m (json/read-str line :key-fn keyword)]
        (when (map? m) m))
      (catch Exception _ nil))))

(defn decode-response
  "Parse a response line. A nil line becomes NO_RESPONSE.

  This is the one function standing between a crashed daemon and a client that
  reports success."
  [line]
  (cond
    (nil? line)
    (err nil "NO_RESPONSE"
         "the daemon closed the connection without a response — check its logs")

    :else
    (or (decode-line line)
        (err nil "MALFORMED_REQUEST"
             (str "unparseable response line: " (pr-str (subs line 0 (min 200 (count line)))))))))

;; ── request validation ─────────────────────────────────────────────────────

(defn validate-request
  "Return `[:ok request]`, or `[:error envelope]`.

  A tuple rather than a throw so the server's accept loop stays a
  straight-line function: every outcome is a value it writes back."
  [m]
  (let [id (:id m)
        op (:op m)
        args (:args m)]
    (cond
      (not (map? m))
      [:error (err id "MALFORMED_REQUEST" "request must be a JSON object")]

      (str/blank? (str op))
      [:error (err id "MISSING_OP" (str "request must name an op; known ops: "
                                        (str/join ", " (sort (keys ops)))))]

      (not (contains? ops op))
      [:error (err id "UNKNOWN_OP" (str "unknown op " (pr-str op) "; known ops: "
                                        (str/join ", " (sort (keys ops))))
                   {:accepted (vec (sort (keys ops)))})]

      (and (some? args) (not (map? args)))
      [:error (err id "MALFORMED_REQUEST" "args must be a JSON object")]

      :else
      (let [accepted (get ops op)
            unknown (remove accepted (keys (or args {})))]
        (if (seq unknown)
          [:error (err id "UNKNOWN_ARGUMENT"
                       (str "unknown argument(s) for " op ": "
                            (str/join ", " (map name (sort unknown)))
                            "; accepted: "
                            (if (seq accepted)
                              (str/join ", " (map name (sort accepted)))
                              "none"))
                       {:unknown (vec (sort unknown))
                        :accepted (vec (sort accepted))})]
          [:ok (assoc m :args (or args {}))])))))
