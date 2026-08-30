# Minimum viable cutover

## Why this document exists

The ADR set describes a complete destination: thirty-four decisions, three POS
providers, three delivery partners, fiscalization, SaaS metering, four
frontends, and a governed legacy retirement. That is the right destination and
it is not the first release.

Nothing in the ADRs states scope, effort, or team size, so the plan reads as
"all thirty-four or nothing", which is the most common way a migration of this
shape stalls. This document names the smallest slice that can take a real order
from a real customer, and marks which ADR requirements block that slice versus
which can follow.

It is a planning document, not a decision record. It does not override any ADR;
it selects a subset of them.

## The slice

**One tenant, one brand, one location, taking real paid orders on the new
platform, while everything else stays on the legacy system.**

Deliberate constraints for the first slice:

```text
tenants          one, internal or a friendly pilot restaurant
brands           one
locations        one
catalog          authored in Qoida by hand; no POS integration
inventory        BINARY or UNTRACKED only; no quantity tracking
pricing          price book and taxes; no promotions or coupons
payments         one provider, one merchant account
fulfillment      pickup, or delivery with MANUAL courier assignment
acceptance       RESTAURANT_APPROVAL through Operations only
notifications    order confirmation and rejection on one channel
frontends        storefront and operations only
customers        phone-authenticated, single brand, no merges
```

Everything excluded here is excluded because it can be added without
re-architecting what the slice builds, which is the actual test of a good first
scope.

## What blocks the slice

These must be genuinely implemented, not merely decided.

| ADR | Blocking subset | Not blocking |
|---|---|---|
| 0025 Authorization | Capability registry, grants, scope covering, `require`, session context | Tenant-defined custom roles, time-bounded support grants |
| 0031 API conventions | Problem Details, idempotency, expected-version, cursor pagination | OpenAPI diff gate can follow, but should not lag far |
| 0027 Audit | Transactional recorder, immutable store | Approval workflow, unless refunds are in the slice |
| 0028 Secrets | Manager provisioned, `SecretResolver`, one rotation rehearsed | Dual-secret windows, per-category automation |
| 0029 PII protection | Classification, envelope encryption, lookup hashing, no PII in events or logs | Crypto-shredding, full privacy-operations suite |
| 0030 Configuration | Typed keys, resolution, policy pinning for acceptance policy | Field-level merge, control-plane editing UI |
| 0032 Event governance | Schemas and catalogue for events actually published | Runtime registry |
| 0034 Hosting | Production environment on the chosen platform, managed PostgreSQL HA with a proven restore | Multi-region, full DR exercise programme |
| 0005 Inbox | Full ADR; the first business consumer depends on it | — |
| 0006 Retry and replay | Read-only failure views plus retry | Resolve with maker-checker, diagnostic DLT |
| 0026 Provider installations | Installations, bindings, secret references, one PAYMENT category | POS, delivery, geocoding categories |
| 0007 Camel | Route foundation, fake provider suite, one real payment route | Circuit-breaker tuning across many providers |
| 0008 Onboarding | Enough to provision the pilot tenant reproducibly; steps for unbuilt capabilities stay visibly blocked | Full twelve-step catalogue, self-service onboarding |
| 0009 Keycloak provisioning | Organization ensure and readback, owner link, scoped roles | Invitations at scale, automated drift correction |
| 0010 Media | Upload, validate, serve, one derivative size | Legacy migration tooling, malware scanning if classification allows |
| 0015 Customers | Account, principal link, brand profile, addresses, consent | Guest claim, merges, identity-policy migration |
| 0016 Catalog | Authoring, validation, publication, location offerings | Translations beyond one locale, packaging, merchandising |
| 0017 Inventory | Binary availability and the reservation path | Quantity tracking, ledger reconciliation, POS observations |
| 0018 Pricing | Price books, tax, quote lifecycle, context hash | Promotions, coupons, benefit grants |
| 0019 Ordering | Cart, checkout, order snapshots, state machine, approval, payment and inventory process managers | POS export, delivery, scheduled orders, cancellation after confirmation |
| 0013 Payments | Intent, attempt, transaction, webhook verification, one provider | Refunds beyond manual, recovery cases, fiscalization if legally deferrable |
| 0020 Notifications | Confirmation and rejection on one channel, consent gate | Multi-channel fallback, marketing, template approval workflow |
| 0022 Frontend | Storefront and operations, session context, PKCE | Control-plane and courier applications |
| 0024 Migration | Scope and ownership registry for this one location; single-writer gate | Full wave programme, bulk backfill |

