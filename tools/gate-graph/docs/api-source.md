# Passive API source observations

`gate.api-source/scan` is a trusted authoring operation over one source string.
It reads Clojure syntax without loading the namespace or executing reader tags.
Pass `:platform :clj` or `:cljs` explicitly. Its closed request has source text
and finite UTF-8 byte, top-level form and parser-node budgets. Invalid Unicode,
reader failures and excessive nesting return closed refusals with coordinates.
Input acquisition and the lifetime of already allocated source strings belong
to the caller; this function is not a filesystem scanner or an agent endpoint.

```clojure
(require '[gate.api-source :as source])
(source/scan {:source "(ns example.api) (def answer 42)"
              :platform :clj
              :limits source/default-limits})
```

The result uses `:profile :passive-declarations-v1`. It contains the namespace,
platform, whole-source SHA-256, parsed form/node counts, sorted declaration
observations and sorted Malli registration observations. Every declaration
retains its qualified name, source position, exact parsed declaration text
digest and declared privacy. Names preserve punctuation, including apostrophes.
Source text and runtime objects are never returned. Results and failures can
use the canonical EDN encoder and bounded `:value` admission; the large source
request deliberately stays outside the persisted canonical-value union.

Supported top-level forms are `ns`, `def`, `defn`, `defn-`, `defmacro`, `declare`,
resolved `malli.core/=>`, and literal reflection-warning settings. Require
aliases/refers and core exclusions are resolved from source, independently of
the caller's loaded namespaces. A different macro named `=>` is not a Malli
registration. Namespace configuration outside the supported explicit profile
refuses. Declarations must eventually be bound; duplicates and registrations
without a matching local declaration refuse.

Function aliases and callable maps remain `:binding` observations. Deciding
that a binding is an operation, schema or constant requires independent
runtime/analyzer evidence and an explicit role policy. Argument/result schemas
are not printed runtime objects: each registration currently retains the whole
ordered source declaration digest, preserving multi-arity pairing and guards.
Whole-file identity binds referenced aliases and all source changes; complete
module/dependency closure fingerprints remain a separate requirement.

This profile does not certify a complete exported API. Function bodies and
initializer expressions may invoke declaration macros or create exports;
passive top-level observations cannot prove those runtime effects absent.
Unsupported top-level generators such as `defrecord`, `defprotocol`, `intern`
and arbitrary calls refuse instead of disappearing. A complete inventory must
reconcile these observations with fresh JVM intern/public observations and
CLJS analyzer observations, preserving macro and runtime phases separately.
It must also bind discovered file membership, dependency/toolchain/config
identity and reviewed macro adapters before constructing the API manifest.

The implementation uses [Edamame's non-evaluating parser and explicit platform
features](https://github.com/borkdude/edamame/tree/v1.6.44). The core Clojure
reader's implicit JVM feature must not select a CLJ branch for a CLJS inventory.
Independent tests cover platform variants, aliases/privacy, real versus
unrelated schema macros, missing/duplicate declarations, UTF-8 budgets, reader
effects, malformed Unicode, deep inputs and generated declaration rosters.
