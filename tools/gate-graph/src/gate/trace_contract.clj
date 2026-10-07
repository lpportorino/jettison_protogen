(ns gate.trace-contract
  "Closed JVM import contracts for the published gate-trace v1 JSON journal."
  (:require [gate.contract :as c]))

(def Counter [:int {:min 0 :max Long/MAX_VALUE}])
(def TraceId [:re #"^(?!0{32}\z)[0-9a-f]{32}\z"])
(def SpanId [:re #"^(?!0{16}\z)[0-9a-f]{16}\z"])
(def Revision [:re #"^(?:[0-9a-f]{40}|[0-9a-f]{64})\z"])
(def Name [:re #"^[A-Za-z0-9][A-Za-z0-9._:/+=@-]{0,127}\z"])
(def Token [:re #"^[a-z0-9][a-z0-9._-]{0,63}\z"])
(def Text [:string {:max 4096}])
(def ShortText [:string {:min 1 :max 256}])
(def Vcs
  [:map {:closed true}
   ["revision" Revision] ["dirty" :boolean] ["dirty_digest" [:maybe c/Digest]]
   ["submodules" [:map-of {:max 256} [:string {:min 1 :max 4096}]
                  [:map {:closed true} ["revision" [:maybe Revision]]
                   ["state" [:enum "match" "moved" "uninitialized" "conflict"]]]]]
   ["changeset" [:map {:closed true} ["kind" [:enum "worktree" "range"]]
                 ["base" Revision] ["tip" [:maybe Revision]]
                 ["paths" [:vector {:max 262144} Text]] ["path_count" Counter]]]])
(def Header
  [:map {:closed true} ["schema" [:= 1]] ["record" [:= "run"]]
   ["run_id" TraceId] ["root_span_id" SpanId] ["entrypoint" Token]
   ["started_unix_ns" Counter] ["started_mono_ns" Counter] ["vcs" Vcs]
   ["toolchain" [:map-of {:min 1 :max 32} Token ShortText]]
   ["host" [:map {:closed true} ["os" ShortText] ["kernel" ShortText]
            ["machine" ShortText] ["cpus" [:int {:min 1 :max 1048576}]]
            ["cpu_model" [:maybe ShortText]] ["name" [:maybe ShortText]]]]])
(def End
  [:map {:closed true} ["schema" [:= 1]] ["record" [:= "run-end"]]
   ["run_id" TraceId] ["ended_unix_ns" Counter] ["ended_mono_ns" Counter]])
(def SpanFields
  [:map {:closed true} ["schema" [:= 1]] ["trace_id" TraceId] ["span_id" SpanId]
   ["parent_span_id" SpanId] ["name" Name]
   ["kind" [:enum "chain" "gate" "test" "step" "boundary"]] ["start_mono_ns" Counter]])
(def Start (conj SpanFields ["record" [:= "span-start"]]))
(def Exit
  [:or
   [:map {:closed true} ["status" [:= "exited"]] ["code" [:int {:min 0 :max 255}]]]
   [:map {:closed true} ["status" [:= "signaled"]] ["signal" [:int {:min 1 :max 64}]]
    ["core_dumped" :boolean]]
   [:map {:closed true} ["status" [:= "spawn-failed"]] ["code" [:enum 126 127]]
    ["errno" [:string {:min 1 :max 32}]]]])
(def Usage
  (into [:map {:closed true}]
        (map (fn [field] [field Counter])
             ["user_us" "sys_us" "maxrss_kib" "minflt" "majflt" "inblock" "oublock" "nvcsw" "nivcsw"])))
(def Span
  (into SpanFields
        [["record" [:= "span"]] ["end_mono_ns" Counter]
         ["verdict" [:enum "success" "failure" "error"]] ["exit" Exit] ["rusage" [:maybe Usage]]
         ["signals_forwarded" [:map {:closed true} ["SIGHUP" Counter] ["SIGTERM" Counter]]]
         ["attributes" [:map {:closed true} ["process.pid" [:maybe [:int {:min 1 :max Long/MAX_VALUE}]]]
                        ["process.executable.name" [:string {:max 255}]]]]]))
(def Record [:or Header End Start Span])
(def Entry
  [:map {:closed true} [:path [:string {:min 1 :max 256}]] [:digest c/Digest]
   [:record Record]])
(def Journal [:vector {:min 1 :max 256002} Entry])
(def Inventory [:vector {:min 1 :max 128000}
                [:map {:closed true} [:name Name] [:key c/Id]]])
(def InventoryIndex [:map-of {:min 1 :max 128000} Name c/Id])
(def Path [:string {:min 1 :max 4096}])
(def Failure
  [:map {:closed true}
   [:code [:enum :trace-io :trace-file-type :trace-byte-limit :trace-file-limit
           :trace-json :trace-shape :trace-layout :trace-identity :trace-start-mismatch
           :trace-exit :trace-inventory :trace-empty :trace-clock :trace-unsupported-annotation]]
   [:path Path]
   [:issues [:vector {:max 32}
             [:map {:closed true} [:in [:vector {:max 64} [:or Text :keyword :int]]]
              [:type [:enum :missing-key :extra-key :invalid-type :invalid-value]]]]]])
