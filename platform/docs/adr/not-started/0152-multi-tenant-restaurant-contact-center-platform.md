# ADR 0152: A multi-tenant restaurant contact centre on Wazo — our platform owns the business domain, Wazo owns communications infrastructure

- Decision status: Proposed — written by the platform owner (Ayubkhon Abbosov) and registered 2026-10-03; the owner decides
- Implementation status: Not started — nothing this record decides exists: no Wazo adapter, client
  or deployment, no queues, IVR, voicemail, business-hours routing, call recording or WebRTC
  softphone, and no tenant provisioning of telephony (numbers, queues, agents). What exists is
  ADR 0064's provider-neutral voice core, which this record relates to (whether this record's
  service reuses that core is the service-runtime open input) and which contains nothing
  Wazo-specific, verified against the tree on 2026-10-03: the `VOICE` provider category (`V0145`)
  with installations and secret references (ADR 0026/0028); the normalized call-event ledger
  `voice.call_events` (`V0146`); event ingestion with webhook de-duplication
  (`integration.voice_processed_events`, `V0147`) and the outbox, publishing
  `VoiceCallEventRecorded` v1 on `voice.events`; two adapter kinds, a hosted SIP/PBX webhook
  (`HostedPbxWebhookController`), proven by hand-constructed normalized payloads, and an Asterisk
  AMI client (`AsteriskAmiSocketClient`), proven against a fake PBX (`FakeAsteriskAmiServer`),
  declared by `VoiceProviderCapabilityCatalog` with the one capability `INGEST_EVENTS_PUSH` and
  never exercised against a live provider account; operator presence (`voice.operator_presence`:
  ONLINE, PAUSED, WRAP_UP, OFFLINE); the screen-pop (`ScreenPopController`) with a masked caller
  display (`CallerNumberDisplay`) and an audited reveal of an unknown caller's number; call facts in
  `reporting.fact_call_hour` (`V0149`, ADR 0043); and the call-centre screen
  `frontend/operations/src/app/features/orders/call-centre-page.ts`. No adapter drives a live PBX's
  agent login from presence (`CONSUME_PRESENCE` in ADR 0064's words).
- Date proposed: 2026-10-02
- Date decided: —
- Deciders: Ayubkhon Abbosov (platform owner) — author and decider
- Depends on: [ADR 0001](../built/0001-platform-foundation.md),
  [ADR 0002](../partial/0002-saas-domain-model.md),
  [ADR 0003](../built/0003-keycloak-tenant-authorization.md),
  [ADR 0004](../built/0004-sql-outbox-and-kafka-delivery.md),
  [ADR 0007](../partial/0007-camel-route-foundation-and-provider-contract-testing.md),
  [ADR 0008](../built/0008-resumable-tenant-onboarding-workflow.md),
  [ADR 0009](../partial/0009-keycloak-organization-provisioning-and-membership-reconciliation.md),
  [ADR 0023](../partial/0023-production-operating-model-observability-security-and-recovery.md),
  [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md),
  [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md),
  [ADR 0027](../built/0027-audit-evidence-and-approval-model.md),
  [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md),
  [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md),
  [ADR 0030](../built/0030-configuration-and-policy-resolution.md),
  [ADR 0031](../built/0031-http-api-conventions.md),
  [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md),
  [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md),
  [ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md) (Superseded) and [ADR 0073](../not-started/0073-production-runs-on-a-rented-vm-in-country.md) (Proposed successor),
  [ADR 0035](../partial/0035-angular-frontend-platform-and-design-system-adoption.md),
  [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md),
  [ADR 0043](../partial/0043-reporting-analytics-and-the-metric-layer.md),
  [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md),
  [ADR 0111](../not-started/0111-customer-card-and-communication-history.md),
  [ADR 0135](../partial/0135-object-storage-runtime-rustfs-replaces-minio.md),
  [ADR 0139](../not-started/0139-staff-identity-the-staff-person-record.md)
- Supersedes / Superseded by: — (does not edit ADR 0064; it would amend 0064's audio decision and bear on three of its open inputs if accepted — see Open inputs)
- Open inputs:
  - The service runtime. Whether the contact-centre service is a separate service with its own
    stack or a module of the platform, and, if a module, whether it reuses ADR 0064's voice core.
    The owner's text names Python + FastAPI for the backend, React / Next.js for the agent UI and
    Redis for cache and ephemeral state (§23); the platform is Java 25 on Spring Boot with Spring
    Modulith ([ADR 0001](../built/0001-platform-foundation.md)) with Angular frontends ([ADR 0035](../partial/0035-angular-frontend-platform-and-design-system-adoption.md)), and its shared runtime state is governed
    by [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md), which records that a shared cache, when one is introduced, is Valkey rather than
    Redis, and that neither runs in the platform today. This record's Specification keeps the
    owner's stack as written until this is answered (platform owner).
  - Audio and the softphone. Whether this record amends ADR 0064's decision that the platform
    never carries audio, and whether that record's softphone trigger ("A provider round demands
    it") has fired. The owner's text requires browser-based agents over WebRTC (§3 goal 9, §18,
    §23), call recording (§3 goal 11) and an operated Wazo and Asterisk media plane (§12, §28);
    ADR 0064 rejected a softphone inside the operations app (platform owner).
  - Events. Whether the contact-centre service publishes its call events through the platform's
    existing outbox and topic policy from day one. The owner's text defers Kafka to a scale stage
    and has the first implementation write events to PostgreSQL, the CRM and a WebSocket to the
    agent UI (§17, §23); the platform already runs Kafka behind the SQL transactional outbox
    ([ADR 0004](../built/0004-sql-outbox-and-kafka-delivery.md)) under event-contract governance ([ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md)), and ADR 0064 already publishes
    `VoiceCallEventRecorded` on `voice.events` (platform owner).
  - Object storage for recordings. Which bucket and tenancy layout recordings use, and the legal
    posture of recording calls. ADR 0064 left the legal posture of recording open and this record
    does not answer it; the bucket and tenancy layout is new here. The platform's object store is
    RustFS behind an S3 API ([ADR 0135](../partial/0135-object-storage-runtime-rustfs-replaces-minio.md)), with a rented provider's own S3-compatible storage under
    consideration in [ADR 0073](../not-started/0073-production-runs-on-a-rented-vm-in-country.md) (Proposed); the owner's text asks for tenant isolation, encryption,
    lifecycle and retention policy on recordings (§20) (platform owner).
  - Numbering and the SIP carrier for Uzbekistan. Which carrier, and how per-restaurant numbers
    are supplied; the owner's text requires them (§1, §32 criterion 2). ADR 0064 left per-brand
    DIDs versus a shared line open (platform owner).
  - Hosting and data residency for Wazo. Where Wazo and its media run, and what residency applies
    to call media and recordings, given the colocated-server posture of [ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md) and the Sarkor
    pilot box of
    [ADR 0061](../partial/0061-production-deployment-pilot-on-owned-hardware-portable-by-construction.md).
    Both records are marked Superseded by [ADR 0073](../not-started/0073-production-runs-on-a-rented-vm-in-country.md), which is itself still Proposed (platform
    owner).
  - Tenant mapping and the agent. How the owner's tenant model (§15: Tenant, with Organization,
    Restaurant (and its Location, phone numbers, queues, IVRs, agents and routing policies) and CRM
    Configuration beneath it) maps onto the platform's tenant → brand → location ([ADR 0002](../partial/0002-saas-domain-model.md)), where
    a tenant is one Keycloak organization ([ADR 0003](../built/0003-keycloak-tenant-authorization.md), [ADR 0009](../partial/0009-keycloak-organization-provisioning-and-membership-reconciliation.md)); whether "agent" is the staff
    person of [ADR 0139](../not-started/0139-staff-identity-the-staff-person-record.md) (not started) or a separate record; and whether agents sign in through the
    platform's identity, as "Our Identity / Auth" in §22 reads (platform owner).
  - PII in events. The owner's screen-pop example (§16) shows a customer name, a phone number and
    order counts. The platform's rule ([ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md), and ADR 0064's masked display with an audited
    reveal) is that events carry identifiers only (`VoiceCallEventRecorded` carries a resolved
    customer account id and never a number) and the card resolves them through an authorized call.
    Needed from the owner: confirmation that the screen-pop example is illustrative and is not the
    event contract (platform owner).
  - What "CRM" is in this platform. The owner's text treats the CRM as the business system of
    record for customers, restaurants, orders, reservations and other business entities (§1; §26
    assigns orders and reservations to the Restaurant Platform). Whether the CRM is the
    `customers` module and the customer card of [ADR 0111](../not-started/0111-customer-card-and-communication-history.md) (Proposed, not started), or a separate
    system (platform owner).
  - Observability stack. §23 names Prometheus + Grafana + Loki; [ADR 0023](../partial/0023-production-operating-model-observability-security-and-recovery.md) rejected an aggregated
    log stack (Loki, ELK), with its own revisit trigger (logs on more than one machine, or an
    incident needing correlation that grep cannot do). Whether the contact centre adopts the §23
    stack or the platform's, and whether that trigger has fired (platform owner).
  - Authorization and endpoint surfaces. §22 says the Contact Center platform should own the
    application-level authorization model; the platform's model is the capability grants of
    [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md) on the Keycloak identity of [ADR 0003](../built/0003-keycloak-tenant-authorization.md). Whether the contact centre has its own
    authorization model or uses the capability model, and whether the §14 endpoints (`POST
    /tenants/{tenantId}/agents` and the others) follow [ADR 0031](../built/0031-http-api-conventions.md)'s surfaces (`/api/v1/operations/**`
    and the others) (platform owner).
  - PII at rest in Wazo. §26 makes Wazo the system of record for call state and, with the
    interaction model, for the CDR, while `V0146` (`voice.call_events`) stores the caller number
    envelope-encrypted beside a masked form ([ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md)). What protection applies to Wazo's CDR, its
    call-state store and the recordings (platform owner).
  - Business hours and routing policies. §13 gives business hours and routing policies to "our
    platform"; the platform already has scoped policy resolution ([ADR 0030](../built/0030-configuration-and-policy-resolution.md)) and service-hour
    schedules bound per fulfilment mode ([ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md)). Whether the contact centre reuses them or holds
    its own. The adr-discipline skill cites `V0012`, which exists because `ordering` built its own
    policy table before [ADR 0030](../built/0030-configuration-and-policy-resolution.md) landed, as the reason to ask (platform owner).
  - The call interaction record. §20 and §26 define one (call id, tenant, customer, agent, start
    and end time, direction, disposition, recording reference); `voice.call_events` (`V0146`)
    already holds `provider_call_id`, tenant, brand and location, direction,
    `operator_principal_id`, `duration_seconds` and `resolved_customer_account_id`. Whether the new
    record is that table or a second ledger (platform owner).

