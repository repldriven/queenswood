# Adding an instance to an installation

<!-- tessl-plugin: deployment -->

## Status

**Untested.** One instance exists, accreted across many changes rather
than created from nothing, so every step below has been performed and
the sequence has not.

## Problem

You want to add a Queenswood instance to an installation.

## Solution

### Prerequisites

- A running Queenswood installation — see
  [management-plane-install](management-plane-install.md).
- The installation's recovery project composed, where this instance is
  to keep backups.
- The plane's Argo identity and recovery project carrying
  `platform.repldriven.com/component`.
- The installation's domain stated in `environment.yml`, and its
  platform identity a verified owner of it — see
  [gcp-dns](gcp-dns.md) step 2.
- Write access to the private manifests repository, and a merge.
- Headroom on the plane.
- The capability each step names. Ours is a Google group; yours may differ.

```bash
# the installation code, e.g.
export QW_CODE=qw01
# the instance's env and label, e.g.
export QW_ENV=n QW_LABEL=dev
# the instance's private manifests repository, e.g.
export QW_INSTALLATIONS_REPO=../installations
```

### 1. Compose this environment's zone, and get it delegated

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

An environment answers on its own name, in a zone of its own, which
something above delegates to. That zone exists before the instance
does — see
[ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md).

```bash
just queenswood-zone-manifest $QW_ENV $QW_LABEL
```

The domain defaults to the label under the installation's. Commit it as
`<code>/<label>.zone.yml`, at the top of the installation's directory,
and merge. Then, once it has reconciled:

```bash
just queenswood-zone-nameservers $QW_ENV $QW_LABEL
```

Four names. Hand them to whoever holds the zone above — for our own
apex that is an NS record in `apex.yml` and `just dns-apex-apply`; for
a domain somebody else delegates, it is a request to them.

Confirm the name resolves before going on. Everything below assumes it
does, and the one that fails silently if it does not is the
certificate.

### 2. Render the instance unit

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just queenswood-instance-manifest
# or, for a spec that states what it would otherwise default:
QW_SPEC=full just queenswood-instance-manifest
```

> [!WARNING]
> Re-render as often as you like until the unit is committed: a plane
> reads it from git, so nothing was built and the minted project id means
> nothing. Once it is committed the file may be the only record of a
> project id GCP has consumed, and the recipe refuses rather than
> minting a second.

### 3. Read what it wrote

In the `QW_INSTALLATIONS_REPO`, check the domain: it must differ from
every other instance's, or both compose a record for the same name.

If you rendered with `QW_SPEC=full`, change any setting that should
differ for this instance.

### 4. Merge the instance, and wait for it

> [!WARNING]
> Commit and merge `<label>.unit.yml` and
> `units/<label>/instance.yml` ONLY, and NONE of the other files yet.

The merge is what starts the build.

```bash
just crossplane-conditions "xqueenswoodinstance/$QW_CODE-$QW_ENV-$QW_LABEL"
```

`Synced` reaches `True` within a minute or two and stays there.
`Ready` is `False` for around twenty minutes, then `True`. A `Synced`
that never arrives is the composite refusing to render at all;
`LastAsyncOperation` carries anything the cloud refused.

```bash
just crossplane-unready
```

Everything on the plane still building, this instance's resources
among them. A header line with nothing under it is the finished
answer.

### 4b. Record the project as adoptable

Once the project exists, add `adopt` beside its `projectId` in
`units/<label>/instance.yml`, spelled `projects/<id>`, and merge that on
its own.

upjet records a project's external name after its own create, so the
plane that made this one knows how to find it and nothing else does. A
plane that did not create it composes a `Project` with no external name,
tries to create one that is already there, and is answered `409
Requested entity already exists` for ever — which is what a rebuilt
plane does with every project the manifests leave unadoptable. Not
before the create, though: an external name set on a project that does
not exist yet makes the first observation fail and creation never
follows.

It changes nothing here. The managed resource already carries that
external name, so the patch matches what is live.

### 5. Create the OAuth client

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

In the new project, in the console, as
[google-sign-in](google-sign-in.md) has it: the consent screen first,
then a Web application client. No API creates one with a chosen
redirect URI. The redirect URI is
`https://keycloak.<domain>/realms/<realm>/broker/<alias>/endpoint`,
alias included, and the client is this environment's alone.

### 6. Write the secrets

**As the instance's own secrets admin**, not the installation's. Ours
is `grp-gcp-<code>-<env>-secrets-admin@` — join for this step, then
leave.

```bash
just queenswood-instance-google-secret
just queenswood-instance-keycloak-admin
just queenswood-recovery-backup-key
```

Each names the entry it wrote and the version it added.

### 7. Merge the Applications

**As the installation's platform viewer again.**

