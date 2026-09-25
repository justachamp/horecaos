# ADR 0114: Manual aggregator order entry — write path and binding resolution

- Decision status: Proposed
- Implementation status: Partial — `AggregatorOrderIntakeService` and
  `JdbcAggregatorOrderStore` (both in `ordering`) exist and are covered by
  `AggregatorOrderIntakeServiceTests`: given an `AGGREGATOR`-system-type
  `tenant.sales_channels` row naming a `provider_installation_id`, and an
  active `integration.bindings` row of that installation covering the
  location or its brand, an operator's typed lines and totals write an
  `ordering.orders` row with `origin = MARKETPLACE`, `pricing_authority =
  EXTERNAL`, `entry_mode = MANUAL`, and `marketplace_binding_id` set —
  the four V0038 authority columns ADR 0040 defines, with `entry_mode =
  MANUAL` in the one place ADR 0040's own status line names as not built.
  Idempotency-Key replay is handled by the same service, checked against
  `findByIdempotencyKey` before any write. Wave 11 w5-fulfillment-destination
  (row `1.3g`) answers this record's own third open input: `fulfillmentMode`
  may now be `DELIVERY`, by reusing `OperationsOrderController
  .DestinationRequest` — the identical structured, saved-address shape the
  New order screen's address pane already sends — named by
  `customerAddressId` and scoped by a `customerAccountId` the request
  carries for the address lookup alone; the order itself still matches no
  customer account (`ck_order_owner`'s `guest_reference_hash` branch, never
  `customer_account_id`), so ADR 0040's "a marketplace order never matches
  one" is unchanged. The resolved destination is snapshotted onto
  `ordering.order_customer_snapshots` the same encrypted way
  `CheckoutOrderWriter` snapshots a native order's, the order is written
  already `CONFIRMED` (with `confirmed_at` set, `ck_order_confirmed_at`'s own
  requirement) rather than `RECEIVED` — a manual entry has no accept/reject
  step left to take — and `DeliveryPlanner#planFor` is called directly,
  opening the same plan and sourcing job a native order's confirmation opens
  through `DeliveryPlanTrigger`. `PICKUP` is unchanged. Not built: `POST
  .../orders/aggregator-entries`'s own frontend form beyond a minimal
  totals/lines entry (no line-level tax entry, no aggregator binding
  picker beyond the tenant's own configured `AGGREGATOR` channels); no
  `ordering.order_external_pricing` or `order_handover_challenges` row is
  written for a manual entry regardless of fulfilment mode (see
  Consequences and the two open inputs below, both still open).
- Date proposed: 2026-09-14
- Date decided: —
- Deciders: proposed by Claude and built on the platform owner's instruction
  of 2026-09-11; Ayubkhon Abbosov (platform owner) decides
- Depends on: ADR 0040, ADR 0026, ADR 0036, ADR 0025
- Supersedes / Superseded by: —
- Open inputs:
  - Whether a manual aggregator entry should also write `order_external_pricing`
    (settlement/reconciliation evidence) and how it should be reconciled
    against a statement with no automated push behind it — finance,
    owner: finance/settlement (ADR 0043's reconciliation reports read this
    table for automated pushes today; a manual entry has none of it)
  - Whether `fulfillment_authority` for a manual entry should ever be
    `PARTNER` rather than always `HORECAOS` — product, owner: product
    (today it is always `HORECAOS` on the argument that "no live provider
    binding" means nothing is dispatching a courier automatically; a tenant
    that separately arranges for the aggregator's own courier to collect a
    manually recorded order has no way to say so)
## Context

Row `1.3g` (gap map, wave P14): "When an aggregator phones an order through
because their integration is down, the operator has no way to record it as
that aggregator's order with externally-set pricing — it would land as an
ordinary own-channel order and corrupt the channel mix." ADR 0040 already
defines the shape a marketplace order must have — `origin`, `pricing_authority`,
`fulfillment_authority`, `entry_mode`, and `marketplace_binding_id` — and
already has one writer of it, `JdbcMarketplaceOrderIntake` in the `partner`
module, for an automated partner push authenticated by a client-credentials
token. Nothing writes `entry_mode = MANUAL`; ADR 0040's own status line has
named that gap since it was written.

Two questions had no existing answer. First, where the write belongs: `partner`
owns the automated push's whole shape — staging evidence, handover proof,
liveness — none of which exists for an operator keying totals in by hand, and
that module's `package-info.java` is explicit that its "inbound public surface"
threat model is built around a machine principal calling in, not a staff
member on the phone. Second, which binding a manually entered order should
name: `integration.bindings` resolution today is entirely capability-code
driven (`ProviderInstallationLookup#primaryBinding`/`candidateBindings`,
resolving "which provider handles capability X here"), built for outbound
calls Camel routes make. A manual entry asks a narrower, inbound question —
"which binding of the aggregator the operator already picked covers this
branch" — that the existing port had no method for.

## Decision

**The write lives in `ordering`, as a second, deliberately narrower writer of
the same V0038 authority columns — not a call into `partner`.** `ordering`
already owns `ordering.orders`; `JdbcAggregatorOrderStore` writes the order,
its first revision, its lines, and one `order_external_references` row,
mirroring `JdbcMarketplaceOrderIntake`'s shape for exactly the columns ADR
0040 requires, with `entry_mode = MANUAL`, `created_by_actor_type = USER`, and
`fulfillment_authority = HORECAOS` (never `PARTNER`, since nothing here is a
live channel to the aggregator's own dispatch). `AggregatorOrderIntakeService`
orchestrates it, resolves the channel and validates the request, entirely
inside `ordering.application`; `ordering` gains no new dependency on `partner`.

**Which binding, resolved from the channel the tenant already configured, not
a second picker.** The operator's only choice is the tenant's own
`AGGREGATOR`-system-type `tenant.sales_channels` row (ADR 0036) — the same
channel-selection pattern the rest of the New order screen already uses for
the operator channel. That channel's `provider_installation_id` names the
ADR 0026 installation; `ProviderInstallationLookup` gains one new default
method, `bindingForInstallation(tenantId, installationId, brandId,
locationId)`, resolving the single active binding of that installation
covering this branch (location-specific first, then brand-wide), by location
specificity — the identical precedence `candidateBindings` already uses, with
no capability code to key on because a manual entry has none.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| Extend `partner.MarketplaceOrderIntake`/`JdbcMarketplaceOrderIntake` with an `entryMode` parameter, called from `ordering` | Makes `ordering` depend on `partner`, the direction ADR 0040's own module doc explicitly did not want (`partner` is "somebody else calls in and holds their secret"; a manual entry holds no credential and is not that threat model) — and the automated writer's full shape (staging row, handover challenge, external pricing settlement columns) does not fit an operator's typed totals | `partner`'s own `MarketplaceIngestionService` needs to call the manual path too — e.g. a partner support tool that lets staff replay a failed push by hand |
| A brand-new `marketplace_binding_id` picker endpoint, independent of any `tenant.sales_channels` row | One more thing for the tenant to configure beside the channel they already set up for this aggregator, and a second source of truth for "which aggregator" that can drift from the channel's own `provider_installation_id` | A tenant runs the same aggregator installation behind two differently priced channels and the operator must choose which one this order counts under |
| Reuse `CartService`/`CheckoutService` with a synthetic "manually priced" quote | ADR 0040's own decision: "the escape hatch is reachable from exactly one origin", enforced by `ck_order_external_pricing_is_marketplace` — a synthetic quote would make an aggregator's total look reconstructible in every pricing reconciliation report that joins to it, the exact failure `JdbcMarketplaceOrderIntake`'s own doc already rejected for the automated path | Never — this is ADR 0040's own settled reasoning, not new to this wave |

## Consequences

### Positive

- The channel mix stops silently absorbing phoned-through aggregator orders
  as ordinary own-channel sales — the row's whole stated purpose.
- `ProviderInstallationLookup#bindingForInstallation` is a small, additive,
  defaulted port method; every existing implementation and test double needs
  no change to keep compiling.
- The board's `reference` filter (ADR 0102, row `1.1d`) finds a manually
  entered order by the aggregator's own number for free, because the same
  `order_external_references` row an automated push writes is written here
  too.

### Negative

- Two writers of the V0038 authority columns (`JdbcMarketplaceOrderIntake`,
  `JdbcAggregatorOrderStore`) instead of one, each hand-maintaining the same
  column set — a future V0038 column addition must be remembered in both
  places, and nothing enforces that beside code review.
- No `order_external_pricing` row means a manual entry carries no settlement
  evidence (commission, payout, a reconciliation statement) — ADR 0043
  reporting that reads that table for automated pushes will not find a
  manual entry there at all, and must be told explicitly to treat its
  absence as "this order has none" rather than "this order was never
  ingested".
- `fulfillment_authority = HORECAOS` always, for every manual entry: a
  tenant whose aggregator relationship genuinely still has the partner's own
  courier collecting a phoned-through order has no way to say so, and the
  order's own delivery tracking will show HorecaOS as accountable for a
  handover it is not making.

### Accepted trade-offs

- A manual entry's totals are trusted arithmetic, not verified against
  anything — the same trade-off ADR 0040 already accepts for the automated
  push ("HorecaOS validates arithmetic and nothing else"), extended here to
  a human's typed numbers rather than a partner's signed payload, which is a
  strictly weaker evidential position the Implementation status line already
  says out loud.

## Specification

```text
ordering.application.AggregatorOrderIntakeService
  Command(tenantId, brandId, locationId, channelCode, externalOrderId,
          lines, currency, subtotalMinor, discountMinor, feeMinor,
          totalMinor, idempotencyKey, operatorSubject,
          fulfillmentMode, customerAccountId, destination)  # wave 11 w5
  -> Result(orderId, publicOrderNumber, replayed)

ordering.infrastructure.persistence.JdbcAggregatorOrderStore
  writes ordering.orders (entry_mode = MANUAL, fulfillment_mode as given;
  DELIVERY writes status = CONFIRMED + confirmed_at, PICKUP keeps
  status = RECEIVED), order_revisions (revision 1, source = CHECKOUT),
  order_lines (external_mapping_status MAPPED|UNMAPPED per line), and one
  order_external_references row (PARTNER_ORDER_ID, issued_by = HORECAOS)

integration.api.provider.ProviderInstallationLookup
  + bindingForInstallation(tenantId, installationId, brandId, locationId)
      -> Optional<BindingRef>, location-specific over brand-wide,
      defaulted to Optional.empty() for existing implementations

POST /api/v1/tenants/{t}/brands/{b}/locations/{l}/orders/aggregator-entries
  ORDER_PLACE at LOCATION scope, mutating, Idempotency-Key required

# Wave 11 w5-fulfillment-destination (DELIVERY): resolves the destination
# through ordering.application.CustomerAddressBook (the same port
# CartService#setDestination uses), encrypts it onto
# ordering.order_customer_snapshots, and calls
# fulfillment.api.DeliveryPlanner#planFor directly — the confirmation-time
# call DeliveryPlanTrigger makes for a native order, made here instead
# because this write has no checkout transaction for that trigger to fire on.
```

taxMinor is derived, never independently typed: `totalMinor - subtotalMinor
- feeMinor + discountMinor`, refused if negative — so `ck_order_total_reconciles`
always holds by construction rather than being asserted against an
operator's own arithmetic.

## Rollout and rollback

Additive only: a new endpoint, a new service, a new store, one new default
port method. Nothing existing changes shape. Disabling the endpoint's
capability grant removes the whole surface with no data migration.

## Implementation checklist

- [x] `AggregatorOrderIntakeService` resolves the channel, validates it is
      `AGGREGATOR`-typed and active, resolves the binding, derives tax, and
      handles Idempotency-Key replay
- [x] `JdbcAggregatorOrderStore` writes the order, revision, lines, and one
      external reference row
- [x] `ProviderInstallationLookup#bindingForInstallation` and its
      `JdbcProviderInstallationLookup` implementation
- [x] `POST .../orders/aggregator-entries` on `OperationsOrderController`
- [x] Frontend: «Заказ агрегатора» toggle on the New order screen
- [x] Delivery fulfilment for a manual entry (wave 11 w5-fulfillment-destination):
      `fulfillmentMode = DELIVERY` resolves a named customer's saved address
      through `CustomerAddressBook`, snapshots it, writes the order
      `CONFIRMED`, and opens a plan through `DeliveryPlanner#planFor` — still
      no `order_external_pricing`/`order_handover_challenges` row, see below
- [ ] `order_external_pricing`/settlement evidence for a manual entry, of
      either fulfilment mode (open input above)

## Exit criteria

An operator can select the tenant's own configured aggregator channel, type
the lines and the total the aggregator already collected, and the resulting
order carries `origin = MARKETPLACE`, `pricing_authority = EXTERNAL`,
`entry_mode = MANUAL`, and a `marketplace_binding_id` that resolves back to
that aggregator's own installation — findable on the board by the
aggregator's own order number, and excluded from every own-channel mix
report exactly as an automated push already is.

## References

- ADR 0040: Marketplace channel: inbound aggregator orders and the partner API
- ADR 0026: Provider installations, bindings, and secret references
- ADR 0036: Sales channels and serviceability
- `docs/operations-gap-map.md`, row `1.3g`
