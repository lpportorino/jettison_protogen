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
	@echo "$(GREEN)Building base Docker image: $(DOCKER_BASE_IMAGE)$(NC)"
	@docker build -f Dockerfile.base -t $(DOCKER_BASE_IMAGE) .
	@echo "$(GREEN)Base Docker image built successfully$(NC)"

.PHONY: build
build: ## Build the main Docker image (builds base if needed)
	@echo "$(GREEN)Checking for base image...$(NC)"
	@if ! docker images | grep -q "jettison-proto-generator-base.*latest"; then \
		echo "$(YELLOW)Base image not found, building...$(NC)"; \
		$(MAKE) build-base; \
	fi
	@echo "$(GREEN)Building Docker image: $(DOCKER_IMAGE)$(NC)"
	@docker build -t $(DOCKER_IMAGE) .
	@echo "$(GREEN)Docker image built successfully$(NC)"

# Removed export-base and import-base targets - no longer using archived images

.PHONY: generate
generate: build ## Generate protocol buffer bindings for all languages
	@echo "$(GREEN)Generating protocol buffer bindings...$(NC)"
	@PROTO_SOURCE_DIR=$(PROTO_SOURCE_DIR) \
	 OUTPUT_BASE_DIR=$(OUTPUT_BASE_DIR) \
	 ./generate-protos.sh

.PHONY: rebuild
rebuild: clean-image generate ## Force rebuild Docker image and regenerate bindings
	@echo "$(GREEN)Rebuild complete$(NC)"

.PHONY: rebuild-base
rebuild-base: clean-base clean-image build-base ## Force rebuild base image
	@echo "$(GREEN)Base rebuild complete$(NC)"

.PHONY: clean
clean: ## Remove all generated files (preserves proto directory)
	@echo "$(YELLOW)Removing generated files...$(NC)"
	@if [ -d "$(OUTPUT_BASE_DIR)" ]; then \
		rm -rf $(OUTPUT_BASE_DIR); \
	fi
	@echo "$(GREEN)Generated files removed$(NC)"
	@echo "$(GREEN)Proto files preserved$(NC)"

.PHONY: clean-image
clean-image: ## Remove the main Docker image
	@echo "$(YELLOW)Removing Docker image...$(NC)"
	@docker rmi -f $(DOCKER_IMAGE) 2>/dev/null || true
	@echo "$(GREEN)Docker image removed$(NC)"

.PHONY: clean-base
clean-base: ## Remove the base Docker image
	@echo "$(YELLOW)Removing base Docker image...$(NC)"
	@docker rmi -f $(DOCKER_BASE_IMAGE) 2>/dev/null || true
	@echo "$(GREEN)Base Docker image removed$(NC)"

.PHONY: clean-all
clean-all: clean clean-image clean-base ## Remove all generated files and Docker images
	@echo "$(GREEN)All cleaned$(NC)"

.PHONY: test
test: ## Run a simple test generation with test proto
	@echo "$(GREEN)Running test generation...$(NC)"
	@mkdir -p test-output
	@PROTO_SOURCE_DIR=./test-proto \
	 OUTPUT_BASE_DIR=./test-output \
	 ./generate-protos.sh
	@echo "$(GREEN)Test complete - check test-output directory$(NC)"

.PHONY: shell
shell: build ## Open a shell in the Docker container
	@echo "$(GREEN)Opening shell in Docker container...$(NC)"
	@docker run --rm -it \
		-v "$$(pwd)/proto:/workspace/proto:ro" \
		-v "$$(pwd)/scripts:/workspace/scripts:ro" \
		-w /workspace \
		$(DOCKER_IMAGE)

.PHONY: versions
versions: build ## Show versions of tools in the Docker image
	@echo "$(GREEN)Tool versions in Docker image:$(NC)"
	@docker run --rm $(DOCKER_IMAGE) -c "\
		echo 'protoc version:' && protoc --version && echo && \
		echo 'go version:' && go version && echo && \
		echo 'rustc version:' && rustc --version && echo && \
		echo 'python version:' && python3 --version && echo && \
		echo 'java version:' && java --version | head -n1 && echo && \
		echo 'node version:' && node --version"

# === Documentation targets ===

.PHONY: docs-generate
docs-generate: ## Generate proto documentation (parse + extract + render)
	@echo "$(GREEN)Generating proto documentation...$(NC)"
	@cd docs/.protodoc/tools && clojure -M:run generate --descriptor ../../../output/json-descriptors/descriptor-set.json --output-dir ../.. --db-path ../proto-db.edn

.PHONY: docs-coverage
docs-coverage: ## Show proto documentation coverage
	@echo "$(GREEN)Documentation coverage:$(NC)"
	@bb docs/.protodoc/scripts/proto-coverage.clj docs/.protodoc/proto-db.edn

.PHONY: docs-search
docs-search: ## Search proto docs (usage: make docs-search Q="query")
	@bb docs/.protodoc/scripts/proto-search.clj "$(Q)" docs/.protodoc/proto-db.edn

.PHONY: docs-test
docs-test: ## Run proto documentation tests
	@echo "$(GREEN)Running documentation tests...$(NC)"
	@cd docs/.protodoc/tools && clojure -M:test

.PHONY: docs-docker-build
docs-docker-build: ## Build proto docs Docker image
	@echo "$(GREEN)Building proto docs Docker image...$(NC)"
	@cd docs/.protodoc/tools && DOCKER_BUILDKIT=1 docker build --network=host -t protodoc:latest .

.PHONY: docs-docker-test
docs-docker-test: ## Run proto docs tests in Docker
	@echo "$(GREEN)Running proto docs tests via Docker...$(NC)"
	@docker run --rm --network=host protodoc:latest -M:test

.PHONY: docs-docker-generate
docs-docker-generate: ## Generate docs using Docker
	@echo "$(GREEN)Generating proto docs via Docker...$(NC)"
	@docker run --rm --network=host \
		-v $$(pwd)/output/json-descriptors:/data/descriptors:ro \
		-v $$(pwd)/docs:/data/docs \
		protodoc:latest \
		-M:run generate \
		--descriptor /data/descriptors/descriptor-set.json \
		--output-dir /data/docs \
		--db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-coverage
docs-docker-coverage: ## Show coverage via Docker
	@echo "$(GREEN)Proto docs coverage via Docker...$(NC)"
	@docker run --rm --network=host \
		-v $$(pwd)/docs:/data/docs:ro \
		protodoc:latest \
		-M:run coverage --db-path /data/docs/.protodoc/proto-db.edn

.PHONY: docs-docker-all
docs-docker-all: docs-docker-build docs-docker-test docs-docker-generate ## Build, test, and generate in Docker
	@echo "$(GREEN)All Docker tasks complete$(NC)"