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

# ── go leg reproducibility ────────────────────────────────────────────────────
# NOT a prerequisite of `generate`, and NOT in any CI workflow. That is a
# decision with a reason, not an omission:
#   - AFTER `make generate` it is vacuous — generate has just written those exact
#     bytes with that exact image, so the comparison cannot fail.
#   - BEFORE `make generate` it is WRONG — the whole job of the release workflow
#     is to regenerate, so it would red on every legitimate proto change.
# The condition it actually catches is developer-local: a WARM image built from
# older pins, which CI never has because CI builds cold. So it is an on-demand
# check, and it lives here rather than nowhere so that it — and the canary that
# proves it can fail — are discoverable and runnable. Both are host-only: they
# drive docker, which tools/uber.sh's image does not carry.
.PHONY: go-leg-repro
go-leg-repro: ## Verify output/go is byte-identical to a fresh offline go-leg run
	@./tools/go_leg_repro.sh

.PHONY: go-leg-repro-canary
go-leg-repro-canary: ## Prove the go-leg reproducibility check can FAIL
	@./tools/go_leg_repro.sh --canary

# ── orphaned generated files ──────────────────────────────────────────────────
# The OTHER direction from go-leg-repro, over ALL eleven legs: a committed path
# that no leg produces any more. Generation never deletes, so such a file stays
# in output/ for ever and the fan-out keeps copying it into ten consumer
# repositories.
#
# IT IS WIRED, AND NOT HERE. The armed caller is build-and-release.yml, as a step
# between `make generate` and the first consumer push. That placement is the
# whole reason this check can be a gate where go-leg-repro deliberately is not,
# and the argument is the exact inverse of the one above: byte-identity is
# vacuous AFTER a regeneration, while orphan-hood is only honest after one —
# generate never deletes, so an orphan survives a full regeneration untouched.
#
# NOT IN `lint`, AND NOT IN THE PRE-PUSH HOOK, on purpose. Both run on a plain
# host: this check needs docker plus the multi-gigabyte generator image, so on a
# checkout without them it would either hard-fail every push or — far worse —
# have to skip, and a skip here is a green tick over zero coverage. The release
# job is the one caller that always has both, and it is also the last point
# before the bindings reach a consumer.
#
# These two targets exist so the check and the canary that proves it can fail
# are discoverable and runnable locally. Host-only, like the pair above.
.PHONY: orphan-scan
orphan-scan: ## Find committed generated files that no generation leg produces
	@./tools/orphan_scan.sh

.PHONY: orphan-scan-canary
orphan-scan-canary: ## Prove the orphan check can FAIL, once per mechanism
	@./tools/orphan_scan.sh --canary

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

# EVERY LEG BELOW THAT RUNS THE BAKED GENERATOR TAKES docs-docker-build AS A
# PREREQUISITE, and that is a correctness requirement rather than convenience.
#
# The image's Dockerfile does `COPY src /app/src/`, so the generator is BAKED IN.
# The legs then mount only DATA — docs/, output/manifests/, the descriptors — and
# run `-M:run` with /app as the working directory. So an edit under
# docs/.protodoc/tools/src has NO EFFECT on any of them until the image is
# rebuilt, and the failure is silent AND INVERTED: the target reports success,
# writes the OLD output over the tracked tree, and the freshness comparison then
# calls the tree stale for a reason that is not the real one.
#
# The inversion is what makes it expensive. renderer.mk's `manifests-proto-db`
# judges output/manifests/ by re-emitting from `-M:run` in the WORKING TREE, so
# writer and judge ran different generators — the writer the image's, the judge
# the tree's — and the judge's remedy line names `docs-manifests`, a THIRD path
# (host, AOT-loaded). Three generators, one artifact, and nothing said so.
#
# MEASURED before this prerequisite existed: a one-token change to
# manifest.clj's emitted `:version`, then `make docs-docker-manifests` — exit 0,
# all four manifests rewritten, and the token absent from every one of them.
#
# WHY A PREREQUISITE RATHER THAN A GUARD. A guard comparing the image's baked
# source against the working tree has to name the compared set, and that set is
# exactly the Dockerfile's COPY list — a second copy of a fact the Dockerfile
# already owns, free to go short the day a path is added to it, and short in the
# direction that passes. `docker build` derives the same set from the Dockerfile
# itself, so it cannot be under-specified. The cost is what makes this affordable
# rather than merely correct: deps.edn is COPYed FIRST and the `clojure -P`
# prefetch layer is keyed on it alone, so a source-only edit re-runs the COPY
# layers and nothing else — measured warm on this tree, both a no-op build and a
# source-changed rebuild finish in well under a second. Cold, it costs the
# prefetch once, which any first use of these legs owes regardless.
#
# docs-docker-test is DELIBERATELY NOT in this set. It mounts the repo and runs
# with -w /repo/docs/.protodoc/tools, so `:paths ["src" "resources"]` resolves
# into the WORKING TREE: it already tests the tree's source, and the image
# supplies only the dependency cache. Adding the prerequisite there would buy
# nothing and imply the leg had the defect.
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
docs-docker-generate: docs-docker-build ## Generate docs using Docker
	@printf "$(GREEN)Generating proto docs via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/output/json-descriptors:/data/descriptors:ro \
		-v $$(pwd)/docs:/data/docs \
		protodoc:latest \
		-M:run generate \
		--descriptor /data/descriptors/descriptor-set.json \
		--output-dir /data/docs \
		--db-path /data/docs/.protodoc/proto-db.edn

