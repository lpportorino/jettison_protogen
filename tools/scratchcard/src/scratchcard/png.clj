(ns scratchcard.png
  "Raw RGBA framebuffers to PNG.

  PNG, NOT JPEG, AND THE CHOICE IS LOAD-BEARING. This repo already has a JPEG
  encoder (`devcards.jpeg`, quality 0.85) used for the committed gallery, and
  reusing it here would be the obvious move. It is the wrong one: JPEG's
  chroma subsampling and block ringing blur exactly the one-pixel geometry
  differences this tool exists to surface, and a reader diffing two runs would
  be comparing compression artefacts. PNG is lossless, so a byte difference
  between two scratch renders is a REAL difference.

  COMPOSITING IS BORROWED, NOT REIMPLEMENTED. `devcards.jpeg/flatten->rgb` is
  the one home for turning a straight-alpha RGBA framebuffer into visible
  pixels — it SrcOver-flattens onto opaque black, which is what the device
  shows and what every committed gallery image was produced with. A second
  compositing path here would mean a scratch render and a gallery render of
  the same screen could differ for a reason that has nothing to do with the
  screen. The framebuffer carries GARBAGE colour bytes under A=0 pixels, so
  the flatten is also what makes the output deterministic at all.

  THE HOST/CONTAINER ENCODER DIVERGENCE DOES NOT REACH HERE, but know why it
  does not: the host JDK's `javax.imageio` writes JPEG bytes differently from
  the pinned container, which is why gallery images must be regenerated in the
  container. Scratch PNGs are gitignored and are never compared against a
  committed artifact, so the divergence cannot fail a gate. It is still a
  reason the daemon belongs in the container — every OTHER artifact it writes
  does get compared."
  (:require
   [clojure.java.io :as io]
   [devcards.jpeg :as jpeg])
  (:import
   (java.awt.image BufferedImage)
   (java.io ByteArrayOutputStream File)
   (javax.imageio ImageIO)))

(def format-name "png")

(defn encode
  "PNG bytes for a `w` x `h` straight-alpha RGBA framebuffer.

  Throws when no PNG writer is registered rather than returning an empty
  array — an encoder that silently produced zero bytes would write a file
  that every downstream reader treats as a corrupt render."
  ^bytes [^bytes rgba w h]
  (let [^BufferedImage img (jpeg/flatten->rgb rgba w h)
        out (ByteArrayOutputStream.)]
    (when-not (ImageIO/write img format-name out)
      (throw (ex-info "no PNG writer available in this JVM"
                      {:error "WRITE_FAILED" :format format-name})))
    (.toByteArray out)))

(defn write!
  "Encode and write a framebuffer to `file`. Returns the file.

  Writes via a `.part` sibling and an atomic rename, so a reader — or a
  retention sweep — never observes a half-written PNG and mistakes a
  truncated file for a render defect."
  ^File [file ^bytes rgba w h]
  (let [f (io/file file)
        part (io/file (str (.getPath f) ".part"))]
    (io/make-parents f)
    (with-open [os (io/output-stream part)]
      (.write os (encode rgba w h)))
    (when-not (.renameTo part f)
      (throw (ex-info (str "could not rename " part " to " f)
                      {:error "WRITE_FAILED" :path (str f)})))
    f))
