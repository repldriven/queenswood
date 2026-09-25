# Argo CD against a private GitHub repository

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Derived from the App this installation runs on and the
`ExternalSecret` that reads it, rather than from following the steps to
build one.

## Problem

You need Argo CD to reconcile your installation's configuration from a
private GitHub repository, and GitHub offers no credential-free way to
read one.

## Solution

A GitHub App, created once by an organisation owner. Three values come
out of it, all three go into one secret-store entry, and the
external-secrets operator places them on the cluster as the Secret Argo
reads.

### Prerequisites

- A management plane running in the installation's folder.
- Step 1 — write access to the manifests repository.
- Steps 3 and 4 — an owner of the GitHub organisation.
- The capability each step names. Ours is a Google group; yours may differ.

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
```

### 1. Name the repository in the manifest

One field, in the installation's own manifest — see
[plane-install](plane-install.md) for where that
file lives:

```yaml
spec:
  management:
    manifestRepoURL: https://github.com/<org>/<manifests-repo>
```

**Merge before going further.**

### 2. Wait for the plane to compose the container

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
kubectl --context "$QW_CODE-mgmt" -n crossplane-system \
  get secret.secretmanager.gcp.m.upbound.io "sec-$QW_CODE-c-github-app"
```

`SYNCED` and `READY` are both `True`.

Steps 3 and 4 are GitHub's web UI and can be done while you wait.

### 3. Create the App

In GitHub's web UI:

1. Organisation settings, then Developer settings, then GitHub Apps,
   then New GitHub App.
2. Name it. App names are globally unique across GitHub — e.g.
   `<org>-<code>-argocd-reader`.
3. Homepage URL is required and unused. The repository's URL will do.
4. Untick Webhook Active. Nothing calls back.
5. Repository permissions: Contents, read-only, and nothing else.
   Metadata read-only is mandatory and selects itself.
6. Only on this account.
7. Create it. The App ID is on the page that follows.
8. Generate a private key. A `.pem` downloads, and it is shown once.

```bash
export APP_ID=<app-id>
export PEM=~/Downloads/<app-name>.<date>.private-key.pem
```

### 4. Install it on the repository

Still in GitHub's web UI:

1. Install App, then Only select repositories, then the repository Argo
   reads.
2. The installation's settings URL ends in the Installation ID:
   `.../settings/installations/<id>`.

```bash
export INSTALL_ID=<installation-id>
```

### 5. Store all three values together

**As the installation's secrets admin.** Ours is
`grp-gcp-<code>-secrets-admin@` — join for this step, then leave.

```bash
just argo-github-app-secret "$PEM" "$APP_ID" "$INSTALL_ID"
```

> [!WARNING]
> Delete `$PEM` once the entry holds it. It is a credential for the
> organisation's repositories sitting in a downloads folder, and
> nothing else needs it again — a rotation generates a new one.

### 6. Check Argo has it

**As the installation's platform viewer again.**

```bash
kubectl --context "$QW_CODE-mgmt" -n argocd get externalsecret \
  installations-repo
```

`STATUS` is `SecretSynced`, `READY` is `True`. Then the repository
itself:

```bash
kubectl --context "$QW_CODE-mgmt" -n argocd get application installation
```

`SYNC STATUS` is `Synced`, `HEALTH STATUS` is `Healthy`.

### Rotating the key

Generate the new key, run step 5 again with it, confirm step 6, and
only then delete the old key from the App's settings in GitHub.

## Failures

**A container reported missing, on a plane that composed it.** `secret`
on its own is Kubernetes' own kind, and `kubectl` reports the managed
one as not found rather than saying it looked somewhere else. Spell it
`secret.secretmanager.gcp.m.upbound.io`. If it is genuinely absent,
the composite has not reconciled the merge yet.

**A repository reported unreachable, on a credential you just wrote.**
The entry is declared by the composite and filled by a person, and each
half succeeds without the other: the container exists, the operator
looks, and there is no version to read. The `ExternalSecret` says so —
its status names the entry it could not fetch, where Argo names only
the repository it could not reach. Write the version as in step 5, then
delete the `external-secrets` controller's pod: the refresh interval is
an hour, and nothing looks again until it elapses.

**A failure naming authorisation, on an App that authenticates.** The
App exists, holds a valid key, and was never installed on the
repository, because installation is a separate act from creation. Go to
Install App, then Only select repositories.

**An App that cannot see a repository it is installed on.** Argo
matches a repository to its credentials by URL. Both URLs derive from
`manifestRepoURL`, so they cannot disagree on their own — a mismatch,
down to a `.git` suffix or a trailing slash, means one of them was set
by hand.

**Everything correct, and Argo stops reading up to an hour later.** The
old key was deleted before the store held the new one. The installation
token Argo is using outlives the key it was minted from, so the failure
appears at the next refresh rather than at the rotation, by which time
the rotation no longer looks like the cause.

