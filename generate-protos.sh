#!/usr/bin/env bash

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Configuration
DOCKER_IMAGE="jettison-proto-generator:latest"
PROTO_SOURCE_DIR="${PROTO_SOURCE_DIR:-../proto}"
OUTPUT_BASE_DIR="${OUTPUT_BASE_DIR:-./output}"

# THE ONE HOME of the per-language list. CLAUDE.md ("Where things live") states
# that this list has exactly one home and that re-enumerating it anywhere is what
# kept getting it wrong — so every site below expands this array and none retypes
# it. Adding a language is one edit here.
LANGS=(c cpp go kotlin python typescript rust zig java json-descriptors typescript-validated)

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1" >&2
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if proto source directory exists
if [ ! -d "$PROTO_SOURCE_DIR" ]; then
    print_error "Proto source directory not found: $PROTO_SOURCE_DIR"
    print_info "Set PROTO_SOURCE_DIR environment variable or ensure ../proto exists"
    exit 1
fi

# Function to check if Docker image exists
image_exists() {
    docker image inspect "$DOCKER_IMAGE" &> /dev/null
}

# Build Docker image if it doesn't exist
build_image() {
    print_info "Building Docker image: $DOCKER_IMAGE"
    if docker build -t "$DOCKER_IMAGE" .; then
        print_info "Docker image built successfully"
    else
        print_error "Failed to build Docker image"
        exit 1
    fi
}

# Check if image exists, build if not
if ! image_exists; then
    print_warning "Docker image not found. Building..."
    build_image
else
    print_info "Docker image found: $DOCKER_IMAGE"
    # Optional: Ask if user wants to rebuild
    if [ "${REBUILD_IMAGE:-false}" == "true" ]; then
        print_info "Rebuilding image (REBUILD_IMAGE=true)"
        build_image
    fi
fi

# REFUSE A STALE IMAGE before any leg runs. The main image is rebuilt above, but
# the base image under it is reused whenever it exists, so a base built before a
# Dockerfile.base pin moved would regenerate every affected file with the OLD
# input and nothing downstream would notice. tools/image_pin_check.sh compares
# the image against the pins and prints the fix.
if ! "$SCRIPT_DIR/tools/image_pin_check.sh" --image "$DOCKER_IMAGE"; then
    print_error "The generator image does not carry the Dockerfile.base pins; no leg was run."
    exit 1
fi

# Create output directories with full permissions
print_info "Creating output directories..."
for lang in "${LANGS[@]}"; do mkdir -p "$OUTPUT_BASE_DIR/$lang"; done
# Set directory permissions to 777
chmod -R 777 "$OUTPUT_BASE_DIR" 2>/dev/null || true

# Copy proto files to local directory to avoid permission issues
print_info "Preparing proto files..."
# Only copy if source is different from ./proto
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
    cp -r "$PROTO_SOURCE_DIR" ./proto
fi

# Function to run generation in Docker
run_generation() {
    local lang=$1
    local script=$2

    print_info "Generating $lang bindings..."

    # ONE HOME FOR LEG STRICTNESS. Every payload re-arms a bare `set -e`, which
    # clears NEITHER -u NOR -o pipefail, so prepending here reaches all of them and
    # no payload needs editing — and a leg added later cannot forget it.
    #
    # It sits HERE rather than in the payloads because this assignment is
    # DOUBLE-quoted: the single-quoted payloads carry an apostrophe-rebalancing
    # hazard (tools/payload_apostrophes.awk) that this line does not touch.
    #
    # WHAT IT REPAIRS: the rust leg's `cargo build 2>&1 | tail -5` reported TAIL's
    # status, so a broken build exited 0 and the leg printed "completed
    # successfully". Without pipefail no leg can fail on the left of a pipe.
    # Scope honestly: pipefail fixes that one live defect; -u is prophylactic —
    # no payload references an unset variable today.
    # Modified script to set permissions inside container
    local full_script="set -euo pipefail
$script
# Set permissions to 777 for all generated files
find /workspace/output -type f -exec chmod 777 {} + 2>/dev/null || true
find /workspace/output -type d -exec chmod 777 {} + 2>/dev/null || true"

    # NO BSR CREDENTIAL IS FORWARDED, AND NONE IS NEEDED.
    #
    # The go leg used to carry a `-e BUF_TOKEN` here because its buf.gen.yaml
    # named two REMOTE plugins, making `buf generate` a Buf Schema Registry
    # codegen request — 10 requests/hour unauthenticated, 960 with a token. The
    # leg now runs the same two plugins as LOCAL binaries pinned in
    # Dockerfile.base, so it makes no registry request at all: no budget, no
    # 429, no credential, and no behaviour that differs between a repo build
    # and a fork build. The whole conditional went with the cause it worked
    # around, rather than lingering as a knob that no longer does anything.
    #
    # No leg reads BUF_TOKEN now. The json-descriptors leg also runs buf, but
    # only `buf build`, which was always local.

    docker run --rm \
        -v "$SCRIPT_DIR/proto:/workspace/proto:ro" \
        -v "$SCRIPT_DIR/output/$lang:/workspace/output:rw" \
        -v "$SCRIPT_DIR/scripts:/workspace/scripts:ro" \
        -w /workspace \
        "$DOCKER_IMAGE" \
        -c "$full_script"
    
    if [ $? -eq 0 ]; then
        print_info "$lang generation completed successfully"
    else
        print_error "$lang generation failed"
        return 1
    fi
}

