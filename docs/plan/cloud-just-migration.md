# Plan: the pivot, and retiring cloud.just

## Context

[ADR-0016](../adr/0016-crossplane-over-terraform.md) chose declared
infrastructure over scripted, and
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
found that the shape it left behind does not hold that line: the plane
that reconciles everything is a kind cluster on one laptop, the
lifecycle is a shell script, and there is one environment with no answer
for a second. [ADR-0023](../adr/0023-installation-naming-and-access.md)
then settled what the replacement's resources are called and who may
touch them.

`justfiles/cloud.just` is that previous generation — keyed to one
environment whose project id, secrets and pointers live in `pass` on one
machine. `justfiles/gcp.just` is where the replacement lands. Nothing
calls across from `gcp.just` into `cloud.just`, so `cloud.just` only
ever shrinks rather than becoming a file of two generations.

The foundation is built. The next act is the pivot: moving the
installation from the throwaway plane onto the management cluster it
created. This plan is that, and what follows it.

## Picking this up cold

**The bank is reachable at a real domain, a user signs in with Google,
and a bank can be created.** The instance serves `console.`, `api.` and
`keycloak.test.queenswood.io` over certificates it composed itself, the
whole DNS path is declared from manifests, and the release was renamed
while FoundationDB still held nothing anyone wanted. Verify against the
account rather than against this line:

```
kubectl --context qw01-n-test -n queenswood get pods
curl -o /dev/null -w '%{http_code}\n' https://console.test.queenswood.io/
```

**It is also backing itself up, continuously, and nothing has ever been
restored from it.** FoundationDB ships snapshots and a mutation log
through an in-cluster S3-to-GCS proxy into a bucket the instance cannot
delete, encrypted under a key that never touched git; Keycloak exports
both realms hourly beside them. Every piece of that was built this
week, and the half that reads it back has been run by nobody:

```
just sop-fdb-list-backups n test
just sop-fdb-version-at n test <YYYY-MM-DDTHH:MM:SSZ>
gcloud storage cat gs://<bucket>/keycloak/realms/LATEST
```

`running: true` on the `FoundationDBBackup` says the agents are
shipping. It does not say a restore works, and today it demonstrably
would not have: the restore Job was reading the encryption key from a
mount that had moved, found by reading the path rather than by anything
failing. Treat "the backup is green" as evidence about writing only.

**Google sign-in is done, and the three acts it took are the three
that never become automated.** external-secrets runs on the instance
cluster, a `ClusterSecretStore` reads the instance project with no auth
block — the controller's pod carries the annotated service account —
and an `ExternalSecret` materialises `queenswood_google-client-secret`
into the vault Keycloak mounts. That name is `<realm>_<key>`, which is
what makes the realm's committed `${vault.google-client-secret}`
resolve. The realm-import Job reconciles the identity provider's client
id from `keycloak.googleClientId`, and carries it in its own name, so a
changed id produces a Job that runs rather than a completed one nothing
re-applies.

What a second instance repeats, in this order:

- **The OAuth client**, created by hand in the console. No API makes a
  web client with a chosen redirect URI, so no automation holds the
  right — it is `roles/oauthconfig.editor`, granted through
  `platformAdmin`. See
  [google-sign-in](../recipes/infra/google-sign-in.md).
- **The secret**, written once into `sec-qw01-n-test-google-oauth` with
  `just gcp-secret-version`. Crossplane composes containers, never
  values, and the GitHub App key went in by hand the same way. What
  external-secrets buys is that it survives a cluster rebuild and
  lives somewhere auditable rather than only in etcd. The general
  shape, and where a value must not go on the way in, is
  [external-secrets](../recipes/infra/external-secrets.md).
- **The id**, set on `keycloak.googleClientId` in
  `qw01/test-values.yml`.

Keycloak reads the vault at startup, so this instance — up before the
store existed — needed one Keycloak restart after the secret was
stored. A rebuilt instance does not: the operator and the config chart
sit in sync waves ahead of the bank's own Application.

**A cluster rebuild has been done, and the instance is part-way through
it.** FoundationDB was restored from backup for the first time —
`Restored to version <version>`, with `ApplyVersionLag: 0` —
which makes the backups evidence rather than belief. The cluster came
back on Dataplane V2, confirmed by `anetd` running in `kube-system`.

It is not serving. Cloud SQL survived the rebuild, so Keycloak's
database still holds the admin account the *previous* cluster's operator
generated, and the operator has since generated a different password.
Keycloak honours a bootstrap admin only while the master realm is
absent, so nothing can administer a realm it is otherwise serving fine.
The realm import cannot get a token, bootstrap waits on the import, and
every service waits on bootstrap.

The decided way out is not to repair the credential but to make it
durable and start the realm again beside a fresh FoundationDB — which is
the only condition under which resetting the realm is safe, since fresh
user ids orphan records written against the old ones. In order:

The chart side is merged: the bootstrap admin now comes from a Secret
Manager entry the composite declares, so a rebuild stops causing this.
What remains is applying it to this instance.

1. Write a version into `sec-<code>-<env>-<label>-keycloak-admin` with
   `just queenswood-instance-keycloak-admin <env> <label>`, which generates the
   value and refuses an entry that already holds one. Two properties,
   `username` and `password` — the `ExternalSecret` reads both and
   materialises a `kubernetes.io/basic-auth` Secret.
2. Set `keycloak.bootstrapAdmin.secretName` in the instance values, to
   the name `queenswood-config` gives it.
3. Set `fdb.restore.enabled: false`. Leave the target beneath it as the
   record of where this cluster's data came from.
4. Reset the Keycloak schema through the proxy, per
   [deployment](../recipes/infra/deployment.md): `instances: 0` first, or the
   schema is in use.

Steps 1 and 2 are `secretsAdmin` and a merge in the manifests
repository; step 4 is the only one that destroys anything, and it is
safe only because step 3 leaves FoundationDB to come up empty
alongside it.

Both stores then come up clean and consistent, and the next rebuild is
what tests whether the credential fix worked.

### What to do first, on a fresh context

In this order, because each one tells you whether the next is worth
doing:

1. **Check what is running**, with the commands above. `gcloud auth
   application-default login` first if a `kubectl` call asks to
   reauthenticate — `gcloud auth login` alone does not refresh ADC.
2. **Read [ADR-0026](../adr/0026-recovering-data-and-the-states-that-do-it.md)**
   before touching anything about recovery. It is the only place the
   position is written down, and the machinery it describes does not
   exist.
3. **Pick from `Also outstanding`.** The list is ordered by what would
   embarrass this installation soonest, not by effort: a restore
   nobody has run, then a bucket that grows without bound, then
   credentials in the clear.

Two habits this week paid for, both cheap:

- **Render before believing.** `helm template` against the real values
  files, and `crossplane render` against the function versions the
  plane actually runs, caught a composite that composed nothing, a
  chart that rendered a fresh keypair every time, and an export that
  exported no realms. None of them failed loudly.
- **Ask which half has never been run.** Every fault this week was on
  a path nothing exercises: the restore, the export, the history
  nobody scanned. The green half is not evidence about the other one.

### Also outstanding

- **The consent screen is in Testing mode**, and publishing it is a
  console act nobody has performed. Refresh tokens expire after seven
  days there, so a session that should persist drops back to sign-in
  about weekly and reads as an application fault. It also caps sign-in
  at the hundred accounts on the test-user list. Publishing ends both,
  and joins no verification queue: Keycloak requests `openid profile
  email` unless the realm sets `defaultScope`, and verification binds
  only sensitive and restricted scopes. Do it when the test-user list
  stops being the point. See
  [google-sign-in](../recipes/infra/google-sign-in.md).
- **A doc sweep.** The same architecture is described in about five
  places and the retired generation is still present in most of them.
  `docs/tdd/infrastructure.md` is the worst: 444 lines, 26 references
  to composites that no longer exist, and whole sections on
  `XPlatform`, `XQueenswoodApex` and workload delivery through
  `provider-helm`. Fixing it piecemeal leaves it half-true; it wants
  one deliberate pass, at the end, together with this plan.
- **Statements in this plan that nothing durable records.** "Workloads
  arrive through Argo, not Crossplane" was one and now lives in
  ADR-0024. Two more are still plan-only: what the Keycloak fold buys
  (one issuer rather than an agreement between two values files), and
  that the Cloud SQL proxy is the instance's rather than Keycloak's.
  Test the rest the same way — grep `docs/adr docs/recipes docs/tdd`
  for each and rehome what only this file says.
- **Six deployment-labelled docs with no rule section**:
  `organisation-foundation`, `cloud-naming`, `security-scanning`, ADR-0023,
  ADR-0025 and ADR-0026. Authoring them would roughly double the
  longest rule file here, so it stays its own pass. `sync-rules-from-docs`
  also reports a structural error on ADR-0022 and ADR-0024 that is not
  a doc fault: both have a `## Decision`, opening with `###`
  subsections the extractor cannot parse. The docs recipe already says
  which side is wrong — if discovery cannot find a labelled doc, the
  script is.
- **`image.tag` is `latest`.** It cannot be fixed in values: GHCR holds
  only `latest`, `latest-amd64` and `latest-arm64` for these images, so
  there is no version to pin to. Build-pipeline work.
- **A restore, which is the only thing that would make any of the
  backup work evidence.** ADR-0026 records the position rather than the
  machinery: for corruption, restore *beside* the damage rather than
  over it, because the corrupted data is the only record of what
  happened and in-place commits the moment the volumes go. Nothing is
  built for either shape, and `state: down` deliberately does not empty
  anything, so the destructive half the old cycle depended on has no
  successor.
- **Nothing expires a backup.** Around 4,700 objects a day, almost all
  zero-byte mutation logs. `fdbbackup expire` is the only thing that
  may delete — a lifecycle rule deleting behind FDB's back leaves
  metadata describing what is gone — and it takes a retention period
  and a floor, both derived from one number in days. A backup started
  this week has nothing thirty days old, so the first run that removes
  anything is a month away and the cadence can be chosen from what it
  costs.
- **Nothing says which APIs a project may have enabled.** The
  composition declares the eight an instance needs and reconciles those:
  disable one by hand and it comes back. It has no notion of a closed
  set, so a ninth enabled by anything else is a service nothing composed
  and nothing will remove — there is no managed resource for it to
  reconcile against.

  `disableOnDestroy` is not that control and reads as though it might
  be. It governs what happens when a declared resource is destroyed, so
  setting it true would only mean that dropping an entry from the list
  disables that API on a live project — which is how
  `cloudresourcemanager` would have been switched off as a side effect
  of tidying a loop. It closes nothing.

  The control is an org policy, `constraints/serviceusage.allowedServices`,
  which refuses the enablement rather than racing to undo it. It belongs
  on the folder with the rest of the installation rather than per
  instance, which is also what makes it worth having: it covers projects
  this composition never created, and an estate accumulates those. That
  places it behind the folder-scoped work rather than beside this one.
  The detective half already runs — see
  [security-scanning](../recipes/infra/security-scanning.md) for what is
  scanned and which findings are accepted.

- **Encryption in transit, decided: Dataplane V2.** Everything crossing
  a network boundary is already TLS — the load balancer, both paths to
  GCS, Cloud SQL, GitHub, the Google APIs, the payment and KYC
  providers. What is not encrypted is ten paths inside the cluster: the
  backup agents to s3proxy, the Gateway to console, api and Keycloak,
  the bank services to Kafka and to FoundationDB, FoundationDB between
  its own processes, Keycloak and the realm exporter to the Cloud SQL
  proxy, and everything to Jaeger.

  The control asks whether data in transit is encrypted, not whether it
  is TLS, so the answer is one field rather than ten pieces of work.
  `datapathProvider: ADVANCED_DATAPATH` and
  `inTransitEncryptionConfig: IN_TRANSIT_ENCRYPTION_INTER_NODE_TRANSPARENT`
  on the composed `Cluster`, both supported by the GCP provider already
  installed. It is WireGuard, over the encryption Google's VM NICs
  already apply.

  Two consequences. It is a create-time setting, so it arrives with a
  cluster rebuild rather than an edit — routine for an instance, the
  pivot for the plane. And it cannot coexist with
  `enableFqdnNetworkPolicy`, so egress control becomes IP-based: a
  Cloud NAT with a reserved address, and policies written against
  CIDRs rather than names. That is the poorer half of the trade and it
  is the half that matters for a payment provider that allowlists a
  source IP, which nothing can be given today because traffic leaves
  via whichever node runs the adapter.

  Worth stating before anyone asks: traffic between two pods on the
  same node is not encrypted by this, because it never reaches a
  network. On a small cluster that is a large share of the traffic.

  Reachability is the other half of the same subject and stays
  unbuilt. Nothing restricts pod-to-pod today, and a `NetworkPolicy` is
  deny-by-default only for the pods it selects — so one policy for one
  service leaves everything else open while the repository starts to
  look as though it has coverage. Done properly it is a default-deny
  baseline and an explicit allow per flow, which cannot be added
  without enumerating every legitimate connection at once.
  `enableCiliumClusterwideNetworkPolicy` is how the baseline gets
  stated once rather than per namespace. `cloud-naming` already
  reserves `nat-<code>-<env>-<region>`; nothing composes it, and the
  publicly reachable cluster accepted as CIS 4.9 belongs here too.

  Superseded by this: enabling TLS on the s3proxy leg on its own. The
  chart's claim that FoundationDB's blobstore client "has no usable
  trust store and the operator cannot pass it a CA" is still wrong and
  worth correcting where it appears in `_helpers.tpl` and
  `s3proxy.yaml` — upstream documents `FDB_TLS_CA_FILE` alongside a
  cert and key that must be parseable even though they are unused,
  which is the likeliest reason an attempt failed and the note got
  written.

