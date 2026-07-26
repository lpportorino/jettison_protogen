(ns protodoc.placeholder
  "The rendered ABSENCE MARKERS, and the one home for them.

   A template renders a marker where a value is absent; the extractor re-reads
   rendered markdown on the next `docs-generate`. If the extractor does not
   recognise a marker as synthetic, it stores it as genuine content and the
   absence is gone.

   That is not a cosmetic round-trip loss here. These markers are what
   `docs-coverage` and `docs-lint` key on to find UNDOCUMENTED items, so a
   marker read back as content makes an undescribed item read as DOCUMENTED —
   coverage improves at exactly the moment nothing was written, and the gate
   measuring documentation is satisfied by its own placeholder.

   The literal therefore lives HERE, once, and both sides reference it. Spelling
   it in the template and again in the extractor is two copies of one fact
   (cohesion.md rule 3): they agree today and drift the first time someone
   rewords the prose — at which point the extractor silently stops recognising
   the marker and the corruption resumes with no test failing."
  (:require [clojure.string :as str]))

(def absent-description
  "Rendered where a message or enum has no `:description`.

   Emitted by BOTH `message.md.selmer` and `enum.md.selmer` — the templates
   reference this via the `absent-description` key their render context
   carries, so this def is the only place the wording exists."
  "*No description yet.*")

(defn synthetic?
  "True when `s` is a rendered absence marker rather than authored prose.

   Trims first: the extractor collects a description as a multi-line blob up to
   the next heading, so the marker arrives with surrounding whitespace."
  [s]
  (and (string? s) (= absent-description (str/trim s))))

(defn content-or-nil
  "`s` unless it is an absence marker, in which case nil.

   The extractor funnels every description through this, so recognising a marker
   is not a judgement call at each call site."
  [s]
  (when-not (synthetic? s) s))