## Context

The sub-headings below keep the numbering of the owner's text so each part can be traced to it.

### Scope and scale

The owner's header block, apart from the status and date, which are above.

- **Decision Type:** Solution / Platform Architecture
- **Scope:** Restaurant Contact Center as a Service (CCaaS)
- **Initial Scale:** 3 restaurants
- **Target Scale:** 100 restaurants → thousands of restaurants

### 1. Context

We are building a Contact Center service for restaurants that will initially serve approximately three restaurants and progressively scale to hundreds and eventually thousands of restaurant tenants.

The Contact Center will provide capabilities such as:

- inbound and outbound voice calls;
- restaurant-specific phone numbers;
- IVR;
- call queues;
- agent management;
- call routing;
- business-hours routing;
- call recording;
- call history and CDR;
- agent status/presence;
- customer identification;
- CRM screen-pop;
- call journaling;
- customer interaction history;
- eventually AI voicebots and automated call handling.

The Contact Center must be tightly integrated with our existing CRM and restaurant platform.

The CRM remains the **business system of record** for customers, restaurants, orders, reservations and other business entities.

The telephony platform should provide communications capabilities without becoming the core business domain.

The platform must support a progression from:

```text
3 restaurants
     ↓
100 restaurants
     ↓
1,000+
     ↓
Thousands of restaurants
```

without requiring a fundamental architectural redesign.

### 2. Problem Statement

A traditional PBX deployment is insufficient for the long-term product vision.

The requirement is not simply:

> "Deploy a PBX for restaurants."

The requirement is:

> "Build a multi-tenant communications platform that exposes Contact Center capabilities as part of our restaurant technology ecosystem."

This means the architecture must support:

- automated tenant provisioning;
- tenant isolation;
- API-driven configuration;
- CRM integration;
- event-driven call processing;
- custom agent experiences;
- centralized observability;
- automated onboarding;
- future billing;
- future AI integrations;
- horizontal scaling;
- infrastructure automation;
- replacement of the underlying telephony platform if required.

### 3. Goals

The architecture must:

1. Support multiple independent restaurant tenants.
2. Provide strong logical isolation between tenants.
3. Allow restaurants to be provisioned through APIs.
4. Integrate directly with our CRM.
5. Support inbound and outbound calls.
6. Support queues, IVRs and routing.
7. Provide call events to business systems.
8. Provide CDR and call-history data.
9. Support browser-based agents through WebRTC.
10. Allow development of a custom agent interface.
11. Support call recording.
12. Support future AI voicebot integration.
13. Allow scaling from 3 to thousands of restaurants.
14. Avoid coupling the CRM directly to the telephony vendor.
15. Allow the underlying telephony platform to be replaced in the future.
16. Keep the initial deployment operationally simple.

