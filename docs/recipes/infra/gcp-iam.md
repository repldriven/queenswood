# GCP IAM for automation

<!-- tessl-plugin: deployment -->

## Problem

An automation identity works until the thing that created it goes away,
because most of what it could do was never granted to it.

## Solution

Grant explicitly, at the narrowest scope that works, and audit before
discarding whatever built the project.

### Rights held by accident

Whoever creates a project becomes its owner. A seed identity
therefore holds every permission on what it built, none of it declared
— so a composition works under it and fails under the identity that
inherits it.

Enumerate before handing over: for each resource, would the inheriting
identity alone have the rights to create it? Found this way rather than
by failing: `iam.serviceAccounts.actAs` on a node identity,
`iam.serviceAccountAdmin`, `resourcemanager.projects.setIamPolicy`,
`serviceusage.services.enable`.

Two are circular. The binding that repairs a missing
`projects.setIamPolicy` is itself a project IAM binding, so an identity
that lacks it cannot grant it back.

### Workload Identity is two halves

A GCP binding of `roles/iam.workloadIdentityUser` for
`serviceAccount:<project>.svc.id.goog[<namespace>/<ksa>]`, **and** the
annotation `iam.gke.io/gcp-service-account` on that Kubernetes service
account. Without the second, a token minted for it is exchangeable for
nothing.

For Crossplane's providers the service account name must be pinned by a
`DeploymentRuntimeConfig`; left generated, the binding matches nothing.

### Node identities

Never the default compute service account. It is shared by everything
in the project that chose none, and holds whatever the organisation's
policy on automatic grants leaves it holding — nothing where
`iam.automaticIamGrantsForDefaultServiceAccounts` is enforced, Editor
where it is not. A node pool requests `cloud-platform` scopes, so
whatever it holds reaches every workload through the metadata server.

Grant a dedicated account `roles/container.defaultNodeServiceAccount`,
which is narrower than the four-role list older guidance gives and
carries `autoscaling.sites.writeMetrics`, which none of them do.

Attaching a service account to anything requires
`iam.serviceAccounts.actAs` on it — the default compute service account
included, whether or not anything of yours ever runs as it. Creating a
cluster creates a default node pool first, and that pool runs as this
account whatever `removeDefaultNodePool` does to it a moment later. So
an identity that creates clusters needs `actAs` on it, and an identity
that created the project already has it by being owner there. The
refusal reads *the user does not have access to service account
`<project-number>-compute@…`*, and lands in the managed resource's
`LastAsyncOperation` while the composite above goes on reporting
`Synced`.

### Roles and scopes

A role has resource types it may be granted on, and that set is not the
set of resources the feature acts on. `roles/orgpolicy.policyAdmin` is
organisation-only, though the policy it sets is applied per project.
The refusal is a 400 declining the scope, not a permission the caller
lacks, so no upstream grant fixes it.

Reading them is scoped the same way, and only `securityReviewer` holds
it — not `platformViewer`, which reads everything else — so a person
outside that capability cannot list the constraints binding their own
installation. See
[ADR-0023](../../adr/0023-installation-naming-and-access.md). Read them
before assuming a constraint is on: the management plane's composition
says a default network is prevented by one, and it was not.

`roles/container.viewer` does not carry `container.pods.getLogs`, and
the only predefined role that does also grants exec and every write. A
project custom role with the one permission is the answer where an
organisation role is not ours to define. Custom role ids take letters,
numbers, underscores and periods — never hyphens.

### Deleting is not symmetrical

Liens are a project mechanism. A folder cannot carry one, and is
protected instead by nobody holding `resourcemanager.folders.delete` —
which Organization Administrator does not carry, so it needs a group of
its own.

A folder's display name must be unique among its siblings, so a second
installation is refused rather than silently duplicated.

### Credentials

`gcloud auth login` does not refresh application-default credentials.
Impersonation then fails minutes later, inside whatever is using it,
as `invalid_rapt` or a denied `getAccessToken`.

