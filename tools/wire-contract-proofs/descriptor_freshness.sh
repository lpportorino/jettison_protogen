#!/usr/bin/env bash
# Rebuild the descriptor set from proto/ and diff it against the COMMITTED one.
#
# WHY: tools/wire_contract_check.py reads output/json-descriptors/descriptor-set.json
# and its own NOT_COVERED list opens with "Whether the descriptor set is FRESH vs
# proto/". Every assertion the wire-contract gate makes on a push or a PR is made
# against that committed file, so if it is stale the gate is asserting the doc
# against protos that are not the ones in the tree.
#
# Runs INSIDE the pinned toolchain container and reproduces the exact recipe in
# generate-protos.sh's JSON_DESCRIPTOR_SCRIPT. Writes ONLY to .fork-scratch/ --
# output/ is never touched, so nothing generated here can be mistaken for a
# committed artifact.
set -euo pipefail

mkdir -p /tmp/json_proto
find proto -name "*.proto" -type f -not -path "*/test/*" | while read -r proto; do
    relpath="${proto#proto/}"
    mkdir -p "/tmp/json_proto/$(dirname "$relpath")"
    cp "$proto" "/tmp/json_proto/$relpath"
    /usr/local/bin/add-validate-import.sh "/tmp/json_proto/$relpath"
done
cp -r /opt/protovalidate/proto/protovalidate/buf /tmp/json_proto/

cd /tmp/json_proto
cat > buf.yaml <<'BUF_EOF'
version: v1
breaking:
  use:
    - FILE
lint:
  use:
    - DEFAULT
BUF_EOF

# Bare, not piped: a pipeline reports the FILTER's status, so filtering here
# would let a failed build exit 0 under `set -e`.
buf build . -o /workspace/.fork-scratch/fresh-descriptor-set.json --exclude-source-info
echo "buf build: OK"
ls -l /workspace/.fork-scratch/fresh-descriptor-set.json
