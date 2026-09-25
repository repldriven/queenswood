# 22. Cloud foundation and environment lifecycle

<!-- tessl-plugin: deployment -->

## Status

Accepted. Supersedes the management-plane half of
[ADR-0016](0016-crossplane-over-terraform.md), which chose Crossplane
but assumed the plane running it is a local kind cluster.

Extended by
[ADR-0024](0024-instances-are-their-own-composites.md), which says
where the environment composite named below actually lives, and what
the management plane's relationship to it is.

Revised once the foundation was first built. Three things this originally
got wrong: a folder is an installation boundary rather than a singleton,
the seed project exists only where you are your own platform team, and
access is granted through groups rather than to people.

## Context

ADR-0016 settled that infrastructure is declared rather than scripted.
The shape it left behind does not hold that line:

- **The plane that reconciles everything is a kind cluster on one
  laptop.** Nothing reconciles while it is off, teardown depends on
  keeping it alive to clear finalizers, and its credentials are a copy
  of the operator's own application-default credentials.
- **The lifecycle is a shell script.** `gcp-up` and `gcp-down` are
  imperative orchestrators, and `justfiles/cloud.just` has grown past a
  thousand lines. Much of it is not irreducible bootstrap: the backup
  bucket, its lifecycle rules, the HMAC key and the org-policy
  exemption all have managed-resource equivalents in providers that are
  simply not installed — `provider-gcp-storage` carries `Bucket`,
  `BucketIAMMember` and `HMACKey`, `provider-gcp-orgpolicy` carries
  `Policy`, and the already-installed `cloudplatform` family carries
  `Project`, `ProjectService`, `ProjectIAMMember` and
  `ProjectIAMCustomRole`.
- **There is one environment, and no answer for a second.** Every
  project id, secret and pointer lives in `pass` on one machine, which
  is neither shareable nor a story anyone else could adopt to run this
  on their own cloud.

The last point is the forcing one. Queenswood is meant to be something
another operator can run, and today the answer to "how do I stand this
up" is a laptop and a password store.

## Decision

### A folder is an installation, and there may be several

A GCP **folder** is what an installation is. It is an IAM boundary, the
place an org-policy exemption is expressed once instead of per project,
and the only stable handle in the design — project ids carry random
suffixes, so everything else is discovered from the folder downwards.

Inside it:

- A **management project** — the hub — running one GKE cluster with
  Crossplane and Argo CD. Never torn down.
- One **project per instance** — dev, test, prod, or whatever the
  installer chooses. Durable, and holding that instance's own data:
  what an instance stops is its compute, not its project. See
  [ADR-0024](0024-instances-are-their-own-composites.md).

There may be as many folders as the installer wants: one holding
everything, one for non-production and another for production, one per
jurisdiction. Each is independent and identically shaped, and nothing is
shared between them — a second installation gets its own bootstrap
identity, because those rights are folder-scoped, and its own management
project, because that is what reconciles the instances inside it. How
many instances there are and whether they are grouped into sub-folders is
the installer's concern, expressed as fields rather than settled here.

### The seed identity, and when a project holds it

Something outside the installation must create the management project.
Where an organisation provisions folders, that identity already exists in
its own automation project and is handed over with the folder, and a CI
runner assumes it through Workload Identity Federation.

Only where you are your own platform team does a **seed project**
exist: a service account has to live somewhere, and on a new
organisation no project does. It keeps a random-suffixed id and is
retained rather than deleted — an empty project costs nothing, and a
project id is consumed permanently, so a deterministic name could never
be recreated. It is designed so that it *could* be deleted, which is
what forces the management plane to hold its own service account rather
than borrowing this one.

The runbook for both paths is
[plane-install](../recipes/infra/plane-install.md).

Its rights are folder-scoped once the folder exists, but creating the
folder is not: `resourcemanager.folders.create` is checked on the
**parent**, so on that path the identity holds folderCreator and
folderIamAdmin there — the pair rather than folderAdmin, because
together they create a folder and grant roles inside it without being
able to delete one.

### Access is granted through groups, and nothing stands

Roles bind to groups, and membership is the only thing that moves
afterwards: one place to look, one thing to revoke, and recorded in the
directory's audit log rather than invisible. It is also the seam access
tooling drives, granting a role by adding a member for as long as it is
needed.

The rule is narrower than "always groups". Bind groups where humans hold
access, and principals directly where automation does — nothing but the
recipe would ever change which service account holds a role, so the
indirection buys only a propagation delay. Who may *act as* that service
account is a human question, so `serviceAccountTokenCreator` on it is
group-bound.

A group owner is always a member, so these groups have no owner and no
manager: administering a privilege-granting group is itself privileged
and belongs with directory administration, which a super admin holds
without being in the group. That is what makes it safe for the
organisation-admin group to be **empty** and for nobody to hold
Organization Administrator at all — break-glass is a super admin adding
a member, and an empty group is never a lockout.

What the groups are and how they come to exist is
[organisation-foundation](../recipes/infra/organisation-foundation.md).

