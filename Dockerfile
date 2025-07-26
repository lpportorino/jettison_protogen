FROM ubuntu:24.04

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive
ENV PROTOC_VERSION=25.1
ENV GO_VERSION=1.21.13
ENV RUST_VERSION=1.83.0

# Install base dependencies
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    curl \
    git \
    wget \
    unzip \
    python3 \
    python3-pip \
    python3-venv \
    openjdk-21-jdk \
    maven \
    nodejs \
    npm \
    gawk \
    && rm -rf /var/lib/apt/lists/*

# Install Protocol Buffers compiler
RUN wget https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip \
    && unzip protoc-${PROTOC_VERSION}-linux-x86_64.zip -d /usr/local \
    && rm protoc-${PROTOC_VERSION}-linux-x86_64.zip

# Install Go
RUN wget https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz \
    && tar -C /usr/local -xzf go${GO_VERSION}.linux-amd64.tar.gz \
    && rm go${GO_VERSION}.linux-amd64.tar.gz
ENV PATH=/usr/local/go/bin:$PATH
ENV GOPATH=/go
ENV PATH=$GOPATH/bin:$PATH

# Install Rust
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain ${RUST_VERSION}
ENV PATH=/root/.cargo/bin:$PATH

# Install Python protobuf dependencies
RUN pip3 install --break-system-packages \
    protobuf \
    grpcio-tools \
    mypy-protobuf

# Install TypeScript/Node dependencies globally
RUN npm install -g \
    ts-proto \
    @bufbuild/protoc-gen-es \
    @bufbuild/protoc-gen-connect-es

# Install Go protobuf plugins
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest \
    && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Install protoc-gen-validate (supports Go and Java validation)
RUN go install github.com/envoyproxy/protoc-gen-validate@latest

# Clone protovalidate for validate.proto definitions
RUN git clone https://github.com/bufbuild/protovalidate.git /opt/protovalidate \
    && git clone https://github.com/envoyproxy/protoc-gen-validate.git /opt/protoc-gen-validate

# Create a script to add validate import to proto files
RUN echo '#!/bin/bash\n\
file="$1"\n\
if ! grep -q "import.*validate.proto" "$file"; then\n\
  sed -i "/^syntax.*=.*proto3/a\\\nimport \"buf/validate/validate.proto\";" "$file"\n\
fi' > /usr/local/bin/add-validate-import.sh && chmod +x /usr/local/bin/add-validate-import.sh

# Clone and build nanopb for C generation
RUN git clone https://github.com/nanopb/nanopb.git /opt/nanopb \
    && cd /opt/nanopb \
    && git checkout 0.4.9 \
    && cmake . \
    && make
ENV PATH=$PATH:/opt/nanopb/generator

# Rust protobuf dependencies will be handled in the generation script

# Java will use the already installed protoc-gen-validate from Go
# The Java protoc plugin is bundled with protoc itself

# Create workspace directory
WORKDIR /workspace

# Copy helper scripts
COPY scripts/proto_cleanup.awk /usr/local/bin/proto_cleanup.awk
RUN chmod +x /usr/local/bin/proto_cleanup.awk

# Copy generation script and make it executable
COPY generate-protos.sh /usr/local/bin/generate-protos.sh
RUN chmod +x /usr/local/bin/generate-protos.sh

# Copy proto files
COPY proto /workspace/proto

# Create a simple test to verify all tools are installed
RUN protoc --version \
    && go version \
    && cargo --version \
    && python3 --version \
    && node --version \
    && java --version \
    && protoc-gen-nanopb --version || echo "nanopb version check" \
    && which protoc-gen-go \
    && which protoc-gen-validate

ENTRYPOINT ["/bin/bash"]