# Cloud DNS

<!-- tessl-plugin: deployment -->

## Problem

[organisation-foundation](organisation-foundation.md) leaves you with an
organisation, and
it got there through the domain: Cloud Identity verifies one before it
gives you anything. So by the time there is somewhere to build, the
domain already carries a `google-site-verification` record placed
through the registrar, and stands as the organisation's primary domain
in the Admin console.

That is all the DNS the account setup needed, and none of what an
application needs. The domain is an identity artefact — it proved who
you are — and nothing is served from it. There is no zone the plane can
write to, no way for its automation to create one, and the registrar is
still the authority for the name. The verification already present is
Cloud Identity's rather than Search Console's, which is why it counts
for nothing here, and why a domain that is demonstrably verified
appears in Search Console not at all.

This recipe is the crossing: turning the domain that bought the
organisation into the one the bank is reached at. The zone and the
records in it are composed, but everything around them is a person's
work — proving ownership to a service account, unsigning a domain that
is signed, and moving the delegation at whoever sold you the name. None
of it has an API the plane can reach.

Where the zone lives and what composes the records in it belongs to the
composition, not here.

## Solution

### Prerequisites

- An organisation. The domain is registered and verified for Cloud
  Identity, and serves whatever the registrar has always served — a
  parked page, or nothing. Step 2 additionally needs the installation's
  plane, since the identity it grants to is composed by it.
- An account at the registrar that can edit DNS and change nameservers,
  for steps 4 and 7.
- The operator account for the installation — the one the organisation
  is administered with, not a personal one. Steps 1 and 2 are done as
  it, in a browser. It is a Google account rather than a GCP role, and
  no group confers it.
- The capability each step names. Ours is a Google group; yours may differ.

### The order, and why it is this order

Three things gate each other, and getting them the wrong way round
costs hours rather than minutes.

Ownership comes first, because a public zone cannot be created without
it. Everything here comes before the zone, and the zone before the
delegation — a delegation to a zone that does not answer is an outage,
and the token proving ownership has to answer from the new zone before
the move, or the move takes ownership away with it.

Step 1 is done once for a domain, and step 2 once per installation. The
apex zone the tokens live in is
[apex-install](apex-install.md)'s; each installation composes zones for
its own names below it, and it is those creates that step 2's grant
unblocks.

Unsigning is the long pole and the one that hurts. Read step 4 before
choosing where to put it: it wants to be early, and there is one case
where it should be last.

### 1. Verify the domain

Cloud DNS refuses to create a public zone whose name the calling
identity has not verified, and reports
`verifyManagedZoneDnsNameOwnership` when it does. The check is against
Google Site Verification, not against anything in Cloud DNS — Cloud DNS
only consults it.

Signed in as the operator account, at `search.google.com/search-console`:

1. Add a property, of type **Domain** rather than URL prefix. A Domain
   property covers every subdomain, so no environment ever needs
   verifying separately.
2. Copy the `google-site-verification=` token it issues.
3. At the registrar, add it as a TXT record at the apex. A registrar
   with a preset menu usually has an entry for this — Squarespace calls
   it Google Workspace Verification — and the preset is only a
   convenience for pasting the value.
4. Back in Search Console, verify.

Then check **Settings, then Ownership verification** and confirm the
method it recorded. Google may auto-verify by recognising who provides
the domain's DNS and record the method as `Domain name provider`, in
which case your token is sitting in DNS unused — and that verification
rests on a provider relationship step 7 ends. Add the DNS TXT method
explicitly so ownership rests on a record you control.

Three things about verification are easy to get wrong.

**It belongs to an identity, not to a domain.** A token verifies
whoever placed it, so the record the account setup left at the apex is
no evidence your account owns anything — and the empty property list
is no evidence of the reverse, for the reason above. Neither tells you
anything. The owner list is the only thing that does.

**Delegated ownership lapses with the token it hangs off.** An owner may
add owners, so any verified account unblocks the rest — but an
installation whose identities all delegate from somebody's personal
account loses the domain when that account is tidied up. Have the
operator account verify in its own right. A domain carries several
verification records without conflict, so this is an addition rather
than a migration, and an unattributed token already present stays:
nothing reports which record holds the organisation's own domain.

**Neither neighbouring permission system is this one.** The Admin
console manages Cloud Identity's domains. The registrar has an account
model of its own, granting people the right to edit DNS and move the
registration. Neither grants ownership in Google's sense.

### 2. Add the automation identity as an owner

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

The zone is created by a service account rather than by you, and a
service account is never a verified owner by default. Print its address:

```bash
just plane-identity
```

An email ending `.iam.gserviceaccount.com`, which is what goes in the
box below.

Signed in as yourself — the account step 1 verified — go to **Settings,
then Users and permissions, then Manage property owners**, and paste
that address into **Add an owner**. Nothing is impersonated: you are
granting the service account ownership, not acting as it.

Choose **Owner**. Full and Restricted grant report access and confer no
ownership, and a zone create refuses them exactly as it refuses a
stranger.

Where that is not wanted, create the zone by hand as a verified human
and let the composite adopt it.

### 3. Inventory the registrar

