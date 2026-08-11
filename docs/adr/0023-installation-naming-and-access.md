# 23. Installation naming and access

<!-- tessl-plugin: deployment -->

## Status

Accepted. Extends
[ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md), which
established the folder as an installation but said nothing about what
things are called inside it or who may touch them.

## Context

The first installation was built by answering each question as it
arrived. A permission was missing, so it was granted; a name was needed,
so one was invented. The result works and cannot be explained: nothing
says why the bootstrap identity holds the roles it holds, why one group
is empty and another is not, or what a project should be called.

Two failures show the cost. Rights were withheld from the automation
that owns the folder in order to make the folder undeletable — which
fights the very model that makes the folder safe, and failed anyway,
because GCP grants a folder's creator administrative rights on it. And
names were chosen per resource, so `queenswood-mgmt-xxxxxx` spends 16 of
the 30 characters a project id allows before saying anything specific.

The Google Cloud security foundations guide answers both, and its
answers are consistent with each other. Two lines carry most of it:

> Groups and users should not have any permissions to alter the
> foundation components laid out by the deployment pipeline unless they
> are one of the privileged identities.

> Eliminate or at least severely limit the use of primitive roles in
> your Google Cloud organization because the wide scope of permissions
> inherent in these roles goes against the principles of least
> privilege.

There is a second audience for all of this. An installation need not be
created in an organisation we control, and the likelier case is that we
are handed a folder and an identity with administrative rights on it,
with everything above belonging to somebody else. Nothing about access
can assume we may create a group, choose what it is called, or read an
organisation's IAM policy.

## Decision

### An installation has a code, and the code is in every name

Each installation takes a four-character code — `qw01`, `qw02` — chosen
when it is created and carried in its manifest. Names derive from it, so
nothing needs a lookup to place a resource, and a second installation
cannot collide with the first.

The code is not descriptive on purpose. A project id may be 30
characters, and the guide's own example spends 27, so the budget is
real: a code plus a one-letter environment leaves room for a label and a
uniqueness suffix, where a spelled-out name does not.

### Names follow the guide's scheme

Environment codes are the guide's: `b` bootstrap, `c` common, `d` dev,
`n` nonprod, `p` prod.

- folder — `fldr-qw01`
- project — `prj-qw01-<env>-<label>-<suffix>`, so `prj-qw01-c-mgmt-xxxxxx`
  for the management project and `prj-qw01-d-xxxxxx` for a dev instance
- VPC — `vpc-qw01-c-mgmt`, subnet — `sb-qw01-c-mgmt-euw2`
- service account — `sa-qw01-platform`, `sa-qw01-boot`
- group — `grp-gcp-<label>` at the organisation,
  `grp-gcp-qw01-<label>` for an installation
- bucket — `bkt-qw01-<label>`
- custom role — `rl_<function>`, underscored: GCP rejects a hyphen in a
  custom role id

Two kinds the guide does not cover take the same shape, environment
segment included: GKE cluster `gke-qw01-c-mgmt`, node pool
`np-qw01-c-mgmt`.

One project carries no code, because it belongs to no installation. The
identity that creates a folder must exist before the folder does, and
one such project serves the whole organisation — the guide calls it the
seed, so `prj-b-seed-<suffix>`. The identities inside it are named per
installation, `sa-qw01-boot`, because each creates a particular one.
Where the organisation is not ours there is no seed project at all: the
identity is handed to us.

The suffix on a project is six hex characters rather than the guide's
five-digit number, because that is what generates a globally unique id
with the least ceremony. Everything else is as published.

Every name here is ours to choose because every resource it names lives
inside the folder. The group names are the exception: a directory is
never inside a folder, so they hold only in an organisation we own.

### Kubernetes names mirror GCP names

A managed resource is named for what it manages: `fldr-qw01`,
`prj-qw01-c-mgmt`, `gke-qw01-mgmt`. So `kubectl get managed` and the
Cloud Console read the same, which is what someone debugging needs. It
costs explicit patches where a single format string would otherwise do.

### Inside the zone, automation owns everything

The folder is the boundary. Everything inside it is created and changed
by automation — the bootstrap identity until the management plane
exists, the platform identity afterwards. No human holds a write role
inside the folder, because there is nothing a human should be writing
that the manifest should not.

It follows that automation holds broad rights inside the zone, including
the administrative rights GCP grants a folder's creator. That is not a
leak to be closed. What restrains automation is its own declaration —
`managementPolicies` without `Delete`, `deletionProtection`, liens —
which is reviewable in a pull request, where an IAM binding scattered
across a policy is not.

### Humans are read-only or break-glass

There is no third category. Standing membership grants sight; changing
anything means joining a group that is normally empty, doing the work,
and leaving. Assuming an automation identity is a write capability and
belongs in the second category, not the first.

In an organisation we own, one set of groups bound at the organisation
node. In one we do not, these are its own business under its own names,
and an installation never refers to them:

- `grp-gcp-org-admin` — organisation IAM. Empty. Abbreviated as the
  guide abbreviates it.
- `grp-gcp-folder-admin` — the boundary itself. Empty. Organization
  Administrator does not carry `resourcemanager.folders.delete`.
