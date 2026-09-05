# ADR 0069: An assistant answers customers — grounded in platform facts, never in model memory

- Decision status: Proposed
- Implementation status: Not started
- Date proposed: 2026-09-05
- Date decided: —
- Deciders: platform owner (direction and the open inputs below), Claude (architecture)
- Depends on: 0016, 0018, 0019, 0020, 0021, 0025, 0026, 0027, 0028, 0029, 0031, 0033, 0036, 0039, 0043, 0058, 0059, 0063, 0064
- Supersedes / Superseded by: —
- Open inputs: which model provider adapter ships first and the per-tenant spend
  ceiling (owner); whether the assistant may ever complete a checkout itself or
  stays assemble-and-confirm permanently (owner); which plan tier the assistant
  is entitled to under ADR 0021 (owner); whether sending pseudonymised customer
  message text to a third-party processor is acceptable under each tenant's own
  obligations, and what a tenant must disclose to its customers (owner, with
  legal advice this record does not attempt to give)

## Context

Customers already write to the tenant's Telegram bot asking what a dish costs,
whether a branch is open, where the branches are, and whether something is
available today. ADR 0059 built the pipe — conversations, contacts, the flow
engine, and the operator inbox — and ADR 0058 and ADR 0063 built the Telegram
channel and Telegram-native identity underneath it. What arrives through that
pipe today is answered by a person, by a deterministic flow that matches a known
intent, or not at all.

The questions being asked are the ones the platform can already answer
authoritatively and no one else can. A price is not a fact about a product; it
is the output of ADR 0018's deterministic pricing against a price book, a
channel, and a location, and the same dish legitimately costs different amounts
through different channels. Availability is ADR 0017's inventory and ADR 0016's
publication state at one location. Serviceability is ADR 0036 and ADR 0037.
Whether a customer's own order is late is ADR 0019's order state. Every one of
these changes during a shift.

That is the constraint that makes this decision non-obvious. A general-purpose
assistant that has read a menu is confidently wrong within a day, and it is
wrong about money, to a customer, in the tenant's name. The value here is not
fluency; the platform already has the answers. The value is turning an
open-ended question into the right platform read, and refusing when there
isn't one.

Two further constraints are structural rather than aspirational. Customer
message bodies are envelope-encrypted under ADR 0029 and ADR 0059's stated PII
posture, and a third-party model provider is a processor the tenant did not
choose. And the operations team — not the platform team — is who knows that
this brand does not do deliveries after 22:00, that the "family set" feeds four,
and that the Yunusabad branch has no parking. That knowledge has nowhere to live
today.

## Decision

**The assistant is a participant in ADR 0059's conversations, not a new channel
and not a second bot.** It reads and writes the same conversation the operator
inbox sees, so a handoff is a change of author and not a change of system. It
is channel-neutral for the same reason the conversation core is: Telegram is the
first surface, not the design.

**It answers only from facts retrieved for that turn, never from model memory.**
Prices come from the same ADR 0018 path the storefront quotes with; availability
from ADR 0016/0017 at the location in question; branches, hours and delivery
coverage from ADR 0036/0037; a customer's own order state from ADR 0019 and only
for the identity ADR 0063 proved. **A stated price must be a price the platform
would actually charge on that channel at that location.** If retrieval yields
nothing that answers the question, the assistant says so and offers a person.
**Refusal is a first-class outcome with its own tests, not a failure mode** — an
invented price is worse than no answer, and this is the decision that keeps it
out.

**Tenant knowledge is authored by the operations team, versioned, and scoped.**
A knowledge entry is a short, tenant-owned answer with an explicit scope (tenant,
brand, or location) and locale, published as a new version rather than edited in
place, audited under ADR 0027 — the same posture ADR 0068 gives terms and ADR
0020 gives notification templates, for the same reason: someone was answered
with specific words at a specific time. Retrieval reads **only the asking
tenant's** entries; cross-tenant retrieval is a tenant-isolation defect, not a
relevance bug.

**A deterministic flow wins whenever one matches.** ADR 0059's flows are cheaper,
faster, and auditable by reading them. The assistant handles the long tail the
flows do not model, and an intent that becomes common is a candidate for
promotion into a flow rather than a permanent inference cost.

**The assistant assembles orders; it does not complete them.** It may build a
cart through the existing ADR 0019 endpoints — the same cart, the same quote,
the same refusals, including the blacklist and serviceability guards — and then
hands the customer an explicit confirmation step. It never selects a payment
method on a customer's behalf, never completes checkout, and never bypasses an
ADR 0025 capability check. The resulting order is an ordinary order whose
provenance records that the assistant assembled it, in the same shape ADR 0064
records a call id. Money is the reason: an assistant that can complete a
purchase can complete a wrong purchase at conversation scale, and a confirmation
step is what stands between a misparsed "two" and a customer's bill.

