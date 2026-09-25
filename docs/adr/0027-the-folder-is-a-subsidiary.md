# 27. The folder is a subsidiary, and the plane is built in one

<!-- tessl-plugin: deployment -->

## Status

Accepted, and built for the folder.
[ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md) decided
that a GCP folder is what an installation is; this supersedes that
sentence and keeps everything else it decided. The kind exists,
`XManagementPlane` no longer composes a folder, and the one live folder
has been transferred.

What is not done is the word. Every recipe, the manifest kind and the
justfile recipes still say *installation*, and renaming them is
[the plan](../plan/subsidiary-split.md)'s remaining work.

## Context

ADR-0022 put the folder inside the plane's composite, because at the
time the plane was the only composite there was. ADR-0024 then took the
instances out of it, for a reason that applies to the folder just as
well and was not applied to it: *a composite is a unit of replacement,
so it is a blast radius.*

So `XManagementPlane` composed a folder that must never be deleted
alongside a cluster that is rebuilt routinely — the pairing
[crossplane-design](../recipes/infra/crossplane-design.md) forbids in as
many words: **never compose resources with different deletion criteria
into one kind, since deleting a kind deletes what it composed.** The
rule was written after the composite it describes.

The cost showed up as a branch rather than an outage. A platform team
handing over a folder is the ordinary case in any organisation that has
one, and the bootstrap recipe carried it as a conditional at step 5 of
eleven — *set `createFolder.folderId` rather than creating one* — with
the reader expected to hold "steps 1 to 3 are theirs" in their head for
the other six.

Two words were also doing one job. *Installation* is a nominalised verb
that ADR-0022 redefines as a place, which is why the recipe that
finishes one has no verb available: `installation-install` stutters, and
`deploy`, `provision` and `launch` each name what the bootstrap recipe
already does.

## Decision

### A folder is a subsidiary, and it is its own composite

`XSubsidiary` in `platform.repldriven.com` composes the folder, the
org-policy exemptions expressed on it, and the folder-scoped half of the
`access` mapping. It publishes `status.folderId` and nothing else.

The name is Google's. Its resource-hierarchy guide calls this option 2,
*hierarchy based on regions or subsidiaries* — a first level of
subsidiary folders beside a bootstrap folder, each operating
independently. That is the shape we use, and it is not a requirement of
the kind: a folder handed over under Development or Production in the
guide's best-practice hierarchy works identically, which is what
`parent` accepting `folders/` as well as `organizations/` is for.

Against *installation*, the word is a plain noun, so the verb slot is
free. Against *tenant*, *estate* and *landing zone*, it is the one the
guide already uses for this. The risk is that in core banking a
subsidiary is a legal entity, and `fldr-<code>` is not one — the
recipes say what it is, and nothing else in the system uses the word.

### The plane is built in a folder it does not own

`XManagementPlane` keeps the management project, the cluster, the
identities, Crossplane and Argo, and the project- and
service-account-scoped bindings. It composes no folder.

It finds the one it sits in by name, as `fldr-<code>` from its own
`spec.code`, never by referring to `XSubsidiary` — the rule ADR-0024
already set for instances, now applying to the plane too. Neither
composite knows about the other, which is what lets the folder come from
somewhere else entirely.

### The handover is the XR, in either direction

Where the folder is ours, `XSubsidiary` composes it. Where an
organisation hands one over, `folderId` adopts it. Both leave the same
object, so the recipe after it is unconditional. Which of the two, and
which folder, is stated in the installation's `EnvironmentConfig`
rather than in the manifest, so a handover edits one file and the
manifest carries the code alone.

**Amended.** This decision first said `parent` and `displayName` were
required in both modes, because the provider holds `Update` and either
left to a default is one it would write onto a folder somebody else
owns. The premise was right and the conclusion was not: what follows
from it is that an adopted folder must not be *managed*, rather than
that it must be *named*. A folder handed over is named and placed by
whoever owns it, so the composite reads both fields back and asserts
neither, and `folderId` suppresses them where it is set — precedence
rather than prohibition, since a rejected `EnvironmentConfig` would
stop the plane and every instance rather than this composite alone.

`Observe` and `LateInitialize` only, then, and not merely `Update`
withheld. The provider marks `displayName` required whenever the
resource is managed at all —
`!('*' in managementPolicies || 'Create' in managementPolicies ||
'Update' in managementPolicies) || has(forProvider.displayName)` — so a
folder we decline to name cannot carry `Create` either, and every apply
is rejected outright until it does not. Which is the right semantics
anyway: an adopted folder is not ours to create, and an external name
means `Create` would never fire.

