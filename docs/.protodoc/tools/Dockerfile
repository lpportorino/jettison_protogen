FROM clojure:temurin-25-tools-deps-bookworm

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
