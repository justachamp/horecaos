# ADR 0001: Multi-Tenant Restaurant Contact Center Platform

**Status:** Proposed  
**Date:** 2026-10-02  
**Decision Type:** Solution / Platform Architecture  
**Scope:** Restaurant Contact Center as a Service (CCaaS)  
**Initial Scale:** 3 restaurants  
**Target Scale:** 100 restaurants → thousands of restaurants

---

## 1. Context

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

---

## 2. Problem Statement

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

---

# 3. Goals

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

---

# 4. Non-Goals

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

---

# 5. Decision Drivers

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

---

# 6. Options Considered

The following platforms were evaluated:

1. **Asterisk**
2. **FreePBX**
3. **Wazo Platform**
4. **3CX**

---

# 7. Option A — Asterisk

Asterisk is a communications engine/framework rather than a complete Contact Center SaaS platform.

Its ARI provides REST-based control over communication primitives such as channels, bridges, endpoints and media, with asynchronous events delivered through WebSockets. This makes Asterisk highly suitable for building custom communications applications.

### Advantages

- Open source.
- Very mature SIP/telephony ecosystem.
- Extremely flexible.
- Excellent developer control.
- Strong automation possibilities.
- ARI provides a programmable communications layer.
- Excellent foundation for custom AI/voice applications.
- Minimal vendor lock-in.

### Disadvantages

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

### Assessment

Asterisk remains an important underlying technology but is not selected as the primary platform abstraction.

---

# 8. Option B — FreePBX

FreePBX provides a GUI and management layer around Asterisk.

It is well suited to deploying and administering conventional PBX systems.

### Advantages

- Open source.
- Mature Asterisk ecosystem.
- Large community.
- Fast PBX deployment.
- Good administrative UI.
- Large module ecosystem.
- Lower engineering effort than raw Asterisk.

### Disadvantages

Its architecture is oriented toward **PBX administration**, not toward building a custom communications SaaS.

The platform would still require significant custom development around:

- automated multi-tenant provisioning;
- CRM integration;
- custom agent experience;
- event-driven business logic;
- SaaS lifecycle;
- tenant-level automation;
- product-level APIs.

### Assessment

FreePBX is not selected because the target product is a programmable CCaaS platform rather than a collection of administrated PBXs.

---

# 9. Option C — Wazo Platform

Wazo is selected as the underlying communications platform.

Wazo currently exposes a broad API/SDK surface covering authentication, application/call control, configuration, contacts, phone provisioning, webhooks, CDR, plugins, call-center agents, presence/chat and WebSockets.

Its architecture also includes Asterisk-based telephony capabilities and additional communications infrastructure.

Wazo provides APIs for authentication, users, groups, policies and access control.

### Advantages

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

### Disadvantages

- More complex than deploying a conventional PBX.
- Requires engineering around the Wazo APIs.
- Requires us to build our own product/domain layer.
- Wazo becomes an infrastructure dependency.
- Some Wazo-specific concepts must be abstracted from our domain model.

### Assessment

Wazo provides the best alignment between the requirements of a programmable CCaaS platform and the need to avoid building an entire telephony platform from scratch.

---

# 10. Option D — 3CX

3CX provides an integrated commercial business communications platform.

3CX currently supports multi-company deployments and provides a Configuration API that can manage companies/departments, users, call handling and other configuration programmatically.

3CX also provides CRM integration mechanisms, including REST-based contact lookup, contact creation and call journaling.

### Advantages

- Fast deployment.
- Mature business communications product.
- Good administration experience.
- Multi-company support.
- CRM integration.
- Web/mobile clients.
- Contact Center capabilities.
- Less engineering required for the initial product.

### Disadvantages

- Commercial proprietary platform.
- Greater dependency on the vendor's product model.
- Less control over the underlying communications architecture.
- Product behavior is constrained by the 3CX platform.
- Less attractive as the foundation of a deeply customized communications product.
- CRM integration and multi-company capabilities have platform-specific constraints; for example, current 3CX documentation notes restrictions around CRM integration when switching to Multi-Company mode.