- `grp-gcp-billing-admin` — the billing account. Empty, with one direct
  human administrator beside it, because a billing account has no
  recovery path outside its own policy.
- `grp-gcp-security-reviewer` — read-only IAM everywhere. Populated:
  auditing access must never require the power to change it.

Per installation, four capabilities, each named area then relation as
the guide's own groups are — `billing-viewer`, `secrets-admin` — so the
name says what holding it lets you do. A capability named for a job
(`operator`) or for the thing it acts on (`automation`) invites the
reader to assume more, or less, than the binding gives:

- **platformViewer** — read inside the folder. Populated.
- **platformAdmin** — may assume the identity that runs the
  installation. That identity holds the administrative rights GCP grants
  a folder's creator, so this is administration of the whole folder,
  arrived at by impersonation rather than by a standing role. Empty.
- **clusterAdmin** — Kubernetes administration. Empty.
- **secretsAdmin** — secret contents. Empty.

Roles bound to these are predefined and granular. Primitive roles —
Owner, Editor, Viewer — are not used, including for `platformViewer`,
which despite the name is assembled from predefined viewer roles rather
than granted `roles/viewer`.

### Capabilities are logical, principals are configuration

The four are logical names. What each resolves to is per-installation
configuration, mapped in the manifest:

```yaml
access:
  platformViewer: [group:grp-gcp-qw01-platform-viewer@queenswood.io]
  platformAdmin: [group:grp-gcp-qw01-platform-admin@queenswood.io]
  clusterAdmin: [group:grp-gcp-qw01-cluster-admin@queenswood.io]
  secretsAdmin: [group:grp-gcp-qw01-secrets-admin@queenswood.io]
```

A value is an IAM member string rather than a group name, because a
capability may be answered by a group, by a user in a small
organisation, or by a `principalSet://` from an external identity
provider — and by more than one of them. A capability the organisation
declines to provide is simply absent, and nothing is bound for it: an
installation stands up correctly with no human bindings at all.

Creating the principals is the organisation's act and binding them
inside the folder is ours. That division is the point. The directory
stays theirs, the folder stays ours, and asking for a principal per
capability shows them precisely what is being requested — which
requesting a role on an existing group would not.

Where we own the organisation the mapping is one-to-one and reads as
pure ceremony. It earns its place in the case where the names are not
ours to choose.

### Above the folder we consume, we do not manage

An installation needs an organisation, a billing account, a parent to
create in, and an identity holding administrative rights inside it. It
takes those as given and forms no opinion about them.
Domain-wide default grants, other folders, and organisation policy
outside the installation's own folder belong to whoever owns the
organisation, who may not be us.

## Consequences

**The first installation is rebuilt, and two project ids are
abandoned.** `queenswood-mgmt-xxxxxx` and `queenswood-bootstrap-xxxxxx`
cannot be renamed, and a project id is consumed permanently. Two ids is
a cheap price for not having to explain which names are legacy.

**Groups are recreated rather than renamed.** A group's email is its
identity in Workspace, so adopting the scheme means create, rebind,
delete.

**Codes are opaque, and descriptions carry the meaning.** `fldr-qw01`
tells a console browser nothing, so the folder's description says
"Queenswood installation 01" and projects carry labels. The name is for
machines and greppability; the description is for people. The expansion
is written once, on the folder, and the code is used bare everywhere
else — repeated in each group description it would be as many places to
correct.

**A group's display name is its address, and its description names no
roles.** One string then appears on every screen and in every binding,
and the console sorts by scope rather than by prose. The description
says what holding the capability lets you do and whether it is standing
or joined; which roles implement it stays in the composition, where a
change is reviewed. A role named in a directory field is a claim nobody
will come back to correct.

**A per-installation capability set multiplies with installations.**
Four principals each, which where we own the organisation means four
groups to create. That is the cost of bindings that name their scope,
and the alternative — one `cluster-admin` group bound on every folder —
grants across installations that are supposed to share nothing.

**A capability is only as separate as its mapping.** Two may resolve to
one principal, and then joining for one grants the other: `cluster-admin`
and `secret-admin` collapsed onto a single group makes their separation
fiction. In an organisation we do not own we cannot prevent that. What
we get is that the collapse is written in the manifest and read in a
pull request, rather than emerging from a policy nobody opens.

**The manifest records what we granted, not what is held.** An
organisation may bind its own principals above the folder, and we may
have no rights to read that policy. The mapping is the whole truth about
our own bindings and no evidence at all about anyone else's.

**The operator's read access is more work to assemble.** `roles/viewer`
would have been one binding; predefined viewer roles are several, and
the set grows as the installation gains resource types.

## References

- [Cloud security foundations guide](https://services.google.com/fh/files/misc/google-cloud-security-foundations-guide.pdf)
  — the August 2020 whitepaper this follows. The live URL now serves a
  stub; the intact editions are in the Wayback Machine.
- [ADR-0022](0022-cloud-foundation-and-environment-lifecycle.md) — the
  folder as an installation, and the lifecycle around it.

## Future

Whether instances get their own capabilities — a `p-operator` against
the production instance alone — is left open. It follows the same shape
and should wait until an instance has someone to grant it to.