### 4. Non-Goals

The first version will **not** attempt to build:

- a new SIP/VoIP protocol stack;
- a new PBX engine;
- a proprietary RTP/media engine;
- a new CRM;
- a complete analytics platform;
- a full billing platform;
- a Kubernetes-based platform from day one;
- a Kafka-based event architecture before event volume justifies it;
- a complete AI contact center.

These capabilities may be introduced incrementally.

### 5. Decision Drivers

The primary architectural decision drivers are:

| Driver | Importance |
|---|---:|
| Multi-tenancy | Critical |
| API-first provisioning | Critical |
| CRM integration | Critical |
| Custom business logic | Critical |
| Scalability | Critical |
| Tenant isolation | Critical |
| Event-driven integration | High |
| WebRTC | High |
| Call Center capabilities | High |
| AI integration capability | High |
| White-label capability | High |
| Operational simplicity | High |
| Vendor independence | High |
| Time to MVP | Medium |
| Licensing cost | Medium |
| Native end-user applications | Medium |

### Registration notes

This record was written by the platform owner, under the title "Multi-Tenant Restaurant Contact
Center Platform", and registered on 2026-10-03 as received. The sections below are the owner's text
re-homed under this repository's ADR template headings: the owner's section numbers are kept in
the sub-headings, the wording is the owner's, and no decision, judgement or matrix rating has been
changed. The owner's §24 phases are under Rollout and rollback, the §29 risks table is under
Accepted trade-offs, and the §33 list of future records is under References as the decisions this
record leaves to later records. The owner's §33 list numbered the future records ADR-002 to
ADR-014; the numbers are dropped because this repository allocates numbers when a record is
written, and "HA" and "voicebot/media" are spelled out.

The following are not the owner's: the Implementation status, Depends on, Deciders, Date decided
and Open inputs lines; the section lead-ins; the added alternatives rows, the alternatives table's
first two columns and its lead-in, and its "Revisit when" column; the accepted-trade-offs
classification sentence and the "Registered at filing" list; the Rollback paragraph; the
repository items, test items and registration annotations in the implementation checklist; the
expected-proof list under Exit criteria; the heading "Decisions this record leaves to later
records"; and the related-record and documentation links under References.

This record relates to [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md) and does not edit it; whether this record's service reuses that
voice core is the service-runtime open input. ADR 0064's open inputs were: whether call audio is
recorded and the legal posture if so; numbering (per-brand DIDs versus a shared line); and the
presence model's interaction with shift scheduling. Its first-adapter input was resolved
2026-09-05 as two adapter kinds with no live vendor named. This record proposes Wazo as a third
adapter kind and the first live vendor; it requires recording under a configured policy (§3, §31,
§32) and a phone number per restaurant (§1, §32 criterion 2); it leaves the legal posture, the
carrier and numbering strategy and recording storage and retention to later records (§33); and the
presence-versus-shift-scheduling input of ADR 0064 is unaffected. The owner's text also requires
browser-based agents that carry audio over WebRTC (§3 goal 9, §18, §23) and an operated Wazo and
Asterisk media plane (§12, §28), while ADR 0064 decided that the platform never carries audio and
rejected a softphone inside the operations app (revisit when "A provider round demands it"). The
owner's text decides this (§3 goal 9, §23); it departs from ADR 0064's alternatives-table row,
which this record does not edit, and the open input above records it.

The divergences between the stack and sequencing stated in the owner's text and platform decisions
already in force are listed in the Open inputs and in the Specification; they are recorded, not
resolved, here.

This record sits outside ADR 0055's launch scope and docs/minimum-viable-cutover.md, neither of
which names telephony, and it is not placed in the roadmap in docs/adr/README.md until it is
accepted.

## Decision

### 12. Decision

We will build the Restaurant Contact Center as a **multi-tenant communications platform**, using **Wazo Platform as the underlying communications platform**.

The architecture will follow this principle:

> **Our platform owns the business domain. Wazo owns communications infrastructure.**

Wazo will not become the source of truth for restaurant business data.

The architecture will be:

```text
                         OUR PLATFORM
                              │
             ┌────────────────┴────────────────┐
             │                                 │
       Restaurant CRM                  Contact Center
             │                         Domain Service
             │                                 │
             └────────────────┬────────────────┘
                              │
                         CC Integration
                              │
                        Wazo Platform
                              │
                           Asterisk
                              │
                         SIP / RTP
                              │
                       SIP Provider
                              │
                             PSTN
```

### 34. Final Decision Summary

We will build a **multi-tenant Restaurant Contact Center Platform** rather than a traditional PBX service.

The architecture will use:

```text
CRM / Restaurant Platform
          │
          ▼
Contact Center Domain
          │
          ▼
Wazo Adapter
          │
          ▼
Wazo Platform
          │
          ▼
Asterisk
          │
          ▼
SIP Provider / PSTN
```

**Wazo is selected as the communications platform because it provides a substantially more programmable platform boundary than a conventional PBX while avoiding the need to build the entire telephony stack ourselves.** Its current platform exposes APIs/SDKs for call control, configuration, agents, CDR, provisioning, webhooks, WebSockets and plugins.

**Asterisk remains the underlying communications engine**, providing the low-level programmable telephony capabilities required for advanced integrations and future AI/media workloads.

**FreePBX is not selected** because the primary requirement is a programmable multi-tenant SaaS platform rather than PBX administration.

**3CX is not selected** because, although its current product supports multi-company deployments, configuration APIs and CRM integration, the strategic requirement is to build our own communications product and domain rather than primarily operate a vendor-defined business communications product.

The architecture must therefore preserve the following boundary:

> **Our CRM owns the customer and restaurant business domain. Our Contact Center platform owns communication business logic. Wazo owns communications infrastructure. Asterisk provides the underlying telephony engine.**

This architecture supports the intended evolution from **3 restaurants → 100 restaurants → thousands of restaurants** without forcing the business domain to become coupled to the underlying telephony implementation.

## Alternatives considered

The table summarises each rejected option and what would make it win; the owner's option sections
(§6–§11) follow it in full.

