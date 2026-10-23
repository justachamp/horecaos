# External connections: the facts batch 21 built against a stand-in

Batch 21 built fourteen waves. Every one of them stopped at the same place: the point where the next step is
a key, an account, a contract, a lawyer's answer, a device or a person's sign-off that exists outside this
repository. This directory holds one file per wave, written by the wave that stopped there. Each file is a
draft until somebody has done what it says once and written the date at its head (ADR 0023): none of them
has been executed.

Every section of every file answers the same questions, in the order a person at a keyboard needs them:

1. **What exists.** What the code does today, and what it was built against (a fake, a recording double, a
   switch left dark, a fixture tenant).
2. **Where the value goes.** The configuration key, environment value, secret reference (ADR 0028), table
   row or console screen the answer is put into. Never a code change.
3. **How to prove the connection.** The step that shows the real thing works.
4. **How to go back.** What undoes it. None of these is a point of no return.

Two rules hold across the directory. A row is graded on what an operator can do today, and an unconnected
outside system is stated as unconnected, never reported as working (ADR 0011). A secret is a reference
stored with `bao kv put`, and never a value pasted into a chat, a ticket or a file (ADR 0028).

## The fourteen waves

| Wave | File | What it waits on | Records |
|---|---|---|---|
| w1 board shell | [w1-board-shell.md](w1-board-shell.md) | The order board's new columns, the plan lock and the reprint key: nothing external is required to run them; the file lists what each needs before a pilot trusts it. | [ADR 0160](../../adr/partial/0160-user-scoped-personalization.md), [ADR 0144](../../adr/partial/0144-the-order-board-across-a-brands-branches-and-the-toggles-it-reads.md) |
| w2 order entry policy | [w2-order-entry-policy.md](w2-order-entry-policy.md) | The map provider behind the New order pin (Yandex keys and the licence payment from Uzbekistan), the late-order threshold's stored values in pre-production, finance's answer on settlement evidence for a manual aggregator entry. | [ADR 0177](../../adr/built/0177-address-capture-for-phone-orders-and-the-order-policy-readers.md) |
| w3 delivery maps | [w3-delivery-maps.md](w3-delivery-maps.md) | The map keys, the 200-address golden set and the bake-off, the pickup and handover gate's pilot branch, a courier's first sign-in by text message (no real SMS adapter), the deliveries-console module. | [ADR 0178](../../adr/partial/0178-the-pickup-and-handover-are-soft-by-default-a-courier-is-not-rated-and-a-rule-needs-a-reader.md), [ADR 0145](../../adr/partial/0145-map-and-geocoding-provider.md) |
| w4 courier client | [w4-courier-client.md](w4-courier-client.md) | The courier transparency notice's wording (the tenant's and counsel's), the production Keycloak client for the PWA, a courier's first password, DNS, a real handset. | [ADR 0179](../../adr/partial/0179-the-courier-client.md), [ADR 0045](../../adr/partial/0045-realtime-operational-push-and-field-telemetry.md) |
| w5 catalog, stops, fiscal | [w5-catalog-stops-fiscal.md](w5-catalog-stops-fiscal.md) | A real aggregator that takes an availability write, the provider's encoding of a fractional quantity, a POS vendor that can revise an exported order, the nutrition accuracy line's wording, the Committee's IKPU/MXIK file. | [ADR 0180](../../adr/partial/0180-stale-marketplace-channels-weighed-baskets-and-the-marketplace-menu-pull.md), [ADR 0173](../../adr/partial/0173-the-ikpu-mxik-reference-dataset.md) |
| w6 promotions, reviews, approvals | [w6-promotions-reviews-approvals.md](w6-promotions-reviews-approvals.md) | Finance and counsel on the order-level service charge, the wording of the loyalty-expiry message, who signs a discretionary discount. | [ADR 0181](../../adr/built/0181-compensation-from-a-case-promotion-targeting-requote-and-discretionary-discounts.md) |
| w7 campaign channels | [w7-campaign-channels.md](w7-campaign-channels.md) | A mail provider and the sending domain's records, counsel on email addresses leaving the country, the VAPID key pair for Web Push, the courier broadcast's gateway account and wording. | [ADR 0182](../../adr/partial/0182-campaign-channels-email-push-couriers-and-delivery-statistics.md) |
| w8 acquisition, merchandising | [w8-acquisition-merchandising.md](w8-acquisition-merchandising.md) | The BotFather round trip for a tenant's Mini App, counsel on attribution and the page-load count, finance and counsel on the 400-day retention. | [ADR 0183](../../adr/built/0183-acquisition-links-the-tenants-mini-app-and-storefront-merchandising.md) |
| w9 notifications, settings | [w9-notifications-settings.md](w9-notifications-settings.md) | A native speaker's review of the platform's default wording, the SMS gateway's approval of it, a notice to couriers, a Georgian typeface. | [ADR 0184](../../adr/partial/0184-order-messages-platform-wording-milestones-and-personal-values.md), [ADR 0149](../../adr/partial/0149-languages-beyond-ru-uz-latn-and-en.md) |
| w10 privacy | [w10-privacy.md](w10-privacy.md) | Legal's retention periods, the lawful basis per consent purpose, the wording of the data-subject export's cover note. Until they are confirmed a production start refuses. | [ADR 0029](../../adr/partial/0029-pii-protection-envelope-encryption-and-key-rotation.md), [ADR 0109](../../adr/partial/0109-wave-w03-privacy-self-service-access-check-and-approvals.md) |
| w11 fiscal evidence | [w11-fiscal-evidence.md](w11-fiscal-evidence.md) | A vendor adapter for a fiscal terminal, which Payme surface Payme reads, Click's quantity scale, the tax authority's answer on a correction. | [ADR 0166](../../adr/partial/0166-the-fiscal-agent-per-settlement-path-and-the-fiscal-evidence-surface.md) |
| w12 money | [w12-money.md](w12-money.md) | HorecaOS's Click merchant account and service id, Click's written confirmation of off-server tokenisation, Didox and Faktura.uz accounts, finance's line codes and tax treatment of prepaid money. | [ADR 0185](../../adr/built/0185-a-redemption-is-taken-at-checkout-and-order-entry-never-recorded-after.md), [ADR 0095](../../adr/partial/0095-a-tenants-wallet-keeps-paid-money-and-bonus-money-apart.md) |
| w13 staff access | [w13-staff-access.md](w13-staff-access.md) | A POS vendor whose create-order API carries the operator, the shape of Clopos's staff list, mail delivery for the password reset, the staff sign-in client on the pre-production realm. | [ADR 0186](../../adr/built/0186-tenant-defined-jobs-composed-from-the-capability-catalogue.md), [ADR 0176](../../adr/partial/0176-staff-sessions-sign-in-history-and-active-sessions.md) |
| w14 channel setup, POS loyalty | [w14-channel-setup-pos-loyalty.md](w14-channel-setup-pos-loyalty.md) | A paired kiosk unit, a POS vendor that returns a loyalty balance, the BotFather identity, a ceiling row for the till. | [ADR 0187](../../adr/built/0187-kiosk-idle-media-and-the-channel-forms-the-console-does-not-owe.md), [ADR 0171](../../adr/partial/0171-who-owns-a-loyalty-balance-when-the-till-also-keeps-one.md) |

The "what it waits on" column is a summary; the file is the record. Read the file before acting on the line.

## Where a fact from this directory lands

- A **configuration key** is set through Settings in the console where a screen exists, and through the API
  alone where it does not (`ConfigurationKeyConsoleCoverageTests` lists the keys still owed a screen, each
  against the gap-map row that owes it).
- An **environment value** is added to `/etc/horecaos/production.env` on the host and read at the next start; the
  keys the batch introduced are in `deploy/env.template`.
- A **secret** is a reference (ADR 0028). Write the value to OpenBao, put the reference where the file says.
- A **database row** (a provider environment, a capability ceiling, a gateway account) is written by an operations
  runbook or by a platform administrator, never by a migration of production data.

When one of these facts is connected, put the date in the file's `Last executed` line, and move what the file
described as a stand-in out of its "what exists" section. A file whose every section has been executed is a
runbook; until then it is a draft.
