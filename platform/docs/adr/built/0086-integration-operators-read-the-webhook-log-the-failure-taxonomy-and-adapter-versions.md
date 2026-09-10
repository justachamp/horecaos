# ADR 0086: Integration operators read the webhook log, the failure taxonomy and adapter versions

- Decision status: Proposed
- Implementation status: Built — `PlatformWebhookLogController` over `payments.provider_callbacks` with V0200's index, `FailureTaxonomyController` counting both queues by category, the adapter-version count on `PlatformIntegrationAdminController`, tested against the migrated schema; the control-plane webhook deliveries, error taxonomy, contracts and versions, and sandbox screens. Replaying recorded provider traffic stays in the build's contract tests
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0005, ADR 0006, ADR 0013, ADR 0026, ADR 0029
- Supersedes / Superseded by: —
- Open inputs: none

## Context

Four integration screens had nothing behind them. Every call a payment
provider makes is already kept in `payments.provider_callbacks` with whether
its signature checked and what we answered, but only the tenant's own payment
views read it. Every failed message is filed under one of ADR 0006's
categories, and the relay and consumers already decide retries by it, but
nowhere explained what a category means or counted what sits in each. Each
installation records the adapter version its last check reported, and each
approved provider endpoint says whether it is production, but no screen put
them together.

## Decision

The control plane reads what the platform already records; nothing new is kept.

1. **Webhook log.** Every provider call across tenants, newest first, with its
   tenant, kind, the provider's reference, signature result, our answer and
   whether it matched a payment. Filterable by provider and to bad signatures
   only. The bodies stay where ADR 0013 put them; the log never returns them.
2. **Failure taxonomy.** Each category with its rules (retried by timer,
   reconcile first, security relevant) and the count of dead-lettered and
   waiting messages in each queue. A code the build no longer knows is counted
   under `UNKNOWN` rather than dropped. The meaning and the action are words
   the console carries, not data.
3. **Adapter versions.** Installations counted by provider and the adapter
   version they last reported, with how many last passed a check; an
   installation never checked reports no version.
4. **Sandbox.** The installations pointed at a non-production endpoint, with
   the same connection check a live installation gets. Replaying recorded
   provider traffic belongs to the contract-test suite, which runs on every build.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Show the callback body | It can carry card and phone fragments; ADR 0029 keeps it out of operator views | A provider dispute cannot be settled from the reference and our answer |
| Keep the category words in the database | They change with the code that applies them, and translation lives in the console | Operators need to edit the guidance |
| A deprecation register for adapters | No adapter has been retired yet | The first adapter version is withdrawn |

## Consequences

### Positive

- A signature failure or a provider that stopped calling is visible across tenants.
- An operator sees what a dead letter's category asks of them before touching it.

### Negative

- The taxonomy counts read both queues in full on each open; acceptable for a
  page a person opens, not for a poller.
- The adapter count only knows what the last connection check reported.

### Accepted trade-offs

- The webhook log returns at most five hundred rows a request (the console
  asks for two hundred); older calls are found through the tenant's own
  payment views.

## Specification

- `GET /control-plane/webhooks?provider&invalidSignatureOnly&limit`
  (`integration.failure.read`, platform scope).
- `GET /control-plane/failure-taxonomy` (`integration.failure.read`).
- `GET /control-plane/adapter-versions` (`integration.installation.manage`).
- V0200: `payments.provider_callbacks(received_at DESC)`.

## Rollout and rollback

Read-only and additive; the index can be dropped without data loss.

## Implementation checklist

- [x] Three reads, index, tests
- [x] Webhook deliveries, error taxonomy, contracts and versions, sandbox screens

## Exit criteria

A callback with a bad signature at any tenant appears on the webhook log, and
a dead letter filed under a category shows in that category's count.

## References

- `docs/frontend-information-architecture.md` §3.4, §3.5, §4.3, §4.4
