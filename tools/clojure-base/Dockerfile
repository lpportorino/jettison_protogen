# syntax=docker/dockerfile:1.7
# The Jettison Clojure base: the pinned Ubuntu 26.04 image, the Eclipse
# Temurin 27 JDK and the Clojure CLI. It replaces the upstream
# `clojure:temurin-*-tools-deps-*` images, which stop at Temurin 26.
#
# ONE DEFINITION, TWO REPOSITORIES. This file is byte-identical in jettison
# (tools/clojure-base/Dockerfile) and protogen (tools/clojure-base/Dockerfile),
# and both build it under the tag `jettison-clojure:27-<first 12 hex of this
# file's sha256>`, so the two repos name and share ONE image. Change it in both
# places in the same change; jettison's `make clojure-base-image` refuses to
# build when its submodule copy differs.
#
# EVERY INPUT IS PINNED BY DIGEST OR BY VERSION + CHECKSUM:
#   UBUNTU_IMAGE       must equal docker_images.toml's `ubuntu` pin — the same
#                      Ubuntu layer every target image is appended onto
#                      (`make clojure-base-image` refuses on a mismatch).
#   TEMURIN_JDK_IMAGE  the upstream JDK, read by digest; only /opt/java/openjdk
#                      is taken from it. The Temurin 27 images are themselves
#                      built on this Ubuntu digest (their bottom two layers are
#                      byte-identical to it, per platform).
#   APT_SNAPSHOT       snapshot.ubuntu.com timestamp, so git and its
#                      dependencies resolve to the same versions on every build.
#   CLOJURE_CLI_*      the release tarball by version; ADD --checksum refuses
#                      any other bytes. The install mirrors that release's own
#                      posix-install.sh (the PREFIX/BINDIR substitutions).
#
# The bump rule is the uber image's (.devcontainer/Dockerfile carries the same
# JDK digest and CLI version; .claude/rules/devcontainer-tools.md is the matrix).
#
# What it carries beyond the JDK and the CLI: git + ca-certificates, because
# tools.deps resolves :git/url dependencies with the git CLI. LANG=C.UTF-8 is
# built into glibc, so no locales package is needed for a UTF-8 JVM.
ARG UBUNTU_IMAGE=ubuntu:26.04@sha256:f144425ff09be612d6d9ad965196e9cdc23dae1f42110a8a11a3e9a8198759f7
ARG TEMURIN_JDK_IMAGE=eclipse-temurin:27_35-jdk@sha256:2771efbbc159b89dc38b82ebe01312fd1b5f226071ff715ca9edd5411a389761

FROM ${TEMURIN_JDK_IMAGE} AS temurin-jdk

FROM ${UBUNTU_IMAGE}
ARG APT_SNAPSHOT=20261005T000000Z
ARG CLOJURE_CLI_VERSION=1.12.6.1673
ARG CLOJURE_CLI_SHA256=fe9194858e75d5af13c2e2aff92d710674d5bc5105f2b42f90a7d94d82ec023c

ENV JAVA_HOME=/opt/java/openjdk \
    JAVA_VERSION=jdk-27+35 \
    LANG=C.UTF-8 \
    PATH=/opt/java/openjdk/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# snapshot.ubuntu.com is HTTPS-only (plain http redirects), and the bare
# Ubuntu image carries no CA bundle, so the snapshot fetch borrows the one in
# the digest-pinned Temurin image until the ca-certificates package installed
# here replaces it. The bare image has no OpenSSL default CA path either
# (/usr/lib/ssl is absent), so apt is pointed at the bundle explicitly.
# Package integrity never rested on TLS: apt verifies every index against the
# archive signing key in /usr/share/keyrings.
COPY --from=temurin-jdk /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN apt-get -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt \
         update --snapshot "${APT_SNAPSHOT}" \
    && DEBIAN_FRONTEND=noninteractive apt-get \
         -o Acquire::https::CaInfo=/etc/ssl/certs/ca-certificates.crt \
         install -y --no-install-recommends --snapshot "${APT_SNAPSHOT}" git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=temurin-jdk /opt/java/openjdk /opt/java/openjdk

ADD --checksum=sha256:${CLOJURE_CLI_SHA256} \
    https://github.com/clojure/brew-install/releases/download/${CLOJURE_CLI_VERSION}/clojure-tools-${CLOJURE_CLI_VERSION}.tar.gz \
    /tmp/clojure-tools.tar.gz

RUN set -eu; \
    cd /tmp; \
    tar -xzf clojure-tools.tar.gz; \
    lib=/usr/local/lib/clojure; \
    mkdir -p "$lib/libexec" /usr/local/bin /usr/local/share/man/man1; \
    install -m 644 clojure-tools/deps.edn clojure-tools/example-deps.edn clojure-tools/tools.edn "$lib/"; \
    install -m 644 clojure-tools/exec.jar "clojure-tools/clojure-tools-${CLOJURE_CLI_VERSION}.jar" "$lib/libexec/"; \
    sed -i -e "s@PREFIX@$lib@g" clojure-tools/clojure; \
    sed -i -e "s@BINDIR@/usr/local/bin@g" clojure-tools/clj; \
    install -m 755 clojure-tools/clojure clojure-tools/clj /usr/local/bin/; \
    install -m 644 clojure-tools/clojure.1 clojure-tools/clj.1 /usr/local/share/man/man1/; \
    rm -rf /tmp/clojure-tools /tmp/clojure-tools.tar.gz; \
    java -version 2>&1 | grep -q '"27' ; \
    clojure -Sdescribe | grep -q "\"${CLOJURE_CLI_VERSION}\""

CMD ["clojure"]
