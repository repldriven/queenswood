(ns com.repldriven.queenswood.email.interface
  "Outbound email. The `email/event-processor` kind writes a pending
  EmailDelivery for each `invitation-created` and `invitation-resent`
  event, once per changelog event id. The `email/outbound-runner` kind
  claims due deliveries under a lease, reads the invitation, its bank
  and its inviter, mints the link's token, records its hash with the
  `record-invitation-token` command, sends the message through its
  `smtp/client`, and marks the delivery sent. An invitation that is no
  longer pending, has been sent again, or whose token is refused marks
  its delivery superseded and nothing is sent. A failed read, command or
  send retries on a geometric schedule and is failed and kept once the
  schedule is spent. The plaintext token lives only in the runner's
  memory and the message."
  (:require
    [com.repldriven.queenswood.email.system]))
