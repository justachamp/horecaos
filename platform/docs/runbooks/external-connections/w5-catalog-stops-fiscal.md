# External facts behind rows 2.5a, 4.2c, 4.2e, 4.6a and 10.7c (batch 21, wave w5)

**Not a runbook for an incident.** The list of things this wave built against a fake, a default or a
fixture because the real fact is held by someone outside the repository, and what to do on the day it
arrives. Each entry says what was built, what it was built against, who can answer, and the exact place the
answer goes. **Last executed:** never; none of these has been connected.

The rule behind every entry: a row is graded on what an operator can do today, and an unconnected outside
system is stated as unconnected, never reported as working (ADR 0011). Nothing below is claimed.

## 1. A real aggregator that takes an availability write (row 2.5a, ADR 0141)

- **Fact needed.** Which of Uzum Tezkor, Yandex Eda, Wolt and Express24 offers a third party an API to
  mark an item available or unavailable, with: the item identifier, a batch size, a rate limit, a
  read-back, the authentication scheme, and a sandbox. Plus the partner agreement. Owner: product and
  partnerships.
- **Built against.** `FakeAggregatorHttpProvider` and `FakeAggregatorAvailabilityAdapter`
  (`integration/camel/marketplace/fake/`, `local` profile only, seeded by `tools/seed-marketplace`). Its path
  (`PUT /v1/items/{id}/availability`), body and answers are this build's own invention, so the fake proves
  the platform's side (the reconciler, the route, the gateway, secret resolution, the stale alert, the dry
  run) and nothing about any vendor.
- **Until then.** Every real marketplace binding reads `MANUAL`: «not propagated automatically», and the
  operator updates the partner portal by hand. The generic partner pull (`GET .../availability`) already
  serves any aggregator that integrates against ADR 0040's contract.
- **Connect on the day.** One class per provider implementing `MarketplaceAvailabilityAdapter` (providerType,
  `availabilityCall`, `interpret` mapping the vendor's "no such item" to `PushConclusion.UNKNOWN_ITEM`), beside
  the fake; a note under `docs/providers/<aggregator>.md`; then the Phase 3 week: set
  `marketplace.availability.dry_run` for one branch, compare the propagation read's would-push list with the
  partner portal for a week, turn it off. The adapter registers itself; nothing else changes.

## 2. How a payment provider or a fiscal terminal writes a fractional quantity (row 4.2c, ADR 0137)

- **Fact needed.** Whether Click (`Amount`, unsigned integer), Payme (`count`, a number) and the OFD protocol
  of a fiscal terminal take a scaled integer (for example thousandths) or a decimal. Owner: finance and
  integration discovery.
- **Built against.** Nothing in this wave. The receipt line that carries a fractional quantity is wave w11's;
  this wave's amendment dialog sends the decimal the platform already accepts, and the platform refuses an
  off-step quantity (`DecimalAmendmentHttpTests`).
- **Connect on the day.** One constant and a test where the fiscal builder serialises a quantity
  (`ClickFiscalAdapter`, `PaymeReceiptDetail`, and the terminal adapter when one exists).

## 3. A till that can revise an exported order (row 4.2c, ADR 0137, ADR 0011)

- **Fact needed.** A vendor whose API changes an exported order's total or a line's quantity. For Clopos the
  answer to Q10 in `docs/providers/clopos-api.md` (`OrderProduct.count` is an integer and `PUT /orders/{id}`
  accepts only `IGNORE`); for iiko and R-Keeper, anything at all (nothing in this repository says what either
  exposes). Owner: integration discovery.
- **Built against.** `PosCapability.ORDER_REVISION`, `PosAdapter.reviseExportedOrder` (default: not
  supported), `FakePosAdapter` (supported, deduplicating on the key, with a refusal, an unreachable till and a
  lost reply injectable), and the ceiling rows in V0665: Clopos recorded `UNSUPPORTED` with its reason, the fake
  `SUPPORTED`, every other vendor unassessed, so the trigger refuses to enable it on a real binding.
- **Until then.** A weight that moves the total of an order the till already holds opens a notice (banner on the
  order and at the pass, one alert to the branch chat); a person changes the till and says so.
- **Connect on the day.** Implement `reviseExportedOrder` in the vendor's adapter; add the vendor's
  `ORDER_REVISION` row to `integration.pos_provider_capabilities` with its evidence in a new migration; enable
  the capability on the binding. The "Send the new total to the till" button appears by itself.

## 4. The wording of the КБЖУ accuracy line, and whether one is required (row 4.2c, ADR 0137)

- **Fact needed.** Legal's decision and exact ru, uz and en wording. Owner: legal and product.
- **Built against.** `catalog.nutrition_accuracy_line_enabled`, off by default and not tenant-visible, a menu
  field the storefronts read, and a provisional neutral sentence in `physical.nutritionAccuracy` of both
  storefronts and `catalog.editor.physical.accuracy.*` of the console. The sentence is a placeholder and is
  never shown while the switch is off.
- **Connect on the day.** Replace the three strings in each of the two storefronts' `i18n/*.json` and the
  console's `catalog.*.ts`, then have a platform administrator write the key at the tenant or brand.

## 5. The Committee's official ИКПУ/MXIK file (row 4.2e, ADR 0173)

- **Fact needed.** The file itself (the portal `tasnif.soliq.uz` offers an Excel download), its header row and
  its licence. Owner: the platform owner or finance.
- **Built against.** The importer, its dry run, held-run and withdrawal rules and the search, all proven against
  the invented fixture `src/test/resources/mxik/tasnif-fixture.csv`.
- **Connect on the day.** `docs/runbooks/mxik-reference-import.md`: put the real headers in `MxikMappingProfile`
  (an unknown header stops the import by design), add a trimmed copy of the file beside the fixture, run the
  pre-production load, have finance check fifty codes, then load production.

## 6. Per-marketplace content rules (row 4.6a, ADR 0138)

- **Fact needed.** Each partner's published requirements: image resolution, description length, category depth,
  price parity, combo transport format for Yandex Eda, Uzum Tezkor, Wolt and Express24. Owner: product and
  partnerships.
- **Built against.** The ruleset plug point (`MarketplaceRuleset`), the per-binding ruleset choice
  (`PUT .../marketplace-ruleset`) and the preview that runs it; the production registry is empty by decision
  (ADR 0138: «building the machinery without the actual rules is deliberate»), so a binding can name none.
- **Connect on the day.** One class per marketplace implementing `MarketplaceRuleset`, with `MarketplaceRulesetTests`
  beside it; the console's ruleset picker lists it.

## 7. An outbound menu push to a push-only partner (row 4.6a, ADR 0040)

- **Fact needed.** Which partner cannot pull and offers a menu API, with credentials and a sandbox. Owner:
  product and partnerships.
- **Built against.** The pull-first contract: `GET /api/v1/partner/.../menu` serves the channel's published
  menu in the platform's identifiers plus the partner's own, with an `ETag`, and `CatalogDraftChanged` tells a
  consumer the menu moved.
- **Connect on the day.** An adapter declaring `marketplace.menu.push` reading the same projection, with the
  vendor's note under `docs/providers/`.
