# Cloud naming

<!-- tessl-plugin: deployment -->

## Problem

[ADR-0023](../../adr/0023-installation-naming-and-access.md) decided that
names follow the Google Cloud security foundations guide, and listed
enough kinds to build the first installation. It is not a complete
inventory, and it never can be: a kind arrives before the list does.

The guide cannot fill the gap either. Its published PDF now serves a
stub, so its own table is reconstructible only from the Wayback Machine
or the Terraform example repository, and it covers nothing specific to
how this platform is built.

So a name gets invented at the moment somebody needs one, which is the
worst moment to be deciding a convention. Worse, most of these names
cannot be changed later: a project id is consumed permanently, and a
folder, cluster or bucket renames only by being rebuilt.

## Solution

### The rule

```
<prefix>-<code>-<env>-<label>[-<qualifier>]
```

- **prefix** — the kind, abbreviated. Fixed per kind, listed below.
- **code** — the installation's four-character code, chosen when it is
  created and carried in its manifest. `qw01` stands in for it
  everywhere below, and is an example rather than a constant: a second
  installation is `qw02`, and one built for somebody else is neither.
- **env** — one letter: `b` bootstrap, `c` common, `d` dev, `n` nonprod,
  `p` prod.
- **label** — what this one is for, within its kind and environment.
- **qualifier** — only where a name would otherwise collide or lose
  meaning: a region for anything regional, a uniqueness suffix where the
  id is globally unique.

A kind not listed below takes this shape. If it does not fit, that is
worth a paragraph in this recipe rather than a decision in a pull
request nobody reads twice.

### Why the code precedes the environment

The guide has no installation code — it assumes one organisation divided
into environments. Inserting the code after the prefix keeps the guide's
`<env>-<label>` tail intact, and sorts an installation's resources
together, which matches the folder being the unit that gets created and
destroyed as a whole.

### A name you cannot take back carries a suffix

Some names are consumed by being used. Delete the resource and the name
does not come back, or comes back only if nobody else took it first —
so a rebuild under the same name is not available, and the rebuild is
what an installation does routinely.

Give those six hex characters at the end. The test is one question:
**if this resource were deleted right now, could the next one have the
same name?** Where the answer is no, or is somebody else's to decide,
the name takes a suffix. Where it is yes, it does not — a suffix on a
name that could simply be reused is noise, and reads as though
something were at stake.

What that settles, and how each was checked rather than assumed:

- **Project id** — never reusable after deletion. Suffix.
- **Bucket** — globally unique across all of GCP, so the name is not
  merely consumed but contested: lose the race and it is gone. Suffix.
- **KMS key ring and key** — cannot be deleted at all, so a name is
  spent permanently. Suffix, from the first commit, if one is ever
  composed.
- **Cloud SQL instance** — reusable immediately, which it was not
  always. No suffix.
- **Service account** — the name is reusable, but the account that
  takes it is a separate identity and inherits none of the roles the
  deleted one held, while those bindings linger with a `deleted:`
  prefix for up to sixty days. Survivable here only because the
  composition creates every binding it needs, so a rebuild re-grants
  them; a grant made by hand does not come back. No suffix, and worth
  knowing why.
- **Folder** — display names are unique among siblings rather than
  globally, and anything durable references the folder id. No suffix.

### The exceptions, and why each is one

- **Folder** — `fldr-qw01`. No environment: the folder *is* the
  installation and holds every environment inside it.
- **Seed project** — `prj-b-seed-<suffix>`. No code: it holds the
  identity that creates installations, so it exists before any folder
  and one serves the whole organisation.
- **Apex DNS project** — `prj-c-dns-<suffix>`. No code either, and for
  the same reason: it holds the zone a registrar points at, which
  belongs to no installation and outlives all of them. `c` because it
  is common to the organisation rather than to an environment.
- **Apex DNS zone** — `dz-c-<domain>`, the domain with its dots as
  hyphens, and the one zone that does carry it. No code, for the same
  reason as the project holding it. The rule below excludes the domain
  from a zone's name because an environment supplies a label to use
  instead; here there is none, and two apex zones differ in nothing
  else.
