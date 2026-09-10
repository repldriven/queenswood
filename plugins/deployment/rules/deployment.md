# Queenswood deployment

How Queenswood ships and runs — the Helm chart, the kind dev loop, and
the Crossplane-managed cloud infrastructure underneath it.

## Deploy each service as project + base + shared Dockerfile

A deployable service is a project under `projects/*-service/` — pure
config, `deps.edn` plus `resources/` — and a base that owns `main.clj`,
imaged from `infra/docker/service/Dockerfile` with a `PROJECT_NAME`
build-arg and from nothing else, since the arch-aware `libfdb_c.so`
install and the shared base layer are what have to stay consistent
across services. Every service Deployment waits on the bootstrap Job
through a `wait-for-bootstrap` initContainer, and neither the migrator
nor the bootstrap Job may be skipped in any flow: the policies, the
product templates, the topics and the FDB metadata are preconditions to
any service's startup. A cross-pod dependency goes in the deployment's
`waitFor` list, which adds a `wait-for-<dep>` initContainer polling the
target's `/actuator/health/liveness`. Deploy flows share the one Helm
release name, `queenswood`, so resource names do not diverge, and a
resource name never carries an environment — discriminate through
`values.yaml` overrides and env vars. A project may carry its own
`application.yml` in `resources/`; a service may listen on more than
one port, `port` being the primary the probes and the Service's `http`
port target and the rest in `extraPorts` as `{name, port}`; and a
service may set `replicas > 1`, except `exclusive-dispatchers-service`,
which owns every changelog cursor and every cron trigger and stays at
1 — scale the relay tier by sharding stores across deployments, and
expect extra replicas elsewhere to buy standbys rather than throughput
until `message-bus/send` carries a partition key and topics have more
than one partition. Never delete a `Keycloak` resource while its
database survives: the operator regenerates the admin password while
the database keeps the old admin user, and nothing can administer the
realm it is still serving. Never reset Keycloak's schema while
FoundationDB survives: the realm rebuilds from the committed JSON with
fresh user ids, and the records referencing the old ones are orphaned
silently.
See [deployment](../../../docs/recipes/infra/deployment.md).

## A folder is an installation, and its foundations are not deleted

A GCP folder is what an installation is: an IAM boundary, the one place
an org-policy exemption is expressed, and the only stable handle, since
project ids carry random suffixes and everything else is discovered from
the folder down. Inside it, a management project running Crossplane and
Argo, never torn down, and one durable project per instance holding
that instance's own data — what an instance stops is its compute, not
its project. There may be as many folders as the installer wants; each
is independent and identically shaped, with its own seed identity and
management project because those rights are folder-scoped. One
management plane serves every environment: isolation comes from a
provider identity per environment, Argo `AppProject`s and manual sync
for prod, not from a plane each. A seed project exists only where you
are your own platform team, keeps its random-suffixed id and is
retained rather than deleted; creating a folder is checked on the
parent, so the seed identity holds `folderCreator` and `folderIamAdmin`
there — the pair, never `folderAdmin`, so it cannot delete one.

Protect a foundation in GCP rather than in a manifest. Projects, DNS
zones and backup buckets carry `managementPolicies` without `Delete` and
a project lien; a folder cannot carry a lien, so what protects it is
that nobody holds `resourcemanager.folders.delete`. The lien matters
more than the policy — a policy is a convention a later edit undoes, and
the hazard is not a deleted control plane, which leaves managed
resources with no finalizers running, but a live one watching its
resources vanish through a prune and doing what it was told. Only what
rebuilds from its own declaration — clusters, addresses, certificates —
is fully managed with `Delete`; a database holds state and belongs with
the protected tier.

Express off as a desired state rather than an absence of one:
Crossplane reconciles toward what is declared and has no notion of
stopped. An instance carries `state: up | draining | down`, and `down`
is node pools at zero and CloudSQL `activationPolicy: NEVER`, with data
untouched. Order a teardown with `Usage` gates whose `by` is the export
Job itself, `replayDeletion: true`, emitted on `draining`: a `Usage`
blocks while its `by` exists, so the Job loops until the export succeeds
and only then exits — never `ttlSecondsAfterFinished` on a Job that can
fail, since TTL collects `Failed` too and a failed export would release
the deletion of what it failed to preserve. Disable CloudSQL's automated
backups outside prod and take the export yourself, alongside FDB's
restore version so restore points pair by construction; prod keeps
automated backups and point-in-time recovery. Prod shares nothing;
non-prod may share Keycloak first, a database second, FoundationDB last
and probably never. Hold outside GCP only what git and Secret Manager
in the management project cannot: the directory acts — the domain, the
billing account, the groups — are done in a browser.
See [ADR-0022](../../../docs/adr/0022-cloud-foundation-and-environment-lifecycle.md).

## Build the plane before a merge can install anything

Build the plane before expecting a merge to install anything: a control plane
running another toolchain cannot apply this kind. Read what you can reach
before choosing between creating a folder and adopting one. Grant the seed
identity its rights on the folder or the parent, never a key; impersonate it
rather than holding a credential, and revoke the impersonation once the
throwaway plane is gone, since it outlives the plane, the terminal and the
reboot otherwise. Never grant a person `serviceAccountTokenCreator` on the
platform identity or create a key for any of the four. Ask for
`compute.skipDefaultNetworkCreation` before the first project is created, and
never fix a default VPC in a composition — it cannot be undone there. Commit
the manifest before applying it and push it before any plane takes over
reading it from git, and pivot the composite off a throwaway plane before
discarding that plane. Never render a manifest over one that already exists:
the management project id is minted per call, so the second render replaces
the recorded id with one no project answers to, and the redirect truncates
before the renderer runs. Close the seed identity once the bootstrap is done
and reopen it for the next one: its organisation grants otherwise stand for
ever, and the plane needs none of them. Never assume you can create a folder —
ids are required, and one may be handed to you instead — and never delete a
project as a side effect of an edit. Standing the installation up with no
instance at all is valid, since an instance is its own composite applied
afterwards, asking a platform team for the folder and the identity shortens
the path without changing it, and another XRD and composition loaded onto the
plane deploys something else the same way.
Commands: `just boot-cluster-up`, `just seed-impersonate`, `just
seed-impersonate-revoke`, `just queenswood-installation-manifest`,
`just boot-mgmt-apply`, `just gcp-org-enforce-constraints`, `just
boot-cluster-down`, `just seed-close`.
See [management-plane-install](../../../docs/recipes/infra/management-plane-install.md).

## The identity that builds installations is opened and closed