Put the client id from step 5 into the unit's `values.yml` as
`keycloak.googleClientId`, then commit and merge every remaining file
in the unit.

This is what installs the bank.

### 8. Check it serves

```bash
just argo-apps-status
```

Every Application for the instance `Synced` and `Healthy`, and the
console answering at `https://console.<domain>`.

## Failures

**An instance reporting healthy that nothing can reach over HTTPS.**
Its certificate is still pending, because the validation record a
`DNSAuthorization` emits has to resolve publicly before the certificate
issues, and it cannot until this environment's name is delegated to the
zone holding it. Everything else composes and reports green, so this
reads as a certificate fault rather than a missing NS record. Step 1 is
what prevents it, and confirming the name resolves is what proves it.

**A container that appears not to exist.** The write was attempted from
the installation's `secretsAdmin`, which binds on the management
project. A capability bound on one project writes nothing in another,
and the denial is reported as absence. The instance's own `access`
mapping is what grants it, so it has to be merged and reconciled first.

**An instance that composes green while Argo has no rights in it.**
The plane's Argo identity was not found, or has not been observed.
Check it carries `platform.repldriven.com/component: argo-identity` and
that `status.atProvider.member` is populated. The requirement resolves
to nothing rather than failing, so the Applications fail against the
new cluster alone and nothing else reports it.

**A Secret that syncs green and is empty.** The composite composed the
container and the operator synced it, both correctly, and no version
was ever written. It surfaces at whatever reads it rather than where it
happened.

**`401 invalid_client` on an instance that just came up.** Read this as
the secret before reading it as the build: a trailing newline on the
version, a vault expression that resolved to nothing, or a value stored
after the pod that reads it had started. Deleting the external-secrets
pod and restarting Keycloak is faster than waiting out the refresh
interval.

**A Cloud SQL create refused with `invalidOperation`.** The manifest
carried `state: down`. An already-stopped database cannot be created;
an instance is built up and stopped afterwards.

**An instance that will not come back up.** `state: up` is merged, the
unit's Application reports the new revision as synced, and the
composite still reads `down`. The Application that syncs the unit holds
both the `XQueenswoodInstance` and the workload Applications, and its
sync waits for those to report healthy — so the sync that applied
`down` then waited for workloads `down` had just removed, and has been
Running ever since. A new sync cannot start behind an operation still
in flight.

`just argo-apps-operation <app>` shows it: an operation whose revision
is the one that took the instance down, `Running`, with `retryCount`
unset because nothing is failing. Terminate it before removing the
queued one, and never the other way round —
[argocd-apps](argocd-apps.md) has the two steps and what removing
`.operation` first does. Posting `up` has always worked, so this only
appears on the way back from a `down`.

**The plane's own pods restarting while the instance builds.** The
instance's composites and Applications landed on a plane with no
headroom. The symptom is liveness kills rather than memory pressure,
because pods with no requests read as uncommitted to the scheduler.

## Rules

**MUST:**

- Add `adopt` beside the project's `projectId` once the project exists,
  and merge it. Without it no other plane can ever adopt the project,
  and the day one has to it is answered `409 Requested entity already
  exists`.

- Render the unit with `just queenswood-instance-manifest`, which mints
  the project id once and writes it into every file that carries it.
  Where one is written by hand instead, they have to agree: a wrong id
  in the external-secrets annotation is a service account nothing is
  bound to, not an error.
- Give the instance its own `access` mapping, and let it reconcile
  before writing any secret version.
- Put the unit declaration at the top of the installation's directory,
  never inside the unit's folder.
- Compose this environment's zone with `just queenswood-zone-manifest`
  and get it delegated before deploying the instance. State
  `ingress.domain` distinct from every other instance's, with
  `zone.name` and `zone.project` naming that zone rather than a shared
  one.
- Let an instance take its region from the installation's
  `environment.yml`. Setting `region`, `regionCode` or `zone` on the
  instance overrides it for that one, which is what putting a single
  instance elsewhere costs.
- Create the OAuth client in the console, in the instance's own
  project, one per environment.
- Merge the composite and the Applications separately, the composite
  first. Keycloak honours a bootstrap admin only while the master realm
  is absent, and nothing automatic holds that gap open — a folder with
  no Applications in it does.
- Write the Keycloak bootstrap admin with
  `just queenswood-instance-keycloak-admin` before the bank first
  starts, and name it in the unit's values as
  `keycloak.bootstrapAdmin.secretName`.
- Write the other versions with `just queenswood-instance-google-secret`
  and `just queenswood-recovery-backup-key`, and let each strip the
  trailing newline.
- Read the build back with `just crossplane-unready` and the workloads
  with `just argo-apps-status`, and reach the cluster with
  `just queenswood-instance-ctx`.

