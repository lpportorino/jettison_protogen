(ns protocol-gen.rust-lit
  "Quoting a value into emitted Rust source.

   ONE HOME, because three namespaces now emit Rust from this projection — the
   per-group ACCESS module, the nested PERMISSION TREE and the STATE SUBSYSTEM
   table — and each of them quotes a name into a string literal. A second copy
   of the assertion below is how one of them quietly stops making it."
  (:require [malli.core :as m]))

(set! *warn-on-reflection* true)

(defn string-literal
  "`s` as a Rust string literal.

   Every value this generator quotes into Rust is a proto identifier, a dotted
   proto path, or a policy id — none of which `protocol-gen.db`'s two name
   schemas admit a quote or a backslash into — so escaping would be dead code.
   The assertion is here instead, because a value that escaped those schemas
   must stop the run rather than be quoted into emitted source."
  [s]
  (when (re-find #"[\"\\]" s)
    (throw (ex-info "Name is not quotable into Rust source" {:name s})))
  (str \" s \"))

(m/=> string-literal [:=> [:cat [:string {:min 1}]] [:string {:min 1}]])