# C generation script
C_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto

# Process all proto files including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$relpath"
done

# Copy nanopb .options files (controls field types/sizes for generated C structs)
find proto -name "*.options" -type f -not -path "*/test/*" | while read -r opts; do
    relpath="${opts#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    cp "$opts" "/tmp/cleaned_proto/$relpath"
done

# NO APOSTROPHES ANYWHERE IN THIS BLOCK. It lives inside a single-quoted
# assignment, so one apostrophe closes the string — and an EVEN number is worse
# than an odd one, because the quoting rebalances, bash -n passes, and the
# payload silently becomes EMPTY. That is how this comment shipped broken once.
#
# --nanopb_opt=-I is NOT redundant with the protoc -I below it. The protoc
# include path resolves proto IMPORTS; the nanopb PLUGIN searches its own,
# separately, to find the sibling .options file carrying max_size/max_count.
# Without it the plugin finds no options and every string falls back to a
# pb_callback_t, so the bounds in proto/ui/ui_ast.options never apply.
#
# Measured on ui_ast.proto in this image: without the flag, 0 char[N] fields and
# 42 callbacks; with it, 22 char[N] and 10 callbacks, and
# SubjectDeclaration.name becomes char name[64] — matching what the renderer
# compiles. The failure is silent both ways: protoc exits 0 and the generated C
# is valid, merely unbounded.
find /tmp/cleaned_proto -name "*.proto" -print0 | sort -z | xargs -0 -P 8 -I{} \
    protoc --plugin=protoc-gen-nanopb=/opt/nanopb/generator/protoc-gen-nanopb \
    -I/tmp/cleaned_proto \
    --nanopb_opt=-I/tmp/cleaned_proto \
    --nanopb_out=/workspace/output \
    {}
# Copy nanopb runtime files that are needed for compilation
cp /opt/nanopb/pb.h /workspace/output/
cp /opt/nanopb/pb_common.h /workspace/output/
cp /opt/nanopb/pb_common.c /workspace/output/
cp /opt/nanopb/pb_encode.h /workspace/output/
cp /opt/nanopb/pb_encode.c /workspace/output/
cp /opt/nanopb/pb_decode.h /workspace/output/
cp /opt/nanopb/pb_decode.c /workspace/output/
# nanopb (zlib licence) is vendored, not authored here: carry its licence
# alongside the runtime files on every regeneration so a standalone copy of
# output/c/ (this is what ships to jettison_proto_c) always keeps the notice
# the zlib licence requires it not be separated from.
cp /opt/nanopb/LICENSE.txt /workspace/output/LICENSE.nanopb
'

# C++ generation script with buf.validate support
CPP_SCRIPT='
set -e
mkdir -p /tmp/cpp_proto_val

# Copy proto files WITH validate imports including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cpp_proto_val/$dirname"
    cp "$proto" "/tmp/cpp_proto_val/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/cpp_proto_val/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/cpp_proto_val/

# Generate C++ with validation annotations preserved
CPP_PROTO_FILES=$(find /tmp/cpp_proto_val -name "*.proto" -type f ! -path "*/buf/*" ! -path "*/test/*" | sort)
protoc -I/tmp/cpp_proto_val \
    --cpp_out=/workspace/output \
    $CPP_PROTO_FILES

echo "C++ generation with buf.validate support completed"
'

# Go generation script with validation support
GO_SCRIPT='
set -e

