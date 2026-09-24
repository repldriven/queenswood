# Parked components

Components preserved for reuse, not currently routed in console.

## `PoliciesByPolicy.svelte`

The original console Policies page: a master **list** of policies
(`GET /v1/bank/policies`) you select from, with a per-policy
capability/limit `PolicyMatrix` below. Replaced on the `/policies` route by
the resolved **effective** view (`Policies.svelte`,
`GET /v1/bank/effective-policy`), which shows one decision per scope with
provenance and no policy selector.

Kept because the **bank-of-banks super-admin** console will manage all policies
(view per policy, and eventually edit), which is what this list+matrix view
already does. It still consumes `list_my_policies` + `adaptPolicies`; when the
super-admin console exists, move it there (or point it at an admin
`list_policies` endpoint) rather than rebuilding it.
