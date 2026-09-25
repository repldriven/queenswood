# An installation's contract

<!-- tessl-plugin: deployment -->

## Status

**Verified.** One installation's groups were created this way.

## Problem

You want to write an installation's contract.

## Solution

### Prerequisites

- An organisation, from
  [organisation-foundation](organisation-foundation.md), or an established
  one.
- The installation's four-character code, chosen now — see
  [cloud-naming](../practices/cloud-naming.md).
- An address on your own domain for each person who will operate the
  installation, or the means to make one.
- The capability each step names. Ours is a Google group; yours may differ.

`qw01` stands in for the code below.

### 1. Create them

**As a super admin.**

In `admin.google.com` under **Directory, then Groups**: Restricted
before Only invited users, and no owner or manager. One group per
capability.

- **`grp-gcp-qw01-platform-viewer@`** — Populated. *"Reads qw01 and
  everything inside it, and writes nothing. Populated: this is
  day-to-day operation."*
- **`grp-gcp-qw01-platform-admin@`** — Empty. *"Assumes the identity that
  runs qw01, which administers all of it. Break-glass: join for the
  task, then leave."*
- **`grp-gcp-qw01-cluster-admin@`** — Empty. *"Administers qw01's
  Kubernetes clusters directly. Break-glass: join for the task, then
  leave. Acting on a cluster by hand bypasses whatever reconciles it."*
- **`grp-gcp-qw01-secrets-admin@`** — Empty. *"Reads and manages qw01's
  secrets. Break-glass: join for the task, then leave. Handling secret
  contents is a different job from running the infrastructure that
  holds them."*

### 2. Bind the one that reaches the organisation

**As an org admin.** Ours is `grp-gcp-org-admin@` — join for this
step, then leave.

```bash
gcloud config unset project
just gcp-groups-bind-installation
```

`grp-gcp-qw01-platform-viewer@` takes Browser at the organisation.
Nothing else here is bound there.

### 3. Add the people who operate it

**As a super admin.**

**Directory**, then **Users**, for anybody without an account on the
domain yet. Then add each to `grp-gcp-qw01-platform-viewer@` and to
nothing else — not a break-glass group, and not the billing group.

### 4. Write the contract

**As the installation's platform viewer, from here on.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just queenswood-environment-manifest <parent-or-blank> "" <folder-id> \
  "" "" <domain>
```

Under `data`: the access mapping naming the principals above, the
folder, the billing account, the manifests repository, the region the
installation runs in, said three ways, and the domain this installation
answers under.

That domain is what an organisation delegates to us, never the apex
above it — our own apex where the domain is ours, and a subdomain of
somebody else's where it is not. Each environment then answers on a
name below it, in a zone this installation composes. Leave it out and
the installation answers on no name, which is what one stands up as
before a domain is delegated to it. See
[ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md).

> [!WARNING]
> Re-render as often as you like until it is committed. Once it is, that
> file may be the only record of an id GCP has consumed — the recovery
> project's — and the recipe refuses rather than minting a second.

Read it, then commit and merge it.

## Failures

**An `access` mapping the manifest accepts and IAM refuses.** A group
named there does not exist. IAM rejects the binding rather than the
manifest, so the composite reports the failure and the file looks
correct.

## Rules

**MUST:**

- Create an installation's groups before the manifest that names them
  is rendered.
- State the region here, as `region`, `regionCode` and `zone`. The
  plane and every instance read them, nothing defaults them, and a
  manifest that restates one is a second place for it to be wrong.
- State the domain here too, as `dns.domain`, and only the name
  delegated to this installation. An environment's own name is derived
  from it, so a unit restates neither.
- Name them for the installation's code, which is chosen here and never
  changed.
- Create each without an owner or a manager, Restricted before Only
  invited users.
- Bind `platform-viewer` at the organisation with
  `just gcp-groups-bind-installation`, which fails before the groups
  exist.
- Join `grp-gcp-org-admin@` for the bind, and leave again. Creating the
  groups and adding people are directory acts and take a super admin
  instead.
- Read what each capability grants, and where, with `just gcp-roles`.
  The organisation-scoped binding is declared in
  [organisation-roles.json](/infra/access/organisation-roles.json); the
  folder and project scoped ones are in the compositions under
  `infra/platform/crossplane-xrds/`.
- Put the people who operate an installation in `platform-viewer` and
  nothing else. Every other right they need arrives by joining a
  break-glass group for the task.
- Use accounts on your own domain rather than personal addresses.

**MUST NOT:**

- Leave anybody standing in one of the three break-glass groups.
- Give an operator a direct organisation binding.
- Bind the other three at the organisation. They are folder and project
  scoped, and the manifest is what grants them.

**MAY:**

- Create no groups at all and install with an empty `access` mapping,
  which is an installation nobody can reach but which reconciles
  correctly.
- Answer a capability with something other than a group — a user, or a
  `principalSet://` from an external provider — since the manifest
  takes whole IAM member strings.

## Discussion

We answered each capability with one group, coded to the installation
and deleted with it, only the day-to-day one populated and the rest
joined for a task. An `access` mapping takes a whole IAM member string,
so a capability may equally be answered by a user or a
`principalSet://` from an external provider — in an established
organisation, answer each with whatever it gives you.

They exist before the installation rather than after it because the
manifest names them, and IAM rejects a binding to a principal that is
not there. The alternative — install with an empty mapping and add
capabilities in a second merge — works, and leaves an installation
nobody can read until somebody adds them.

**Where each capability is bound.** `platform-viewer` takes Browser at
the organisation because tooling cannot reach a folder without first
resolving the organisation holding it. Nothing else is bound there: the
rest is folder and project scoped and reaches these groups through the
installation's manifest, and nobody needs a direct binding either,
since Project Creator and Billing Account Creator are granted to the
whole domain and every other right arrives through membership.

**Directory acts and IAM bindings.** Creating a group is a directory
act and binding a role is a Google Cloud one, and the two have separate
authorities. A super admin
administers the directory and holds nothing in the organisation's IAM
policy, so binding at the organisation means joining the group that
carries Organization Administrator, and leaving it again.

Creating them cannot be scripted: every Cloud Identity write attributes
quota to a project, and at this point the installation has none.
Binding needs no quota project, which is why one half is a recipe and
the other is a browser.

The capabilities are separate because they are held at different times
by different people: reading an installation is day-to-day, assuming
the identity that runs it is not, administering a cluster by hand
bypasses what reconciles it, and handling secret contents is a
different job from running what holds them. The set is not fixed — one
is added to the XRD's `access` mapping with a principal named for it —
but these are what an installation needs. See
[ADR-0023](../../adr/0023-installation-naming-and-access.md).

**Accounts on the domain.** An organisation that later sets
`iam.allowedPolicyMemberDomains` invalidates every binding naming a
principal outside the domain, the operator's group membership included.
It is easier to set before anything depends on not having it.

## References

- [organisation-foundation](organisation-foundation.md) — the organisation,
  the domain these accounts are on, and its own capabilities.
- [plane-install](plane-install.md) — the plane
  that binds them.
- [cloud-naming](../practices/cloud-naming.md) — the code they are named for.
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  capabilities and who holds them.