### Assessment

3CX is a viable option for rapidly delivering a managed business PBX/Contact Center service, but Wazo provides a better architectural boundary for building our own product.

---

# 11. Decision Matrix

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

---

# 12. Decision

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

---

# 13. Architectural Boundary

The most important architectural decision is the boundary between our domain and Wazo.

### Our platform owns

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

### Wazo owns

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

---

# 14. Anti-Corruption Layer

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

---

# 15. Tenant Model

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

---

# 16. Call Flow — Incoming Call

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

---

# 17. Call Event Architecture

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

---

# 18. Agent Application

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

---

# 19. AI Integration

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

---

# 20. Recording Architecture

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

---

# 21. Security and Tenant Isolation

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

---

# 22. Authentication and Authorization

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

---

# 23. Initial Technology Stack

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

---

# 24. Scaling Strategy

## Phase 1 — 3 Restaurants

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

---

## Phase 2 — ~100 Restaurants

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

---

## Phase 3 — Thousands of Restaurants

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

---

# 25. High Availability Strategy

HA should evolve with scale.

### MVP

Accept controlled single-instance failure with:

- backups;
- monitoring;
- documented recovery;
- redundant SIP connectivity where practical.

### 100 tenants

Introduce:

- redundant application instances;
- database HA;
- redundant SIP connectivity;
- automated deployment;
- infrastructure monitoring.

### Thousands

Evaluate:

- multiple Wazo clusters;
- geographic/regional isolation;
- active-active application services;
- distributed event processing;
- SIP carrier redundancy;
- disaster recovery regions.

The architecture must not assume that "thousands of restaurants" automatically requires every component to be distributed.

---

# 26. Data Ownership

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

---

# 27. Consequences

## Positive Consequences

### 1. We build a product rather than a PBX service

The Contact Center becomes a native component of our restaurant ecosystem.

### 2. CRM remains the business source of truth

Telephony does not dictate the business domain model.

### 3. Strong automation potential

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

### 4. Custom customer experience

We can build the exact agent UI required by the restaurant business.

### 5. AI-ready architecture

The communication layer remains accessible for future voice AI applications.

### 6. Lower vendor lock-in

Wazo-specific functionality is isolated behind an adapter.

### 7. Progressive scaling

We do not need to operate a hyperscale platform when we only have three restaurants.

---

# 28. Negative Consequences

### 1. We own more software

Compared with 3CX, we must build:

- product UI;
- tenant lifecycle;
- CRM integration;
- business APIs;
- reporting;
- billing;
- some operational tooling.

### 2. Wazo becomes an infrastructure dependency

Although the architecture isolates Wazo, operating and upgrading Wazo remains our responsibility.

### 3. Telecom expertise is required

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

### 4. More engineering than 3CX

The initial implementation will take more engineering effort than deploying a ready-made PBX.

### 5. Operational complexity grows with scale

At thousands of tenants, communications infrastructure becomes a significant platform engineering responsibility.

---

# 29. Risks and Mitigations

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

---

# 30. Architectural Principles

The following principles are mandatory:

### Principle 1 — CRM owns business data

Wazo does not become the customer or restaurant master database.

### Principle 2 — Contact Center owns communication business logic

Routing policies, tenant configuration and agent business concepts belong to our platform.

### Principle 3 — Wazo owns telephony mechanics

Do not duplicate SIP/PBX functionality unnecessarily.

### Principle 4 — Never expose Wazo directly to the CRM

Use a Contact Center API.

### Principle 5 — Everything that can be provisioned must eventually be provisionable through APIs

Manual PBX configuration must not become the operating model.

### Principle 6 — Tenant is a first-class domain concept

Tenant isolation must exist at API, data and infrastructure boundaries.

### Principle 7 — Events over point-to-point synchronization

Call state changes should eventually be represented as events rather than creating large numbers of tightly coupled integrations.

### Principle 8 — Scale architecture progressively

