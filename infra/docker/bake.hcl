// Build every Queenswood service image in parallel from the
// shared parameterised Dockerfile. `docker buildx bake` reuses
// the heavy Clojure base layer across all targets.
//
//   TAG=dev docker buildx bake -f infra/docker/bake.hcl

variable "REGISTRY" { default = "ghcr.io/repldriven/queenswood" }
variable "TAG"      { default = "dev" }

// Provenance. The uberjar carries no usable version of its own: the
// build stage runs `uber :snapshot true` because the service's
// Dockerfile.dockerignore excludes `.git`, so `git-count-revs` has no
// repository to count and returns nil. The published tag is `latest`, so without these labels
// there is nothing on an image saying which commit produced it.
//
// REVISION and CREATED are supplied by the release workflow and left
// empty for a local `just docker-build-*`, where the answer would be
// "your working tree" rather than a commit. Empty ones are dropped
// below rather than emitted blank.
variable "REVISION" { default = "" }
variable "CREATED"  { default = "" }
variable "SOURCE"   { default = "https://github.com/repldriven/queenswood" }

// `org.opencontainers.image.source` does double duty on GHCR: it is
// the standard provenance key, and it is also what links a package to
// its repository so the package inherits the repo's visibility.
provenance_labels = merge(
  { "org.opencontainers.image.source" = SOURCE },
  REVISION != "" ? { "org.opencontainers.image.revision" = REVISION } : {},
  CREATED != "" ? { "org.opencontainers.image.created" = CREATED } : {},
)

services = [
  "migrator-service",
  "bootstrap-service",
  "api-service",
  "monolith-service",
  "financial-processors-service",
  "operational-processors-service",
  "exclusive-dispatchers-service",
  "external-adapters-service",
  "external-simulators-service",
]

group "default" {
  targets = concat(services, ["console"])
}

// Multi-platform builds are gated on the `MULTI_ARCH` variable so
// local `docker buildx bake` (the just docker-build-* recipes) stays
// fast and single-arch, while CI publishes both linux/amd64 and
// linux/arm64. Single-arch is selected by leaving the variable empty
// (the default) — `platforms = []` lets BuildKit fall back to the
// host platform.
variable "MULTI_ARCH" { default = "" }

platforms_default = MULTI_ARCH == "1" ? ["linux/amd64", "linux/arm64"] : []

target "service" {
  name       = svc
  matrix     = { svc = services }
  context    = "."
  dockerfile = "infra/docker/service/Dockerfile"
  args       = { PROJECT_NAME = svc }
  tags       = ["${REGISTRY}/${svc}:${TAG}"]
  labels     = merge(provenance_labels,
    { "org.opencontainers.image.title" = svc })
  platforms  = platforms_default
  output     = ["type=docker"]
}

// Human-identity SPA: sign-in + onboarding + dashboard. Node →
// nginx, with the SPA reading /env.js at runtime so a single image
// works under both kind (Keycloak port-forward) and GKE (Keycloak
// at https://keycloak.<env>.repldriven.com).
target "console" {
  context    = "."
  dockerfile = "infra/docker/console/Dockerfile"
  tags       = ["${REGISTRY}/console:${TAG}"]
  labels     = merge(provenance_labels,
    { "org.opencontainers.image.title" = "console" })
  platforms  = platforms_default
  output     = ["type=docker"]
}