Create the seed identity once for an organisation rather than once per
installation, and reuse the project labelled `queenswood-tier=seed`
where one exists rather than minting a second -- its id is consumed and
it is retained rather than deleted. Hold `folderCreator` and
`folderIamAdmin` on the parent, never `folderAdmin`, so it cannot
delete a folder, and grant them there because creating a folder is
checked on the parent rather than on the folder. Impersonate it rather
than holding a credential, never create a key for it or for any
identity an installation composes, and never grant a person
`serviceAccountTokenCreator` on it outside a bootstrap. Read what you
can reach before choosing between creating a folder and adopting one.
Close it once a bootstrap is done and reopen it for the next: its
organisation grants otherwise stand for ever, and the plane that
succeeds it needs none of them. Never delete the seed project — its id
is consumed and it is reused. Skip this entirely where an organisation
hands you a folder and an identity able to create projects in it --
this is how we produce one, not what an installation requires.
Commands: `just seed-preflight`, `just seed-create`, `just
seed-grant-org-roles`, `just seed-close`, `just seed-open`, `just
seed-impersonate`.
See [organisation-bootstrap](../../../docs/recipes/infra/organisation-bootstrap.md).

## An installation is one file, and changing it is a merge

Change what exists by editing the manifest and merging it, never by
acting on GCP, and apply from merged state only, since a
`pull_request` trigger gets no cloud identity and a fork's would
otherwise run as the platform identity. Push the manifest before a
plane takes over reading it from git, and give Argo the credential for
the manifests repository before expecting any later merge to reach the
plane at all.
Supply `management.projectId` always — the folder is `XSubsidiary`'s
rather than this manifest's — give `metadata.name` and
`spec.code` the same string — nothing enforces it and the tooling
assumes it — and state `region`, `regionCode`, `zone` and anything else
immutable rather than leaving it to a default that may move. Render the
installation's environment and merge it before the first instance is
composed, keep the manifests repository private, and read `status` back
rather than committing it. Never commit anything secret beside the
manifest, never
name a principal in `access` that does not exist — IAM rejects the
binding, not the manifest — never create a key for an identity the
installation composes, never leave a new XRD field required when the
manifest that sets it lives in another repository, never retype a
verification token into the manifest, since the block is rendered from
what the domain answers and a token that exists only in the file proves
nothing, and never delete and recreate the public zone, whose
nameservers change with it while the registrar does not follow.
`management.source` may point at upstream, a fork, or a mirror that vendors
the layout, with `targetRevision` pinned
to a tag; an empty `access` mapping installs and capabilities may be
added later, which on a first installation is the only order available;
an existing recovery project may be adopted by passing its id; and one
manifest per folder allows
more than one installation.
See [management-plane-install](../../../docs/recipes/infra/management-plane-install.md).

## An instance is a unit, and its secrets are written while it builds
Render an instance's unit with `just queenswood-instance-manifest`, which
mints the project id once and writes it into every file carrying it, and never
render one over a unit that has been committed — the id is minted per call,
and a committed unit may already be built, leaving the file as the only record
of the one GCP consumed; an uncommitted one a plane never read is free to
re-render. Where a file is written by hand instead, the ids have to agree: a
wrong one in the external-secrets annotation is a service account nothing is
bound to rather than an error. Put the unit declaration at the top of the
installation's directory, never inside the unit's folder, since the
installation's Application is not recursive and a declaration filed inside is
never applied at all. Add `adopt` beside the project's `projectId` once the project exists and
merge it, since upjet records a project's external name after its own
create and nothing else can find it afterwards -- a plane that did not
create the project composes a `Project` with no external name, tries to
create one already there, and is answered `409 Requested entity already
exists` for ever; never before the create, where an external name on a
project that does not exist makes the first observation fail and creation
never follow. Give the instance its own `access` mapping and let it
reconcile before writing any secret version: the installation's `secretsAdmin`
binds on the management project, writes nothing here, and the denial is
reported as a container that does not exist. Let an instance take its region
from the installation's `environment.yml`, since setting `region`,
`regionCode` or `zone` on the instance overrides it for that one alone. State
`ingress.domain` distinct from every other instance's, naming the
installation's zone in `zone.name` and `zone.project`, and never share a
domain between two instances — both compose a record for the one name and each
reconciles it to its own address. Merge the composite and the Applications
separately, the composite first: Keycloak honours a bootstrap admin only while
the master realm is absent, and nothing automatic holds that gap open, where a
folder with no Applications in it does. Create the OAuth client in the
console, in the instance's own project, one per environment. Write the
Keycloak bootstrap admin before the bank first starts and name it in the
unit's values as `keycloak.bootstrapAdmin.secretName`, or the entry is inert;
write the other two versions the same way, letting each strip the trailing
newline, and never add a second version to the FDB backup key. Never create an
instance with `state: down` — Cloud SQL refuses to create an already-stopped
database, so an instance is built up and stopped afterwards. An instance may
lean on the XRD's defaults, which `QW_DEFAULTS=true` does, but state them with
`QW_DEFAULTS=false` for anything long-lived, since the blocks it writes out
are immutable or nearly so and a default that moves under a live instance is
refused rather than applied. An instance may be taken down once it is up, and
one stood up with no `ingress` answers on no name at all.
Commands: `just queenswood-instance-manifest`, `just
queenswood-instance-keycloak-admin`, `just
queenswood-instance-google-secret`, `just
queenswood-recovery-backup-key`, `just crossplane-unready`, `just
argo-apps-status`, `just queenswood-instance-ctx`.
See [instance-deploy](../../../docs/recipes/infra/instance-deploy.md).

## A folder is a subsidiary, and the plane is built in one

Compose the folder as its own kind — `XSubsidiary` in
`platform.repldriven.com` — and never inside the plane's, which pairs
something that must never be deleted with a cluster rebuilt routinely.
Give it the folder, the org-policy exemptions expressed on it, and the
folder-scoped half of `access`; leave the management project, the
cluster, the identities and the project- and service-account-scoped
bindings with `XManagementPlane`, which composes no folder and finds
the one it sits in by naming `fldr-<code>` from its own `spec.code`.
Only `platformViewer` and `clusterAdmin` bind on a folder.

Make the XR the handover in either direction: composed where the folder
is ours, and adopted with `folderId` where an organisation hands one
over. State `parent` and `displayName` in both modes, since the
provider holds `Update` and either left to a default is one it would
write onto a folder somebody else owns, and never omit `folderId` when
adopting — GCP permits two folders with one display name under a
parent, so a second is composed and everything reports healthy. Prove
an adoption by counting folders under the parent rather than by reading
the composite. Where an organisation runs no Crossplane, nothing
instantiates the kind and the XRD is the contract a folder must meet.