Do not introduce distributed infrastructure simply because the eventual target is large.

---

# 31. Initial MVP Scope

The first production-capable MVP should contain:

### Tenant Management

- create tenant;
- configure restaurant;
- configure location;
- assign phone number.

### Agent Management

- create agent;
- assign agent to restaurant;
- assign queue;
- configure availability.

### Telephony

- inbound calls;
- outbound calls;
- IVR;
- queues;
- transfers;
- business hours;
- voicemail;
- call recording.

### CRM

- phone-number lookup;
- customer screen-pop;
- call journaling;
- customer interaction history.

### Agent UI

- login;
- availability;
- incoming call;
- answer/hangup;
- hold;
- transfer;
- customer information;
- call disposition.

### Platform

- tenant isolation;
- audit logging;
- monitoring;
- backups;
- API documentation.

---

# 32. Success Criteria

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

---

# 33. Future ADRs

The following decisions should be documented separately rather than overloading this ADR:

- **ADR-002:** Tenant isolation model
- **ADR-003:** SIP carrier and numbering strategy
- **ADR-004:** Contact Center domain model
- **ADR-005:** CRM integration architecture
- **ADR-006:** Event architecture and Kafka adoption
- **ADR-007:** Call recording storage and retention
- **ADR-008:** WebRTC architecture
- **ADR-009:** AI voicebot/media architecture
- **ADR-010:** Wazo deployment topology and HA
- **ADR-011:** Billing and usage metering
- **ADR-012:** Observability and SLA/SLO model
- **ADR-013:** Disaster recovery strategy
- **ADR-014:** Multi-region architecture

---

# 34. Final Decision Summary

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

---

# 35. Registration Notes

*Written at registration on 2026-10-03, not by the owner. Sections 1–34 are the owner's
text as received; nothing in them was changed except the title line, which gained the
number 0001 to match the owner's own numbering of the later records in §33.*

**Where this record lives.** The owner decided on 2026-10-03 that the Contact Center is a
separate project in this repository — `voice_platform/` — and not part of the HorecaOS
platform's module set or its ADR series. This record was first filed as HorecaOS ADR 0152
(commit `d49b0087`) and moved here the same day; that number is retired. The HorecaOS
ADR template, capability model, policy-resolution, observability and runtime-state
decisions bind `platform/`, not this project, so the open questions the first filing
raised about the service's stack and sequencing (Python + FastAPI, Redis, React / Next.js,
Loki, a broker later) are answered by this record as written.

**What crosses the boundary.** The owner still decides these; they are listed so the
integration is built deliberately:

1. **HorecaOS is the "CRM / Restaurant Platform" of this record** — customers, orders,
   reservations and restaurant/location data are read and journaled through HorecaOS's
   public APIs, never by sharing its database.
2. **HorecaOS ADR 0064 already built a provider-neutral voice core** in `platform/` — a
   `VOICE` provider category with installations and secret references, call-event
   ingestion with de-duplication and the outbox, operator presence, a masked screen-pop in
   the operations app, and hourly call facts; it decided that the HorecaOS platform itself
   never carries audio. Whether this platform plugs into HorecaOS as that `VOICE` provider
   (pushing call events in) while this record's agent application (§18) carries the audio,
   or whether HorecaOS's call-centre screen is retired in favour of the agent application,
   is open (owner).
3. **Personal data at the boundary** — events and API calls into HorecaOS carry
   identifiers only; names and numbers are resolved on the HorecaOS side through
   authorized, audited calls (HorecaOS ADR 0029). The §16 screen-pop example is
   illustrative of the agent's view, not an event contract.
4. **Numbering, the SIP carrier and recording** — HorecaOS ADR 0064 left per-brand numbers
   versus a shared line and the legal posture of recording open; both are decided in this
   project, by ADR-003 and ADR-007 of §33.
5. **Hosting and data residency** for Wazo, call media and recordings in Uzbekistan —
   decided by ADR-010 and ADR-014 of §33; HorecaOS's hosting records do not bind this
   project.