| Option | Why not chosen | Revisit when |
|---|---|---|
| Stay on ADR 0064's two existing adapters behind a hosted PBX and operate no phone system (the status quo) | The owner's §2: a traditional PBX deployment is insufficient for the product vision, which needs queues, IVR, recording, provisioning APIs and a custom agent experience. | A hosted PBX supplies the §31 MVP telephony scope (queues, IVR, recording, business hours) through APIs without an operated media plane. |
| Asterisk as the primary platform abstraction (§7) | A strong engine, but the majority of the Contact Center platform would be built and operated by us: tenant management, provisioning, administration, agent management, queues, CRM integration, the WebRTC application, event processing, reporting, operational tooling, tenant isolation and business-level routing. Asterisk remains the underlying engine. | A requirement in the decision drivers (§5) turns out not to be reachable through Wazo's APIs. |
| FreePBX (§8) | Its architecture is oriented toward PBX administration, not toward building a custom communications SaaS; automated multi-tenant provisioning, CRM integration, a custom agent experience, event-driven business logic, the SaaS lifecycle, tenant-level automation and product-level APIs would still need significant custom development. | The requirement changes from a programmable multi-tenant CCaaS platform to administering conventional PBXs. |
| 3CX (§10) | A commercial proprietary platform: product behaviour is constrained by the vendor's product model, it is less attractive as the foundation of a deeply customized communications product, and its documentation notes restrictions around CRM integration when switching to Multi-Company mode. The strategic requirement is to build our own communications product and domain rather than primarily operate a vendor-defined one. | The strategic requirement changes to delivering a managed business PBX / Contact Center service quickly, which the text calls viable (§10). |
| A Kafka-based event architecture from day one (§4, §17, §23) | Kafka is a scale-stage decision, not an MVP requirement; the first deployment should be deliberately boring. | Event volume or use cases justify a broker (§29); Kafka appears in the owner's Phase 3 architecture (§24). |
| Kubernetes, a service mesh and a large microservice estate from day one (§4, §23) | The first three-restaurant deployment should not begin with them; the MVP deploys with Docker. | Phase 3 (§24), when additional infrastructure is justified by measurable requirements. |
| Wazo's own agent interface, authentication and ACL model as the product surface (§18, §22) | It would make the business dependent on the UI of the underlying telephony vendor and make Wazo's authorization model the public security model of the product. | — (§18 and §22 state why the product owns the agent interface and the authorization model) |

### 6. Options Considered

The following platforms were evaluated:

1. **Asterisk**
2. **FreePBX**
3. **Wazo Platform**
4. **3CX**

### 7. Option A — Asterisk

Asterisk is a communications engine/framework rather than a complete Contact Center SaaS platform.

Its ARI provides REST-based control over communication primitives such as channels, bridges, endpoints and media, with asynchronous events delivered through WebSockets. This makes Asterisk highly suitable for building custom communications applications.

#### Advantages

- Open source.
- Very mature SIP/telephony ecosystem.
- Extremely flexible.
- Excellent developer control.
- Strong automation possibilities.
- ARI provides a programmable communications layer.
- Excellent foundation for custom AI/voice applications.
- Minimal vendor lock-in.

#### Disadvantages

The majority of the Contact Center platform would need to be built and operated by us:

- tenant management;
- provisioning;
- administration;
- agent management;
- queues;
- CRM integration;
- WebRTC application;
- event processing;
- reporting;
- operational tooling;
- tenant isolation;
- business-level routing.

Asterisk is therefore a strong **engine**, but choosing raw Asterisk means taking responsibility for building a substantial amount of platform functionality.

#### Assessment

Asterisk remains an important underlying technology but is not selected as the primary platform abstraction.

### 8. Option B — FreePBX

FreePBX provides a GUI and management layer around Asterisk.

It is well suited to deploying and administering conventional PBX systems.

#### Advantages

- Open source.
- Mature Asterisk ecosystem.
- Large community.
- Fast PBX deployment.
- Good administrative UI.
- Large module ecosystem.
- Lower engineering effort than raw Asterisk.

#### Disadvantages

Its architecture is oriented toward **PBX administration**, not toward building a custom communications SaaS.

The platform would still require significant custom development around:

- automated multi-tenant provisioning;
- CRM integration;
- custom agent experience;
- event-driven business logic;
- SaaS lifecycle;
- tenant-level automation;
- product-level APIs.

#### Assessment

FreePBX is not selected because the target product is a programmable CCaaS platform rather than a collection of administrated PBXs.

### 9. Option C — Wazo Platform

Wazo is selected as the underlying communications platform.

Wazo currently exposes a broad API/SDK surface covering authentication, application/call control, configuration, contacts, phone provisioning, webhooks, CDR, plugins, call-center agents, presence/chat and WebSockets.

Its architecture also includes Asterisk-based telephony capabilities and additional communications infrastructure.

Wazo provides APIs for authentication, users, groups, policies and access control.

#### Advantages

- API-first architecture.
- Asterisk-based telephony foundation.
- Multi-tenant capabilities.
- WebRTC support.
- WebSocket/event capabilities.
- CDR APIs.
- Call Center agent capabilities.
- Phone provisioning.
- Webhooks.
- Plugins.
- Custom application development.
- Suitable for white-label/platform scenarios.
- Less infrastructure to build than raw Asterisk.
- More architectural flexibility than a closed PBX product.

#### Disadvantages

- More complex than deploying a conventional PBX.
- Requires engineering around the Wazo APIs.
- Requires us to build our own product/domain layer.
- Wazo becomes an infrastructure dependency.
- Some Wazo-specific concepts must be abstracted from our domain model.

#### Assessment

Wazo provides the best alignment between the requirements of a programmable CCaaS platform and the need to avoid building an entire telephony platform from scratch.

### 10. Option D — 3CX

3CX provides an integrated commercial business communications platform.

3CX currently supports multi-company deployments and provides a Configuration API that can manage companies/departments, users, call handling and other configuration programmatically.

3CX also provides CRM integration mechanisms, including REST-based contact lookup, contact creation and call journaling.

#### Advantages

- Fast deployment.
- Mature business communications product.
- Good administration experience.
- Multi-company support.
- CRM integration.
- Web/mobile clients.
- Contact Center capabilities.
- Less engineering required for the initial product.

#### Disadvantages

- Commercial proprietary platform.
- Greater dependency on the vendor's product model.
- Less control over the underlying communications architecture.
- Product behavior is constrained by the 3CX platform.
- Less attractive as the foundation of a deeply customized communications product.
- CRM integration and multi-company capabilities have platform-specific constraints; for example, current 3CX documentation notes restrictions around CRM integration when switching to Multi-Company mode.

#### Assessment

3CX is a viable option for rapidly delivering a managed business PBX/Contact Center service, but Wazo provides a better architectural boundary for building our own product.

### 11. Decision Matrix

