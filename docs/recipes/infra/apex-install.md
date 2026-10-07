# An organisation's apex

<!-- tessl-plugin: deployment -->

## Status

**Verified.** One organisation's apex was built this way end to end:
the project, the zone, its records, the delegation moved at the
registrar, and the standing owner grant closed out.

Written from
[ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md) for
an organisation bringing a domain in, so it assumes nothing answers
below the apex yet. Where something does,
[apex-dns-migration](../../plan/apex-dns-migration.md) is the sequence
and this is a part of it.

## Problem

You have a domain, and you want every environment in every installation
to answer on a name below it — without any installation owning the name
itself.

The apex is the domain carrying no subdomain prefix, and exactly one
zone can be authoritative for it, because a registrar delegates to one
nameserver set. Put that zone inside an installation and two things
follow: a second installation cannot have one, and the zone sits in the
blast radius of a composite that gets rebuilt. Neither is survivable
once there is more than one installation, and the second is not
survivable at all — a recreated zone draws nameservers the registrar
does not follow, from a pool that is finite per domain.

So it lives above every installation, in a project of its own, declared
in git and reconciled by nothing.

## Solution

### Prerequisites

- The organisation, its billing account and its access groups —
  [organisation-foundation](organisation-foundation.md).
- A registrar account that can change the domain's nameservers.
- The domain verified to you as a person —
  [gcp-dns](gcp-dns.md) step 1. Everything below is done as that
  account, not as a service account: the apex is the one name no
  automation is made an owner of.

Do this once for an organisation. An installation in a folder somebody
else hands you does none of it — their apex is theirs, what you are
given is a subdomain of it, and you start at
[contract-install](contract-install.md) instead.

### 1. Create the project

**As an org project admin and billing admin.** Ours are
`grp-gcp-project-admin@` and `grp-gcp-billing-admin@` — join both for
this step, then leave. Creating a project above a folder and billing it
are two capabilities.

```bash
just dns-apex-project-create
```

`prj-c-dns-<suffix>` at the organisation, outside every folder, with
the DNS API enabled and billing linked. It carries no code because it
belongs to no installation, which is the seed project's exception too —
see [cloud-naming](../practices/cloud-naming.md).

It reuses a project labelled `queenswood-tier=dns` where one exists
rather than minting a second, and stops rather than guessing where it
cannot list.

### 2. Render the manifest

**As yourself.** Creating the project made you its owner, and steps 2
to 5 use that rather than any capability. Step 1 also bound `dnsAdmin`
on it, which is what everything after step 6 uses — nothing composes an
access mapping for a project outside every folder, so that binding is
the only durable human access there is.

```bash
just dns-apex-manifest <domain> <project>
```

The zone's name is derived from the domain by rule rather than passed —
see [cloud-naming](../practices/cloud-naming.md) — and the recipe
prints it.

It writes `apex.yml` at the root of the manifests repository, beside
the installation directories, carrying the ownership tokens, SPF and
DMARC read from what the domain answers today rather than retyped.
Read it, then commit it: everything below reads that file, and nothing
else in that repository is applied by hand.

It refuses where the file already exists. The delegations below the
apex are added a name at a time and are regenerated from nothing, so
re-rendering over a live file would drop every one of them.

### 3. Create the zone

**As yourself, and it must be the account [gcp-dns](gcp-dns.md) step 1
verified.** Cloud DNS checks domain ownership against the caller, which
no group can supply.

```bash
just dns-apex-zone-create
```

It refuses where a zone of that name already exists, and stops rather
than guessing where it cannot tell. That matters more here than
anywhere else: creating a zone draws a nameserver set that is never
returned, and the pool a domain draws from is finite.

Where the account turns out not to be verified, the recipe opens Search
Console and tells you to re-run.

### 4. Fill it

**As yourself**, still.

```bash
just dns-apex-diff
just dns-apex-apply
```

