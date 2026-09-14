# Google sign-in

<!-- tessl-plugin: deployment -->

## Problem

The realm ships an identity provider for Google with a placeholder
client id and secret, because a real pair belongs to an installation
rather than to a chart. Supplying the real one is manual twice over:
the client itself exists only in a console, and the realm that must
carry it cannot be re-imported.

Neither half is hard. Both are easy to get subtly wrong in ways that
surface after a user has already left the site.

## Solution

### Before you start

- The instance's project, and access to its console.
- `roles/oauthconfig.editor` on that project. The console names the
  half it is missing — `oauthconfig.verification.get` — and
  `roles/oauthconfig.viewer` carries that one and stops at the next:
  creating the client needs `clientauthconfig.clients.create` and
  `.createSecret`, which only the editor role has.
- The domain Keycloak is published on, and the realm name.
- The identity provider's alias as the realm defines it — `google`
  below, and the same string the console SPA sends as its
  `keycloakIdpHint`.
- An external-secrets operator on the instance cluster, and the Secret
  Manager entry the instance composite made for this client. Step 4
  puts the secret there; nothing else can reach it.

The right is granted through the installation's `platformAdmin`
capability, so it reaches a person through group membership rather than
by being clicked on. It sits there rather than with `platformViewer`
for a reason worth keeping: creating a client mints a credential that
speaks for this installation to Google, and a capability called viewer
should not.

No automation holds it, and none can. Google exposes no API for a web
client with a chosen redirect URI, so there is no identity to give the
job to — which is what makes this a standing right for a person rather
than something to grant an automation and take away.

### 1. Configure the consent screen

The client cannot be created until the project has one, and it asks for
two email addresses that are not the same kind of thing. The **user
support** address is shown to users as the contact for questions about
their consent, so it should be a group rather than a person, and Google
accepts only an address you own. The **contact** addresses are Google
notifying you — deprecations, policy changes, verification status — and
take any address. Neither wants a personal account: these are how an
installation finds out an API it depends on is going away, and an
account that leaves takes them with it.

Name the app for what a user is consenting to, and distinguish the
environment. Each instance is its own project with its own consent
screen, so without it there are eventually two apps called the same
thing and nothing on the screen to tell them apart.

**Internal or external is the consequential choice.** Internal admits
only accounts in the organisation, which makes an environment a
different sign-in path from the one being shipped. External admits any
Google account, which is what a product whose users bring their own
identity needs.

Verification is the usual reason people reach for internal, and for
these scopes it does not apply: Keycloak's Google provider requests
`openid profile email` unless the realm sets `defaultScope`, and
verification is required for sensitive and restricted scopes. An
external app requesting only these can be published without joining the
queue.

What external does cost is testing mode, which it starts in:

- Only accounts on the test-user list may sign in, up to a hundred.
- **Refresh tokens expire after seven days.** A session that should
  persist drops back to sign-in about weekly, which reads as a bug in
  the application rather than as a property of the consent screen, and
  is the single most confusing consequence of leaving an environment in
  testing mode.

Publishing to production ends both. Do it once the test-user list stops
being the point.

### 2. Create the OAuth client

There is no API for this. `gcloud iap oauth-clients` creates an
IAP-scoped client, sets its own redirect URI, and stopped functioning
in any case. A Web application client with a redirect URI of your
choosing is a console action.

Create it as a **Web application** under the instance project's
credentials page. The redirect URI is the part worth checking twice:

```
https://keycloak.<domain>/realms/<realm>/broker/<alias>/endpoint
```

`broker/<alias>/endpoint` is Keycloak's shape and the alias is the
realm's, not the provider's name in general. A wrong one is not
refused at setup: Google accepts the client happily and returns
`redirect_uri_mismatch` at the moment a user has left your site for
theirs, which is the worst place to discover a typo.

### 3. One client per environment

Local development counts as one, with a client in the installation's
local project and a `localhost` redirect URI — see
[local-install](local-install.md).

Not one shared across them. Revoking or rotating the client a
development environment uses must not touch the one real customers
sign in through, and a client still in Google's Testing mode is a
different risk object from a published one — different consent
behaviour, different token lifetimes, a different audience.

The cost of one each is a console visit. The cost of sharing is
discovering the difference during an incident.

### 4. Put the pair where the realm can reach it

The realm was imported with a placeholder and cannot be re-imported —
Keycloak creates realms and never overwrites them, so a chart change
never reaches a realm that exists. Both values therefore arrive against
the running realm, and by different routes.

**The id goes in over the Admin API**, on this endpoint:

```
PUT /admin/realms/<realm>/identity-provider/instances/<alias>
```

That call is the realm-import Job's rather than yours. Set
`keycloak.googleClientId` in the installation's values and the Job
reconciles it — and carries it in its own name, so a changed id
produces a Job that runs rather than a completed one nothing
re-applies.

