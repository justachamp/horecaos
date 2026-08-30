# Logical entity-relationship model

The ERD is split by bounded context so ownership remains readable. Every
tenant-owned entity shown below carries `tenant_id`, even when a relationship
already implies it.

## SaaS, identity, and customer

```mermaid
erDiagram
    PRINCIPAL ||--o{ TENANT_MEMBERSHIP : has
    TENANT ||--o{ TENANT_MEMBERSHIP : contains
    TENANT_MEMBERSHIP ||--o{ TENANT_ROLE_GRANT : receives
    TENANT_MEMBERSHIP ||--o{ BRAND_ROLE_GRANT : receives
    TENANT_MEMBERSHIP ||--o{ LOCATION_ROLE_GRANT : receives

    TENANT ||--o{ BRAND : owns
    BRAND ||--o{ LOCATION : owns
    BRAND ||--o{ BRAND_ROLE_GRANT : scopes
    LOCATION ||--o{ LOCATION_ROLE_GRANT : scopes

    TENANT ||--o{ CUSTOMER_IDENTITY_POLICY : configures
    TENANT ||--o{ CUSTOMER_ACCOUNT : owns
    PRINCIPAL ||--o{ CUSTOMER_PRINCIPAL_LINK : authenticates
    CUSTOMER_ACCOUNT ||--o{ CUSTOMER_PRINCIPAL_LINK : linked_by
    CUSTOMER_ACCOUNT ||--o{ CUSTOMER_BRAND_PROFILE : has
    BRAND ||--o{ CUSTOMER_BRAND_PROFILE : scopes

    ONBOARDING_TEMPLATE ||--o{ ONBOARDING_RUN : defines
    TENANT ||--o{ ONBOARDING_RUN : provisions
    ONBOARDING_RUN ||--o{ ONBOARDING_STEP : executes
```

## Catalog, pricing, inventory, and media

```mermaid
erDiagram
    BRAND ||--o{ CATALOG : owns
    CATALOG ||--o{ CATEGORY : contains
    BRAND ||--o{ PRODUCT : owns
    PRODUCT ||--|{ PRODUCT_VARIANT : contains
    CATEGORY ||--o{ CATALOG_ITEM : contains
    PRODUCT ||--o{ CATALOG_ITEM : publishes

    BRAND ||--o{ MODIFIER_GROUP : owns
    MODIFIER_GROUP ||--|{ MODIFIER_OPTION : contains
    PRODUCT ||--o{ PRODUCT_MODIFIER_GROUP : configures
    MODIFIER_GROUP ||--o{ PRODUCT_MODIFIER_GROUP : assigned

    BRAND ||--o{ PRICE_LIST : owns
    PRICE_LIST ||--o{ PRICE_LIST_ITEM : contains
    PRODUCT_VARIANT ||--o{ PRICE_LIST_ITEM : priced_by
    LOCATION ||--o{ LOCATION_PRICE_LIST : uses
    PRICE_LIST ||--o{ LOCATION_PRICE_LIST : assigned

    LOCATION ||--o{ LOCATION_OFFERING : offers
    PRODUCT_VARIANT ||--o{ LOCATION_OFFERING : offered_as
    LOCATION_OFFERING ||--o| INVENTORY_POSITION : tracks
    INVENTORY_POSITION ||--o{ INVENTORY_RESERVATION : reserves
    INVENTORY_POSITION ||--o{ INVENTORY_MOVEMENT : records

    PRODUCT ||--o{ PRODUCT_MEDIA : displays
    MEDIA_ASSET ||--o{ PRODUCT_MEDIA : supplies
```

## Orders, payments, and fulfillment

```mermaid
erDiagram
    TENANT ||--o{ ORDER_ACCEPTANCE_POLICY : defaults
    BRAND ||--o{ ORDER_ACCEPTANCE_POLICY : overrides
    LOCATION ||--o{ ORDER_ACCEPTANCE_POLICY : overrides

    CUSTOMER_ACCOUNT ||--o{ CUSTOMER_ORDER : places
    CUSTOMER_BRAND_PROFILE o|--o{ CUSTOMER_ORDER : represents
    LOCATION ||--o{ CUSTOMER_ORDER : receives
    ORDER_ACCEPTANCE_POLICY ||--o{ CUSTOMER_ORDER : snapshotted_into

    CUSTOMER_ORDER ||--|{ ORDER_ITEM : contains
    ORDER_ITEM ||--o{ ORDER_ITEM_MODIFIER : contains
    CUSTOMER_ORDER ||--o| ORDER_ADDRESS_SNAPSHOT : delivers_to
    CUSTOMER_ORDER ||--o{ ORDER_STATUS_HISTORY : records
    CUSTOMER_ORDER ||--o{ ORDER_ACCEPTANCE_DECISION : receives

    CUSTOMER_ORDER ||--o{ PAYMENT_INTENT : paid_by
    PAYMENT_INTENT ||--o{ PAYMENT_ATTEMPT : attempts
    PAYMENT_ATTEMPT ||--o{ PAYMENT_TRANSACTION : records
    PAYMENT_INTENT ||--o{ REFUND : refunds

    CUSTOMER_ORDER ||--o{ SHIPMENT : fulfilled_by
    SHIPMENT ||--o{ SHIPMENT_ASSIGNMENT : assigns
    SHIPMENT ||--o{ FULFILLMENT_EVENT : records
```