Where an organisation runs no Crossplane at all, nothing instantiates
the kind and the XRD is read as the contract: what a folder must look
like before a plane can be built in it.

### Capabilities are bound here and created elsewhere

`XSubsidiary` composes the bindings and takes the principals as input,
through the `access` mapping
[ADR-0023](0023-installation-naming-and-access.md) defines. Creating a
group stays a directory act.

The reason is escalation. Creating a group needs a Cloud Identity admin
role, and Groups Admin is not scopable to a name prefix — an identity
that could mint `grp-gcp-<code>-platform-viewer@` could add itself to
`grp-gcp-org-admin@`, which carries Organization Administrator. That
would put an escalation path into the one identity the whole bootstrap
impersonates.

The quota argument the recipes give for the same conclusion is weaker
than it reads: a Cloud Identity write wants a quota project, and none
exists at organisation-foundation time — but the seed project exists by
the time a subsidiary is created. Escalation is the reason that holds at
both moments.

Only `platformViewer` and `clusterAdmin` bind on the folder.
`platformAdmin` binds on the platform service account and `secretsAdmin`
on the management project, and both stay with the plane, which composes
the things they bind on.

## Consequences

**A transfer of a live folder has a window in it, and the window is
survivable.** The two composites are declared in two repositories behind
two Argo Applications, so nothing makes the move atomic: the parent
releases the resources and the child adopts them back by external name.
Withholding `Delete` first is what turns that from a revocation into an
orphaning — done as its own merge, per
[crossplane-live](../recipes/infra/crossplane-live.md), and the seven
capability bindings were never absent from GCP at any point.

**A correct adoption is one where nothing happens.** The proof is a
folder count under the parent, not a green composite: GCP permits two
folders with the same display name, so a `folderId` omitted composes a
second one and everything reports healthy.

**The plane's `status.folderId` is now derived rather than composed.**
It is published from the management project's resolved folder reference.
The field means what it always meant, and nothing that read it changed.

**Reference resolution is what makes the window safe, and it is
implicit.** Every project in the folder resolves `folderIdRef` once and
keeps the answer, so none re-resolves while the managed resource is
absent. That is a default, not a declaration, and it is the assumption
the transfer rests on.

**A schema cannot lose a field in one merge.** The CRD prunes, so
removing a property while a manifest still sets it strips the field
from the object and leaves Argo diffing for ever. `createFolder` came
out in three, across two repositories: made optional, dropped from the
manifest, then deleted — with the renderer and its template going in
the last, since a template that emits a field the schema has lost
produces exactly the same diff for the next installation.

**One kind now spans two suppliers.** An `XSubsidiary` may be reconciled
by our plane or by somebody else's, and only the second case has been
designed rather than run. Nothing verifies that a folder handed over
actually meets the contract; `just seed-preflight` is where that
check belongs.

## Future

**The word has not moved.** *Installation* is still in every recipe, the
manifest kind, the justfile recipes and the private manifests repo
layout. The renames are mechanical and consequential on this;
[the plan](../plan/subsidiary-split.md) lists them.

**Whether a recipe survives the split.** What is today
`plane-install` supplies four things, and each may belong
elsewhere once the layers separate: the Argo credential to the boot,
since without it the plane reconciles from nothing while reporting
healthy; the environment config and the recovery project to the
subsidiary; the zone to the DNS recipes. It may dissolve rather than be
renamed.

**Org-policy exemptions are named here and not composed.** ADR-0022 made
the folder the one place an exemption is expressed. `XSubsidiary` is
where they belong, and it composes none yet.

## References

- [ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md) — the
  folder as an installation, whose central sentence this supersedes
- [ADR-0023](0023-installation-naming-and-access.md) — the code, the
  capabilities, and the `access` mapping this keeps whole
- [ADR-0024](0024-instances-are-their-own-composites.md) — the instance
  as its own composite, and naming across composites rather than
  referring
- [crossplane-design](../recipes/infra/crossplane-design.md) — the
  deletion-criteria rule the previous shape broke
- [crossplane-live](../recipes/infra/crossplane-live.md) — what a
  transfer between composites costs, and the order it takes
- [subsidiary-split](../plan/subsidiary-split.md) — the sequence, and
  what is left
- [Deciding a resource hierarchy](https://docs.cloud.google.com/architecture/landing-zones/decide-resource-hierarchy#option2)
  — option 2, and the best-practice hierarchy beside it