`apply` creates and updates. It deletes nothing: a record removed from
the file is reported and left, because removing one from the apex is a
deliberate act rather than a consequence of an edit.

### 5. Move the delegation

**As yourself**, plus an account at the registrar.

```bash
just dns-apex-nameservers
```

Four names, which nothing points at yet.
[gcp-dns-delegation](gcp-dns-delegation.md) is the rest: prove the new
zone answers what the old one does, move the registrar, and confirm the
ownership token from the new authority. Done in that order the
propagation window is a no-op — and its step 1 is where a name already
answering below the apex has to be dealt with before you move.

### 6. Put the standing grant away

**As yourself, for the last time.** Creating a project makes the caller
its owner, so step 1 left you one.
Nothing above needed it — `dnsAdmin` carries all of it — and a standing
personal grant on the least replaceable thing in the estate is the
category [ADR-0023](../../adr/0023-installation-naming-and-access.md)
says does not exist.

```bash
gcloud projects remove-iam-policy-binding <project> \
  --member="user:<you>" --role=roles/owner
```

From here every act on this zone goes through `dnsAdmin`, joined for it
and left after — including every later delegation, which is
[instance-deploy](instance-deploy.md) step 1's rather than this
recipe's. Administering the project itself, as opposed to the DNS in
it, is `orgAdmin`'s to grant when it is needed.

## Failures

**A certificate that never issues, on an instance reporting healthy.**
Its `DNSAuthorization` emits a validation CNAME that has to resolve
publicly, and it cannot until this zone delegates the name. Delegate
before deploying the instance rather than after.

**`verifyManagedZoneDnsNameOwnership` on a zone create.** Cloud DNS
refuses a create by an identity that is not a verified owner of the
name, and checks Google Site Verification rather than anything in Cloud
DNS. Verify as the account you are running as.

**A record changed in the console stays changed.** Nothing reconciles
this zone. `just dns-apex-diff` is what finds it, and only when run.

## Rules

**MUST:**

- Create the apex project outside every folder with `just
  dns-apex-project-create`. It binds `dnsAdmin` on the project as it
  goes: nothing composes an access mapping for a project outside every
  folder, so without that the apex is one nobody may read or write.
- Create the zone as a verified person rather than as any service
  account, with `just dns-apex-zone-create`, which refuses a second and
  stops where it cannot tell.
- Declare the zone's contents in `apex.yml` at the root of the
  manifests repository, and change them by merging that file and
  running `just dns-apex-apply`.
- Read `just dns-apex-diff` before assuming the zone matches the file.
- Delegate a name before deploying the instance that answers on it, or
  its certificate cannot validate. That act is
  [instance-deploy](instance-deploy.md) step 1's, once per environment.

**MUST NOT:**

- Compose the apex from any control plane, or grant an installation
  rights in its project. It publishes nameservers upward and holds
  nothing here.
- Create and delete apex zones to try variations. Each create draws
  from a finite per-domain pool and the draw is not returned.
- Delete the old zone until the registrar answers from the new one.

**MAY:**

- Skip this entirely where an organisation hands you a folder and a
  subdomain. Their apex is theirs, and an installation reads the same
  either way.
- Point the apex at a front door rather than at an environment's
  address, once one exists, or at a static site —
  [web-install](web-install.md). It is the same record.

## References

- [ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md) —
  why the apex belongs to no installation, and what is declared where.
- [gcp-dns](gcp-dns.md) — proving ownership, and the registrar
  inventory this depends on.
- [gcp-dns-delegation](gcp-dns-delegation.md) — moving the delegation
  once the zone answers.
- [organisation-foundation](organisation-foundation.md) — the
  capabilities this takes, and the groups that answer them.
- [cloud-naming](../practices/cloud-naming.md) — why this project
  carries no code.
- [apex-dns-migration](../../plan/apex-dns-migration.md) — reaching
  this from an estate whose apex is inside an installation.