**MUST NOT:**

- Create an instance with `state: down`. Cloud SQL refuses to create an
  already-stopped instance, and the refusal is a 400.
- Share an `ingress.domain` between two instances. Both compose a
  record for the same name, and each reconciles it to its own address.
- Reuse or rename a project id. Neither is possible, and the second
  rebuilds the resource.
- Render a unit over one that has been committed. The project id is
  minted per call, and a committed unit may already be built, leaving
  the file as the only record of the one GCP consumed.
- Add a second version to the FDB backup key. A later key strands every
  backup written under the first.

**MAY:**

- Render minimally and lean on the XRD's defaults, which is what
  `QW_DEFAULTS=true` does. State them with `QW_DEFAULTS=false` for
  anything long-lived: the blocks it writes out are immutable or nearly
  so, and a default that moves under a live instance is refused rather
  than applied.
- Take the instance down once it is up, which is a one-word change.
- Stand an instance up with no `ingress` at all, which answers on no
  name and composes no certificate.

## Discussion

An instance is a unit: one declaration at the top of the installation's
directory and a folder of manifests beneath it. The declaration is what the
plane reads, the folder is what the declaration's own Application reads, and
the split is what keeps a unit's sync waves out of the installation's.

**The unit's two places.** The plane's `installation` Application syncs the
installation's directory with no `directory` block, which makes it
non-recursive on purpose: it applies the installation's own manifests and one
declaration per unit, all of which sit at the top, while a unit's contents are
installed by the Application that unit composes. An `include` cannot narrow it
instead — Argo compiles those globs with no separator, so `*` crosses `/` and
any pattern admitting the top level admits the whole tree, bringing the unit's
Applications back into the installation's sync carrying their waves.

**What the composite builds.** The project, the network, the cluster, the
identities, the database, the endpoint and the three empty Secret Manager
entries — then it registers the cluster with Argo, which is what makes the
unit's Applications able to target it by name. The merge is what starts it,
since the plane reads the revision its Application names rather than a working
tree, and nothing on the plane reacts to a file that is only local.

**Two merges.** Keycloak honours a bootstrap admin only while the master realm
is absent, and the OAuth client cannot be created before the project holding
it exists — so a secret has to be written after the project is built and
before the bank first starts. Nothing automatic can hold that gap open. Sync
waves order one sync; they cannot wait for somebody to visit a console.

Leaving the unit's Applications out of the first merge is what holds it open
instead. The folder the unit points at contains only the composite until step
6, so there is nothing to install and no clock running, and the second merge
starts the bank with every secret already in place. Doing it in one merge
would be a race against the build: the composite reporting `Ready` is what
releases the Applications, and an entry with no version does not fail an
`ExternalSecret` — it syncs green and empty, which is how an instance arrives
green everywhere and unable to sign anybody in. The unit's Application carries
`prune: false`, so adding files to a folder it already syncs takes nothing
away.

**`down` is not a starting state.** Down is a declared state and reconciling
toward it is ordinary, but it describes a database that exists and is stopped.
Cloud SQL will not create one already stopped, so the first reconcile of a new
instance has to build what later reconciles may stop.

**What the installation supplies.** The folder, the platform identity, the
recovery project and the name this installation answers under belong to the
installation, and the instance reaches them by naming them rather than by
referring to the plane's composite — its zone explicitly, because two
composites have to spell one name and only one of them makes it. What the
instance owns is its project and everything in it, which is why `down` stops
an environment rather than emptying one.

The recovery project may be absent. Where the plane composed none, this
instance finds none, composes neither a backups bucket nor a backup key entry,
and `just queenswood-recovery-backup-key` refuses.

The domain is not. Verification is done once for the installation, but the
delegation is per environment: this name is its own, in a zone of its own, and
something above has to point at it. That is step 1, and the only step whose
outcome is not this installation's to produce.

No step needs a cluster admin either. That capability is what `kubectl`
against the new cluster takes, and that is debugging rather than any part of
standing an instance up.

## References

- [outbound-email-install](outbound-email-install.md) — the mail server
  an instance's invitation emails go through, once it serves.
- [management-plane-install](management-plane-install.md) — building
  the plane this runs on, and the manifest it reads.
- [google-sign-in](google-sign-in.md) — the console acts and the Admin
  API call behind step 5.
- [external-secrets](external-secrets.md) — the declared container and
  the written version, and what each entry holds.
- [argocd-apps](argocd-apps.md) — what a parent Application may hold,
  and reading a sync that is not applying.
- [crossplane-debug](crossplane-debug.md) — starting from what is not
  ready.
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  code in every name, and who holds which capability.
- [ADR-0024](../../adr/0024-instances-are-their-own-composites.md) —
  why an instance is its own composite.
