# Up and running

<!-- tessl-plugin: deployment -->

## Status

**Untested.** The steps have each been run, never in one sequence by
one person.

## Problem

You want to run Queenswood on Google Cloud.

## Solution

### Prerequisites

- A private git repository for the manifests.
- For a new organisation - a domain with access to edit its records at
  the registrar, and a payment method for the billing account.

Start at step 1 if you need to create a new organisation.

Start at step 3 if your organisation exists already and they can give
you a folder, or somewhere to make one — including the name you answer
under, which is theirs rather than yours to create.

### 1. New organisation

[organisation-foundation](organisation-foundation.md), then
[organisation-bootstrap](organisation-bootstrap.md). Cloud Identity,
the domain verified against it, the organisation, a billing account,
the access capabilities, and the seed identity that creates folders
and projects.

Done once for an organisation rather than once per installation: the
seed project is reused where one exists, and its organisation-scoped
rights are opened for a bootstrap and closed after it.

### 2. The apex

[apex-install](apex-install.md). A project at the organisation holding
the one zone a registrar points at, its ownership tokens and mail
policy, and the delegation moved onto it. Declared in `apex.yml` and
reconciled by nothing, because it belongs to no installation and is the
one zone that cannot be rebuilt.

Done once for an organisation, and skipped entirely where somebody
else's apex delegates a subdomain to you. It takes only organisation
capabilities, so nothing about an installation has to exist first.

### 3. The contract

[contract-install](contract-install.md). `environment.yml`, committed,
naming the installation's folder, its manifests repository, its billing
account, the region it runs in, and a principal for each capability.

### 4. The boundary

[boundary-install](boundary-install.md). `subsidiary.yml`, committed,
declaring the installation's folder and the capabilities bound inside
it, composed where the folder is ours and adopted where an organisation
hands one over.

### 5. The management plane

[management-plane-install](management-plane-install.md). The management
project, a cluster running Crossplane and Argo that reconciles the
installation from git, Argo's credential for the manifests repository, the
recovery project, and the name this installation answers under. A throwaway
control plane raises the project and the cluster, the composite pivots onto
the cluster it built, and the throwaway one is discarded.

### 6. An instance

[instance-deploy](instance-deploy.md). The instance's own DNS zone and
the delegation to it, then the unit: its project, network, cluster,
database and records for one environment, and then the bank on top of
it, answering at `https://console.<domain>`. Then
[outbound-email-install](outbound-email-install.md), for the invitation
emails it sends.

### 7. Local development

[local-install](local-install.md). The installation's local project, the
OAuth client in it, and the pair a developer's monolith signs in with.

## Rules

**MUST:**

- Do these in order.

**MAY:**

- Start at step 3 where the organisation is established and its apex
  already delegates a name to you.
- Stop after step 5, which is an installation with no instance on it.
- Skip step 7 where nobody signs in to a local monolith with Google.
- Run steps 1 and 2 once for an organisation, and steps 3 to 6 once per
  installation.

## Discussion

The dependencies run one way: a directory before an organisation,
principals before anything binds them, a folder before a project inside
it, a plane before anything it reconciles, an installation before an
instance derives from it.

Step 1 is a browser. Cloud Identity, a directory and a billing account
have no API between them, and the seed identity is the one part done in
a shell. Everything from step 3 is a file in a repository. That is the
first seam: where the work stops being performed and starts being
recorded.

Step 4 is the second. A folder is what an installation is, so an
organisation handing one over hands over the whole thing, and step 4
declares the same folder either way. That is what makes the two entries
two starting points rather than a branch: nothing after step 3 is done
differently depending on where you came in.

The apex is the same shape one level up. Step 2 builds one where the
domain is ours; an organisation that already has one delegates a name
to us instead, and every step below reads a delegated name identically
whether the zone above it is ours or theirs.

Both entries converge on capabilities. Every step from 4 onwards asks
for a capability rather than for whoever answers it, so an established
organisation can answer the same ones its own way, and the boundary,
the plane and the instance cannot tell which entry produced them.

## References

- [organisation-foundation](organisation-foundation.md) and
  [organisation-bootstrap](organisation-bootstrap.md) — step 1.
- [apex-install](apex-install.md) — step 2, and what an organisation
  whose apex is somebody else's does instead.
- [contract-install](contract-install.md) — step 3, and what to ask an
  organisation for.
- [boundary-install](boundary-install.md) — step 4.
- [management-plane-install](management-plane-install.md) — step 5.
- [instance-deploy](instance-deploy.md) — step 6.
- [ADR-0022](../../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — the folder as the boundary an installation occupies.
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  code, and who holds which capability.
- [ADR-0027](../../adr/0027-the-folder-is-a-subsidiary.md) — the folder
  as its own kind, and the handover in either direction.