**The model provider is an adapter, like every other provider on this
platform.** It installs under ADR 0026 with an ADR 0028 secret reference,
declares its capabilities, and exposes a normalized request and response to the
core. No provider DTO, model id, or `if (provider == X)` reaches core code. The
exact model is configuration, not source.

**Personal data does not leave for the model.** The provider receives the
customer's question, the retrieved facts, and pseudonymous identifiers — never a
name, phone number, or address. Personal values are rendered into the reply
locally, after the model returns. This is ADR 0029 applied to a new egress, and
it is enforced by a test rather than by care.

**Escalation is designed, not incidental.** No grounded answer, a complaint or
refund topic, a repeated failure to help, or a customer asking for a person all
hand the conversation to ADR 0059's operator inbox with its history intact. ADR
0064's operator presence says whether anyone is actually there to receive it,
and the assistant tells the customer the truth about that.

**Spend is capped and visible.** Per-tenant budget ceilings and per-conversation
turn caps run on ADR 0033's rate-limiting and caching; identical grounded
questions are served from cache. The assistant is entitlement-gated under ADR
0021, and its usage lands in ADR 0043's fact layer like every other metered
thing.

**Behaviour changes are gated by an evaluation suite.** A prompt, a retrieval
change, or a model swap changes what customers are told and no compiler notices.
A golden-set suite asserts grounding: a price question yields the pricing
engine's number, an unanswerable question yields a refusal, a cross-tenant probe
yields nothing. It is a build gate. It is **not** the existing `evals/`
directory, which tests this repository's own agent configuration and is a
different concern that happens to share a technique.

## Alternatives considered

| Option | Why not chosen | Revisit when |
|---|---|---|
| A third-party chatbot SaaS with its own inbox | Splits conversation truth from the platform and cannot answer authoritatively about price, availability, or an order's state — the questions actually being asked. The platform has just spent a programme exiting SendPulse for this class of reason (ADR 0059) | Never for pricing or order questions; reconsider only for a purely marketing FAQ that never touches platform state |
| Retrieval over uploaded documents (menu PDFs, a policy file) | A document is a snapshot; price is per channel and per location and availability changes within a shift. It would be wrong about money on day two, in the tenant's name | A question class is shown to be genuinely static — opening hours, parking — where a knowledge entry already covers it better |
| Fine-tune a model per tenant on its catalogue | Bakes facts that change daily into weights, at a cost per tenant, and still cannot quote a channel-specific price | Retrieval latency proves unacceptable *and* the facts in question are provably static |
| Give the model the platform's HTTP API and let it act autonomously with tool use | The assistant would become an authenticated actor whose authorization is decided by inference. Money and ADR 0025 capabilities are not inference problems | The eval suite and audit trail are mature enough to trust autonomous writes, and the owner closes the open input on autonomous checkout |
| Extend ADR 0059's deterministic flows to cover these questions | Flows match known intents; "do you have plov today" and "where are you" are open-ended phrasings of many intents. This is precisely the gap the owner observed | Not an either/or — flows stay first, and a common intent is promoted into one |
| Answer from the model's own knowledge with no retrieval | Fluent, fast, and wrong about every number that matters | Never |

## Consequences

### Positive

- The questions customers already ask get answered correctly, at any hour,
  without an operator reading each one.
- The answers are the platform's own — the same price the customer would be
  charged, the same availability the storefront shows.
- Operations gains a place to put tenant knowledge, and improving an answer
  becomes editing an entry rather than filing a ticket.
- Escalation carries history, so a person picks up mid-conversation rather than
  asking the customer to repeat themselves.
- Order assembly reuses checkout wholesale, so every guard already built —
  serviceability, blacklist, quote binding, idempotency — applies unchanged.

### Negative

- A per-message cost and an external dependency enter a channel that was free
  and self-contained. A provider outage degrades a customer-facing surface.
- A grounded answer needs retrieval plus inference, so it is slower than a flow
  and much slower than a cached read.
- The assistant will refuse questions a human would have answered. That is the
  design, and it will read as unhelpful sometimes.
- Prompt and model changes alter customer-facing behaviour with no compile-time
  signal; only the eval suite stands between a wording change and a regression.
- Knowledge quality becomes an operations responsibility. A wrong entry is now a
  confidently wrong answer delivered at scale, in the tenant's name.
- The PII boundary limits personalisation: the assistant cannot greet a customer
  by name in the model's own words, because the name never reaches the model.

### Accepted trade-offs

- **Refusing beats guessing.** Coverage is deliberately sacrificed for
  correctness about money.
- **Assemble-and-confirm beats autonomous ordering**, at the cost of one extra
  step for the customer, until the owner decides otherwise.