The two are further apart than that suggests: `gcloud` does not read
application-default credentials at all. It authenticates with its own
login, so pointing ADC at an identity leaves Crossplane impersonating
it and every `gcloud` command still running as you. Reaching it from
the command line is `--impersonate-service-account`, which needs
`iam.serviceAccounts.getAccessToken` on that identity — held by a
group, so being in the group is what grants it and no amount of
re-running an ADC login will.

Console recommender insights are worth checking rather than dismissing,
and are generated on a schedule — they lag a fix by up to a day.

### Two generations of constraint id

An organisation policy constraint may have a legacy id and a newer
*managed* one carrying a `.managed.` infix, and a new organisation is
given the managed set enforced by default. Asking the legacy name about
an organisation that enforces the managed one answers "not set" while
the protection is fully in place —
`iam.disableServiceAccountKeyCreation` reads unset here and
`iam.managed.disableServiceAccountKeyCreation` reads enforced. Check
both spellings, and read what is *set* at the organisation before
trusting a constraint-by-constraint report to have asked the right
question.

The v1 `gcloud resource-manager org-policies` commands read both. The
v2 `gcloud org-policies` commands need the Organization Policy API
enabled on a quota project, which an impersonated seed identity
does not have.

### An API is checked against the caller's project too

Enabling an API in the project a resource lives in is necessary and not
sufficient. GCP also checks it against the project the *calling
identity* belongs to, so a control plane that composes a kind on
someone else's behalf needs that API enabled where the plane's identity
lives, not only where the resource lands.

The 403 says so, and it is easy to misread: it names a project number
rather than an id, and the number is the caller's. Two consecutive
failures on one resource can name two different projects — the target
while its own enablement propagates, then the caller — and they look
identical apart from the digits. Resolve the number before believing
the message, from `status.atProvider.number` on the `Project`.

It surfaces late because a plane usually calls only the APIs it built
itself out of, which are enabled by definition. The first composite
that reaches for a kind the plane has never used is where it appears.


### A role named for objects grants nothing about the bucket

`roles/storage.objectAdmin` carries only `storage.objects.*` and
`storage.folders.*`. It does not carry `storage.buckets.get`, so a
client that asks about the bucket before it touches an object — which
an S3-compatible one does, since HEAD-bucket is how S3 says "does this
exist" — fails 403 against a name that plainly has write access.

The message names the permission rather than the role, which is the
useful half: read `does not have storage.buckets.get access` as a
missing second binding rather than as the first one being wrong.
`roles/storage.legacyBucketReader` supplies it and is the role meant to
be bound on a bucket; `roles/storage.bucketViewer` carries the same
permission and is meant for a project.

## Rules

**MUST:**
- Grant bucket-metadata read alongside object access where the client
  is S3-compatible. `storage.objectAdmin` has no `storage.buckets.get`,
  and a HEAD-bucket is the first thing such a client sends.

- Give every node pool its own service account with
  `roles/container.defaultNodeServiceAccount`.
- Grant both halves of Workload Identity, and pin the Kubernetes
  service account name.
- Audit an inheriting identity against every resource it must manage,
  before the identity that created them is discarded.
- Prefer a project custom role over a predefined role that grants
  writes you do not want, and name it with underscores: a custom role
  id takes no hyphens.
- Grant `iam.serviceAccounts.actAs` on any service account something
  must attach to a resource, the default compute service account
  included where the thing creates clusters: a cluster's initial node
  pool runs as it even when `removeDefaultNodePool` deletes the pool
  immediately. An identity that created the project holds this already
  and one granted rights inside it does not.

**MUST NOT:**

- Rely on the default compute service account being powerless. That is
  an org policy enforced elsewhere.
- Assume a role can be granted at the scope its feature acts on.
- Assume `gcloud auth login` refreshed ADC.
- Assume ADC impersonation makes `gcloud` act as that identity.

## References

- [plane-install](plane-install.md) — the
  identities an installation has, and why each is separate.
- [crossplane-providers](crossplane-providers.md) — how a provider
  authenticates as one.
