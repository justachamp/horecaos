# GA4 ecommerce event contract, v1

ADR 0106 (wave P35), gap-map row `10.8e`.

## Why this is written down before the second tenant depends on it

`frontend/storefront`'s only analytics counter before this wave was a
commented-out, hard-coded, platform-wide Yandex Metrika id
(`src/index.html:58-68`) — never tenant-scoped, switched off, and wrong for
every tenant but the one whose id it hard-coded. This wave gives analytics a
real, tenant-scoped home (`ProviderCategory.ANALYTICS`, gap-map row `10.8e`)
and, with it, the first event contract this platform's frontend ever pushes
to a *third party's* data store rather than its own. A GA4 property keeps
history: a tenant who builds a report against `purchase` volume this month is
depending on this shape not changing meaning under them next month. Versioning
it now, before a second event or a second tenant exists, is cheaper than
discovering the need for a version field after the first breaking rename.

## The rule

Every event pushed to `window.dataLayer` by this application carries
`contractVersion: 1`. A future field rename, addition that changes meaning,
or removal ships as `contractVersion: 2` — a new named export beside this
one, never a silent edit of what `v1` means. `pushEcommerceEvent` (below)
enforces the version at the type level: it is not a field a call site can
forget or override.

## Events (v1)

Four named events, matching GA4's own recommended ecommerce events exactly —
deliberately not inventing a HorecaOS-specific vocabulary, so a tenant's GA4
property renders standard ecommerce reports (Monetization, Acquisition ROI)
with no custom schema on Google's side.

| Event | Fires when | Wired this wave? |
|---|---|---|
| `view_item` | A customer opens one product's detail | No — contract-ready, no call site yet |
| `add_to_cart` | A customer adds a line to their cart | No — contract-ready, no call site yet |
| `begin_checkout` | A customer opens the checkout/confirmation flow | No — contract-ready, no call site yet |
| `purchase` | An order is confirmed, once | **Yes** — `CartOrderStatusComponent` |

`view_item`, `add_to_cart` and `begin_checkout` are named in the type union
and accepted identically by `pushEcommerceEvent`; wiring their call sites into
the product and cart screens is an open input this ADR names with an owner
("whichever wave next touches those pages"), not a silent gap.

## Shape

```ts
interface EcommerceEvent {
  contractVersion: 1;
  event: 'view_item' | 'add_to_cart' | 'begin_checkout' | 'purchase';
  ecommerce: {
    currency: string;       // ISO 4217, e.g. "UZS"
    value: number;          // major units — GA4's own convention, not this platform's minor-unit one
    items: {
      item_id: string;
      item_name: string;
      price: number;        // major units, per item
      quantity: number;
    }[];
    transaction_id?: string; // purchase only — the platform order id
  };
}
```

`value` and `items[].price` are **major units** (so'm, not tiyin) —
deliberately the one place in this codebase's frontend that does not follow
ADR 0018's integer-minor-units rule, because the receiving system is GA4's
Measurement Protocol, which defines its own ecommerce object in major units.
Converting at the boundary here is the same discipline `money.ts`'s own
`formatMoney` applies going the other way, for a foreign consumer's format
rather than a human's.

## `transaction_id` and double-counting

`purchase` carries `transaction_id: order.id` (the platform order id, a
UUID). GA4 deduplicates ecommerce events sharing a `transaction_id` for
`purchase` specifically — its own documented behaviour, not a claim this
platform makes — so a customer refreshing the order-confirmation screen after
an order already fired `purchase` re-fires the same event with the same
`transaction_id` and GA4 counts it once. This is why the order id, not a
per-render token, is what identifies the transaction.

## Where it lives

- `frontend/storefront/src/app/core/analytics/ecommerce-events.ts` — the
  types and `pushEcommerceEvent`, the one function that ever writes to
  `window.dataLayer`.
- `frontend/storefront/src/app/core/analytics/analytics-injector.ts` — loads
  a brand's GTM/GA4 script tags from the new
  `GET /api/v1/storefront/tenants/{tenantId}/brands/{brandId}/analytics`
  read. `pushEcommerceEvent` works whether or not this has run yet:
  `dataLayer` is a plain array queue by design, and the standard GTM/gtag
  snippet drains whatever accumulated before it loaded.
- `CartOrderStatusComponent` — the one call site this wave wires, in
  `ngOnInit`, keyed so a poll or a re-render never fires `purchase` twice for
  the same order.

## Revisit when

- A second event (`view_item`/`add_to_cart`/`begin_checkout`) gets its call
  site — no contract change needed, just confirm this document's table above.
- A field's meaning needs to change — bump to `contractVersion: 2` and add a
  new section here rather than editing this one, the same append-only
  discipline ADR 0106 itself follows for a Decided architecture record.