- **Service account** — no environment where the identity is one per
  installation, following the guide, which names an identity for its job
  rather than its tier: `sa-qw01-platform` runs the whole installation,
  `sa-qw01-boot` sits outside the folder entirely. An identity belonging
  to one cluster takes the environment, because every cluster wants the
  same job done and the name has to say whose:
  `sa-qw01-c-secrets` for the operator reading secrets on the
  management cluster. A node identity carries the label as well —
  `sa-qw01-c-mgmt-nodes`, `sa-qw01-n-test-nodes` — for the reason API
  enablement does: two nonprod instances would both claim
  `sa-qw01-n-nodes`, and while their projects would keep them apart in
  GCP, the composed resources naming them all sit in one namespace on
  the management cluster.
- **Group** — `grp-gcp-<capability>` at the organisation,
  `grp-gcp-qw01-<capability>` for an installation. The `gcp` segment is
  the guide's, marking these as cloud access groups within a directory
  that holds other kinds.
- **Custom role** — `rl_<function>`, underscores rather than hyphens: a
  custom role id takes letters, numbers, underscores and periods, and
  GCP rejects anything else. Defined at the organisation where it is
  shared, and in a project where an installation must own it outright —
  an installation built in an organisation somebody else administers
  cannot define one above its own folder.

### The inventory

Resource-manager and identity:

- **folder** — `fldr-qw01`
- **project** — `prj-qw01-c-mgmt-<suffix>`. The suffix is six hex
  characters, because a project id is globally unique. An installation
  has two `c` projects: `mgmt` reconciles the platform and
  `recovery` holds what the platform would be rebuilt from, apart so
  that the identity able to destroy a primary cannot reach the copy.
  The plane composes `recovery` and leaves it empty; each instance
  composes its own bucket in it. One `d` project, `local`, holds the
  OAuth client local development signs in through and nothing that runs
  — see [local-install](../infra/local-install.md).
- **API enablement** — `svc-qw01-c-<api>`, the API's first label:
  `svc-qw01-c-iam`, `svc-qw01-c-container`. The management project ends
  the name there because there is one of it. An instance keeps its
  label and takes the API as the qualifier —
  `svc-qw01-n-test-compute` — since two nonprod instances would
  otherwise both claim `svc-qw01-n-compute`.
- **service account** — `sa-qw01-platform`
- **Kubernetes objects** — two rules, by how the name is arrived at. A
  name a composition builds from the installation's code, or that
  another resource then references by that constructed name, follows
  the scheme above: `sa-qw01-secrets`, `qw01-mgmt`,
  `sec-qw01-c-github-app`. A cluster-singleton — a chart, an Argo
  Application, a configuration nothing constructs a name for — is named
  in plain words instead: `management-plane`, `crossplane-providers`,
  `external-secrets`. There is one installation per management cluster,
  so a code on those would distinguish nothing.
- **custom role** — `rl_<function>`, with underscores. A custom role id
  takes letters, numbers, underscores and periods, and no hyphens, so
  this is the one kind that cannot follow the separator every other name
  uses. Where the same role is also a Kubernetes object, its Kubernetes
  name keeps hyphens and the id is the external name — `rl-pod-log-reader`
  naming `rl_pod_log_reader`.
- **group** — `grp-gcp-qw01-platform-viewer`

Network:

- **VPC** — `vpc-qw01-c-mgmt`
- **subnet** — `sb-qw01-c-mgmt-euw2`. Regional, so the region is
  abbreviated and carried: GCP publishes no short form, so the
  installation manifest states it. A second subnet in the same place
  takes what it is for before the region —
  `sb-qw01-n-test-proxy-euw2` — since the region alone no longer tells
  the two apart.
- **firewall rule** — `fw-qw01-c-mgmt-<direction>-<action>-<target>`
- **route** — `rt-qw01-c-mgmt-<destination>`
- **Cloud Router** — `cr-qw01-c-mgmt-euw2`
- **Cloud NAT** — `nat-qw01-c-mgmt-euw2`
- **static address** — `addr-qw01-c-<label>`
- **DNS zone** — `dz-qw01-<env>-<label>`, and not the domain, the apex
  zone above being the exception. One name
  is one zone, an environment answers on its own name, and a zone is
  renamed only by rebuilding it — so a name spelled from the domain
  would move every time the domain did. The domain is stated in the
  spec instead. See
  [ADR-0028](../../adr/0028-the-apex-belongs-to-no-installation.md).
- **DNS record set** — `rs-qw01-<env>-<label>-<name>-<type>`, where the
  name is what it publishes and `host` stands for the domain itself. It
  carries the instance rather than the zone, because the zone holds the
  records of every instance in the installation and the object name is
  what says whose a record is.
- **DNS authorization** — `da-qw01-<env>-<label>`. One covers a domain
  and its wildcard, so there is no second to distinguish.