The following matrix compares the options against the architectural requirements.

| Capability | Asterisk | FreePBX | Wazo | 3CX |
|---|---|---|---|---|
| Open-source foundation | Strong | Strong | Strong | No |
| Telephony engine | Excellent | Excellent | Excellent | Integrated |
| API-first platform | Excellent | Moderate | **Excellent** | Strong |
| Multi-tenancy | Build | Limited/modular | **Strong** | Strong |
| Automated provisioning | Build | Moderate | **Strong** | Strong |
| Custom business logic | **Excellent** | Strong | **Excellent** | Moderate |
| CRM integration | Build | Good | **Strong** | Strong |
| Event-driven architecture | **Excellent** | Good | **Excellent** | Good |
| CDR/API access | Build | Good | **Strong** | Strong |
| WebRTC | Build/integrate | Integrate | **Strong** | Strong |
| Custom agent application | **Excellent** | Strong | **Excellent** | Moderate |
| AI voice integration | **Excellent** | Strong | **Excellent** | Strong |
| White-label potential | **Excellent** | Strong | **Excellent** | More constrained |
| Vendor independence | **Excellent** | Excellent | **Strong** | Lower |
| Initial deployment speed | Low | High | Medium | **High** |
| Amount of platform we must build | **Very high** | High | **Medium** | Low |
| Long-term platform flexibility | **Very high** | High | **Very high** | Medium |

## Consequences

### Positive

The owner's §27, Positive Consequences.

#### 1. We build a product rather than a PBX service

The Contact Center becomes a native component of our restaurant ecosystem.

#### 2. CRM remains the business source of truth

Telephony does not dictate the business domain model.

#### 3. Strong automation potential

Restaurant onboarding can eventually become:

```text
Create Restaurant
       ↓
Create Tenant
       ↓
Assign Number
       ↓
Create Queue
       ↓
Create IVR
       ↓
Create Agents
       ↓
Configure Routing
       ↓
Activate
```

#### 4. Custom customer experience

We can build the exact agent UI required by the restaurant business.

#### 5. AI-ready architecture

The communication layer remains accessible for future voice AI applications.

#### 6. Lower vendor lock-in

Wazo-specific functionality is isolated behind an adapter.

#### 7. Progressive scaling

We do not need to operate a hyperscale platform when we only have three restaurants.

### Negative

The owner's §28, Negative Consequences.

#### 1. We own more software

Compared with 3CX, we must build:

- product UI;
- tenant lifecycle;
- CRM integration;
- business APIs;
- reporting;
- billing;
- some operational tooling.

#### 2. Wazo becomes an infrastructure dependency

Although the architecture isolates Wazo, operating and upgrading Wazo remains our responsibility.

#### 3. Telecom expertise is required

The team must understand:

- SIP;
- RTP;
- WebRTC;
- codecs;
- NAT;
- SBC;
- call routing;
- SIP trunking;
- telephony failure modes.

#### 4. More engineering than 3CX

The initial implementation will take more engineering effort than deploying a ready-made PBX.

#### 5. Operational complexity grows with scale

At thousands of tenants, communications infrastructure becomes a significant platform engineering responsibility.

### Accepted trade-offs

The owner's §29, Risks and Mitigations, each risk with the mitigation the owner states; the registrar places the table here as the trade-offs the decision carries. Two further provisions of the owner's text bear on this: controlled single-instance failure at MVP (§25) and a manual operational fallback in Phase 1 (§24).

| Risk | Mitigation |
|---|---|
| Wazo API changes | Wazo Adapter / Anti-Corruption Layer |
| Telephony outage | SIP redundancy + HA strategy |
| Tenant data leakage | Tenant-aware authorization + isolation testing |
| Poor call quality | SIP/RTP monitoring + QoS + carrier redundancy |
| Recording storage growth | Object storage + lifecycle policies |
| Database growth | Partitioning/retention strategy |
| Event duplication | Idempotent event processing |
| Event loss | Durable event processing when scale requires it |
| Wazo becomes business-domain dependency | Strict domain boundary |
| Manual provisioning becomes bottleneck | Automate before ~100 tenants |
| Kubernetes introduced too early | Scale-stage architecture |
| Kafka introduced too early | Introduce only when event volume/use cases justify it |
| AI increases media complexity | Isolate AI media gateway/service |
| Vendor lock-in | Adapter + domain-owned APIs |

#### Registered at filing

Written at registration; not in the owner's text.

- If accepted, this record amends [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md)'s decision that the platform never carries audio.
- A second language, runtime and datastore to operate (Python, Redis, React, Loki) unless the service-runtime input resolves otherwise.
- [ADR 0073](../not-started/0073-production-runs-on-a-rented-vm-in-country.md) records that "one person operates this platform"; that constraint is set against operating Wazo, Asterisk and the new stack.

## Specification

### 13. Architectural Boundary

The most important architectural decision is the boundary between our domain and Wazo.

#### Our platform owns

- Tenant
- Restaurant
- Location
- Customer
- Agent
- Agent employment/assignment
- Contact Center configuration
- Business hours
- Routing policies
- Queue configuration as a business concept
- Phone number ownership
- CRM relationships
- Call interaction records
- Customer interaction history
- Billing
- Subscription
- Product configuration

#### Wazo owns

- SIP endpoints
- Telephony configuration
- Call control
- PBX mechanics
- Media handling
- WebRTC infrastructure
- Telephony-level queues
- Extensions
- Lines
- Devices
- CDR infrastructure
- Telephony provisioning

The Contact Center service translates between these two models.

### 14. Anti-Corruption Layer

The CRM must not directly depend on Wazo.

Instead:

```text
CRM
 │
 │
 ▼
Contact Center Domain
 │
 │
 ▼
Wazo Adapter
 │
 ▼
Wazo
```

The Wazo adapter should encapsulate all Wazo-specific APIs and terminology.

For example, our application should expose:

```http
POST /tenants/{tenantId}/agents
POST /tenants/{tenantId}/queues
POST /tenants/{tenantId}/phone-numbers
POST /tenants/{tenantId}/routing-rules
POST /tenants/{tenantId}/business-hours
```

rather than exposing Wazo-specific configuration directly to the CRM.

This gives us the ability to replace Wazo in the future without redesigning the CRM or Contact Center domain.

### 15. Tenant Model

The fundamental business abstraction will be:

```text
Tenant
 │
 ├── Organization
 │
 ├── Restaurant
 │    ├── Location
 │    ├── Phone Numbers
 │    ├── Queues
 │    ├── IVRs
 │    ├── Agents
 │    └── Routing Policies
 │
 └── CRM Configuration
```

The architecture must not assume:

