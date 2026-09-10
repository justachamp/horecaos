# ADR 0084: Platform figures are counted from the tables, on request

- Decision status: Proposed
- Implementation status: Built — `PlatformHealthController` (tenants, orders, receipts, queue backlog by topic and consumer), `PlatformFiscalController` (every tenant's blocked receipts), V0198's indexes, tested against the migrated schema; the control-plane health board, message-flow backlog and all-tenants fiscalization board. Error-budget burn is not built here
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0006, ADR 0023, ADR 0029, ADR 0038
- Supersedes / Superseded by: —
- Open inputs: none

## Context

The health board (IA 1.1) counted the first two hundred tenants it was sent
and called that the total, and had no order, receipt or queue figures at all:
order throughput and fiscal failures were readable one tenant at a time, and
queue lag lived only in the Prometheus gauges ADR 0023 pages on. The
fiscalization board (6.1) made an operator pick tenants one by one to find
which had a receipt waiting. Message flow (4.1) listed twenty pending events
and could say nothing about lag.

The observability module already reads these tables across every tenant for
its gauges, with rules this decision keeps: age, not depth, is what matters
in a queue, and no figure names a customer.

## Decision

The control plane's platform figures are exact counts from the tables at the
moment a person asks.

1. One read for the board: tenants by status; orders placed in the last hour
   and day and those still in progress with the oldest one's age; fiscal
   receipts of the last day by outcome and every blocked one; each queue's
   backlog by topic and by consumer with the age of its oldest waiting message.
2. One cross-tenant list of blocked receipts, longest-waiting first, each
   naming its tenant; a retry stays the tenant-scoped action it always was.
3. Indexes on `created_at` for orders and receipts and a partial index on
   blocked receipts, so a platform-wide count does not read whole tables.
4. Error-budget burn stays with the monitoring stack, which owns the SLOs.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Query Prometheus from the control plane | A new dependency on the monitoring stack being reachable from the app, for figures the tables already hold | SLO burn is wanted on the board |
| A precomputed platform summary table | A second copy to keep true; the counts are cheap with the indexes | The page becomes slow at scale |
| Reuse the metrics module's polled values | They hold only totals, not per-topic or per-consumer backlog, and exist only where metrics are enabled | Never needed |

## Consequences

### Positive

- Exact totals on the board, and the queue that is behind is named.
- An operator sees every blocked receipt on the platform in one list.

### Negative

- The inbox backlog has no index to serve it and is read in full when the
  page opens; fine for a page a person opens, which is why the scraper polls
  it slowly.
- Three more indexes on write-heavy tables.

### Accepted trade-offs

- The board counts at the moment it is opened and does not refresh by
  itself; it says when it counted and offers a refresh.

## Specification

- `GET /control-plane/platform-health` (`tenant.read`, platform scope).
- `GET /control-plane/fiscal-documents/blocked` (`fiscal.document.read`,
  platform scope).
- V0198: `ordering.orders(created_at)`, `fiscal.fiscal_documents(created_at)`,
  `fiscal.fiscal_documents(blocked_at) WHERE status = 'BLOCKED'`.

## Rollout and rollback

Additive; the indexes can be dropped without data loss.

## Implementation checklist

- [x] Health read, blocked-receipt list, indexes, tests
- [x] Health board, message-flow backlog, all-tenants fiscalization

## Exit criteria

The health board's tenant count equals the number of tenants, not the first
page of them, and a twenty-minute-old event in any queue is marked on it.

## References

- `docs/frontend-information-architecture.md` §1.1, §4.1, §6.1
