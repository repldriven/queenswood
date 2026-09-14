// Placeholder so Vite dev returns 200 for this path. In production
// nginx synthesises this file from container env (see
// infra/docker/console/nginx.conf.template); locally the SPA
// falls back to the VITE_KEYCLOAK_* env vars `just console-start` sets.
window.__env = {};