```text
1 restaurant = 1 tenant
```

because a future restaurant chain may have:

```text
Tenant: Restaurant Group
 │
 ├── Tashkent
 │    ├── Location A
 │    ├── Location B
 │    └── Location C
 │
 ├── Samarkand
 │    └── Location D
 │
 └── Bukhara
      └── Location E
```

This allows the platform to support both independent restaurants and restaurant chains.

### 16. Call Flow — Incoming Call

The target flow is:

```text
Customer
   │
   │ PSTN
   ▼
SIP Provider
   │
   ▼
Wazo
   │
   │ Call event
   ▼
Contact Center Service
   │
   ├── Identify tenant
   ├── Identify phone number
   ├── Identify customer
   │
   ▼
CRM
   │
   ├── Customer
   ├── Orders
   ├── Reservations
   └── History
   │
   ▼
Agent UI
```

The agent should receive customer context before or during call handling.

Example:

```text
Incoming Call

Customer:
John Smith

Phone:
+998 XX XXX XX XX

Orders:
17

Last Order:
25 Sep 2026

Open Order:
#18492

Reservations:
2 upcoming

[Answer]
```

### 17. Call Event Architecture

Wazo exposes WebSocket/event mechanisms and webhook capabilities.

The first implementation may use:

```text
Wazo
  │
  ▼
Contact Center Event Handler
  │
  ├── PostgreSQL
  ├── CRM
  └── WebSocket → Agent UI
```

As scale increases:

```text
Wazo
  │
  ▼
Event Gateway
  │
  ▼
Kafka
  │
  ├── CRM
  ├── Analytics
  ├── Interaction History
  ├── AI
  ├── Billing
  └── Notifications
```

Kafka is therefore a **scale-stage decision**, not an MVP requirement.

### 18. Agent Application

The agent experience should be owned by us.

```text
Web Application
      │
      ├── Authentication
      ├── Customer screen-pop
      ├── Call controls
      ├── Queue status
      ├── Customer history
      ├── Order information
      └── Call disposition
             │
             ▼
      Contact Center API
             │
        ┌────┴────┐
        │         │
       CRM       Wazo
```

This prevents the business from becoming dependent on the UI of the underlying telephony vendor.

### 19. AI Integration

The architecture should explicitly preserve an AI integration point.

Potential future architecture:

```text
                    Wazo / Asterisk
                          │
                     Call Media
                          │
                          ▼
                    AI Gateway
                          │
                ┌─────────┼─────────┐
                │         │         │
               STT       LLM       TTS
                │         │         │
                └─────────┼─────────┘
                          │
                    AI Agent Service
                          │
                          ▼
                         CRM
```

Asterisk's ARI exposes communication primitives and asynchronous events for custom communication applications, while current Asterisk releases also provide a WebSocket channel driver for media applications.

This provides a technical path toward:

- AI receptionist;
- reservation assistant;
- order-taking voicebot;
- FAQ automation;
- call summarization;
- agent-assist;
- automatic call classification;
- sentiment/quality analytics.

### 20. Recording Architecture

Call recordings should not become part of the core transactional CRM database.

Recommended architecture:

```text
Wazo
 │
 │ Recording
 ▼
Object Storage
 │
 ├── Tenant isolation
 ├── Encryption
 ├── Lifecycle policy
 └── Retention policy
       │
       ▼
CRM / Contact Center
 │
 └── Recording metadata + secure reference
```

CRM should store:

```text
Call ID
Tenant ID
Customer ID
Agent ID
Start time
End time
Direction
Disposition
Recording reference
```

rather than storing large media blobs directly in PostgreSQL.

### 21. Security and Tenant Isolation

Tenant ID must be a first-class security boundary.

Every business object should have an explicit tenant association.

Example:

```text
tenant_id
restaurant_id
location_id
```

must be present where applicable.

The platform must enforce:

- tenant-aware authentication;
- authorization;
- tenant-scoped APIs;
- tenant-scoped Wazo resources;
- tenant-scoped recordings;
- tenant-scoped reporting;
- tenant-scoped agent access;
- tenant-scoped CRM data access.

No API should rely solely on a frontend-provided tenant identifier for authorization.

The authorization context must come from authenticated identity and server-side policy.

### 22. Authentication and Authorization

The Contact Center platform should own the application-level authorization model.

Wazo authentication should remain behind the Contact Center platform where practical.

Wazo provides its own authentication/token and ACL model for service access.

The desired architecture is:

```text
User
 │
 ▼
Our Identity / Auth
 │
 ▼
Contact Center API
 │
 ├── Authorization
 │
 └── Wazo Adapter
       │
       ▼
      Wazo
```

This prevents Wazo's authorization model from becoming the public security model of our product.

### 23. Initial Technology Stack

For the MVP:

| Component | Technology |
|---|---|
| Backend | Python + FastAPI |
| Database | PostgreSQL |
| Cache / ephemeral state | Redis |
| Contact Center platform | Wazo |
| Telephony engine | Asterisk via Wazo |
| Agent UI | React / Next.js |
| WebRTC | Wazo |
| Object storage | S3-compatible storage |
| API documentation | OpenAPI |
| Deployment | Docker |
| Observability | Prometheus + Grafana + Loki |
| Events | Wazo events/webhooks |
| Message broker | Introduce later if required |

The first three-restaurant deployment should **not** begin with Kubernetes, Kafka, service mesh and a large microservice estate.

The initial architecture should be deliberately boring.

### 24. Scaling Strategy

The owner's three phases (3 restaurants, about 100, thousands) are given under Rollout and rollback below.

### 25. High Availability Strategy

HA should evolve with scale.

#### MVP

Accept controlled single-instance failure with:

- backups;
- monitoring;
- documented recovery;
- redundant SIP connectivity where practical.

#### 100 tenants

Introduce:

- redundant application instances;
- database HA;
- redundant SIP connectivity;
- automated deployment;
- infrastructure monitoring.

#### Thousands

Evaluate:

- multiple Wazo clusters;
- geographic/regional isolation;
- active-active application services;
- distributed event processing;
- SIP carrier redundancy;
- disaster recovery regions.

The architecture must not assume that "thousands of restaurants" automatically requires every component to be distributed.

### 26. Data Ownership

| Data | System of Record |
|---|---|
| Restaurant | CRM / Restaurant Platform |
| Location | CRM / Restaurant Platform |
| Customer | CRM |
| Orders | Restaurant Platform |
| Reservations | Restaurant Platform |
| Agent employment | Our Platform |
| Contact Center configuration | Our Platform |
| Phone number ownership | Our Platform |
| Telephony configuration | Wazo |
| Call state | Wazo |
| CDR | Wazo + our interaction model |
| Interaction history | Our Platform / CRM |
| Recordings | Object Storage |
| Billing | Our Platform |
| Subscription | Our Platform |

