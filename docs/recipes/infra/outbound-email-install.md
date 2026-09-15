# Outbound email for an instance

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Steps 1 to 3 were followed for one instance, through
Postmark, as far as the provider verifying the domain. No email has been
sent. The steps were derived from
[outbound-email.md](../../tdd/outbound-email.md) and
[instance-deploy](instance-deploy.md). The instance composition and a
zone carrying the three records were checked with `crossplane render`,
and both charts with `helm template`.

## Problem

You want an instance to email an invitee the link that accepts their
invitation, from an address on the instance's own domain, and have it
arrive rather than land in spam.

## Solution

### Prerequisites

- A deployed instance, and its zone composed from
  `<label>.zone.yml` — see [instance-deploy](instance-deploy.md).
- A plane running a revision whose instance composition carries the
  `instance-smtp-secret` slot, and the instance reconciled against it.
- An account with a submission provider that offers SMTP on 587 or 465
  and signs with DKIM under your domain. Google Cloud refuses outbound
  port 25. [smtp-postmark](smtp-postmark.md) is one worked through.
- Write access to the private manifests repository, and a merge.
- The capability each step names. Ours is a Google group; yours may
  differ.

```bash
# the installation code, environment and label, e.g.
export QW_CODE=qw01
export QW_ENV=n
export QW_LABEL=test
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

Start at step 3 where the provider already lists the records to
publish.

### 1. Add the sending domain at the provider

In the provider's console, add the instance's domain as a sending
domain. It lists the records to publish: one or more DKIM records, and
either a return-path CNAME or an SPF include.

### 2. Create the SMTP credential

In the provider's console, create an SMTP credential restricted to
sending. Note the host, the port, the username and the password.

### 3. Publish the records

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

Add the provider's records and a DMARC record to `spec.records` in
`<code>/<label>.zone.yml`, one entry per name and type, each name
relative to the domain. The SPF entry is only for a provider that asks
for an include rather than a return-path CNAME:

```yaml
spec:
  records:
    - type: TXT
      rrdatas:
        - '"v=spf1 include:<provider-spf-domain> -all"'
    - name: <selector>._domainkey
      type: CNAME
      rrdatas:
        - <provider-dkim-target>.
    - name: <return-path>
      type: CNAME
      rrdatas:
        - <provider-return-path-target>.
    - name: _dmarc
      type: TXT
      rrdatas:
        - '"v=DMARC1; p=none"'
```

Commit and merge, then:

```bash
just crossplane-conditions "xpubliczone/$QW_CODE-$QW_ENV-$QW_LABEL"
just dns-records <domain>
```

`Ready` is `True`, and the sweep shows the DMARC value. Verify the
domain in the provider's console once it finds the records.

### 4. Write the password

**As the instance's own secrets admin.** Ours is
`grp-gcp-<code>-<env>-secrets-admin@` — join for this step, then leave.

```bash
just queenswood-instance-smtp-secret
```

It names the entry it wrote and the version it added.

### 5. Name the server

**As the installation's platform viewer again.**

In `<code>/units/<label>/values.yml`:

```yaml
mail:
  smtp:
    host: <provider-smtp-host>
    port: 587
    security: starttls
    username: <provider-smtp-username>
    from: "Queenswood <noreply@<domain>>"
    passwordSecret: queenswood-smtp
```

In `<code>/units/<label>/config.yml`, under `valuesObject`:

```yaml
mail:
  smtp:
    enabled: true
```

Commit both and merge.

### 6. Check

```bash
just argo-apps-status
```

Every Application for the instance `Synced` and `Healthy`. Invite an
address you can read from the console at `https://console.<domain>`:
the email arrives, its link opens the accept screen, and its
`Authentication-Results` header reads `spf=pass`, `dkim=pass` and
`dmarc=pass`. Raise the DMARC policy to `p=quarantine` and merge.

## Failures

**`external-adapters-service` pods in `CreateContainerConfigError`.**
`passwordSecret` is set and `queenswood-smtp` does not exist: the config
Application has not enabled `mail.smtp`, or the entry holds no version.
The pods start on their own once the Secret arrives.

