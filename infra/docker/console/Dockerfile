# syntax=docker/dockerfile:1.25

# console: Svelte/Vite frontend for the human sign-in flow.
#  Stage 1 — build the static bundle with Node from the yarn 4
#  workspace at the repo root. The console base is one
#  workspace member; the shared design system (`@queenswood/ui`)
#  is another, hoisted by yarn so `import { Logo } from
#  "@queenswood/ui"` resolves through `node_modules/`.
#  Stage 2 — serve dist/ via nginx, proxying /v1, /oauth, and
#  /.well-known to bank-api so the SPA stays same-origin (no CORS).
#  nginx also synthesises /env.js from container env at startup
#  so the SPA can resolve its Keycloak URL without a rebuild per env.

# `--platform=$BUILDPLATFORM` pins the Node build to the host arch
# regardless of the requested target. Static output is arch-
# independent and we avoid running npm under QEMU for non-native
# arches.
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /repo

# Workspace manifests first for Docker layer caching: yarn install
# only re-runs when a package.json, the lockfile, or .yarnrc.yml
# changes. Every workspace member the root package.json lists must be
# present even though this image only builds console: yarn validates
# the whole graph, and one missing member is a lockfile `--immutable`
# refuses. The js CI job checks this list against the root's.
COPY package.json yarn.lock .yarnrc.yml ./
COPY bases/console/package.json bases/console/
COPY bases/demo-digital-bank-app/package.json bases/demo-digital-bank-app/
COPY components/ui/package.json components/ui/
RUN corepack enable && corepack yarn install --immutable

# Brick source. The repo layout is preserved here so source-relative
# imports resolve the same way they do at the repo root: Landing.svelte
# ?raw-imports markdown via `../../../../docs/...`, which only works
# because both `bases/console/` and `docs/` exist as siblings.
COPY bases/console/ bases/console/
COPY components/ui/ components/ui/
COPY docs/ docs/
RUN corepack yarn workspace console build

FROM nginxinc/nginx-unprivileged:1.31-alpine AS runtime
COPY infra/docker/console/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /repo/bases/console/dist/ /usr/share/nginx/html/

# nginx-unprivileged runs envsubst on /etc/nginx/templates/*.template
# at startup. API_UPSTREAM is the bank-api proxy target,
# KEYCLOAK_UPSTREAM is the in-cluster Keycloak Service that the
# `/keycloak/*` location reverse-proxies to so the SPA stays same-
# origin, and the four KEYCLOAK_* values get rendered into /env.js.
ENV NGINX_ENVSUBST_FILTER="^(API_UPSTREAM|KEYCLOAK_UPSTREAM|KEYCLOAK_URL|KEYCLOAK_REALM|KEYCLOAK_CLIENT_ID|KEYCLOAK_IDP_HINT)$"
ENV API_UPSTREAM="http://bank-api:8080"
ENV KEYCLOAK_UPSTREAM="http://queenswood-keycloak-keycloak-service:8080"
ENV KEYCLOAK_URL=""
ENV KEYCLOAK_REALM="queenswood"
ENV KEYCLOAK_CLIENT_ID="queenswood-console"
ENV KEYCLOAK_IDP_HINT=""

EXPOSE 8080
