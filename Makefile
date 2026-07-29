# Protogen Makefile
# Docker-based Protocol Buffer Generator

# Variables
DOCKER_BASE_IMAGE := jettison-proto-generator-base:latest
DOCKER_IMAGE := jettison-proto-generator:latest
BASE_IMAGE_ARCHIVE := jettison-proto-generator-base.tar.gz
PROTO_SOURCE_DIR ?= ./proto
OUTPUT_BASE_DIR ?= ./output

# Colors for output
GREEN := \033[0;32m
YELLOW := \033[1;33m
NC := \033[0m # No Color

# Default target
.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help message
	@echo "Protogen - Docker-based Protocol Buffer Generator"
	@echo ""
	@echo "Usage: make [target]"
	@echo ""
	@echo "Targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  $(GREEN)%-15s$(NC) %s\n", $$1, $$2}'
	@echo ""
	@echo "Environment Variables:"
	@echo "  PROTO_SOURCE_DIR      Source proto directory (default: ./proto)"
	@echo "  OUTPUT_BASE_DIR       Output directory (default: ./output)"
	@echo ""
	@echo "Examples:"
	@echo "  make generate                    # Build image and generate all bindings"
	@echo "  make generate PROTO_SOURCE_DIR=/path/to/protos"
	@echo "  make clean                       # Remove generated files"
	@echo "  make rebuild                     # Force rebuild image and regenerate"

.PHONY: build-base
build-base: ## Build the base Docker image with all dependencies
	@printf "$(GREEN)Building base Docker image: $(DOCKER_BASE_IMAGE)$(NC)\n"
	@docker build -f Dockerfile.base -t $(DOCKER_BASE_IMAGE) .
	@printf "$(GREEN)Base Docker image built successfully$(NC)\n"

.PHONY: build
build: ## Build the main Docker image (builds base if needed)
	@printf "$(GREEN)Checking for base image...$(NC)\n"
	@if ! docker images | grep -q "jettison-proto-generator-base.*latest"; then \
		printf "$(YELLOW)Base image not found, building...$(NC)\n"; \
		$(MAKE) build-base; \
	fi
	@printf "$(GREEN)Building Docker image: $(DOCKER_IMAGE)$(NC)\n"
	@docker build -t $(DOCKER_IMAGE) .
	@printf "$(GREEN)Docker image built successfully$(NC)\n"

# Removed export-base and import-base targets - no longer using archived images

.PHONY: generate
generate: build ## Generate protocol buffer bindings for all languages
	@printf "$(GREEN)Generating protocol buffer bindings...$(NC)\n"
	@PROTO_SOURCE_DIR=$(PROTO_SOURCE_DIR) \
	 OUTPUT_BASE_DIR=$(OUTPUT_BASE_DIR) \
	 ./generate-protos.sh
	@$(MAKE) binary-dedup-run

.PHONY: rebuild
rebuild: clean-image generate ## Force rebuild Docker image and regenerate bindings
	@printf "$(GREEN)Rebuild complete$(NC)\n"

.PHONY: rebuild-base
rebuild-base: clean-base clean-image build-base ## Force rebuild base image
	@printf "$(GREEN)Base rebuild complete$(NC)\n"

.PHONY: clean
clean: ## Remove all generated files (preserves proto directory)
	@printf "$(YELLOW)Removing generated files...$(NC)\n"
	@if [ -d "$(OUTPUT_BASE_DIR)" ]; then \
		rm -rf $(OUTPUT_BASE_DIR); \
	fi
	@printf "$(GREEN)Generated files removed$(NC)\n"
	@printf "$(GREEN)Proto files preserved$(NC)\n"

.PHONY: binary-dedup
binary-dedup: generate ## Full generate + binary dedup tag map (use for standalone runs)

.PHONY: binary-dedup-run
# The tools directory is mounted READ-ONLY and copied into a container-local
# working directory, rather than mounted read-write as the cwd. The Clojure CLI
# writes its classpath cache to ./.cpcache beside any project deps.edn, and this
# container runs as root — so a read-write mount leaves a root-owned .cpcache in
# a tracked source tree, and lint.mk's docs-lint later runs `clojure` in exactly
# that directory as an ordinary user.
#
# WHAT BREAKS IS A WRITE, NOT A READ, and the difference decides the fix. The
# CLI's cache key folds in the PATHS of the config files it found, one of which
# is $HOME/.clojure/deps.edn — so a run under a different HOME computes a
# different key and must MINT its own .cp, into a directory it does not own. Its
# only fallback tests whether the CWD is writable, which it is, so the
# user-cache path is never taken. The cached entries naming this container's own
# .m2 are real but INCIDENTAL: no other user can select that file to begin with.
# Making those paths portable would therefore fix nothing.
#
# The read-only mount is what makes the write unrepresentable rather than merely
# avoided. The copy set is deps.edn plus the :paths it declares, ENUMERATED
# rather than a wholesale `cp -a /src/.` on purpose: copying everything would
# drag in whatever .cpcache and target/ the host happens to carry, re-importing
# the same problem from the other direction. `resources` is not optional —
# protodoc.render calls (io/resource "templates") at namespace-load time, so
# every invocation needs it on the classpath, this one included. Keep the set in
# step with :paths.
#
# `--entrypoint bash` names the shell explicitly because the image's default
# entrypoint dispatches on whether `clj <first-arg> --help` succeeds, which is a
# heuristic this recipe should not depend on.
#
# THIS RECIPE IS NOT THE WHOLE CLASS. docs-docker-test still mounts the repo
# read-write and runs as root with this same directory as its cwd, so it plants
# the identical cache here; the docs-docker-generate/render legs mount docs/ the
# same way. Fixing those is separate work — do not read this recipe as evidence
# the directory is safe.
binary-dedup-run: ## Generate binary dedup tag map (called automatically by generate)
	@printf "$(GREEN)Generating binary dedup tag map...$(NC)\n"
	@docker run --rm \
		-v "$$(pwd)/output/json-descriptors:/data/descriptors:ro" \
		-v "$$(pwd)/output/typescript:/data/output" \
		-v "$$(pwd)/docs/.protodoc/tools:/src:ro" \
		-w /app \
		--entrypoint bash \
		clojure:temurin-25-tools-deps-bookworm \
		-c 'cp -a /src/deps.edn /src/src /src/resources /app/ && exec clojure -M:run binary-dedup --descriptor /data/descriptors/descriptor-set.json --output /data/output/binary_dedup_tags.ts'
	@printf "$(GREEN)Binary dedup tag map generated$(NC)\n"

.PHONY: clean-image
clean-image: ## Remove the main Docker image
	@printf "$(YELLOW)Removing Docker image...$(NC)\n"
	@docker rmi -f $(DOCKER_IMAGE) 2>/dev/null || true
	@printf "$(GREEN)Docker image removed$(NC)\n"

.PHONY: clean-base
clean-base: ## Remove the base Docker image
	@printf "$(YELLOW)Removing base Docker image...$(NC)\n"
	@docker rmi -f $(DOCKER_BASE_IMAGE) 2>/dev/null || true
	@printf "$(GREEN)Base Docker image removed$(NC)\n"

.PHONY: clean-all
clean-all: clean clean-image clean-base ## Remove all generated files and Docker images
	@printf "$(GREEN)All cleaned$(NC)\n"

.PHONY: test
test: ## Run a simple test generation with test proto
	@printf "$(GREEN)Running test generation...$(NC)\n"
	@mkdir -p test-output
	@PROTO_SOURCE_DIR=./test-proto \
	 OUTPUT_BASE_DIR=./test-output \
	 ./generate-protos.sh
	@printf "$(GREEN)Test complete - check test-output directory$(NC)\n"

.PHONY: shell
shell: build ## Open a shell in the Docker container
	@printf "$(GREEN)Opening shell in Docker container...$(NC)\n"
	@docker run --rm -it \
		-v "$$(pwd)/proto:/workspace/proto:ro" \
		-v "$$(pwd)/scripts:/workspace/scripts:ro" \
		-w /workspace \
		$(DOCKER_IMAGE)

.PHONY: versions
versions: build ## Show versions of tools in the Docker image
	@printf "$(GREEN)Tool versions in Docker image:$(NC)\n"
	@docker run --rm $(DOCKER_IMAGE) -c "\
		echo 'protoc version:' && protoc --version && echo && \
		echo 'go version:' && go version && echo && \
		echo 'rustc version:' && rustc --version && echo && \
		echo 'python version:' && python3 --version && echo && \
		echo 'java version:' && java --version | head -n1 && echo && \
		echo 'node version:' && node --version && echo && \
		echo 'zig version:' && zig version"

# === Documentation targets ===

# Each protodoc leg below is a fresh JVM whose wall clock is dominated by
# Clojure LOADING — i.e. compiling — the same namespace tree (protodoc plus
# malli/selmer/telemere) all over again; on this tree that load is the large
# majority of the leg. `docs-aot` does that compile ONCE into
# docs/.protodoc/tools/target/classes, and the legs run through the `:aot`
# alias, which puts that directory on the classpath so the JVM loads bytecode
# instead of compiling source.
#
# It is a prerequisite rather than a manual step because it is content-hash
# gated: with the compiled output current it re-hashes the inputs and exits, so
# the common case adds a fraction of a second. See docs/.protodoc/tools/aot.sh
# for the freshness contract (and why it is a hash, not a timestamp).
#
# A NEW `-M:aot:run` LEG MUST TAKE `docs-aot` TOO — that hash is the only thing
# standing between a leg and stale bytecode. Clojure's own .class-vs-.clj
# preference is an MTIME comparison, so it does not cover an edit that arrives
# with an old timestamp (a `cp -p`, a tar extract, a checkout); aot.sh's header
# carries the reproduction.
#
# The test leg deliberately does NOT use it: a gate judges the source that
# ships. Nor does binary-dedup-run — that leg runs in its own container and
# would pay the compile without amortising it.
.PHONY: docs-aot
docs-aot: ## Compile protodoc to bytecode for the docs legs (content-hash gated)
	@docs/.protodoc/tools/aot.sh

.PHONY: docs-generate
docs-generate: docs-aot ## Generate proto documentation (parse + extract + render)
	@printf "$(GREEN)Generating proto documentation...$(NC)\n"
	@cd docs/.protodoc/tools && clojure -M:aot:run generate --descriptor ../../../output/json-descriptors/descriptor-set.json --output-dir ../.. --db-path ../proto-db.edn

.PHONY: docs-render
docs-render: docs-aot ## Render markdown from existing proto-db.edn (no parsing)
	@printf "$(GREEN)Rendering proto documentation...$(NC)\n"
	@cd docs/.protodoc/tools && clojure -M:aot:run render --output-dir ../.. --db-path ../proto-db.edn

.PHONY: docs-coverage
docs-coverage: ## Show proto documentation coverage
	@printf "$(GREEN)Documentation coverage:$(NC)\n"
	@bb docs/.protodoc/scripts/proto-coverage.clj docs/.protodoc/proto-db.edn

.PHONY: docs-search
docs-search: ## Search proto docs (usage: make docs-search Q="query")
	@bb docs/.protodoc/scripts/proto-search.clj "$(Q)" docs/.protodoc/proto-db.edn

.PHONY: docs-test
docs-test: ## Run proto documentation tests
	@printf "$(GREEN)Running documentation tests...$(NC)\n"
	@cd docs/.protodoc/tools && clojure -M:test

.PHONY: docs-manifests
docs-manifests: docs-aot ## Generate machine-readable JSON manifests from proto-db.edn
	@printf "$(GREEN)Generating proto manifests...$(NC)\n"
	@cd docs/.protodoc/tools && clojure -M:aot:run manifest --db-path ../proto-db.edn --config-path ../manifest-config.edn --output-dir ../../../output/manifests --git-sha "$$(cd ../../.. && git rev-parse --short HEAD 2>/dev/null || echo unknown)"
	@printf "$(GREEN)Manifests written to output/manifests/$(NC)\n"

.PHONY: docs-docker-build
docs-docker-build: ## Build proto docs Docker image
	@printf "$(GREEN)Building proto docs Docker image...$(NC)\n"
	@cd docs/.protodoc/tools && DOCKER_BUILDKIT=1 docker build --network=host -t protodoc:latest .

.PHONY: docs-docker-test
docs-docker-test: ## Run proto docs tests in Docker
# The REPO ROOT is mounted, not just the tools dir: the suite reaches out to
# ../proto-db.edn and ../../../output/json-descriptors/descriptor-set.binpb (the
# parity/roundtrip/manifest tests check the committed DB and generated bindings
# against the LIVE descriptor). Without the mount those files do not exist, the
# run fills with FileNotFoundException errors and goes permanently red — while
# silently skipping the tests that matter.
#
# The sharp part: that degradation is INVISIBLE in the test count, which is the
# same either way. Only the assertion count collapses. So judge a run by the
# runner's own summary, never by a test tally — including one written down here,
# which is why none is: it would rot on the next test added and then quietly
# disagree with the suite it claims to describe.
#
# MEASURED, both mounts, same image, same tree: the correct mount and a
# tools-dir-only mount ran the IDENTICAL number of tests, while the assertion
# count fell by ~96% and 89 errors appeared. Read the second half of that
# sentence as the good news it is — this recipe's warning has been quoted
# elsewhere as a SILENT degradation, and it is not one. The degraded run exits
# NON-ZERO and prints its errors; what it hides is only how much less it
# checked. So the failure mode here is a misread of scope, never a false green.
#
# MOUNTED READ-ONLY. This image declares no USER, so the payload runs as root
# with a tracked tree as its working directory — the shape that leaves a
# root-written .cpcache behind, in the very directory lint.mk's docs-lint later
# runs `clojure` in. A test suite reads; it has no reason to write into the
# source tree, and :ro makes that unrepresentable rather than merely unintended.
# Measured against this mount: 235 tests, 23657 assertions, 0 failures, and
# nothing written under docs/.protodoc/tools.
	@printf "$(GREEN)Running proto docs tests via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v "$$(pwd)":/repo:ro -w /repo/docs/.protodoc/tools \
		protodoc:latest -M:test

.PHONY: docs-docker-generate
docs-docker-generate: ## Generate docs using Docker
	@printf "$(GREEN)Generating proto docs via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/output/json-descriptors:/data/descriptors:ro \
		-v $$(pwd)/docs:/data/docs \
		protodoc:latest \
		-M:run generate \
		--descriptor /data/descriptors/descriptor-set.json \
		--output-dir /data/docs \
		--db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-render
docs-docker-render: ## Render markdown from proto-db.edn via Docker (no parsing)
	@printf "$(GREEN)Rendering proto docs via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs \
		protodoc:latest \
		-M:run render \
		--output-dir /data/docs \
		--db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-coverage
docs-docker-coverage: ## Show coverage via Docker
	@printf "$(GREEN)Proto docs coverage via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs:ro \
		protodoc:latest \
		-M:run coverage --db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-lint
docs-docker-lint: ## Lint proto documentation quality via Docker
	@printf "$(GREEN)Proto docs lint via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs:ro \
		protodoc:latest \
		-M:run lint --db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-all
docs-docker-all: docs-docker-build docs-docker-test docs-docker-generate ## Build, test, and generate in Docker
	@printf "$(GREEN)All Docker tasks complete$(NC)\n"