# Qoida domain design

This directory is the canonical version-1 logical business model for Qoida.
It records the product decisions approved before business tables and domain
code are introduced. Physical schemas are added incrementally by capability;
they must preserve the ownership and invariants defined here.

## Documents

- [Domain model](domain-model.md): glossary, aggregate ownership, policies,
  invariants, and module boundaries
- [Entity-relationship diagrams](erd.md): logical relationships split by
  bounded context
- [State machines](state-machines.md): tenant onboarding, order acceptance,
  payment, POS export, and fulfillment lifecycles
- [Business processes](processes.md): onboarding, checkout, dual-channel
  approval, POS synchronization, provider failure, Kafka, and S3 flows
- [Kafka event catalog](events.md): external envelopes, tenancy events,
  partition keys, and delivery guarantees
- [Legacy mapping](legacy-mapping.md): approved and provisional mappings from
  the FastAPI model into the new domains
- [Migration coverage register](../migration-coverage.md): complete known
  legacy table/journey/runtime disposition and readiness gates

## Approved product decisions

- The ownership hierarchy is `Tenant -> Brand -> Location`.
- A location belongs to exactly one brand.
- One Keycloak principal may belong to multiple tenants.
- Customer identity is tenant-configurable as `TENANT_SHARED` or
  `BRAND_ISOLATED`; brand-specific profiles exist in both modes.
- Catalogs and products are brand-owned and cannot be shared across brands.
- Qoida is authoritative for customer-facing products, prices, and
  availability. POS imports pass through staging and reconciliation.
- Initial POS providers are CLOPOS, r_keeper, and iiko.
- Order acceptance is inherited from platform, tenant, brand, and location.
- The supported acceptance modes are `AUTO_CONFIRM` and
  `RESTAURANT_APPROVAL`.
- Restaurant approval accepts both Qoida Operations and POS decisions. The
  first valid decision wins atomically.
- Kafka is the durable event backbone; Camel is the integration boundary.
- Media is stored in private S3-compatible object storage, not a local
  filesystem.

ADRs 0005–0034 contain planned extensions. An `Accepted` decision status means
the design is settled; it does not mean the entities in that ADR are canonical
domain facts yet. Before implementing one, update these domain documents with
the accepted entities, invariants, processes, states, and events for that slice.

## Design discipline

Order, payment, POS transport, kitchen preparation, and fulfillment are
separate lifecycles. A provider outage may create an operational exception,
but it must not rewrite an already-confirmed commercial fact.

Every tenant-owned row, command, event, object key, job, and log context carries
`tenant_id`. Cross-tenant relationships are denied by application policy and
database constraints.