The groups are not one per tier of seniority but one per capability that
must be separable. Organization Administrator and Folder Administrator
are two groups because the first cannot delete a folder and the second
cannot touch organisation IAM, and collapsing them would hand out both.

A billing account is the exception, and keeps one direct human
administrator beside its group. The organisation has a recovery path
outside its IAM policy; a billing account has none, so removing its last
administrator means a support ticket rather than a command.

### One management plane, not one per environment

Per-environment planes triple the only permanently-running cost in the
design to buy isolation available more cheaply: a distinct provider
service account per environment scoped to that project alone, Argo
`AppProject`s constraining which Applications may target which
destination, and manual rather than automatic sync for prod. A
separate prod plane is a second instance of the same configuration if
a compliance argument later demands one, not a re-architecture.

### Foundations are observed and liened; only what rebuilds is deleted

Tiering by what is expensive to rebuild extends up to the projects
themselves:

- Bootstrap project, management project, instance projects, DNS zones and
  backup buckets carry `managementPolicies` without `Delete` — v2
  namespaced resources have no `deletionPolicy` — and are protected by
  GCP project **liens**.
- The folder is protected differently, because liens are a project
  mechanism and do not apply to folders. What protects it is that
  `resourcemanager.folders.delete` is held by nobody: Organization
  Administrator does not carry it, and the seed identity is given
  `folderCreator` and `folderIamAdmin` precisely so that it cannot.
  Deleting one means joining `grp-gcp-folder-admin@` deliberately. GCP also
  refuses to delete a folder that still holds projects.
- Instance clusters, addresses and certificates are fully managed,
  `Delete` included: each rebuilds from its own declaration and carries
  nothing that cannot be rebuilt with it.
- CloudSQL is not among them, and was when this was written. A database
  holds state, and `down` stops it with `activationPolicy: NEVER`
  rather than deleting it, so it belongs with the protected tier. The
  original list read "disposable", which described a generation where
  `down` destroyed the project. It no longer does.

The liens matter more than the policies. A deletion policy is a
convention a later edit can quietly undo; a lien lives in GCP and
refuses the delete no matter what the cluster asks for. The hazard is
not a deleted management cluster — that leaves managed resources
without running finalizers, so nothing happens — but a *live*
Crossplane watching its resources disappear through an Argo prune or a
bad sync, and doing exactly what it was told.

### Down is a declared state, not an absence of one

Crossplane reconciles toward a desired state and has no notion of
"off". It does not need one: off is a different desired state.

An environment composite carries `state: up | draining | down`, and the
Composition maps `down` onto a stopped environment — GKE node pools at
zero, CloudSQL `activationPolicy: NEVER`, purely rebuildable resources
absent. Data is not among what stops: an instance's project is durable
and keeps it.
Bringing an environment down becomes a one-word change reconciled like
any other, rather than a shell script sequencing deletions. The same
trick as `fdb.restore.version`: name the target and let reconciliation
do the work.

### Teardown is ordered by Usage gates, and the gates are the export Jobs

Down being a state does not make a teardown safe, because a safe
teardown is a *sequence*. FDB's backup must be closed off while its
cluster is live and before the workload namespace drains; Keycloak's
export must run after GKE is gone and before CloudSQL is deleted.
Reconciling to `down` naively would delete in whatever order the
resource graph resolves, losing precisely the data the backup exists
to preserve.

A `Usage` (`protection.crossplane.io/v1beta1`, present in the
Crossplane 2.3 already running) declares that one resource is used by
another and blocks deletion of the used resource until the using one
is gone. Its `by` is an untyped `apiVersion`/`kind`/`resourceRef`, so
a `batch/v1` Job is as valid a referent as a managed resource — the
export Jobs are themselves the gates, and no marker object is needed:

```yaml
Usage{of: <GKE cluster>, by: Job/fdb-export,      replayDeletion: true}
Usage{of: <CloudSQL>,    by: Job/keycloak-export, replayDeletion: true}
```

`replayDeletion` retries the released deletion immediately rather than
waiting out a backoff. The Keycloak gate is satisfied later of its own
accord, since that export needs GKE gone before it can run.

How the Job is written is what makes this safe, because a `Usage`
blocks while its `by` **exists**, not while it is unfinished — a
completed Job goes on blocking until something removes it. Reaching
for `ttlSecondsAfterFinished` is the trap: TTL collects *finished*
Jobs, and finished means `Failed` as well as `Complete`, so a failed
export would delete its own gate and release the deletion of the data
it had just failed to preserve.

So the container loops until the export succeeds and only then exits
zero. Failure is never a Job outcome — it stays `Running` and the gate
holds; TTL fires only on success. Running blocks, complete releases,
failed cannot happen. The same discipline as the `wait-for-restore`
gate, expressed through Job status rather than an initContainer.

The Usages and Jobs are emitted by the environment Composition on
entering `state: draining`, which precedes `down`.

Gating the right writers is harder than it looks, and issue #349 is
the evidence. The first real teardown-and-rebuild restored
FoundationDB with no intervention and needed four manual steps for
Keycloak, because the restore was ordered ahead of the writer that
seemed to matter -- the bootstrap Job -- while Keycloak itself starts
two seconds earlier and creates its own schema. A gate is only as good
as the census of writers behind it.