Find out what the domain is actually serving. The records that matter
are rarely the ones it is visibly for.

```bash
just dns-records <domain>
```

Every apex type, and the underscore names that carry policy. A sweep of
the apex alone misses the records that matter most, because the ones
carrying policy sit under names nothing advertises — `_dmarc` is the
other half of an SPF lockdown and is invisible from there.

```bash
just dns-carried <domain>
```

Of that, what a new zone has to keep: the verification tokens, SPF and
DMARC. A placeholder site's A records and `www` CNAME describe a page
nobody depends on, and `_domainconnect` names the old provider's
one-click endpoint, which means nothing once the domain delegates
elsewhere.

Read the first against the second. That narrowing is right for a domain
serving nothing but policy, and the sweep is where you find out this
one is different — an MX the organisation receives on, a CAA, an A
record something points at. Anything like that goes into the zone by
hand, because nothing below will notice it missing.

### 4. Unsign, if the domain is signed

```
dig +short DS <domain>
```

An answer means DNSSEC is on, and the domain cannot move until it is
off. The DS record lives at the parent registry and names the keys the
current nameservers sign with. Changing the delegation does not touch
it, so the new authority answers unsigned against a parent still
asserting signatures — and validating resolvers treat that as forgery
rather than as an absence. Not a stale answer for a while: SERVFAIL
everywhere at once, the verification TXT included, and with it the
ownership the zone rests on.

The mismatch fails closed deliberately, since the alternative would let
an attacker strip signatures to downgrade a protected domain. It is
also the one failure the delegation diff cannot protect against: that
makes the two authorities agree on content, and the parent's assertion
is not about content.

Keeping it signed across the move is not available. It would need the
new provider's keys pre-published in the old zone, and Cloud DNS
generates and owns its keys rather than accepting an existing one.

**Expect the outage to begin here, not at the delegation.** A registrar
may strip the zone's keys at once and submit the DS withdrawal to the
registry afterwards, leaving the domain in exactly the mismatched state
above for as long as that takes. So:

- **For a domain not yet serving anything, do this first**, before
  step 1 even, and let the wait overlap everything else.
- **For a domain serving traffic, do it last**, immediately before
  step 7, and accept a serial wait in exchange for the tightest
  possible window.

Watch the parent, not the zone:

```
dig +short DS <domain> @<a registry nameserver, e.g. a0.nic.io>
```

Empty is the signal. Then wait out the DS record's own TTL — the
registry's, not the zone's — because a resolver still holding a cached
DS goes on expecting signatures after the registry has dropped it.
Recovery is uneven while that happens: public resolvers are anycast,
each node caches independently, and one probe hits one node, so a
single clean answer proves nothing. Take several, spaced, across more
than one resolver.

Move the delegation once the zone exists — that is
[gcp-dns-delegation](gcp-dns-delegation.md), and it cannot start
before [plane-install](plane-install.md) has
composed one.

## Rules

**MUST:**

- Verify the domain before a public zone is created, as the operator
  account in its own right.
- Add the property as a Domain property, not a URL prefix.
- Add the automation identity — `just plane-identity` prints its
  address — as an **Owner** of the property, once per installation.
  Full and Restricted confer no ownership, and a Domain property covers
  every name below it, so one grant covers every zone that installation
  composes.
- Add the DNS TXT verification method explicitly where the domain was
  auto-verified through its provider.
- Inventory with `just dns-records`, every record type and the
  underscore-prefixed names, before moving a domain.
- Carry over what `just dns-carried` names — the verification tokens,
  SPF and DMARC — and read the full sweep against it, since that
  narrowing is right only for a domain serving nothing else.
- Check for a DS record before delegating, and where one exists unsign
  at the registrar and wait out the DS TTL first, watching the parent
  registry rather than the zone.
- Take several spaced probes across more than one resolver before
  calling DNSSEC recovery complete.

**MUST NOT:**

- Read an existing `google-site-verification` record as evidence your
  account owns the domain, or an absent Search Console property as
  evidence it is unverified.
- Tidy away an unattributed verification token.
- Regenerate a token to move it. The same string is copied, and answers
  from both authorities across the switch.
- Leave the installation's identities delegated from a personal
  account.
- Delete and recreate a zone to change it. The nameservers change with
  it, the registrar does not follow, and each fresh zone draws from a
  finite per-domain pool.

**SHOULD:**

- Unsign first for a domain not yet serving anything, so the wait
  overlaps everything else; unsign last for one serving traffic, to
  keep the window tight.
- Move the apex once rather than delegating a subdomain per
  environment, so the registrar is a one-time act.

## References

- [organisation-foundation](organisation-foundation.md) — domain verification
  at signup, and
  why the directory work has no API.
- [cloud-naming](../practices/cloud-naming.md) — the `dz-` prefix and the
  environment letter.
- [apex-install](apex-install.md) — the zone the tokens go in, which no
  installation composes.
- [crossplane-design](crossplane-design.md) — what composes the zones
  below it, and their records.
- [plane-install](plane-install.md) — the manifest
  the domain is named in.
- [gcp-dns-delegation](gcp-dns-delegation.md) — moving the
  registrar, once the zone answers.