This separation is mandatory for avoiding vendor lock-in.

### 30. Architectural Principles

The following principles are mandatory:

#### Principle 1 — CRM owns business data

Wazo does not become the customer or restaurant master database.

#### Principle 2 — Contact Center owns communication business logic

Routing policies, tenant configuration and agent business concepts belong to our platform.

#### Principle 3 — Wazo owns telephony mechanics

Do not duplicate SIP/PBX functionality unnecessarily.

#### Principle 4 — Never expose Wazo directly to the CRM

Use a Contact Center API.

#### Principle 5 — Everything that can be provisioned must eventually be provisionable through APIs

Manual PBX configuration must not become the operating model.

#### Principle 6 — Tenant is a first-class domain concept

Tenant isolation must exist at API, data and infrastructure boundaries.

#### Principle 7 — Events over point-to-point synchronization

Call state changes should eventually be represented as events rather than creating large numbers of tightly coupled integrations.

#### Principle 8 — Scale architecture progressively

Do not introduce distributed infrastructure simply because the eventual target is large.

## Rollout and rollback

### 24. Scaling Strategy

#### Phase 1 — 3 Restaurants

Objective:

> Validate the product and operational model.

```text
                     CRM
                      │
               Contact Center API
                      │
                    Wazo
                      │
                 SIP Provider
```

Characteristics:

- single Wazo deployment;
- PostgreSQL;
- Redis;
- Docker;
- simple event handling;
- centralized monitoring;
- custom agent UI;
- manual operational fallback.

Focus:

- call quality;
- CRM integration;
- tenant isolation;
- agent workflow;
- onboarding process.

#### Phase 2 — ~100 Restaurants

Objective:

> Automate provisioning and eliminate manual operations.

Introduce:

- automated tenant provisioning;
- automated phone-number provisioning;
- infrastructure-as-code;
- stronger monitoring;
- centralized event processing;
- backup/restore automation;
- recording lifecycle management;
- tenant-level quotas;
- automated onboarding;
- billing integration;
- operational dashboards.

Architecture:

```text
                    API Gateway
                         │
                 Contact Center API
                         │
            ┌────────────┼────────────┐
            │            │            │
           CRM          Wazo        Events
                         │
                    SIP Providers
```

At this stage, evaluate whether a single Wazo environment remains the appropriate deployment topology.

#### Phase 3 — Thousands of Restaurants

Objective:

> Operate the Contact Center as a real communications SaaS platform.

Potential architecture:

```text
                       API Gateway
                           │
                   Contact Center API
                           │
              ┌────────────┼────────────┐
              │            │            │
            CRM          Kafka        Billing
              │            │
              │       ┌────┼────┬─────┐
              │       │    │    │     │
              │      AI  Analytics CRM Notifications
              │
              ▼
                    Wazo Platform
                       /    \
                      /      \
                Wazo Cluster / Pool
                    /          \
                 SIP            SIP
                  │              │
               Carrier        Carrier
```

At this stage, introduce additional infrastructure only when justified by measurable requirements.

Potential technologies:

- Kubernetes;
- Kafka;
- ClickHouse;
- distributed object storage;
- multiple Wazo instances/clusters;
- regional deployments;
- dedicated SIP infrastructure;
- centralized observability.

### Rollback

Written at registration; not in the owner's text. Expected rollback, if the adapter is a `VOICE` binding of [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md): rollback suspends bindings and retains installations and evidence ([ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md)); Wazo keeps telephony configuration, call state and its half of the CDR (§26); the platform returns to [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md)'s state with no live `VOICE` provider connected. What happens to numbers already assigned at the carrier depends on the numbering open input.

## Implementation checklist

### 31. Initial MVP Scope

The first production-capable MVP should contain:

#### Tenant Management

- [ ] create tenant;
- [ ] configure restaurant;
- [ ] configure location;
- [ ] assign phone number.

#### Agent Management

- [ ] create agent;
- [ ] assign agent to restaurant;
- [ ] assign queue;
- [ ] configure availability.

#### Telephony

- [ ] inbound calls;
- [ ] outbound calls;
- [ ] IVR;
- [ ] queues;
- [ ] transfers;
- [ ] business hours;
- [ ] voicemail;
- [ ] call recording.

#### CRM

- [ ] phone-number lookup;
- [ ] customer screen-pop;
- [ ] call journaling;
- [ ] customer interaction history.

#### Agent UI

- [ ] login;
- [ ] availability;
- [ ] incoming call;
- [ ] answer/hangup;
- [ ] hold;
- [ ] transfer;
- [ ] customer information;
- [ ] call disposition.

#### Platform

- [ ] tenant isolation;
- [ ] audit logging;
- [ ] monitoring;
- [ ] backups;
- [ ] API documentation.

### Repository items

Written at registration; not in the owner's text.

- [ ] Wazo API credentials and SIP trunk credentials are [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md) secret references, never values in configuration or the database
- [ ] Provisioning, configuration changes and recording access are audited under [ADR 0027](../built/0027-audit-evidence-and-approval-model.md)

Applies only if the service-runtime input resolves to a module of this platform; if it resolves to a separate service, restate these for it:

- [ ] The Wazo adapter is a provider adapter under the `VOICE` category of [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md): it translates Wazo events into the normalized call-event vocabulary and declares its capabilities, as the two existing adapter kinds do
- [ ] Any call-centre event beyond `VoiceCallEventRecorded` v1 has a JSON schema and an `EventCatalog` entry under [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md), and carries identifiers only ([ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md))
- [ ] Every mutating contact-centre endpoint declares a capability code under [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md)
- [ ] Flyway migrations are append-only; every new table has a non-null `tenant_id` and its `GRANT` for the application role
- [ ] OpenAPI baselines are regenerated for each surface (ADR 0057)
- [ ] A fake Wazo server, in the genre of [ADR 0007](../partial/0007-camel-route-foundation-and-provider-contract-testing.md) (as `FakeAsteriskAmiServer` is for the Asterisk adapter), proves the adapter without a live Wazo
- [ ] A cross-tenant isolation test covers every new endpoint and every recording reference
- [ ] A proof against a live carrier number (a number that rings through Wazo to an agent)

### Registration annotations