**An invitation that stays pending and no email arrives.** The send
fails and is retried until it is given up on, and
`external-adapters-service` logs `Email delivery attempt failed` with
the anomaly. An authentication failure is the username or the password,
and a timeout on connect is a port Google Cloud refuses or a `security`
that does not match the port.

**A DKIM record the provider never verifies.** A TXT key longer than
255 characters was published as one string. Split it into several
quoted strings in the one rrdata, `'"<first>" "<rest>"'`.

**A zone manifest that fails to compose, naming a duplicate resource.**
Two entries share a name and a type. Merge their `rrdatas` into one
entry.

**`dmarc=fail` with `spf=pass` and `dkim=pass`.** The provider signed
under its own domain rather than the instance's. The sending domain is
not verified at the provider, or the credential belongs to another
domain.

## Rules

**MUST:**

- Send from the instance's own domain and publish its records in the
  instance's zone, through `spec.records` in `<label>.zone.yml`.
- Publish one entry per name and type, with every value for that pair
  in its `rrdatas`.
- Write the password with `just queenswood-instance-smtp-secret`, as
  the instance's own secrets admin, before enabling `mail.smtp` in
  `config.yml`.
- Set `mail.smtp.host`, `username` and `passwordSecret` in `values.yml`
  in the same merge as `mail.smtp.enabled` in `config.yml`.
- Read the build back with `just crossplane-conditions` and the
  workloads with `just argo-apps-status`.

**MUST NOT:**

- Use port 25. Google Cloud refuses it.
- Enable `mail.smtp` in `config.yml` before the entry holds a version.
- Enable `mail.catcher` on an instance whose invitations must reach
  people.
- Publish `p=quarantine` or `p=reject` before a delivered email reads
  `dmarc=pass`.

**MAY:**

- Leave an instance with no mail server. Its invitations stay pending
  and every send is given up on.
- Use port 465 with `security: tls`.

## Discussion

The provider holds the sending domain and issues the credential, the
zone publishes the records that let a receiver trust it, Secret Manager
holds the password, and the chart names the server.

**Why a recipe and not a kind.** A provider's sending domain and SMTP
credential are console acts at a provider the platform names nowhere,
as [ADR-0020](../../adr/0020-providers-are-deployment-facts.md) keeps
them. What has an API is composed: the Secret Manager container and the
records, as
[ADR-0025](../../adr/0025-building-blocks-and-what-cannot-be-one.md)
draws the line.

**Why the instance's zone.** The records sit beside the names the
instance answers on, and go with it. The apex belongs to no
installation. A `_dmarc` record on the instance's domain overrides
whatever policy the apex publishes for its subdomains.

**Why two files in one merge.** The config Application writes the
Secret and the bank's Application reads it, and they sync in waves. With
the password written first, the Secret exists by the time the bank's
pods need it. Without the version, the `ExternalSecret` fails and the
pods wait on a Secret that never comes.

**Why `p=none` first.** A receiver applies a DMARC policy to every
message that fails alignment, and a record the provider has not yet
verified fails it. Starting with no enforcement costs nothing while
nothing sends.

## References

- [outbound-email.md](../../tdd/outbound-email.md) — the adapter, its
  retries and the values it reads.
- [instance-deploy](instance-deploy.md) — the instance and its zone.
- [external-secrets](external-secrets.md) — the container and the
  version.
- [smtp-postmark](smtp-postmark.md) — these steps for one provider.
- [ADR-0020](../../adr/0020-providers-are-deployment-facts.md) —
  External providers are deployment facts, not request parameters.
- [ADR-0025](../../adr/0025-building-blocks-and-what-cannot-be-one.md) —
  Building blocks, and what cannot be one.
- [RFC 7208](https://www.rfc-editor.org/rfc/rfc7208) — SPF.
- [RFC 6376](https://www.rfc-editor.org/rfc/rfc6376) — DKIM.
- [RFC 7489](https://www.rfc-editor.org/rfc/rfc7489) — DMARC.