The ADR 0019 half of this diagram is built by migration V0022, under the physical
names below. The logical names above are kept because the payment and fulfilment
halves of the diagram are still targets, and renaming only the built third would
make the diagram harder to read rather than easier.

| Logical entity | Physical table | Notes |
|---|---|---|
| — | `ordering.carts`, `ordering.cart_lines` | Mutable, bound to one location and channel, rebuilt rather than moved. The ADR's `cart_fulfillment` is not created: nothing captures a delivery address yet |
| — | `ordering.checkout_attempts` | The transactional checkout idempotency record |
| `CUSTOMER_ORDER` | `ordering.orders` | One order per accepted quote and per cart, both enforced by unique constraints |
| `ORDER_ITEM` | `ordering.order_lines` | Snapshotted names and amounts; `SELECT`/`INSERT` only |
| `ORDER_ITEM_MODIFIER` | `ordering.order_line_modifiers` | |
| — | `ordering.order_adjustments` | Every step that made up the total, copied from the quote |
| `ORDER_ADDRESS_SNAPSHOT` | `ordering.order_customer_snapshots` | Encrypted per ADR 0029; `UPDATE` granted only for crypto-shredding |
| `ORDER_STATUS_HISTORY` | `ordering.order_state_history` | Append-only |
| `ORDER_ACCEPTANCE_DECISION` | `ordering.approval_decisions` | Every command, including the losers; exactly one `effective` |
| — | `ordering.order_timers` | The durable approval deadline |
| — | `ordering.order_process_states` | One row per order per concern; only `ORDER_INVENTORY` is driven today |

`PAYMENT_INTENT` and everything below it is still a target: ADR 0013 is unbuilt,
and ordering calls a declared-unwired `PaymentIntentPort` in its place.

## POS and provider integration

```mermaid
erDiagram
    TENANT ||--o{ INTEGRATION_INSTALLATION : owns
    INTEGRATION_INSTALLATION ||--o{ POS_LOCATION_BINDING : binds
    LOCATION ||--o{ POS_LOCATION_BINDING : connects

    POS_LOCATION_BINDING ||--o| POS_SYNC_POLICY : configures
    POS_LOCATION_BINDING ||--o{ POS_SYNC_RUN : executes
    POS_SYNC_RUN ||--o{ POS_IMPORT_SNAPSHOT : captures
    POS_SYNC_RUN ||--o{ POS_IMPORT_DIFFERENCE : discovers
    POS_LOCATION_BINDING ||--o{ POS_ENTITY_MAPPING : maps

    CUSTOMER_ORDER ||--o{ POS_APPROVAL_REQUEST : requests
    CUSTOMER_ORDER ||--o{ POS_ORDER_EXPORT : exports
    POS_LOCATION_BINDING ||--o{ POS_APPROVAL_REQUEST : transports
    POS_LOCATION_BINDING ||--o{ POS_ORDER_EXPORT : transports

    INTEGRATION_INSTALLATION ||--o{ INTEGRATION_INBOX_MESSAGE : receives
    INTEGRATION_INSTALLATION ||--o{ INTEGRATION_DEAD_LETTER : quarantines
```

## Planned target extensions (unimplemented ADRs)

The diagrams below expose the intended relationships needed for migration
coverage. They are governed by ADRs 0013–0034 and do not become accepted
physical tables until the applicable ADR moves through implementation, even
where that ADR's decision status is `Accepted`.

### Catalog publication, cart, quote, and inventory reservation

```mermaid
erDiagram
    BRAND ||--o{ CATALOG_PUBLICATION : publishes
    CATALOG ||--o{ CATALOG_PUBLICATION : snapshots
    CATALOG_PUBLICATION ||--o{ CATALOG_PUBLICATION_ITEM : contains

    CUSTOMER_ACCOUNT o|--o{ CART : owns
    LOCATION ||--o{ CART : prices_for
    CART ||--|{ CART_LINE : contains
    CART ||--o{ PRICING_QUOTE : quoted_as
    PRICING_QUOTE ||--|{ PRICING_QUOTE_LINE : contains
    PRICING_QUOTE ||--o{ PRICING_ADJUSTMENT : explains

    LOCATION ||--o{ STOCK_ITEM : stocks
    PRODUCT_VARIANT ||--o{ STOCK_ITEM : identifies
    STOCK_ITEM ||--|| INVENTORY_POSITION : summarizes
    INVENTORY_RESERVATION ||--|{ INVENTORY_RESERVATION_LINE : contains
    STOCK_ITEM ||--o{ INVENTORY_RESERVATION_LINE : reserves
    STOCK_ITEM ||--o{ INVENTORY_MOVEMENT : records

    CART ||--o| INVENTORY_RESERVATION : holds
    PRICING_QUOTE o|--o| CUSTOMER_ORDER : snapshotted_into
    INVENTORY_RESERVATION o|--o| CUSTOMER_ORDER : committed_for
```