# MODULE-MANIFEST PINS. The only three values of the go.mod files this leg
# writes (see "Go module manifests" at the end of this payload) that nothing
# inside the image can derive. Every other value is read from what the leg
# itself runs and produces. NO APOSTROPHES in this block either -- see C_SCRIPT.
#
# JONP_MODULE -- the module path of the bindings this repository owns, which is
# what consumers require and replace. It is an IDENTITY, not a measurement:
# inferring it from the go_package options would silently turn a stray
# go_package into a different module (and drop the jonp go.mod). So it is pinned,
# and every go_package outside it -- other than the vendored buf/validate one --
# is a hard error.
JONP_MODULE="git-codecommit.eu-central-1.amazonaws.com/v1/repos/jettison/jonp"
# GO_MOD_GO_DIRECTIVE -- the go line of both manifests. It mirrors the Go floor
# of the consumer modules these bindings are vendored into and replaced into,
# and was chosen by hand when the manifests were first committed. It is NOT the
# toolchain in this image (Dockerfile.base GO_VERSION): the image only runs the
# generators and never builds the emitted packages, so deriving the line from
# it would rewrite a consumer-visible floor on every toolchain bump.
GO_MOD_GO_DIRECTIVE="1.26"
# PROTOVALIDATE_BSR_REF -- the <commit-timestamp>-<commit-id>.<plugin-revision>
# tail of the buf.build/gen/go generated-SDK version the jonp module requires
# for buf/validate. It names a Buf Schema Registry commit of
# buf.build/bufbuild/protovalidate, and a BSR commit id is NOT a git commit: it
# is no object in the /opt/protovalidate clone, so no offline step can compute
# it. The version PREFIX is derived (the protobuf module version below), so a
# protoc-gen-go bump moves both requirements together.
#
# IT IS A DIFFERENT REVISION FROM Dockerfile.base PROTOVALIDATE_REF (the git
# commit whose validate.proto every validate-aware leg compiles), and that is
# fine: a consumer that replaces only jonp into output/go -- the power
# dashboard -- resolves this SDK through the module proxy and compiles against
# IT, never against the validate.pb.go this leg emits. The two pins answer
# different questions and move independently.
PROTOVALIDATE_BSR_REF="20250130201111-63bb56e20495.1"

# The leg generates into an EMPTY scratch directory and copies the result into
# /workspace/output at the end. The output directory is a bind mount of the
# TRACKED tree, and buf does not rewrite a file whose content is unchanged, so
# judging the leg by what sits in /workspace/output would judge an earlier run.
GO_LEG_OUT=/tmp/go_leg_out
rm -rf "$GO_LEG_OUT"
mkdir -p "$GO_LEG_OUT"

mkdir -p /tmp/go_proto_val

# Copy proto files and add validate import including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/go_proto_val/$dirname"
    cp "$proto" "/tmp/go_proto_val/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/go_proto_val/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/go_proto_val/

# Create buf.yaml for the generation
cd /tmp/go_proto_val
cat > buf.yaml << "BUF_EOF"
version: v2
modules:
  - path: .
    name: buf.build/jettison/jonp
BUF_EOF

# Create buf.gen.yaml for Go generation with validation
#
# LOCAL PLUGINS, NOT REMOTE. These two entries used to name BSR remote plugins
# (buf.build/protocolbuffers/go and buf.build/grpc/go), which made buf generate
# a Buf Schema Registry codegen REQUEST: metered at 10 requests/hour without a
# BUF_TOKEN, so a rate limit could redden the whole run, and it needed a
# credential no fork build can hold.
#
# It was NOT the only leg reaching the network -- TYPESCRIPT_SCRIPT runs
# npm install and RUST_SCRIPT runs cargo build, both of which fetch -- but it
# was the only one whose reach was CREDENTIALED and METERED, which is the part
# that made the leg fail for reasons unrelated to the protos.
#
# NO APOSTROPHES ANYWHERE IN THIS BLOCK -- it lives inside a single-quoted
# assignment; see the identical warning in C_SCRIPT above for what an even
# number of them does.
#
# The binaries come from Dockerfile.base, pinned to the exact versions the
# remote entries named, so the switch moves no generated bytes. Bumping either
# pin is a regeneration, not a toolchain tidy-up: protoc-gen-go stamps its own
# version into every file header it writes.
cat > buf.gen.yaml << BUF_EOF
version: v2
managed:
  enabled: true
  override:
    - file_option: go_package_prefix
      value: ""
plugins:
  - local: protoc-gen-go
    out: $GO_LEG_OUT
  - local: protoc-gen-go-grpc
    out: $GO_LEG_OUT
BUF_EOF

# Generate using buf
echo "Generating Go bindings with buf.validate support using buf generate..."
buf generate

# Verify files were generated -- in the scratch directory, where an empty
# result cannot be masked by files an earlier run left behind.
if [ -z "$(find "$GO_LEG_OUT" -name "*.pb.go" -type f 2>/dev/null)" ]; then
    echo "ERROR: No Go files were generated!"
    exit 1
fi
echo "Go generation successful, found $(find "$GO_LEG_OUT" -name "*.pb.go" -type f | wc -l) .pb.go files"

