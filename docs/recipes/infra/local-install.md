# Google sign-in for local development

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Nobody has followed these steps. They were derived from
[instance-deploy](instance-deploy.md) and
[google-sign-in](google-sign-in.md), the composition was checked with
`crossplane render`, and Keycloak 26.0 was checked to substitute the
client id at import while leaving the vault expression alone.

## Problem

You want to sign in to a locally running Queenswood with Google, the way
a deployed instance does.

## Solution

### Prerequisites

- A running Queenswood installation, with `billingAccountId` and
  `access.platformAdmin` in its `environment.yml` — see
  [management-plane-install](management-plane-install.md).
- Write access to the private manifests repository, and a merge.
- `pass`, on the machine that runs the monolith.
- The capability each step names. Ours is a Google group; yours may differ.

```bash
# the installation code, e.g.
export QW_CODE=qw01
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

Start at step 4 where the installation already has a local project,
and at step 5 where the client exists too.

### 1. Render the project

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just queenswood-local-manifest
```

It names the project `prj-<code>-d-local-<suffix>` and writes
`<code>/local.yml`. Read it, then commit it at the top of the
installation's directory and merge.

> [!WARNING]
> Once `local.yml` is committed it may be the only record of a project
> id GCP has consumed. The recipe refuses to render over it.

### 2. Wait for the project

```bash
just crossplane-conditions "xqueenswoodlocal/$QW_CODE-d-local"
```

`Synced` and then `Ready` reach `True` within a few minutes.

### 3. Record the project as adoptable

Add `adopt` beside `projectId` in `<code>/local.yml`, spelled
`projects/<id>`, and merge that on its own.

### 4. Create the OAuth client

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

In the local project, in the console, as
[google-sign-in](google-sign-in.md) has it: the consent screen first,
named for local development, then a Web application client whose one
redirect URI is:

```
http://localhost:8090/realms/queenswood/broker/google/endpoint
```

Add every account that will sign in as a test user while the consent
screen is in testing mode.

### 5. Store the pair where the monolith reads it

**On the machine that runs the monolith.**

```bash
pass insert queenswood/local/dev/auth/clients/ids/google
pass insert queenswood/local/dev/auth/clients/google
```

The id first, then the secret, each at its prompt.

### 6. Check

```bash
just monolith-start
```

Once it is serving, in another terminal:

```bash
VITE_KEYCLOAK_IDP_HINT=google just console-start
```

Signing in at `http://localhost:5173` goes to Google and comes back
signed in.

## Failures

**`The OAuth client was not found`, from Google, naming
`REPLACE-ME-GOOGLE-CLIENT-ID`.** The id never reached the realm. It is
read from `pass` when the monolith starts and imported with the realm,
so an id stored afterwards needs a restart. A system started from the
REPL reads no `pass` at all: export `QW_LOCAL_GOOGLE_CLIENT_ID` in that
shell first.

**`401 invalid_client`, from Google.** The secret did not resolve. The
monolith logs a warning naming the `pass` entry it could not read, and
signs in only local principals.

**`redirect_uri_mismatch`, from Google.** The redirect URI on the client
differs from step 4's, character for character, including `http`.

**A project that stays `Ready: False` with a billing refusal.** The
plane's identity cannot link the installation's billing account. The
grant is made once by an administrator of the account — see
[management-plane-install](management-plane-install.md).

## Rules

**MUST:**

- Render the project with `just queenswood-local-manifest`, which mints
  the id once and refuses to render over a committed manifest.
- Commit `local.yml` at the top of the installation's directory. The
  installation's Application is not recursive.
- Add `adopt` beside `projectId` once the project exists, and merge it
  on its own.
- Create the client in the local project, never in an instance's. One
  client per environment, and local development is one.
- Store the id at `queenswood/local/dev/auth/clients/ids/google` and
  the secret at `queenswood/local/dev/auth/clients/google`, where `just
  monolith-start` and the dev profile read them.

**MUST NOT:**

- Commit a client id to this repository. The realm's placeholder is
  what tests and `just keycloak-up` sign in against.
- Put the secret in Secret Manager as well. Nothing on a developer's
  machine reads it there, and a second copy of a credential you can
  regenerate is one more to rotate.

**MAY:**

- Skip steps 1 to 3 where the installation's local project exists.
- Use `just keycloak-up` for username and password sign-in. It mounts
  no vault, so it does not sign in with Google.

## Discussion

A deployed instance signs in with a client in its own project. Local
development gets the same shape: a project that holds the client, a
right to create it, and the pair on the machine that needs it.

**Why a project of its own.** An instance's client redirects to the
instance's Keycloak, and a client shared with local development would
be revoked or rotated with it. The local project is the installation's
`d` tier with the label `local`, and holds nothing that runs:
`XQueenswoodLocal` composes the project and the `oauthconfig.editor`
binding for `platformAdmin`, and no network, cluster or database. It
withholds Delete from the project, because the client inside it is a
console act and cannot be rebuilt from a declaration.

**Why the pair lives in `pass`.** The secret reaches Keycloak through its
file vault, written at container start from the `pass` entry, so it
never enters the host environment or git. The id is not secret, but it
is an identifier of this installation, so it is kept beside the secret
rather than committed. The realm names it as
`${QW_LOCAL_GOOGLE_CLIENT_ID:REPLACE-ME-GOOGLE-CLIENT-ID}`, which
Keycloak substitutes at import. The dev profile passes the variable
into the container and falls back to the placeholder, because an empty
value would replace it with nothing; tests leave it unset and sign in
exactly as before.

**Why a restart and not the Admin API.** A deployed realm exists before
its client does, so its id arrives over the Admin API. The local
container imports a fresh realm every time it starts, so the next start
is the whole reconcile.

## References

- [google-sign-in](google-sign-in.md) — the consent screen, the client,
  and the failure each half produces.
- [instance-deploy](instance-deploy.md) — the same sequence for an
  environment of the bank.
- [ADR-0025](../../adr/0025-building-blocks-and-what-cannot-be-one.md) —
  why the client is a recipe and the project is a kind.
- [cloud-naming](../practices/cloud-naming.md) — the `d` tier and the
  project's name.