# The containerized twin of docs-manifests. It exists because the docs chain is
# otherwise fully runnable without a host toolchain, and this one step was not:
# output/manifests/ is tracked, is NOT a binding output, and `make generate`
# does not write it — so a proto change that followed the documented chain left
# the manifests stale and reddened renderer.yml's manifest-freshness step, which
# is the only place that drift surfaces.
.PHONY: docs-docker-manifests
docs-docker-manifests: docs-docker-build ## Generate output/manifests/ from proto-db.edn via Docker
	@printf "$(GREEN)Generating proto manifests via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs \
		-v $$(pwd)/output/manifests:/data/manifests \
		protodoc:latest \
		-M:run manifest \
		--db-path /data/docs/.protodoc/proto-db.edn \
		--config-path /data/docs/.protodoc/manifest-config.edn \
		--output-dir /data/manifests \
		--git-sha "$$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
	@printf "$(GREEN)Manifests written to output/manifests/$(NC)\n"

.PHONY: docs-docker-render
docs-docker-render: docs-docker-build ## Render markdown from proto-db.edn via Docker (no parsing)
	@printf "$(GREEN)Rendering proto docs via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs \
		protodoc:latest \
		-M:run render \
		--output-dir /data/docs \
		--db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-coverage
docs-docker-coverage: docs-docker-build ## Show coverage via Docker
	@printf "$(GREEN)Proto docs coverage via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs:ro \
		protodoc:latest \
		-M:run coverage --db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-lint
docs-docker-lint: docs-docker-build ## Lint proto documentation quality via Docker
	@printf "$(GREEN)Proto docs lint via Docker...$(NC)\n"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs:ro \
		protodoc:latest \
		-M:run lint --db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-all
docs-docker-all: docs-docker-build docs-docker-test docs-docker-generate ## Build, test, and generate in Docker
	@printf "$(GREEN)All Docker tasks complete$(NC)\n"

# === protocol-gen — descriptors + policy EDN -> per-group .proto ===============
#
# The tool lives in tools/protocol-gen and has its own deps.edn. These targets
# are the ONLY way it is reachable without typing a classpath, so they exist to
# make it discoverable and runnable rather than to gate anything.
#
# NOT IN ANY AGGREGATE THIS REPOSITORY ALREADY RUNS, and that is a GAP rather
# than a decision. `.claude/rules/gate-enforcement.md` §6 is explicit that a
# gate reachable only by a human typing its target is not armed, and none of
# the three files that could arm one — lint.mk's `lint-lanes`,
# .githooks/pre-push, .github/workflows/ — is this tool's to edit. What is owed,
# stated here so it is not rediscovered:
#
#   * `protocol-gen-lint` and `protocol-gen-test` join `lint.mk`'s `lint-lanes`,
#     and `tools/protocol-gen/{src,test,verify}` join `LINT_CLJ_PATHS` so the
#     Clojure lanes reach this tree the way they reach every other one. Until
#     then `make -f lint.mk audit-clj-paths` reports these files as UNGATED,
#     which is the honest report and the reason that audit exists.
#   * `protocol-gen-canary` joins the same aggregate; it needs bash, git,
#     clojure and protoc, and the first three are already `lint`'s footprint.
#
# WHY THE ENCODING FLAGS. Same reason lint.mk carries them: the JVMs here write
# findings, and a stdout left at the image's ASCII default loses every non-ASCII
# character AT WRITE TIME rather than merely rendering it oddly.
PROTOCOL_GEN_DIR := tools/protocol-gen
PROTOCOL_GEN_CLJ := clojure -J-Dstdout.encoding=UTF-8 -J-Dstderr.encoding=UTF-8
PROTOCOL_GEN_PATHS := $(PROTOCOL_GEN_DIR)/src $(PROTOCOL_GEN_DIR)/test $(PROTOCOL_GEN_DIR)/verify

.PHONY: protocol-gen-test
protocol-gen-test: ## Run the protocol-gen unit suite (arms its malli specs)
	@printf "$(GREEN)protocol-gen: unit suite$(NC)\n"
	@cd $(PROTOCOL_GEN_DIR) && $(PROTOCOL_GEN_CLJ) -M:test

.PHONY: protocol-gen-lint
protocol-gen-lint: ## cljfmt + clj-kondo over the protocol-gen tree
	@printf "$(GREEN)protocol-gen: cljfmt$(NC)\n"
	@$(PROTOCOL_GEN_CLJ) -M:fmt check $(PROTOCOL_GEN_PATHS)
	@printf "$(GREEN)protocol-gen: clj-kondo$(NC)\n"
	@clj-kondo --parallel --cache false --fail-level warning --lint $(PROTOCOL_GEN_PATHS)

.PHONY: protocol-gen-survey
protocol-gen-survey: ## Enumerate what a descriptor database can and cannot be emitted from
	@cd $(PROTOCOL_GEN_DIR) && $(PROTOCOL_GEN_CLJ) -M:run survey \
		--db $(if $(DB),$(DB),../../docs/.protodoc/proto-db.edn)

.PHONY: protocol-gen-check
protocol-gen-check: protocol-gen-lint protocol-gen-test ## Every protocol-gen lane
	@printf "$(GREEN)protocol-gen: all lanes green$(NC)\n"