The same teardown found three infrastructure defects of one shape: a
wait that could never finish, a cluster's credentials fetched before
it existed, and an IAM binding that does not survive a rebuilt
instance. Each was an assumption that held while an environment was
running. That is the class of failure `draining` automates, so this
design should be treated as unproven until a cycle runs unattended.

### Backups are ours, and CloudSQL's are off

CloudSQL's automated backups are disabled outside prod. They are tied
to the instance, so `gcp-down` destroys them, which means they cover
nothing in the cycle actually run — while still costing storage and
presenting a second mechanism to reason about. What replaces them is
what already exists for FDB: an export we take, into the tier-0
bucket, on a schedule and again at ordered shutdown.

The shutdown export matters more than the scheduled one. It is the
artefact a rebuild restores from, and with automated backups gone it
is the only thing between a teardown and losing the realm entirely —
which is why the teardown gate must block on it rather than warn.

This buys a property worth more than the storage it saves. If every
durable Keycloak restore point is an export we took, and each is taken
alongside FDB's restore version, then **restore points are paired by
construction**. The dangling-reference problem — identities pointing
at organizations that no longer exist, organizations whose service
accounts are absent from the realm — stops being a hazard to warn
about and becomes unreachable. A scheduled export must therefore
record the FDB version at the same moment, or it forfeits the pairing
and is worth less than the automated backup it replaced.

Prod is the exception, and keeps both automated backups and
point-in-time recovery. "Someone corrupted the realm on Tuesday" is a
real production failure, and rebuilding from a teardown export is not
an answer to it. So this is a per-environment setting alongside
`state`, defaulting off.

### Prod shares nothing; non-prod may share, in one order

Prod shares nothing: an environment holding money gets its own
project, ledger and identity store, and no argument about cost changes
that. Non-prod environments may share, and the order to consider it in
is Keycloak first, a database second, FoundationDB last and probably
never.

Cross-project access is the wrong thing to fear here. Reaching a
shared Keycloak is ordinary HTTPS to a hostname, and a shared CloudSQL
is an IAM binding for the workload's service account.

The genuine obstacle is FoundationDB. The keyspace prefix looks like
the mechanism for it and is not: it exists so separate *banks* can
share a cluster, and it is a single value per deployment, so it cannot
carry an environment axis as well without conflating the two. It is
also not retrofittable — setting it on a cluster that already holds
data strands the existing records, changelog and cursors. And a prefix
is a naming convention rather than a boundary in any case.

Sharing a cluster also couples the environments' lifecycles: tearing
down test stops being safe once dev's data lives in it, which forfeits
the disposability the tier model exists to provide.

### The minimum held outside GCP

Once the management project is durable, almost nothing needs to be:

- **Non-secret configuration** — domain, environment names, region —
  lives in git.
- **Secrets that must outlive a teardown** — the FDB backup encryption
  key, the GCS HMAC key, database passwords — live in Secret Manager in
  the management project.
- **The manifest** — one per installation, naming the folder, the billing
  account and the instances. It holds no secret, and it is the whole
  record of what exists.

No pointer is needed. The organisation and billing account are
discoverable from the operator's own credentials, and the bootstrap
project is found by its label.

What cannot be automated is the directory: an organisation comes from
Cloud Identity rather than Google Cloud, and every Cloud Identity call
needs a project to attribute quota to, which at foundation time does not
exist. So claiming the domain, creating the billing account and creating
the access groups are done in a browser, and everything after them is
declared.

## Consequences

**A permanently-running GKE cluster is a new cost**, where kind was
free. It is the only always-on component, which is the strongest
argument for a single management plane. Sizing it for "Crossplane and
Argo and nothing else" keeps it small, and one zonal cluster's control
plane may fall under the per-billing-account allowance — worth
confirming against current pricing rather than assumed.

**Most of `cloud.just` dissolves.** Project creation, API enablement,
IAM bindings, custom roles, the backup bucket and its lifecycle, the
HMAC key and the org-policy exemption all become managed resources.
What survives is the directory work that has no API — claiming the
domain, the billing account, the access groups — and the two commands
that create the seed identity. The recipes added for FDB and
Keycloak backup are the last generation of that style rather than the
start of one.

**Reconciliation stops when the plane is scaled to zero.** Node pools
can be scaled down to save cost, but a management plane at zero does
not reconcile — which is the problem moving off kind was meant to
solve. Acceptable for dev and test, self-defeating for prod.

**Migration is incremental.** The folder, the seed identity and the
management project can be created while the kind plane still runs, and
instances moved one at a time. Nothing here requires a flag day.

**The restores this depends on are proven for FDB and not for
Keycloak.** A full teardown and rebuild returned FoundationDB intact
without intervention, and Keycloak only after four manual steps
(#349). `state: draining` assumes both are unattended, so that gap
closes before this is built, not after.

## Future

Whether the management plane should also be reachable privately —
rather than through a public GKE endpoint — is deliberately left open,
and interacts with any decision to put a private network in front of
it.