## What is explicitly deferred

Deferred means "not in the first slice", not "not needed". Each remains an
accepted decision with an unchanged ADR.

- **POS integration** (0011, 0012). The pilot restaurant enters its menu in
  Qoida and works orders in the Operations application. This removes three
  provider contract discoveries from the critical path.
- **External delivery partners** (0014). Manual assignment or pickup only. Both
  Yandex and Noor are now documented well enough to integrate, but each adds a
  provider contract suite and an uncertainty-reconciliation path; keeping them
  out of the first slice keeps checkout simple. Noor is the easier first
  integration: webhook status push, one pickup point, and a single-step create.
- **Refunds and service recovery** (0013 recovery half). Handled manually in the
  provider console with back-recording, under a documented runbook, until the
  approval model and refund reservation are built.
- **Direct fiscal integration** — not deferred so much as absent: Click and
  Payme fiscalize as part of accepting payment, so the slice only needs to
  capture and store the fiscal evidence they return. Confirm what each returns
  during payment integration.
- **Promotions, coupons, benefits** (0018 partial).
- **Quantity inventory** (0017 partial).
- **SaaS plans, entitlements, metering** (0021). One pilot tenant does not need
  commercial enforcement; metering can run in `METER_ONLY` if cheap.
- **Control-plane and courier applications** (0022 partial). Platform staff can
  use APIs and the operations application.
- **Legacy retirement** (0024 partial). The legacy system keeps serving every
  other restaurant throughout.

## Rules the slice must not break

Scope reduction is legitimate; correctness reduction is not. These hold even for
one pilot location, because retrofitting them is what makes migrations fail:

1. Every tenant-owned row carries `tenant_id`, with composite ancestry
   constraints and negative isolation tests.
2. Exactly one authoritative writer per capability, recorded in the ADR 0024
   scope registry, for the pilot location as much as for the platform.
3. No credential outside ADR 0028; no personal data outside ADR 0029; no
   personal data in any event, log, trace, or metric.
4. Every mutation is idempotent, version-checked, capability-authorized, and
   audited.
5. Money is integer minor units with a currency, everywhere.
6. Orders are immutable once created. Corrections are new facts.
7. Every external effect is reconcilable: uncertain outcomes are queried before
   retry, never blindly repeated.

A shortcut on any of these is not a smaller first release; it is a defect that
the rest of the roadmap will be built on top of.

## Suggested staging

| Stage | Outcome | Contains |
|---|---|---|
| 0 | The platform can be operated safely | Track A foundations, 0034 environment, 0005, 0006 |
| 1 | A tenant exists reproducibly | 0026, 0007, 0008, 0009, 0022 shell |
| 2 | A menu can be published and seen | 0010, 0016, 0015, 0022 storefront |
| 3 | A cart can be priced and reserved | 0017 binary, 0018 core |
| 4 | An order can be taken, paid, and approved | 0013 payments core, 0019, 0020 minimal, 0022 operations |
| 5 | The pilot location runs on the new platform | 0024 scope registry, single-writer gate, reconciliation for one location |

Each stage should end with something demonstrable to a restaurant, not only a
merged branch. If a stage cannot be demonstrated, its scope was wrong.

## After the slice

Expansion order follows the demand that appears once the pilot runs, but the
likely sequence is: quantity inventory and promotions, then POS for restaurants
that need it, then external delivery, then refunds and recovery, then plans and
entitlements as more tenants arrive, then the remaining journeys and legacy
retirement under ADR 0024.

Revisit this document at the end of each stage. If it has not changed after a
stage, it probably was not consulted.