- **certificate** — `crt-qw01-<env>-<label>`, and `-wildcard` for the
  one covering everything beneath it. Two, because a certificate's
  domains cannot be added to.
- **static address** — `addr-qw01-<env>-<label>` for an instance, where
  the label already says what it is for.

Compute and data:

- **GKE cluster** — `qw01-c-mgmt`, without a kind prefix: clusters are
  never listed beside other kinds, and GKE prefixes every name it
  derives from one with `gke-` already, so ours would only double it
- **node pool** — `np-qw01-<env>-<label>-primary`, and `primary` rather
  than `default` because GKE creates its own `default-pool` on every
  cluster and a name echoing it leaves a reader guessing whose is
  whose. The environment is in it even though the cluster above it
  already settles that, because GCP scopes a pool's name to its parent
  and Kubernetes does not: every cluster may hold an `np-primary`,
  where the composed resources for every instance in an installation
  share one namespace. The short form is only available on one side, so
  taking it would mean two names for one thing. The cost is visible
  wherever a node is named, and larger than the sixty-three character
  limit suggests: GKE builds a node name from the cluster and the pool
  and truncates to its own budget, which the repeated scope exceeds. The
  nodes came up as
  `gke-qw01-n-test-np-qw01-n-test-primar-9e7207bb-7rqg`, fifty-one
  characters with the pool's last letter dropped. So a node name no
  longer spells the pool's, which is a real cost of repeating the scope
  and worth knowing before choosing a longer label
- **CloudSQL instance** — `sql-qw01-<env>-<label>`
- **database inside one** — `sql-qw01-<env>-<label>-<name>`, so
  `sql-qw01-n-test-keycloak`. A database name is scoped to its instance
  in GCP and to a namespace in Kubernetes, the same split a node pool
  has, so the longer name wins in both
- **database user** — the exception the annotation exists for. Postgres
  wants an IAM user named for the service account's address with
  `.gserviceaccount.com` removed, which carries an `@` and is not a
  name Kubernetes can express, so `crossplane.io/external-name` holds
  it and the Kubernetes name is `sql-qw01-<env>-<label>-user`
- **private services access range** — `addr-qw01-<env>-<label>-psa`,
  following the static address prefix. Global rather than regional, so
  no region qualifier
- **service networking peering** — `psa-qw01-<env>-<label>`, one per
  VPC and named for what it grants rather than for either end
- **bucket** — `bkt-qw01-<env>-<label>-<what>-<suffix>`, with the
  `<what>` distinguishing buckets whose retention regimes differ: a
  retention policy is bucket-wide and, once locked, permanent, so
  anything sharing a bucket with what is kept forever is kept forever
  too. The suffix is six hex characters, because a bucket name is
  globally unique, and it is the suffix of the project *holding* the
  bucket rather than of whatever owns it — so no second unique value
  has to be supplied and kept in step, and a bucket does not change
  name if the thing that writes to it is ever rebuilt.
  `bkt-qw01-n-test-backups-<suffix>` is the one that exists: an
  instance's own bucket, in the installation's recovery project, so an
  instance is granted on its own backups and reaches no other's.
- **secret** — `sec-qw01-<env>-<label>`

### Installation qw01, as built

Above the folder and named by whoever owns the organisation, so no
concern of this recipe: the organisation itself, and a billing account.
Their identifiers are deliberately not reproduced here — a public
document should carry names, which is what this is about, and not
account identifiers, which are what somebody pretexting a support call
would want.

Outside the folder, one per organisation:

- seed project — `prj-b-seed-xxxxxx`
- boot identity — `sa-qw01-boot@prj-b-seed-xxxxxx.iam.gserviceaccount.com`

The installation itself:

- folder — `fldr-qw01`
- management project — `prj-qw01-c-mgmt-xxxxxx`
- APIs — `svc-qw01-c-iam`, `svc-qw01-c-iamcredentials`,
  `svc-qw01-c-serviceusage`, `svc-qw01-c-resourcemanager`,
  `svc-qw01-c-compute`, `svc-qw01-c-container`
- VPC — `vpc-qw01-c-mgmt`
- subnet — `sb-qw01-c-mgmt-euw2`
- cluster — `qw01-c-mgmt`
- node pool — `np-qw01-c-mgmt-primary`
- platform identity —
  `sa-qw01-platform@prj-qw01-c-mgmt-xxxxxx.iam.gserviceaccount.com`
