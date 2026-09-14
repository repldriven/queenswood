// Keycloak Authorization Code + PKCE wrapper. The realm is
// configured via Vite env vars so the same bundle works against a
// local dev Keycloak (kubectl port-forward) and a real one published
// at https://keycloak.<env>.repldriven.com.
//
// We expose three thunks the rest of the SPA depends on:
//   ensure_session()   – idempotent init; resolves once on every page
//                        load with the current auth state attached.
//   sign_in(provider)  – kc.login({idpHint: provider}); the browser
//                        navigates to Keycloak and never returns from
//                        this call. Pass 'google' / 'github' to skip
//                        the Keycloak chooser; pass null to land on
//                        Keycloak's own username/password form.
//   sign_out()         – kc.logout(); same browser-navigation contract.
//   fresh_token()      – returns a current access token, refreshing
//                        if it's within 30s of expiry. Used by api.mjs
//                        as the source of truth for the bearer header.
import Keycloak from "keycloak-js";

const env = (typeof window !== "undefined" && window.__env) || {};
const url = env.keycloakUrl || import.meta.env.VITE_KEYCLOAK_URL;
const realm =
  env.keycloakRealm ||
  import.meta.env.VITE_KEYCLOAK_REALM ||
  "queenswood";
const client_id =
  env.keycloakClientId ||
  import.meta.env.VITE_KEYCLOAK_CLIENT_ID ||
  "queenswood-console";
// Optional IdP hint. When set (e.g. "google"), kc.login() skips
// Keycloak's own login form and redirects straight to the federated
// provider. Leave empty for local dev so the Keycloak login form
// renders — username/password against the seeded `dev` user works
// there but a Google round-trip needs real OAuth credentials.
const idp_hint =
  env.keycloakIdpHint || import.meta.env.VITE_KEYCLOAK_IDP_HINT || null;

// Without a Keycloak URL there's nothing to authenticate against,
// and instantiating keycloak-js anyway would either no-op silently
// or top-level-redirect to `undefined/realms/.../auth` and pin the
// browser in a redirect loop. We short-circuit instead — the SPA
// falls into the sign-in screen and clicking the button surfaces
// a clear console error.
const kc = url ? new Keycloak({ url, realm, clientId: client_id }) : null;

let init_promise = null;

export function ensure_session() {
  if (!init_promise) {
    if (!kc) {
      console.warn(
        "console: Keycloak URL not configured. Start with " +
        "`just console-start`, or set window.__env.keycloakUrl in " +
        "/env.js. The sign-in button is inert until this is fixed.",
      );
      init_promise = Promise.resolve({
        authenticated: false,
        token: null,
        claims: null,
      });
      return init_promise;
    }
    init_promise = kc
      .init({
        onLoad: "check-sso",
        pkceMethod: "S256",
        // The silent-check iframe needs a static asset on the same
        // origin. /silent-check-sso.html lives in public/ so the
        // iframe path works under both Vite dev and nginx prod.
        silentCheckSsoRedirectUri:
          window.location.origin + "/silent-check-sso.html",
        checkLoginIframe: false,
      })
      .then(() => ({
        authenticated: !!kc.authenticated,
        token: kc.token ?? null,
        claims: kc.tokenParsed ?? null,
      }))
      .catch((err) => {
        console.error("console: Keycloak init failed", err);
        return { authenticated: false, token: null, claims: null };
      });
  }
  return init_promise;
}

export function sign_in(provider) {
  if (!kc) {
    console.error(
      "console: cannot sign in — Keycloak URL not configured.",
    );
    return;
  }
  // Explicit provider from the UI wins; fall back to the env hint
  // (still useful for prod where you might force everyone via Google).
  // null/undefined → no idpHint → Keycloak shows its own login form.
  const hint = provider ?? idp_hint;
  return kc.login(hint ? { idpHint: hint } : {});
}

export function sign_out() {
  if (!kc) return;
  return kc.logout({ redirectUri: window.location.origin });
}

export async function fresh_token() {
  if (!kc) return null;
  // 30s safety margin — short-lived tokens (Keycloak's default is
  // 5 minutes) refresh transparently long before the api gets a 401.
  await kc.updateToken(30);
  return kc.token;
}

export function token_claims() {
  return kc?.tokenParsed ?? null;
}