# -- Go module manifests --------------------------------------------------------
# buf generate writes .pb.go files and no go.mod, and a manifest no leg produces
# is one a regeneration can drop: a consumer whose go.mod replaces the jonp
# module into output/go then cannot resolve it, and go vet fails there. So the
# leg WRITES both manifests on every run, from what it just ran and produced:
#   module paths      JONP_MODULE (pinned, every go_package checked against it)
#                     and the protovalidate SDK module (its go_package, minus
#                     the directory the proto sits in)
#   require set       the imports of every file in the scratch output; an import
#                     outside the module itself, the protobuf runtime and the
#                     protovalidate SDK is an ERROR, never a guess
#   protobuf module   the build info of the protoc-gen-go binary buf just ran
#   SDK version       that protobuf version + PROTOVALIDATE_BSR_REF
#   go line           GO_MOD_GO_DIRECTIVE
# Layout is the canonical go mod form: a single requirement on the require line,
# several in a sorted block.

go_package_of() {
    sed -n "s|^option go_package *= *\"\([^\";]*\).*|\1|p" "$1"
}

# protobuf module + version, from the plugin binary itself.
read -r PROTOBUF_MODULE PROTOBUF_VERSION <<< "$(go version -m "$(command -v protoc-gen-go)" \
    | sed -n "s/^[[:space:]]*mod[[:space:]]\{1,\}\([^[:space:]]*\)[[:space:]]\{1,\}\([^[:space:]]*\).*/\1 \2/p")"
if [ -z "${PROTOBUF_MODULE:-}" ] || [ -z "${PROTOBUF_VERSION:-}" ]; then
    echo "ERROR: could not read the protobuf module and version out of protoc-gen-go build info"
    exit 1
fi

# protovalidate: the SDK module is its go_package minus the directory the proto
# sits in, which is how both BSR generated SDKs and this output tree lay it out.
PV_PROTO="buf/validate/validate.proto"
PV_GO_PACKAGE="$(go_package_of "$PV_PROTO")"
PV_MODULE="${PV_GO_PACKAGE%/"$(dirname "$PV_PROTO")"}"
if [ -z "$PV_GO_PACKAGE" ] || [ "$PV_MODULE" = "$PV_GO_PACKAGE" ]; then
    echo "ERROR: cannot derive the protovalidate module from go_package [$PV_GO_PACKAGE] of $PV_PROTO"
    exit 1
fi
PV_GENERATED="$GO_LEG_OUT/$PV_GO_PACKAGE/$(basename "$PV_PROTO" .proto).pb.go"
# POSITIVE CONTROL on the version source: the file buf just wrote must carry
# the same protoc-gen-go version the build info reports.
if ! grep -q "^//[[:space:]]*protoc-gen-go $PROTOBUF_VERSION\$" "$PV_GENERATED"; then
    echo "ERROR: $PV_GENERATED does not carry protoc-gen-go $PROTOBUF_VERSION in its header"
    exit 1
fi

