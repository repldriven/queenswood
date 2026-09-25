# Plan: split the folder from the plane, and name what is left

## Context

[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
decided that a GCP folder is what an installation is, and
[ADR-0024](../adr/0024-instances-are-their-own-composites.md) that an
instance is its own composite beside `XManagementPlane`. Between them
the folder ended up inside the plane's composite, and it does not
belong there.

The shape is Google's option 2, *"hierarchy based on regions or
subsidiaries"* — a first level of subsidiary folders beside a bootstrap
folder, each operating independently. See
[decide-resource-hierarchy](https://docs.cloud.google.com/architecture/landing-zones/decide-resource-hierarchy#option2).
The kind does not require it: it needs a folder and nothing more, so a
folder handed over under Development or Production in the guide's
[best-practice hierarchy](https://docs.cloud.google.com/architecture/landing-zones/decide-resource-hierarchy#best_practices_for_resource_hierarchy)
works identically — which is what `parent` taking `folders/` as well as
`organizations/` is for.

`XSubsidiary` is written and renders; nothing else here is. It needs an
ADR superseding ADR-0022's central decision before the live extraction,
because it retires the word *installation* along with the shape.

## Before

```
Organisation              manual
  prj-b-seed              manual + seed identity
  fldr-<code>          ┐
  org-policy exemption |
  folder IAM           |  XManagementPlane   <- spans two lifetimes
  prj-<code>-c-mgmt    |
  cluster, Argo, XP    |
  identities, proj IAM ┘
  prj-<code>-c-recovery
  prj-<code>-<env>-<label>  XQueenswoodInstance
```

The folder is never deleted; the cluster is rebuilt routinely. One
composite holds both, which is what
[crossplane-design](../recipes/infra/crossplane-design.md) forbids:
never compose resources with different deletion criteria into one kind,
since deleting a kind deletes what it composed.

## After

```
Organisation                        manual: directory, billing, org policy
  prj-b-seed                        manual + seed identity, org-scoped, once
==================================  org | subsidiary
  fldr-<code>                       XSubsidiary
  org-policy exemptions               spec: code, parent, access
  folder IAM from access              status: folderId
==================================  subsidiary | plane
  prj-<code>-c-mgmt                 XManagementPlane
  cluster, Crossplane, Argo           spec: folderId, access
  platform/argo/secrets identities
  project IAM from access
==================================  plane | unit
  prj-<code>-<env>-<label>          XQueenswoodInstance
```

The handoff boundary is `XSubsidiary`'s status: a folder with its
capabilities already bound.

## What the split buys

**A branch becomes a boundary.** The bootstrap recipe carries a
conditional — where a platform team hands you a folder, steps 1 to 3
are theirs and you set `createFolder.folderId` rather than creating
one. Split, that case is somebody else having run the same composite
and given you its `folderId`. One artefact, two suppliers, no branch.

**The seed's folder rights close sooner.** Today the seed identity
holds `folderCreator`, `folderIamAdmin` and `projectCreator` across the
whole run. Split, the folder rights are wanted for one composite and
the project rights for the next, so the organisation-scoped grant can
be revoked as soon as the folder exists rather than at the end.

**The blast radius shrinks.** A composite is a unit of replacement, and
the plane's currently contains the one object nothing may delete.

## Groups are bound here and created elsewhere

`XSubsidiary` composes the bindings and takes the principals as input,
through the same `access` mapping ADR-0023 defines. Creating a group
stays a directory act.

The reason is escalation rather than convenience. Creating a group
needs a Cloud Identity admin role, and Groups Admin is not scopable to
a name prefix — an identity that could mint
`grp-gcp-<code>-platform-viewer@` could add itself to
`grp-gcp-org-admin@`, which carries Organization Administrator. That
would put an escalation path into the one identity the whole bootstrap
impersonates.

[organisation-foundation](../recipes/infra/organisation-foundation.md)
currently gives a different reason — that a Cloud Identity write needs
a quota project and none exists. That is true when it is written and
false by the time a subsidiary is created, since `prj-b-seed` exists by
then. Correct it whether or not this plan proceeds.

`access` keeps taking whole IAM member strings, so an established
organisation answers each capability with its own group, a user, or a
`principalSet://`.

## The XR is the handover

`XSubsidiary` is what crosses the boundary, in both directions:

- **Ours** — no `folderId`; it composes `fldr-<code>` and the bindings.
- **Handed a folder** — `folderId` set; it adopts and records the one
  you were given, keeping their `displayName` and `parent`.

Both leave the same object for `plane-install` to read, so
the branch
that today sits at step 5 of an eleven-step bootstrap becomes one field
in a manifest. It is the convention the installation manifest already
uses — *"Supply `management.projectId` always and
`createFolder.folderId` wherever the folder already exists"* — moved
onto the composite it belongs to, and a spec field rather than a
`managementPolicies` difference so the mode is reviewable in the diff.

`parent` and `displayName` are required in both modes. The provider
holds `Update`, so either left to a default is one it would write onto
a folder somebody else owns — the first bug the rendered composition
turned up.

This also settles where the folder id lives. Only `XManagementPlane`
needs it: ADR-0024 already has an instance *"naming the composed
`Folder` as `fldr-<code>` from the instance's own `spec.code`, never by
referring to the plane's composite"*. So the `EnvironmentConfig` keeps
what it holds today and never carries the subsidiary's identity. One
object owns that, and no second copy can disagree with it.

## What is built

- [xsubsidiary-xrd.yml](/infra/platform/crossplane-xrds/xsubsidiary-xrd.yml)
  — `code`, `parent`, `displayName`, optional `folderId`, and an
  `access` mapping holding only the two capabilities that bind on a
  folder. `platformAdmin` binds on a service account and `secretsAdmin`
  on a project, neither of which exists until a plane is built there.
- [xsubsidiary-composition.yml](/infra/platform/crossplane-xrds/xsubsidiary-composition.yml)
  — the `Folder`, lifted from `XManagementPlane` with its
  `lifecycleState` readiness check, and the folder bindings through
  `function-go-templating`. `Delete` withheld from the folder in every
  mode; carried on a binding, so a principal dropped from `access`
  loses it in GCP.

`crossplane render` gives eight resources for a full mapping — six
`platformViewer` roles, one `clusterAdmin`, and the folder — and the
adopt path renders their id, their name and their parent.

**Not yet extracted from `XManagementPlane`.** Both kinds would compose
`fldr-<code>`, and two composites claiming one managed resource makes
every apply fail. Loading an XRD with no composites of its kind is a
known-safe state, so the file lands ahead of the move; the move itself
is [crossplane-live](../recipes/infra/crossplane-live.md)'s two-merge
transfer, and it is step 2 below.

## The word

*Installation* is a nominalised verb that ADR-0022 redefines as a
place. That is why there is no verb left for the recipe that finishes
one — `installation-install` stutters, and `deploy`, `provision` and
`launch` each collide with what the bootstrap recipe already does.

A plain noun frees the verb slot. `subsidiary` carries the sense of an
independent, identically shaped unit inside an organisation, which is
what a folder is here. Against it: in core banking a subsidiary is a
legal entity, and `fldr-qw01` could be read as one. Worth weighing
`tenant`, `estate`, `landing-zone` — the industry term for exactly this
— and `cell` before committing.

**Done.** The names, replacing the `queenswood-` prefix that says
nothing in a repository where everything is Queenswood:

```
organisation-foundation     the org, billing, directory groups  ┐ once per
organisation-bootstrap      prj-b-seed and the seed identity    ┘ organisation
──────────────────────────  the installation's own, from here ────────────
contract-install            who holds what, which folder, what pays
boundary-install            the folder, and what binds on it
plane-install    the project, cluster, Crossplane, Argo
instance-deploy             one environment's project and the bank on it
instance-rebuild-cluster
up-and-running              the order they go in
```

`contract-install` comes **before** `boundary-install`: the folder
composite binds the capabilities, and IAM rejects a binding to a
principal that does not exist.

The seam moved somewhere better. It was a branch inside bootstrap —
*"where a platform team hands you a folder, steps 1 to 3 are theirs"* —
and is now a field in the contract: `folder.folderId` adopts,
`folder.parent` with `folder.displayName` composes. `up-and-running`'s
two paths are "start at 1" or "start at 2", with no recipe that is read
rather than followed.

`subsidiary-install` does not survive. Its six steps each belonged
somewhere else: the environment render is the contract, the Argo
credential and the zone finish the management plane, and the domain and
its delegation were already `gcp-dns` and `gcp-dns-delegation`.

**Done, ahead of the rest.** `cloud-dns` and `cloud-dns-delegation`
became `gcp-dns` and `gcp-dns-delegation`, since `gcp-iam` and the two
of them named the same kind of thing under two prefixes; and
`cloud-naming` and `cloud-identifiers` moved to `practices/`, being
writing conventions rather than infrastructure procedures. Both keep
their names and their `deployment` label there — which plugin a naming
convention belongs to is a separate question from which directory the
file sits in, and moving the file settles only the second.

## A generated value is read, not transcribed

The plane composes things an instance must name: the Argo service
account it grants `container.admin` to on its own project, and the
recovery project whose suffix its backups bucket borrows.

> The plane's own comment on that identity says the instance grants it
> `container.developer` — *"it reaches Kubernetes objects inside a
> cluster and cannot create or delete the cluster itself"* — and the
> instance binds `container.admin`. One of the two is wrong, and the
> comment is the one making the least-privilege argument. Worth
> settling separately from any of this. Both reach
the instance today by being written into `environment.yml` — rendered
by a recipe that discovers them from live GCP, and committed.

That is the wrong direction. A plane that creates a resource and then
requires somebody to transcribe its identity into a file, so that
another composite can be told about it, produces authoring mistakes
rather than preventing them. The value already exists on the plane; the
instance should read it.

### What already works, and where it stops

Anything an instance *targets* goes by reference to a deterministic
Kubernetes name, and needs no file: `folderIdRef` to `fldr-<code>`,
`projectRef`, `serviceAccountIdRef`, `managedZoneRef`. That is the
mechanism ADR-0024 settled on for the folder.

It stops at two places:

- **A `member` string.** `ProjectIAMMember.forProvider` is `condition,
  member, project, projectRef, projectSelector, role` — read off the
  live CRD. `member` has no `Ref` and no `Selector`, because it may be
  a user, a group, a domain or a `principalSet://`, none of which is a
  Crossplane object.
- **A resource name.** The backups bucket is
  `bkt-<name>-backups-<suffix>`, where the suffix is split off the
  recovery project's id so that a rebuilt project does not rename the
  bucket. A name is a string, not a reference.

### The fix

Crossplane satisfies a pipeline step's requirements before the step
runs, so the instance declares what it needs and no function fetches
anything:

```yaml
- step: recovery
  functionRef:
    name: function-go-templating
  requirements:
    requiredResources:
      - requirementName: recoveryProject
        apiVersion: cloudplatform.gcp.m.upbound.io/v1beta1
        kind: Project
        namespace: crossplane-system
        matchLabels:
          platform.repldriven.com/component: recovery-project
```

The step reads it back under `.extraResources`, in that same step, and
spells the member from what it read. A second requirement on the Argo
step answers the identity.

By label rather than by name, and the reason is not preference. The
match is `name` or `labels` and never an annotation — the `oneof` in
Crossplane's own `ResourceSelector` — so
`crossplane.io/composition-resource-name`, which already carries
exactly the value wanted on all 121 managed resources, is unreachable.
Both fields are static literals besides, so neither can carry the code.
The plane therefore publishes the slot name as a label, and the
selector needs no installation in it: a plane serves one, so what it
composes is a singleton and matching the cluster is matching the one.

`function-extra-resources` is not installed. It is a fifth function
whose `Reference` has the same static-literal limitation, and
`function-go-templating` can request extra resources with a templated
name where a name is wanted at all. Neither is needed.

**Both keys leave the file.** `argoServiceAccount` because nothing
reads it. `recoveryProjectId` because the plane is its only reader once
the instance reads the id off the composed `Project` — and a
single-reader value in a shared config is indirection, not sharing. It
moves to `installation.yml`'s `spec.recovery.projectId`, which the
composition already prefers, beside `management.projectId`: the two ids
consumed permanently, minted in one call, recorded in one file. That
also settles the question this section used to defer.

The XRD's own description argued *for* the `EnvironmentConfig` — *"two
copies of one id is two places to disagree"*. That argument holds while
two composites read it; with one reader there is no second copy to
avoid, and the override becomes the place.

### The order it goes in

Inside out: prove the consumer can live without the courier before
removing it.

1. **`installation.yml` carries `spec.recovery.projectId`.** A no-op:
   the composition already prefers it and the value is the one the
   environment holds. It has to be first — with the manifest silent and
   the environment stripped, the plane composes no recovery project.
2. **The plane publishes the labels.** Its own merge, reaching GCP
   first. The instance selects on them, so an instance reconciling
   before the plane has relabelled finds nothing and drops what depends
   on it — orphaned rather than deleted, but churn on a live instance.
3. **The instance reads both values, and the plane stops consulting the
   environment.** Provable before merging — `crossplane render
   --extra-resources` against stubbed resources, diffed resource for
   resource against today's output.
4. **`environment.yml` sheds both keys**, which step 3 made dead.

### What it costs

- A label the plane publishes and the instance selects on, which is a
  contract between two composites where there was none — and one that
  holds only while a plane serves one installation.
- A different failure. The environment patch is deliberately not
  `Required` — *"a merge here before the key exists there would fail
  every instance rather than this one binding"* — and an extra resource
  that cannot be found may fail the whole pipeline step. The soft
  failure was chosen; whatever replaces it has to be chosen too.
- A line ADR-0024 drew. It says an instance finds the folder *"never by
  referring to the plane's composite, so neither composite knows about
  the other"*. Reading a resource the plane composed, by name, is the
  same distinction that already makes `folderIdRef` acceptable — but it
  is the ADR's line, and it should be drawn deliberately rather than
  crossed quietly.

### What `environment.yml` is left holding

```
manifestRepoURL      static config
billingAccountId     an account that pre-exists
access               who holds which capability
```

All authored, none generated, and each read by more than one composite
— which is what the file is for, and what tells it from a manifest. A
value with a single reader belongs to its reader, which is why
`recoveryProjectId` went the other way.

`access` is the same argument from the other side: one mapping, written
three times today, with the subsidiary, the plane and every instance
each binding the subset it can. Stated here, a new instance restates
none of it. Each composite still carries `spec.access` as a
per-capability override, so an instance may name a different
`secretsAdmin` without the file changing for anyone else.

The folder id stays in `subsidiary.yml`, for the reason
`recoveryProjectId` moved there: one reader, which is `XSubsidiary`
itself. Neither the plane nor the instance takes a folder id at all —
both resolve the composed object by its Kubernetes name, `fldr-<code>`,
derived from `spec.code` in the composed and the adopted mode alike, so
the id is never spelled twice and `XSubsidiary` is where it is
authoritative. What it publishes as `status.folderId` is for people and
tooling, not for another composite.

That reference holds because the kind is always instantiated: composed
where the folder is ours, adopted where an organisation hands one over.
ADR-0027's *"where an organisation runs no Crossplane, nothing
instantiates the kind"* is about the organisation supplying the folder,
and says nothing about the plane receiving it — which runs the kind
either way. Binding is ours in both modes too: a supplier is not
expected to bind the capabilities, and one that does costs nothing,
since the bindings are declared rather than added and reconcile to the
same state.

## Answered: `subsidiary-install` did not survive

Its four parts each belonged somewhere else, as suspected, and one more
turned up when the file was read again:

- **The Argo credential** is part of the boot. Without it the plane
  reconciles from nothing while reporting healthy.
- **The environment config** is the contract, and moved earlier rather
  than later: it is agreed before the boundary is built, since the
  boundary reads two of its four keys.
- **The recovery project** is composed by the plane from that contract.
- **The zone and its delegation** are `gcp-dns` and
  `gcp-dns-delegation`, pointed at rather than restated.
- **The readiness check** is the management plane's last step.

So the recipe was deleted rather than renamed.

## Ordering

0. **Done** — `XSubsidiary`'s XRD and Composition.
1. **Done** —
   [ADR-0027](../adr/0027-the-folder-is-a-subsidiary.md), superseding
   ADR-0022's folder-is-an-installation decision.
2. **Done** — the folder extracted from `XManagementPlane` and adopted
   by `XSubsidiary`, in the order
   [crossplane-live](../recipes/infra/crossplane-live.md) requires:
   `Delete` withheld in a merge of its own, then the transfer. No
   binding left GCP at any point. `createFolder` came out of the XRD,
   the manifest and the renderer after it.
3. **`plane-install` no longer describes what happens.** Its
   step 5 renders an installation manifest with no folder and step 6
   says the apply reports one. Nothing renders or applies a
   `subsidiary.yml`. A second installation following it would get a
   plane with nowhere to put its projects. The fix is two commands in
   step 5, one sentence in step 6, and `boot-mgmt-apply` reading
   two files — no new step.
4. **Reading a generated value rather than transcribing it**,
   independent of the rest and worth doing on its own merits. Four
   merges across two repositories, inside out — see the section above.
5. `access` into `environment.yml`, once 4 has emptied that file of
   generated values. Four merges, the same way round:
   the environment carries the mapping while nothing reads it; the three
   compositions read it in preference to their own field, which changes
   nothing while the manifests still set theirs; the manifests shed it;
   and the bootstrap learns to apply the file. That last one is not
   optional — `boot-mgmt-apply` applies `installation.yml` and
   nothing else, and `environment.yml` reaches a cluster only through
   Argo, which is not running until the plane is up. Without it a fresh
   installation has no bindings at all until the first sync,
   `clusterAdmin` included, which is how a person reaches the cluster
   once the seed impersonation is revoked.
   `XSubsidiary` needs an environment step of its own, resolving
   `Optional`: it is applied by the boot cluster before any
   `EnvironmentConfig` exists, and `Required` would fail the pipeline,
   which on that kind means no folder.
6. **Done** — the recipe renames, and the two splits they were waiting
   on: the seed identity out of the bootstrap, and the installation
   recipe dissolved into the contract and the management plane.

## References

- [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — the folder as an installation, which this supersedes
- [ADR-0023](../adr/0023-installation-naming-and-access.md) — the code,
  the capabilities, and the `access` mapping this keeps
- [ADR-0024](../adr/0024-instances-are-their-own-composites.md) — the
  instance as its own composite, which this follows for the folder
- [composite-catalogue](composite-catalogue.md) — the extractions out
  of `XQueenswoodInstance`, which this is the same move for the plane
- [crossplane-design](../recipes/infra/crossplane-design.md) — the
  deletion-criteria rule the current shape breaks