- **CMEK on the backups bucket**, which is a layer above the control
  rather than a gap in it: Google encrypts GCS, persistent disks and
  Cloud SQL by default, so data at rest is already encrypted
  everywhere. What CMEK adds is keys this installation holds and can
  revoke. The realm export carries user
  credential records as plain JSON, where the bank's own data beside it
  is encrypted. Keycloak cannot close this itself: `kc.sh export` takes
  a directory, a realm and a user strategy, and has no encryption
  option at all, so the only places to add one are the bucket or the
  upload step.

  The bucket is the right one, and the reason is an asymmetry with FDB.
  FoundationDB encrypts its backup files because its blobstore hop to
  `s3proxy` is plain HTTP — the client has no usable trust store and the
  operator cannot pass it a CA. The realm export has no such hop: the
  upload container runs `gcloud storage cp` straight to GCS over TLS, so
  at rest is its only exposure, which is what CMEK covers. The
  alternatives — GCS CSEK, or encrypting in the container before upload
  — both hand back an unrotatable key that strands what was written
  under it, which is the problem the FDB key already has.

  CMEK is rotatable and revocable where that key is neither, and it
  answers "can we revoke access to backups", which nothing does today.
  It does not make the FDB key rotatable: those files stay encrypted
  twice, and bucket read access alone still yields nothing, which is
  the property CMEK does not provide on its own — it is transparent to
  any principal holding `storage.objects.get`.