**The secret is never sent there at all.** What is stored on the
identity provider is a vault expression —
`${vault.<alias>-client-secret}` — which the committed realm already
carries and the Job restores from that definition rather than writing
back what it read: the Admin API returns a configured secret masked, so
a read-modify-write replaces the expression with asterisks and leaves a
realm that looks entirely correct.

The secret itself is written by hand into one Secret Manager entry,
`sec-<code>-<env>-<label>-google-oauth`, which the instance composite
creates as a container and never fills:

```
just queenswood-instance-google-secret
```

It names the entry and its project from the instance, prompts, does not
echo, and never puts the value on a command line.
An `ExternalSecret` on the instance cluster then materialises it into
the vault Keycloak mounts, under the filename that vault expects:
`<realm>_<key>`, so `${vault.google-client-secret}` in realm
`queenswood` reads `queenswood_google-client-secret`. Rename either
half and the expression stays unresolved, which presents as Google
refusing the client rather than as anything about a mount.

Nothing automates the writing, and nothing should — the secret is
issued by Google to whoever created the client, which is the console
act above. What the entry buys is that the value survives a cluster
rebuild and is auditable where it lives.

Keycloak reads that mount at startup, so a secret stored after the pod
started is not seen until it restarts. A fresh build is ordered so that
it does not arise: the operator and the store install ahead of the bank
in earlier sync waves.

### 5. Check

Sign in. A failure is legible if you know which half produced it:

- **`redirect_uri_mismatch`, from Google** — the redirect URI on the
  client does not match what Keycloak sent. Compare it against the
  form above, character for character, including the scheme.
- **`401 invalid_client`, from Google** — the id or the secret is
  wrong, or the secret never resolved and Keycloak sent the literal
  vault expression. A trailing newline is the version of "wrong" worth
  knowing about: Secret Manager stores the bytes it is given and the
  vault reads the file whole, so `echo secret | gcloud …` sends the
  newline as part of the credential and fails here rather than
  anywhere that mentions one. On a restored or rebuilt environment
  this is indistinguishable from the restore itself having failed,
  which is what makes it worth ruling out first.
- **`Invalid parameter: redirect_uri`, from Keycloak** — nothing to do
  with Google. That is the console client's own redirect list, which
  the realm import reconciles; see
  [deployment](deployment.md).

## Rules

**MUST:**

- Configure the consent screen before the client. The client cannot be
  created without one.
- Create the OAuth client as a Web application, by hand, in the
  console. No API creates one with a chosen redirect URI.
- Match the redirect URI to
  `https://keycloak.<domain>/realms/<realm>/broker/<alias>/endpoint`
  exactly, alias included.
- Create one client per environment.
- Grant `roles/oauthconfig.editor` through `platformAdmin`, not
  `platformViewer`. Creating a client mints a credential.
- Put the id in through the installation's `keycloak.googleClientId`.
  A realm that exists keeps the placeholder it was imported with,
  whatever the chart's committed definition says, so the value has to
  reach it over the Admin API — and the Job that makes that call has to
  carry the id in its name, or a changed id leaves the same completed
  Job and nothing applies it.
- Leave a vault expression stored as the secret, never the secret
  itself, and restore it from the committed definition rather than from
  what the Admin API returned — a configured secret comes back masked.
- Write the secret into `sec-<code>-<env>-<label>-google-oauth` by
  hand, with no trailing newline, and name the vault key
  `<realm>_<key>` so the realm's expression resolves.
- Restart Keycloak where a secret is stored after the pod started.

**MUST NOT:**

- Share one OAuth client across environments.
- Expect a chart change or a re-import to reach the placeholder.
- Read `401 invalid_client` on a rebuilt environment as evidence the
  rebuild failed. It is the likelier cause and the wrong conclusion.

**SHOULD:**

- Choose external where users bring their own identity, and read the
  verification warning against the scopes actually requested rather
  than as written: `openid profile email` needs none.
- Publish out of testing mode once the test-user list stops being the
  point, or refresh tokens keep expiring after seven days and it reads
  as an application fault.
- Name the app for the environment as well as the product. Each
  instance has its own consent screen.
- Use a group for the user support address, and never a personal
  account for either address.

## References

- [organisation-foundation](organisation-foundation.md) — the other console
  work with no
  API behind it.
- [fdb-recovery](fdb-recovery.md) — why a realm keeps what it was
  first imported with, and what that costs on a restore.
- [deployment](deployment.md) — the console client's own redirect
  list, which is reconciled rather than manual.
- [argocd-apps](argocd-apps.md) — why an operator's CRDs need server-side apply,
  and how the waves that order this are read.
- [external-secrets](external-secrets.md) — the general shape this is
  one case of, and where a value must not go on the way in.
