# The Clojure + Temurin 27 base (tools/clojure-base/Dockerfile), named by the
# content-derived tag `make docs-docker-build` passes. No default: a build that
# bypasses the Makefile fails on an empty base name instead of picking an
# unpinned image.
ARG CLOJURE_IMAGE
FROM ${CLOJURE_IMAGE}

WORKDIR /app

# Copy deps.edn first for layer caching
COPY deps.edn /app/
RUN clojure -P && clojure -P -M:test

# Copy source, resources, and tests
COPY src /app/src/
COPY resources /app/resources/
COPY test /app/test/
COPY build.clj /app/

ENTRYPOINT ["clojure"]
CMD ["-M:run"]