### SaaS commercial, recovery, fiscal, and notification

```mermaid
erDiagram
    PLAN ||--|{ PLAN_VERSION : versions
    PLAN_VERSION ||--o{ PLAN_ENTITLEMENT : grants
    TENANT ||--o{ SUBSCRIPTION : subscribes
    PLAN_VERSION ||--o{ SUBSCRIPTION : selected_by
    TENANT ||--o{ USAGE_EVENT : meters
    SUBSCRIPTION ||--o{ ENTITLEMENT_OVERRIDE : overrides

    CUSTOMER_ORDER ||--o{ RECOVERY_CASE : concerns
    RECOVERY_CASE ||--o{ REMEDY_DECISION : decides
    REMEDY_DECISION ||--o{ REMEDY_EXECUTION : executes
    CUSTOMER_ACCOUNT ||--o{ BENEFIT_GRANT : receives
    RECOVERY_CASE ||--o{ BENEFIT_GRANT : grants

    PAYMENT_TRANSACTION ||--o{ FISCAL_RECEIPT : evidenced_by
    SETTLEMENT_IMPORT ||--|{ SETTLEMENT_LINE : contains
    SETTLEMENT_LINE o|--o| PAYMENT_TRANSACTION : reconciles

    CUSTOMER_ACCOUNT ||--o{ NOTIFICATION_PREFERENCE : configures
    NOTIFICATION_TEMPLATE ||--|{ NOTIFICATION_TEMPLATE_VERSION : versions
    CUSTOMER_ORDER ||--o{ NOTIFICATION : causes
    NOTIFICATION ||--o{ NOTIFICATION_DELIVERY_ATTEMPT : attempts
```

### Delivery plan, internal courier, and external partner sourcing

```mermaid
erDiagram
    CUSTOMER_ORDER ||--o{ DELIVERY_PLAN : requests
    DELIVERY_PLAN ||--o{ DELIVERY_QUOTE : compares
    DELIVERY_PLAN ||--|{ SHIPMENT : realizes
    SHIPMENT ||--o{ ASSIGNMENT_ATTEMPT : sources

    INTEGRATION_INSTALLATION o|--o{ DELIVERY_QUOTE : provides
    INTEGRATION_INSTALLATION o|--o{ ASSIGNMENT_ATTEMPT : external_source

    PRINCIPAL ||--o| COURIER_PROFILE : authenticates
    COURIER_PROFILE ||--o{ COURIER_AVAILABILITY : declares
    DISPATCH_POOL ||--o{ DISPATCH_POOL_COURIER : includes
    COURIER_PROFILE ||--o{ DISPATCH_POOL_COURIER : belongs
    DISPATCH_POOL ||--o{ DISPATCH_POOL_LOCATION : serves
    LOCATION ||--o{ DISPATCH_POOL_LOCATION : covered_by
    COURIER_PROFILE ||--o{ COURIER_LOCATION_OBSERVATION : reports
    COURIER_PROFILE o|--o{ ASSIGNMENT_ATTEMPT : internal_source
```

### Migration control and reconciliation

```mermaid
erDiagram
    MIGRATION_PROGRAM ||--|{ MIGRATION_SCOPE : contains
    TENANT ||--o{ MIGRATION_SCOPE : migrated_by
    MIGRATION_SCOPE ||--o{ MIGRATION_RUN : executes
    MIGRATION_SCOPE ||--o{ MIGRATION_ENTITY_MAPPING : maps
    MIGRATION_RUN ||--o{ MIGRATION_QUARANTINE_ITEM : quarantines
    MIGRATION_RUN ||--o{ MIGRATION_RECONCILIATION_RESULT : verifies
    MIGRATION_SCOPE ||--o{ MIGRATION_CUTOVER_DECISION : transfers
```

## Required physical constraints

- Unique tenant slug globally.
- Unique brand code and slug within a tenant.
- Unique location code and slug within a brand.
- Unique principal membership per tenant.
- Unique active principal link per tenant in `TENANT_SHARED` mode, or per tenant
  and identity-partition brand in `BRAND_ISOLATED` mode. The same principal may
  link in several tenants and, in isolated mode, several brand partitions.
- Unique customer brand profile per account and brand.
- Composite foreign keys on `(tenant_id, brand_id)` and
  `(tenant_id, brand_id, location_id)`.
- One active acceptance-policy version per tenant, brand, or location scope.
- One effective acceptance decision per order, while retaining all attempted
  decisions.
- Unique provider inbox key and idempotency key per installation.
- Unique POS external entity reference within a location binding and entity
  type.
