# Turning on the grounded assistant

**Last executed:** never. The assistant ships behind a plan entitlement and a
per-tenant switch, both off, and with no provider key it takes no turns at all.

The assistant (ADR 0069) answers a customer's question in a conversation only
from facts the platform retrieves for that turn -- a price from the same menu path
the storefront quotes, a branch and its hours, the customer's own order -- and
says so and offers a person when there is nothing to answer from. It composes its
replies with a language-model provider the **platform** holds an account with; a
tenant never chooses or pays for it. Today that provider is Anthropic, model
`claude-sonnet-5-5`.

Nothing in this runbook is needed for any other part of the platform to work.

## What only the owner can do

- Hold the provider account and decide what it may spend.
- Put the API key into OpenBao. It is never pasted into chat, a ticket, an
  environment file or this repository (ADR 0028).
- Decide, with counsel, whether sending a customer's pseudonymised question to a
  third-party processor is acceptable under each tenant's own obligations, and
  what a tenant must tell its customers (ADR 0069's open input; the platform
  cannot answer it). The assistant discloses in its first reply of every
  conversation that it is automated and that an external AI service processes the
  question, and asks the customer not to send personal details -- that wording is
  `CustomerWording.disclosure` and is a default, not legal advice. A tenant words
  its own, per language, on the operations console (Settings > Chat assistant >
  What customers are told first; the keys `assistant.disclosure_text_{en,ru,uz}`);
  blank keeps the default and a tenant can replace the sentence but never remove it.

## 1. Store the key

The reference is platform-owned and read-only to the application (the
`provider_assistant` category is deliberately not one a tenant can write):

```sh
bao kv put horecaos/production/provider_assistant/platform/anthropic value=<the key>
```

Then set, in the environment file the deployment reads (`deploy/env.template`
documents both):

```sh
HORECAOS_ASSISTANT_SECRET_REF=horecaos:production:provider_assistant:platform:anthropic
HORECAOS_ASSISTANT_MODEL_ID=claude-sonnet-5-5
```

and restart the application. The endpoint is not configured here: it is the
approved `anthropic-production` row of `integration.provider_environments`
(migration V0512), as for every provider (ADR 0026).

A model swap is a setting, not a deploy of code -- but the model id, `thinking`
and `effort` travel together (the provider ties the last two to the model), and
the two per-token prices must follow the new model's list prices
(`horecaos.assistant.provider.*` in `application.yml`). A stale price understates
spend, which is the figure the ceiling protects.

## 2. Turn it on for one pilot tenant

Both are needed, and neither is on by default.

1. **Plan entitlement** `assistant.answering.enabled`, and `telegram.conversations.enabled`
   (the assistant speaks inside ADR 0059's conversations). Under the pilot's
   meter-only enforcement ADR 0021 says a feature check cannot refuse, so these
   read true for every tenant until enforcement is raised; the next step is what
   holds the assistant dark today.
2. **The switch** `assistant.enabled = true` at the pilot tenant's scope (or one
   brand's). The tenant's owner or administrator does this on the operations
   console (Settings > Chat assistant); the control plane's configuration screen
   can too.

Stage one is **read-only answering**: the assistant assembles no cart and never
completes an order -- the module has no dependency on a cart, a checkout or a
payment, and a test keeps it that way. Watch the operator inbox for the first
days: every refusal and every escalation lands there with its history.

## 3. Cost: what bounds it

- `assistant.monthly_spend_ceiling_usd_cents` (default 2 500, i.e. $25) per
  tenant per calendar month (UTC). It is the platform's to set and is not a key a
  tenant can read or write: the operations console shows the tenant its month's
  spend against it and says to ask support to change it. Reaching it hands customers to a person with
  the stable reason `SPEND_CEILING`; **0 pauses the assistant for that tenant**.
  Concurrent turns can overshoot by the cost of those in flight.
- `assistant.conversation_turn_cap` (default 20 per conversation per day).
- Six turns a minute per conversation and 120 a minute per tenant; excess is
  dropped without a reply.
- The plan allowance `assistant.turns_monthly_included` (metered, never
  refuses while the pilot runs meter-only).

`GET /api/v1/operations/tenants/{tenantId}/assistant/usage` shows the month's
turns by outcome, tokens, spend in millionths of a dollar, the ceiling, and
whether it is reached. It reads the ledger the ceiling is decided from.

## 4. When something is wrong

| Symptom | Likely cause |
|---|---|
| Customers get "I can't answer right now" and a handoff | `PROVIDER_UNAVAILABLE`: the provider is down, the key was rotated and not stored, or the approved environment row is missing. Check `horecaos.assistant.model.failures` by `code`. |
| Same, for one tenant, after a while | `SPEND_CEILING` or `TURN_CAP`. See the usage endpoint and the ledger's `refusal_reason`. |
| The assistant never answers | The switch is off for that brand, the provider reference is empty, or the plan entitlement is refused once enforcement is raised. |
| Customers are told "nobody is online" | Operator presence (ADR 0064) is self-declared and has no heartbeat: nobody has marked themselves ONLINE at any of the brand's branches. |
| An answer cites a wrong price | It cannot: a reply is sent only if every figure is in a cited fact retrieved that turn. If the **fact** is wrong, so is the storefront; fix the price book. |

**Rollback** is switching `assistant.enabled` off (or the entitlement once
enforcement is on): conversations revert to flows and the operator inbox, which
never knew the assistant existed.

## What this runbook does not cover

Order assembly from chat (stage two), the knowledge authoring screen in the
operations console, and a per-tenant provider account: none is built.