Compose the bindings and take the principals as input; creating a group
stays a directory act, because Groups Admin is not scopable to a name
prefix and an identity that could mint
`grp-gcp-<code>-platform-viewer@` could add itself to
`grp-gcp-org-admin@`. Withhold `Delete` in its own merge before moving
a live resource between the two kinds, or the transfer revokes what it
was meant to move, and expect a window: the two composites sit in two
repositories behind two Applications, so the parent releases and the
child adopts by external name. Never remove a field from an XRD while a
manifest still sets it — the schema prunes, and Argo then diffs for
ever.
See [ADR-0027](../../../docs/adr/0027-the-folder-is-a-subsidiary.md).

## The contract is agreed before the boundary is built

Commit the installation's contract before applying its boundary: the
composite reads `access` and `folder` from it and composes neither the
bindings nor the folder without them, and a principal named there that
does not exist has IAM rejecting the binding rather than the file.
Render the manifest at the top of the installation's directory, never
inside a subdirectory of it. Never state `parent` and `displayName`
beside a `folderId` and expect them to apply: they are ignored, and
removing that line later leaves them to compose a second folder. Prove
an adoption by counting folders under the parent rather than by reading
the composite, which reports healthy either way. This manifest may be
re-rendered at any time, holding nothing generated, and a boundary may
be declared with an empty `access` mapping, which reconciles correctly
and which nobody can reach.
Commands: `just queenswood-subsidiary-manifest`.
See [boundary-install](../../../docs/recipes/infra/boundary-install.md).

## A composite builds what an instance is, Argo installs what runs there

The plane is the only thing that reconciles anything, the durable home,
and where the API lives; it manages the instances and does not compose
them. Make each instance one XR of its own kind — `XQueenswoodInstance`
in `queenswood.repldriven.com`, beside `XManagementPlane` in
`platform.repldriven.com` — never a field or a list on the plane's XR:
a composite is a unit of replacement and so a blast radius, `state` is
a per-instance property a plane does not have, a list item's name
renumbers when one before it is removed and rebuilds the live
environment it named, and the plane's kind stays generic. Give the
instance its own project with the protected tier's policies, so `down`
stops it rather than deletes it, and keep the instance's operational
state — database, disks, buckets — in that project; retire a project
deliberately, by lifting its lien, never by reconciling it away. Find
the folder by naming the composed `Folder` as `fldr-<code>` from the
instance's own `spec.code`, never by referring to the plane's composite,
so neither composite knows about the other. Keep one manifest per
composite, flat in the installation's directory — its Application does
not recurse, and `prune: false` there means adding a file never removes
anything.

The composite builds the project, network, cluster, identities, database
and names an instance answers on. It does not install the workloads:
those arrive through Argo, reading the chart from one repository and the
values from another. The line is between a cloud API and a cluster — a
composite creates the identity a controller runs as, and Argo installs
the controller. Crossplane could do both, but that means a second
delivery path with its own provider configuration and its own credential
into every instance cluster, beside one that already works. Keeping them
apart also keeps their failures apart: a composite that stops
reconciling leaves the workloads running, an Application that fails to
sync leaves the infrastructure standing, and each reports in its own
place.
See [ADR-0024](../../../docs/adr/0024-instances-are-their-own-composites.md).

## Cloud infrastructure is Crossplane, not Terraform

Cloud infrastructure is declared via Crossplane, not Terraform. A small
local kind cluster (`boot-mgmt`, the boot management plane) runs
Crossplane plus the GCP family of upjet providers and `provider-helm`;
every cloud resource is a Crossplane Managed Resource or a Composite of
them (XRDs + Compositions). Argo CD on the same kind cluster applies
the manifests from the repo, and workloads on GKE are themselves
Crossplane `Release` resources of `provider-helm`.
See [ADR-0016](../../../docs/adr/0016-crossplane-over-terraform.md).

## The catalogue holds only what has an API

A kind needs a provider that can create the thing, and some of what an
installation depends on has no API at all: the organisation, domain
verification, the registrar's delegation, an OAuth client with a chosen
redirect URI. These are not kinds nobody has written yet — they cannot
be in the catalogue, and somebody will eventually go looking for the
abstraction that cannot exist. The manual half lives in the
`organisation-foundation`, `gcp-dns` and `google-sign-in` recipes and
is as much a part of building an installation as anything composed.
What is
excluded is only the thing itself: the OAuth client cannot be composed,
but the Secret Manager entry holding its secret is an ordinary managed
resource and belongs in the catalogue like any other.
See [ADR-0025](../../../docs/adr/0025-building-blocks-and-what-cannot-be-one.md).

## A composed resource is identified by its composition name

Give the application one kind, and decompose inside it into kinds that
group managed resources created and destroyed as one; never compose
resources with different deletion criteria into one kind, since
deleting a kind deletes what it composed — a public zone does not
belong with a public endpoint, nor a network with a cluster. Fix the
invariants in `base`, constants included, and leave the caller only
what does not change what the kind guarantees; fix a field that cannot
change after create in `base` too, never in a caller-supplied patch,
and compose a second resource where a caller must vary one — nothing in
the CRD says which fields those are. Read the slot names
already in use before naming a composed resource, and change a
resource's `- name:` to rebuild it under a new `metadata.name` —
deleting the object alone rebuilds the old one. Set
`policy.fromFieldPath: Required` where a missing source is a mistake
rather than a meaning, and on every patch reading a field the XRD
defaults -- except in the change that adds the field to a kind with live
XRs, since a default is applied when an object is written and never on
the way out, so absent there means an XR older than the field: put its
value in `base`, leave the patch unrequired, and read it in a template
with a fallback. Never expect a Composition to withhold a field. Use
`function-go-templating` where the number of composed resources varies
with the caller, and never compose a cluster-scoped kind from a
namespaced XR. Carry `Delete` in `managementPolicies` only where a
rebuild returns what was there — withholding it is the prudent
default. End every Composition with `function-auto-ready`, and add a
`readinessCheck` against the field carrying the real state where a
managed resource's own conditions do not reflect the cloud. Make every
Composition edit safe for an XR that already exists: add fields rather
than repurpose them, default what a manifest does not yet set, and
never make a field required in the change that introduces it. Never
add a version to an XRD — where a change is that large it is a
different kind, named for what it is and adopted deliberately — and
never set `compositionUpdatePolicy: Manual` on an XR, since pinning
divides an estate into the XRs that took an edit and the ones that did
not, which is the problem versioning would have caused.
Commands: `just crossplane-kinds`, `just crossplane-slots`, `just
crossplane-policies`.
See [crossplane-design](../../../docs/recipes/infra/crossplane-design.md).

## Debug from what is not ready, not from what was named