## Rules

**MUST:**

- Use a GitHub App, not a deploy key or a personal access token.
- Grant Contents read-only, and install the App only on the
  repositories Argo reads.
- Install the App on the repository. Creating it grants nothing.
- Merge `manifestRepoURL` before writing the credential. Nothing
  composes a container to write to without it.
- Spell the kind as `secret.secretmanager.gcp.m.upbound.io`. The short
  name resolves to Kubernetes' own Secret and reports the object as not
  found.
- Store the App ID, the Installation ID and the private key together,
  in `sec-<code>-c-github-app`, with `just argo-github-app-secret`. It
  reads the key with `--rawfile`, so it never reaches a command line.
- Let the chart's `ExternalSecret` place the Secret, and keep the key
  in the secret store.
- Add a new key before deleting the one it replaces.
- Delete the `.pem` once the entry holds it.
- Join `secretsAdmin` for the write and leave again. Everything else
  here is a viewer's.

**MUST NOT:**

- Commit the `.pem`, or pass it on a command line.
- Give the App webhook access, or any write permission.
- Set the Application's `repoURL` or the Secret's `url` by hand. Both
  derive from one field, which is what keeps them equal.
- Read a repository reported unreachable as a wrong credential before
  checking that the entry holds a version at all.
- Read the Secret to check the credential arrived. The
  `ExternalSecret`'s status carries the same answer and needs no right
  to the value.

**MAY:**

- Use one App for several repositories in the same organisation, where
  the same reader should reach all of them.
- Leave a public source repository with no credential. Only the private
  one needs any of this.

## Discussion

We keep an installation's configuration in a private repository, and
give its plane a GitHub App to read it with: created by hand once,
stored whole in a single Secret Manager entry, and placed on the
cluster by the external-secrets operator rather than by anybody.

**Why the configuration is private.** It carries no secrets. It
carries identifiers: a folder id, an organisation id, a project's
random suffix, a domain. None of those is a credential, which is
exactly why they get written down without anybody feeling they have
done anything — and a full set of realised ones is what somebody
pretexting a support call wants. That repository is where the real ones
belong, because that is what it is for, and having somewhere for them
is what lets everything else say `<folder-id>`. See
[system-identifiers](../practices/system-identifiers.md).

**Why an App.** It belongs to the organisation instead of to a person,
appears in the organisation's installed applications with an audit
trail, is revoked by uninstalling it, and reaches GitHub over HTTPS
where a deploy key needs SSH egress. A personal access token is worse
than either, since it carries whatever else its owner can reach and
dies when they leave.

**Created and installed are two acts.** GitHub has no API that creates
an App, so that half is console work by an organisation owner and
cannot be automated away. Creating one then grants it nothing: an App
is a principal, and installing it on a repository is what gives that
principal access — which is why an App that authenticates while
reaching nothing is an ordinary state rather than a broken one.

**What carries the value.** The composite composes the Secret Manager
entry and never its contents; a person writes the version once; and
`infra/helm/management-plane-config/templates/secret-store.yaml`
renders the `ExternalSecret` that materialises it on the cluster as the
Secret Argo reads. All three values sit in that one entry so the
identifiers travel with the key rather than through a second channel,
and the `ExternalSecret`'s status is the whole check — it says what the
Secret says, without the right to read a credential.

**One field, two consumers.** `manifestRepoURL` is patched into the
management plane's chart values, and the chart renders it twice: as the
`url` on the Secret that carries the credential, and as the `repoURL`
on `installation`, the Application that reads it. Argo matches the two by
string, so deriving both from one field is what makes the match hold —
and why the composite composes no credential machinery at all where
the field is absent. An installation applied only from a boot plane
reads its manifest from a checkout and has nothing to authenticate to.

**What the key authenticates.** Not each request. Argo mints an
installation token from the key, valid an hour, and refreshes it
itself, and an App may hold two keys at once — which is why the stored
key can be replaced under a running Argo without interrupting anything,
and why replacing it in the wrong order is invisible until the token it
is still using expires.

The path the value takes — manifest to Secret Manager, Secret Manager
to the cluster through the operator, cluster to Argo — is drawn in the
infrastructure diagram, alongside everything else the management
project holds.

## References

- [argocd-apps](argocd-apps.md) — Applications, sync waves, and what a
  parent may hold.
- [plane-install](plane-install.md) — the private
  repository this reads for Queenswood, and the manifest that names it.
- [external-secrets](external-secrets.md) — how the key gets into that
  store, and out of it onto a cluster.
- [system-identifiers](../practices/system-identifiers.md) — what counts as an
  identifier, and why the real ones live in that repository and nowhere
  else.
- [diagrams](../../diagrams/README.md) — the infrastructure diagram,
  and what its colours mean.
