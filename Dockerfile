FROM jettison-proto-generator-base:latest

# Create workspace directory
WORKDIR /workspace

# Proto files will be mounted as volumes at runtime

# Copy helper scripts
COPY scripts/proto_cleanup.awk /usr/local/bin/proto_cleanup.awk
RUN chmod +x /usr/local/bin/proto_cleanup.awk

# Copy generation script and make it executable
COPY generate-protos.sh /usr/local/bin/generate-protos.sh
RUN chmod +x /usr/local/bin/generate-protos.sh

ENTRYPOINT ["/bin/bash"]