Start from what is not ready rather than from the resource somebody
named. Read the composite's own conditions before any composed
resource's: one pipeline step failing — a template that will not parse,
a kind with no CRD — stops every composed resource and reports on the
composite, so never treat a failure as belonging to the resource it
names. Read `Synced`, `Ready` and `LastAsyncOperation` before
concluding anything, since they report different failures, and never
read `Ready` on a composite as evidence about every resource under it.
Enable an API before composing a kind that needs it — Cloud Storage is
on by default in a new project and Secret Manager is not — and install
a provider for every kind the composite composes, on every plane that
composes it. Check which field manager owns a field before assuming a
hand patch will hold or that a field will survive its patch being
removed: never patch a field the composition sets and expect it to
hold, and never delete a patch for a field you want kept, since the
composition owns what it patches and the field goes with the patch.
Change a composition-owned field in the Composition and merge it —
nothing pins a revision, so every live composite takes the edit on its
next reconcile, and a `kubectl patch` of such a field reverts. A
rendered diff is proof of what a composition produces, never of what
applying it does to what already exists.
Commands: `just crossplane-unready`, `just crossplane-conditions`,
`just crossplane-owners`.
See [crossplane-debug](../../../docs/recipes/infra/crossplane-debug.md).

## A change to a live resource applies, is refused, or destroys

Determine what kind of change it is before making it: ownership decides
what happens to a field, and identity is visible nowhere, so it is
settled when the kind is designed rather than looked up here. Read
`LastAsyncOperation` on the managed resource before treating a
change as applied, since a refusal reports there while the composite
above goes on reading `Synced`. Never expect a merged value to reach a
field that identifies its resource: upjet refuses the replacement
rather than performing it, so the value moves only by destroying and
rebuilding the cloud resource — granting `Delete` for the duration
where the policy withholds it, since nothing else moves it.

Withhold `Delete` before moving a resource to another composite, in a
change of its own that reaches the plane first — the transfer deletes
the parent's copy, so the parent's policy is the one that governs — and
never combine the policy change and the move into one merge, since the
plane applies what it reads and reads them in order. Read the live slot
names before naming a composed resource in the new kind: reusing one a
live managed resource carries makes two composites claim it, and every
apply then fails. Never rename a composite's slot without applying the
same two-step to the resources inside it, and never delete a composite
to tidy up — it deletes what it composes, subject to each resource's
`managementPolicies`. Count the live instances of a kind before
removing its XRD, since the CRD and every composite of it go with it,
and delete the Composition alongside, because nothing links them but a
`compositeTypeRef`. Read whether the Application carrying a file prunes
before treating a deletion from the repository as a removal from the
plane, and merge a change before expecting it there — Argo reads the
revision an Application names, never a working tree.
Commands: `just crossplane-owners`, `just crossplane-slots`, `just
crossplane-conditions`.
See [crossplane-live](../../../docs/recipes/infra/crossplane-live.md).

## Provider resources are Terraform underneath

Read the schema from the installed CRD with `just crossplane-explain`
before writing a composed resource, never from the provider's
documentation, and use the `.m.` API group. Check what the provider
late-initialises with `just crossplane-owners` before deciding which
fields to compose, and compose one whose value is a choice somebody
should make or a parameter a later create will need — never re-adding a
patch for a field late-initialisation now owns. Set the external name
explicitly where it must differ from the Kubernetes name or where
something else spells it, `just crossplane-external-names` being where
the two already differ, and feed a generated id back as an adopt value
where the external name is empty after create, or the resource never
completes. Pivot a provider-assigned value up to the composite and
compose from it rather than committing a literal read out by hand.

Never expect a ForceNew change to replace a resource: it is refused,
the refusal is in `LastAsyncOperation`, and diagnosing from `Synced`
alone misreads it. Never treat a list-shaped field as extensible
without checking — a `Certificate`'s `managed.domains` is identity, so
a second domain is refused rather than appended. Create a service account a
provider shares, or that a binding names, outside the package manager
and point the pod at it with `deploymentTemplate`; never pin a name in
`serviceAccountTemplate`, since the package manager takes controller
ownership and the next claimant — another provider, or this provider's
next revision — fails its runtime hook.
Commands: `just crossplane-explain`, `just crossplane-owners`, `just
crossplane-external-names`.
See [crossplane-providers](../../../docs/recipes/infra/crossplane-providers.md).

## Do it in order, and each recipe leaves what the next reads

Do these in order. Start at step 2 where the organisation is already
established, stop after step 4 — an installation with no instance on
it — and run step 1 once for an organisation, with steps 2 to 5 once
per installation.
See [up-and-running](../../../docs/recipes/infra/up-and-running.md).

## A foundation produces capabilities, not groups
Set recovery email and phone on the super admin, and 2-step verification: it
has no mailbox and no one above it, and a second super admin, unused, means
one lost device is not the end of the organisation. Read the subscription in
the Admin console rather than trusting the sign-up confirmation, since Cloud
Identity Free and a Workspace trial confirm identically and the trial expires
taking the organisation with it. Create every access group without an owner or
a manager, since both are members, and set Restricted before Only invited
users or the join rule is discarded. Bind from no active project, the
organisation's groups and an installation's separately, since each fails
before its own groups exist. Never script the creation: every Cloud Identity
write attributes quota to a project and at foundation time none exists, which
is also why `gcloud identity groups describe` answers that a group plainly
present does not exist.

Bind groups where humans hold access and principals directly where automation
does. Create the billing account as a user on your own domain rather than as
the super admin, never in a private window since 3-D Secure needs an ordinary
one, and never sign up in a browser already signed in to a Google account.
Keep one direct human administrator on the billing account, which may be an
existing one reused rather than created, and revoke the super admin's local
credentials and its direct organisation binding together once the group
carries the role — either one left standing still reaches super admin. Never
leave anybody standing in a break-glass group — `grp-gcp-org-admin@`,
`grp-gcp-folder-admin@`, `grp-gcp-billing-admin@`, `grp-gcp-dns-admin@`,
`grp-gcp-<code>-platform-admin@`, `grp-gcp-<code>-cluster-admin@`,
`grp-gcp-<code>-secrets-admin@`.

Create a group as a super admin, in the directory, and join
`grp-gcp-org-admin@` for the bind alone, leaving again — binding at the
organisation is the one act in either recipe that is not a directory act. The
organisation's capabilities outlive every installation and are bound at the
organisation; an installation's are coded to it, created before the manifest
that names them, and only `platform-viewer` is bound at the organisation,
taking Browser there because tooling cannot reach a folder without resolving
the organisation above it. The rest is folder and project scoped and reaches
them through the manifest. Put the people who operate an installation in
`platform-viewer` and nothing else, on accounts in your own domain, never with
a direct organisation binding. What either recipe produces is capabilities
rather than groups: an established organisation answers the same capabilities
its own way, so skip the organisation's foundation entirely and read the
installation's rather than follow it. An installation may be stood up with no
groups at all and an empty `access` mapping, which reconciles correctly and
which nobody can reach, and a capability may be answered by a user or a
`principalSet://` rather than a group. State the region in the contract too,
as `region`, `regionCode` and `zone`: the plane and every instance read them,
nothing defaults them, and a manifest that restates one is a second place for
it to be wrong. Declare an
organisation-scoped role in `infra/access/organisation-roles.json`, never in
the recipe that binds it, and read what a capability grants and why with `just
gcp-roles` — everything folder or project scoped is in the compositions under
`infra/platform/crossplane-xrds/`.
Commands: `just gcp-groups-bind-org`, `just
gcp-groups-bind-installation`, `just gcp-roles`.
See [organisation-foundation](../../../docs/recipes/infra/organisation-foundation.md) and
[contract-install](../../../docs/recipes/infra/contract-install.md).

