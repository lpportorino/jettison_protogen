(ns lvgl-codegen.registry
  "Shared Malli named primitives + the composite default-registry install.

   Mirrors sych's `recipes.common.lib` registry pattern: a small set of named,
   reusable schemas (each with a human `:error/message`) installed into Malli's
   default registry so they resolve as keyword refs (`::ne-string`, `::hex-color`)
   anywhere. Require this ns (transitively) wherever those keywords are used; it
   installs at load. The composite includes `(m/default-schemas)`, so all stock
   schemas (`:string`, `:int`, `:map`, `:enum`, …) keep working unchanged."
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(set! *warn-on-reflection* true)

(def primitives
  "Named primitives reused across schemas. Each carries a human `:error/message`
   — the no-bare-`:string` policy (CLAUDE.md Malli Spec Policy) made reusable."
  {::ne-string [:string {:min 1 :error/message "should be a non-empty string"}]
   ::hex-color [:re {:error/message "should be a \"#RRGGBB\" hex colour"}
                #"^#[0-9A-Fa-f]{6}$"]})

(defn install!
  "Install the composite default registry: Malli defaults + our named primitives.
   Idempotent (a plain re-set); runs once at ns load."
  []
  (mr/set-default-registry! (mr/composite-registry (m/default-schemas) primitives)))
(m/=> install! [:=> [:cat] :any])

(install!)