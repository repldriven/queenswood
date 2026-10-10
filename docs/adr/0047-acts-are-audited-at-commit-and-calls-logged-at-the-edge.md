# 47. Acts are audited at commit, and calls logged at the edge

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

The property wanted is that every change a member, an operator or the
bank's credential makes to a bank's records can be attributed, act by act, and
that every call made to the API, a read included, can be accounted
for. A regulator or a bank asking why customers' accounts moved onto
new terms asks who approved it. One asking who looked at a customer's
details, or who kept being refused, asks the second question, and no
record of a change answers it.

Without both, the answers are partial. A record holds its latest act
of each kind and no earlier one, so a product version edited ten times
names its last editor. Every transition a record gains adds an `_at`
and `_by` pair to its proto. The bank's audit log, `GET
/v1/bank/audit-events`, is assembled on read from the transition fields
of the bank, its members and invitations and a `MemberRoleChange`
record kept only for it, so it covers access and nothing else. No
record of a call exists at all: the traces in SigNoz name a route and
an outcome, but they are sampled and expire.

The shortlist:

- **The actor on the record, beside the time of each act**, as
  [ADR-0046](0046-who-did-what-is-recorded-on-the-record.md) decided.
  Rejected: it keeps the latest act of each kind, grows a record by a
  pair per transition, and leaves the history to be assembled from
  fields that were never meant to be one.
- **The actor on the changelog envelope.** Rejected: the changelog
  carries every write, the platform's included, is kept per store and
  read by relays in commit order rather than by bank, subject or
  actor, and a store with no changelog has no history.
- **The command and its reply.** Rejected: neither is kept once
  answered, and a synchronous write sends no command.
- **A log of API calls alone.** Rejected: it records what was asked,
  not what committed. A command answered 202 may be refused by its
  processor, a call whose reply was lost may have committed, a replay
  changes nothing, one call can change many records, and the API is
  not in the transaction that makes a command's change, so a call and
  its change can disagree.
- **An audit entry committed with each act, and an access entry for
  each call, joined by the request's id.** The audit entry is in the
  transaction that makes the change, so the two cannot disagree. The
  access entry is written at the edge, where reads and refusals are
  seen. A scenario check that every write call left an audit entry
  catches a write path that forgot one.

## Decision

Every change a member, an operator or the bank's credential makes
commits an
append-only audit entry in the transaction that makes it, every call
to the API leaves an access entry written at the edge, and the two
share the id the API gives the request; a record keeps only the audit
block, and the transition band leaves it.

The decision has these parts:

- Append each entry, an `AuditEntry` message, under the bank and the
  versionstamp of the commit that makes it, outside the Record Layer,
  and an empty key beside it under the bank, the subject's id and that
  versionstamp, and under the bank, the kind and that versionstamp, so
  a bank's log, one record's history and any set of kinds each read
  newest first, a page at a time.
- Hold in each entry the act's kind, the subject's kind and id, the
  actor as `created_by`, the request id, the time, and `details`, a
  json string of whatever the act carried, of any depth, never a
  person's name or contact details, as
  [ADR-0045](0045-a-persons-identity-evidence-stays-with-the-customers-provider.md)
  has it, and indexed by nothing.
- Number `AuditSubjectKind` in tens and each subject's
  `AuditKind`s up from its own, so a kind's number shows its
  subject.
- Write one entry per act, naming the record it acts on, through one
  function in an `audit` brick, `record`, called in the transaction
  that saves the change; a record the act writes as its consequence
  keeps only its own `created_by`.
- Write no entry for what the platform does by itself, such as the
  scheduler completing a run, a provider settling a payment or a relay
  reacting to an event.
- Keep the log append-only: a versionstamped key exists only from its
  commit, so nothing can write one twice, and the `audit` brick
  exposes `record` and reads and nothing else and is the only brick
  that names the log's keys, which a semgrep rule holds.
- Build the caller's actor in one place in the `api` base, from the
  request's authentication, give every request an id, a uuidv7, in an
  interceptor there, answer it in the `Request-Id` header, and pass
  both to the domain wherever the actor goes: the request id as a
  command's causation id, which the processor passes on beside the
  actor, and in a synchronous call's arguments.
- Write an access entry for every call, from that interceptor after
  the response is chosen: the request id, the principal where one was
  authenticated, the bank, the method, the route's template, the ids
  in its path, the status, the rejection's type, whether the response
  was replayed, the client's address and the duration, and no body or
  query value.
- Write access entries off the request's thread, many to a
  transaction, in an `access-log` brick whose queue is bounded; count
  an entry the full queue refuses as dropped, and publish the count
  beside the queue's depth.
- Keep audit entries for the life of the bank. Keep access entries for
  a period the installation configures, and delete older ones from a
  system job, the only deletion either store allows.
- Serve a bank its own entries at `GET /v1/bank/audit-log`, which
  takes a set of kinds, and `GET /v1/bank/access-log`, and an
  operator any bank's, each actor named on read as the access routes
  name one today.
- Assert in the API scenario runner, after each step, that every
  successful write call that was not a replay left at least one audit
  entry under its request id, exempting a retry after a lost reply,
  whose act committed under the request that lost it, and a step that
  says `:audited false` because its write changes nothing.
- Remove a transition's `_at` and `_by` pair from its record once
  its act writes an audit entry, keeping among the record's own fields
  a value the domain reads to decide, and leaving a platform transition
  to the record's `status` and `updated_at`, clearing `updated_by`.
- Keep the audit block on every record: `created_at` and `created_by`,
  and `updated_at` with `updated_by` where the record has an actor
  for its changes, the pair set together; publish only that block
  through the API.
- Remove a record kept only to remember an act, such as
  `MemberRoleChange`, once its act writes an audit entry, and leave
  the changelog envelope without an actor.

## Consequences

Easier:

- The audit entry and the change commit together, so an entry never
  names a change that did not happen, and every act is kept, not only
  the latest of its kind.
- Adding a transition adds an audit kind, not a pair of fields, and
  the bank's audit log covers every record rather than access alone.
- Reads, refusals and replays are accounted for, and a request id
  leads from a call to every change it made.
- The platform's own writes, settlement, interest and inbound
  payments among them, pay nothing.

Harder:

- A person's act on a busy path writes three more keys, blind and
  conflicting with nothing: a payment a bank submits through the API
  is the busiest, measured with the perf suite before and after.
- The log reads newest first by bank, by subject or by a set of kinds
  and nothing else: a page across several kinds reads each kind's
  keys, and a filter on anything in `details` would scan.
- An access entry is lost when its process dies with it still queued,
  and dropped when the queue is full, so the access log is complete
  only while the dropped count stays at zero.
- Every write path threads the request id beside the actor, and a test
  driving a domain directly supplies both.
- Coverage drifts as write routes are added: the scenario check makes
  a write route that records no audit entry fail, but a write path no
  scenario reaches is unchecked, as is a brick written to from outside
  the API.
- Removing the transition band rewrites the
  [record-protos](../recipes/code/record-protos.md) layout and every
  record that carries one, a schema change each installation takes as
  [schema-evolution](../recipes/code/schema-evolution.md) describes.