Written at registration; not in the owner's text. The [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md), [ADR 0008](../built/0008-resumable-tenant-onboarding-workflow.md) and [ADR 0027](../built/0027-audit-evidence-and-approval-model.md) core already meets, on the platform's own side and not for Wazo, these §31 items: tenant, restaurant and location creation (ADR 0008 onboarding creates tenants, brands and locations); phone-number lookup and the screen-pop on an inbound call event (ADR 0064); login, availability and the incoming-call card in the operations app (the existing staff sign-in, ADR 0064's operator presence and screen-pop); and tenant isolation and audit logging (ADR 0027 and the `tenant_id` rule). What remains is the rest of §31: every telephony item (outbound calls, IVR, queues, transfers, business hours, voicemail, call recording), phone-number assignment, agent-to-queue assignment, the answer, hang-up, hold and transfer controls, call disposition, call journaling and customer interaction history beyond ADR 0064's call log, and the Wazo side of each item above.

## Exit criteria

### 32. Success Criteria

The architecture will be considered validated when:

1. A new restaurant can be provisioned without manual Wazo configuration.
2. A restaurant receives its own phone number and routing configuration.
3. Agents can receive and make calls.
4. Incoming callers can be matched against CRM records.
5. Agents can see customer context.
6. Calls are recorded according to configured policy.
7. Call history is available in the CRM.
8. One tenant cannot access another tenant's data.
9. Wazo can be upgraded without changing the CRM domain model.
10. A new Wazo instance can be introduced without changing the public Contact Center API.

### Proof this repository will expect

Written at registration; not in the owner's text. It does not replace the owner's criteria above.

- A Wazo upgrade in staging passes the contract suite with no domain-schema or public-API change (criterion 9).
- A second Wazo instance registered through the same adapter passes the same suite (criterion 10).
- An inbound call on a real carrier number reaches an agent and pops the card.

## References

### Decisions this record leaves to later records

The following decisions should be documented separately rather than overloading this ADR:

- Tenant isolation model
- SIP carrier and numbering strategy
- Contact Center domain model
- CRM integration architecture
- Event architecture and Kafka adoption
- Call recording storage and retention
- WebRTC architecture
- AI voicebot and media architecture
- Wazo deployment topology and high availability
- Billing and usage metering
- Observability and SLA/SLO model
- Disaster recovery strategy
- Multi-region architecture

### Records this decision depends on

- [ADR 0001](../built/0001-platform-foundation.md) — Java platform foundation
- [ADR 0002](../partial/0002-saas-domain-model.md) — SaaS domain model and order acceptance
- [ADR 0003](../built/0003-keycloak-tenant-authorization.md) — Keycloak tenant authorization
- [ADR 0004](../built/0004-sql-outbox-and-kafka-delivery.md) — SQL transactional outbox and Kafka delivery
- [ADR 0007](../partial/0007-camel-route-foundation-and-provider-contract-testing.md) — Camel route foundation and provider contract testing
- [ADR 0008](../built/0008-resumable-tenant-onboarding-workflow.md) — Resumable tenant onboarding workflow
- [ADR 0009](../partial/0009-keycloak-organization-provisioning-and-membership-reconciliation.md) — Keycloak organization provisioning and membership reconciliation
- [ADR 0023](../partial/0023-production-operating-model-observability-security-and-recovery.md) — Production operating model, observability, security, and recovery
- [ADR 0025](../built/0025-fine-grained-authorization-and-capability-model.md) — Fine-grained authorization and the capability model
- [ADR 0026](../built/0026-provider-installations-bindings-and-secret-references.md) — Provider installations, bindings, and secret references
- [ADR 0027](../built/0027-audit-evidence-and-approval-model.md) — Audit evidence and the approval model
- [ADR 0028](../partial/0028-secrets-management-and-credential-lifecycle.md) — Secrets management and credential lifecycle
- [ADR 0029](../partial/0029-pii-protection-envelope-encryption-and-key-rotation.md) — PII protection, envelope encryption, and key rotation
- [ADR 0030](../built/0030-configuration-and-policy-resolution.md) — Configuration and policy resolution
- [ADR 0031](../built/0031-http-api-conventions.md) — HTTP API conventions
- [ADR 0032](../built/0032-event-contract-governance-and-topic-policy.md) — Event contract governance and topic policy
- [ADR 0033](../built/0033-caching-rate-limiting-and-shared-runtime-state.md) — Caching, rate limiting, and shared runtime state
- [ADR 0034](../partial/0034-hosting-environments-topology-and-data-residency.md) — Hosting environments, topology, and data residency
- [ADR 0073](../not-started/0073-production-runs-on-a-rented-vm-in-country.md) — Production runs on a rented machine in Uzbekistan, not on hardware we own
- [ADR 0035](../partial/0035-angular-frontend-platform-and-design-system-adoption.md) — Frontend platform, repository split, and design system adoption
- [ADR 0036](../partial/0036-sales-channels-and-location-serviceability.md) — Sales channels and location serviceability
- [ADR 0043](../partial/0043-reporting-analytics-and-the-metric-layer.md) — Reporting, analytics, and the metric layer
- [ADR 0064](../partial/0064-voice-channels-and-the-operator-presence-model.md) — Voice joins the channels — IP telephony, operator presence, and the screen-pop
- [ADR 0111](../not-started/0111-customer-card-and-communication-history.md) — A guest has one card, and every contact with them is history
- [ADR 0135](../partial/0135-object-storage-runtime-rustfs-replaces-minio.md) — Object storage runtime: RustFS replaces MinIO
- [ADR 0139](../not-started/0139-staff-identity-the-staff-person-record.md) — Staff identity: the staff person record

### Other records named in this record

- [ADR 0057](../built/0057-openapi-per-surface-document-groups.md) — OpenAPI per-surface document groups
- [ADR 0061](../partial/0061-production-deployment-pilot-on-owned-hardware-portable-by-construction.md) — Production runs on owned hardware first — and stays portable by construction

### Documentation relevant to the owner's text (links added at registration; the owner cited none)

- Asterisk REST Interface (ARI), §7 and §19: <https://docs.asterisk.org/Configuration/Interfaces/Asterisk-REST-Interface-ARI/> and <https://docs.asterisk.org/Latest_API/API_Documentation/Asterisk_REST_Interface/>
- Asterisk WebSocket channel driver, §19: <https://docs.asterisk.org/Configuration/Channel-Drivers/WebSocket/>
- Wazo Platform API and SDK, §9: <https://wazo-platform.org/uc-doc/api_sdk/>, with the REST API at <https://wazo-platform.org/uc-doc/api_sdk/rest_api> and the WebSocket event service at <https://wazo-platform.org/uc-doc/api_sdk/websocket> (§17)
- 3CX Configuration API, §10: <https://www.3cx.com/docs/configuration-rest-api/>
- 3CX CRM integration, §10: <https://www.3cx.com/docs/crm-integration/>