- **A second inference path is not built.** Voice (ADR 0064) will eventually want
  the same grounded answering; this record builds it once, channel-neutral, and
  accepts that the first consumer pays for generality it does not yet need.

## Specification

**Module.** A new `assistant` module owning retrieval, grounding, the knowledge
store, and the provider port. It depends on other modules only through their
`api` ports — catalog, pricing, ordering, customers — never across schemas, the
discipline `loyalty.api.ReferralGrantPort` and `customers.api.CustomerBlacklistPort`
already follow.

**Knowledge store.** `assistant.knowledge_entries` and
`assistant.knowledge_entry_versions`, append-only by grant (`SELECT, INSERT`),
tenant-scoped with non-null `tenant_id`, keyed with it, carrying scope
(TENANT/BRAND/LOCATION plus the id), locale, question form, answer body, author,
and published-at. Entry bodies are tenant business content, not personal data.

**Retrieval.** For each turn: classify the question, resolve scope from the
conversation's brand and location, then fetch — knowledge entries for that scope
and locale, and live platform reads for price, availability, serviceability, or
order state as the classification requires. Retrieved facts are passed as
structured context, not prose, so the model composes rather than recalls.

**Provider port.** `assistant.api.AssistantModelPort` with a normalized request
(system posture, retrieved facts, conversation turns) and response (reply,
grounding citations, refusal signal, token usage). Adapters live behind ADR
0026 installations of a new `ASSISTANT` category.

**Authorization and entitlement.** Answering is gated by the tenant's ADR 0021
entitlement. Knowledge authoring declares a capability under ADR 0025, held by
the roles that already own tenant content. Nothing the assistant does escapes a
capability check by virtue of being automated.

**Audit.** Every turn that quoted a price, asserted availability, or assembled a
cart records an ADR 0027 fact with the retrieved inputs and the entry versions
used — so "why did it say that" has an answer.

**Observability and cost.** Tokens, latency, refusal rate, escalation rate and
spend per tenant land in ADR 0043 facts. Budget ceilings and turn caps use ADR
0033.

**Testing.** Contract tests against a fake model adapter in the ADR 0007 genre —
no live provider in the suite. A grounding test asserts a quoted price equals
the pricing engine's answer for the same inputs. A PII test asserts no name,
phone, or address reaches the port. A tenant-isolation test asserts retrieval
never returns another tenant's entry. The golden-set eval suite runs in CI with
a pass floor.

## Rollout and rollback

Ships behind an entitlement and a per-tenant switch, defaulting off. Stage one is
**read-only answering** — no order assembly — on one pilot tenant, with every
turn escalating on low confidence and an operator watching the inbox. Stage two
enables assembly once the eval suite and audit are trusted. Rollback is switching
the entitlement off: conversations revert to flows and the operator inbox, which
continue to work unchanged, because the assistant was never in their path.

## Implementation checklist

- [ ] `assistant` module skeleton, `AssistantModelPort`, fake adapter
- [ ] Knowledge store, versions, authoring endpoints and capability
- [ ] Operations console: knowledge authoring screen (IA row to be assigned)
- [ ] Retrieval and grounding for price, availability, branches, hours, coverage
- [ ] Customer order-state answering, bound to ADR 0063 proven identity
- [ ] Refusal and escalation into the ADR 0059 inbox, with ADR 0064 presence
- [ ] First provider adapter under an `ASSISTANT` installation category
- [ ] PII egress guard and its test
- [ ] Entitlement gate, budget ceilings, turn caps, cache
- [ ] ADR 0027 audit facts and ADR 0043 usage facts
- [ ] Golden-set eval suite and its CI floor
- [ ] Order assembly through existing cart/checkout, with assistant provenance

## Exit criteria

A customer asks a pilot tenant's Telegram bot what a dish costs and is told the
same number the storefront would charge on that channel at that branch; asks
something the platform cannot answer and is offered a person rather than a
guess; asks where the branches are and is told, from the tenant's own data;
and — at stage two — assembles a basket in chat and completes it through the
ordinary confirmation step, with the resulting order carrying assistant
provenance and the audit trail showing which facts and which knowledge-entry
versions produced each answer.

## References

- ADR 0059 (conversations, flows, operator inbox), ADR 0058 (Telegram channel),
  ADR 0063 (Telegram-native identity)
- ADR 0018 (deterministic pricing), ADR 0016 (catalog and publication),
  ADR 0017 (inventory), ADR 0036/0037 (channels, serviceability, delivery)
- ADR 0019 (cart and checkout), ADR 0039 (operator-assisted ordering)
- ADR 0026 (installations), ADR 0028 (secrets), ADR 0029 (PII),
  ADR 0025 (capabilities), ADR 0027 (audit), ADR 0033 (caching and limits)
- ADR 0021 (entitlements), ADR 0043 (facts), ADR 0064 (operator presence)
