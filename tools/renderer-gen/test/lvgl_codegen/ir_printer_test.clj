(ns lvgl-codegen.ir-printer-test
  "The IR baseline printer must not depend on WHICH JVM printed it.

   `lvgl-codegen.core/ir->edn-string` renders the `<screen>.ir.edn` tree-patch
   baseline that `emit-patch-artifacts!` re-spits on every screen compile. Two
   ambient printer bindings change its BYTES without changing its VALUE:

     `*print-namespace-maps*` rewrites the single-key ByteString marker map
     `{:lvgl-codegen.core/bytes-b64 \"…\"}` into the namespace-map form
     `#:lvgl-codegen.core{:bytes-b64 \"…\"}`. A REPL binds it true; a plain JVM
     leaves it false. So the same IR prints two ways depending on which process
     wrote it, and a baseline written by one and refreshed by the other reports
     a screen change that never happened — and, wherever that baseline is a
     tracked file, is a file a compile MUTATES while judging it.

     `*print-length*` / `*print-level*` are worse than cosmetic: an ambient
     limit ELIDES part of the tree. A truncated baseline is not a baseline —
     it reads back as a different IR, so the next diff is against a fiction.

   WHY THE ASSERTIONS ARE SHAPED THIS WAY. Equality between the two renderings
   is the contract, but equality alone cannot say WHICH form won: both sides
   agreeing on the namespace-map spelling would satisfy it. So each test also
   pins the surviving spelling, and each `is` carries its own message, so a red
   names the clause that failed rather than the test that contained it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.core :as core])
  (:import [com.google.protobuf ByteString]))

(set! *warn-on-reflection* true)

(def ^:private an-ir
  "An IR map carrying a ByteString the way an emitted CmdSpec does — the one
   value in the IR with no EDN literal, hence the marker map the printer
   bindings act on. The child vector is longer than the `*print-length*` bound
   the truncation test sets, so an inherited limit is visible in the output
   rather than merely possible."
  {:uid 1
   :kind :WIDGET_KIND_BUTTON
   :cmd_spec {:template (ByteString/copyFromUtf8 "a cmd.Root template")}
   :children (mapv (fn [i] {:uid (+ 2 i) :kind :WIDGET_KIND_LABEL :text (str "row " i)})
                   (range 8))})

(deftest baseline-bytes-do-not-depend-on-print-namespace-maps
  (testing "`ir->edn-string` pins `*print-namespace-maps*` rather than
            inheriting it, so a REPL and a plain JVM print the ByteString
            marker map identically"
    (let [as-repl (binding [*print-namespace-maps* true] (#'core/ir->edn-string an-ir))
          as-plain (binding [*print-namespace-maps* false] (#'core/ir->edn-string an-ir))]
      (is (= as-plain as-repl)
          "the two renderings of one IR are the same string")
      (is (not (str/includes? as-repl "#:lvgl-codegen.core{"))
          "the qualified-key form is what survives, not the namespace-map form")
      (is (str/includes? as-plain ":lvgl-codegen.core/bytes-b64")
          "the marker key is still present — equality must not be bought by
           dropping it"))))

(deftest baseline-is-not-truncated-by-an-ambient-print-limit
  (testing "`ir->edn-string` pins `*print-length*` and `*print-level*` to nil:
            a baseline that elides part of the tree reads back as a different
            IR, so the next diff is against a fiction"
    (let [unlimited (#'core/ir->edn-string an-ir)
          limited (binding [*print-length* 3 *print-level* 2]
                    (#'core/ir->edn-string an-ir))]
      (is (= unlimited limited)
          "an ambient print limit does not reach the baseline")
      (is (not (str/includes? limited "..."))
          "nothing in the baseline is elided")
      (is (= an-ir (#'core/edn-string->ir limited))
          "the limited rendering still reads back to the whole IR"))))

(deftest baseline-round-trips-the-byte-string-under-either-binding
  (testing "whichever way the ambient bindings are set, the baseline reads back
            value-for-value — the property `emit-patch-artifacts!` depends on
            when it diffs the persisted baseline against a fresh IR"
    (doseq [nsmaps [true false]]
      (let [rendered (binding [*print-namespace-maps* nsmaps]
                       (#'core/ir->edn-string an-ir))
            read-back (#'core/edn-string->ir rendered)]
        (is (= an-ir read-back)
            (str "round-trip is value-preserving with *print-namespace-maps* "
                 nsmaps))
        (is (instance? ByteString (get-in read-back [:cmd_spec :template]))
            (str "the marker map decodes back to a ByteString with"
                 " *print-namespace-maps* " nsmaps))))))