- node identity —
  `sa-qw01-c-mgmt-nodes@prj-qw01-c-mgmt-xxxxxx.iam.gserviceaccount.com`
- secrets identity —
  `sa-qw01-c-secrets@prj-qw01-c-mgmt-xxxxxx.iam.gserviceaccount.com`

The worked example is what a new installation is built to. `qw01` was
built before the cluster prefix was dropped and before the pool took
`primary`, so it holds `gke-qw01-c-mgmt` and `np-qw01-c-mgmt` until its
plane is next rebuilt: renaming either destroys and rebuilds the
cluster, so it happens with a rebuild rather than instead of one. See
[plane-cluster-naming](../../plan/plane-cluster-naming.md).

One environment inside it, `n-test`, built to the rule rather than
before it:

- project — `prj-qw01-n-test-xxxxxx`
- APIs — `svc-qw01-n-test-compute`, `svc-qw01-n-test-container`,
  `svc-qw01-n-test-iam`, `svc-qw01-n-test-resourcemanager`,
  `svc-qw01-n-test-serviceusage`
- VPC — `vpc-qw01-n-test`
- subnets — `sb-qw01-n-test-euw2` and `sb-qw01-n-test-proxy-euw2`
- cluster — `qw01-n-test`
- node pool — `np-qw01-n-test-primary`
- node identity —
  `sa-qw01-n-test-nodes@prj-qw01-n-test-xxxxxx.iam.gserviceaccount.com`

The installation's capabilities, in a directory we happen to own:

- `grp-gcp-qw01-platform-viewer@queenswood.io`
- `grp-gcp-qw01-platform-admin@queenswood.io`
- `grp-gcp-qw01-cluster-admin@queenswood.io`
- `grp-gcp-qw01-secrets-admin@queenswood.io`

Only three values here were chosen rather than derived: the code
`qw01`, and the two project suffixes, which are random because a
project id is globally unique. Everything else follows.

### Kubernetes names are the same names

A Crossplane managed resource is named for what it manages, so
`kubectl get managed` and the Cloud Console read alike — the composite
is `qw01`, and the resources under it are `fldr-qw01`,
`prj-qw01-c-mgmt`, `gke-qw01-c-mgmt`. This costs an explicit patch per
resource where one format string would otherwise do, and it is what
makes a console tab and a terminal describe the same thing.

A composed resource whose name is generated cannot be referenced by
another, so this is load-bearing rather than cosmetic.

It also decides a name where GCP would allow a shorter one. A node pool
is scoped to its cluster, so `np-primary` is unambiguous in GCP and
collides in the namespace every instance's resources share. Where the
two disagree, the longer name wins in both rather than
`crossplane.io/external-name` holding a second one — the annotation is
for a name Kubernetes cannot express, not for a name that is merely
tidier.

### Names the manifest derives

Nothing in a composition hard-codes an installation's code. The XR
carries `spec.code`, every name patches from it, and the region
abbreviation comes from `spec.regionCode` beside it. A second
installation is that field and nothing else.

## Rules

**MUST:**

- Derive every composed name from `spec.code`, never from the
  composite's own name.
- Carry the environment letter on anything scoped to one, including the
  kinds the guide does not list.
- Add a kind to the inventory above when you name one that is not
  already there.
- End a name with six hex characters where the name cannot be reused
  after the resource is deleted. Check what the kind actually does
  rather than assuming, and record the answer in the list above.

**MUST NOT:**

- Bake an environment name, a domain or a customer name into a resource
  name. The code and the environment letter are the only identifiers a
  name carries.
- Put a realised suffix anywhere public — a pull request's title or
  body, an issue, a comment, a review.
  `scripts/hooks/check-system-ids.sh` covers the tree and the commit
  message from a hook, and everything written outside git from a
  workflow; write `xxxxxx` instead. On a pull request that check can
  hold a merge. On a comment it is detection only: the text is public
  the moment it is posted, and no workflow unposts it.
- Rename a project id, a folder or a bucket in place. None of them
  supports it — the id is consumed and the resource is rebuilt.
- Invent a prefix where the inventory already has one.

**MAY:**

- Give a name a qualifier where it would otherwise collide, most often
  a region.
- Keep a name a supplier chose, where a folder or project is handed to
  us already named.

## References

- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  decision this recipe carries out, and the reasoning behind the code.
- [ADR-0022](../../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — the folder as an installation, and its lifecycle.
- [plane-install](../infra/plane-install.md) — what a
  deployment builds, and the two identities that build it.