## An automation identity is granted, never inherited

Give every node pool its own service account with
`roles/container.defaultNodeServiceAccount`, and never rely on the
default compute service account being powerless — that is an org policy
enforced elsewhere. Grant both halves of Workload Identity and pin the
Kubernetes service account name. Grant `iam.serviceAccounts.actAs` on
any service account something must attach to a resource -- the default
compute service account included where the thing creates clusters, since
a cluster's initial node pool runs as it even when
`removeDefaultNodePool` deletes that pool immediately, and an identity
granted rights inside a project it did not create does not hold this
already -- and bucket-metadata read alongside object access where the
client is S3-compatible: `storage.objectAdmin` has no `storage.buckets.get`, and a
HEAD-bucket is the first thing such a client sends. Audit an inheriting
identity against every resource it must manage before the identity that
created them is discarded. Prefer a project custom role over a
predefined role that grants writes you do not want, naming it with
underscores because a custom role id takes no hyphens, and never assume
a role can be granted at the scope its feature acts on. Never assume
`gcloud auth login` refreshed ADC, or that ADC impersonation makes
`gcloud` act as that identity.
See [gcp-iam](../../../docs/recipes/infra/gcp-iam.md).

## A credential is a declared container and a written version

Compose the container and write the version separately: a composite
declares an entry with no version, and a person adds one. Read it on
the destination cluster, through an operator authenticated by Workload
Identity, so neither git nor Argo ever holds the value; give the
`ClusterSecretStore` no auth block, since the controller's pod carries
the identity, and pin the release name and the service account name the
Workload Identity binding spells rather than letting either be derived.
Grant the writer on the project the entry is in — a capability bound on
one project writes nothing in another, and the denial surfaces as a
container that appears not to exist — and never create a missing
container by hand, since its absence means the composite has not
reconciled and one made here is one nothing declares. Write what the
consumer reads, byte for byte: an `ExternalSecret` base64s the payload
on its way into a Secret, so an entry a workload reads as bytes holds
those bytes and not the text of them, and a version goes in stripped of
the trailing newline anything typed or piped carries. Withhold `Delete`
from the entry's `managementPolicies` where the value cannot be
regenerated, and never add a second version to an entry that is not
rotatable — a later key strands every backup written under the first,
so rotate by starting a new generation under a new entry. Generate a
value the cluster can make in the cluster, from a Job, and let the
chart declare its Secret without `data`, since a chart Argo renders
cannot preserve one. Never commit a credential, private repository or
not, and never keep a second durable copy of one that can be
regenerated — not in a local store, and not in a file deleted
afterwards. Overwrite the clipboard after pasting a value, and delete
the controller's pod rather than waiting out the refresh interval,
restarting whatever reads the value at startup. A value that is not
something to type — a key, a certificate — may be passed as a file, and
several fields that identify each other may be held in one entry as
JSON.
Commands: `just queenswood-recovery-backup-key`, `just
gcp-secret-version`.
See [external-secrets](../../../docs/recipes/infra/external-secrets.md).

## Google sign-in is two console acts and an Admin API call

Configure the consent screen first, since the client cannot be created
without one, then create the OAuth client by hand in the console, as a
Web application: no API creates one with a chosen redirect URI. Grant
`roles/oauthconfig.editor` through `platformAdmin` rather than
`platformViewer`, because creating a client mints a credential. Match
the redirect URI to
`https://keycloak.<domain>/realms/<realm>/broker/<alias>/endpoint`
exactly, alias included. Create one client per environment and never
share one across environments. Choose external where users bring their
own identity, and read the verification warning against the scopes
actually requested rather than as written — `openid profile email`
needs none. Publish out of testing mode once the test-user list stops
being the point, or refresh tokens keep expiring after seven days and
it reads as an application fault. Name the app for the environment as
well as the product, since each instance has its own consent screen,
and use a group for the user support address — never a personal
account for either address.

A realm that exists keeps the placeholder it was imported with,
whatever the chart's committed definition says, so never expect a chart
change or a re-import to reach it. Put the id in through the
installation's `keycloak.googleClientId`: it reaches the realm over the
Admin API, and the Job that makes that call has to carry the id in its
name, or a changed id leaves the same completed Job and nothing applies
it. Leave a vault expression stored as the secret, never the secret
itself, restored from the committed definition rather than from what
the Admin API returned, since a configured secret comes back masked.
Write the secret into `sec-<code>-<env>-<label>-google-oauth` by hand,
with no trailing newline, name the vault key `<realm>_<key>` so the
realm's expression resolves, and restart Keycloak where a secret is
stored after the pod started. Never read `401 invalid_client` on a
rebuilt environment as evidence the rebuild failed — it is the likelier
cause and the wrong conclusion.
See [google-sign-in](../../../docs/recipes/infra/google-sign-in.md).

## The apex belongs to no installation, and names below it are delegated