# Every go_package this repository declares must sit inside JONP_MODULE.
n_pkgs=0
for pkg in $(find . -name "*.proto" -type f -not -path "./buf/*" -exec sed -n "s|^option go_package *= *\"\([^\";]*\).*|\1|p" {} + | sort -u); do
    n_pkgs=$((n_pkgs + 1))
    case "$pkg" in
        "$JONP_MODULE"|"$JONP_MODULE"/*) ;;
        *) echo "ERROR: go_package $pkg is outside the pinned module $JONP_MODULE"; exit 1 ;;
    esac
done
if [ "$n_pkgs" -eq 0 ]; then
    echo "ERROR: no proto under proto/ declares a go_package"
    exit 1
fi

# Every generated file must belong to one of the two modules a go.mod is
# written for; a file outside both would ship unimportable.
stray="$(find "$GO_LEG_OUT" -name "*.pb.go" -type f -not -path "$GO_LEG_OUT/$JONP_MODULE/*" -not -path "$GO_LEG_OUT/$PV_MODULE/*")"
if [ -n "$stray" ]; then
    echo "ERROR: generated files outside both modules: $stray"
    exit 1
fi

# collect_requirements <module>: sets REQUIREMENTS to the require lines the
# module needs, judged from the imports of every file generated for it.
collect_requirements() {
    local mod="$1" dir="$GO_LEG_OUT/$1" imp needs_pb=0 needs_pv=0 n
    n="$(find "$dir" -name "*.pb.go" -type f | wc -l)"
    if [ "$n" -eq 0 ]; then
        echo "ERROR: the leg generated no .pb.go under $dir; cannot judge its imports"
        exit 1
    fi
    for imp in $(find "$dir" -name "*.pb.go" -type f \
            -exec sed -n "/^import (/,/^)/ s|^[[:space:]]*\([A-Za-z0-9_.]* \)\{0,1\}\"\([^\"]*\)\"$|\2|p" {} + | sort -u); do
        case "$imp" in
            "$mod"|"$mod"/*) ;;
            "$PROTOBUF_MODULE"|"$PROTOBUF_MODULE"/*) needs_pb=1 ;;
            "$PV_MODULE"|"$PV_MODULE"/*) needs_pv=1 ;;
            *)
                case "${imp%%/*}" in
                    *.*) echo "ERROR: $mod imports $imp, which belongs to no module this leg can version"; exit 1 ;;
                esac
                ;;
        esac
    done
    # NON-VACUITY: every generated file imports the protobuf runtime, so a module
    # that appears not to is an extraction that stopped matching.
    if [ "$needs_pb" -eq 0 ]; then
        echo "ERROR: found no $PROTOBUF_MODULE import under $dir; the import extraction broke"
        exit 1
    fi
    REQUIREMENTS=("$PROTOBUF_MODULE $PROTOBUF_VERSION")
    if [ "$needs_pv" -eq 1 ]; then
        REQUIREMENTS+=("$PV_MODULE $PROTOBUF_VERSION-$PROTOVALIDATE_BSR_REF")
    fi
}

# write_go_mod <module>: writes $GO_LEG_OUT/<module>/go.mod from REQUIREMENTS.
write_go_mod() {
    local mod="$1"
    {
        printf "module %s\n\ngo %s\n\n" "$mod" "$GO_MOD_GO_DIRECTIVE"
        if [ "${#REQUIREMENTS[@]}" -eq 1 ]; then
            printf "require %s\n" "${REQUIREMENTS[0]}"
        else
            printf "require (\n"
            printf "\t%s\n" "${REQUIREMENTS[@]}" | LC_ALL=C sort
            printf ")\n"
        fi
    } > "$GO_LEG_OUT/$mod/go.mod"
    echo "Wrote go.mod for $mod"
}

for mod in "$JONP_MODULE" "$PV_MODULE"; do
    collect_requirements "$mod"
    write_go_mod "$mod"
done

# Publish: the whole leg output, manifests included, into the output directory.
mkdir -p /workspace/output
cp -a "$GO_LEG_OUT"/. /workspace/output/
'

# Kotlin generation script with validation support (uses protoc alongside Java for package consistency)
# NOTE: Kotlin codegen depends on Java classes, so packages MUST match.
# We use protoc directly (not buf) to ensure the proto package is respected without prefix.
KOTLIN_SCRIPT='
set -e
mkdir -p /tmp/kotlin_proto_val

# Copy proto files and add validate import including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/kotlin_proto_val/$dirname"
    cp "$proto" "/tmp/kotlin_proto_val/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/kotlin_proto_val/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/kotlin_proto_val/

# Ensure output directory exists
mkdir -p /workspace/output

# Generate Kotlin using protoc (must match Java package structure)
# Kotlin DSL wrappers require Java classes, so package must be identical
PROTO_FILES=$(find /tmp/kotlin_proto_val -name "*.proto" -type f ! -path "*/buf/*" ! -path "*/test/*" | sort)
protoc -I/tmp/kotlin_proto_val \
    --kotlin_out=/workspace/output \
    $PROTO_FILES

# Verify files were generated
if [ -z "$(find /workspace/output -name "*.kt" -type f 2>/dev/null)" ]; then
    echo "ERROR: No Kotlin files were generated!"
    exit 1
fi
echo "Kotlin generation successful, found $(find /workspace/output -name "*.kt" -type f | wc -l) .kt files"
'

# Python generation script
PYTHON_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto

# Process all proto files including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$relpath"
done

PROTO_FILES=$(find /tmp/cleaned_proto -name "*.proto" -type f | sort)
protoc -I/tmp/cleaned_proto \
    --python_out=/workspace/output \
    --pyi_out=/workspace/output \
    $PROTO_FILES
'

# TypeScript generation script
TYPESCRIPT_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto

# Process all proto files including subdirectories (exclude test directory)
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$relpath"
done

# Create temporary node project for ts-proto
cd /tmp
npm init -y
npm install ts-proto

# Find all proto files for protoc
PROTO_FILES=$(find /tmp/cleaned_proto -name "*.proto" -type f | sort)
protoc -I/tmp/cleaned_proto \
    --plugin=protoc-gen-ts_proto=/tmp/node_modules/.bin/protoc-gen-ts_proto \
    --ts_proto_opt=outputIndex=true \
    --ts_proto_opt=esModuleInterop=true \
    --ts_proto_opt=forceLong=long \
    --ts_proto_out=/workspace/output \
    $PROTO_FILES
'

# Rust generation script
RUST_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto /tmp/rust_gen

# Process all proto files including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$relpath"
done

# Ensure the output directory exists
mkdir -p /workspace/output

# Set PATH to include cargo - try multiple locations
export PATH="/opt/rust/bin:/root/.cargo/bin:/usr/local/bin:$PATH"

# Cargo availability is classified by ONE seam, below. There is deliberately no
# probe here: the PATH export above already carries every location cargo could
# be at, so any block testing those same paths is unreachable by construction.

# Create a Rust project for generation
cd /tmp/rust_gen

# Set cargo directories to writable locations
export CARGO_HOME="/tmp/.cargo"
mkdir -p "$CARGO_HOME"

# THE ONE SEAM THAT CLASSIFIES A MISSING OR BROKEN CARGO, and the only one.
# Reached whether cargo is absent, unexecutable, or present-but-failing, so
# there is no path on which this leg reports success without having run.
#
# It replaced two `exit 0` branches — "cargo not installed" and "permission
# issues with cargo" — which BOTH REPORTED SUCCESS HAVING GENERATED NOTHING:
# exit 0 propagates out of the docker run, run_generation prints "rust
# generation completed successfully", rust never enters FAILED_LANGS, and the
# summary then counts the TRACKED leftovers in output/rust as evidence the leg
# ran. A missing tool is a hard failure with an install hint, never a skip.
#
# THE HINT NAMES REBUILD_IMAGE, NOT uber.sh. This leg runs in the DERIVED image
# built from Dockerfile, and build_image() is skipped whenever that image merely
# EXISTS — so rebuilding only the base leaves the identical broken derived image
# in play. Rust is installed system-wide at /opt/rust by Dockerfile.base.
cargo --version &> /dev/null || {
    echo "ERROR: cargo is not usable in this image — the rust leg cannot run."
    echo "  Rust lives at /opt/rust in Dockerfile.base. To rebuild the image"
    echo "  this leg actually runs in:"
    echo "    REBUILD_IMAGE=true make generate"
    exit 1
}

cat > Cargo.toml << EOF
[package]
name = "proto-gen"
version = "0.1.0"
edition = "2021"

[dependencies]
prost = "0.13"

[build-dependencies]
prost-build = "0.13"
EOF

mkdir -p src
cat > build.rs << "EOF"
use std::io::Result;
use std::path::{Path, PathBuf};

fn main() -> Result<()> {
    let proto_files = find_protos(Path::new("/tmp/cleaned_proto"))?;

    // Ensure output directory exists and is writable
    std::fs::create_dir_all("/workspace/output")?;

    prost_build::Config::new()
        .out_dir("/workspace/output")
        .compile_protos(&proto_files, &["/tmp/cleaned_proto"])?;

    Ok(())
}

/// Recursively find all `.proto` files under `dir` (subdirectories included).
fn find_protos(dir: &Path) -> Result<Vec<PathBuf>> {
    let mut files = Vec::new();
    // read_dir yields OS/filesystem order, not sorted order. prost emits its
    // declarations in the order it receives the files, so an unsorted walk
    // makes output/rust/ser.rs a function of the directory layout on whatever
    // machine built it — hundreds of lines of pure reordering churn per run.
    // (No apostrophe above on purpose: this is inside a single-quoted bash -c
    // payload. See the note at the buf build step.)
    let mut entries: Vec<PathBuf> = std::fs::read_dir(dir)?
        .map(|e| e.map(|e| e.path()))
        .collect::<std::result::Result<Vec<_>, _>>()?;
    entries.sort();
    for path in entries {
        if path.is_dir() {
            files.extend(find_protos(&path)?);
        } else if path.extension().is_some_and(|e| e == "proto") {
            files.push(path);
        }
    }
    Ok(files)
}
EOF

cat > src/main.rs << "EOF"
fn main() {}
EOF

cargo build 2>&1 | tail -5
'

# Zig generation script
ZIG_SCRIPT='
set -e
mkdir -p /tmp/cleaned_proto

# Process all proto files including subdirectories
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/cleaned_proto/$dirname"
    awk -f /usr/local/bin/proto_cleanup.awk "$proto" > "/tmp/cleaned_proto/$relpath"
done

# Generate Zig bindings using protoc-gen-zig
PROTO_FILES=$(find /tmp/cleaned_proto -name "*.proto" -type f | sort)
protoc --plugin=protoc-gen-zig=/opt/zig-protobuf/zig-out/bin/protoc-gen-zig \
    -I/tmp/cleaned_proto \
    --zig_out=/workspace/output \
    $PROTO_FILES

# Verify files were generated
# `\( … \)` IS LOAD-BEARING: find binds -a tighter than -o, so the ungrouped
# form parses as `(-name "*.zig") OR (-name "*.pb.zig" AND -type f)` — the first
# alternative then matches DIRECTORIES, and a stray directory alone satisfies
# the assertion.
if [ -z "$(find /workspace/output \( -name "*.zig" -o -name "*.pb.zig" \) -type f 2>/dev/null)" ]; then
    echo "ERROR: No Zig files were generated!"
    exit 1
fi
echo "Zig generation successful, found $(find /workspace/output \( -name "*.zig" -o -name "*.pb.zig" \) -type f | wc -l) Zig files"
'

# Java generation script with buf.validate support
JAVA_SCRIPT='
set -e
mkdir -p /tmp/java_proto_buf

# Copy proto files and add validate import (excluding test directory)
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/java_proto_buf/$dirname"
    cp "$proto" "/tmp/java_proto_buf/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/java_proto_buf/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/java_proto_buf/

# Generate using standard protoc with the validate.proto available
PROTO_FILES=$(find /tmp/java_proto_buf -name "*.proto" -type f ! -path "*/buf/*" ! -path "*/test/*" | sort)
protoc -I/tmp/java_proto_buf \
    --java_out=/workspace/output \
    $PROTO_FILES

# Verify files were generated
if [ -z "$(find /workspace/output -name "*.java" -type f 2>/dev/null)" ]; then
    echo "ERROR: No Java files were generated!"
    exit 1
fi
echo "Generated $(find /workspace/output -name "*.java" -type f | wc -l) .java files"
'

# REMOVED - Go validation is now in main GO_SCRIPT

# Java validation script removed - Java now uses direct generation with annotations

# C++ validation removed - not needed and has compatibility issues

# JSON descriptor generation script
JSON_DESCRIPTOR_SCRIPT='
set -e
mkdir -p /tmp/json_proto

# Copy proto files and add validate import (excluding test directory)
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/json_proto/$dirname"
    cp "$proto" "/tmp/json_proto/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/json_proto/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/json_proto/

cd /tmp/json_proto

# Check if buf is available, otherwise fall back to protoc
if command -v buf &> /dev/null; then
    echo "Using buf to generate JSON descriptors with validation annotations..."
    
    # Create buf.yaml if it doesn'\''t exist
    if [ ! -f buf.yaml ]; then
        cat > buf.yaml << "BUF_EOF"
version: v1
breaking:
  use:
    - FILE
lint:
  use:
    - DEFAULT
BUF_EOF
    fi
    
    # Generate JSON descriptors using buf build
    buf build . -o /workspace/output/descriptor-set.json --exclude-source-info

    # Binary FileDescriptorSet (buf.validate options + imports retained) for
    # prost-reflect / protovalidate consumers, for their test-time buf.validate
    # checks (validate_state/validate_cmd). NOTE: this whole block is the body of
    # a SINGLE-QUOTED bash -c payload, so a bare apostrophe here TERMINATES the
    # payload and the rest of the comment executes as shell. Write '\''  or, as
    # here, avoid the apostrophe. Lands beside
    # descriptor-set.json in output/json-descriptors/. --as-file-descriptor-set
    # emits a pure google.protobuf.FileDescriptorSet (not the buf Image
    # superset), which a prost-reflect DescriptorPool decodes.
    buf build . --as-file-descriptor-set -o /workspace/output/descriptor-set.binpb --exclude-source-info
    
    # Also generate individual file descriptors
    for proto in *.proto; do
        if [ -f "$proto" ]; then
            base_name="${proto%.proto}"
            buf build . --path "$proto" -o "/workspace/output/${base_name}.json" --exclude-source-info
        fi
    done
else
    echo "buf not found, using protoc with custom extensions support..."
    
    # Use protoc to generate FileDescriptorSet (binary format) with extensions
    protoc -I/tmp/json_proto \
        --descriptor_set_out=/tmp/descriptor-set.pb \
        --include_imports \
        --include_source_info \
        /tmp/json_proto/*.proto

    # Persist the binary FileDescriptorSet (buf.validate options + imports) for
    # prost-reflect / protovalidate consumers (parity with the buf branch above).
    cp /tmp/descriptor-set.pb /workspace/output/descriptor-set.binpb

    # Convert to JSON using Python with custom extensions support
    python3 << "PYTHON_EOF"
import json
import base64
from google.protobuf import descriptor_pb2
from google.protobuf.json_format import MessageToJson, MessageToDict
from google.protobuf import text_format

# Read the binary descriptor set
with open("/tmp/descriptor-set.pb", "rb") as f:
    descriptor_data = f.read()

# Parse it as a FileDescriptorSet
file_descriptor_set = descriptor_pb2.FileDescriptorSet()
file_descriptor_set.ParseFromString(descriptor_data)

# Convert to JSON with extensions preserved
# The including_default_value_fields and preserving_proto_field_name options
# help preserve more information, but custom extensions still need special handling
json_str = MessageToJson(
    file_descriptor_set, 
    indent=2,
    preserving_proto_field_name=True,
    including_default_value_fields=True,
    use_integers_for_enums=False
)

# Write JSON output
with open("/workspace/output/descriptor-set.json", "w") as f:
    f.write(json_str)

# Also convert to dict for processing individual files
descriptor_dict = MessageToDict(
    file_descriptor_set,
    preserving_proto_field_name=True,
    including_default_value_fields=True
)

# Save individual file descriptors as JSON
for file_descriptor in descriptor_dict.get("file", []):
    name = file_descriptor.get("name", "").replace(".proto", "")
    if name and not name.startswith("google/") and not name.startswith("buf/"):
        individual_desc = {
            "file": [file_descriptor]
        }
        # Get just the base filename
        base_name = name.split("/")[-1]
        output_path = f"/workspace/output/{base_name}.json"
        with open(output_path, "w") as f:
            json.dump(individual_desc, f, indent=2)

print("Generated JSON descriptors")
PYTHON_EOF
fi

echo "Generated $(find /workspace/output -name "*.json" -type f | wc -l) JSON files"
'

# TypeScript validated generation script with protovalidate-es
TYPESCRIPT_VALIDATED_SCRIPT='
set -e
mkdir -p /tmp/ts_proto_val

# Copy proto files and add validate import (excluding test directory)
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    dirname=$(dirname "$relpath")
    mkdir -p "/tmp/ts_proto_val/$dirname"
    cp "$proto" "/tmp/ts_proto_val/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/ts_proto_val/$relpath"
done

# Copy validate.proto from protovalidate
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/ts_proto_val/

# Generate TypeScript using @bufbuild/protoc-gen-es with validation
TS_PROTO_FILES=$(find /tmp/ts_proto_val -name "*.proto" -type f ! -path "*/buf/*" ! -path "*/test/*" | sort)
protoc -I/tmp/ts_proto_val \
    --plugin=/usr/local/lib/node_modules/@bufbuild/protoc-gen-es/bin/protoc-gen-es \
    --es_out=/workspace/output \
    --es_opt=target=ts \
    $TS_PROTO_FILES

# Create package.json for the generated output
cat > /workspace/output/package.json << "PKG_EOF"
{
  "name": "@lpportorino/jettison-protovalidate-es",
  "version": "1.0.0",
  "description": "Jettison protocol buffers with validation for TypeScript/JavaScript",
  "type": "module",
  "main": "index.js",
  "types": "index.d.ts",
  "files": ["**/*.js", "**/*.d.ts"],
  "dependencies": {
    "@bufbuild/protobuf": "^2.2.2",
    "@bufbuild/protovalidate": "^0.8.1"
  },
  "repository": {
    "type": "git",
    "url": "git+https://github.com/lpportorino/jettison_protovalidate_es.git"
  },
  "keywords": ["protobuf", "validation", "protovalidate", "typescript"],
  "author": "Jettison",
  "license": "MIT"
}
PKG_EOF

# Verify files were generated
# Grouped for the same reason as the zig leg above: ungrouped, `-type f` binds
# only to the second -name, so a directory matching the first satisfies it.
if [ -z "$(find /workspace/output \( -name "*_pb.js" -o -name "*_pb.ts" \) -type f 2>/dev/null)" ]; then
    echo "ERROR: No TypeScript files were generated!"
    exit 1
fi
echo "Generated $(find /workspace/output -name "*_pb.ts" -type f | wc -l) TypeScript files"
'

# Run all generations
FAILED_LANGS=()

for lang in "${LANGS[@]}"; do
    case $lang in
        c) script="$C_SCRIPT" ;;
        cpp) script="$CPP_SCRIPT" ;;
        go) script="$GO_SCRIPT" ;;
        kotlin) script="$KOTLIN_SCRIPT" ;;
        python) script="$PYTHON_SCRIPT" ;;
        typescript) script="$TYPESCRIPT_SCRIPT" ;;
        rust) script="$RUST_SCRIPT" ;;
        zig) script="$ZIG_SCRIPT" ;;
        java) script="$JAVA_SCRIPT" ;;
        json-descriptors) script="$JSON_DESCRIPTOR_SCRIPT" ;;
        typescript-validated) script="$TYPESCRIPT_VALIDATED_SCRIPT" ;;
    esac

    if ! run_generation "$lang" "$script"; then
        FAILED_LANGS+=("$lang")
    fi
done

# Permissions are now set inside the Docker container after each generation

# No separate validated generation needed - Go and Java now use validation by default

# Summary
echo
print_info "========== Generation Summary =========="
print_info "Output directory: $OUTPUT_BASE_DIR"

# Check what was generated
for lang in "${LANGS[@]}"; do
    count=$(find "$OUTPUT_BASE_DIR/$lang" -type f 2>/dev/null | wc -l)
    if [ $count -gt 0 ]; then
        print_info "$lang: $count files generated"
    else
        print_warning "$lang: No files generated"
    fi
done

print_info ""
print_info "Note: C++, Go, Kotlin, Java, and TypeScript-validated bindings include buf.validate support"

if [ ${#FAILED_LANGS[@]} -gt 0 ]; then
    print_error "Failed languages: ${FAILED_LANGS[*]}"
    exit 1
else
    print_info "All generations completed successfully!"
fi

# Optional: Clean up proto copy
# Only remove if we copied from a different location
if [ "$PROTO_SOURCE_DIR" != "./proto" ]; then
    rm -rf ./proto
fi