- **Argo holds `roles/container.admin` on every instance project**, in
  [what is left](#what-is-left) with the narrower shape and its
  trigger.

### What cost the most, across two days

**Nothing re-evaluates, and the thing that reports it lies.** An Argo
operation pins the revision it started with and replays it on every
retry, so a merged fix never arrives — twice — while
`status.sync.revisions` shows the new commit and
`.operation.sync.revisions` shows what is actually being applied. One
rejected object fails a whole sync and leaves every well-formed
resource beside it `OutOfSync`. A composite behaves the same way: one
bad resource stops it composing anything, while `Ready` stays `True` on
everything already built. Read the condition that carries the failure,
not the one that looks relevant.

**Read the CRD, not the shape you expect.** Three separate resources
failed on fields that do not exist —
`KeycloakOIDCClient.spec.client.clientId`, a `projectRef` on a
`ServiceAccount`, and `secretId` with a list-shaped `replication` on a
Secret Manager entry. Server-side apply refuses an undeclared field
rather than dropping it, and `helm template` and `crossplane render`
both pass it happily. Only the API server has the schema.

**A fixture that differs from reality in the one respect that matters
will pass.** The validation record renders correctly against a
placeholder with no trailing dot and wrongly against the real value.

**A green path says nothing about the path that reads it.** The backup
went entirely green — agents shipping, objects landing,
`file_level_encryption` written — while the restore Job read the
encryption key from a mount that had moved under it two commits
earlier. Nothing exercises a restore until one is needed, so the half
that writes and the half that reads drift apart silently, and only the
writing half has a status to look at.

**A missing file is reported by everything except its name.** The realm
export asked for a filename that does not exist. `Files.Get` returns
nothing, `fromJson` turns nothing into an empty map rather than an
error, `.realm` on that is empty, the loop runs zero times and the
upload dies on an unmatched glob. Five symptoms, none of them naming
the file, and the template two directories away had it right all along.

**A guard's reach is not its rule.** Identifiers were scrubbed from
pull request descriptions and left in commit messages, because the scan
that found them was a scan of descriptions. The same guard could not
match a number at the end of a sentence, was widened three times, and
on the third the widening arrived in a description carrying a real
folder id as an example. Detection reports afterwards; the recipe that
stops it being written is the half that was missing.

**Deleting a Keycloak CR while its database survives strands the admin
credential**, because the operator owns the Secret and regenerates it.
It presents as an instance that will not start, and only Keycloak's own
log names the cause.

**A chart that generates a value regenerates it on every render.** The
signing key for `queenswood-admin` was minted by `genSelfSignedCert`
and preserved by a `lookup` of the live Secret — which returns nothing
under `helm template`, so Argo took the generate branch every time and
applied a fresh key over the last. The certificate registered on the
client stayed whatever the bootstrap Job had pushed, because that Job's
name hashes its pod spec and a changed Secret does not move it. Nothing
reported drift: from Argo's side the Secret was exactly what git said.
The fix was to generate in the cluster, from the Job that registers it,
and let the chart declare the Secret with no `data` so server-side
apply leaves the contents alone. Now in
[argocd-apps](../recipes/infra/argocd-apps.md).

**Two credentials in a row failed only at the moment of use.** A
redirect URI missing its last character is accepted by Google at setup
and rejected once the user has already left the site; a keypair whose
halves have drifted apart verifies nowhere but at a token exchange.
Neither is visible in any status, which is what put both of them last.
The general shape is worth carrying forward: for anything with a
counterpart held by another system, the thing to check is not that each
side is present but that the two were written in the same act.

## How the machinery fits together

Written down because reconstructing it from the manifests takes an hour
and it is the thing every decision below turns on. Kubernetes
understands none of this: the API server is a database, and everything
here is a controller reading and writing it.

Three files, three different kinds of object:

- **XRD** — `xqueenswoodinstallation-xrd.yml`. A schema. Crossplane
  reads it and generates a CRD, which teaches the API server to accept
  `kind: XQueenswoodInstallation`. That is all it does.
- **Composition** — `xqueenswoodinstallation-composition.yml`. A
  program, in `mode: Pipeline`. It says what an installation is made of.
- **XR** — the composite itself, `name: qw01`. The manifest, committed
  to `installations` and applied to the management cluster by Argo.

The flow, once all three are in a cluster:

```
XR applied
  -> Crossplane matches it to the Composition (defaultCompositionRef)
  -> runs the pipeline: function-patch-and-transform,
     function-go-templating
  -> functions emit desired managed resources
     (Folder/fldr-qw01, Project/prj-qw01-c-mgmt, ...)
  -> Crossplane writes those as objects in the API server
  -> each provider's controller sees its own kind, reads the
     ClusterProviderConfig for credentials, calls the GCP API
  -> status returns, and MR readiness rolls up into the XR's Ready
```

A provider package also installs the CRDs for its own kinds. So a
cluster without the GCP providers has no `Folder` kind, cannot store a
`Folder` object, and therefore cannot reconcile one — which is the whole
of what "no Crossplane CRDs on the management cluster" means.

**Argo CD interprets none of this.** It watches a git directory and
makes the cluster match it. Its job is to put the XRD, Composition and
XR into the cluster; Crossplane does the work. The two are named
together in
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
because they are the halves of "what git says, exists": Argo makes the
cluster match git, Crossplane makes GCP match the cluster.

**Something undeclared has to place the first Crossplane**, because
Crossplane cannot install itself onto a cluster that has none. That
irreducible step is `boot-cluster-up`: a kind cluster, Crossplane by Helm,
providers by manifest. It is the floor, and the design's aim is to keep
it at a scratch cluster on a laptop rather than at anything inside the
installation.

## What the recipes are for

`justfiles/gcp.just` is long, and the length reads as sprawl until you
ask what each recipe is for. Almost none of it installs Queenswood.

### The install is a merge, and the rest is a ladder

Where an organisation already runs GitOps, installing anything is one
act: a manifest lands in the repository a privileged control plane
reads, and the plane installs it. The privilege is ambient — somebody
established it before any of this, and it is simply there.

Queenswood's install is that merge. One file in `installations`, one
plane watching it, which is what
[picking this up cold](#picking-this-up-cold) says from the other end:
a change to the installation is a merge rather than a plane raised to
apply it.

The rest of `gcp.just` manufactures the ambient privilege for somebody
who has not got it — a control plane, an identity for it to
authenticate as, the rights that identity holds, and a repository it
may read. [The pivot](#the-pivot) is where the manufactured capability
becomes the durable one, and after it the installation is reconciled by
a plane watching a repository, which is where a GitOps organisation
starts.

That privilege has to be of the right kind, which is what keeps the
ladder from being a fallback for the under-equipped. A plane
reconciling Terraform against AWS cannot apply an
`XQueenswoodInstallation` at all: the manifest names a kind its API
server has never heard of, so the capability that installs everything
else in that organisation installs nothing here. Merging into a plane
that already exists needs Crossplane, on GCP, with this XRD and
composition loaded — and an organisation holding all three is the
exception. Everyone else climbs the ladder, whatever they already run.

What the climb leaves behind is not Queenswood-specific. A management
plane in the folder, running Crossplane and Argo against a repository,
reconciles whatever composite it is given: load another XRD and
composition, and applying that is a merge too. An organisation climbs
once and holds a general capability afterwards, which is the strongest
argument for the durable plane over raising throwaway ones repeatedly.

So every recipe answers one question: does this exist only because no
privileged plane exists yet?

- **Scaffolding**, if it does — the `seed-*` and `boot-*` recipes,
  `gcp-groups-bind`, the `gcp-adc-*` recipes and the `gcp-plane-*`
  recipes. Most of the file.
- **Durable**, if it does not — `plane-ctx`,
  `plane-status`, `plane-billing-role`,
  `argo-github-app-secret`. What survives having a platform.

Judge the scaffolding as scaffolding: it runs once and is discarded, so
its bar is reaching parity reliably rather than being a surface worth
polishing. The diagnostics among it — `seed-impersonate-status`,
`boot-mgmt-status`, `seed-status` — inspect the ladder rather than
the installation, which is why they sit oddly beside the recipes that
operate one.

### What the composite cannot build for itself

The prerequisites are not a list somebody chose. They are what is left
when you ask what an `XQueenswoodInstallation` reaches for and cannot
create. Each is unbuildable for a different reason, and the reason is
what says who satisfies it.

- **Recursion.** A control plane that already knows the kind:
  Crossplane, the XRD, the composition, a provider package for every
  kind composed, and the functions the pipeline runs. The plane the
  composite builds is where this eventually lives, so the first one
  comes from outside — and a plane built on another toolchain is not a
  weaker version of this one, it is not one at all.
- **Authentication.** The identity the provider authenticates as, which
  it cannot both be and create. With keys banned that means ADC
  impersonating a service account, so the account exists, in a project
  that exists, with the APIs impersonation calls enabled on it.
- **Scope.** What is held above the folder: creating or adopting it,
  `projectCreator`, `folderIamAdmin`, `browser`, `billing.user` on a
  billing account whose id is supplied when the project is created, and
  the org policies the design leans on. Granted by whoever holds them,
  which is never the installation.
- **No API.** The organisation, the billing account and the GitHub App.
  Each is a person in a browser, with no create call to write against.
- **Another directory.** The principals in `spec.access`. Nothing here
  speaks Cloud Identity, and IAM rejects a binding naming a principal
  that does not exist.
- **Secrecy.** The App key's value. The composite composes the secret's
  container, and what goes inside may never be in git.

They divide by how often each is paid, which is what the two paths in
[plane-install](../recipes/infra/plane-install.md) are
really distinguishing:

- **Once per organisation** — the organisation, the billing account, the
  GitHub App, the org policies, and whichever principals hold the
  capabilities.
- **Once per installation** — the folder, the identity, and the rights
  it holds on that folder.
- **Once per run** — the boot plane.

### What is left to a person, per installation

Against a plane that already exists, the residue is smaller than the
recipes make it look.

- **One decision.** Which principals hold `platformViewer`,
  `platformAdmin`, `clusterAdmin` and `secretsAdmin`. `spec.access`
  takes whole IAM member strings because the principal need not be a
  group, let alone one named for the installation, so an organisation
  with access groups already maps them straight in and creates nothing.
  The coded groups are what to do when no suitable principal exists.
- **One organisation-scoped binding.** `roles/browser` on the viewer
  principal, because tooling cannot reach a folder without resolving the
  organisation above it. Every other coded capability is folder- or
  project-scoped and composed from `access`.
- **The groups, only when minting them.** An Admin console act, because
  Cloud Identity writes need a quota project and at foundation time none
  exists. That blocker lifts once the management project does, so this
  can move to after the install rather than before it: a capability
  absent from `access` composes nothing, so an empty mapping installs
  and adding the groups is a second merge.

### Three ways this reports success and does not work

Each leaves the composite Ready and every managed resource green.

- **A manifest that was never pushed.** `boot-mgmt-apply` checks that
  the file is on disk, which is all a boot plane needs, but after the
  pivot the manifest is read from GitHub — so a local-only file passes
  every check in the ladder and then reconciles from nothing. Producing
  it should know the destination applying it already knows:
  `boot-mgmt-apply` resolves `QW_INSTALLATIONS_REPO`, while
  `queenswood-installation-manifest` prints to stdout for somebody to redirect there.
- **A secret with no version.** The composite composes the container and
  a person adds the version, so in between Argo holds no credential for
  the repository it reconciles from. That secret is the link between the
  plane and its directory, and it is the one piece the composite
  deliberately cannot fill.
- **An org policy nothing enforces.** Neither
  `compute.skipDefaultNetworkCreation` nor
  `iam.disableServiceAccountKeyCreation` is composed, and
  `seed-grant-org-roles` grants `orgpolicy.policyAdmin` to an identity
  that never spends it — but only one of the two is exposed by that. GCP
  enforces the key ban at the organisation by default, which is why
  `_gcp-allow-sa-keys` reads the effective policy and does nothing where
  it is already off, so the ban holds wherever a folder is handed over
  and
  [plane-install](../recipes/infra/plane-install.md)
  is imprecise about where it comes from rather than wrong about it
  holding. The default network has no such default: `cloud.just` set it
  at the organisation, and a folder elsewhere gets a default VPC in
  every project until somebody sets it again.

## Where this stands

**The pivot is done.** `qw01` is reconciled by the management plane it
describes, from the manifest committed to `installations`, and the
throwaway plane that built it has been discarded.

Four facts, replacing the five this section carried while it was being
built.

**It reconciles itself.** Forty-seven managed resources on
`gke-qw01-c-mgmt`, all Synced and Ready, composed from an
`XQueenswoodInstallation` that Argo pulls from the private repository.
Argo brings the provider packages, the runtime and provider
configurations, external-secrets and the API itself from this
repository; the manifest naming the installation comes from the other
one. Changing what exists is now a merge.

**Nothing on it holds a key.** The GCP providers authenticate as
`sa-qw01-platform` through Workload Identity, external-secrets as
`sa-qw01-c-secrets`, the nodes as `sa-qw01-c-nodes`. The one credential
in the design — a GitHub App private key, so Argo can read a private
repository — lives in Secret Manager and is fetched by an identity with
no key of its own. Nothing was placed on the cluster by hand.

**The adoption was asserted, not assumed.** One folder, one management
project, the same node: nothing created where something should have
been adopted, which is the failure both `createFolder.folderId` and
`management.adopt` exist to prevent and which would otherwise look
exactly like success.

**Nothing is liened.** `liens list` on the management project returns
nothing. The declarations are all in place — `managementPolicies`
without `Delete`, `deletionProtection`, `deletionPolicy: PREVENT` —
with nothing underneath them, which inverts
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)'s
own position that the lien matters more than the policy, because a
policy is a convention a later edit can undo. That is deliberate rather
than outstanding, for the reason [what is left](#what-is-left) gives.

## What is left

Small, and none of it blocking:

- **Liens**, above, deferred on purpose. A lien makes every teardown two
  acts — lift it, then delete — which is the point of one, and the wrong
  trade while the installation is still being rebuilt to test that it
  can be. They go on when it stops being rebuilt, and until then the
  `managementPolicies` are what protects the foundations. Nothing
  composes a lien either, so it is a recipe when it comes.
- **Labels** carrying the installation code on the folder and the
  management project, which is what would let a bare login find an
  installation without knowing a random suffix, and would retire the
  project-id prefix match in `plane-ctx`. A `Project` takes
  labels; a `Folder` takes only tags, which are a separate mechanism
  with their own resources, so the project alone may be enough.
- **Resource requests** on what runs on the management cluster. Nothing
  declares any, so the scheduler reads the node as two-thirds empty
  while it is nearly full, and eviction order is backwards — the pods
  that matter are `BestEffort` and go first.
- **Argo holds `container.admin` on every instance project**, which is
  what lets it write the RBAC any chart shipping an operator carries —
  the FoundationDB operator is what proved a deployer that cannot is a
  deployer that cannot deploy one at all. The same role carries
  `container.pods.create`, so that identity can port-forward and exec
  into any pod, and create and delete clusters in the project. The
  narrower shape is `container.clusterViewer` plus a Kubernetes
  `ClusterRoleBinding` on the cluster itself, which moves the authority
  into RBAC where a diff shows it — and needs something able to write
  RBAC on an instance cluster before it can be built, which is the
  machinery this defers. Before a production instance, and only
  recorded until now in a comment in the composition.
- **`sa-qw01-nodes` renamed but the cluster not.** `gke-qw01-c-mgmt` and
  `np-qw01-c-mgmt` keep names that predate the rule in
  [cloud-naming](../recipes/practices/cloud-naming.md), because renaming either
  destroys the cluster. New installations get the shorter forms.

Then the durable tier and the first instance, both described under
[after the pivot](#after-the-pivot).

## Why the XR is reusable, and what makes it fragile

The pivot re-applies the same XR to a different cluster. What makes that
an adoption rather than a second installation is one annotation.

`crossplane.io/external-name` maps a managed resource to the cloud
object it stands for. It lived in the kind cluster's etcd. Applied to a
fresh cluster, every MR is created with that annotation empty, and the
provider has no idea the GCP object exists.

For most resources this is harmless, because the external name defaults
to `metadata.name`, and the composition pins those deterministically:
`vpc-qw01-c-mgmt`, `gke-qw01-c-mgmt`, `np-qw01-c-mgmt`. The provider
observes, finds the object, and adopts it. This is the second reason for
[cloud-naming](../recipes/practices/cloud-naming.md)'s rule that
Kubernetes names
are the same names — the recipe gives the cross-reference reason, and
re-adoption is the one that bites at a pivot.

Only three resources set the annotation explicitly, and two of them are
the exceptions that fail in opposite directions:

- **Folder** — its GCP identifier is a number GCP assigned, not the
  display name, so an empty annotation leaves nothing to look up and the
  provider creates a **second folder**. GCP permits the duplicate
  display name, so nothing objects. `spec.createFolder.folderId` is what
  prevents it.
- **Project** — GCP answers permission denied, never not found, for a
  project id you do not own, so the provider cannot distinguish absent
  from someone-else's and never gets past its first observation to
  create. `spec.management.adopt` is what prevents it, and it is
  deliberately a separate field from `projectId` because creation and
  adoption cannot share one.
- **Platform identity** — set explicitly because the Workload Identity
  member string elsewhere in the composition has to spell the same
  address.

So the XR is reusable exactly to the extent that it carries its own
adopt values, and today those exist only as arguments somebody typed.
That is why committing the manifest is part of the pivot rather than
tidying afterwards.

The rest of what must perpetuate is in the XR already — billing account,
parent, code, region, zone, the access mapping, the management project
id — and perpetuates for the same reason: it is in the file, or it is
lost.

## The pivot

### 1. Widen the boot plane

`boot-cluster-up` installed a narrowed set of packages, chosen when the
composition stopped at a folder. It now takes `provider-helm` as well,
which is what lets the boot plane install onto the management cluster
in step 3, and applies a `ProviderConfig` for it.

**How that config reaches the cluster without a key**, which was an
open question and is not: `provider-helm` splits the problem in two.
`credentials` is a kubeconfig Secret carrying the endpoint and the CA
and no token, and `identity`, typed `GoogleApplicationCredentials`, is
what actually authenticates — pointed at the same impersonated ADC the
GCP providers already use. So the boot plane holds no key for the
cluster either, and the pattern is not new: `helm-provider-config.yml`
has used exactly this shape against the current deployment.

The kubeconfig is the composed `Cluster`'s own connection Secret, which
the provider writes on every reconcile, so nothing has to run
`get-credentials` and no recipe holds the endpoint. That is one more
reason a v2 namespaced MR suits this: it writes the Secret into its own
namespace, which is where the config looks.

`providers.yml` itself was short of two the durable tier will need:
storage and secretmanager, for the `Bucket` and `BucketIAMMember`
ADR-0022 names by hand.

The boot plane installs a narrowed subset of that file, and what
belongs in it is **the providers whose kinds the composite composes**,
not the providers an instance eventually needs. That distinction was
learned the hard way: composing a Secret Manager container for the
plane's own credential put a `secretmanager` kind in the composite
while the boot plane had no definition for it, and the whole pipeline
stopped with `no matches for kind "Secret"` — not that resource alone,
every resource. A composed kind the applying plane cannot store is a
composition that cannot run.

ADR-0022 names a third, orgpolicy, for the `Policy` that exempts a
project from the key-creation ban and for `HMACKey` alongside it. Left
out: the exemption needs a role granted only at the organisation, which
[the durable tier](#the-key-ban-and-the-proxy-that-answers-it)
takes as unavailable and designs around, and the other org policy in
play — `skipDefaultNetworkCreation` — was thought to be answered by the
project's own `autoCreateNetwork: false`, and is not: GCP creates the
default network when the Compute API is enabled rather than when the
project is created, so a composed project enables its APIs an hour
later and the flag is satisfied at a moment when there is nothing to
suppress. Both projects acquired one.

The conclusion survives the correction, for a different reason. A
`Policy` needs `orgpolicy.policyAdmin`, which is granted at the
organisation and nowhere else, so the plane cannot hold it without
being able to weaken any constraint anywhere in that organisation.
`just gcp-org-enforce-constraints` does this as a person instead. A package the
composition composes nothing from is still a provider pod for
nothing.

### 2. The platform identity's rights

The management plane runs as `sa-qw01-platform` through Workload
Identity. **This is where the work now starts.**

**Done: the billing account.** `roles/billing.user` is bound, by
`plane-billing-role`. It could not come from the composition — a
billing account sits above the folder, and `sa-qw01-boot` holds
`billing.user` rather than `billing.admin`, so it cannot delegate what
it was given. That made it the same seam as `seed-grant-org-roles`: a
recipe run once by a billing administrator, which is a person and
deliberately so.

**Declared: everything inside the folder**, which the identity does not
hold until a plane applies it. `just plane-status` prints all
three scopes. Derived from what the composition creates now and what an
instance adds — project, network, cluster, database, service accounts,
address, certificate:

- On the folder — `projectCreator`, `folderIamAdmin`, and compute,
  container, storage, DNS and CloudSQL administration. Assembled from
  predefined roles rather than `folderAdmin`, which is what GCP gave the
  boot identity and is more than this should hold.
- On the management project — `secretmanager.admin`.

Storage is bound once, on the folder, rather than at both scopes. A
folder binding inherits into every project beneath it, so the
project-scoped grant this plan first listed alongside it would have
been a second copy of a right the identity already held — and the
folder form is the one that also covers a bucket an instance turns out
to want.

`projectCreator` is what makes the rest narrower than it reads. GCP
makes the creator of a project its owner, so an instance project is
administered by having built it; the folder-scoped roles are what
reach the projects this identity did not build.

**`orgpolicy.policyAdmin` is not here at all, and cannot be.** It was
written project-scoped, then folder-scoped, and GCP refused both with
`Role roles/orgpolicy.policyAdmin is not supported for this resource` —
a 400 declining the scope rather than a permission the boot identity
was missing, so nothing granted upstream would have made it work. The
role is organisation-only, which puts it on the same seam as
`billing.user`: granted above the folder, by a person, and therefore a
recipe rather than a composed resource.

Nothing needs it until the durable tier, so it is settled there rather
than guessed at here — see [After the pivot](#after-the-pivot). Note
that `roles/owner` deliberately excludes org policy administration, so
creating the project that needs the exemption does not confer the right
to exempt it.

The general form is worth carrying. A role has a set of resource types
it may be granted on, that set is not the same as the set of resources
the feature acts on, and a scope outside it fails at the API with a 400
rather than by producing a binding that silently grants nothing. Loud,
but only once something applies it — which is the argument for applying
a composition change rather than reading it.

Both are `FolderIAMMember` and `ProjectIAMMember`, which the access step
has proven against this provider version. They are applied by the boot
plane, which holds `folderAdmin` and so can grant them.

Note the shape this repeats: the composition's `access` step already
emits exactly these kinds for the four human capabilities, keyed off
`spec.access`. The platform identity is a fifth principal with a fixed
role set rather than a configurable one, so it belongs in
`patch-and-transform` beside `platform-identity` rather than in the
go-templated access step.

### 3. Crossplane and Argo onto the management cluster, declared

Two `Release` resources of `provider-helm`, composed by
`XQueenswoodInstallation` and applied from the boot plane: Crossplane
and Argo CD. **Both are written.** What Argo reconciles from is not
part of them — putting Argo on the cluster and pointing it at a
repository are separate, and only the first is a `Release`.

This is what keeps the installer at four commands. `boot-mgmt-apply`
applies the composite, so anything the composite composes arrives in
that one step — where a recipe running `helm upgrade` against the new
cluster would be a fifth, and an imperative one in the middle of the
path [ADR-0016](../adr/0016-crossplane-over-terraform.md) exists to
keep declarative.

The `DeploymentRuntimeConfig` and the `ClusterProviderConfig` do not
travel with that Release, because a `Release` installs a chart and
neither is a value of Crossplane's. They arrive the way everything else
in a cluster running Argo arrives: applied from the repository, as
[infrastructure](../tdd/infrastructure.md) already has
`crossplane-configs` do for the current deployment.

**Who owns these chart versions.** Each is pinned twice — the `xp-mp`
chart's dependency, which is what Renovate bumps, and the composed
Release — and `scripts/check-versions.sh` holds the two equal. Renovate
sees the dependency and not the Composition, so a bump it made alone
would fail that check rather than land; both copies move by hand, in
one change. Renovate is turned off for both charts rather than left to
produce a PR that cannot go green.

Crossplane carries a second reason, and it is the sharper one. The boot
plane runs one Crossplane and the management plane runs the other, and
step 5 hands a live composite from one to the other, so a skew surfaces
at the handover rather than at an upgrade — and a control-plane version
also has to agree with the provider and function packages it runs,
which Renovate bumps separately.

Declared rather than helm-installed by a recipe, because the management
plane's own existence is part of what the manifest describes — and
because a rebuild then means applying the manifest rather than
remembering a sequence.

What the management cluster needs beyond those two releases does not
travel in a release, because a `Release` installs a chart. It arrives
the way everything in a cluster running Argo arrives — from a
repository — and `infra/helm/management-plane/` is the chart that
carries it:

- A `DeploymentRuntimeConfig` pinning the GCP providers' Kubernetes
  service account to `crossplane-provider-gcp`. The XRD's
  `management.providerServiceAccount` field already assumes this and the
  Workload Identity binding already spells it, but nothing creates it.
  Left to Crossplane the name is generated, and the binding matches
  nothing. Each GCP provider names it through `runtimeConfigRef`.
- **The annotation that binding is useless without.** Workload Identity
  is two halves, and only one of them was ever written down here:
  `roles/iam.workloadIdentityUser` on the GCP side, from step 2, and
  `iam.gke.io/gcp-service-account` on the Kubernetes service account.
  Without the second, a token minted for that account is exchangeable
  for nothing. It goes on the runtime config's
  `serviceAccountTemplate`.
- A GCP `ClusterProviderConfig` with `credentials.source:
  InjectedIdentity` rather than a secret. No key, and no ADC — which is
  the whole point of the platform identity existing.
- A helm `ClusterProviderConfig`, so this plane can observe the two
  releases it inherits. It has the same name as the boot plane's,
  because the composed releases name it by value, and deliberately not
  the same content: the boot plane reaches this cluster across a
  network with a kubeconfig and an impersonated identity, and this one
  is already inside it.
- Argo `Application`s for the provider packages and for the XRD and
  Composition. That is what gives the plane the kinds it reconciles
  with and the API it reconciles — and nothing else. The manifest
  naming an actual installation is in a private repository and arrives
  in step 5, so this plane knows the shape of an installation and holds
  none.

Waves order it, and the order is not the obvious one. The runtime
config goes first, because a provider Deployment takes whatever service
account exists when it starts — but the *provider* configurations have
to go after the packages, because they are custom resources of the
kinds those packages install. Put them first and Argo rejects the whole
sync with `one or more synchronization tasks are not valid`, which is
what it says when a kind is unknown to the cluster. So: runtime config
0, packages 1, provider configurations 2, the API 3.

Two further things that ordering alone does not fix. Argo validates
every task before applying any, so a resource whose CRD arrives in an
earlier wave still fails unless it carries
`SkipDryRunOnMissingResource=true`. And a Crossplane package install is
asynchronous — Argo reports the `Provider` applied long before its CRDs
are registered — so the Application needs a retry that outlasts an
image pull rather than the default handful of attempts.

**Nothing applies that chart by hand.** The root `Application` is
carried in the Argo release's own `values.extraObjects`, and the
composition patches the installation's code and management project into
it. So a management plane is still one act rather than a sequence, and
the only repository involved is the public one — nothing here needs a
credential.

**Which repository is a manifest value, not a constant.**
`management.source` carries the URL, the revision and where this
project sits inside that repository, all defaulted. A hard-coded URL
would be a defect rather than an inconvenience for anyone running a
fork: the composition travels with
the fork, so their boot plane would build their cluster and then point
Argo at *upstream's* manifests, and every change they had made would be
invisible on it. The revision matters for a different reason — `main`
is a moving target for a plane meant to be durable, and a tag is not
expressible without this. The path prefix is there for a mirror that
vendors this project under a directory rather than forking it whole,
which is the common corporate shape: three paths are hard-coded across the root
Application and its two children, and one prefix moves all of them.

Each is patched twice, into the root Application's own source and into
the values it hands the chart, so an installation has one answer rather
than two.

The prefix earns its place now rather than later for a reason worth
generalising: a field added to `management.source` after manifests
exist leaves every one of them silently defaulted, and the record stops
being the whole account of what exists. The one committed manifest
already demonstrates it — written before `source` existed, it carries
none, and the installation runs on XRD defaults.

**The self-management loop, and its answer.** Once the management plane
adopts the composite it owns the Release that installs it. A composition
edit that removed or upgraded that Release would have Crossplane acting
on itself mid-reconcile, and a delete would be unrecoverable without
raising the boot plane again. So those Releases carry
`managementPolicies: [Observe]` on the management plane: it watches its
own installation and never acts on it, and changing Crossplane or Argo
becomes a deliberate act from the boot plane. That is
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)'s
"foundations are observed" applied one level up.

**How one document does both**, since step 5 applies the same composite
to both planes and a composition cannot ask which plane is running it.
`management.bootstrap` is set by the plane installing the management
plane and left out of the committed manifest — the same seam
`billingAccountId` uses, and for the same reason: it is true of an act
of creation rather than of the installation. The base carries
`[Observe]`, because a patch whose source is absent is skipped, so the
safe value is what a manifest without the field produces. Both cases
are spelled in the patch, so setting it to `false` says what leaving it
out says.

`boot-mgmt-apply` passes it, and nothing else does. That is what makes
"the boot plane may install the management plane, and the management
plane may not reinstall itself" a property of who is applying rather
than a rule someone has to remember.

**Both Releases are `helm.m.crossplane.io`, never `helm.crossplane.io`.**
provider-helm installs both groups, and the composite is namespaced, so
the legacy cluster-scoped kind fails the whole pipeline with `cannot
apply cluster scoped composed resource ... for a namespaced composite
resource` — every composed resource stops, not just the offending one.
It also survives one reconcile before doing so, since a create and an
apply are different paths, which makes a first green run no evidence at
all. `kubectl api-resources | grep -i helm` distinguishes them: the
NAMESPACED column, not the version. The rule generalises — prefer the
`.m.` group for every provider, and treat an API group copied from
`crossplane-configs/` as belonging to the previous generation.

### 3b. A way to read a secret

Argo cannot reach the private repository without a credential, and an
operator holding `platformViewer` cannot place one — writing a Secret to
`argocd` needs `clusterAdmin`, which is break-glass. So the credential
has to arrive the way everything else does, and the operator that
delivers it is `external-secrets`, installed by Argo from the upstream
chart and authenticated by Workload Identity.

**A second identity, `sa-<code>-secrets`**, holding
`secretmanager.secretAccessor` on the management project and nothing
else. The platform identity already holds `secretmanager.admin` and
could do the reading, but it can also create projects and administer
every cluster in the folder — so an operator whose whole job is
fetching a secret would be holding the installation. That is the same
argument that made the platform identity worth separating from the boot
identity.

**A `ClusterSecretStore`** naming the management project, with no auth
block: the controller's pod carries the annotated service account, so
the provider takes the identity it already has. The same property the
GCP provider configuration has, reached the same way.

**The credential itself is two human acts**, and they are the last two
in the chain. The GitHub App is created by a person in a UI, because
GitHub has no API that creates one — see
[plane-install](../recipes/infra/plane-install.md) —
and `argo-github-app-secret` writes its three values into Secret Manager
as one JSON entry, run by a person holding `secretsAdmin`. The
identifiers travel with the key rather than through a second channel, so
nothing about an installation's GitHub App reaches either repository.

The container is composed; only the version is placed. What exists is
declared, and what it holds never touches git.

**None of it is composed for an installation that has no manifest
repository.** `management.manifestRepoURL` is absent by default, and
the chart renders no `ExternalSecret` without it — an installation
applied only from a boot plane has nothing to read and needs no
credential to read it with.

**An API nobody enabled.** The management project enables the six APIs
it needed when the composite stopped at a cluster, and composing a
Secret Manager container added a seventh without adding the service to
enable it. The container sat `Synced: False` for an hour with a 403
saying the API had never been used — legible, but only to somebody
looking at the managed resource rather than at the thing that depended
on it three steps downstream.

The rule is the same one the boot plane's provider set follows: what
the composite composes decides what has to exist first. A provider for
the kind, on the plane doing the composing, and an enabled API on the
project being composed into.

**A parent that carries resources fails on behalf of its children.**
Argo cannot build a sync task for a kind the cluster does not have --
`SkipDryRunOnMissingResource` skips the dry run, not the apply -- so a
chart holding both a child Application and a custom resource of a kind
that Application installs deadlocks: the parent fails on the resource,
never applies the child, and the child is what would have registered
the kind. It cost a hand-applied patch to break, and hand-applying
needs cluster write access the access model withholds.

So the parent holds Applications and the one resource whose kind
Crossplane itself provides, the `DeploymentRuntimeConfig`. Everything
of a kind installed by something else is a child Application of its
own, where a missing kind is that child's failure and its retries
outlast an operator install.

Two second-order traps in the same area. An Application that exhausts
its retry budget stops on its own until the revision changes or
somebody syncs it -- so a fix landing in git after the budget ran out
still needs a nudge, and a nudge is cluster write access again. And a
failure at any wave stops the waves after it, so one unresolvable
resource holds back everything downstream of it rather than only
itself.

**A CRD large enough to break the apply.** external-secrets' own
`SecretStore` and `ClusterSecretStore` definitions carry every
provider's schema and exceed 256KB, and Argo's default client-side
apply writes a copy of the whole resource into
`last-applied-configuration` — which the API server then rejects as
`Too long: may not be more than 262144 bytes`. Twenty-three of the
twenty-five CRDs install, the two the controller actually needs do not,
and it crash-loops looking for kinds nothing registered. The
Application carries `ServerSideApply=true`, which does not write that
annotation at all.

Worth generalising, because it is invisible until it happens: any chart
whose CRDs are large wants server-side apply, and the symptom is a
crash-looping workload rather than a failed CRD.

**Which changes the character of what follows.** Every chain up to here
converged on its own given time. This one has a person in the middle:
the `ExternalSecret` cannot succeed until the key is stored, and the
Application reading the private repository cannot succeed until that
Secret exists. Both fail and retry exactly as the missing-CRD race did,
so a failing `ExternalSecret` means nobody has done the GitHub step yet
rather than that something is broken. Worth knowing before it is
debugged as a fault.

#### An identity per cluster, not per installation

`sa-qw01-nodes` and `sa-qw01-secrets` claimed names every instance would
want. Service accounts otherwise carry no environment segment, and that
holds for the ones above the folder — `platform` runs the whole
installation, `boot` sits outside it — but a node identity and a
secrets-reading identity belong to *a cluster*, and every cluster wants
the same job done. So they take the environment the rest of the scheme
already uses: `sa-qw01-c-nodes` and `sa-qw01-c-secrets` here,
`sa-qw01-d-nodes` and `sa-qw01-p-secrets` for instances.

The secrets identity matters more than the tidiness. One account shared
by every cluster's external-secrets means an instance's operator can
read another instance's credentials, which is the same split
[ADR-0023](../adr/0023-installation-naming-and-access.md) records for
capabilities, arriving at the identity layer.

Renamed before the handover rather than after, because
`nodeConfig.serviceAccount` is immutable: the rename replaces the node
pool, and doing it while nothing depends on the old name costs one
outage instead of a migration.

#### What an instance repeats

Its own node identity and its own secrets identity, for the reasons
above, and permission to attach them. That last is free for an
instance and was not for the management cluster: the plane that creates
a project owns it, and an instance project is created by the plane that
then runs it, where the management project was created by the boot
plane and handed over.

#### Rights the boot plane has by accident

GCP makes whoever creates a project its owner, and the composite
creates the management project as the boot identity. So the boot plane
holds `roles/owner` there — not from anything declared, but from having
built it.

That matters because owner quietly covers things the composition never
grants. Attaching a service account to a node pool needs
`iam.serviceAccounts.actAs` on that account, and nothing binds it: the
boot plane can do it only because owner includes it. The platform
identity inherits none of that. It holds `projectCreator`, so it owns
projects *it* creates, and the management project is not one of those —
so the first node pool rebuilt after the handover would fail with "does
not have permission to act as service account", on a plane with no boot
plane left to fix it.

Two are now composed: `iam.serviceAccountUser` on the node identity,
and `iam.serviceAccountAdmin` on the management project — the
composition creates three service accounts and no folder role it holds
carries `iam.serviceAccounts.create`, so that worked only because the
boot plane owns the project it built. Found by trying to delete a stray
account and being refused, which is a thin thread to have found it on.

A sweep of every permission the composition needs against every role the
platform identity holds found two more, and they are the two it cannot
run without: `resourcemanager.projects.setIamPolicy`, which every
`ProjectIAMMember` needs, and `serviceusage.services.enable`, which
every `ProjectService` needs. Both now composed as
`projectIamAdmin` and `serviceUsageAdmin` on the management project.

The circularity is what makes them worth naming rather than just
fixing. The binding that would repair a missing `projects.setIamPolicy`
is itself a `ProjectIAMMember`, so a plane that lost it could not grant
it back, and the plane that could is discarded at the end of the pivot.

Two remain unfixed and deliberately so: `resourcemanager.projects.update`
and `resourcemanager.folders.update`, which the display-name patches
would need. The role carrying them is `projectEditor`, far too broad for
a field set once at creation and not usefully changed afterwards. So a
display name is fixed after handover, which is a limitation rather than
a fault.

The general form is the thing to keep:
**a right the boot plane has by accident is a right the management
plane will discover it lacks**, and it discovers it at the worst
moment, because the boot plane is what would have fixed it. Worth
checking for at step 5 rather than after step 6.

### 4. The manifest in git — done

The private `installations` repository holds
`qw01/installation.yml`, rendered by `queenswood-installation-manifest` and carrying
its own `createFolder.folderId` and `management.adopt`. **This step is
now complete**: `boot-mgmt-apply` applies that file rather than
assembling a composite from five arguments, so the boot plane and Argo
consume the same document and cannot disagree about what the
installation is.

Two fields never enter it, and are merged client-side at apply time
with `kubectl patch --local`, which needs neither a cluster nor a YAML
processor: `billingAccountId`, because creating a project is the one
moment it is needed, and `management.bootstrap`, because installing the
management plane is something only the boot plane may do. Both are true
of an act of creation rather than of an installation, which is why
neither belongs in the record. `queenswood-installation-manifest` cannot emit either
of them at all — a manifest that carried `bootstrap` would hand the
management plane the right to reinstall itself.

Reading the file rather than taking arguments is also what makes the
boot path credential-free: the operator has a checkout, so it is their
own access to the private repository that is used. Argo needs a
credential of its own, which is step 5's problem and not this one.

Branch protection requiring review is the control that repository has,
deliberately, since it carries nothing executable — a direct push to its
main changes infrastructure with nothing in the way.

Note that the three values `boot-mgmt-apply` prints are not the whole
set that has to be frozen. The rest — the code, the domain the group
addresses are built from, the billing account — are *discovered* at
apply time, from a constant in the justfile, from the organisation
lookup, and from whichever billing account happens to be open first.
That holds while there is one organisation and one open billing
account, and stops holding silently rather than loudly.

Argo syncs from merged state only, and a `pull_request` trigger gets no
cloud identity: a merge is the privileged action, and otherwise a fork's
pull request runs as the platform identity.

#### Which repository, and why it is a second one

The manifest is nothing but identifiers and this repository is public.
The most sensitive of them, the billing account id, leaves the composite
altogether rather than being hidden — see below. What remains is the
project hierarchy: the organisation id in `createFolder.parent`, the
folder id, and the management project id with its random suffix. So the
manifests live in a private `installations`, which Argo reconciles
from with a deploy key. **Public is how, private is what**:
the XRD, the Composition, `providers.yml`, the charts, the recipes and
the ADRs stay here, because they are the part with value to a reader;
what moves is a list of identifiers with value to nobody but the
operator.

**That repository holds data and nothing executable** — no workflows,
no actions, no scripts. Argo pulls, so nothing in it needs to run and
nothing in it needs a credential: the deploy key is read-only, lives in
the cluster, and points outward. The only thing able to act on GCP is
the management cluster.

That is stronger than the rule
[plane-install](../recipes/infra/plane-install.md)
states today. "A merge is the privileged action, so merged state applies
and a `pull_request` trigger gets no cloud identity" exists because the
alternative was push-based CI holding one. Pull-based and data-only
means no cloud identity in GitHub at all, and the caveat stops needing
to be remembered.

It costs pre-merge validation: with no workflow, an invalid manifest
merges and fails at Argo rather than in the pull request. Nothing
half-applies — the API server rejects a composite that violates the
XRD's schema — but the failure surfaces in the cluster. A local recipe
doing a server-side dry-run against the XRD keeps the check without
putting anything executable in the repository, and branch protection
requiring review is the control that belongs there, a merge being what
reaches production.

This is a seam the design already has rather than a new one.
[plane-install](../recipes/infra/plane-install.md)
says the manifest lives "in whichever repository the applier reconciles
from", allowing that it is not this one, and
[ADR-0023](../adr/0023-installation-naming-and-access.md) assumes
installations built in organisations we do not own, from folders handed
to us, with groups named nothing like ours. A manifest for one of those
would never live here. Queenswood's own installation has simply been
getting special treatment as the only tenant.

The alternative considered and rejected was keeping the manifest here
and sourcing the sensitive fields from an `EnvironmentConfig` or a
Secret. It works, and for two fields it is proportionate, but it spends
three things that a second repository does not:

- **The record thins.** The manifest is meant to be the whole account of
  what exists. Fields held elsewhere leave it the account minus those
  fields, without making anything secret — everything it describes stays
  visible to anyone holding Browser on the folder.
- **`pass` comes back.** An out-of-band object with no history and no
  review is the previous generation's single machine with a better
  mechanism, which is what
  [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  set out to escape.
- **Review is lost, and review was the point.**
  [ADR-0023](../adr/0023-installation-naming-and-access.md) puts the
  access mapping in the manifest precisely so two capabilities
  collapsing onto one principal "is written in the manifest and read in
  a pull request, rather than emerging from a policy nobody opens".

It also does not scale to what comes next: instance manifests want
domains, address ranges and database sizing, and each of those reopens
the argument about which fields are exempt. A private repository settles
it once.

The cost is cross-repository atomicity. A composition change needing a
simultaneous manifest change spans two repositories and cannot land as
one commit. Keeping new XRD fields optional with defaults is what makes
that rare, and is good discipline regardless.

The test that decided this still governs what goes in *this* repository:
**does exposure cost anything?** The code, the region, the folder
display name and the access group addresses all fail it — the ADRs and
[organisation-foundation](../recipes/infra/organisation-foundation.md)
publish the naming
convention in full, so a redacted worked example may stay here and be
useful. The billing account id and the project-hierarchy identifiers
pass it.

On what has already leaked: management and seed project ids are printed
in [cloud-naming](../recipes/practices/cloud-naming.md)'s worked example. A
rebuilt installation takes new random suffixes, at the cost of consuming
the old project ids permanently — which
[ADR-0023](../adr/0023-installation-naming-and-access.md) already
accepted once, for this reason. So that is an argument for rebuilding
eventually rather than for treating the current ids as recoverable
secrets.

#### The billing account is a property of the identity

It does not go in the manifest at all, and the reason is
[ADR-0020](../adr/0020-providers-are-deployment-facts.md)'s one layer
down: a value that selects behaviour belongs in deployment rather than
in a request. The identity must hold `billing.user` on an account
before it can link a project to it, so that binding already settles
which account is used. A field restating it is a second copy that can
disagree with the first.

Three facts make this cleaner than it first looks:

- **A folder has no billing account.** Billing attaches to projects, and
  a folder is only a hierarchy and IAM node — which is why projects are
  portable. There is nothing to inherit.
- **`billing.user` is bound on the billing account, not the folder**,
  which `seed-create` already does. Linking a project needs
  `resourcemanager.projects.createBillingAssignment` on the project and
  `billing.resourceAssociations.create` on the account.
- **The identity can list what it is bound to.** Where an organisation
  hands over a folder and an identity, that list has exactly one entry.
  Discovery through the identity is therefore *more* reliable than the
  current `boot-mgmt-apply` behaviour, which takes the first open
  account visible to the operator, who may see several.

Discovery is confirmed rather than assumed: `gcloud billing accounts
list`, run as the boot identity, returns exactly one open account. Run
as the operator it may return several, which is why `boot-mgmt-apply`
taking the first one is fragile today and stops being so here.

The design is one shape, not a choice between mechanisms:

```
composition   never declares billingAccount
creation      the boot plane links, the account discovered from the
              identity's own binding
steady state  late-initialisation owns the field
the manifest  says nothing about billing at all
```

**The composition never declaring it is the invariant**, and what
forces that is server-side apply. Field ownership on the live Project
shows two managers: the composition owns `autoCreateNetwork`,
`billingAccount`, `deletionPolicy`, `folderIdRef`, `name` and
`projectId`, while `folderId` is owned by Crossplane's reference
resolver. `folderId` survives precisely because the composition has
never once set it. A field the composition *does* own and then stops
setting is deleted on the apply that relinquishes it, since SSA removes
a field its sole owner no longer declares.

That makes removing the patch from the *existing* resource a one-time
transition rather than a steady state: the field is dropped, late-init
writes it back under its own manager, and subsequent applies never
reclaim it. The hazard is only inside that window, and only because the
composition owns the field today — a composition that had never set it
would look like `folderId` from the start.

**Creation is the case late-initialisation cannot cover**, because it
can only reflect a link that already exists. A GCP project may be
created unbilled and linked immediately after, so the boot plane does
that once, as the identity, against the account it just discovered.

`billingAccountId` then leaves the XRD's `required` list, and its
docstring says it is discovered from the identity's bindings.

A general hazard worth carrying beyond billing: **every field this
composition sets, it owns.** Deleting a patch later deletes the field
from the resource, so a patch is not a free thing to add.

##### Tested, and it holds

Run against the live management project with its `managementPolicies`
cut to `[Observe, LateInitialize]`, so nothing could reach GCP — both,
because `LateInitialize` is itself a policy and `[Observe]` alone would
have disabled the thing under test.

Dropping the patch moved the field to a third manager:

```
before   composition   autoCreateNetwork billingAccount deletionPolicy
                       folderIdRef name projectId
         resolver      folderId

after    composition   autoCreateNetwork deletionPolicy folderIdRef
                       name projectId
         resolver      folderId
         provider      billingAccount
```

So late-initialisation does own it, under the provider's own field
manager, and the design holds: the composition stops declaring the
field, the provider keeps it, and the manifest carries nothing about
billing.

The value stayed populated in every three-second sample, so no
transition gap was observed — which is not the same as proving there
was none at finer resolution. Treat the window as real but very short,
and do the change once, deliberately, rather than assuming it is free.

Three field managers is the model to carry: the composition owns what
it patches, Crossplane's reference resolver owns what it resolves, and
the provider owns what it late-initialises. Ownership is what decides
whether a field survives a patch being deleted.

##### Done for qw01, and what it did not prove

The live installation has crossed over: the XRD makes the field
optional, the XR carries no `billingAccountId`, the provider owns
`forProvider.billingAccount` alone, and GCP still bills the project.
The composition keeps its patch, which now fires only when a manifest
supplies the field — which `boot-mgmt-apply` does at creation and the
committed manifest never does.

Two things that run did **not** establish, both worth knowing before
trusting it again:

**The dangerous transition was not exercised, and it is not where it
first appeared to be.** Running the spike had left the field owned by
*both* the composition and the provider, since restoring the real
composition re-claimed it alongside late-init. With two owners, one
relinquishing cannot remove the field, so no window opened.

Where the window actually is: the composition owns `billingAccount`
only when a composite supplies `billingAccountId`, and the only thing
that supplies it is `boot-mgmt-apply`, at creation. So the window is
the *first reconcile from the committed manifest* after a new
installation is created — the moment the composition stops declaring
what it declared at creation. That is a fresh-installation concern, on
every new installation, and it was never qw01's pivot: an existing
project reconciled from a manifest that never carries the field means
the composition never owns it in the first place.

qw01 is past it permanently for the same reason. Note also that the
handover's effect was entirely cluster-side — the XRD, the composite
and the field ownership all went with the boot plane when it was
deleted. What persists is the committed code and GCP itself, which is
the design working rather than a loss.

**Guarding it by patching the resource does not work.** The handover
script cut the project to `[Observe, LateInitialize]` with `kubectl
patch` and the composition reverted it within seconds, because
`managementPolicies` is set in the composition's `base` and is
therefore a field the composition owns. The guard was inert for the
whole run. The spike worked because it changed the *composition*; that
is the only thing that changes a composition-owned field.

So the ownership rule has now caught three separate things — the
billing account, the readiness of go-templated resources, and a safety
mechanism built to protect against it. Treat "who owns this field" as
the first question about any composed resource, not a detail.

This does not retire the private repository: `createFolder.parent`
still carries the organisation id, and a folder's parent is required
rather than late-initialisable. It does mean the repository holds one
identifier rather than the most sensitive one.

#### The layout

The repository is `installations`, named for what it holds — an
installation being the unit
[ADR-0023](../adr/0023-installation-naming-and-access.md) settled, and
naming a thing for what it acts on being the rule everything else here
follows. No product in the name: a second product's installations sit
beside Queenswood's without it lying, and the boundary that matters is
the audience rather than the product, since access is per repository.
One belonging to a different operator wants its own.

```
installations/
├── README.md          what this is, where the schema lives, and
│                      that it holds no credentials
└── qw01/
    └── installation.yml     the XQueenswoodInstallation
```

`just queenswood-installation-manifest` prints that file, resolved, on stdout with
every message on stderr, so it redirects straight into place. A second
installation is `qw02/`. `QW_INSTALLATIONS_REPO` in `gcp.just` says where
the checkout is, defaulting beside this one.

**Argo's wiring stays in this repository**, beside the XRD it depends
on: one Application with `path: .` and `directory.recurse: true`. That
keeps the private repository purely data — not merely free of workflows
but free of Argo configuration too. The Application names the private
repository's URL, which is not sensitive.

An ApplicationSet with a git generator over `*/` earns its place when
installations need *different* sync policies, which
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
already anticipates in wanting manual rather than automatic sync for
prod. Until then it is machinery for one directory.

**This layout survives a second cloud**, because the design already put
the seam in the right place: the composite is `XQueenswoodInstallation`
with no provider in its name, and the Composition is
`xqueenswoodinstallation.gcp`, which the XRD names as its default. One
API, one Composition per provider, chosen per manifest. So a provider is
a property of an installation rather than of the tree, and there is no
`gcp/` level — an installation is on one cloud, and its code is already
unique.

Where a second cloud actually bites is the XRD, not the directory
structure. `billingAccountId`, `createFolder.parent` and
`management.projectId` are GCP nouns; AWS has organizational units and
accounts, Azure management groups and subscriptions. Either the XRD
grows a neutral core plus a per-provider block, or there is a second
XRD. The first is the more likely answer given the Composition is
already named for its provider, but it is a decision for when the
second cloud is real, and nothing here prejudges it.

**One repository holds installations that share an audience.** Access
control is per repository, so an installation belonging to a different
customer or operator wants its own rather than a directory. That is the
same seam [ADR-0023](../adr/0023-installation-naming-and-access.md)
draws in assuming a folder handed to us by an organisation whose groups
are named nothing like ours.

Nothing else belongs in there. In particular no credential, for the
reason the next section gives.

#### What the GitOps model says, and where it binds

Checked rather than assumed, because "identifiers in a private
repository" and "secrets in a repository" attract very different
advice and it matters which one this is.

The [GitOps Principles v1.0.0](https://opengitops.dev/) — declarative,
versioned and immutable, pulled automatically, continuously reconciled —
say nothing about everything living in git. They constrain how desired
state is expressed, stored and applied. Secrets are the acknowledged
gap beneath them, addressed by tooling rather than by the principles.

**On splitting the repository**, Argo CD's
[best practices](https://argo-cd.readthedocs.io/en/stable/user-guide/best_practices/)
recommend a separate repository for manifests on five grounds, none of
them about disclosure: separation of application code from application
config, not triggering a build to change a replica count, a cleaner
audit history free of development noise, separation of commit access
between people who write the application and people who may push to
production, and avoiding the loop where CI commits into the repository
that triggers CI. So the split here is an ordinary pattern that happens
to also answer the disclosure question.

**On secrets**, both major implementations say the same thing and it is
stronger than "use a private repository". Flux's
[secrets management guidance](https://fluxcd.io/flux/security/secrets-management/)
states plainly that storing plain-text secrets in desired state is not
recommended, offers Sealed Secrets, SOPS and external-secret operators,
and adds a rule worth carrying: do not co-locate ciphertext with the key
that decrypts it. Argo CD's
[secret management page](https://argo-cd.readthedocs.io/en/stable/operator-manual/secret-management/)
goes further and prefers destination-cluster secret management — Sealed
Secrets, External Secrets Operator, the Secrets Store CSI Driver, Vault
Secrets Operator — because then Argo never holds the secrets at all.

That page also carries a fact that settles the alternative rejected
above on independent grounds: **Argo stores generated manifests in
plaintext in its Redis cache**, so injecting a secret during manifest
generation leaks it into that cache. Encrypting the sensitive fields
into the public manifest and decrypting them through a config-management
plugin would have done exactly that.

Where this binds is not the manifest. Nothing in it is a credential:
these are identifiers, and the test is that if they *were* credentials a
private repository would be insufficient by every source above, which is
the whole reason for the distinction. It binds on what comes next.
Instances bring database passwords and the FDB backup encryption key,
and those must not enter `installations` even privately. They belong
in Secret Manager, which is already
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)'s
answer, reached either by an external-secrets operator or by Crossplane
writing connection details straight into a cluster Secret. Choosing
between those two is worth doing before the first instance rather than
after.

#### Two alternatives rejected, so they are not re-derived

**Reading the identifiers from Secret Manager.** An External Secrets
Operator on the management cluster, a `ClusterSecretStore` pointed at
the project holding them, an `ExternalSecret` materialising a Secret in
`crossplane-system`, and a `function-go-templating` step reading that
Secret and patching `Project.forProvider.billingAccount` and
`Folder.forProvider.parent`. Argo would apply the `ExternalSecret` and
the XR, and nothing else.

One property of it is genuinely good and worth remembering: the read
happens at reconcile time inside Crossplane rather than at manifest
generation, so Argo never sees the values and the plaintext Redis cache
does not come into it.

It fails on two counts. **The boot plane cannot do it** — Secret Manager
lives in the management project, and the boot plane runs before that
project exists, so the one scenario the adopt values exist for is the
one where the mechanism is absent. Moving the secret to the seed project
fixes that and makes the durable path depend on a project ADR-0022
describes as designed so it could be deleted. And **the root of a
discovery chain cannot be hidden**: the `ClusterSecretStore` needs a
`projectID`, in the public repository, to fetch the project ids being
hidden — one identifier published to conceal four. The justfile dodges
this by finding the seed project by label rather than by id, and no
operator has a label search to dodge with.

Against a private repository it gains one repository and loses review,
history and diff, for values whose whole risk is a phone call.

**Selecting by label instead of carrying an id.** Crossplane's
`matchLabels` selectors resolve against managed resources **already
present in the Kubernetes cluster** — the controller reads the
referenced resource's external-name and copies it in. There is no
Terraform-style data source, so nothing expresses "the GCP project
labelled `tier=seed`".

The composition already uses the by-name form of this for everything it
composes: `folderIdRef` on the Project, `projectRef` on each
ProjectService, `networkRef` on the Subnetwork. It cannot reach outward.
For `management.projectId` to resolve by label there would have to be a
`Project` resource in the cluster carrying that label, and that resource
needs the project id as its external-name — so the id moves to a
neighbouring manifest rather than disappearing.

The asymmetry underneath is worth stating plainly, because it is why the
identifiers have to be written down at all: a GCP-side label query is
real and `_gcp-seed-project` already uses one, but it is an imperative
capability. The justfile can discover, Crossplane cannot, and Argo
cannot run `gcloud`. The declarative layer carries only what it is told.

One thing survives the rejection. **Label the folder, the management
project and every instance project with the installation code.** Not for
Crossplane, which will not use it, but for the recovery path: today
`plane-ctx` matches a project-id prefix and says why — "the
suffix is random, so it is not derivable, and nothing labels the
project". A label turns finding an entire installation from a bare login
into one query, which is worth having when the management project is
what has been lost.

### 5. Move the XR, and verify the adoption

Apply the XRD, Composition and manifest to the management cluster, and
check every managed resource individually rather than trusting the
composite's `Ready`.

The commit criterion is `kubectl --context qw01-mgmt get managed`
listing the existing resources as Ready and Synced, with nothing created
or changed on the GCP side — no second folder, no second project, no
replaced node pool. Adoption is asserted resource by resource here
because the two known traps are both silent: a duplicate folder looks
like success, and a project stuck observing looks like a slow create.

A lien lands on the management project, so that the plane taking over
meets a refusal in GCP rather than a convention in a manifest if a
later edit removes a `managementPolicies` entry. Not on the folder: a
lien is a project mechanism and a folder cannot carry one, which
corrects what
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
assumed. A folder is protected instead by nobody holding
`resourcemanager.folders.delete`.

Labels land here as well, carrying the installation code on the folder,
the management project and later every instance project. Crossplane will
not use them; `gcloud` will, and it is what lets a bare login find a
whole installation without knowing a random suffix. It also retires the
project-id prefix match in `plane-ctx`.

**Two planes reconcile the same resources until step 6.** That window
is safe by construction rather than by luck: both run the same
composition against the same external names, so they agree about
everything, and neither creates what the other already adopted. It is
not a state to linger in, but it is not a race either.

**Never delete the composite on the boot plane.** Deleting a composite
deletes what it composes, and the cluster and its node pool carry
`managementPolicies: ["*"]` — so a `kubectl delete
xqueenswoodinstallation` would destroy the management cluster the
installation was built to hand over. The folder and project would
survive, because theirs withhold `Delete`, which is exactly the
asymmetry [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
describes: foundations are protected, the plane running on them is
disposable.

The boot plane is discarded by deleting the kind cluster, which deletes
nothing in GCP because no controller is left to act on the objects. The
distinction is the whole of step 6, and getting it backwards costs the
cluster.

### 6. Discard the boot plane

`just boot-cluster-down`, and `just seed-impersonate-revoke`. After this the
management cluster reconciles its own project and folder, which the
liens are what make safe.

## After the pivot

**The durable tier.** This section said the backup bucket belongs in
the **management** project, because an instance's project was
disposable and a bucket inside one died with the thing it protects.
That is no longer the model: ADR-0022 and ADR-0024 now say an
instance's project is durable and keeps its own data, and `down` was
demonstrated to leave it standing. So the bucket's home is open again,
and the argument for moving it out is blast radius — a backup in the
same project as its primary is reachable by the same compromised
identity — rather than the project going away.

What travels with it either way: the per-prefix lifecycle rules from
`gcp-backup-lifecycle-set` as `lifecycleRule`, and somewhere for the
FDB encryption key. Not database passwords — IAM database
authentication makes the workload's service account the database user,
so Cloud SQL creates no password to keep.

How the data reaches that bucket is a redesign rather than a port of
what exists, and the reason is a permission we should not expect to
hold.

#### The key ban, and the proxy that answers it

FDB's backup agent speaks S3 rather than GCS, so it authenticates to
the interop endpoint with an HMAC key, which GCP counts as a
service-account key. The organisation bans those by default, so
`_gcp-allow-sa-keys` exempts the instance project from
`iam.disableServiceAccountKeyCreation` — and setting that exemption
needs `orgpolicy.policyAdmin`.

Step 2 established that GCP grants that role at the organisation and
nowhere else: a project binding and a folder binding are both refused
with `Role roles/orgpolicy.policyAdmin is not supported for this
resource`, a 400 declining the scope rather than a permission the
caller was missing. The old recipe worked by granting it to the
operator on our own organisation and then applying a *project*-scoped
override, which is the shape worth remembering — the grant is
organisation-wide, the policy it sets is not, and it is the grant that
is out of reach.

Under [ADR-0023](../adr/0023-installation-naming-and-access.md)'s
assumption, of a folder handed to us inside an organisation we do not
own, that grant is not ours to make. Asking for it means asking to be
able to weaken any constraint anywhere in that organisation, for the
sake of one HMAC key, and it should be treated as unavailable rather
than as a request that might succeed. `sa-qw01-boot` does hold the role
today, from `seed-grant-org-roles`, so the exemption is reachable for
this installation — from the disposable plane only, which makes it a
property of our own organisation rather than of the design.

**So the transport changes, rather than the permission.** Run an
S3-to-GCS proxy in the instance cluster, and point the backup agents at
it instead of at `storage.googleapis.com`. The agents keep speaking
the only protocol they know; the proxy speaks GCS's own API and
authenticates through Workload Identity, so no exportable Google
credential exists anywhere in the path and the exemption stops being
needed at all.

What FDB presents to the proxy is a locally generated pair that grants
nothing in GCP. That is the point rather than a detail: a credential
the organisation's key policy has no opinion about, whose blast radius
is one proxy inside one cluster, and which can be rotated without
asking anyone.

**The proxy is `s3proxy`, on its `google-cloud-storage-sdk` backend.**
Its older jclouds backend takes a service account's RSA private key,
which would swap one banned key for another and gain nothing. The SDK
backend uses Google's own client and does reach Application Default
Credentials, so Workload Identity carries it — but it is reached by
supplying a credential the SDK cannot parse, whose `IOException` lands
on `getApplicationDefault()`. An empty credential means `NoCredentials`,
which is for an emulator.

That fallback is deliberate rather than accidental: the call is there on
purpose. What is not transparent is the condition guarding it, which is
a parse failure rather than an option saying so. So the value goes in
with the image tag pinned, and an upstream change making it an explicit
option is worth proposing — after backups are tested, since testing them
is what would find anything else wrong with this.

**It also retires a compromise the chart currently documents.** FDB's
blobstore client has no usable trust store — it statically links
OpenSSL with an `OPENSSLDIR` absent from the image, so every public
certificate verifies as self-signed, and only `--tls-ca-file` loads a
CA, which the operator never passes and offers no way to add. So the
backup runs plaintext to `storage.googleapis.com` today, over the
public internet, with encrypted files and SigV4's promise that the
secret is never transmitted standing in for the transport. With a
proxy in the cluster the plaintext hop is pod to pod, and the leg that
crosses a network is the proxy's own HTTPS to GCS, made by a client
whose trust store works. The comment in
`infra/helm/queenswood/templates/fdb-backup.yaml` explaining why this
is tolerable stops needing to be true.

Continuity is what makes this better than moving the data. The backup
stays continuous and lands natively in GCS, so the object layout,
`fdbbackup expire`, the bucket's lifecycle rules and the restore path
are all exactly what
[fdb-recovery](../recipes/infra/fdb-recovery.md) already describes. The recovery point objective does not move, and restore
gains no step.

The encryption key is unaffected: FDB encrypts what it writes, the key
is generated once and must outlive every cluster, and Secret Manager in
the management project is still where it lives.

**Prove the restore before trusting it.** A translation layer's
failures live in the parts of the S3 API that GCS implements
differently — multipart semantics and list behaviour especially — and
they surface on read, not on write. A backup that writes cleanly and
will not restore is the failure this design has to rule out early,
against a real cluster, before the proxy is anywhere near a production
instance.

The proxy is also in the data path, so its failure stops backups
without stopping anything else. What that wants is an alarm on backup
staleness rather than on the pod: a healthy proxy that has written
nothing for an hour is the condition worth waking someone for. Being
stateless, it takes replicas at no cost beyond the pods.

#### What was rejected, and why

**MinIO as the store.** Trades GCS's durability and lifecycle rules for
a storage system to operate and back up in its own right. Worth it only
if MinIO is wanted for something else.

**`fdbdr` to a second cluster.** Continuous replication is disaster
recovery, not point-in-time restore, and
[fdb-recovery](../recipes/infra/fdb-recovery.md) is built on restoring
to a recorded version. A second cluster running continuously
also contradicts the disposable tier's whole reason for existing. It
answers a different question, and could sit alongside a backup rather
than replace one.

**A scoped policy exception.** A dedicated backups project holding only
the bucket, one HMAC key, rotation automated, and the exception
written down. It does not reduce the ask: the override is already
project-scoped — `_gcp-allow-sa-keys` applies exactly that — and what
is out of reach is the organisation-level grant needed to set any
override at all. Worth knowing that
`constraints/storage.restrictAuthTypes` exists as a separate control on
HMAC access to GCS, which an organisation may enforce independently and
which the proxy also sidesteps.

**Disk snapshots** are the one not rejected. FDB supports binary
backups through volume snapshots, which involve no S3 protocol at all,
and CSI `VolumeSnapshot` of the storage servers' disks is a plausible
second line. Against it: the operator's support is thinner than for
blobstore backups, recovery points are coarser than a continuous
backup's, and quiescing has to be coordinated with FDB rather than
taken underneath it. A second line, once the first one restores.

Two decisions this leaves. **Where the object path carries the
instance**: one bucket per installation means a prefix per instance,
`<instance>/fdb/continuous/<generation>`, and the lifecycle rules
already discriminate by prefix. A bucket per instance is no longer
ruled out by the project being disposable, so this turns on whether the
backup should sit outside the project it protects at all. And **where
the proxy runs**: in the instance cluster it is rebuilt with everything
else there, and reaches the bucket across a project boundary if the
bucket is outside — which is the cross-project binding the design would
then need.

**Real secrets, at last.** An instance brings the first actual
credentials — database passwords and the FDB backup encryption key —
and neither goes into `installations`, private or not, because every
source in the survey above rejects a repository as protection for a
credential. They live in Secret Manager in the
management project, which is already
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)'s
answer, and reach the workloads one of two ways:

- **An external-secrets operator** on the instance cluster,
  authenticated by Workload Identity, with a `ClusterSecretStore`
  pointed at the management project and an `ExternalSecret` per
  credential. This is the sketch rejected above for identifiers, and it
  is correct here: the values are credentials, the read happens on the
  destination cluster, and Argo never holds them — which is precisely
  what Argo's own guidance asks for.
- **Crossplane writing connection details directly** into a cluster
  Secret, which is what it already does for a CloudSQL instance's
  generated credentials, and which needs no extra operator for the
  secrets Crossplane itself creates.

The split is likely both: Crossplane for what it generates, the operator
for what it does not — the FDB encryption key in particular, which is
generated once by a recipe and must outlive every cluster. Deciding it
before the first instance is what stops a credential landing in a
manifest by default.

**An instance.** An `XQueenswoodInstance` per environment, its own
composite carrying `state: up | draining | down`. Project, network,
cluster, CloudSQL, and — where it declares a `domain` — a static
address, a DNS zone and a certificate. This is where `gcp-up` and
`gcp-down` stop being scripts and become a field, and it is the step
that proves the installation. The workloads on that cluster are
`Release` resources of `provider-helm`, which is what the existing
`queenswood-platform` composites already do.

This paragraph originally said `spec.instances` joins the plane's XRD.
[ADR-0024](../adr/0024-instances-are-their-own-composites.md) decides
otherwise, and says why: a composite is a unit of replacement, so
declaring the instances inside the plane's own composite puts every
environment's project, cluster and database in the blast radius of a
plane rebuild.

**Where it got to.** It is up. Every pod runs: FoundationDB fully
replicated, Kafka, Jaeger, Keycloak, the Cloud SQL Auth Proxy, and the
five services — bank-api serving, its Kafka producers created, its
interceptor chain built. The migrator and bootstrap Jobs completed, and
both realms imported. `state` governs the node pool and, since CloudSQL
arrived, that database's `activationPolicy` too.

Three things the bring-up settled rather than assumed. **A bank runs
with no database password anywhere**: the workload authenticates to
Cloud SQL as an IAM principal with a token from the metadata server,
and created its own schema over that connection. **`up` and a node pool
rebuilt underneath it are both survivable** — FoundationDB reattached
its volumes and returned fully replicated, which is the property that
lets a node hold nothing. And **two `e2-standard-4` beat three
`e2-standard-2`**, measured: 56% of allocatable where the smaller shape
sat at 90% and left FoundationDB a storage pod it could not
schedule.

**All three firsts have now happened.** The proxy reached Cloud SQL as
an IAM principal — which is where a Workload Identity binding spelling
the wrong pair surfaced, twice, and where `--private-ip` earns its
place, since the proxy looks for a public address by default and this
instance has none. The realm import created both realms from the
chart's committed definitions, with no console redirect URI baked in
because there was no hostname to name. And the bootstrap Job completed,
which is what every service had been waiting on.

The issuer is the in-cluster Service rather than a public hostname,
because nothing outside the cluster can reach this instance yet. It is
a value to revisit when the gateway arrives, not a permanent one —
tokens minted before it changes stop verifying after, and the two
`expectedIssuer` values move with it on their own.

After that: the apex address, DNS record and certificate, which need
the zone question answered first. Whether an instance composes
`Release` resources or an `Application` per workload,
[ADR-0024](../adr/0024-instances-are-their-own-composites.md) left
open, and the Argo path has now answered it in practice.

**Known limitations.** There is no external-secrets operator on an instance
cluster, so a workload needing a real credential has nowhere to read one —
which the FDB backup encryption key will need. (Built since, for Google
sign-in: see *Picking this up cold*.) FDB backup stays off until the proxy
above is built and a restore proven through it: an instance with no data loses
nothing by waiting, and the HMAC path it would otherwise run on is the one
being retired. `image.tag` is `latest`, which is the wrong tag for an
environment: a mutable tag means two nodes can run different code and a
rollback has nothing to return to, and the build publishes no versioned tag to
use instead. The gateway is disabled, so nothing is reachable from outside the
cluster.

**Known problems.** Something reports healthy while the thing that matters is
stuck. The first three cost a day; the rest cost the first bring-up, and every
one of them looked like a different problem than it was:

- A **`ForceNew` change is refused, not performed.** Changing an IAM
  binding's role rewrote `spec` and left `atProvider` on the old value;
  `Ready` stayed `Available` and only `LastAsyncOperation` said
  `AsyncUpdateFailure`. The managed resource has to be deleted.
- **`stringData` is write-only.** The API server folds it into `data`
  and drops it, so provider-kubernetes cannot see a value it failed to
  write. A registration Secret sat for half an hour missing the key
  that mattered, reporting `Ready`. Use `data` with a `ToBase64`
  convert, where a written value is read back.
- **A bad image tag wedges a cluster.** Deployments applied with a tag
  that does not exist leave ReplicaSets that can never become `Ready`,
  a rollout that will never retire them, and their CPU requests held
  forever — which starved FoundationDB of the capacity it needed to
  schedule, so nothing could progress in either direction. The same
  shape arrives without a bad tag: a chart version bump changed the pod
  template labels, so every Deployment rolled to a new ReplicaSet that
  could not schedule while the old one still held its requests.
- **A name two sides must spell, invented on one side.** Three times.
  The proxy's Kubernetes service account was derived from the Helm
  release name, which is the Argo Application's name, which the
  manifest binding Workload Identity to it cannot know; Keycloak asked
  for a database called `keycloak` where the composite had made
  `sql-<code>-<env>-<label>-keycloak`; and the issuer lived in one
  values file while the two strings verifying it lived in another. Each
  reported healthy on both sides — binding `Synced` and `Ready`,
  annotation present, probe green — until a real request crossed the
  gap. A name another repository has to spell is a value, not a
  derivation.
- **The Cloud SQL Auth Proxy treats absent credentials as terminal.**
  On a rebuilt node pool the GKE metadata server takes minutes to be
  ready, and the proxy exits rather than waiting, so it crashloops
  until a backoff happens to land after the metadata server is up —
  four restarts, and anything depending on it that also exits on first
  failure burns its own on top. It converges alone and reads exactly
  like a broken Workload Identity binding while it does. It also needs
  `--private-ip`, since it looks for a public address the instance
  composite never builds.
- **A NEG readiness gate with no load balancer never satisfies.** The
  operator-published Keycloak Service carried `cloud.google.com/neg`,
  so GKE injected a readiness gate on its pod; with the gateway
  disabled there was no backend to program, so the pod sat
  `ContainersReady=True` and `Ready=False` forever, the Service had no
  serving endpoint, and a perfectly healthy Keycloak was unreachable.
  Remove the annotation and recreate the pod — gates are set at
  creation. The Gateway controller re-adds it when there is something
  to attach to.
- **Nothing re-evaluates after the environment is fixed underneath
  it.** Three separate mechanisms, one afternoon: an Argo Application
  whose retries are exhausted waits for a revision rather than a fixed
  cluster; a Job that reached its `backoffLimit` never runs again; and
  the bootstrap gate that selects the realm-import Job by label
  snapshots the matching set at start, so a Job deleted afterwards
  wedges it permanently. Each needs the waiter recreated, and
  `selfHeal: false` means deleting a resource does not bring it back —
  Argo reports the drift and leaves it.

Sizing, measured on a running system rather than guessed. Three
`e2-standard-2` give 5790m allocatable and the deployment asked for
5226m of it — 90%, and FoundationDB was a storage pod short. Two
`e2-standard-4` give 7840m for 4413m, which is 56%. Fewer larger nodes
because GKE's own daemonsets cost about 278m per node whatever its
size, so a small node pays that tax on a small base and three of them
pay it three times. `redundancy_mode: single` is what makes two nodes
enough.

**Database authorisation is owned now, and this is how.** The design's claim
that no password exists is true, and it quietly assumes that authenticating
to Cloud SQL brings privileges inside Postgres with it. It does not. An IAM
user is created with no privileges on any database, and since PostgreSQL 15
only a database's owner may create in its `public` schema — so Keycloak
connected, authenticated, and could not create a table. The `Database`
resource has no owner field to fix it with, and `User` has no
`databaseRoles`, so the composite cannot express the grant at all.

What unblocked it was `gcloud sql users assign-roles ...
--database-roles=cloudsqlsuperuser`, which is an Admin API call needing
no database session and no password — so the no-password property
survives. What it also showed is that **no capability in
[ADR-0023](../adr/0023-installation-naming-and-access.md) can perform
it**: `grp-gcp-qw01-cluster-admin` holds `container.admin`, the viewer
group holds reads, and neither carries `cloudsql.admin`. It worked only
by impersonating the platform identity, which
`grp-gcp-qw01-platform-admin` may do — itself worth reconciling against
the rule that says never to grant a person `serviceAccountTokenCreator`
on that identity.

This is once per instance, so every future environment would have met
it. The composite now closes it: a custom role holding
`cloudsql.users.{update,get,list}` and `cloudsql.instances.{get,update}`
rather than `cloudsql.admin`, an identity holding that role, the
Workload Identity binding, and a Job in the chart that calls the Admin
API. No password, no person, and nobody needs `platform-admin` to build
an environment.

The identity is its own rather than the workload's, because editing an
instance's users is not something a workload should be able to do — and
because granting narrowly later means connecting as a superuser that is
not the workload, so that step becomes a sidecar and some SQL rather
than another identity. `cloudsqlsuperuser` is broader than one database
needs; it is what the Admin API can grant without a database session,
which is what keeps this passwordless, and it suffices while one
workload uses the instance.

Four things it took to get right, each worth not repeating. A custom
role's id is its **external name** — `forProvider` has no `roleId`, and
the role twenty lines above already showed it. `cloudsql.users.update`
alone is **refused**: `assign-roles` writes through the instance, so it
needs `instances.update` too, and the tell is that `users.list` through
the same role succeeds while the write does not — a missing permission
and an unbound identity are the same 403 otherwise. A **required chart
value with nothing setting it breaks the whole release**, not one Job,
so a chart change and the installation change it needs are one unit and
not two merges. And the Job's `backoffLimit` is a budget: it survived
seven restarts across three fixes, but a Job that exhausts one needs a
person to delete it and sync, which is the same shape as everything
else here that does not re-evaluate.

**And the console's origin is not in the realm.** The realms imported
from the chart's committed definitions with `consoleRedirectUri` unset,
because no public hostname existed to name and a realm is created once
and never overwritten. When the gateway and the domain arrive, the
`queenswood-console` client's redirect URI has to be added through the
Admin API. That was the right trade against baking in a guess, and it
is a debt rather than a decision deferred.

Four things wait rather than block:

- **A lien on the instance project.** Deferred deliberately while the
  shape is still changing — a lien makes every teardown two acts, which
  is the point of one and the wrong trade today. Nothing composes one:
  the installed provider offers no `Lien` kind, so it is a recipe when
  it comes.
- **`XPlatform`, `XQueenswoodApex` and `XQueenswoodCertificate`** are
  loaded on the plane with no composites of their kinds. Deleting them
  is a file deletion, and what is useful in them folds into this kind.
- **`ProjectMetadataItem` for `enable-oslogin`**, and
  `ProjectIAMAuditConfig` for data-access logs. Both are composed
  resources rather than scripts, both close a real CIS finding, and the
  second carries a standing log bill worth sizing first. See
  [security-scanning](../recipes/infra/security-scanning.md).
- **Flow logs on both subnets**, which is `logConfig` on `Subnetwork`
  and the same cost question one level up.

The instance's own composition is the lever for all four: it is what
every future environment inherits, so a fix there is a fix for projects
that do not exist yet.

**Draining.** The `Usage`-gated export Jobs, which
[ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
says to treat as unproven until a cycle runs unattended. Its stated
precondition is that the Keycloak restore is unattended first — #349
needed four manual steps — so that gap closes before this is built.

## Recipe by recipe

**Already replaced.** `gcp-org-create`'s org-role half by
`seed-grant-org-roles`; `gcp-iam-bootstrap`'s identity by the
composition's `platform-identity`; `gcp-crossplane-login` by
`seed-impersonate` for the boot path and by Workload Identity for the
durable one.

**Becomes a managed resource.** `gcp-project-create`;
`gcp-dns-zone-create`; `gcp-backup-bucket-create`;
`gcp-backup-lifecycle-set`; `gcp-keycloak-restore-sa-create`;
`gcp-keycloak-backup-sa-create`; the `skipDefaultNetworkCreation` half
of `gcp-org-create`. `gcp-dns-zone-create` carries a half that does
not: the domain-ownership verification it falls back to has no API, so
it stays directory work and the composition assumes it has already
happened.

**Dies with the key.** `gcp-backup-hmac-create` and
`_gcp-allow-sa-keys`, once the backup agents reach GCS through the
proxy the durable tier proposes — there is then no HMAC key to mint and
no exemption to apply. Both stay until that is built and a restore has
been proven through it, because they are what the current backup runs
on.

**Becomes a field.** `gcp-up` and `gcp-down` become `state`.

**Dies with the model.** `gcp-project-delete`, `gcp-gke-delete`,
`gcp-cloudsql-delete`, `gcp-ip-delete`, `gcp-cert-delete`,
`gcp-vpc-delete`, `gcp-dns-zone-delete`, `gcp-dns-records-delete` —
deletion is reconciliation, and a project is retired by lifting its lien
deliberately. `gcp-cloudsql-wire` dies too: the composition wires the
connection rather than a recipe reading it out of status.

**Moves to `gcp.just` as it stands.** The operational reads and
in-cluster actions, which have no declarative equivalent because they
act on a running system rather than on its shape:
`gcp-fdb-restore-points`, `gcp-fdb-export`,
`gcp-keycloak-restore-points`, `gcp-keycloak-export`,
`gcp-k8s-redeploy-svc`, `gcp-dns-check`, `gcp-health-check`. Each loses
its `pass` lookups in favour of Secret Manager and the manifest, and
each gains the installation code in place of `QUEENSWOOD_ENV`.

**Dies with `pass`.** `gcp-keycloak-idp` and
`gcp-keycloak-vault-secret`, which read a client id and a secret out of
`pass` and pushed both at a cluster. The realm-import Job reconciles the
id from the installation's values, and `external-secrets` materialises
the secret out of Secret Manager, so what is left of either is
`gcp-secret-version` putting one value into one entry — general, and
named for what it acts on rather than for Keycloak.

**Never migrates.** The directory work in
[organisation-foundation](../recipes/infra/organisation-foundation.md) — the
organisation, the
billing account, domain verification, the access groups — because Cloud
Identity has no API reachable before a project exists. `gcp-oauth-client`
stays a console step for the same reason, and only its capture changes.

## Open questions

- How a composition change and the manifest change it requires land
  together across two repositories. Keeping new XRD fields optional
  with defaults makes it rare rather than solved.
- Whether a redacted worked example stays in
  [cloud-naming](../recipes/practices/cloud-naming.md), or the whole example
  follows the manifests into `installations`. It is the one piece of
  this documentation that reads better with real values in it.
- Whether `pass` is retired at the pivot or kept for the boot path.
  Nothing in the durable design reads it, but `seed-impersonate` runs before
  any of it exists.
- Whether the management cluster stays publicly reachable, which
  [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  leaves open and which interacts with putting a private network in
  front of the instances.
- Whether an S3-to-GCS proxy restores as well as it backs up. The
  design turns on it, and the answer comes from a real restore rather
  than from the proxy's documentation. Its authentication is settled —
  Application Default Credentials, reached the undocumented way
  described above — and pinning the image is what keeps it settled until
  an upstream option replaces the parse failure it depends on.

## References

- [ADR-0022](../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — the folder as an installation, declared `state`, ordered draining,
  and why foundations are liened.
- [ADR-0023](../adr/0023-installation-naming-and-access.md) — the
  installation code, the naming scheme and the four capabilities.
- [ADR-0016](../adr/0016-crossplane-over-terraform.md) — why
  infrastructure is declared rather than scripted.
- [plane-install](../recipes/infra/plane-install.md) —
  what a deployment builds, and the two identities that build it.
- [cloud-naming](../recipes/practices/cloud-naming.md) — the inventory every new
  resource takes its name from.
- [fdb-recovery](../recipes/infra/fdb-recovery.md) — what a restore
  actually does, which is what the durable tier exists for.
- [GitOps Principles v1.0.0](https://opengitops.dev/) — the four
  principles, published by the GitOps Working Group.
- [Argo CD best practices](https://argo-cd.readthedocs.io/en/stable/user-guide/best_practices/)
  — why a config repository is kept separate from a source repository.
- [Argo CD secret management](https://argo-cd.readthedocs.io/en/stable/operator-manual/secret-management/)
  — destination-cluster secret management, and the plaintext Redis
  cache that rules out injecting secrets at manifest generation.
- [Flux secrets management](https://fluxcd.io/flux/security/secrets-management/)
  — plain-text secrets in desired state, and not co-locating ciphertext
  with its key.
