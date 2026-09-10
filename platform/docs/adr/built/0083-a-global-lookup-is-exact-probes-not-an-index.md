# ADR 0083: A global lookup is exact probes, not an index

- Decision status: Proposed
- Implementation status: Built — `JdbcGlobalLookup` and `GET /control-plane/lookup` with value-first indexes (V0197), tested against the migrated schema; the control-plane global lookup screen. Finding a customer by phone number is not built
- Date proposed: 2026-09-11
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction of 2026-09-10 to finish the control plane's remaining waves; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0025, ADR 0029, ADR 0056
- Supersedes / Superseded by: —
- Open inputs: whether support may find a customer by phone across tenants, which would be a search of protected data needing its own record — owner Ayubkhon Abbosov

## Context

Support is handed an identifier — an order number from a restaurant, an id
from a log line, a transaction id from a payment provider, a courier's badge
— and has to find what it is and whose it is. The console could find a
tenant by slug and nothing else (IA 10.1).

Every index on those identifiers leads with `tenant_id`, which is right for
a tenant's own screens and useless to a question that does not know the
tenant yet. And the identifiers that matter most to support are the ones
people read aloud, not ids.

## Decision

The global lookup is a fixed set of exact-match probes, one indexed equality
per table that could hold the identifier, and nothing personal in the answer.

1. An id is checked against every kind of record it could be: tenant, brand,
   location, order, customer, courier, device, installation, POS export,
   fiscal document.
2. Anything else is matched exactly as a tenant slug, a tenant name
   (substring, the one inexact match), an order number, a courier's
   reference, a POS order id, a payment provider's transaction id, or a
   partner's order reference in its stored normalised form.
3. Each hit says what it is, whose it is, what it matched on, and a label
   with no personal data. Customers and couriers come back by id only.
4. Value-first indexes make the probes that do not know the tenant use an
   index instead of reading the table.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A search index (a table or an engine fed by events) | A second copy of every identifier to keep in step, for exact matches the tables already answer | Fuzzy or prefix search is asked for |
| Ask each module in turn | Ten calls to answer one question, and a module boundary is not what support is asking about | A module's tables move out of this database |
| Phone search through the blind indexes | Finding a person by phone across restaurants is a directory of people, and phones are protected data | A decided need, with its own audit and access rule |

## Consequences

### Positive

- Support finds an order from its number without knowing the restaurant.
- Nothing is copied; the answer is always the tables' own.

### Negative

- Exact only: a mistyped order number finds nothing.
- The lookup reads several modules' schemas directly, the one place that
  does; a table renamed in one of them breaks the lookup's test, not the build.
- Five more indexes to maintain on write-heavy tables.

### Accepted trade-offs

- Twenty hits per probe: a common order number across many tenants is
  recognisable but not a listing.

## Specification

- `GET /control-plane/lookup?q=` (`tenant.read` at platform scope, held by
  platform support and administrators).
- V0197: indexes on `ordering.orders(public_order_number)`,
  `ordering.order_external_references(reference_value_normalised)`,
  `integration.pos_order_exports(external_order_id)`,
  `payments.payment_transactions(provider_reference)`,
  `fulfillment.couriers(display_reference)`.
- The partner-reference rule is restated from `ExternalReference.normalise`
  and a test holds the two to the same answers.

## Rollout and rollback

Additive; the indexes can be dropped without data loss.

## Implementation checklist

- [x] Probes, indexes, endpoint, tests
- [x] Console screen, with the query kept in the address so a result can be shared
- [ ] Phone search — not built; see Open inputs

## Exit criteria

An order number read out by a restaurant finds that order and its tenant in
one search, without the operator choosing a tenant first.

## References

- `docs/frontend-information-architecture.md` §10.1
