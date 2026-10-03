# 34. Outbound calls go through a breaker on their destination

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

The platform calls out in three places, each from a loop that takes a
due item, makes its call outside any FDB transaction, records the
outcome, and retries later or gives up:

- The intent poller carries out each external adapter's operations
  against a payment or identity verification provider's API.
- The webhook runner delivers each notification to a customer's
  endpoint.
- The email runner sends each invitation through the mail server.

Two more calls are made while a request waits for them, a company lookup
at the company register and a Confirmation of Payee check at a payment
provider, each through an external adapter.

The property wanted is that an outage at a destination stops the calls
to it, and fails nothing its recovery would have delivered, without a
person stepping in. Today each loop retries each item on its own
schedule and counts every failed call against that item. A provider
down for longer than the poller's twenty attempts fails every payment
queued behind it, though nothing was wrong with any of them; the
webhook runner pauses an endpoint after a day without a success, and
only the customer can resume it, which does not scale with the number
of customers; and the schedules, attempt caps, timeouts and windows are
constants in code, each loop's different from the others'.

The shortlist:

- **Keep per-item retry, with longer schedules.** Rejected: a longer
  schedule still spends an item's attempts on an outage it did not
  cause, and every item keeps calling a destination that is down.
- **A breaker held in each process's memory.** Rejected: the webhook
  and email runners share their work across replicas through FDB
  claims, so each replica would learn an outage separately, and a
  restart forgets one in progress.
- **Escalate a breaker that stays open to a pause a person lifts.**
  Rejected: lifting it is manual work for every customer whose endpoint
  had an outage.
- **A breaker per destination, its state in FDB, every number in
  configuration.** A destination is what fails as a unit, so the
  breaker is the one place an outage is learned, every replica reads
  it, and the policy is set per environment.

## Decision

Every outbound call goes through a circuit breaker on its destination,
whose state is an FDB record shared by every replica: closed, it lets
calls through and counts consecutive failures; open, it lets none
through for a cool-down; half-open, it lets one probe through, closing
on its success and reopening on its failure with a longer cool-down.

The decision has these parts:

- Name a destination for what fails as a unit: an external adapter's
  API for each adapter, a customer's webhook endpoint for each endpoint,
  and the mail server.
- Count towards the breaker only failures that are the destination's:
  an unreachable destination, a timeout, a 5xx and a 429. A refusal
  that is the item's own stays with the item, and every non-2xx from a
  customer's endpoint is the endpoint's.
- Hold the breaker's state in one FDB record per destination, written
  when a call fails and when its state changes, and claim a half-open
  probe in one transaction, so two replicas never probe at once.
- Leave an item due while its destination's breaker is open without
  counting an attempt against it.
- Answer a call a request waits for as unavailable while its
  destination's breaker is open, rather than after the call's timeout.
- Make a call a destination is given on a schedule, such as checking an
  adapter's subscriptions at its provider, through its breaker too, so
  it probes the breaker whether or not anything else is sent.
- Give an item up when its own attempts reach the maximum, or when it
  is older than the maximum age, whichever comes first, and report it
  as undelivered where its loop reports one.
- Take every number from the loop's system configuration: a
  `:default` policy, a policy per operation where a loop has operations
  that override the default, and the breaker's threshold and
  cool-downs; nothing in code but the schema the configuration is
  checked against at start-up.
- Stop pausing a webhook endpoint on failure: its breaker opens and
  closes on its own, and a pause is a person's.

## Consequences

Easier:

- An outage costs the items queued behind it time, not attempts, and
  they go out when the destination recovers.
- A customer's endpoint recovers from an outage without the customer.
- Each environment sets its own schedules, caps and cool-downs, the
  scenario rig shortening them as it does today.
- The three loops share one policy and one breaker, so a fourth
  destination gets both by naming itself.

Harder:

- A failing call writes to FDB, so a destination failing at volume
  costs a write per failure until its breaker opens.
- An item that fails for its own reason with a 5xx counts against its
  destination, and enough of them open the breaker for every item; the
  threshold and the maximum age bound what that costs.
- A destination's breaker is shared by everything sent to it, so a
  provider's partial outage stops all of an adapter's operations.
- A new loop or a new number can still be written in code; the start-up
  schema check refuses a policy missing a key, and `just semgrep` is
  where a check for a numeric literal in a runner would go.
