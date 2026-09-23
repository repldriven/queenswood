# syntax=docker/dockerfile:1.25

# Multi-stage build for any Queenswood service uberjar.
# Pass PROJECT_NAME (e.g. api-service, cash-account-service)
# at build time:
#   docker buildx build \
#     --build-arg PROJECT_NAME=api-service \
#     -t ghcr.io/repldriven/api-service:0.0.0 \
#     -f infra/docker/service/Dockerfile \
#     --platform linux/amd64,linux/arm64 \
#     .

ARG PROJECT_NAME

# `--platform=$BUILDPLATFORM` pins the JVM build to the host arch.
# Java bytecode is platform-independent, so the resulting uberjar runs
# on either linux/amd64 or linux/arm64 runtime images without
# rebuilding. Avoids ~3× QEMU-emulation slowdown for the cross-arch
# build under multi-platform bake. The runtime stage stays on
# `$TARGETPLATFORM` because the JRE and libfdb_c.so are arch-specific.
# This stage must never reference PROJECT_NAME. A build arg declared in
# a stage becomes part of the cache key for everything after it, so
# naming it here would give every bake target a distinct key for
# work that is byte-identical — and they would each run it, serialised
# by the lock below. Keeping the arg out until the next stage is what
# makes this run exactly once.
FROM --platform=$BUILDPLATFORM clojure:temurin-25-tools-deps-bookworm AS deps
ARG BUILDARCH
WORKDIR /workspace

# Code-gen tooling for the prep step. Both versions match the local
# nix flake so generated output is identical to dev:
#  - protoc 25.8 — arch-aware Linux build from the upstream release
#  - protoc-gen-clojure 2.1.2 — protojure's plugin, distributed as a
#    `:;exec java -jar ...` self-executing jar, so it runs on any
#    arch via the temurin JDK already in this image
# `BUILDARCH` (not TARGETARCH) because this stage runs on the build
# host; protoc's binary needs to match the host, not the eventual
# image's target arch.
# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && PROTOC_ARCH="$(case "${BUILDARCH:-amd64}" in \
                     amd64|x86_64)  echo x86_64 ;; \
                     arm64|aarch64) echo aarch_64 ;; \
                     *) echo "unknown arch: ${BUILDARCH}" >&2; exit 1 ;; \
                   esac)" \
 && curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
      "https://github.com/protocolbuffers/protobuf/releases/download/v25.8/protoc-25.8-linux-${PROTOC_ARCH}.zip" \
      -o /tmp/protoc.zip \
 && unzip -q /tmp/protoc.zip -d /usr/local \
 && rm /tmp/protoc.zip \
 && curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
      "https://github.com/protojure/protoc-plugin/releases/download/v2.1.2/protoc-gen-clojure" \
      -o /usr/local/bin/protoc-gen-clojure \
 && chmod +x /usr/local/bin/protoc-gen-clojure

COPY . .
# Cache mounts for the Maven local repo and tools.deps git-libs cache.
# Contents are *not* baked into the image layer, which keeps the
# runtime stage clean. Seed them once from a host `~/.m2` with
# `just docker-warm-cache` — they are BuildKit volumes, unrelated to
# the host repo, so a fresh builder is otherwise cold.
#
# This step is the only writer, and every target shares one execution
# of it, so `sharing=locked` costs a single lock holder rather than
# one queue per target.
#
# Two jobs, in one layer so they share that lock:
#  - Code-gen (Lancaster Avro schemas, protobuf classes) must run from
#    the workspace root; the `:dev` alias with every brick is defined
#    in the top-level deps.edn, not in any project deps.edn. Mirrors
#    the local `just force-prep` recipe.
#  - Resolve every project's dependencies, so the parallel uberjar
#    steps below have reads only and their shared mount has no writer
#    to race. Both invocations are needed and neither substitutes for
#    the other: `clojure -P` resolves the project's own `:deps`, which
#    is what `uber` ends up compiling, while `-P -X:build` resolves
#    only the tools.build alias that runs it. Prefetching just the
#    alias leaves `create-basis` to fetch the real dependency tree at
#    uberjar time — which is a concurrent write, on a shared mount,
#    and it corrupts artifacts mid-download.
#
# The prep is retried. tools.deps expands the dependency tree
# concurrently, and two coordinates from one git repository -- mono's
# dev bundle and its test runner -- can have one thread validate a tag
# while the other is still cloning the repository, which reads as
# `has invalid tag` on a fresh cache. The clone is there by the second
# attempt. Same family as the Maven race the CI prep step draws.
RUN --mount=type=cache,id=queenswood-m2,target=/root/.m2,sharing=locked \
    --mount=type=cache,id=queenswood-gitlibs,target=/root/.gitlibs,sharing=locked \
    for attempt in 1 2 3; do \
      if clojure -X:deps prep :aliases '[:dev]' :force true; then break; fi; \
      if [ "$attempt" = 3 ]; then exit 1; fi; \
      echo "prep failed on attempt $attempt; retrying" >&2; sleep 5; \
    done \
 && for p in projects/*/; do \
      (cd "$p" && clojure -P && clojure -P -X:build) || exit 1; \
    done

FROM deps AS build
ARG PROJECT_NAME
WORKDIR /workspace/projects/${PROJECT_NAME}
# `sharing=shared`, not locked: this is the per-project step, so every
# bake target reaches it at once. The prefetch above leaves them with
# reads only, and Maven Resolver's parallel-write race needs a writer to
# bite. Locking here would serialise the uberjar builds behind one
# mutex — most of what bake's parallelism buys.
RUN --mount=type=cache,id=queenswood-m2,target=/root/.m2,sharing=shared \
    --mount=type=cache,id=queenswood-gitlibs,target=/root/.gitlibs,sharing=shared \
    clojure -X:build uber :snapshot true \
 && mv target/*.jar target/app.jar

FROM eclipse-temurin:25-jre-noble AS runtime
ARG PROJECT_NAME
ARG TARGETARCH
ARG FDB_VERSION=7.3.75

# hadolint ignore=DL3008
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates dnsutils \
 && rm -rf /var/lib/apt/lists/*

# libfdb_c is required by the FDB Java client at runtime; arch-aware
# download from the upstream release.
RUN ARCH="$(case "${TARGETARCH:-amd64}" in \
              amd64|x86_64) echo x86_64 ;; \
              arm64|aarch64) echo aarch64 ;; \
              *) echo "unknown arch: ${TARGETARCH}" >&2; exit 1 ;; \
            esac)" \
 && curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
      "https://github.com/apple/foundationdb/releases/download/${FDB_VERSION}/libfdb_c.${ARCH}.so" \
      -o /usr/lib/libfdb_c.so \
 && ln -sf /usr/lib/libfdb_c.so "/usr/lib/libfdb_c_${FDB_VERSION}.so"

ENV FDB_CLUSTER_FILE=/etc/fdb/fdb.cluster
ENV JAVA_TOOL_OPTIONS="-XX:+ExitOnOutOfMemoryError"

WORKDIR /app
COPY --from=build /workspace/projects/${PROJECT_NAME}/target/app.jar /app/app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar", "-c", "classpath:application.yml", "-p", "default"]