Create the apex project outside every folder -- `prj-c-dns-<suffix>`,
beside the seed's and carrying no code -- with `just
dns-apex-project-create`, as a person holding `projectAdmin` and
`billingAdmin`. It binds `dnsAdmin` on the project as it goes: nothing
composes an access mapping for a project outside every folder, so the
apex is otherwise one nobody may read or write. Create the zone as a
verified person rather than as any service account, with `just
dns-apex-zone-create`, which refuses a second and stops where it cannot
tell an absence from a denial. Declare its contents in `apex.yml` at
the root of the manifests repository and change them by merging that
file and running `just dns-apex-apply`, reading `just dns-apex-diff`
before assuming the zone matches it. Delegate a name before deploying
the instance that answers on it, or its certificate never validates
while the instance goes on reporting healthy.

Never compose the apex from any control plane, or grant an installation
rights in its project: it publishes its nameservers upward and holds
nothing there. Never create and delete apex zones to try variations,
since each create draws from a finite per-domain pool and the draw is
not returned, and never delete the old zone until the registrar answers
from the new one. Every serving name is a delegation to a zone one
installation composes, named `dz-<code>-<env>-<label>` with the domain
in the spec rather than in the name; the installation states the name
delegated to it in `environment.yml` and never the apex above. Skip all
of it where an organisation hands over a folder and a subdomain --
their apex is theirs, and an installation reads the same either way --
and point the apex at a front door rather than at an environment's
address once one exists, since it is the same record.
Commands: `just dns-apex-project-create`, `just
dns-apex-zone-create`, `just dns-apex-diff`, `just dns-apex-apply`.
See [apex-install](../../../docs/recipes/infra/apex-install.md) and
[ADR-0028](../../../docs/adr/0028-the-apex-belongs-to-no-installation.md).

## A public zone needs proven ownership, and the registrar is touched once

Verify the domain before a public zone is created, as the operator
account in its own right, adding it as a Domain property rather than a
URL prefix and adding the automation identity as an Owner — Full and
Restricted confer no ownership. Never read an existing
`google-site-verification` record as evidence your account owns the
domain, or an absent Search Console property as evidence it is
unverified, and never tidy away an unattributed token or regenerate one
to move it: the same string is copied, and answers from both
authorities across the switch. Where the domain was auto-verified
through its provider, add the DNS TXT method explicitly, and never
leave the installation's identities delegated from a personal account.

Inventory every record type at the registrar before moving a domain,
the underscore-prefixed names included, and carry the verification
tokens, SPF and DMARC into the new zone before the delegation moves.
Check for a DS record before delegating; where one exists, unsign at
the registrar and wait out the DS TTL first, watching the parent
registry rather than the zone, and take several spaced probes across
more than one resolver before calling DNSSEC recovery complete. Unsign
first for a domain not yet serving anything, so the wait overlaps
everything else, and last for one serving traffic, to keep the window
tight. Never delete and recreate a zone to change it: the nameservers
change with it, the registrar does not follow, and each fresh zone
draws from a finite per-domain pool. Move the apex once rather than
delegating a subdomain per environment. Moving the delegation itself is
the section below.
Commands: `just plane-identity`, `just dns-records`, `just dns-carried`.
See [gcp-dns](../../../docs/recipes/infra/gcp-dns.md).

## A delegation moves only once the new zone answers

Diff the same sweep from each authority before delegating, aiming it at
the new zone's nameservers and at the registrar's in turn: a public
resolver still answers from the old authority and can say nothing about
the new one. Query the registry's authority section to check the
delegation itself, since a referral carries the NS records there rather
than in the answer, and a short query looks empty and reads as failure.
Confirm the verification TXT resolves from the new authority
afterwards. Never change the delegation before the new zone answers, or
while a DS record still names the old nameservers' keys, never replace
only some of the registrar's nameservers, never delete the old records
there — they are the way back — and never re-enable DNSSEC at the
registrar afterwards. Set a CAA record naming the issuing CA. Done in
this order the propagation window is a no-op, since both authorities
answer the same and no resolver holding either one is wrong.
Commands: `just dns-records`.
See [gcp-dns-delegation](../../../docs/recipes/infra/gcp-dns-delegation.md).

## A parent Application holds only kinds that already exist

Keep concrete resources out of a parent Application: anything whose kind a
child installs belongs in a child of its own. Sync waves do not resolve a
missing kind, and `SkipDryRunOnMissingResource` skips the dry run and nothing
else. Set `ServerSideApply=true` for charts with large CRDs, `prune: false`
where pruning would delete something a missing file should not delete, and
retry budgets that outlast an operator install — a merged fix does not reach
an Application whose retries are already exhausted. When a sync is failing,
read `.operation.sync.revisions` rather than `status.sync.revisions`: the
first is what is being retried, the second only what would be synced next.
Read `retryCount` before calling a stuck sync a retry loop, since unset means
the operation is not failing at all. Merge the fix before cancelling anything,
or the fresh sync hangs the same way; then remove `.operation` to cancel a
queued sync, with a JSON patch, and terminate one already in flight by setting
`status.operationState.phase` to `Terminating` — terminate before removing
`.operation`, never after, since operation processing is driven by that field
and removing it first leaves the state sitting `Terminating` for good. Both
are plain patches: the Application CRD declares no status subresource, and
`--subresource=status` reports the object as not found. Strip
`argocd.argoproj.io/tracking-id` from a resource handed from one Application
to another. Merge a change before expecting Argo to apply it, and confirm it
landed: Argo reads the revision an Application names, never a working tree.
Never rely on Helm's `lookup` to keep a value a chart generated once — Argo
renders with `helm template`, where it returns nothing, so the branch that
mints a fresh one wins on every sync.
Commands: `just argo-apps-sync-policy`, `just argo-apps-operation`,
`just argo-apps-status`.
See [argocd-apps](../../../docs/recipes/infra/argocd-apps.md).

## A group with no health check reads Healthy

Re-read the checks after an Argo upgrade and re-check the status-less
kinds after a Crossplane one — an upgrade is what moves either answer.
Add an XRD's API group to `compositeGroups` in
`infra/helm/management-plane/values.yaml` in the same change as the
XRD, where the group is not there already; one entry covers every kind
in a group. Never read `Healthy` on a group with no check as evidence
of anything, and never expect a managed resource's health to reach the
Application above it. Give an environment's Applications a parent of
their own before registering a check for `argoproj.io/Application`.
When writing a check, read `Synced` before `Ready`, in a pass of its
own, and patch the script that checks a status-less kind rather than
one kind — there are several, in both groups. Delete the
`*.crossplane.io/*` and `*.upbound.io/*` entries from
`infra/helm/management-plane/templates/argocd-cm.yaml`, and point
`LISTED_CROSSPLANE` and `LISTED_UPBOUND` in `justfiles/argo.just` at
Argo's own lists, once a release carrying `argoproj/argo-cd#29382` is
the one the plane runs; keeping them is defensible only for a kind
upstream still does not list.
Commands: `just argo-health-checks`, `just argo-health-kinds`.
See [argocd-health](../../../docs/recipes/infra/argocd-health.md).

## Argo reads a private repository as a GitHub App

Use a GitHub App, never a deploy key or a personal access token. Grant
it Contents read-only, give it no webhook access and no write
permission, and install it only on the repositories Argo reads —
creating it grants nothing. One App may serve several repositories in
the same organisation where the same reader should reach all of them,
and a public source repository needs no credential at all. Merge
`manifestRepoURL` before writing the credential, since nothing composes
a container to write to without it, and never set the Application's
`repoURL` or the Secret's `url` by hand: both derive from that one
field, which is what keeps them equal. Store the App ID, the
Installation ID and the private key together in
`sec-<code>-c-github-app`, spelling the kind
`secret.secretmanager.gcp.m.upbound.io` — the short name resolves to
Kubernetes' own Secret and reports the object as not found — joining
`secretsAdmin` for the write and leaving again. Let the chart's
`ExternalSecret` place the Secret and keep the key in the secret store;
delete the `.pem` once the entry holds it, never commit it or pass it
on a command line, and add a new key before deleting the one it
replaces. Read a repository reported unreachable as an entry with no
version before reading it as a wrong credential, and read the
`ExternalSecret`'s status rather than the Secret to check the
credential arrived — it carries the same answer and needs no right to
the value.
Commands: `just argo-github-app-secret`.
See [argocd-github](../../../docs/recipes/infra/argocd-github.md).

## Argo and Crossplane on a plane are upgraded by hand

Merge the change before upgrading the plane, and never expect a merged
change to reach a running plane on its own — a plane may be left on an
older chart than git describes, deliberately, and nothing detects it.
Change the version in both the boot chart and the composition and run
`just check-versions`, which fails on one without the other, and never
set `management.bootstrap: true` to make the composition
authoritative.
Build the values file from the composed `Release`, never by hand,
spelling the kind `release.helm.m.crossplane.io` — the short name
resolves to provider-helm's cluster-scoped `Release` and reports the
object as not found — and pin `--version` to the same object's
`chart.version`. Never omit `-f`, on any change: Helm replaces a
release's values with what it is given, so an upgrade without the file
resets them to the chart's defaults, and never upgrade Argo with a
values file that omits `extraObjects`. `--reuse-values` is for a
release with values and no drift to preserve. Render both chart
versions against the running values before merging a version change,
and read the release notes for the versions it crosses. Confirm
`management-plane` still exists before anything else on an Argo
upgrade; check every provider and function is healthy after a
Crossplane one, and never judge a composite while the core is
restarting. Join `cluster-admin` for the upgrade itself and leave
again — everything else here is a viewer's.
Commands: `just check-versions`.
See [argocd-upgrades](../../../docs/recipes/infra/argocd-upgrades.md) and
[crossplane-upgrades](../../../docs/recipes/infra/crossplane-upgrades.md).

## Every name derives from the code

Derive every composed name from `spec.code`, never from the composite's
own name, and carry the environment letter on anything scoped to one,
including the kinds the inventory does not list. Never bake an
environment name, a domain or a customer name into a resource name —
the code and the environment letter are the only identifiers a name
carries — and never invent a prefix where the inventory already has
one; add a kind to the inventory when you name one that is not there.
End a name with six hex characters where it cannot be reused after the
resource is deleted, checking what the kind actually does rather than
assuming and recording the answer. Never rename a project id, a folder
or a bucket in place: none supports it, the id is consumed and the
resource is rebuilt. Never put a realised suffix anywhere public — a
pull request's title or body, an issue, a comment, a review — and write
`xxxxxx` instead: `scripts/hooks/check-cloud-ids.sh` covers the tree
and the commit message from a hook and everything written outside git
from a workflow, and can hold a merge on a pull request, but on a
comment it is detection only, since the text is public the moment it is
posted. A name may take a qualifier where it would otherwise collide,
most often a region, and a folder or project handed to us already named
keeps the name its supplier chose.
See [cloud-naming](../../../docs/recipes/practices/cloud-naming.md).

## An installation has a code, and humans are read-only or break-glass

Give each installation a four-character code, chosen when it is created
and carried in its manifest, and derive every name from it with the
environment letter — `b` bootstrap, `c` common, `d` dev, `n` nonprod,
`p` prod — so nothing needs a lookup and a second installation cannot
collide with the first; the code is not descriptive, because a project
id's budget is real. A name does not repeat what its container already
says, nor carry a prefix the platform supplies: a cluster is
`<code>-<env>-<label>` with no kind prefix, since GKE prefixes `gke-`
itself, and a node pool is `np-<code>-<env>-<label>-primary` — never
`default`, which reads as GKE's own. Name a managed resource for what
it manages, so `kubectl get managed` and the console read the same, and
where GCP would allow a shorter name than the namespace does, the one
both accept wins in both — `crossplane.io/external-name` is for a name
Kubernetes cannot express, never for one that is merely tidier. The
seed project alone carries no code, since it belongs to no
installation; its identities are named per installation. Group names
hold only in an organisation we own, since a directory is never inside
a folder.

Inside the folder, automation owns everything: no human holds a write
role there, and what restrains automation is its own declaration —
`managementPolicies`, `deletionProtection`, liens — reviewable in a pull
request. Humans are read-only or break-glass, with no third category:
standing membership grants sight, and changing anything means joining a
normally empty group, doing the work and leaving. Assuming an
automation identity is a write capability and belongs in the second
category. Per installation there are four capabilities, named area then
relation — `platformViewer`, populated; `platformAdmin`, `clusterAdmin`
and `secretsAdmin`, empty — bound with predefined granular roles, never
Owner, Editor or Viewer, including `platformViewer`, which is assembled
from predefined viewer roles. An installation's capabilities are not an
instance's: `platformViewer` inherits into every project deliberately,
but Kubernetes and secret administration on one instance is granted on
that instance's own project, with the environment in the group name,
and expires with it — an instance's `secretsAdmin` is viewer plus
`secretVersionAdder`, so writing a secret and reading one back stay
separate rights. Capabilities are logical names; what each resolves to
is an IAM member string in the manifest's `access` mapping — a group, a
user, a `principalSet://` — and a capability the organisation declines
to provide is absent and binds nothing. Creating the principals is the
organisation's act and binding them inside the folder is ours. Above
the folder, consume and do not manage: the organisation, the billing
account, the parent and the identity holding rights in it are taken as
given.
See [ADR-0023](../../../docs/adr/0023-installation-naming-and-access.md).

## Name the shape, never the instance

Write how a thing is named and mask what identifies the one in front of
you: every number and every hex run — a name's suffix, an id, an
address, a version, a sha — as `xxxxxx`, `<folder-id>`, `<org-id>`,
`<project-number>`, `<version>`, `<sha>`. Mask while writing, with the
real value still in front of you, and mask a pasted error message and a
worked example the same way: both are text somebody else produced, and
both carry ids verbatim. Never use a real identifier as an example of a
masked one. Never rely on the check to catch one — it is deliberately
more permissive than this, so that it does not cry wolf, and it reports
afterwards. A public resolver or nameserver, and loopback, may be
named: they identify nobody, and a delegation cannot be documented
without them. Where a real value genuinely belongs, mark the line
`cloud-id-ok`, which makes it deliberate rather than missed.
See [cloud-identifiers](../../../docs/recipes/practices/cloud-identifiers.md).

## A plane builds its successor and swaps onto it

Replace the cluster a management plane runs on by having the plane
compose its successor and swapping onto it, never by deleting the
cluster the controller doing the work stands on. Give the successor a
cluster name and a composition slot of their own -- a reused slot keeps
the live object and ignores the new name, so the composite reports
`Synced` while nothing happens -- and never take this path where the
change keeps the cluster's name, since nothing can stand a second
cluster up under one name: a ForceNew field on its own is a delete and
an install. One riding along with a rename is free, since the successor
is created rather than altered, and is the only way the plane's cluster
acquires an immutable field it was built without. Install Crossplane and Argo onto the successor by hand,
Crossplane first, with the release name, namespace, chart version and
values the composed `Release`s carry and never without `-f`, since
`extraObjects` holds the Application that pulls everything else -- and
Argo in two passes where the cluster has never had it, the first with
`extraObjects` emptied, because the chart's CRDs are templates and helm
builds every object before applying any, so an `Application` cannot ride
in the release that installs its own kind. Read
the release names from each object's `crossplane.io/external-name`
rather than from its Kubernetes name.

Record the slot list and the external names before and diff them after,
writing both outside every repository since they are the estate's own
identifiers, compare the two planes field by field with `just
crossplane-drift` -- a field only the creating plane sets is one
late-initialisation filled there, which an adopting plane has no way to
learn and only the composition can supply -- and prove the instances
adopted as well as the plane before swapping, counting a `down`
instance's stopped servers as adopted since nothing about them can
reconcile until it is up: adoption is the whole procedure and nothing
else reports whether it happened. Never run a command against the
successor without checking `$NEXT` is set, since an unset one is the
current context -- the plane in charge -- and the command succeeds there
rather than failing. Scale the old plane's Crossplane core down before
its provider pods, and only once the successor holds the estate; never
delete the old composite to stop it, since its access bindings carry
`Delete`, including the one its Crossplane authenticates with. Keep the
cluster it replaced until the successor is trusted -- while it stands,
scaling it up and reverting the merge is the way back -- and never run
`just plane-ctx` before the swap, which renames whatever it fetches to
`MGMT_CTX`. No boot plane and no seed identity: everything a second
cluster in the management project needs, the platform identity already
holds inside the folder. Where no plane is running at all this is not
the procedure, since nothing is left to build a successor -- install
one, which adopts what survived.
Commands: `just crossplane-slots`, `just crossplane-external-names`,
`just crossplane-unready`, `just argo-apps-status`, `just plane-ctx`.
See [plane-rebuild-cluster](../../../docs/recipes/infra/plane-rebuild-cluster.md).

## A restore is proven by a key count, and a destructive state is self-limiting

Establish that data is actually lost or wrong before restoring:
scale-to-zero preserves the volumes, so `down` and `up` is not a
recovery scenario and needs no restore point. Name a generation with
every version and record the generation with it — a version alone
cannot say which container to read — and open a new generation after
recovering: `fdb.restore.backupName` is the source,
`fdb.backup.backupName` the destination, and after a recovery they
differ, so point the destination at a new generation before a rebuilt
cluster starts writing. Quiesce writes before taking a restore point
for a planned cluster rebuild, take it with `sop-fdb-version-at` rather
than modelling one, and delete promptly afterwards, so the point is
still current when the volumes go. Never use restore-to-latest where
the moment is known, and never set a `version` with no `backupName` —
the render refuses it, and the refusal is the feature. Read RPO off the
restorable window rather than off `snapshotPeriodSeconds`. Recover
Keycloak alongside FoundationDB, preserving user ids. Prove a restore
with `fdbrestore status` and a key count — never with the Job's exit
status, which against a populated destination succeeds by doing
nothing, never with the `FoundationDBRestore` resource, and never with
a bucket listing, which a wrong encryption key produces exactly like a
right one. Restore onto systems segregated from the source anywhere
other than a test environment: the damaged data is the only record of
what happened, and restoring over it commits before anything is
verified. Test the recovery procedure on a schedule and record what it
proved — a backup is not verified until it has been restored. Down and
rebuild are different kinds of state and must not share a word: never
give whatever empties a destination a name sharing a word with `down`,
or let it stay true across reconciles, never treat a cluster rebuild as
reversible, and never use it for an instance or installation rebuild,
since both take the database or the backups with them. Never let a
lifecycle rule delete inside FDB's prefixes — `fdbbackup expire` is the
only thing that may delete. A ForceNew field does not apply itself: the
composite reports `Synced` while nothing happens, so read
`LastAsyncOperation`. `fdb.restore` may stay set indefinitely after a
recovery, being a target rather than a mode, and a test environment may
restore in place.
Commands: `just sop-fdb-list-backups`, `just sop-fdb-version-at`, `just
sop-fdb-describe`, `just crossplane-unready`.
See [fdb-recovery](../../../docs/recipes/infra/fdb-recovery.md),
[instance-rebuild-cluster](../../../docs/recipes/infra/instance-rebuild-cluster.md)
and
[ADR-0026](../../../docs/adr/0026-recovering-data-and-the-states-that-do-it.md).

## A muted finding is a decision, recorded

Mute by resource, so the check still fails for anything the reasoning
does not cover, and record the reasoning in the recipe as well as in
the file's `Description`. Say what is deferred and why: a finding
nobody has explained is a finding nobody has decided. Never mute a
finding because fixing it is inconvenient — cost is a reason to defer
in the open, not to hide — and never treat a falling finding count as
the objective, since enabling an API in every project to satisfy a
per-project check that describes an organisation-wide capability
changes the report and nothing else. Never assume a scan ran as you: it
authenticates through ADC, which may be impersonating something else
entirely.
See [security-scanning](../../../docs/recipes/infra/security-scanning.md).

## A recipe fails loudly or not at all

Under `set -e`, `cmd && break`, `[[ test ]] && cmd` and a bare `VAR=$(cmd)`
whose command may fail each end the recipe rather than the line, so never
write them there, and never read an instant exit with no output as anything
other than `set -e` aborting before the recipe's first `echo`. Consume a
failure you expect instead — `if cmd; then break; fi`, or `|| true` where
emptiness is handled explicitly — and capture a command's output into a
variable before piping it, so a denial is not read as an empty result. Use
whatever the caller supplied and discover only what they did not: never add a
lookup for a value the caller already named, since discovery fails where an
argument would have worked. Pass the identity a recipe acts as rather than
discovering it, and stop rather than guessing where none is given. Declare an
overridable variable with `env_var_or_default`, and put a recipe in the
justfile for the domain it acts on, prefixed with that domain's name — the
prefix is what groups it in `just --list`, and the file is where somebody
looks for one they half-remember. Declare a constant in the file that reads
it, and in `vars.just` where more than one does or where it has to agree with
one already there; put a private helper with the domain it is about, whoever
calls it. Order a file as constants, private helpers, then recipes in the
order they are run, with the ad-hoc ones last, and give every recipe a
one-line comment naming its parameters and the values a fixed parameter takes
— that line is what `just --list` shows. Never comment a recipe body except
where a reader would otherwise make an edit that breaks it: why it is that way
belongs in the recipe under `docs/`.
See [justfile-recipes](../../../docs/recipes/practices/justfile-recipes.md).
